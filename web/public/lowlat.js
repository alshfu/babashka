// Прототип низколатентной панели: H.264 (annex-b) по WebSocket → WebCodecs → canvas.
// Рядом с основной панелью, для замера задержки управления. См. server/src/lowlat.js.
//
// Протокол кадра (бинарное сообщение): [type:1][ptsMicros:8 BE][annex-b payload].
//   type 0 = codec config (SPS/PPS), 1 = keyframe (SPS+PPS+IDR), 2 = delta.
// Команды панель→устройство идут ТЕКСТОМ: {"t":"tap","x":..,"y":..} и т.п.

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const room = params.get('room') || 'demo';

const canvas = $('screen');
// desynchronized: просим низколатентный путь отрисовки (меньше буферизации композитора).
const ctx = canvas.getContext('2d', { desynchronized: true, alpha: false });
const hud = $('hud');

let decoder = null;
let configured = false;
let frames = 0;
let lastFpsAt = performance.now();
let fps = 0;
let lastFrameAt = 0;
let vw = 0;
let vh = 0;
// Если декодер не успевает (слабый канал/CPU), дельта-кадры выбрасываем до кейфрейма —
// иначе очередь декодирования превращается в нарастающее отставание от live.
let dropping = false;
let droppedFrames = 0;
const MAX_DECODE_QUEUE = 5;

function log(msg) {
  hud.textContent = msg;
}

// ── WebCodecs ────────────────────────────────────────────────────────────────

// Строка кодека из SPS: avc1.<profile_idc><constraint_flags><level_idc> (hex).
function codecFromSps(annexb) {
  // Находим NAL типа 7 (SPS) после старт-кода.
  let i = 0;
  while (i + 4 < annexb.length) {
    // старт-код 00 00 01 или 00 00 00 01
    if (annexb[i] === 0 && annexb[i + 1] === 0 && (annexb[i + 2] === 1 || (annexb[i + 2] === 0 && annexb[i + 3] === 1))) {
      const off = annexb[i + 2] === 1 ? i + 3 : i + 4;
      const nalType = annexb[off] & 0x1f;
      if (nalType === 7) {
        const profile = annexb[off + 1];
        const constraint = annexb[off + 2];
        const level = annexb[off + 3];
        const hex = (n) => n.toString(16).padStart(2, '0');
        return `avc1.${hex(profile)}${hex(constraint)}${hex(level)}`;
      }
      i = off;
    } else {
      i++;
    }
  }
  return 'avc1.42e01e'; // baseline 3.0 по умолчанию
}

function setupDecoder(codec) {
  decoder = new VideoDecoder({
    output: (frame) => {
      const now = performance.now();
      vw = frame.displayWidth;
      vh = frame.displayHeight;
      if (canvas.width !== vw || canvas.height !== vh) {
        canvas.width = vw;
        canvas.height = vh;
      }
      ctx.drawImage(frame, 0, 0);
      frame.close();
      frames++;
      lastFrameAt = now;
      if (now - lastFpsAt >= 1000) {
        fps = Math.round((frames * 1000) / (now - lastFpsAt));
        frames = 0;
        lastFpsAt = now;
        log(`H.264+WebCodecs · ${vw}×${vh} · ${fps} fps · dropped ${droppedFrames}`);
      }
    },
    error: (e) => log(`decoder error: ${e.message}`),
  });
  // optimizeForLatency — просим декодер не копить кадры (ключевое для управления).
  decoder.configure({ codec, optimizeForLatency: true });
  configured = true;
  log(`decoder configured: ${codec}`);
}

function onEncoded(type, pts, payload) {
  if (!configured) {
    // Первый пакет с SPS: настраиваем декодер под реальный профиль/уровень.
    if (type === 0 || type === 1) setupDecoder(codecFromSps(payload));
    if (type === 2) return; // до конфигурации дельты бесполезны — ждём keyframe
  }
  if (type === 0) return; // чистый config без картинки — только для настройки
  if (decoder.state !== 'configured') return;
  if (dropping) {
    if (type !== 1) { droppedFrames++; return; } // ждём кейфрейм для восстановления
    dropping = false;
  } else if (type === 2 && decoder.decodeQueueSize > MAX_DECODE_QUEUE) {
    dropping = true;
    droppedFrames++;
    return;
  }
  const chunk = new EncodedVideoChunk({
    type: type === 1 ? 'key' : 'delta',
    timestamp: pts,
    data: payload,
  });
  try {
    decoder.decode(chunk);
  } catch (e) {
    log(`decode failed: ${e.message}`);
  }
}

// ── WebSocket ────────────────────────────────────────────────────────────────

const wsUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/lowlat?room=${encodeURIComponent(room)}&role=panel`;
let ws = null;

function connect() {
  ws = new WebSocket(wsUrl);
  ws.binaryType = 'arraybuffer';
  ws.onopen = () => log('connected — waiting for device stream…');
  ws.onclose = () => { log('disconnected — reconnecting…'); setTimeout(connect, 1000); };
  ws.onerror = () => log('ws error');
  ws.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      // служебное (peer-left и т.п.)
      try { const m = JSON.parse(ev.data); if (m.t === 'peer-left') log('device disconnected'); } catch {}
      return;
    }
    const buf = new Uint8Array(ev.data);
    const type = buf[0];
    // pts: 8 байт BE micros
    let pts = 0;
    for (let k = 1; k <= 8; k++) pts = pts * 256 + buf[k];
    const payload = buf.subarray(9);
    onEncoded(type, pts, payload);
  };
}

// ── Управление: тапы/свайпы по canvas → команды устройству ───────────────────

const send = (obj) => { if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)); };

// Координаты в долях кадра с учётом letterbox (canvas масштабируется по CSS).
function frac(ev) {
  const r = canvas.getBoundingClientRect();
  const scale = Math.min(r.width / (vw || 1), r.height / (vh || 1));
  const shownW = (vw || 1) * scale;
  const shownH = (vh || 1) * scale;
  const offX = (r.width - shownW) / 2;
  const offY = (r.height - shownH) / 2;
  const x = (ev.clientX - r.left - offX) / shownW;
  const y = (ev.clientY - r.top - offY) / shownH;
  return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)) };
}

let down = null;
canvas.addEventListener('pointerdown', (e) => { e.preventDefault(); down = { ...frac(e), t: Date.now() }; });
canvas.addEventListener('pointerup', (e) => {
  if (!down) return;
  e.preventDefault();
  const up = frac(e);
  const moved = Math.hypot(up.x - down.x, up.y - down.y);
  if (moved > 0.03) {
    send({ t: 'swipe', x1: down.x, y1: down.y, x2: up.x, y2: up.y, ms: Math.min(1200, Math.max(120, Date.now() - down.t)) });
  } else {
    send({ t: 'tap', x: up.x, y: up.y });
  }
  down = null;
});
canvas.addEventListener('pointercancel', () => { down = null; });

$('nav-back').addEventListener('click', () => send({ t: 'nav', action: 'back' }));
$('nav-home').addEventListener('click', () => send({ t: 'nav', action: 'home' }));
$('nav-recents').addEventListener('click', () => send({ t: 'nav', action: 'recents' }));

if (!('VideoDecoder' in window)) {
  log('WebCodecs недоступен в этом браузере — нужен Chrome/Edge');
} else {
  connect();
}
