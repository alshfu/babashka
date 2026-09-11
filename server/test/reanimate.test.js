import test from 'node:test';
import assert from 'node:assert/strict';

import { startTestServer } from './helpers.js';

const AGENT_TOKEN = 'test-token-reanimate';

async function start(t, overrides = {}) {
  const ctx = await startTestServer({ agentToken: AGENT_TOKEN, fcmKey: 'fcm-key-1', ...overrides });
  t.after(() => ctx.app.close());
  return ctx;
}

const post = (base, body, token = AGENT_TOKEN) =>
  fetch(`${base}/api/reanimate`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(token ? { 'x-agent-token': token } : {}),
    },
    body: JSON.stringify(body),
  });

test('/api/reanimate: без токена — 401', async (t) => {
  const { base } = await start(t);
  const res = await post(base, { pairId: 'p', action: 'reboot' }, null);
  assert.equal(res.status, 401);
});

test('/api/reanimate: без pairId — 400', async (t) => {
  const { base } = await start(t);
  const res = await post(base, { action: 'reboot' });
  assert.equal(res.status, 400);
});

test('/api/reanimate: action нормализуется к wake, push уходит в FCM', async (t) => {
  // Мок ставим ДО подъёма сервера: createPush захватывает globalThis.fetch
  // в момент создания (аргумент по умолчанию), поздняя подмена его не видит.
  const calls = [];
  const original = globalThis.fetch;
  t.mock.method(globalThis, 'fetch', async (target, options) => {
    if (String(target).includes('fcm.googleapis.com')) {
      calls.push({ target, options });
      return { ok: true };
    }
    return original(target, options);
  });
  const { base, url } = await start(t);

  const g = await (await import('./helpers.js')).TestClient.connect(url);
  await g.hello({ pairId: 'p-reanimate', role: 'grandma' });
  g.send({ t: 'register-push', token: 'fcm-device-token' });
  await new Promise((r) => setTimeout(r, 150));

  const res = await post(base, { pairId: 'p-reanimate', action: 'взорвать' });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(body, { ok: true, sent: true, action: 'wake' });
  assert.equal(calls.length, 1);
  const payload = JSON.parse(calls[0].options.body);
  assert.equal(payload.to, 'fcm-device-token');
  assert.equal(payload.priority, 'high');
  assert.deepEqual(payload.data, { t: 'reanimate', action: 'wake' });
});

test('/api/reanimate: reboot доходит до FCM дословно', async (t) => {
  // Мок до подъёма сервера — см. тест выше.
  const calls = [];
  const original = globalThis.fetch;
  t.mock.method(globalThis, 'fetch', async (target, options) => {
    if (String(target).includes('fcm.googleapis.com')) {
      calls.push(JSON.parse(options.body));
      return { ok: true };
    }
    return original(target, options);
  });
  const { base, url } = await start(t);

  const g = await (await import('./helpers.js')).TestClient.connect(url);
  await g.hello({ pairId: 'p-reboot', role: 'grandma' });
  g.send({ t: 'register-push', token: 'fcm-device-token-2' });
  await new Promise((r) => setTimeout(r, 150));

  const res = await post(base, { pairId: 'p-reboot', action: 'reboot' });
  assert.equal((await res.json()).action, 'reboot');
  assert.deepEqual(calls[0].data, { t: 'reanimate', action: 'reboot' });
});

test('/api/reanimate: нет FCM-токена — sent=false, но 200', async (t) => {
  const { base } = await start(t);
  const res = await post(base, { pairId: 'никто-не-подписан', action: 'reboot' });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, sent: false, action: 'reboot' });
});
