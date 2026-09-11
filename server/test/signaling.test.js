import assert from 'node:assert/strict';
import test, { after, before, describe } from 'node:test';

import { TestClient, fakeSdp, pairId, startTestServer } from './helpers.js';

/**
 * Тесты сценария 1-2-3 и правил маршрутизации.
 * Список того, что здесь обязано проверяться, — docs/testing.md §1.
 */
describe('сигналинг', () => {
  let ctx;

  before(async () => {
    ctx = await startTestServer({ consentTimeoutMs: 300, sessionIdleTimeoutMs: 5000 });
  });

  after(async () => {
    await ctx.app.close();
  });

  test('hello: комната, присутствие и ICE-конфиг', async () => {
    const id = pairId('hello');
    const grandma = await TestClient.connect(ctx.url);
    const first = await grandma.hello({ pairId: id, role: 'grandma' });

    assert.equal(first.peerOnline, false);
    assert.ok(Array.isArray(first.iceServers) && first.iceServers.length > 0);

    const helper = await TestClient.connect(ctx.url);
    const second = await helper.hello({ pairId: id, role: 'helper' });
    assert.equal(second.peerOnline, true);

    const presence = await grandma.next('peer-state');
    assert.equal(presence.online, true);

    await helper.close();
    const gone = await grandma.next('peer-state');
    assert.equal(gone.online, false);

    await grandma.close();
  });

  test('сообщение до hello закрывает соединение', async () => {
    const client = await TestClient.connect(ctx.url);
    client.send({ t: 'help-request' });
    const error = await client.next('error');
    assert.equal(error.code, 'not-authenticated');
  });

  test('полный путь: запрос → согласие → offer/answer → auth-confirm', async () => {
    const id = pairId('happy');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'help-request', note: 'покажу почту' });
    const ack = await helper.next('help-request-sent');
    const incoming = await grandma.next('help-request');

    assert.equal(incoming.sessionId, ack.sessionId);
    assert.equal(incoming.from, 'helper');
    assert.equal(incoming.note, 'покажу почту');
    assert.equal(ack.peerOnline, true);

    const sessionId = ack.sessionId;
    grandma.send({ t: 'consent-granted', sessionId });
    await helper.next('consent-granted');

    helper.send({ t: 'offer', sessionId, sdp: fakeSdp(), nonceH: 'nonce-h' });
    const offer = await grandma.next('offer');
    assert.equal(offer.sdp, fakeSdp());
    assert.equal(offer.nonceH, 'nonce-h');

    grandma.send({ t: 'answer', sessionId, sdp: fakeSdp('11:22'), nonceG: 'nonce-g', macG: 'mac-g' });
    const answer = await helper.next('answer');
    assert.equal(answer.macG, 'mac-g');

    helper.send({ t: 'auth-confirm', sessionId, macH: 'mac-h' });
    await grandma.next('auth-confirm');

    assert.equal(ctx.app.hub.inspect(id).session.state, 'connected');

    grandma.send({ t: 'session-end', sessionId, reason: 'stopped-by-grandma' });
    await helper.next('session-end');

    const entry = ctx.app.journal.list(id).at(-1);
    assert.equal(entry.reason, 'stopped-by-grandma');
    assert.equal(entry.screenShown, true);
    assert.equal(entry.requestedBy, 'helper');
    assert.ok(entry.consentedAt > 0);

    await grandma.close();
    await helper.close();
  });

  test('инициировать помощь может только помощник', async () => {
    const id = pairId('initiator');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    grandma.send({ t: 'help-request' });
    const error = await grandma.next('error');
    assert.equal(error.code, 'wrong-role');

    const seen = await helper.silentFor(150);
    assert.equal(seen.some((m) => m.t === 'help-request'), false);
    assert.equal(ctx.app.hub.inspect(id).session, null);

    await grandma.close();
    await helper.close();
  });

  test('согласие может дать только бабушка', async () => {
    const id = pairId('consent');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'help-request' });
    const { sessionId } = await helper.next('help-request-sent');
    await grandma.next('help-request');

    // Помощник пытается «разрешить сам себе».
    helper.send({ t: 'consent-granted', sessionId });
    const error = await helper.next('error');
    assert.equal(error.code, 'wrong-role');
    assert.equal(ctx.app.hub.inspect(id).session.state, 'awaiting-consent');

    await grandma.close();
    await helper.close();
  });

  test('offer до согласия отклоняется', async () => {
    const id = pairId('early-offer');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'offer', sessionId: 'нет-такой', sdp: fakeSdp(), nonceH: 'n' });
    const error = await helper.next('error');
    assert.equal(error.code, 'unknown-session');

    const seen = await grandma.silentFor(150);
    assert.equal(seen.some((m) => m.t === 'offer'), false);

    await grandma.close();
    await helper.close();
  });

  test('отказ бабушки завершает сессию и попадает в журнал', async () => {
    const id = pairId('declined');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'help-request' });
    const { sessionId } = await helper.next('help-request-sent');
    await grandma.next('help-request');

    grandma.send({ t: 'consent-denied', sessionId, reason: 'declined' });
    const relayed = await helper.next('consent-denied');
    assert.equal(relayed.reason, 'declined');

    const entry = ctx.app.journal.list(id).at(-1);
    assert.equal(entry.reason, 'declined');
    assert.equal(entry.screenShown, false);
    assert.equal(entry.consentedAt, null);

    await grandma.close();
    await helper.close();
  });

  test('молчание 90 с → no-answer обеим сторонам', async () => {
    const id = pairId('no-answer');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'help-request' });
    await helper.next('help-request-sent');
    await grandma.next('help-request');

    const endHelper = await helper.next('session-end');
    const endGrandma = await grandma.next('session-end');
    assert.equal(endHelper.reason, 'no-answer');
    assert.equal(endGrandma.reason, 'no-answer');
    assert.equal(ctx.app.journal.list(id).at(-1).reason, 'no-answer');

    await grandma.close();
    await helper.close();
  });

  test('второй сокет той же роли вытесняет первый', async () => {
    const id = pairId('replace');
    const first = await TestClient.connect(ctx.url);
    await first.hello({ pairId: id, role: 'helper' });

    const second = await TestClient.connect(ctx.url);
    await second.hello({ pairId: id, role: 'helper' });

    const error = await first.next('error');
    assert.equal(error.code, 'replaced');
    assert.deepEqual(ctx.app.hub.inspect(id).roles, ['helper']);

    await second.close();
  });

  test('несколько помощников с разными deviceId сосуществуют (тестирование с нескольких устройств)', async (t) => {
    const id = pairId('multi-helper');
    const grandma = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    const phone = await TestClient.connect(ctx.url);
    await phone.hello({ pairId: id, role: 'helper', deviceId: 'dev-phone' });
    const tablet = await TestClient.connect(ctx.url);
    const ok = await tablet.hello({ pairId: id, role: 'helper', deviceId: 'dev-tablet' });

    // Второй помощник НЕ вытесняет первого, бабушка онлайн для обоих.
    assert.equal(ok.peerOnline, true);
    assert.deepEqual(ctx.app.hub.inspect(id).roles.sort(), ['grandma', 'helper', 'helper']);

    // Сессия от первого помощника; согласие бабушки уходит именно ему, второй не получает.
    phone.send({ t: 'help-request' });
    await phone.next('help-request-sent');
    await grandma.next('help-request');
    grandma.send({ t: 'consent-granted', sessionId: ctx.app.hub.inspect(id).session.id });
    await phone.next('consent-granted');
    const leaked = await tablet.silentFor(150);
    assert.equal(leaked.some((m) => m.t === 'consent-granted' || m.t === 'session-end'), false);

    // Второй помощник во время чужой сессии получает wrong-state, а не рвёт чужую сессию.
    tablet.send({ t: 'help-request' });
    const busy = await tablet.next('error');
    assert.equal(busy.code, 'wrong-state');

    // Уход последнего помощника сообщает бабушке «офлайн» ровно один раз.
    await grandma.next('peer-state'); // online:true при подключении phone
    await grandma.next('peer-state'); // online:true при подключении tablet
    await phone.close();
    await tablet.close();
    const offline = await grandma.next('peer-state');
    assert.equal(offline.online, false);

    await grandma.close();
  });

  test('запрос ждёт бабушку, которая подключилась позже (сценарий «телефон спал»)', async () => {
    const id = pairId('wake');
    const helper = await TestClient.connect(ctx.url);
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'help-request' });
    const ack = await helper.next('help-request-sent');
    assert.equal(ack.peerOnline, false);

    const grandma = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    const pending = await grandma.next('help-request');
    assert.equal(pending.sessionId, ack.sessionId);

    await grandma.close();
    await helper.close();
  });

  test('битый кадр не рвёт соединение', async () => {
    const id = pairId('junk');
    const client = await TestClient.connect(ctx.url);
    await client.hello({ pairId: id, role: 'helper' });

    client.sendRaw('{это не json');
    const error = await client.next('error');
    assert.equal(error.code, 'bad-message');

    client.send({ t: 'ping' });
    await client.next('pong');

    await client.close();
  });

  test('слишком большое сообщение отклоняется', async () => {
    const id = pairId('big');
    const client = await TestClient.connect(ctx.url);
    await client.hello({ pairId: id, role: 'helper' });

    client.send({ t: 'help-request', note: 'x'.repeat(70_000) });
    const error = await client.next('error');
    assert.equal(error.code, 'bad-message');

    await client.close();
  });
});
