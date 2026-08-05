#!/usr/bin/env node
// Одноразовый пробник помощника: hello → help-request → слушаем ответ телефона.
// Проверяет путь панели без браузера: присутствие, авто-accept, SDP offer.
import WebSocket from '../server/node_modules/ws/index.js';

const url = process.argv[2] || 'wss://89-127-235-17.sslip.io/ws';
const pairId = 'demo-pair-000000000000';
const ws = new WebSocket(url);
const t0 = Date.now();
const stamp = () => ((Date.now() - t0) / 1000).toFixed(1) + 's';

let asked = false;

ws.on('open', () => {
  console.log(stamp(), 'open → hello');
  ws.send(JSON.stringify({
    t: 'hello', v: 1, pairId, role: 'helper',
    deviceId: 'probe-' + Math.random().toString(36).slice(2, 8),
  }));
});

ws.on('message', (data) => {
  let m;
  try { m = JSON.parse(data.toString('utf8')); } catch { return; }
  const brief = { ...m };
  if (brief.sdp) brief.sdp = String(brief.sdp).slice(0, 60) + '…';
  if (brief.candidate) brief.candidate = String(brief.candidate).slice(0, 60) + '…';
  console.log(stamp(), '←', JSON.stringify(brief).slice(0, 300));
  if (m.t === 'hello-ok') {
    console.log(stamp(), 'peerOnline =', m.peerOnline, '| iceServers =', JSON.stringify(m.iceServers));
    if (!asked) {
      asked = true;
      ws.send(JSON.stringify({ t: 'help-request' }));
      console.log(stamp(), '→ help-request отправлен');
    }
  }
  if (m.t === 'consent-granted' || m.t === 'offer') {
    console.log(stamp(), '*** ЦЕПОЧКА ЖИВА:', m.t, '— телефон принял и отвечает ***');
  }
});

ws.on('close', (c, r) => { console.log(stamp(), 'close', c, String(r)); process.exit(0); });
ws.on('error', (e) => console.log(stamp(), 'error', e.message));

setTimeout(() => { console.log(stamp(), 'таймаут прослушки, выхожу'); ws.close(); process.exit(0); }, 30000);
