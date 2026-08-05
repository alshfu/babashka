#!/usr/bin/env node
// Отправить одну JSON-команду LAN-агенту на телефоне (порт 47201) и напечатать ответ.
//   node scripts/pc-lan.mjs '{"t":"tap","x":0.5,"y":0.75}'
//   LAN_HOST=192.168.39.111 LAN_PORT=47201 node scripts/pc-lan.mjs '{"t":"ping"}'
import net from 'node:net';

const raw = process.argv[2];
if (!raw) { console.error('usage: pc-lan.mjs <json>'); process.exit(2); }
const msg = JSON.parse(raw);
msg.id = msg.id ?? Date.now() % 100000;

const HOST = process.env.LAN_HOST || '192.168.39.111';
const PORT = parseInt(process.env.LAN_PORT || '47201', 10);

const c = net.connect(PORT, HOST, () => c.write(JSON.stringify(msg) + '\n'));
let out = '';
c.on('data', (d) => {
  out += d.toString('utf8');
  const nl = out.indexOf('\n');
  if (nl >= 0) { console.log(out.slice(0, nl).trim()); c.end(); process.exit(0); }
});
c.on('error', (e) => { console.log(JSON.stringify({ ok: false, err: 'lan-agent: ' + e.message })); process.exit(1); });
setTimeout(() => { console.log(JSON.stringify({ ok: false, err: 'lan-agent timeout' })); process.exit(2); }, 60000);
