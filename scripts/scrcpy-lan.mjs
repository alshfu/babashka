#!/usr/bin/env node
/**
 * scrcpy-lan — управление телефоном по LAN без USB/ADB.
 *
 * Инъекция касаний: scrcpy-server (app_process shell) на телефоне, слушает
 * localabstract:scrcpy; LanAgent на телефоне релеит его в TCP (relayPort).
 * Сюда (на Mac) сокеты доходят по WiFi — USB-кабель не нужен, host-ADB может
 * быть мёртв. BankID принимает эту инъекцию (проверено USB-прогонами), т.к.
 * события создаёт сам scrcpy-server.
 *
 * Наблюдение (texts/shot/shell/focus/nav) проксируется в LanAgent (agentPort),
 * тоже по LAN — adb не используется вообще.
 *
 * Запуск (фон):  node scripts/scrcpy-lan.mjs [host] [cmd-port] [relay-port] [agent-port]
 * Команда:       echo '{"t":"tap","x":0.5,"y":0.75}' | nc 127.0.0.1 47203
 *            или node scripts/pc-lan.mjs --port 47203 '{"t":"tap","x":0.5,"y":0.75}'
 *
 * Команды: tap {x,y} swipe {x1,y1,x2,y2,ms} bankid-login {pin} texts
 *          swedbank-login {pin} — полный вход Swedbank: Logga in → confirm → PIN → «Konton»
 *          bankid-complete {pin} — универсально: BankID уже открыт (любой сервис)
 *                                  → confirm → PIN → ждать закрытия BankID
 *          wait-text {text,timeoutMs} shot {path} nav {action} shell {command} focus ping
 */
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import net from 'node:net';

const HOST = process.argv[2] || process.env.LAN_HOST || '192.168.39.111';
const CMD_PORT = process.argv[3] ? parseInt(process.argv[3], 10) : 47203;
const RELAY_PORT = process.argv[4] ? parseInt(process.argv[4], 10) : 47202;
const AGENT_PORT = process.argv[5] ? parseInt(process.argv[5], 10) : 47201;

const LOG_DIR = '/Users/al_sh/IdeaProjects/babashka/test_logs';
const LOG_FILE = join(LOG_DIR, 'scrcpy-lan.log');
mkdirSync(LOG_DIR, { recursive: true });

function logLine(obj) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n';
  appendFileSync(LOG_FILE, line);
  console.log(line.trim());
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const randomSleep = (minMs, maxMs) => sleep(minMs + Math.random() * (maxMs - minMs));
const jitter = () => (Math.random() - 0.5) * 0.01;

// ── LanAgent (наблюдение + менеджмент scrcpy-server) ─────────────────────────

function lanAgent(msg, timeoutMs = 35000) {
  return new Promise((resolve) => {
    const s = net.connect(AGENT_PORT, HOST);
    let buf = '';
    const to = setTimeout(() => { s.destroy(); resolve({ ok: false, err: 'agent timeout' }); }, timeoutMs);
    s.on('connect', () => s.write(JSON.stringify(msg) + '\n'));
    s.on('data', (d) => {
      buf += d.toString('utf8');
      const nl = buf.indexOf('\n');
      if (nl >= 0) {
        clearTimeout(to);
        s.destroy();
        try { resolve(JSON.parse(buf.slice(0, nl))); }
        catch { resolve({ ok: false, err: 'bad agent json' }); }
      }
    });
    s.on('error', (e) => { clearTimeout(to); resolve({ ok: false, err: 'agent: ' + e.message }); });
  });
}

// ── scrcpy-server lifecycle (стартует/рестартует через LanAgent, не adb) ─────

const SERVER_CMD = "setsid nohup sh -c 'CLASSPATH=/data/local/tmp/scrcpy-server " +
  'app_process / com.genymobile.scrcpy.Server 4.0 scid=-1 tunnel_forward=true ' +
  'video=true audio=true control=true cleanup=false log_level=error ' +
  "</dev/null >/sdcard/scrcpy-lan.log 2>&1 &'";

async function serverPid() {
  const r = await lanAgent({ t: 'shell', command: "ps -A -o PID,ARGS | grep 'app_process / com.genymobile.scrcpy.Server' | grep -v grep" });
  if (!r.ok || !r.out) return null;
  const m = r.out.match(/^\s*(\d+)/m);
  return m ? parseInt(m[1], 10) : null;
}

let videoSocket = null, audioSocket = null, controlSocket = null;
let controlReady = false;
let screenW = 720, screenH = 1600;

function connectRelay(name) {
  return new Promise((resolve, reject) => {
    const s = net.connect(RELAY_PORT, HOST);
    const to = setTimeout(() => { s.destroy(); reject(new Error(name + ' connect timeout')); }, 4000);
    s.once('connect', () => { clearTimeout(to); resolve(s); });
    s.once('error', (e) => { clearTimeout(to); reject(e); });
  });
}

async function connectSockets() {
  // порядок сокетов в tunnel_forward фиксирован: video → audio → control
  videoSocket = await connectRelay('video');
  let vBytes = 0;
  videoSocket.on('data', (d) => {
    vBytes += d.length;
    if (vBytes >= 1048576) { logLine({ t: 'video-rx', mb: (vBytes / 1048576).toFixed(1) }); vBytes = 0; }
  });
  videoSocket.on('error', () => {});
  videoSocket.on('close', () => logLine({ t: 'video-closed' }));
  logLine({ t: 'video-connected' });

  audioSocket = await connectRelay('audio');
  let aBytes = 0;
  audioSocket.on('data', (d) => {
    aBytes += d.length;
    if (aBytes >= 1048576) { logLine({ t: 'audio-rx', mb: (aBytes / 1048576).toFixed(1) }); aBytes = 0; }
  });
  audioSocket.on('error', () => {});
  audioSocket.on('close', () => logLine({ t: 'audio-closed' }));
  logLine({ t: 'audio-connected' });

  controlSocket = await connectRelay('control');
  controlSocket.on('data', (d) => logLine({ t: 'control-rx', hex: d.toString('hex') }));
  controlSocket.on('error', (e) => logLine({ t: 'control-error', err: e.message }));
  controlSocket.on('close', () => {
    logLine({ t: 'control-closed' });
    controlReady = false;
    scheduleReconnect();
  });
  controlReady = true;
  logLine({ t: 'control-connected', screen: [screenW, screenH] });
}

let reconnecting = false;
function scheduleReconnect() {
  if (reconnecting) return;
  reconnecting = true;
  (async () => {
    while (!controlReady) {
      await sleep(3000);
      try { await ensureServer(); } catch (e) { logLine({ t: 'reconnect-fail', err: e.message }); }
    }
    reconnecting = false;
    logLine({ t: 'reconnected' });
  })();
}

async function ensureServer() {
  const pid = await serverPid();
  if (pid != null) {
    try {
      await connectSockets();
      return; // сервер жив, сокеты поднялись
    } catch (e) {
      logLine({ t: 'stale-server', pid, err: e.message });
      await lanAgent({ t: 'shell', command: 'kill ' + pid });
      await sleep(1000);
    }
  }
  const r = await lanAgent({ t: 'shell', command: SERVER_CMD });
  logLine({ t: 'server-spawn', ok: r.ok, err: r.err });
  await sleep(3000);
  for (let i = 0; ; i++) {
    try { await connectSockets(); return; }
    catch (e) {
      if (i >= 19) throw new Error('connect after spawn failed: ' + e.message);
      await sleep(1000);
    }
  }
}

// ── touch injection по протоколу scrcpy (как в scrcpy-direct.mjs) ───────────

const MSG_INJECT_TOUCH = 2;
const ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2;
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

function controlWrite(buf) {
  if (!controlReady || !controlSocket) throw new Error('control not connected');
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
    await sleep(ms / steps);
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

// Универсальное завершение BankID (для любого сервиса: Swedbank, Skatteverket,
// företagskonto): вход уже инициирован в приложении сервиса, BankID-экран открыт.
// Будим экран, подтверждаем («Identifiera/Signera med säkerhetskod» — одна позиция),
// вводим PIN, ждём закрытия BankID. Диалог отмены/ошибки = ok:false.
async function runBankIdComplete(msg) {
  const cut = (a) => (a || []).slice(0, 30);
  await lanAgent({ t: 'shell', command: 'input keyevent KEYCODE_WAKEUP' }, 10000);
  await sleep(1200);
  const confirm = await lanAgent({ t: 'wait-text', text: 'med säkerhetskod', timeoutMs: 10000 }, 25000);
  if (!confirm.ok) {
    return { ok: false, err: 'экран BankID не найден — сначала начни вход в самом сервисе', texts: cut(confirm.texts) };
  }
  const r = await runBankIdLogin(msg);   // confirm → PIN → identifiera/signera
  if (!r.ok) return r;
  const deadline = Date.now() + 25000;
  let last = [];
  while (Date.now() < deadline) {
    await sleep(1500);
    const t = await lanAgent({ t: 'texts' }, 20000);
    const joined = (t.texts || []).join(' ');
    last = cut(t.texts);
    if (/avbruten|gick fel|inte längre/.test(joined)) {
      return { ok: false, err: 'BankID отменил операцию', texts: last };
    }
    const f = await lanAgent({ t: 'focus' }, 15000);
    if (f.ok && f.out && !f.out.includes('com.bankid.bus')) {
      return { ok: true, err: '', texts: last };   // BankID закрылся — операция принята
    }
  }
  return { ok: false, err: 'таймаут ожидания результата BankID', texts: last };
}

// Полный вход Swedbank с любого экрана: будит экран, при необходимости открывает
// Swedbank, «Logga in» → BankID confirm → PIN → проверка «Konton». Уже залогинен — сразу ok.
async function runSwedbankLogin(msg) {
  const cut = (a) => (a || []).slice(0, 30);
  // Экран мог уснуть: тачи в погашенный экран теряются, а uiautomator при этом
  // отдаёт старую иерархию (снимок чёрный) — сначала будим.
  await lanAgent({ t: 'shell', command: 'input keyevent KEYCODE_WAKEUP' }, 10000);
  await sleep(1200);
  let t = await lanAgent({ t: 'texts' }, 20000);
  if (t.ok && (t.texts || []).some((x) => x.includes('Konton'))) {
    return { ok: true, err: '', already: true, texts: cut(t.texts) };
  }
  // Шторка «Fler alternativ» перехватывает тапы — закрываем.
  if (t.ok && (t.texts || []).some((x) => x.includes('Säkerhetsdosa'))) {
    await scrcpyTap(0.5 * screenW, 0.35 * screenH);
    await sleep(1200);
    t = await lanAgent({ t: 'texts' }, 20000);
  }
  // Swedbank не на экране — запускаем его.
  if (!t.ok || !(t.texts || []).some((x) => x.includes('Logga in'))) {
    await lanAgent({ t: 'shell', command: 'monkey -p se.swedbank.mobil -c android.intent.category.LAUNCHER 1' }, 20000);
    const w0 = await lanAgent({ t: 'wait-text', text: 'Logga in', timeoutMs: 15000 }, 30000);
    if (!w0.ok) return { ok: false, err: 'экран «Logga in» не появился', texts: cut(w0.texts) };
  }
  await scrcpyTap(0.5 * screenW, 0.75125 * screenH);   // «Logga in»
  const confirm = await lanAgent({ t: 'wait-text', text: 'Identifiera med säkerhetskod', timeoutMs: 20000 }, 35000);
  if (!confirm.ok) return { ok: false, err: 'BankID confirm не появился', texts: cut(confirm.texts) };
  const r = await runBankIdLogin(msg);
  if (!r.ok) return r;
  await sleep(4000);
  const konton = await lanAgent({ t: 'wait-text', text: 'Konton', timeoutMs: 25000 }, 40000);
  if (!konton.ok) return { ok: false, err: '«Konton» не появилось после PIN', texts: cut(konton.texts) };
  return { ok: true, err: '', texts: cut(konton.texts) };
}

// ── обработчик команд ───────────────────────────────────────────────────────

async function handleMessage(msg) {
  const started = Date.now();
  let result = { ok: false, err: 'unknown command' };
  switch (msg.t) {
    case 'ping':
      result = { ok: true, err: '', control: controlReady };
      break;
    case 'bankid-login': {
      const r = await runBankIdLogin(msg);
      result = { ok: r.ok, err: r.err };
      logLine({ t: 'bankid-login', id: msg.id, ok: r.ok, err: r.err });
      break;
    }
    case 'swedbank-login': {
      const r = await runSwedbankLogin(msg);
      result = r;
      logLine({ t: 'swedbank-login', id: msg.id, ok: r.ok, err: r.err });
      break;
    }
    case 'bankid-complete': {
      const r = await runBankIdComplete(msg);
      result = r;
      logLine({ t: 'bankid-complete', id: msg.id, ok: r.ok, err: r.err });
      break;
    }
    case 'tap': {
      await scrcpyTap(msg.x * screenW, msg.y * screenH);
      result = { ok: true, err: '' };
      logLine({ t: 'tap', id: msg.id, x: msg.x, y: msg.y, via: 'scrcpy-lan' });
      break;
    }
    case 'swipe': {
      await scrcpySwipe(msg.x1 * screenW, msg.y1 * screenH, msg.x2 * screenW, msg.y2 * screenH, msg.ms ?? 250);
      result = { ok: true, err: '' };
      break;
    }
    case 'texts':
    case 'wait-text':
    case 'shell':
    case 'focus':
    case 'nav': {
      const r = await lanAgent(msg, (msg.timeoutMs ?? 20000) + 15000);
      result = r;
      break;
    }
    case 'shot': {
      const r = await lanAgent({ t: 'shot' }, 45000);
      if (r.ok && r.png_b64) {
        const path = String(msg.path || '/tmp/babashka-shots/shot.png');
        mkdirSync(dirname(path), { recursive: true });
        writeFileSync(path, Buffer.from(r.png_b64, 'base64'));
        result = { ok: true, err: '', path };
      } else {
        result = { ok: false, err: r.err || 'shot failed' };
      }
      break;
    }
    default:
      result.err = 'unknown type';
  }
  result.ms = Date.now() - started;
  return { id: msg.id ?? 0, ...result };
}

// ── main ────────────────────────────────────────────────────────────────────

async function main() {
  logLine({ t: 'start', host: HOST, cmdPort: CMD_PORT, relayPort: RELAY_PORT, agentPort: AGENT_PORT });

  // размер экрана — у LanAgent (adb не трогаем)
  const size = await lanAgent({ t: 'shell', command: 'wm size' });
  const m = (size.out || '').match(/(\d+)x(\d+)/);
  if (m) { screenW = parseInt(m[1], 10); screenH = parseInt(m[2], 10); }

  while (!controlReady) {
    try { await ensureServer(); }
    catch (e) { logLine({ t: 'ensure-fail', err: e.message }); await sleep(5000); }
  }
  logLine({ t: 'ready' });

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

main().catch((e) => { logLine({ t: 'fatal', err: e.message }); process.exit(1); });
