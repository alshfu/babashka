#!/usr/bin/env node
/**
 * scrcpy-direct — прямое управление телефоном через scrcpy-server, без браузера.
 *
 * INJECT_TOUCH_EVENT через injectInputEvent от shell — BankID принимает как
 * настоящий тач. Команды приходят по локальному TCP JSON-lines порту.
 *
 * Запуск (фон):  node scripts/scrcpy-direct.mjs [serial] [cmd-port] [scrcpy-port]
 * Команда:       echo '{"t":"tap","x":0.5,"y":0.75,"id":1}' | nc 127.0.0.1 47200
 *
 * Команды: tap {x,y} swipe {x1,y1,x2,y2,ms} bankid-login {pin} texts shot {path}
 *          nav {action} shell {command}  (координаты tap/swipe — доли экрана 0..1)
 */
import { spawn, execFile } from 'node:child_process';
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import net from 'node:net';

const SERIAL = process.argv[2] || 'DQ6TC64DY9PRBE4T';
const CMD_PORT = process.argv[3] ? parseInt(process.argv[3], 10) : 47200;
const LOCAL_PORT = process.argv[4] ? parseInt(process.argv[4], 10) : 27199;
const SCRCPY_SERVER = '/opt/homebrew/share/scrcpy/scrcpy-server';

const LOG_DIR = '/Users/al_sh/IdeaProjects/babashka/test_logs';
const LOG_FILE = join(LOG_DIR, 'scrcpy-direct.log');
mkdirSync(LOG_DIR, { recursive: true });

function logLine(obj) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n';
  appendFileSync(LOG_FILE, line);
  console.log(line.trim());
}

function sh(cmd, args, timeout = 30000) {
  return new Promise((resolve) => {
    const started = Date.now();
    execFile(cmd, args, { timeout, maxBuffer: 16 * 1024 * 1024 }, (err, stdout, stderr) => {
      resolve({ ok: !err, out: String(stdout).trim(), err: String(stderr).trim(), ms: Date.now() - started });
    });
  });
}
const adb = (args) => sh('adb', ['-s', SERIAL, ...args]);

// ── scrcpy-server lifecycle ─────────────────────────────────────────────────

let serverProc = null;

async function startScrcpyServer() {
  const push = await adb(['push', SCRCPY_SERVER, '/data/local/tmp/scrcpy-server']);
  if (!push.ok) throw new Error('push scrcpy-server failed: ' + push.err);

  await adb(['forward', `tcp:${LOCAL_PORT}`, 'localabstract:scrcpy']);

  // scid=-1 → сокет "scrcpy". video=true нужен контроллеру для контекста
  // дисплея — без видеозахвата инъекция касаний молча не работает.
  serverProc = spawn('adb', [
    '-s', SERIAL, 'shell',
    'CLASSPATH=/data/local/tmp/scrcpy-server app_process / com.genymobile.scrcpy.Server 4.0 scid=-1 tunnel_forward=true video=true audio=true control=true cleanup=false log_level=verbose',
  ], { stdio: ['ignore', 'pipe', 'pipe'] });
  serverProc.stdout.on('data', (d) => logLine({ t: 'server-out', text: String(d).trim() }));
  serverProc.stderr.on('data', (d) => logLine({ t: 'server-err', text: String(d).trim() }));
  serverProc.on('exit', (code) => logLine({ t: 'server-exit', code }));

  // scrcpy-server принимает РОВНО одно control-соединение. Пробным коннектом
  // проверять нельзя — он сам станет control-каналом и умрёт при закрытии.
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
  buf.writeUInt16BE(pressure, 22);
  buf.writeInt32BE(actionButton, 24);
  buf.writeInt32BE(buttons, 28);
  return buf;
}

let controlSocket = null;
let screenW = 720, screenH = 1600;

// Порядок сокетов в tunnel_forward фиксирован: video → audio → control.
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
  const sizeRes = await adb(['shell', 'wm', 'size']);
  const m = sizeRes.out.match(/(\d+)x(\d+)/);
  if (m) { screenW = parseInt(m[1], 10); screenH = parseInt(m[2], 10); }

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

// ── BankID ──────────────────────────────────────────────────────────────────

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

// ── наблюдение за экраном (uiautomator: BankID читается, даже когда он чёрный) ──

async function currentTexts() {
  await adb(['shell', 'uiautomator', 'dump', '/sdcard/_d.xml']);
  const pull = await adb(['shell', 'cat', '/sdcard/_d.xml']);
  const texts = [];
  const re = /text="([^"]*)"/g;
  let m;
  while ((m = re.exec(pull.out)) !== null) if (m[1]) texts.push(m[1]);
  return texts;
}

async function waitText(needle, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const texts = await currentTexts();
    if (texts.some((t) => t.includes(needle))) return { ok: true, texts };
    await sleep(700);
  }
  return { ok: false, err: 'timeout waiting text: ' + needle, texts: await currentTexts() };
}

// ── обработчик команд ───────────────────────────────────────────────────────

async function handleMessage(msg) {
  const started = Date.now();
  let result = { ok: false, err: 'unknown command' };
  switch (msg.t) {
    case 'bankid-login': {
      const r = await runBankIdLogin(msg);
      result = { ok: r.ok, err: r.err };
      logLine({ t: 'bankid-login', id: msg.id, ok: r.ok, err: r.err });
      break;
    }
    case 'tap': {
      await scrcpyTap(msg.x * screenW, msg.y * screenH);
      result = { ok: true, err: '' };
      logLine({ t: 'tap', id: msg.id, x: msg.x, y: msg.y, via: 'scrcpy' });
      break;
    }
    case 'swipe': {
      await scrcpySwipe(msg.x1 * screenW, msg.y1 * screenH, msg.x2 * screenW, msg.y2 * screenH, msg.ms ?? 250);
      result = { ok: true, err: '' };
      break;
    }
    case 'texts': {
      const texts = await currentTexts();
      result = { ok: true, err: '', texts: texts.slice(0, 30) };
      break;
    }
    case 'wait-text': {
      const r = await waitText(String(msg.text || ''), msg.timeoutMs ?? 20000);
      result = { ok: r.ok, err: r.err ?? '', texts: (r.texts ?? []).slice(0, 30) };
      break;
    }
    case 'shot': {
      const path = String(msg.path || '/tmp/babashka-shots/shot.png');
      const r = await sh('bash', ['-c', `adb -s ${SERIAL} exec-out screencap -p > ${JSON.stringify(path)}`], 30000);
      result = { ok: r.ok, err: r.err, path };
      break;
    }
    case 'nav': {
      const key = { back: 'KEYCODE_BACK', home: 'KEYCODE_HOME', recents: 'KEYCODE_APP_SWITCH' }[msg.action];
      if (!key) { result.err = 'unknown nav action'; break; }
      const r = await adb(['shell', 'input', 'keyevent', key]);
      result = { ok: r.ok, err: r.err };
      break;
    }
    case 'shell': {
      const r = await adb(['shell', String(msg.command || '')]);
      result = { ok: r.ok, err: r.err, out: (r.out || '').slice(0, 2000) };
      break;
    }
    case 'focus': {
      const r = await adb(['shell', 'dumpsys window | grep mCurrentFocus']);
      result = { ok: r.ok, err: r.err, out: (r.out || '').slice(0, 500) };
      break;
    }
    default:
      result.err = 'unknown type';
  }
  result.ms = Date.now() - started;
  return { id: msg.id ?? 0, ...result };
}

// ── TCP JSON-lines командный порт ───────────────────────────────────────────

async function main() {
  logLine({ t: 'start', serial: SERIAL, cmdPort: CMD_PORT, scrcpyPort: LOCAL_PORT });
  await startScrcpyServer();
  await connectPlain('video');
  await connectPlain('audio');
  await connectControl();
  logLine({ t: 'ready' });

  // Очередь: команды выполняем строго по одной (тапы не должны перемешиваться).
  let chain = Promise.resolve();
  const server = net.createServer((conn) => {
    let buf = '';
    conn.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { conn.write('{"ok":false,"err":"bad json"}\n'); continue; }
        chain = chain.then(() => handleMessage(msg))
          .then((res) => conn.write(JSON.stringify(res) + '\n'))
          .catch((e) => conn.write(JSON.stringify({ id: msg.id ?? 0, ok: false, err: e.message }) + '\n'));
      }
    });
    conn.on('error', () => {});
  });
  server.listen(CMD_PORT, '127.0.0.1', () => logLine({ t: 'cmd-listen', port: CMD_PORT }));
}

process.on('exit', () => { try { serverProc?.kill(); } catch {} });
main().catch((e) => { logLine({ t: 'fatal', err: e.message }); process.exit(1); });
