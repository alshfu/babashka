#!/usr/bin/env node
/**
 * lowlat-start — «одна кнопка» запуска низколатентной трансляции на время разработки.
 *
 * Делает всё на автомате: будит экран, подавляет диалог захвата (appops), стартует
 * LowLatActivity с битрейтом/fps, если системный диалог всё же всплыл — находит
 * кнопку «Start now»/«Начать» по uiautomator-дампу и тапает её, затем проверяет
 * по logcat, что энкодер реально поднялся.
 *
 * Запуск:  node scripts/lowlat-start.mjs [serial] [bitrate] [fps] [ws-url]
 * напр.:   node scripts/lowlat-start.mjs DQ6TC64DY9PRBE4T 1500000 20
 * Дефолты: serial=DQ6TC64DY9PRBE4T, bitrate=1500000 (VPS-канал), fps=20,
 *          url=wss://89-127-235-17.sslip.io/lowlat?room=demo&role=device
 */
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const run = promisify(execFile);

const SERIAL = process.argv[2] || 'DQ6TC64DY9PRBE4T';
const BITRATE = process.argv[3] || '1500000';
const FPS = process.argv[4] || '20';
const WS_URL = process.argv[5] || 'wss://89-127-235-17.sslip.io/lowlat?room=demo&role=device';
const ACTIVITY = 'se.pult.app/ru.pult.grandma.lowlat.LowLatActivity';

const LOG_DIR = '/Users/al_sh/IdeaProjects/babashka/test_logs';
mkdirSync(LOG_DIR, { recursive: true });
function logLine(obj) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n';
  appendFileSync(join(LOG_DIR, 'lowlat-start.log'), line);
  console.log(line.trim());
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function adb(args, timeout = 15000) {
  const { stdout } = await run('adb', ['-s', SERIAL, ...args], { timeout, maxBuffer: 8 * 1024 * 1024 });
  return stdout;
}

/** Дамп UI и центр кнопки согласия на захват (null, если диалога нет). */
async function consentButtonCenter() {
  await adb(['shell', 'uiautomator', 'dump', '/data/local/tmp/lowlat-ui.xml']);
  const xml = await adb(['exec-out', 'cat', '/data/local/tmp/lowlat-ui.xml']);
  const re = /<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const text = m[1].toLowerCase();
    if (/start now|начать|разрешить|allow/.test(text)) {
      const [, , x1, y1, x2, y2] = m.map(Number);
      return { x: (x1 + x2) / 2, y: (y1 + y2) / 2, text: m[1] };
    }
  }
  return null;
}

async function main() {
  logLine({ t: 'start', serial: SERIAL, bitrate: BITRATE, fps: FPS });

  // Экран мог уснуть — диалог захвата тогда не покажется.
  await adb(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']);
  await sleep(600);

  // Подавляем диалог захвата на тех прошивках, где appops это уважают.
  await adb(['shell', 'appops', 'set', 'se.pult.app', 'PROJECT_MEDIA', 'allow']).catch(() => {});

  // Чистим logcat, чтобы верификация не цепляла строки прошлых запусков,
  // и гасим возможный прежний стример, чтобы не было двух захватов сразу.
  await adb(['logcat', '-c']).catch(() => {});
  await adb(['shell', 'am', 'force-stop', 'se.pult.app']);
  await sleep(500);

  // URL в одинарных кавычках: adb передаёт команду через shell телефона,
  // и без кавычек «&» в query оборвёт команду (фоновый оператор shell).
  await adb(['shell', 'am', 'start', '-n', ACTIVITY,
    '--ei', 'bitrate', BITRATE, '--ei', 'fps', FPS, '--es', 'url', `'${WS_URL}'`]);
  logLine({ t: 'activity-started', url: WS_URL });
  await sleep(2000);

  // Если диалог всё же всплыл (Android 14+ часто игнорит appops) — тапаем кнопку.
  const btn = await consentButtonCenter();
  if (btn) {
    await adb(['shell', 'input', 'tap', String(Math.round(btn.x)), String(Math.round(btn.y))]);
    logLine({ t: 'consent-tapped', button: btn.text, x: btn.x, y: btn.y });
  } else {
    logLine({ t: 'consent-not-needed' });
  }

  // Проверка: ждём «ws open» (или сразу падаем на «ws failure») до 15 секунд.
  let encoder = false, wsOpen = false, failure = '';
  for (let i = 0; i < 15 && !(encoder && wsOpen) && !failure; i++) {
    await sleep(1000);
    const logcat = await adb(['logcat', '-d', '-s', 'PultLowLat']).catch(() => '');
    encoder = encoder || /streamer started/.test(logcat);
    wsOpen = wsOpen || /ws open/.test(logcat);
    const f = logcat.match(/ws failure: (.+)/);
    if (f) failure = f[1];
  }
  logLine({ t: encoder && wsOpen ? 'ok' : 'check-failed', encoder, ws: wsOpen, err: failure });
  if (!encoder || !wsOpen) {
    console.error('Поток не поднялся: ' + (failure || 'таймаут ожидания ws open'));
    process.exit(1);
  }
  console.log(`Готово: трансляция идёт (${BITRATE} bps, ${FPS} fps) → ${WS_URL}`);
}

main().catch((e) => { logLine({ t: 'fatal', err: e.message }); process.exit(1); });
