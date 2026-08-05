#!/usr/bin/env node
/**
 * ADB-контрольный мост для разработки.
 *
 * Проблема: на MIUI/HyperOS службу доступности нельзя включить через adb
 * (система сбрасывает её при перезапуске приложения), поэтому удалённое
 * управление из панели helper не работает "из коробки".
 *
 * Решение: скрипт опрашивает через Chrome DevTools Protocol очередь
 * управляющих сообщений, которые панель helper складывает в
 * `window.pultAdbQueue`, и выполняет их через `adb shell input`.
 *
 * Логирование: каждая команда пишется в test_logs/adb-bridge.log с
 * timestamp, id, координатами, результатом и длительностью. Результаты
 * также возвращаются в панель через `window.pultAdbResults`.
 *
 * Запуск:
 *   node scripts/adb-control-bridge.mjs [cdp-port] [page-id] [serial]
 *
 * По умолчанию CDP-порт 9222, page-id определяется автоматически по URL
 * панели helper (`?role=helper`).
 */

import { spawn } from 'node:child_process';
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import WebSocket from '/Users/al_sh/IdeaProjects/babashka/server/node_modules/ws/index.js';

const CDP_PORT = process.argv[2] ? parseInt(process.argv[2], 10) : 9222;
let PAGE_ID = process.argv[3];
const SERIAL = process.argv[4];

const LOG_DIR = '/Users/al_sh/IdeaProjects/babashka/test_logs';
const LOG_FILE = join(LOG_DIR, 'adb-bridge.log');
mkdirSync(LOG_DIR, { recursive: true });

function logLine(obj) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n';
  appendFileSync(LOG_FILE, line);
  console.log(line.trim());
}

function adb(args) {
  const cmd = SERIAL ? ['-s', SERIAL, ...args] : args;
  return new Promise((resolve) => {
    const started = Date.now();
    const child = spawn('adb', cmd, { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => { out += d.toString(); });
    child.stderr.on('data', (d) => { err += d.toString(); });
    child.on('close', (code) => {
      resolve({ ok: code === 0, code, out: out.trim(), err: err.trim(), ms: Date.now() - started });
    });
  });
}

let cachedSize = null;
async function screenSize() {
  if (cachedSize) return cachedSize;
  const res = await adb(['shell', 'dumpsys', 'window', 'displays']);
  const out = res.out;
  const main = out.split(/Display: mDisplayId=0 \(organized\)/)[1] || out;
  const m = main.match(/cur=(\d+)x(\d+)/);
  if (m) cachedSize = { w: parseInt(m[1], 10), h: parseInt(m[2], 10) };
  else {
    const p = main.match(/init=(\d+)x(\d+)/);
    if (p) cachedSize = { w: parseInt(p[1], 10), h: parseInt(p[2], 10) };
    else cachedSize = { w: 720, h: 1600 };
  }
  return cachedSize;
}

async function httpJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

async function findHelperPage() {
  const pages = await httpJson(`http://localhost:${CDP_PORT}/json/list`);
  const page = pages.find((p) => p.url.includes('/panel/') && p.url.includes('role=helper'));
  if (!page) throw new Error('helper panel page not found in CDP');
  return page.id;
}

function cdpSocket(pageId) {
  return new WebSocket(`ws://localhost:${CDP_PORT}/devtools/page/${pageId}`);
}

function cdpCall(ws, method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = Math.floor(Math.random() * 1e9);
    const onMsg = (data) => {
      const msg = JSON.parse(data);
      if (msg.id === id) {
        ws.off('message', onMsg);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      }
    };
    ws.on('message', onMsg);
    ws.send(JSON.stringify({ id, method, params }));
  });
}

// Координаты кнопок BankID (доли экрана, замеры 720×1600) — как в web/public/app.js.
const BANKID_KEYS = {
  '1': [0.167, 0.629], '2': [0.501, 0.629], '3': [0.835, 0.629],
  '4': [0.167, 0.719], '5': [0.501, 0.719], '6': [0.835, 0.719],
  '7': [0.167, 0.809], '8': [0.501, 0.809], '9': [0.835, 0.809],
  '0': [0.501, 0.898],
  'radera': [0.167, 0.898],
  'identifiera': [0.835, 0.898],
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const randomSleep = (minMs, maxMs) => sleep(minMs + Math.random() * (maxMs - minMs));
const jitter = () => (Math.random() - 0.5) * 0.01; // ±0.5% экрана, как человек

async function bankIdTap(size, key) {
  const [x0, y0] = BANKID_KEYS[key];
  const x = Math.round(Math.min(0.99, Math.max(0.01, x0 + jitter())) * size.w);
  const y = Math.round(Math.min(0.99, Math.max(0.01, y0 + jitter())) * size.h);
  const res = await adb(['shell', 'input', 'touchnavigation', 'tap', String(x), String(y)]);
  logLine({ t: 'bankid-tap', key, x, y, ok: res.ok, code: res.code, ms: res.ms, err: res.err });
  return res;
}

// Вся последовательность входа BankID — на стороне моста: страница в фоне
// Chrome душит setTimeout, поэтому тайминги делаем здесь, а не в панели.
async function runBankIdLoginSequence(msg) {
  const size = await screenSize();
  const pin = String(msg.pin || '').replace(/\D/g, '');
  if (!pin) return { ok: false, code: -1, out: '', err: 'empty pin', ms: 0 };
  const started = Date.now();

  // 1. Кнопка подтверждения — на месте «0» PIN-pad.
  let r = await bankIdTap(size, '0');
  if (!r.ok) return { ok: false, code: r.code, out: '', err: 'confirm: ' + r.err, ms: Date.now() - started };
  await randomSleep(1800, 2600); // ждём отрисовку PIN-pad

  // 2. PIN цифра за цифрой с человеческими паузами.
  for (let i = 0; i < pin.length; i++) {
    r = await bankIdTap(size, pin[i]);
    if (!r.ok) return { ok: false, code: r.code, out: '', err: `digit ${pin[i]}: ` + r.err, ms: Date.now() - started };
    if (i < pin.length - 1) await randomSleep(700, 2200);
  }

  // 3. Identifiera.
  if (msg.tapIdentifiera !== false) {
    await randomSleep(800, 2000);
    r = await bankIdTap(size, 'identifiera');
    if (!r.ok) return { ok: false, code: r.code, out: '', err: 'identifiera: ' + r.err, ms: Date.now() - started };
  }
  return { ok: true, code: 0, out: `pin ${pin.length} digits entered`, err: '', ms: Date.now() - started };
}

async function handleMessage(msg) {
  const size = await screenSize();
  const started = Date.now();
  let result = { ok: false, code: -1, out: '', err: 'unknown command', ms: 0 };
  switch (msg.t) {
    case 'bankid-login': {
      result = await runBankIdLoginSequence(msg);
      logLine({ t: 'bankid-login', id: msg.id, ok: result.ok, ms: result.ms, err: result.err, out: result.out });
      break;
    }
    case 'tap': {
      const x = Math.round(msg.x * size.w);
      const y = Math.round(msg.y * size.h);
      // Источник из сообщения: PIN-pad BankID требует touchnavigation,
      // обычные кнопки (подтверждение) — стандартный touchscreen.
      const source = msg.source || 'touchnavigation';
      result = await adb(['shell', 'input', source, 'tap', String(x), String(y)]);
      logLine({ t: 'tap', id: msg.id, x, y, frac: [msg.x, msg.y], source, ok: result.ok, code: result.code, ms: result.ms, err: result.err });
      break;
    }
    case 'swipe': {
      const x1 = Math.round(msg.x1 * size.w);
      const y1 = Math.round(msg.y1 * size.h);
      const x2 = Math.round(msg.x2 * size.w);
      const y2 = Math.round(msg.y2 * size.h);
      const ms = Math.round(msg.ms ?? 250);
      result = await adb(['shell', 'input', 'swipe', String(x1), String(y1), String(x2), String(y2), String(ms)]);
      logLine({ t: 'swipe', id: msg.id, x1, y1, x2, y2, ms, ok: result.ok, code: result.code, err: result.err });
      break;
    }
    case 'nav': {
      const key = { back: 'KEYCODE_BACK', home: 'KEYCODE_HOME', recents: 'KEYCODE_APP_SWITCH' }[msg.action];
      if (!key) { result.err = 'unknown nav action'; break; }
      result = await adb(['shell', 'input', 'keyevent', key]);
      logLine({ t: 'nav', id: msg.id, action: msg.action, ok: result.ok, code: result.code, ms: result.ms, err: result.err });
      break;
    }
    case 'sys': {
      const key = {
        'volume-up': 'KEYCODE_VOLUME_UP',
        'volume-down': 'KEYCODE_VOLUME_DOWN',
        'volume-mute': 'KEYCODE_VOLUME_MUTE',
        notifications: 'KEYCODE_NOTIFICATION',
        'quick-settings': 'KEYCODE_QUICK_SETTINGS',
        lock: 'KEYCODE_POWER',
      }[msg.action];
      if (!key) { result.err = 'unknown sys action'; break; }
      result = await adb(['shell', 'input', 'keyevent', key]);
      logLine({ t: 'sys', id: msg.id, action: msg.action, ok: result.ok, code: result.code, ms: result.ms, err: result.err });
      break;
    }
    case 'shell': {
      // Осторожно: команда выполняется как есть. Только для локального dev-моста.
      const command = String(msg.command || '');
      if (!command.trim()) { result.err = 'empty shell command'; break; }
      result = await adb(['shell', command]);
      logLine({ t: 'shell', id: msg.id, command, ok: result.ok, code: result.code, ms: result.ms, err: result.err });
      break;
    }
    default:
      result.err = 'unknown type';
      logLine({ t: 'unknown', id: msg.id, msg, ok: false, err: result.err });
  }
  return { id: msg.id ?? 0, ok: result.ok, ms: result.ms || (Date.now() - started), err: result.err };
}

async function main() {
  if (!PAGE_ID) PAGE_ID = await findHelperPage();
  logLine({ t: 'start', pageId: PAGE_ID, serial: SERIAL });

  const ws = cdpSocket(PAGE_ID);
  await new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });

  await cdpCall(ws, 'Runtime.enable');
  logLine({ t: 'connected', pageId: PAGE_ID });

  // Последний обработанный id: панель при reload сбрасывает счётчик, поэтому
  // дубликаты фильтруем по паре id+время жизни страницы.
  let lastId = 0;
  let lastRunAt = 0;

  while (ws.readyState === WebSocket.OPEN) {
    try {
      const result = await cdpCall(ws, 'Runtime.evaluate', {
        expression: `
          (() => {
            const q = window.pultAdbQueue;
            if (!q || q.length === 0) return '[]';
            const batch = q.splice(0, q.length);
            return JSON.stringify(batch);
          })()
        `,
        returnByValue: true,
      });
      const batch = JSON.parse(result.result.value);
      if (Array.isArray(batch) && batch.length > 0) {
        const results = [];
        const now = Date.now();
        for (const msg of batch) {
          const id = msg.id ?? 0;
          const isStale = id > 0 && id <= lastId && now - lastRunAt < 3000;
          if (!isStale) {
            lastId = Math.max(lastId, id);
            lastRunAt = now;
            const res = await handleMessage(msg);
            results.push(res);
          } else {
            logLine({ t: 'skip-stale', id, lastId, now, lastRunAt });
            results.push({ id, ok: false, ms: 0, err: 'stale' });
          }
        }
        // Отправляем результаты обратно в панель для отображения прогресса.
        await cdpCall(ws, 'Runtime.evaluate', {
          expression: `window.pultAdbResults = (window.pultAdbResults || []).concat(${JSON.stringify(results)})`,
          returnByValue: true,
        }).catch((e) => logLine({ t: 'ack-error', err: e.message }));
      }
    } catch (e) {
      logLine({ t: 'poll-error', err: e.message });
    }
    await new Promise((r) => setTimeout(r, 50));
  }
}

main().catch((e) => {
  logLine({ t: 'fatal', err: e.message, stack: e.stack });
  process.exit(1);
});
