import test from 'node:test';
import assert from 'node:assert/strict';

import { startTestServer, TestClient, pairId } from './helpers.js';

const AGENT_TOKEN = 'test-token-123';

async function start(t) {
  const ctx = await startTestServer({ agentToken: AGENT_TOKEN });
  t.after(() => ctx.app.close());
  return ctx;
}

const devices = (base) =>
  fetch(`${base}/api/devices`, { headers: { 'x-agent-token': AGENT_TOKEN } }).then((r) => r.json());

test('/api/devices: без токена — 401', async (t) => {
  const { base } = await start(t);
  const res = await fetch(`${base}/api/devices`);
  assert.equal(res.status, 401);
});

test('/api/devices: реестр бабушек с визитками, online-флаги, peerLabel в hello-ok', async (t) => {
  const { base, url } = await start(t);
  const pair1 = pairId('reg-a');
  const pair2 = pairId('reg-b');

  const g1 = await TestClient.connect(url);
  await g1.hello({ pairId: pair1, role: 'grandma', label: 'Бабушка', model: 'Pixel 7' });
  const g2 = await TestClient.connect(url);
  await g2.hello({ pairId: pair2, role: 'grandma' }); // без label/model

  // Помощник пары 1 видит визитку бабушки в hello-ok; в реестр он не попадает.
  const helper = await TestClient.connect(url);
  const helloOk = await helper.hello({ pairId: pair1, role: 'helper', label: 'Внук' });
  assert.equal(helloOk.peerOnline, true);
  assert.equal(helloOk.peerLabel, 'Бабушка');
  assert.equal(helloOk.peerModel, 'Pixel 7');

  const list = await devices(base);
  assert.equal(list.length, 2);
  const first = list.find((d) => d.pairId === pair1);
  const second = list.find((d) => d.pairId === pair2);
  assert.deepEqual(
    { deviceId: first.deviceId, label: first.label, model: first.model, online: first.online },
    { deviceId: 'dev-grandma', label: 'Бабушка', model: 'Pixel 7', online: true },
  );
  assert.equal(typeof first.lastSeen, 'number');
  // У второй бабушки визитки нет — поля отсутствуют, запись всё равно есть.
  assert.equal(second.online, true);
  assert.equal(second.label, undefined);
  assert.equal(second.model, undefined);

  // Отключение: запись остаётся, online уходит в false, lastSeen сохраняется.
  await g2.close();
  let after = [];
  for (let i = 0; i < 20; i += 1) {
    after = await devices(base);
    if (after.find((d) => d.pairId === pair2)?.online === false) break;
    await new Promise((done) => setTimeout(done, 50));
  }
  const gone = after.find((d) => d.pairId === pair2);
  assert.equal(gone.online, false);
  assert.equal(typeof gone.lastSeen, 'number');
  assert.equal(after.find((d) => d.pairId === pair1).online, true);
});

test('peer-state: визитка приходит при появлении пира онлайн', async (t) => {
  const { url } = await start(t);
  const pair = pairId('reg-c');

  const helper = await TestClient.connect(url);
  await helper.hello({ pairId: pair, role: 'helper' });

  const grandma = await TestClient.connect(url);
  await grandma.hello({ pairId: pair, role: 'grandma', label: 'Mormor' });

  const state = await helper.next('peer-state');
  assert.equal(state.online, true);
  assert.equal(state.peerLabel, 'Mormor');
  assert.equal(state.peerModel, undefined);
});

test('hello: кривая визитка отбраковывается', async (t) => {
  const { url } = await start(t);
  const client = await TestClient.connect(url);
  client.send({ t: 'hello', v: 1, pairId: pairId('reg-d'), role: 'grandma', deviceId: 'd1', label: 'x'.repeat(41) });
  const error = await client.next('error');
  assert.equal(error.code, 'bad-message');
});
