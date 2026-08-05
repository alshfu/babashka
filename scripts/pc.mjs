#!/usr/bin/env node
// Отправить одну JSON-команду мосту scrcpy-direct (порт 47200) и напечатать ответ.
//   node scripts/pc.mjs '{"t":"tap","x":0.5,"y":0.75}'
import net from 'node:net';

const raw = process.argv[2];
if (!raw) { console.error('usage: pc.mjs <json>'); process.exit(2); }
const msg = JSON.parse(raw);
msg.id = msg.id ?? Date.now() % 100000;

const c = net.connect(47200, '127.0.0.1', () => c.write(JSON.stringify(msg) + '\n'));
let out = '';
c.on('data', (d) => {
  out += d.toString('utf8');
  const nl = out.indexOf('\n');
  if (nl >= 0) { console.log(out.slice(0, nl).trim()); c.end(); process.exit(0); }
});
c.on('error', (e) => { console.log(JSON.stringify({ ok: false, err: 'bridge: ' + e.message })); process.exit(1); });
setTimeout(() => { console.log(JSON.stringify({ ok: false, err: 'bridge timeout' })); process.exit(2); }, 40000);
