import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

import { startTestServer, TestClient, pairId } from './helpers.js';

const AGENT_TOKEN = 'test-token-123';
const UPDATE_TOKEN = 'update-token-456';

async function start(t) {
  const updatesDir = await mkdtemp(join(tmpdir(), 'pult-updates-'));
  const ctx = await startTestServer({ agentToken: AGENT_TOKEN, updateToken: UPDATE_TOKEN, updatesDir });
  t.after(() => ctx.app.close());
  t.after(() => rm(updatesDir, { recursive: true, force: true }));
  return ctx;
}

const sha256hex = (body) => createHash('sha256').update(body).digest('hex');

async function upload(base, body, { token = AGENT_TOKEN, kind = 'apk', version = '1.2.3', sha } = {}) {
  return fetch(`${base}/api/update`, {
    method: 'PUT',
    headers: {
      'x-agent-token': token,
      'x-update-kind': kind,
      'x-update-version': version,
      'x-sha256': sha ?? sha256hex(body),
    },
    body,
  });
}

test('update: загрузка артефакта — ок, манифест и файл на месте', async (t) => {
  const { base } = await start(t);
  const body = Buffer.from('fake-apk-bytes');
  const res = await upload(base, body);
  assert.equal(res.status, 200);
  const out = await res.json();
  assert.equal(out.ok, true);
  assert.equal(out.kind, 'apk');
  assert.equal(out.version, '1.2.3');
  assert.equal(out.sha256, sha256hex(body));
  assert.equal(out.size, body.length);
  assert.match(out.url, /^\/update\/apk-[\w-]+\.apk$/);
});

test('update: чужой токен при загрузке — 401', async (t) => {
  const { base } = await start(t);
  const res = await upload(base, Buffer.from('x'), { token: 'wrong' });
  assert.equal(res.status, 401);
});

test('update: sha256 не сошёлся — 400', async (t) => {
  const { base } = await start(t);
  const res = await upload(base, Buffer.from('x'), { sha: '0'.repeat(64) });
  assert.equal(res.status, 400);
});

test('update: манифест — оба способа авторизации, без токена 401, пусто — 404', async (t) => {
  const { base } = await start(t);
  const body = Buffer.from('dex-payload');

  // Ещё ничего не загружали — 404 (авторизация при этом валидна).
  const empty = await fetch(`${base}/api/update/manifest?kind=dex`, {
    headers: { 'x-agent-token': AGENT_TOKEN },
  });
  assert.equal(empty.status, 404);

  await upload(base, body, { kind: 'dex', version: '42' });

  const byHeader = await fetch(`${base}/api/update/manifest?kind=dex`, {
    headers: { 'x-agent-token': AGENT_TOKEN },
  });
  assert.equal(byHeader.status, 200);
  const manifest = await byHeader.json();
  assert.deepEqual(
    { kind: manifest.kind, version: manifest.version, sha256: manifest.sha256, size: manifest.size },
    { kind: 'dex', version: '42', sha256: sha256hex(body), size: body.length },
  );

  const byToken = await fetch(`${base}/api/update/manifest?kind=dex&token=${UPDATE_TOKEN}`);
  assert.equal(byToken.status, 200);

  const noAuth = await fetch(`${base}/api/update/manifest?kind=dex`);
  assert.equal(noAuth.status, 401);
});

test('update: скачивание артефакта по ссылке из манифеста', async (t) => {
  const { base } = await start(t);
  const body = Buffer.from('apk-file-content');
  const out = await (await upload(base, body)).json();

  const res = await fetch(`${base}${out.url}?token=${UPDATE_TOKEN}`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/octet-stream');
  assert.deepEqual(Buffer.from(await res.arrayBuffer()), body);

  const byHeader = await fetch(`${base}${out.url}`, { headers: { 'x-agent-token': AGENT_TOKEN } });
  assert.equal(byHeader.status, 200);

  const noAuth = await fetch(`${base}${out.url}`);
  assert.equal(noAuth.status, 401);

  const traversal = await fetch(`${base}/update/..%2F..%2Fpackage.json?token=${UPDATE_TOKEN}`);
  assert.equal(traversal.status, 404);
});

test('update: notify — бабушка онлайн получает update-available', async (t) => {
  const { base, url } = await start(t);
  const body = Buffer.from('apk-v2');
  const out = await (await upload(base, body, { version: '2.0' })).json();

  const pair = pairId('notify');
  const grandma = await TestClient.connect(url);
  await grandma.hello({ pairId: pair, role: 'grandma' });

  const res = await fetch(`${base}/api/update/notify`, {
    method: 'POST',
    headers: { 'x-agent-token': AGENT_TOKEN, 'content-type': 'application/json' },
    body: JSON.stringify({ kind: 'apk', pairId: pair }),
  });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, notified: 1 });

  const msg = await grandma.next('update-available');
  assert.deepEqual(msg, {
    t: 'update-available',
    kind: 'apk',
    version: '2.0',
    url: out.url,
    sha256: sha256hex(body),
    size: body.length,
  });
});

test('update: notify без pairId — всем онлайн-бабушкам, офлайн не считается', async (t) => {
  const { base, url } = await start(t);
  await upload(base, Buffer.from('apk-v3'));

  const online1 = await TestClient.connect(url);
  await online1.hello({ pairId: pairId('all-a'), role: 'grandma' });
  const online2 = await TestClient.connect(url);
  await online2.hello({ pairId: pairId('all-b'), role: 'grandma' });
  const gone = await TestClient.connect(url);
  await gone.hello({ pairId: pairId('all-c'), role: 'grandma' });
  await gone.close();
  // Даём серверу обработать close, чтобы реестр успел пометить офлайн.
  await new Promise((done) => setTimeout(done, 150));

  const res = await fetch(`${base}/api/update/notify`, {
    method: 'POST',
    headers: { 'x-agent-token': AGENT_TOKEN, 'content-type': 'application/json' },
    body: JSON.stringify({ kind: 'apk' }),
  });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, notified: 2 });

  await online1.next('update-available');
  await online2.next('update-available');
});

test('update: notify без манифеста — 404', async (t) => {
  const { base } = await start(t);
  const res = await fetch(`${base}/api/update/notify`, {
    method: 'POST',
    headers: { 'x-agent-token': AGENT_TOKEN, 'content-type': 'application/json' },
    body: JSON.stringify({ kind: 'dex' }),
  });
  assert.equal(res.status, 404);
});

test('update: дефолтный токен Gradle (pult-local-test) тоже принимается', async (t) => {
  const { base } = await start(t, { updateToken: 'srv-token' });
  const put = await fetch(`${base}/api/update`, { method: 'PUT' });
  assert.equal(put.status, 401); // без токена — всё так же закрыто
  const manifest = await fetch(`${base}/api/update/manifest?kind=apk&token=pult-local-test`);
  // манифеста нет в чистом окружении — важен сам факт прохождения auth (не 401)
  assert.notEqual(manifest.status, 401);
});
