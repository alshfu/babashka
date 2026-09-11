import test from 'node:test';
import assert from 'node:assert/strict';

import { startTestServer, TestClient, pairId } from './helpers.js';

const AGENT_TOKEN = 'test-token-123';

async function start(t) {
  const ctx = await startTestServer({ agentToken: AGENT_TOKEN });
  t.after(() => ctx.app.close());
  return ctx;
}

const linkUrl = (port, pair, token = AGENT_TOKEN) =>
  `ws://127.0.0.1:${port}/link?pairId=${encodeURIComponent(pair)}&token=${encodeURIComponent(token)}`;

const VALID_DEEPLINK = 'bankid:///?autostarttoken=7c4f2a50-9b0d-4f2b-9f3d-2f8a1b2c3d4e&redirect=null';

test('/link: чужой токен отбрасывается', async (t) => {
  const { port } = await start(t);
  const { default: WebSocket } = await import('ws');
  const socket = new WebSocket(linkUrl(port, pairId('a'), 'wrong-token'));
  const code = await new Promise((done, fail) => {
    socket.once('close', done);
    socket.once('error', fail);
    setTimeout(() => fail(new Error('сокет не закрыли')), 2000);
  });
  assert.equal(code, 4403);
});

test('/link: диплинк доезжает до телефона бабушки, статус — обратно в приложение', async (t) => {
  const { port, url } = await start(t);
  const pair = pairId('b');

  const grandma = await TestClient.connect(url);
  await grandma.hello({ pairId: pair, role: 'grandma' });

  const app = await TestClient.connect(linkUrl(port, pair));
  app.send({ t: 'deeplink', url: VALID_DEEPLINK });

  const ack = await app.next('deeplink-ack');
  assert.equal(ack.delivered, true);

  const onPhone = await grandma.next('deeplink');
  assert.equal(onPhone.url, VALID_DEEPLINK);

  grandma.send({ t: 'deeplink-status', ok: true, stage: 'signed', err: '' });
  const status = await app.next('deeplink-status');
  assert.deepEqual({ ok: status.ok, stage: status.stage, err: status.err }, { ok: true, stage: 'signed', err: '' });
});

test('/link: телефон офлайн — delivered:false, приложение сразу в курсе', async (t) => {
  const { port } = await start(t);
  const app = await TestClient.connect(linkUrl(port, pairId('c')));
  app.send({ t: 'deeplink', url: VALID_DEEPLINK });
  const ack = await app.next('deeplink-ack');
  assert.equal(ack.delivered, false);
});

test('/link: pin-setup доезжает до телефона бабушки, итог возвращается приложению', async (t) => {
  const { port, url } = await start(t);
  const pair = pairId('pin');

  const grandma = await TestClient.connect(url);
  await grandma.hello({ pairId: pair, role: 'grandma' });

  const app = await TestClient.connect(linkUrl(port, pair));
  app.send({ t: 'pin-setup' });

  const ack = await app.next('deeplink-ack');
  assert.equal(ack.delivered, true);

  const onPhone = await grandma.next('pin-setup');
  assert.equal(onPhone.t, 'pin-setup');

  // Итог ввода PIN бабушка шлёт тем же deeplink-status (stage=pin-saved|pin-cancelled).
  grandma.send({ t: 'deeplink-status', ok: true, stage: 'pin-saved', err: '' });
  const status = await app.next('deeplink-status');
  assert.deepEqual({ ok: status.ok, stage: status.stage }, { ok: true, stage: 'pin-saved' });
});

test('/link: pin-set доезжает до телефона, нецифровой PIN отбраковывается на сервере', async (t) => {
  const { port, url } = await start(t);
  const pair = pairId('pinset');

  const grandma = await TestClient.connect(url);
  await grandma.hello({ pairId: pair, role: 'grandma' });

  const app = await TestClient.connect(linkUrl(port, pair));
  app.send({ t: 'pin-set', pin: '483921' });

  const ack = await app.next('deeplink-ack');
  assert.equal(ack.delivered, true);
  const onPhone = await grandma.next('pin-set');
  assert.equal(onPhone.pin, '483921');

  // Формат валидируется на сервере: мусор до телефона не доходит.
  app.send({ t: 'pin-set', pin: '12ab' });
  const badAck = await app.next('deeplink-ack');
  assert.equal(badAck.delivered, false);
  assert.deepEqual(await grandma.silentFor(150), []);
});

test('/link: не-bankid URL не маршрутизируется', async (t) => {
  const { port, url } = await start(t);
  const pair = pairId('d');
  const grandma = await TestClient.connect(url);
  await grandma.hello({ pairId: pair, role: 'grandma' });

  const app = await TestClient.connect(linkUrl(port, pair));
  app.send({ t: 'deeplink', url: 'https://evil.example/phish' });
  const ack = await app.next('deeplink-ack');
  assert.equal(ack.delivered, false);
  assert.deepEqual(await grandma.silentFor(150), []);
});

test('/link: deeplink-status от роли helper игнорируется', async (t) => {
  const { port, url } = await start(t);
  const pair = pairId('e');
  const helper = await TestClient.connect(url);
  await helper.hello({ pairId: pair, role: 'helper' });

  const app = await TestClient.connect(linkUrl(port, pair));
  helper.send({ t: 'deeplink-status', ok: true, stage: 'signed', err: '' });
  assert.deepEqual(await app.silentFor(150), []);
});

test('/link: update-status с телефона доезжает до приложения с pairId и at', async (t) => {
  const { port, url } = await start(t);
  const pair = pairId('f');

  const grandma = await TestClient.connect(url);
  await grandma.hello({ pairId: pair, role: 'grandma' });

  const app = await TestClient.connect(linkUrl(port, pair));
  grandma.send({ t: 'update-status', kind: 'apk', version: '1.2.3', ok: false, err: 'install failed' });

  const status = await app.next('update-status');
  assert.equal(status.pairId, pair);
  assert.deepEqual(
    { kind: status.kind, version: status.version, ok: status.ok, err: status.err },
    { kind: 'apk', version: '1.2.3', ok: false, err: 'install failed' },
  );
  assert.equal(typeof status.at, 'number');
});

test('/link: update-status от роли helper игнорируется', async (t) => {
  const { port, url } = await start(t);
  const pair = pairId('g');
  const helper = await TestClient.connect(url);
  await helper.hello({ pairId: pair, role: 'helper' });

  const app = await TestClient.connect(linkUrl(port, pair));
  helper.send({ t: 'update-status', kind: 'apk', version: '1.2.3', ok: true });
  assert.deepEqual(await app.silentFor(150), []);
});
