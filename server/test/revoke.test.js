import assert from 'node:assert/strict';
import test, { after, before, describe } from 'node:test';

import { TestClient, pairId, startTestServer } from './helpers.js';

/**
 * Отзыв доступа инициирует помощник, но сервер его не подделывает — он лишь доставляет
 * подписанную команду. Здесь проверяем маршрутизацию и отложенную доставку;
 * проверку самой подписи делает устройство бабушки (см. PairAuthTest).
 */
describe('отзыв доступа', () => {
  let ctx;

  before(async () => {
    ctx = await startTestServer();
  });

  after(async () => {
    await ctx.app.close();
  });

  test('revoke от помощника доставляется онлайн-бабушке', async () => {
    const id = pairId('revoke');
    const grandma = await TestClient.connect(ctx.url);
    const helper = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'revoke', nonce: 'nonce-1', mac: 'mac-1' });
    const delivered = await grandma.next('revoke');
    assert.equal(delivered.nonce, 'nonce-1');
    assert.equal(delivered.mac, 'mac-1');

    const queued = await helper.next('revoke-queued');
    assert.equal(queued.online, true);

    grandma.send({ t: 'revoke-ack', ok: true });
    const ack = await helper.next('revoke-ack');
    assert.equal(ack.ok, true);

    await grandma.close();
    await helper.close();
  });

  test('revoke ждёт офлайн-бабушку и приходит на следующем hello', async () => {
    const id = pairId('revoke-offline');
    const helper = await TestClient.connect(ctx.url);
    await helper.hello({ pairId: id, role: 'helper' });

    helper.send({ t: 'revoke', nonce: 'nonce-2', mac: 'mac-2' });
    const queued = await helper.next('revoke-queued');
    assert.equal(queued.online, false);

    // Телефон бабушки появляется позже — отзыв ждёт его.
    const grandma = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });
    const delivered = await grandma.next('revoke');
    assert.equal(delivered.nonce, 'nonce-2');

    await grandma.close();
    await helper.close();
  });

  test('revoke от роли grandma не маршрутизируется', async () => {
    const id = pairId('revoke-wrong');
    const grandma = await TestClient.connect(ctx.url);
    await grandma.hello({ pairId: id, role: 'grandma' });

    grandma.send({ t: 'revoke', nonce: 'n', mac: 'm' });
    const error = await grandma.next('error');
    assert.equal(error.code, 'wrong-role');

    await grandma.close();
  });
});
