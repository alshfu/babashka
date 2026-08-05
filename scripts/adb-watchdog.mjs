#!/usr/bin/env node
/**
 * Watchdog для телефона, оставленного в Швеции.
 *
 * Проблема: после перезагрузки телефона ADB-доступ не восстановить удалённо
 * (см. docs/wifi-adb-sweden-setup.md), поэтому об обрыве надо узнавать сразу,
 * пока причина ещё обратима (отвалился Tailscale, сменился IP, упал adbd).
 *
 * Что делает: каждые INTERVAL_SEC секунд выполняет `adb connect TARGET` и
 * `adb shell echo ok`. Пишет результат в test_logs/adb-watchdog.log.
 * После FAIL_THRESHOLD неудач подряд шлёт один алерт в Telegram (и ещё один —
 * когда связь восстановилась).
 *
 * Запуск:
 *   ADB_TARGET=100.x.y.z:5555 \
 *   TG_BOT_TOKEN=123:abc TG_CHAT_ID=456 \
 *   node scripts/adb-watchdog.mjs
 *
 * ADB_TARGET обязателен. Без TG_* просто пишет лог и печатает в консоль.
 * На Mac удобно держать через `nohup ... &` или launchd.
 */

import { spawn } from 'node:child_process';
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const TARGET = process.env.ADB_TARGET;
const INTERVAL_SEC = parseInt(process.env.INTERVAL_SEC || '300', 10);   // 5 мин
const FAIL_THRESHOLD = parseInt(process.env.FAIL_THRESHOLD || '3', 10);
const TG_BOT_TOKEN = process.env.TG_BOT_TOKEN;
const TG_CHAT_ID = process.env.TG_CHAT_ID;

const LOG_DIR = '/Users/al_sh/IdeaProjects/babashka/test_logs';
const LOG_FILE = join(LOG_DIR, 'adb-watchdog.log');
mkdirSync(LOG_DIR, { recursive: true });

if (!TARGET) {
  console.error('ADB_TARGET не задан (пример: ADB_TARGET=100.64.1.2:5555)');
  process.exit(1);
}

function logLine(obj) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n';
  appendFileSync(LOG_FILE, line);
  console.log(line.trim());
}

function adb(args, timeoutMs = 15000) {
  return new Promise((resolve) => {
    const child = spawn('adb', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    let err = '';
    const killer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    child.stdout.on('data', (d) => { out += d.toString(); });
    child.stderr.on('data', (d) => { err += d.toString(); });
    child.on('close', (code) => {
      clearTimeout(killer);
      resolve({ ok: code === 0 && out.includes('ok'), code, out: out.trim(), err: err.trim() });
    });
  });
}

async function check() {
  const conn = await adb(['connect', TARGET]);
  if (!/connected|already connected/.test(conn.out + conn.err)) {
    return { ok: false, stage: 'connect', detail: (conn.out + ' ' + conn.err).trim() };
  }
  const shell = await adb(['-s', TARGET, 'shell', 'echo', 'ok']);
  if (!shell.ok) {
    return { ok: false, stage: 'shell', detail: (shell.out + ' ' + shell.err).trim() };
  }
  return { ok: true };
}

async function tgAlert(text) {
  if (!TG_BOT_TOKEN || !TG_CHAT_ID) return;
  try {
    await fetch(`https://api.telegram.org/bot${TG_BOT_TOKEN}/sendMessage`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ chat_id: TG_CHAT_ID, text }),
    });
  } catch (e) {
    logLine({ t: 'tg-error', err: String(e) });
  }
}

let failCount = 0;
let alerted = false;

async function tick() {
  const r = await check();
  if (r.ok) {
    if (alerted) {
      await tgAlert(`✅ Телефон ${TARGET} снова на связи.`);
      logLine({ t: 'recovered' });
    }
    failCount = 0;
    alerted = false;
    logLine({ t: 'ok' });
    return;
  }
  failCount += 1;
  logLine({ t: 'fail', n: failCount, stage: r.stage, detail: r.detail });
  if (failCount >= FAIL_THRESHOLD && !alerted) {
    alerted = true;
    await tgAlert(
      `🚨 Телефон ${TARGET} не отвечает уже ${failCount} проверок подряд ` +
      `(этап: ${r.stage}). Деталь: ${r.detail}\n` +
      `Если это перезагрузка — ADB удалённо не восстановить, см. ` +
      `docs/wifi-adb-sweden-setup.md («Инструкция для человека в Швеции»).`
    );
  }
}

logLine({ t: 'start', target: TARGET, intervalSec: INTERVAL_SEC, failThreshold: FAIL_THRESHOLD });
tick();
setInterval(tick, INTERVAL_SEC * 1000);
