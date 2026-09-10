#!/usr/bin/env node
/**
 * panel-proxy — локальный HTTP CONNECT-прокси на машине помощника: браузер/система
 * помощника выходят в сеть с IP телефона бабушки в Швеции (Swedbank, BankID).
 * Зеркало app/lib/tunnel.dart (Flutter-приложение шлюза), но для ПК.
 *
 * Систему настраивают на прокси 127.0.0.1:8877; каждый CONNECT становится потоком
 * в одном WebSocket к серверу (/tunnel, side=app), сервер релеит кадры телефону,
 * а тот открывает TCP к цели — трафик выходит с его (шведского) IP.
 *
 * Кадр: [streamId:uint32 BE][op:uint8][payload]
 * op: 0=open ("host:port"), 1=data, 2=close, 3=error.
 *
 * Запуск:
 *   node scripts/panel-proxy.mjs --pair <pairId> --server ws://127.0.0.1:8080 \
 *        --token pult-local-test [--port 8877]
 * Env-альтернатива: PAIR_ID VPS_URL AGENT_TOKEN PROXY_PORT.
 */
import net from 'node:net';
import { createRequire } from 'node:module';

// ws ставится только в server/; из scripts/ до него добираемся через createRequire,
// чтобы не дублировать зависимость и не тянуться в node_modules относительным путём.
const require = createRequire(new URL('../server/package.json', import.meta.url));
const { WebSocket } = require('ws');

function arg(name, env, fallback) {
  const at = process.argv.indexOf(`--${name}`);
  if (at >= 0 && process.argv[at + 1]) return process.argv[at + 1];
  return process.env[env] ?? fallback;
}

const PAIR_ID = arg('pair', 'PAIR_ID', '');
const SERVER = arg('server', 'VPS_URL', 'ws://127.0.0.1:8080');
const TOKEN = arg('token', 'AGENT_TOKEN', '');
const PORT = parseInt(arg('port', 'PROXY_PORT', '8877'), 10);

if (!PAIR_ID || !TOKEN) {
  console.error('usage: panel-proxy.mjs --pair <pairId> --server <ws-url> --token <token> [--port 8877]');
  process.exit(2);
}

const OP_OPEN = 0;
const OP_DATA = 1;
const OP_CLOSE = 2;
const OP_ERROR = 3;

const WS_URL = `${SERVER}/tunnel?pairId=${encodeURIComponent(PAIR_ID)}` +
  `&side=app&token=${encodeURIComponent(TOKEN)}`;

let ws = null;
let online = false;
let backoffMs = 1000;
let reconnectTimer = null;
let nextStreamId = 1;
/** @type {Map<number, net.Socket>} */
const clients = new Map();

const logLine = (msg, fields = {}) =>
  console.log(JSON.stringify({ ts: new Date().toISOString(), msg, ...fields }));

// ── WS к серверу ──────────────────────────────────────────────────────────────

function openWs() {
  ws = new WebSocket(WS_URL);
  ws.binaryType = 'nodebuffer';
  ws.on('open', () => {
    backoffMs = 1000;
    online = true;
    logLine('tunnel online', { server: SERVER, pairId: PAIR_ID });
  });
  ws.on('message', (data) => onFrame(data));
  ws.on('close', () => failWs('tunnel ws closed'));
  ws.on('error', (error) => logLine('tunnel ws error', { err: error.message }));
}

/** Обрыв канала: все потоки мертвы (кадры некуда слать), переподключаемся с backoff. */
function failWs(reason) {
  if (!online && clients.size === 0) return scheduleReconnect();
  online = false;
  logLine('tunnel offline', { reason, streams: clients.size });
  for (const client of clients.values()) client.destroy();
  clients.clear();
  scheduleReconnect();
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    openWs();
  }, backoffMs);
  backoffMs = Math.min(backoffMs * 2, 15000);
}

function sendFrame(streamId, op, payload = Buffer.alloc(0)) {
  if (!ws || !online) return;
  const frame = Buffer.allocUnsafe(5 + payload.length);
  frame.writeUInt32BE(streamId, 0);
  frame.writeUInt8(op, 4);
  payload.copy(frame, 5);
  ws.send(frame, { binary: true });
}

function onFrame(frame) {
  if (frame.length < 5) return;
  const streamId = frame.readUInt32BE(0);
  const op = frame.readUInt8(4);
  const payload = frame.subarray(5);
  const client = clients.get(streamId);
  if (op === OP_DATA) {
    if (client) client.write(payload);
    return;
  }
  if (op === OP_CLOSE || op === OP_ERROR) {
    if (op === OP_ERROR) {
      logLine('stream error', { streamId, err: payload.toString('utf8') });
    }
    if (client) {
      clients.delete(streamId);
      // Graceful close: end() сначала сливает накопленный ответ клиенту, потом FIN.
      client.end();
      logLine('disconnect', { streamId });
    }
  }
}

// ── Локальный CONNECT-прокси ──────────────────────────────────────────────────

const server = net.createServer((client) => {
  let headerDone = false;
  let buffer = Buffer.alloc(0);
  let streamId = null;

  const closeStream = () => {
    const id = streamId;
    streamId = null;
    if (id !== null && clients.delete(id)) {
      sendFrame(id, OP_CLOSE);
      logLine('disconnect', { streamId: id });
    }
  };

  client.on('data', (chunk) => {
    if (headerDone) {
      if (streamId !== null) sendFrame(streamId, OP_DATA, chunk);
      return;
    }
    buffer = Buffer.concat([buffer, chunk]);
    const end = buffer.indexOf('\r\n\r\n');
    if (end < 0) return; // ждём конец заголовков
    headerDone = true;
    streamId = beginStream(client, buffer.subarray(0, end + 4), buffer.subarray(end + 4));
  });
  client.on('error', () => { closeStream(); client.destroy(); });
  client.on('close', () => { closeStream(); });
});

/**
 * Разбор заголовка, регистрация потока. Возвращает streamId или null (отказ,
 * ответ клиенту уже отправлен). leftover — байты, приехавшие за заголовком.
 */
function beginStream(client, headerBuf, leftover) {
  const header = headerBuf.toString('utf8');
  const line = header.split('\r\n')[0];

  const connect = /^CONNECT ([^:\s]+):(\d+) HTTP/i.exec(line);
  if (connect) {
    const id = openStream(client, `${connect[1]}:${connect[2]}`);
    if (id === null) return null;
    // Оптимистичное 200: если телефон не достучится до цели, придёт opError
    // и мы закроем сокет — TLS-хендшейк клиента просто оборвётся.
    client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    if (leftover.length > 0) sendFrame(id, OP_DATA, leftover);
    return id;
  }

  // Обычный HTTP с абсолютным URI (GET http://host/path …): переписываем в
  // origin-form и гоним как есть на 80-й порт цели. Ответ придёт по потоку.
  const plain = /^(GET|POST|HEAD|PUT|DELETE|OPTIONS|PATCH) (https?:\/\/[^\s]+) HTTP/i.exec(line);
  if (plain) {
    let target;
    try {
      target = new URL(plain[2]);
    } catch {
      client.write('HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n');
      client.end();
      return null;
    }
    const port = target.port || (target.protocol === 'https:' ? '443' : '80');
    const id = openStream(client, `${target.hostname}:${port}`);
    if (id === null) return null;
    const rewritten = header
      .replace(line, `${plain[1]} ${target.pathname}${target.search} HTTP/1.1`)
      .replace(/^Proxy-Connection:.*\r\n/im, '');
    sendFrame(id, OP_DATA, Buffer.concat([Buffer.from(rewritten, 'utf8'), leftover]));
    return id;
  }

  client.write(`HTTP/1.1 ${online ? '405' : '503'}\r\nContent-Length: 0\r\n\r\n`);
  client.end();
  return null;
}

/** Новый поток через туннель. null — канал не поднят (клиенту уже отвечено 503). */
function openStream(client, targetHostPort) {
  if (!online) {
    client.write('HTTP/1.1 503\r\nContent-Length: 0\r\n\r\n');
    client.end();
    return null;
  }
  const streamId = nextStreamId++;
  clients.set(streamId, client);
  sendFrame(streamId, OP_OPEN, Buffer.from(targetHostPort, 'utf8'));
  logLine('connect', { streamId, target: targetHostPort });
  return streamId;
}

server.listen(PORT, '127.0.0.1', () => {
  logLine('proxy listening', { port: PORT, pairId: PAIR_ID });
  openWs();
});

process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
