import assert from 'node:assert/strict';
import test, { after, before, describe } from 'node:test';

import { TestClient, pairId, startTestServer } from './helpers.js';
import { sha256Base64url } from '../src/ids.js';

/**
 * Журнал: доступен паре, недоступен всем остальным, содержит и несостоявшиеся сессии
 * (docs/protocol.md §7, docs/security.md §6).
 */
describe('журнал', () => {
  let ctx;

  before(async () => {
    ctx = await startTestServer({ consentTimeoutMs: 120 });
  });

  after(async () => {
    await ctx.app.close();
  });

  const fetchJournal = (id, token, method = 'GET') =>
    fetch(`${ctx.base}/api/journal?pairId=${encodeURIComponent(id)}`, {
      method,
      headers: token ? { 'x-journal-token': token } : {},
    });

  test('доступ по токену, отказ без него и с чужим', async () => {
    const id = pairId('journal');
    const token = 'jt-9f3a2b1c-primary';
    const grandma = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma', journalTokenHash: sha256Base64url(token) });

    const helper = await TestClient.connect(ctx.url);
    await helper.hello({ pairId: id, role: 'helper' });
    helper.send({ t: 'help-request' });
    await helper.next('help-request-sent');
    await helper.next('session-end'); // no-answer

    const ok = await fetchJournal(id, token);
    assert.equal(ok.status, 200);
    const body = await ok.json();
    assert.equal(body.entries.length, 1);
    assert.equal(body.entries[0].reason, 'no-answer');

    assert.equal((await fetchJournal(id, '')).status, 403);
    assert.equal((await fetchJournal(id, 'jt-wrong-token')).status, 403);

    await grandma.close();
    await helper.close();
  });

  test('несуществующая пара отвечает так же, как неверный токен', async () => {
    const response = await fetchJournal(pairId('нет'), 'jt-any-token');
    assert.equal(response.status, 403, 'наличие пары не должно утекать');
  });

  test('в журнале нет содержимого помощи', async () => {
    const id = pairId('content');
    const token = 'jt-2c4d6e8f-content';
    const grandma = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma', journalTokenHash: sha256Base64url(token) });
    const helper = await TestClient.connect(ctx.url);
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'help-request', note: 'секретная причина' });
    const { sessionId } = await helper.next('help-request-sent');
    await grandma.next('help-request');
    grandma.send({ t: 'consent-granted', sessionId });
    await helper.next('consent-granted');
    helper.send({ t: 'offer', sessionId, sdp: 'v=0 очень секретный sdp', nonceH: 'n' });
    await grandma.next('offer');
    grandma.send({ t: 'session-end', sessionId, reason: 'stopped-by-grandma' });
    await helper.next('session-end');

    const body = await (await fetchJournal(id, token)).json();
    const dump = JSON.stringify(body);
    assert.equal(dump.includes('секретная причина'), false, 'note не должен попадать в журнал');
    assert.equal(dump.includes('sdp'), false, 'sdp не должен попадать в журнал');
    assert.deepEqual(Object.keys(body.entries[0]).sort(), [
      'consentedAt', 'endedAt', 'pairId', 'reason', 'requestedAt', 'requestedBy', 'screenShown', 'sessionId',
    ]);

    await grandma.close();
    await helper.close();
  });

  test('удаление журнала пары по запросу', async () => {
    const id = pairId('erase');
    const token = 'jt-7a1b3c5d-erase';
    const grandma = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma', journalTokenHash: sha256Base64url(token) });
    const helper = await TestClient.connect(ctx.url);
    await helper.hello({ pairId: id, role: 'helper' });
    helper.send({ t: 'help-request' });
    await helper.next('help-request-sent');
    await helper.next('session-end');

    assert.equal((await (await fetchJournal(id, token)).json()).entries.length, 1);
    assert.equal((await fetchJournal(id, token, 'DELETE')).status, 200);
    // Пара забыта вместе с токеном: повторный запрос уже неотличим от «нет такой пары».
    assert.equal((await fetchJournal(id, token)).status, 403);

    await grandma.close();
    await helper.close();
  });

  test('/healthz и /metrics отвечают', async () => {
    const health = await (await fetch(`${ctx.base}/healthz`)).json();
    assert.equal(health.ok, true);

    const metrics = await (await fetch(`${ctx.base}/metrics`)).text();
    assert.match(metrics, /pult_sessions_started_total \d+/);
    assert.match(metrics, /pult_auth_failed_total \d+/);
  });
});
