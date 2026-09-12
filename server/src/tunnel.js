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
 * Телефонов (A-app) в паре может быть НЕСКОЛЬКО — тестовый режим «авто-пара на любом
 * телефоне». Слот phone ключуется deviceId (query-параметр): переподключение одного
 * устройства заменяет только его собственный сокет. App-сторона выбирает телефон
 * своим параметром deviceId (шлюз на VPS знает, через какой Redmi выходить); без
 * параметра — первый живой телефон (старое поведение одиночной пары).
 *
 * Потоки принадлежат конкретному app-сокету и конкретному телефону: сервер запоминает
 * владельцев на op=0, кадры phone→app адресует владельцу потока; чужой streamId
 * (на всякий случай) — всем приложениям.
 *
 * Backpressure: потоки TCP-сами по себе медленные (банковские API), релей без дропов;
 * при раздутом буфере получателя кадры просто копятся — для этого трафика приемлемо,
 * а вот видео сюда пускать нельзя (для него /lowlat с дропами).
 */
export function createTunnelWss({ config }) {
  // pairId → { phones: Map<deviceId → socket>, apps: Set<socket>,
  //            streams: Map<streamId → { app, phone }> }
  const pairs = new Map();

  const wss = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024 * 1024 });

  /** Телефон для app-сокета: явно запрошенный deviceId, иначе первый живой. */
  function pickPhone(entry, app) {
    const wanted = app._tunnelDevice;
    if (wanted) return entry.phones.get(wanted) ?? null;
    for (const phone of entry.phones.values()) {
      if (phone.readyState === phone.OPEN) return phone;
    }
    return null;
  }

  wss.on('connection', (socket, request) => {
    const url = new URL(request.url, 'http://x');
    const pairId = url.searchParams.get('pairId') || '';
    const token = url.searchParams.get('token') || '';
    const side = url.searchParams.get('side') || '';
    const deviceId = url.searchParams.get('deviceId') || '';

    if (!config.agentToken || token !== config.agentToken || !pairId || (side !== 'app' && side !== 'phone')) {
      log.warn('tunnel: rejected connection', { pairId: pairId || '—' });
      socket.close(4403, 'forbidden');
      return;
    }

    let entry = pairs.get(pairId);
    if (!entry) {
      entry = { phones: new Map(), apps: new Set(), streams: new Map() };
      pairs.set(pairId, entry);
    }
    if (side === 'phone') {
      // Переподключение того же устройства заменяет только его сокет.
      const previous = entry.phones.get(deviceId);
      if (previous && previous.readyState === previous.OPEN) {
        previous.close(4000, 'replaced');
      }
      entry.phones.set(deviceId, socket);
      // Новый сокет не знает про потоки, открытые через старый: маппинги протухли.
      for (const [id, owner] of entry.streams) {
        if (owner.phone === previous) entry.streams.delete(id);
      }
    } else {
      // Шлюз может попросить конкретный телефон («шведский IP» именно того Redmi).
      socket._tunnelDevice = deviceId || null;
      entry.apps.add(socket);
    }
    log.info('tunnel: join', { pairId, role: side, deviceId: deviceId || '—', apps: entry.apps.size });

    socket.on('message', (data, isBinary) => {
      if (!isBinary) return; // текстовых сообщений в туннеле нет
      // Кадры без заголовка [streamId:4][op:1] (короче 5 байт) ретранслируем как есть:
      // релей прозрачен для байтов, парсинг — лишь для адресации phone→app.
      if (data.length < 5) {
        if (side === 'app') {
          const phone = pickPhone(entry, socket);
          if (phone) phone.send(data, { binary: true });
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
        const phone = pickPhone(entry, socket);
        if (op === 0) {
          if (!phone) return; // телефона нет — шлюз сам закроет поток по таймауту
          entry.streams.set(streamId, { app: socket, phone }); // open — поток наш
          socket._boundPhone = phone;
        }
        if (op === 2 || op === 3) entry.streams.delete(streamId);
        const target = entry.streams.get(streamId)?.phone ?? phone;
        if (target && target.readyState === target.OPEN) target.send(data, { binary: true });
      } else {
        const owner = entry.streams.get(streamId);
        if (owner && owner.phone === socket && owner.app.readyState === owner.app.OPEN) {
          owner.app.send(data, { binary: true });
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
        if (entry.phones.get(deviceId) === socket) entry.phones.delete(deviceId);
        for (const [id, owner] of entry.streams) {
          if (owner.phone === socket) entry.streams.delete(id);
        }
        // Приложения, привязанные к умершему телефону, должны сбросить потоки.
        for (const app of entry.apps) {
          if (app._boundPhone === socket && app.readyState === app.OPEN) app.close(4001, 'peer-left');
        }
      } else {
        entry.apps.delete(socket);
        for (const [id, owner] of entry.streams) {
          if (owner.app === socket) entry.streams.delete(id);
        }
        // Ушло ПОСЛЕДНЕЕ приложение — анонимным телефонам (без deviceId, старый
        // контракт одиночной пары) больше нечего обслуживать: сбрасываем их потоки.
        // Телефоны с deviceId — долгоживущие каналы устройств, чужие приложения
        // их не трогают.
        if (entry.apps.size === 0) {
          const anon = entry.phones.get('');
          if (anon && anon.readyState === anon.OPEN) anon.close(4001, 'peer-left');
        }
      }
      if (entry.phones.size === 0 && entry.apps.size === 0) pairs.delete(pairId);
      log.info('tunnel: leave', { pairId, role: side, deviceId: deviceId || '—', apps: entry.apps.size });
    });
    socket.on('error', (error) => log.warn(`tunnel ws: ${error.message}`, { pairId }));
  });

  return wss;
}
