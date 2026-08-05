import test from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';

import { startTestServer, pairId } from './helpers.js';

const AGENT_TOKEN = 'test-token-123';

async function start(t) {
  const ctx = await startTestServer({ agentToken: AGENT_TOKEN });
  t.after(() => ctx.app.close());
  return ctx;
}

const tunnelUrl = (port, pair, side, token = AGENT_TOKEN) =>
  `ws://127.0.0.1:${port}/tunnel?pairId=${encodeURIComponent(pair)}&side=${side}&token=${encodeURIComponent(token)}`;

function connect(url) {
  return new Promise((done, fail) => {
    const ws = new WebSocket(url);
    ws.once('open', () => done(ws));
    ws.once('error', fail);
  });
}

test('/tunnel: чужой токен отбрасывается', async (t) => {
  const { port } = await start(t);
  const ws = new WebSocket(tunnelUrl(port, pairId('a'), 'app', 'wrong'));
  const code = await new Promise((done) => ws.once('close', done));
  assert.equal(code, 4403);
});

test('/tunnel: бинарные кадры ретранслируются в обе стороны как есть', async (t) => {
  const { port } = await start(t);
  const pair = pairId('b');
  const app = await connect(tunnelUrl(port, pair, 'app'));
  const phone = await connect(tunnelUrl(port, pair, 'phone'));

  const frame = Buffer.from([0, 0, 0, 7, 1, 10, 20, 30]); // stream 7, op=data
  const got = new Promise((done) => phone.once('message', (d, isBinary) => done({ d, isBinary })));
  app.send(frame, { binary: true });
  const { d, isBinary } = await got;
  assert.equal(isBinary, true);
  assert.deepEqual([...d], [...frame]);

  const back = new Promise((done) => app.once('message', (data) => done(data)));
  phone.send(Buffer.from([9, 8, 7]), { binary: true });
  assert.deepEqual([...(await back)], [9, 8, 7]);

  app.close(); phone.close();
});

test('/tunnel: уход одной стороны закрывает вторую (сброс потоков)', async (t) => {
  const { port } = await start(t);
  const pair = pairId('c');
  const app = await connect(tunnelUrl(port, pair, 'app'));
  const phone = await connect(tunnelUrl(port, pair, 'phone'));

  const closed = new Promise((done) => phone.once('close', done));
  app.close();
  assert.equal(await closed, 4001);
});
