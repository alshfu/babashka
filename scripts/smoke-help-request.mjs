// Одноразовый триггер сессии: подключаемся к локальному сигналингу как helper
// демо-пары и шлём help-request, чтобы устройство прошло путь consent -> capture.
import WebSocket from '../server/node_modules/ws/wrapper.mjs';

const url = process.env.WS_URL ?? 'ws://localhost:8080/ws';
const pairId = process.env.PAIR_ID ?? 'demo-pair-000000000000';
const ws = new WebSocket(url);
const log = (m) => console.log(new Date().toISOString().slice(11, 19), m);

ws.on('open', () => {
  log('connected');
  ws.send(JSON.stringify({ t: 'hello', v: 1, pairId, role: 'helper', deviceId: 'dev-trigger-script' }));
});

ws.on('message', (data) => {
  const msg = JSON.parse(data.toString('utf8'));
  log(`recv ${JSON.stringify(msg)}`);
  if (msg.t === 'hello-ok') {
    log('sending help-request');
    ws.send(JSON.stringify({ t: 'help-request', note: 'smoke: projection grant check' }));
  }
  if (msg.t === 'consent-granted') {
    log('CONSENT OK — устройство дало согласие; WebRTC не поднимаем, это smoke-тест');
    setTimeout(() => { ws.send(JSON.stringify({ t: 'session-end', sessionId: msg.sessionId, reason: 'smoke-done' })); ws.close(); process.exit(0); }, 1500);
  }
  if (msg.t === 'consent-denied' || msg.t === 'error') {
    log('FAIL');
    process.exit(1);
  }
});

ws.on('error', (e) => { log(`error ${e.message}`); process.exit(1); });
setTimeout(() => { log('timeout 20s'); process.exit(2); }, 20000);
