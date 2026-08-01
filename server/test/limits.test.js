import assert from 'node:assert/strict';
import test, { after, before, describe } from 'node:test';

import { TestClient, pairId, startTestServer } from './helpers.js';

/**
 * Лимиты частоты. Инициатива у помощника — значит, технически он может дёргать бабушку
 * бесконечно. Эти тесты фиксируют, что не может (docs/protocol.md §3).
 */
describe('лимиты запросов', () => {
  let ctx;

  before(async () => {
    ctx = await startTestServer({
      consentTimeoutMs: 120,
      helpRequestsPerHour: 3,
      maxConsecutiveNoAnswer: 2,
      messagesPerWindow: 10,
      rateWindowMs: 1000,
    });
  });

  after(async () => {
    await ctx.app.close();
  });

  test('превышение лимита запросов в час: ошибка помощнику, бабушке — тишина', async () => {
    const id = pairId('limit');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    // Три запроса, каждый честно отклонён бабушкой (значит, «подряд без ответа» не копится).
    for (let attempt = 0; attempt < 3; attempt += 1) {
      helper.send({ t: 'help-request' });
      const { sessionId } = await helper.next('help-request-sent');
      await grandma.next('help-request');
      grandma.send({ t: 'consent-denied', sessionId, reason: 'declined' });
      await helper.next('consent-denied');
    }

    helper.send({ t: 'help-request' });
    const error = await helper.next('error');
    assert.equal(error.code, 'too-many-requests');

    const seen = await grandma.silentFor(150);
    assert.equal(seen.some((m) => m.t === 'help-request'), false, 'бабушке не должно прийти ничего');

    await grandma.close();
    await helper.close();
  });

  test('несколько запросов без ответа подряд блокируют следующий', async () => {
    const id = pairId('no-answer-streak');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    for (let attempt = 0; attempt < 2; attempt += 1) {
      helper.send({ t: 'help-request' });
      await helper.next('help-request-sent');
      await helper.next('session-end'); // no-answer по таймауту
      await grandma.next('session-end');
    }

    helper.send({ t: 'help-request' });
    const error = await helper.next('error');
    assert.equal(error.code, 'too-many-requests');

    await grandma.close();
    await helper.close();
  });

  test('ответ бабушки сбрасывает счётчик «без ответа»', async () => {
    const id = pairId('streak-reset');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'help-request' });
    await helper.next('help-request-sent');
    await helper.next('session-end');
    await grandma.next('session-end');
    assert.equal(ctx.app.hub.inspect(id).consecutiveNoAnswer, 1);

    helper.send({ t: 'help-request' });
    const { sessionId } = await helper.next('help-request-sent');
    await grandma.next('help-request');
    grandma.send({ t: 'consent-granted', sessionId });
    await helper.next('consent-granted');

    assert.equal(ctx.app.hub.inspect(id).consecutiveNoAnswer, 0);

    await grandma.close();
    await helper.close();
  });

  test('флуд сообщениями → rate-limited, сокет жив', async () => {
    const id = pairId('flood');
    const client = await TestClient.connect(ctx.url);
    await client.hello({ pairId: id, role: 'helper' });

    for (let i = 0; i < 15; i += 1) client.send({ t: 'ping' });
    const error = await client.next('error');
    assert.equal(error.code, 'rate-limited');

    await client.close();
  });
});
