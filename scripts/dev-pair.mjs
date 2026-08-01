#!/usr/bin/env node
/**
 * Пара для отладки: один секрет — на телефон (через adb) и в веб-панель (через ссылку).
 *
 * Это инструмент разработчика, а не путь спаривания в продукте. На устройстве пользователя
 * пара создаётся ТОЛЬКО вблизи — NFC или QR при личной встрече (docs/pairing.md).
 * Приёмник на телефоне живёт в src/debug и в релизный APK не попадает физически.
 *
 *   node scripts/dev-pair.mjs [--name Петя] [--url ws://localhost:8080/ws]
 */

import { randomBytes } from 'node:crypto';

const args = new Map();
for (let i = 2; i < process.argv.length; i += 2) {
  args.set(process.argv[i].replace(/^--/, ''), process.argv[i + 1]);
}

const name = args.get('name') ?? 'Петя';
const panelUrl = args.get('url') ?? 'ws://localhost:8080/ws';

const pairId = randomBytes(16).toString('base64url');
const secret = randomBytes(32).toString('base64url');

const packet = Buffer.from(
  JSON.stringify({ v: 1, pid: pairId, sec: secret, name, url: panelUrl }),
).toString('base64url');

const origin = panelUrl.replace(/^ws/, 'http').replace(/\/ws$/, '');

console.log(`pairId:  ${pairId}`);
console.log(`packet:  ${packet}`);
console.log();
console.log('Панель внука (браузер на хосте):');
console.log(`  ${origin}/panel/?role=helper#p=${packet}`);
console.log();
console.log('Телефон бабушки (эмулятор видит хост как 10.0.2.2, адрес берётся из BuildConfig):');
console.log(
  `  adb shell am broadcast -a ru.pult.grandma.DEBUG_PAIR \\\n` +
    `    -n ru.pult.grandma/.debug.DebugPairReceiver --es packet "${packet}"`,
);
