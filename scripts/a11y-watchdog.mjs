#!/usr/bin/env node
// a11y-watchdog — самолечение телефона бабушки, без USB и без ручных действий.
//
// Каждые 45 секунд через локальный scrcpy-lan (TCP 127.0.0.1:47203) проверяет:
//   1. Жив ли процесс se.pult.app — нет → запускает (monkey).
//   2. Включена ли accessibility-служба RemoteControlService — нет → включает (settings put).
//   3. Не в «crashed» ли она (падает вместе с процессом: MIUI kill, лимит dataSync FGS 6ч/24ч)
//      — да → перещёлкивает настройку (пусто → обратно), заставляя систему забиндить заново.
//
// Логирует только переходы состояний: test_logs/a11y-watchdog.log.
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import net from 'node:net';

const CMD_PORT = parseInt(process.env.LAN_CMD_PORT || '47203', 10);
const INTERVAL_MS = parseInt(process.env.WATCHDOG_INTERVAL_MS || '45000', 10);
const PKG = 'se.pult.app';
const A11Y = 'se.pult.app/ru.pult.grandma.control.RemoteControlService';

const LOG_DIR = '/Users/al_sh/IdeaProjects/babashka/test_logs';
mkdirSync(LOG_DIR, { recursive: true });
function logLine(obj) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n';
  appendFileSync(join(LOG_DIR, 'a11y-watchdog.log'), line);
  console.log(line.trim());
}

function lanCmd(cmd, timeoutMs = 25000) {
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

const shell = (command) => lanCmd({ t: 'shell', command, timeoutMs: 15000 }, 25000);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let lastState = 'unknown';

async function tick() {
  // 1. Процесс приложения жив?
  const pid = await shell(`pidof ${PKG}`);
  if (!pid.ok) return logLine({ t: 'phone-unreachable', err: pid.err });
  if (!(pid.out || '').trim()) {
    logLine({ t: 'app-dead', action: 'relaunch' });
    await shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`);
    await sleep(6000); // даём процессу подняться и забиндить службу
  }

  // 2. Служба включена в настройках?
  const enabled = await shell('settings get secure enabled_accessibility_services');
  if (enabled.ok && !(enabled.out || '').includes(A11Y)) {
    logLine({ t: 'a11y-disabled', action: 'enable' });
    await shell(`settings put secure enabled_accessibility_services ${A11Y}`);
    await shell('settings put secure accessibility_enabled 1');
    await sleep(2500);
  }

  // 3. Служба в crashed или не забиндена при включённой настройке?
  const dump = await shell("dumpsys accessibility | grep -E 'Bound services|Crashed services' | head -4");
  if (!dump.ok) return;
  const out = dump.out || '';
  const crashed = /Crashed services:\{\{[^}]*se\.pult\.app/.test(out);
  const bound = /Bound services:\{Service\[label=Pult/.test(out);
  const state = crashed ? 'crashed' : bound ? 'bound' : 'unbound';
  if (crashed) {
    // Перещёлкнуть: иначе система не перебиндит, пока человек не зайдёт в настройки.
    logLine({ t: 'a11y-crashed', action: 'rebind' });
    await shell('settings put secure enabled_accessibility_services ""');
    await sleep(1200);
    await shell(`settings put secure enabled_accessibility_services ${A11Y}`);
    await shell('settings put secure accessibility_enabled 1');
  }
  if (state !== lastState) {
    logLine({ t: 'a11y-state', from: lastState, to: state });
    lastState = state;
  }
}

async function main() {
  logLine({ t: 'start', cmdPort: CMD_PORT, intervalMs: INTERVAL_MS });
  for (;;) {
    try { await tick(); }
    catch (e) { logLine({ t: 'tick-error', err: e.message }); }
    await sleep(INTERVAL_MS);
  }
}

main();
