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
 */
const MAX_URL = 512;

const isBankIdUrl = (url) =>
  typeof url === 'string' && url.length <= MAX_URL && url.startsWith('bankid:///');

export function createLinkChannel({ config, hub }) {
  const links = new Map();   // pairId → socket

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

    const prev = links.get(pairId);
    if (prev && prev.readyState === prev.OPEN) prev.close(4000, 'replaced');
    links.set(pairId, socket);
    log.info('link: connected', { pairId });

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
      socket.send(JSON.stringify({ t: 'deeplink-ack', delivered: false, error: 'bad-message' }));
    });

    socket.on('close', () => {
      if (links.get(pairId) === socket) links.delete(pairId);
      log.info('link: disconnected', { pairId });
    });
    socket.on('error', (error) => log.warn(`link ws: ${error.message}`, { pairId }));
  });

  /** Произвольное сообщение приложению пары. Молчит, если приложение офлайн. */
  function notify(pairId, obj) {
    const socket = links.get(pairId);
    if (!socket || socket.readyState !== socket.OPEN) return;
    socket.send(JSON.stringify(obj));
  }

  /** Статус подписания с телефона → в приложение. */
  function notifyStatus(pairId, status) {
    notify(pairId, { t: 'deeplink-status', ...status });
  }

  return { wss, notify, notifyStatus };
}
