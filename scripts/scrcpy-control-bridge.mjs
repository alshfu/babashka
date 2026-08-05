#!/usr/bin/env node
/**
 * scrcpy-control-bridge — замена adb-control-bridge для BankID.
 *
 * Проблема: `adb shell input` BankID молча игнорирует (события идут через
 * виртуальное устройство uinput_nav). Клики по окну scrcpy BankID принимает,
 * потому что scrcpy-server инъектирует их через InputManager.injectInputEvent
 * как shell-процесс — это выглядит как настоящий тач.
 *
 * Этот мост поднимает scrcpy-server на телефоне (только control-канал, без
 * видео) и переводит команды панели в его протокол:
 *   - tap/swipe  → SC_CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT (работает на BankID)
 *   - nav/sys/shell → обычный `adb shell` (вне BankID они и так работают)
 *
 * Работает по WiFi-adb — USB-кабель не нужен.
 *
 * Запуск:
 *   node scripts/scrcpy-control-bridge.mjs [cdp-port] [page-id] [serial] [local-port]
 * По умолчанию CDP 9222, page-id ищется сам, serial из adb devices, порт 27199.
 */
import { spawn, execFile } from 'node:child_process';
import { appendFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { join } from 'node:path';
import net from 'node:net';
import WebSocket from '/Users/al_sh/IdeaProjects/babashka/server/node_modules/ws/index.js';

const CDP_PORT = process.argv[2] ? parseInt(process.argv[2], 10) : 9222;
let PAGE_ID = process.argv[3];
const SERIAL = process.argv[4];
const LOCAL_PORT = process.argv[5] ? parseInt(process.argv[5], 10) : 27199;
const SCRCPY_SERVER = '/opt/homebrew/share/scrcpy/scrcpy-server';

const LOG_DIR = '/Users/al_sh/IdeaProjects/babashka/test_logs';
const LOG_FILE = join(LOG_DIR, 'scrcpy-bridge.log');
mkdirSync(LOG_DIR, { recursive: true });

function logLine(obj) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n';
  appendFileSync(LOG_FILE, line);
  console.log(line.trim());
}

function sh(cmd, args, timeout = 30000) {
  return new Promise((resolve) => {
    const started = Date.now();
    execFile(cmd, args, { timeout }, (err, stdout, stderr) => {
      resolve({ ok: !err, out: String(stdout).trim(), err: String(stderr).trim(), ms: Date.now() - started });
    });
  });
}
const adb = (args) => sh('adb', SERIAL ? ['-s', SERIAL, ...args] : args);

// ── scrcpy-server lifecycle ─────────────────────────────────────────────────

let serverProc = null;

async function startScrcpyServer() {
  const push = await adb(['push', SCRCPY_SERVER, '/data/local/tmp/scrcpy-server']);
  if (!push.ok) throw new Error('push scrcpy-server failed: ' + push.err);

  await adb(['forward', `tcp:${LOCAL_PORT}`, 'localabstract:scrcpy']);

  // scid=-1 → сокет "scrcpy" (без суффикса). video=true нужен контроллеру
  // для контекста дисплея — без видеозахвата инъекция касаний молча не работает.
  serverProc = spawn('adb', [
    ...(SERIAL ? ['-s', SERIAL] : []),
    'shell',
    'CLASSPATH=/data/local/tmp/scrcpy-server app_process / com.genymobile.scrcpy.Server 4.0 scid=-1 tunnel_forward=true video=true audio=true control=true cleanup=false log_level=verbose',
  ], { stdio: ['ignore', 'pipe', 'pipe'] });
  serverProc.stdout.on('data', (d) => logLine({ t: 'server-out', text: String(d).trim() }));
  serverProc.stderr.on('data', (d) => logLine({ t: 'server-err', text: String(d).trim() }));
  serverProc.on('exit', (code) => logLine({ t: 'server-exit', code }));

  // scrcpy-server принимает РОВНО одно control-соединение. Проверять доступность
  // отдельным пробным коннектом нельзя — он сам станет control-каналом и умрёт
  // при закрытии. Просто ждём старт сервера, подключение делается в connectControl().
  await new Promise((r) => setTimeout(r, 2500));
}

// ── touch injection по протоколу scrcpy ─────────────────────────────────────

const MSG_INJECT_TOUCH = 2;
const ACTION_DOWN = 0;
const ACTION_UP = 1;
const ACTION_MOVE = 2;
const POINTER_ID_MOUSE = -1n;

function touchMsg(action, pointerId, x, y, w, h, pressure, actionButton, buttons) {
  const buf = Buffer.alloc(32);
  buf.writeUInt8(MSG_INJECT_TOUCH, 0);
  buf.writeUInt8(action, 1);
  buf.writeBigInt64BE(BigInt(pointerId), 2);
  buf.writeInt32BE(x, 10);
  buf.writeInt32BE(y, 14);
  buf.writeUInt16BE(w, 18);
  buf.writeUInt16BE(h, 20);
  buf.writeUInt16BE(pressure, 22);      // 0xffff = 1.0 (down), 0 (up)
  buf.writeInt32BE(actionButton, 24);
  buf.writeInt32BE(buttons, 28);
  return buf;
}

let controlSocket = null;
let videoSocket = null;
let audioSocket = null;
let screenW = 720, screenH = 1600;

// Порядок сокетов в tunnel_forward фиксирован: video → audio → control.
// Даже с выключенным аудио сервер ждёт audio-сокет — иначе наш control-коннект
// будет принят за аудио и контроллер ничего не получит.
async function connectPlain(name) {
  let s;
  for (let i = 0; ; i++) {
    try {
      s = net.connect(LOCAL_PORT, '127.0.0.1');
      await new Promise((resolve, reject) => {
        s.once('connect', resolve);
        s.once('error', reject);
        setTimeout(() => reject(new Error(name + ' connect timeout')), 1000);
      });
      break;
    } catch (e) {
      s?.destroy();
      if (i >= 29) throw new Error(name + ' socket connect failed: ' + e.message);
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  s.on('data', () => {}); // дренируем
  s.on('error', (e) => logLine({ t: name + '-error', err: e.message }));
  logLine({ t: name + '-connected', port: LOCAL_PORT });
  return s;
}

async function connectControl() {
  // Размер экрана — для протокола (position сообщается вместе с габаритами).
  const sizeRes = await adb(['shell', 'wm', 'size']);
  const m = sizeRes.out.match(/(\d+)x(\d+)/);
  if (m) { screenW = parseInt(m[1], 10); screenH = parseInt(m[2], 10); }

  // Подключаемся с ретраями: серверу нужно время подняться, и это ЕДИНСТВЕННОЕ
  // control-соединение — его нельзя терять.
  for (let i = 0; ; i++) {
    try {
      controlSocket = net.connect(LOCAL_PORT, '127.0.0.1');
      await new Promise((resolve, reject) => {
        controlSocket.once('connect', resolve);
        controlSocket.once('error', reject);
        setTimeout(() => reject(new Error('connect timeout')), 1000);
      });
      break;
    } catch (e) {
      controlSocket?.destroy();
      if (i >= 29) throw new Error('control socket connect failed: ' + e.message);
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  controlSocket.on('error', (e) => logLine({ t: 'control-error', err: e.message }));
  // В scrcpy 2.0+ control-сокет ничего не шлёт первым. На всякий случай
  // дренируем возможные стартовые байты, чтобы не засорять состояние.
  controlSocket.on('data', (d) => logLine({ t: 'control-rx', hex: d.toString('hex') }));
  logLine({ t: 'control-connected', port: LOCAL_PORT, screen: [screenW, screenH] });
}

function controlWrite(buf) {
  return new Promise((resolve, reject) => {
    controlSocket.write(buf, (err) => (err ? reject(err) : resolve()));
  });
}

async function scrcpyTap(xPx, yPx) {
  const x = Math.round(xPx), y = Math.round(yPx);
  await controlWrite(touchMsg(ACTION_DOWN, POINTER_ID_MOUSE, x, y, screenW, screenH, 0xffff, 1, 1));
  await controlWrite(touchMsg(ACTION_UP, POINTER_ID_MOUSE, x, y, screenW, screenH, 0, 0, 0));
}

async function scrcpySwipe(x1, y1, x2, y2, ms) {
  const steps = Math.max(2, Math.round(ms / 16));
  await controlWrite(touchMsg(ACTION_DOWN, POINTER_ID_MOUSE, Math.round(x1), Math.round(y1), screenW, screenH, 0xffff, 1, 1));
  for (let i = 1; i <= steps; i++) {
    const x = Math.round(x1 + ((x2 - x1) * i) / steps);
    const y = Math.round(y1 + ((y2 - y1) * i) / steps);
    await controlWrite(touchMsg(ACTION_MOVE, POINTER_ID_MOUSE, x, y, screenW, screenH, 0xffff, 0, 1));
    await new Promise((r) => setTimeout(r, ms / steps));
  }
  await controlWrite(touchMsg(ACTION_UP, POINTER_ID_MOUSE, Math.round(x2), Math.round(y2), screenW, screenH, 0, 0, 0));
}

// ── CDP polling (как в adb-control-bridge) ──────────────────────────────────

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

// Координаты BankID (доли экрана) — как в web/public/app.js.
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
const jitter = () => (Math.random() - 0.5) * 0.01;

async function bankIdKey(key) {
  const [x0, y0] = BANKID_KEYS[key];
  const x = Math.min(0.99, Math.max(0.01, x0 + jitter())) * screenW;
  const y = Math.min(0.99, Math.max(0.01, y0 + jitter())) * screenH;
  await scrcpyTap(x, y);
  logLine({ t: 'bankid-tap', key, x: Math.round(x), y: Math.round(y) });
}

// Вся последовательность входа BankID на стороне моста (тайминги не зависят
// от вкладки браузера): подтверждение → пауза → PIN → Identifiera.
async function runBankIdLogin(msg) {
  const pin = String(msg.pin || '').replace(/\D/g, '');
  if (!pin) return { ok: false, err: 'empty pin' };
  await bankIdKey('0');                    // кнопка подтверждения на месте «0»
  await randomSleep(1800, 2600);           // ждём PIN-pad
  for (let i = 0; i < pin.length; i++) {
    await bankIdKey(pin[i]);
    if (i < pin.length - 1) await randomSleep(700, 2200);
  }
  if (msg.tapIdentifiera !== false) {
    await randomSleep(800, 2000);
    await bankIdKey('identifiera');
  }
  return { ok: true, err: '' };
}

async function handleMessage(msg) {
  const started = Date.now();
  let result = { ok: false, err: 'unknown command', ms: 0 };
  switch (msg.t) {
    case 'bankid-login': {
      const r = await runBankIdLogin(msg);
      result = { ok: r.ok, err: r.err };
      logLine({ t: 'bankid-login', id: msg.id, ok: r.ok, err: r.err });
      break;
    }
    case 'tap': {
      const x = msg.x * screenW, y = msg.y * screenH;
      await scrcpyTap(x, y);
      result = { ok: true, err: '' };
      logLine({ t: 'tap', id: msg.id, x: Math.round(x), y: Math.round(y), via: 'scrcpy' });
      break;
    }
    case 'swipe': {
      await scrcpySwipe(msg.x1 * screenW, msg.y1 * screenH, msg.x2 * screenW, msg.y2 * screenH, msg.ms ?? 250);
      result = { ok: true, err: '' };
      logLine({ t: 'swipe', id: msg.id, via: 'scrcpy' });
      break;
    }
    case 'nav': {
      const key = { back: 'KEYCODE_BACK', home: 'KEYCODE_HOME', recents: 'KEYCODE_APP_SWITCH' }[msg.action];
      if (!key) { result.err = 'unknown nav action'; break; }
      const r = await adb(['shell', 'input', 'keyevent', key]);
      result = { ok: r.ok, err: r.err };
      logLine({ t: 'nav', id: msg.id, action: msg.action, ok: r.ok });
      break;
    }
    case 'sys': {
      const key = {
        'volume-up': 'KEYCODE_VOLUME_UP', 'volume-down': 'KEYCODE_VOLUME_DOWN',
        'volume-mute': 'KEYCODE_VOLUME_MUTE', notifications: 'KEYCODE_NOTIFICATION',
        'quick-settings': 'KEYCODE_QUICK_SETTINGS', lock: 'KEYCODE_POWER',
      }[msg.action];
      if (!key) { result.err = 'unknown sys action'; break; }
      const r = await adb(['shell', 'input', 'keyevent', key]);
      result = { ok: r.ok, err: r.err };
      logLine({ t: 'sys', id: msg.id, action: msg.action, ok: r.ok });
      break;
    }
    case 'shell': {
      const r = await adb(['shell', String(msg.command || '')]);
      result = { ok: r.ok, err: r.err };
      logLine({ t: 'shell', id: msg.id, command: msg.command, ok: r.ok });
      break;
    }
    default:
      result.err = 'unknown type';
      logLine({ t: 'unknown', id: msg.id, msg });
  }
  result.ms = Date.now() - started;
  return { id: msg.id ?? 0, ok: result.ok, ms: result.ms, err: result.err };
}

async function main() {
  if (!PAGE_ID) PAGE_ID = await findHelperPage();
  logLine({ t: 'start', pageId: PAGE_ID, serial: SERIAL, port: LOCAL_PORT });

  await startScrcpyServer();
  // Порядок строго video → audio → control.
  videoSocket = await connectPlain('video');
  audioSocket = await connectPlain('audio');
  await connectControl();

  const ws = new WebSocket(`ws://localhost:${CDP_PORT}/devtools/page/${PAGE_ID}`);
  await new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
  await cdpCall(ws, 'Runtime.enable');
  logLine({ t: 'connected', pageId: PAGE_ID });

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
            results.push(await handleMessage(msg));
          } else {
            results.push({ id, ok: false, ms: 0, err: 'stale' });
          }
        }
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

process.on('exit', () => { try { serverProc?.kill(); } catch {} });
main().catch((e) => { logLine({ t: 'fatal', err: e.message }); process.exit(1); });
