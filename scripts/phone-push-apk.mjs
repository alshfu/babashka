#!/usr/bin/env node
/**
 * phone-push-apk — установка APK на телефон без USB: через LanAgent (LAN, shell UID).
 *
 * APK кодируется base64 и пишется на телефон чанками через shell-команды агента
 * (на устройстве нет curl/wget, adbd по сети выключен), затем pm install -r.
 *
 * Запуск:  node scripts/phone-push-apk.mjs <apk-path> [host] [package]
 * напр.:   node scripts/phone-push-apk.mjs android/.../grandma-v2-debug.apk 192.168.3.111 se.pult.app
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import net from 'node:net';

const APK = process.argv[2];
const HOST = process.argv[3] || '192.168.3.111';
const PKG = process.argv[4] || 'se.pult.app';
const PORT = 47201;
const CHUNK = 100_000;

if (!APK) {
  console.error('usage: node scripts/phone-push-apk.mjs <apk-path> [host] [package]');
  process.exit(1);
}

const send = (command, timeout = 15000) => new Promise((resolve, reject) => {
  const s = net.connect(PORT, HOST);
  let buf = '';
  const to = setTimeout(() => { s.destroy(); reject(new Error('agent timeout')); }, timeout);
  s.on('connect', () => s.write(JSON.stringify({ t: 'shell', command }) + '\n'));
  s.on('data', (d) => {
    buf += d;
    const i = buf.indexOf('\n');
    if (i >= 0) {
      clearTimeout(to);
      s.destroy();
      try { resolve(JSON.parse(buf.slice(0, i).toString())); } catch { resolve({ ok: false, err: 'bad json' }); }
    }
  });
  s.on('error', (e) => { clearTimeout(to); reject(e); });
});

const b64 = readFileSync(APK).toString('base64');
const md5 = createHash('md5').update(readFileSync(APK)).digest('hex');
console.log(`apk: ${APK} (${b64.length} b64 chars, md5 ${md5})`);

await send('rm -f /data/local/tmp/p.b64');
const t0 = Date.now();
for (let off = 0, n = 0; off < b64.length; off += CHUNK, n++) {
  const r = await send(`echo -n '${b64.slice(off, off + CHUNK)}' >> /data/local/tmp/p.b64`);
  if (!r.ok) { console.error('chunk failed at', off, r.err); process.exit(1); }
  if (n % 100 === 0) console.log(`${n} chunks…`);
}
console.log(`uploaded in ${((Date.now() - t0) / 1000).toFixed(0)}s`);

let r = await send('base64 -d /data/local/tmp/p.b64 > /data/local/tmp/p.apk && md5sum /data/local/tmp/p.apk', 60000);
if (!(r.out || '').startsWith(md5)) { console.error('md5 mismatch:', r.out); process.exit(1); }
console.log('md5 ok');

r = await send('pm install -r /data/local/tmp/p.apk && rm -f /data/local/tmp/p.apk /data/local/tmp/p.b64', 120000);
if (!/Success/.test(r.out || '')) { console.error('install failed:', r.out, r.err); process.exit(1); }
console.log('installed');

r = await send(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`, 10000);
console.log('restarted:', PKG);
