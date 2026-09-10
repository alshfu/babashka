import { WebSocketServer } from 'ws';

import { log } from './log.js';

/**
 * Прототип низколатентной трансляции экрана (scrcpy-подход): H.264 из MediaCodec гоним
 * прямо байтами по WebSocket, минуя jitter buffer и congestion control WebRTC. Браузер
 * декодирует через WebCodecs. Управление (тапы) идёт текстом по тому же сокету.
 *
 * Это ОТДЕЛЬНЫЙ путь рядом с `/ws`: сигналинг и WebRTC не трогаем. Здесь нет пар, MAC и
 * журналов — только релей «устройство ⇄ панель» в пределах комнаты. Для демо/замера
 * задержки; в бой пойдёт с той же проверкой пары, что и WebRTC.
 *
 * Протокол: подключаемся на `/lowlat?room=<id>&role=<device|panel>`. Всё, что прислал один
 * участник комнаты (бинарь H.264 или текст-команда), пересылаем другому как есть.
 *
 * Backpressure: если исходящий буфер получателя раздут (его канал не успевает за
 * потоком), дельта-кадры выбрасываем до ближайшего keyframe, а не копим очередь.
 * Без этого задержка растёт бесконечно: релей превращается в накопитель, и зритель
 * смотрит «прошлое» с нарастающим отставанием. Keyframe и codec config всегда проходят.
 */
// Потолок исходящего буфера на сокет: ~1.3 с при 3 Мбит/с. Больше — уже не «live».
const MAX_BUFFERED_BYTES = 512 * 1024;

const FRAME_CONFIG = 0;
const FRAME_KEY = 1;
const FRAME_DELTA = 2;

export function createLowLatencyWss({ agentToken } = {}) {
  // Комната = до двух сокетов. Первый ключ — идентификатор комнаты из query.
  const rooms = new Map();
  // Последние config + keyframe по комнате: отдаём их панели сразу при входе.
  // Без этого поздний зритель ждёт первый СВЕЖИЙ кейфрейм, а энкодер на телефоне
  // работает по изменению экрана — на статичном экране панель сидела бы в «тишине»
  // бесконечно. Дельты после реплея пропускаем до первого живого кейфрейма:
  // они ссылаются на кадры, которых панель не видела.
  const lastFrames = new Map();

  // noServer: апгрейды маршрутизирует index.js (иначе несколько WSS на одном http-сервере
  // конфликтуют — чужой путь отбивается с 400 до того, как дойдёт до нужного WSS).
  const wss = new WebSocketServer({ noServer: true, maxPayload: 8 * 1024 * 1024 });
  wss.on('connection', (socket, request) => {
      const url = new URL(request.url, 'http://x');
      const room = url.searchParams.get('room') || 'demo';
      const role = url.searchParams.get('role') || 'peer';
      // Токен панели: просмотр открыт как раньше, но команды управления
      // (тап/свайп/навигация) релей пропускает только с токеном пары —
      // иначе любой, кто угадал имя комнаты, получил бы пульт над телефоном.
      const token = url.searchParams.get('token') || '';

      let peers = rooms.get(room);
      if (!peers) {
        peers = new Set();
        rooms.set(room, peers);
      }
      peers.add(socket);
      socket._lowlatRole = role;
      socket._lowlatToken = token;
      log.info('lowlat: join', { room, role, size: peers.size });

      // Поздний зритель: мгновенно отдаём последние config+keyframe из кэша.
      if (role !== 'device') {
        const cached = lastFrames.get(room);
        if (cached?.key) {
          if (cached.config) socket.send(cached.config, { binary: true });
          socket.send(cached.key, { binary: true });
          socket._lowlatDrop = { dropping: true, dropped: 0 };
          log.info('lowlat: replayed cached keyframe', { room });
        }
      }

      // Пересылаем всё, что прислали, ДРУГИМ участникам комнаты — как есть (бинарь остаётся
      // бинарём, иначе H.264 испортится при перекодировке в utf8).
      socket.on('message', (data, isBinary) => {
        // Команды управления от панели — только с токеном пары. Без токена
        // сокет может смотреть трансляцию, но пультом не является.
        if (!isBinary && socket._lowlatRole !== 'device' && agentToken) {
          const controlActions = { tap: 1, swipe: 1, nav: 1 };
          let action = null;
          try { action = JSON.parse(data.toString('utf8'))?.t; } catch { /* не JSON — релей как есть */ }
          if (action && controlActions[action] && socket._lowlatToken !== agentToken) {
            log.warn('lowlat: control rejected (no token)', { room, action });
            return;
          }
        }
        // Кэшируем config/keyframe от устройства для поздних зрителей.
        if (isBinary && socket._lowlatRole === 'device' && data[0] !== FRAME_DELTA) {
          const cached = lastFrames.get(room) ?? { config: null, key: null };
          if (data[0] === FRAME_CONFIG) cached.config = Buffer.from(data);
          else cached.key = Buffer.from(data);
          lastFrames.set(room, cached);
        }
        for (const other of peers) {
          if (other === socket || other.readyState !== other.OPEN) continue;
          if (isBinary) {
            const state = (other._lowlatDrop ??= { dropping: false, dropped: 0 });
            if (state.dropping) {
              // Ждём кейфрейм: без него декодер не восстановится после пропуска дельт.
              if (data[0] === FRAME_KEY || data[0] === FRAME_CONFIG) {
                state.dropping = false;
                log.info('lowlat: resumed after drop', { room, dropped: state.dropped });
              } else {
                state.dropped++;
                continue;
              }
            } else if (data[0] === FRAME_DELTA && other.bufferedAmount > MAX_BUFFERED_BYTES) {
              state.dropping = true;
              state.dropped++;
              log.info('lowlat: backpressure — drop to keyframe', {
                room, buffered: other.bufferedAmount,
              });
              continue;
            }
          }
          other.send(data, { binary: isBinary });
        }
      });

      socket.on('close', () => {
        peers.delete(socket);
        // Сообщим оставшемуся, что пир ушёл (чтобы панель показала «устройство отключилось»).
        for (const other of peers) {
          if (other.readyState === other.OPEN) other.send(JSON.stringify({ t: 'peer-left', role }));
        }
        if (peers.size === 0) {
          rooms.delete(room);
          lastFrames.delete(room);
        }
        log.info('lowlat: leave', { room, role });
      });
      socket.on('error', (error) => log.warn(`lowlat ws: ${error.message}`, { room, role }));
    });

  return wss;
}
