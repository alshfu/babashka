import { WebSocketServer } from 'ws';

import { log } from './log.js';

/**
 * Канал «VPS ⇄ шлюз-приложение» (Flutter на устройстве под рукой): проброс
 * BankID-диплинков на телефон в Швеции и статусов подписания обратно.
 *
 * Отдельный путь рядом с `/ws`, `/lowlat` и `/agent`: парная криптография сюда не
 * тащится — аутентификация токеном, как у /agent (AGENT_TOKEN). Сессия WebRTC для
 * диплинка не нужна: телефон бабушки держит постоянное сигналинг-подключение.
 *
 * Протокол (текст, JSON):
 *   app → S  {"t":"deeplink","url":"bankid:///…autostarttoken=…"}
 *   S → app  {"t":"deeplink-ack","delivered":true|false}    — квитанция маршрутизации
 *   S → app  {"t":"deeplink-status","ok":bool,"stage":"…","err":"…"} — итог с телефона
 *
 * На пару может быть подключено НЕСКОЛЬКО приложений шлюза одновременно (телефон
 * под рукой + планшет хозяина): диплинк в телефон уходит по любому из них, статусы
 * и update-status рассылаются всем. Раньше слот был один и два шлюза выбивали
 * друг друга каждые ~20 с (постоянный reconnect, стыренные команды).
 */
const MAX_URL = 512;

const isBankIdUrl = (url) =>
  typeof url === 'string' && url.length <= MAX_URL && url.startsWith('bankid:///');

export function createLinkChannel({ config, hub }) {
  const links = new Map();   // pairId → Set<socket>

  const wss = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 });

  wss.on('connection', (socket, request) => {
    const url = new URL(request.url, 'http://x');
    const pairId = url.searchParams.get('pairId') || '';
    const token = url.searchParams.get('token') || '';

    if (!config.agentToken || token !== config.agentToken || !pairId) {
      log.warn('link: rejected connection', { pairId: pairId || '—' });
      socket.close(4403, 'forbidden');
      return;
    }

    let set = links.get(pairId);
    if (!set) {
      set = new Set();
      links.set(pairId, set);
    }
    set.add(socket);
    log.info('link: connected', { pairId, clients: set.size });

    socket.on('message', (data) => {
      let msg;
      try { msg = JSON.parse(data.toString('utf8')); } catch { return; }
      if (msg.t === 'deeplink' && isBankIdUrl(msg.url)) {
        const deviceId = typeof msg.deviceId === 'string' ? msg.deviceId : '';
        const delivered = hub.sendToGrandma(pairId, { t: 'deeplink', url: msg.url, deviceId }, deviceId);
        socket.send(JSON.stringify({ t: 'deeplink-ack', delivered }));
        log.info('link: deeplink', { pairId, deviceId: deviceId || 'default', status: delivered ? 'delivered' : 'offline' });
        return;
      }
      // Управление показом экрана: приложение просит телефон начать/завершить
      // lowlat-трансляцию (H.264 по /lowlat уже есть; это лишь пуск/стоп).
      if (msg.t === 'screencast') {
        const delivered = hub.sendToGrandma(pairId, { t: 'screencast', on: msg.on === true });
        socket.send(JSON.stringify({ t: 'screencast-ack', delivered }));
        log.info('link: screencast', { pairId, status: delivered ? 'delivered' : 'offline' });
        return;
      }
      // Сброс BankID-PIN: на далёком телефоне поднимается экран ввода PIN (+трансляция),
      // вводит человек у телефона. Итог возвращается бабушкой как deeplink-status
      // (stage=pin-saved|pin-cancelled) — отдельный тип статуса не нужен.
      if (msg.t === 'pin-setup') {
        const deviceId = typeof msg.deviceId === 'string' ? msg.deviceId : '';
        const delivered = hub.sendToGrandma(pairId, { t: 'pin-setup' }, deviceId);
        socket.send(JSON.stringify({ t: 'deeplink-ack', delivered }));
        log.info('link: pin-setup', { pairId, deviceId: deviceId || 'default', status: delivered ? 'delivered' : 'offline' });
        return;
      }
      // Удалённая запись PIN: владелец вводит новый код в приложении Б (телефон
      // далеко — вводить на его экране некому), телефон сохраняет его локально.
      // PIN — секрет: в логи пишем ТОЛЬКО факт доставки, само значение нигде не светится.
      if (msg.t === 'pin-set' && typeof msg.pin === 'string' && /^\d{4,8}$/.test(msg.pin)) {
        const deviceId = typeof msg.deviceId === 'string' ? msg.deviceId : '';
        const delivered = hub.sendToGrandma(pairId, { t: 'pin-set', pin: msg.pin }, deviceId);
        socket.send(JSON.stringify({ t: 'deeplink-ack', delivered }));
        log.info('link: pin-set', { pairId, deviceId: deviceId || 'default', status: delivered ? 'delivered' : 'offline' });
        return;
      }
      socket.send(JSON.stringify({ t: 'deeplink-ack', delivered: false, error: 'bad-message' }));
    });

    socket.on('close', () => {
      const set2 = links.get(pairId);
      if (set2) {
        set2.delete(socket);
        if (set2.size === 0) links.delete(pairId);
      }
      log.info('link: disconnected', { pairId });
    });
    socket.on('error', (error) => log.warn(`link ws: ${error.message}`, { pairId }));
  });

  /** Произвольное сообщение всем приложениям пары. Молчит, если все офлайн. */
  function notify(pairId, obj) {
    const set = links.get(pairId);
    if (!set) return;
    for (const socket of set) {
      if (socket.readyState === socket.OPEN) socket.send(JSON.stringify(obj));
    }
  }

  /** Статус подписания с телефона → во все подключённые приложения. */
  function notifyStatus(pairId, status) {
    // Без этой строки сервер «слепой» к итогу на телефоне: доставка диплинка
    // (delivered) ≠ BankID открыт/подписан — различие видно только здесь.
    log.info('link: deeplink-status', { pairId, ...status });
    notify(pairId, { t: 'deeplink-status', ...status });
  }

  return { wss, notify, notifyStatus };
}
