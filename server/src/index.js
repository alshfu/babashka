import { createServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { readFileSync, realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

import { config as defaultConfig } from './config.js';
import { createRequestHandler } from './http.js';
import { createPush } from './push.js';
import { Hub } from './hub.js';
import { Journal } from './journal.js';
import { createLowLatencyWss } from './lowlat.js';
import { createAgentChannel } from './agent.js';
import { createLinkChannel } from './link.js';
import { createTunnelWss } from './tunnel.js';
import { createUpdateChannel } from './update.js';
import { log } from './log.js';

/**
 * Сборка сервера. Вынесена в функцию, чтобы тесты поднимали его на случайном порту
 * с журналом в памяти и своими таймаутами.
 */
export async function createApp(overrides = {}) {
  const config = { ...defaultConfig, ...overrides };
  const journal = new Journal({
    path: config.journalPath,
    retentionDays: config.journalRetentionDays,
    persist: config.journalPath !== ':memory:',
  });
  await journal.load();

  const push = createPush({ config });
  const hub = new Hub({ config, journal, push });
  const startedAt = Date.now();
  const agentChannel = createAgentChannel({ config });
  // Шлюз BankID-диплинков: статусы с телефона бабушки уходят в /link-канал.
  const linkChannel = createLinkChannel({ config, hub });
  hub.onDeeplinkStatus = (pairId, status) => linkChannel.notifyStatus(pairId, status);
  // Итоги установки обновлений (apk/dex) — туда же, в /link-канал.
  hub.onUpdateStatus = (pairId, status) =>
    linkChannel.notify(pairId, { t: 'update-status', pairId, ...status, at: Date.now() });
  // TCP-туннель приложение ⇄ телефон (шведский IP для банковского приложения).
  const tunnelWss = createTunnelWss({ config });
  // Хостинг apk/dex и push «update-available» телефону бабушки.
  const updateChannel = createUpdateChannel({ config, hub });

  const handler = createRequestHandler({ config, hub, journal, startedAt, agentChannel, updateChannel, push });

  // Обработчик одного WebSocket-подключения. Одинаков для обычного и TLS-листенера,
  // оба кормят один и тот же hub — телефон (ws) и панель (wss) оказываются в одной комнате.
  const wireWss = (wss) => {
    wss.on('connection', (socket, request) => {
      const conn = {
        socket,
        joined: false,
        pairId: null,
        role: null,
        deviceId: null,
        bucket: { start: Date.now(), count: 0 },
        alive: true,
        send(payload) {
          if (socket.readyState === socket.OPEN) socket.send(JSON.stringify(payload));
        },
        close() {
          try {
            socket.close();
          } catch {
            /* сокет уже мёртв */
          }
        },
      };

      socket.on('message', (data, isBinary) => {
        hub.handleRaw(conn, isBinary ? null : data.toString('utf8'));
      });
      socket.on('pong', () => {
        conn.alive = true;
      });
      socket.on('close', () => hub.handleClose(conn));
      socket.on('error', (error) => {
        log.warn(`ws: ${error.message}`, { pairId: conn.pairId, role: conn.role });
      });

      log.debug('ws: connection', { path: request.url });
    });
    return wss;
  };

  // Прототип низколатентной трансляции (H.264 по WebSocket) — общий на оба листенера.
  // Токен нужен релею, чтобы пускать команды управления (тап/свайп/навигация)
  // только от панелей с токеном пары — сама трансляция открыта как раньше.
  const lowlatWss = createLowLatencyWss({ agentToken: config.agentToken });

  // Маршрутизация апгрейдов вручную (noServer): на одном http-сервере живут два WSS —
  // `/ws` (сигналинг) и `/lowlat` (прототип). Если оба вешать через {server, path}, они
  // конфликтуют и чужой путь отбивается с 400. Роутим сами по pathname.
  const routeUpgrade = (mainWss) => (req, socket, head) => {
    let pathname;
    try {
      pathname = new URL(req.url, 'http://x').pathname;
    } catch {
      socket.destroy();
      return;
    }
    if (pathname === '/ws') {
      mainWss.handleUpgrade(req, socket, head, (ws) => mainWss.emit('connection', ws, req));
    } else if (pathname === '/lowlat') {
      lowlatWss.handleUpgrade(req, socket, head, (ws) => lowlatWss.emit('connection', ws, req));
    } else if (pathname === '/agent') {
      agentChannel.wss.handleUpgrade(req, socket, head, (ws) => agentChannel.wss.emit('connection', ws, req));
    } else if (pathname === '/link') {
      linkChannel.wss.handleUpgrade(req, socket, head, (ws) => linkChannel.wss.emit('connection', ws, req));
    } else if (pathname === '/tunnel') {
      tunnelWss.handleUpgrade(req, socket, head, (ws) => tunnelWss.emit('connection', ws, req));
    } else {
      socket.destroy();
    }
  };

  const server = createServer(handler);
  const wss = wireWss(new WebSocketServer({ noServer: true, maxPayload: config.maxMessageBytes * 4 }));
  server.on('upgrade', routeUpgrade(wss));

  // TLS-листенер (https + wss) — только если заданы сертификат и ключ.
  let tlsServer = null;
  let tlsWss = null;
  if (config.tlsCert && config.tlsKey) {
    tlsServer = createHttpsServer(
      { cert: readFileSync(config.tlsCert), key: readFileSync(config.tlsKey) },
      handler,
    );
    tlsWss = wireWss(new WebSocketServer({ noServer: true, maxPayload: config.maxMessageBytes * 4 }));
    tlsServer.on('upgrade', routeUpgrade(tlsWss));
  }

  const allClients = () => [
    ...wss.clients,
    ...(tlsWss ? tlsWss.clients : []),
    ...linkChannel.wss.clients,
    ...tunnelWss.clients,
    ...agentChannel.wss.clients,
    ...lowlatWss.clients,
  ];

  // Мёртвые сокеты в мобильной сети закрываются молча — вычищаем их сами.
  const heartbeat = setInterval(() => {
    for (const socket of allClients()) {
      const conn = socket;
      if (conn.__pultAlive === false) {
        socket.terminate();
        continue;
      }
      conn.__pultAlive = false;
      socket.ping();
      socket.once('pong', () => {
        conn.__pultAlive = true;
      });
    }
  }, config.heartbeatMs);
  heartbeat.unref?.();

  const pruneTimer = setInterval(() => journal.prune(), 6 * 60 * 60 * 1000);
  pruneTimer.unref?.();

  return {
    config,
    hub,
    journal,
    push,
    server,
    wss,
    tlsServer,
    agentChannel,
    listen: async () => {
      const port = await new Promise((done) => {
        server.listen(config.port, config.host, () => {
          log.info('signaling started', { port: server.address().port });
          done(server.address().port);
        });
      });
      if (tlsServer) {
        await new Promise((done) => {
          tlsServer.listen(config.tlsPort, config.host, () => {
            log.info('TLS signaling started (https+wss)', { port: config.tlsPort });
            done();
          });
        });
      }
      return port;
    },
    async close() {
      clearInterval(heartbeat);
      clearInterval(pruneTimer);
      hub.shutdown();
      for (const socket of allClients()) socket.terminate();
      for (const socket of agentChannel.wss.clients) socket.terminate();
      for (const socket of linkChannel.wss.clients) socket.terminate();
      for (const socket of tunnelWss.clients) socket.terminate();
      await new Promise((done) => agentChannel.wss.close(done));
      await new Promise((done) => linkChannel.wss.close(done));
      await new Promise((done) => tunnelWss.close(done));
      await new Promise((done) => wss.close(done));
      if (tlsWss) await new Promise((done) => tlsWss.close(done));
      await new Promise((done) => server.close(done));
      if (tlsServer) await new Promise((done) => tlsServer.close(done));
    },
  };
}

const entry = process.argv[1] ? realpathSync(process.argv[1]) : null;

if (entry && entry === fileURLToPath(import.meta.url)) {
  const app = await createApp();
  await app.listen();

  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, async () => {
      log.info(`stopping on ${signal}`);
      await app.close();
      process.exit(0);
    });
  }
}
