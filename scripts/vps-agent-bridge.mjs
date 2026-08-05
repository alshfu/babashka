#!/usr/bin/env node
/**
 * vps-agent-bridge — мост «VPS ⇄ локальный LAN-стек телефона».
 *
 * Держит исходящее WSS-подключение к сигналинг-серверу (/agent?pairId&token),
 * каждую входящую команду проксирует в scrcpy-lan.mjs (TCP 127.0.0.1:47203),
 * ответ возвращает в WS. Так кнопка панели на VPS выполняет действия на телефоне
 * дома, без USB и без входящих подключений к Mac.
 *
 * Запуск:  node scripts/vps-agent-bridge.mjs <ws-url> <pairId> <token>
 *   напр.: node scripts/vps-agent-bridge.mjs wss://panel.example.com pair1 secret
 *   локально: node scripts/vps-agent-bridge.mjs ws://127.0.0.1:8090 pair1 test123
 * Env-альтернатива: VPS_URL PAIR_ID AGENT_TOKEN LAN_CMD_PORT
 */
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import net from 'node:net';
import WebSocket from '../server/node_modules/ws/index.js';

const VPS_URL = process.argv[2] || process.env.VPS_URL || 'ws://127.0.0.1:8080';
const PAIR_ID = process.argv[3] || process.env.PAIR_ID || 'pair1';
const TOKEN = process.argv[4] || process.env.AGENT_TOKEN || '';
const CMD_PORT = parseInt(process.env.LAN_CMD_PORT || '47203', 10);

const LOG_DIR = '/Users/al_sh/IdeaProjects/babashka/test_logs';
mkdirSync(LOG_DIR, { recursive: true });
function logLine(obj) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n';
  appendFileSync(join(LOG_DIR, 'vps-agent-bridge.log'), line);
  console.log(line.trim());
}

// Одна команда в scrcpy-lan: TCP-соединение, строка запроса → строка ответа.
function lanCmd(cmd, timeoutMs = 120000) {
  return new Promise((resolve) => {
    const s = net.connect(CMD_PORT, '127.0.0.1');
    let buf = '';
    const to = setTimeout(() => { s.destroy(); resolve({ ok: false, err: 'scrcpy-lan timeout' }); }, timeoutMs);
    s.on('connect', () => s.write(JSON.stringify(cmd) + '\n'));
    s.on('data', (d) => {
      buf += d.toString('utf8');
      const nl = buf.indexOf('\n');
      if (nl >= 0) {
        clearTimeout(to);
        s.destroy();
        try { resolve(JSON.parse(buf.slice(0, nl))); }
        catch { resolve({ ok: false, err: 'bad scrcpy-lan json' }); }
      }
    });
    s.on('error', (e) => { clearTimeout(to); resolve({ ok: false, err: 'scrcpy-lan: ' + e.message }); });
  });
}

let ws = null;

function connect() {
  const url = `${VPS_URL}/agent?pairId=${encodeURIComponent(PAIR_ID)}&token=${encodeURIComponent(TOKEN)}`;
  logLine({ t: 'connecting', url: `${VPS_URL}/agent`, pairId: PAIR_ID });
  ws = new WebSocket(url);

  ws.on('open', () => logLine({ t: 'connected', pairId: PAIR_ID }));

  ws.on('message', async (data) => {
    let msg;
    try { msg = JSON.parse(data.toString('utf8')); } catch { return; }
    if (!msg.cmd || typeof msg.cmd !== 'object') return;
    logLine({ t: 'cmd', id: msg.id, cmdT: msg.cmd.t });
    const res = await lanCmd(msg.cmd);
    logLine({ t: 'cmd-done', id: msg.id, ok: res.ok, err: res.err, ms: res.ms });
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify({ id: msg.id, res }));
  });

  ws.on('close', (code, reason) => {
    logLine({ t: 'closed', code, reason: String(reason) });
    setTimeout(connect, 3000);
  });
  ws.on('error', (e) => logLine({ t: 'ws-error', err: e.message }));
}

connect();
