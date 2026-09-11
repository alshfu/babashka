import { WebSocketServer } from 'ws';

import { log } from './log.js';

/**
 * TCP-туннель «приложение шлюза ⇄ телефон» поверх WebSocket: Swedbank на устройстве
 * под рукой выходит в сеть с IP телефона в Швеции. Сервер лишь сращивает сокеты
 * внутри пары (side=app | side=phone) и пересылает бинарные кадры как есть — формат
 * кадра (мультиплексирование потоков) придуман концами, серверу он неинтересен:
 *   [streamId:uint32 BE][op:uint8][payload]
 *   op: 0=open ("host:port"), 1=data, 2=close, 3=error
 *
 * Аутентификация — токеном, как у /agent и /link (AGENT_TOKEN). Пара идентифицируется
 * pairId.
 *
 * Сторон может быть несколько: phone — одна (A-app на телефоне в Швеции), app — до
 * нескольких (шлюз на телефоне под рукой + планшет хозяина). Потоки принадлежат
 * конкретному app-сокету: сервер запоминает, кто открыл streamId (op=0), и кадры
 * phone→app адресует владельцу потока; чужой streamId (на всякий случай) — всем.
 * Раньше слот app был один: два шлюза выбивали друг друга, и туннель «не работал»
 * ровно на половине попыток.
 *
 * Backpressure: потоки TCP-сами по себе медленные (банковские API), релей без дропов;
 * при раздутом буфере получателя кадры просто копятся — для этого трафика приемлемо,
 * а вот видео сюда пускать нельзя (для него /lowlat с дропами).
 */
export function createTunnelWss({ config }) {
  // pairId → { phone?: socket, apps: Set<socket>, streams: Map<streamId → appSocket> }
  const pairs = new Map();

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
      entry = { phone: null, apps: new Set(), streams: new Map() };
      pairs.set(pairId, entry);
    }
    if (side === 'phone') {
      // Телефон в Швеции один: переподключение заменяет старый сокет.
      if (entry.phone && entry.phone.readyState === entry.phone.OPEN) {
        entry.phone.close(4000, 'replaced');
      }
      entry.phone = socket;
      // Новый phone не знает про открытые потоки: старые маппинги протухли вместе
      // с прошлым сокетом — сбрасываем, чтобы кадры не уходили в никуда.
      entry.streams.clear();
    } else {
      entry.apps.add(socket);
    }
    log.info('tunnel: join', { pairId, role: side, apps: entry.apps.size });

    socket.on('message', (data, isBinary) => {
      if (!isBinary) return; // текстовых сообщений в туннеле нет
      // Кадры без заголовка [streamId:4][op:1] (короче 5 байт) ретранслируем как есть:
      // релей прозрачен для байтов, парсинг — лишь для адресации phone→app.
      if (data.length < 5) {
        if (side === 'app') {
          const phone = entry.phone;
          if (phone && phone.readyState === phone.OPEN) phone.send(data, { binary: true });
        } else {
          for (const app of entry.apps) {
            if (app.readyState === app.OPEN) app.send(data, { binary: true });
          }
        }
        return;
      }
      const streamId = data.readUInt32BE(0);
      const op = data[4];
      if (side === 'app') {
        if (op === 0) entry.streams.set(streamId, socket); // open — поток наш
        if (op === 2 || op === 3) entry.streams.delete(streamId);
        const phone = entry.phone;
        if (phone && phone.readyState === phone.OPEN) phone.send(data, { binary: true });
      } else {
        const owner = entry.streams.get(streamId);
        if (owner && owner.readyState === owner.OPEN) {
          owner.send(data, { binary: true });
        } else {
          // Не нашли владельца (устаревший поток) — разошлём всем приложениям:
          // концы сами отбросят чужие streamId.
          for (const app of entry.apps) {
            if (app.readyState === app.OPEN) app.send(data, { binary: true });
          }
        }
      }
    });

    socket.on('close', () => {
      if (side === 'phone') {
        if (entry.phone === socket) entry.phone = null;
        entry.streams.clear();
        // Все приложения должны сбросить потоки: кадры больше некуда слать.
        for (const app of entry.apps) {
          if (app.readyState === app.OPEN) app.close(4001, 'peer-left');
        }
      } else {
        entry.apps.delete(socket);
        for (const [id, owner] of entry.streams) {
          if (owner === socket) entry.streams.delete(id);
        }
        // Ушло ПОСЛЕДНЕЕ приложение — телефону больше нечего обслуживать:
        // сбрасываем его потоки (старый контракт туннеля).
        if (entry.apps.size === 0 && entry.phone && entry.phone.readyState === entry.phone.OPEN) {
          entry.phone.close(4001, 'peer-left');
        }
      }
      if (!entry.phone && entry.apps.size === 0) pairs.delete(pairId);
      log.info('tunnel: leave', { pairId, role: side, apps: entry.apps.size });
    });
    socket.on('error', (error) => log.warn(`tunnel ws: ${error.message}`, { pairId }));
  });

  return wss;
}
