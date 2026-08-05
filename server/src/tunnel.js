import { WebSocketServer } from 'ws';

import { log } from './log.js';

/**
 * TCP-туннель «приложение шлюза ⇄ телефон» поверх WebSocket: Swedbank на устройстве
 * под рукой выходит в сеть с IP телефона в Швеции. Сервер лишь сращивает два сокета
 * внутри пары (side=app | side=phone) и пересылает бинарные кадры как есть — формат
 * кадра (мультиплексирование потоков) придуман концами, серверу он неинтересен:
 *   [streamId:uint32 BE][op:uint8][payload]
 *   op: 0=open ("host:port"), 1=data, 2=close, 3=error
 *
 * Аутентификация — токеном, как у /agent и /link (AGENT_TOKEN). Пара идентифицируется
 * pairId. На пару — по одному сокету каждой стороны: переподключение заменяет старый.
 *
 * Backpressure: потоки TCP-сами по себе медленные (банковские API), релей без дропов;
 * при раздутом буфере получателя кадры просто копятся — для этого трафика приемлемо,
 * а вот видео сюда пускать нельзя (для него /lowlat с дропами).
 */
export function createTunnelWss({ config }) {
  const pairs = new Map(); // pairId → { app?: socket, phone?: socket }

  const wss = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024 * 1024 });

  wss.on('connection', (socket, request) => {
    const url = new URL(request.url, 'http://x');
    const pairId = url.searchParams.get('pairId') || '';
    const token = url.searchParams.get('token') || '';
    const side = url.searchParams.get('side') || '';

    if (!config.agentToken || token !== config.agentToken || !pairId || (side !== 'app' && side !== 'phone')) {
      log.warn('tunnel: rejected connection', { pairId: pairId || '—' });
      socket.close(4403, 'forbidden');
      return;
    }

    let entry = pairs.get(pairId);
    if (!entry) {
      entry = {};
      pairs.set(pairId, entry);
    }
    const prev = entry[side];
    if (prev && prev.readyState === prev.OPEN) prev.close(4000, 'replaced');
    entry[side] = socket;
    log.info('tunnel: join', { pairId, role: side });

    socket.on('message', (data, isBinary) => {
      if (!isBinary) return; // текстовых сообщений в туннеле нет
      const peer = entry[side === 'app' ? 'phone' : 'app'];
      if (peer && peer.readyState === peer.OPEN) peer.send(data, { binary: true });
    });

    socket.on('close', () => {
      if (entry[side] === socket) delete entry[side];
      // Вторая сторона должна сбросить все потоки: кадры больше некуда слать.
      const peer = entry[side === 'app' ? 'phone' : 'app'];
      if (peer && peer.readyState === peer.OPEN) peer.close(4001, 'peer-left');
      if (!entry.app && !entry.phone) pairs.delete(pairId);
      log.info('tunnel: leave', { pairId, role: side });
    });
    socket.on('error', (error) => log.warn(`tunnel ws: ${error.message}`, { pairId }));
  });

  return wss;
}
