import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import test, { describe } from 'node:test';

import {
  ERROR,
  SESSION_STATE,
  checkRoute,
  parseMessage,
  peerRole,
  relayPayload,
  validateHello,
} from '../src/protocol.js';
import { safeEqual, sha256Base64url, turnCredentials } from '../src/ids.js';

describe('разбор сообщений', () => {
  test('не-JSON, не-объект, без типа — всё bad-message', () => {
    for (const raw of ['{битый', '"строка"', '[1,2]', 'null', '{}', '{"t":""}']) {
      assert.equal(parseMessage(raw, 1024).code, ERROR.BAD_MESSAGE, raw);
    }
  });

  test('бинарный кадр отвергается', () => {
    assert.equal(parseMessage(null, 1024).code, ERROR.BAD_MESSAGE);
  });

  test('размер считается в байтах, а не в символах', () => {
    const cyrillic = JSON.stringify({ t: 'x', note: 'я'.repeat(40) }); // 2 байта на символ
    assert.equal(parseMessage(cyrillic, 50).code, ERROR.BAD_MESSAGE);
    assert.equal(parseMessage(cyrillic, 500).ok, true);
  });
});

describe('hello', () => {
  const base = { v: 1, role: 'grandma', pairId: 'p1', deviceId: 'd1' };

  test('корректный hello принимается', () => {
    assert.equal(validateHello(base).ok, true);
  });

  test('чужая версия протокола', () => {
    assert.equal(validateHello({ ...base, v: 2 }).code, ERROR.UNSUPPORTED_VERSION);
  });

  test('неизвестная роль', () => {
    assert.equal(validateHello({ ...base, role: 'сосед' }).code, ERROR.BAD_MESSAGE);
  });
});

describe('правила маршрутизации', () => {
  test('инициировать помощь может только помощник', () => {
    assert.equal(checkRoute('help-request', 'helper', null), null);
    assert.equal(checkRoute('help-request', 'grandma', null), ERROR.WRONG_ROLE);
  });

  test('согласие может дать только бабушка', () => {
    assert.equal(checkRoute('consent-granted', 'grandma', SESSION_STATE.AWAITING_CONSENT), null);
    assert.equal(checkRoute('consent-granted', 'helper', SESSION_STATE.AWAITING_CONSENT), ERROR.WRONG_ROLE);
  });

  test('offer имеет смысл только после согласия', () => {
    assert.equal(checkRoute('offer', 'helper', SESSION_STATE.AWAITING_CONSENT), ERROR.WRONG_STATE);
    assert.equal(checkRoute('offer', 'helper', SESSION_STATE.CONSENTED), null);
    assert.equal(checkRoute('offer', 'helper', null), ERROR.UNKNOWN_SESSION);
  });

  test('неизвестный тип не маршрутизируется', () => {
    assert.equal(checkRoute('shutdown-everything', 'helper', SESSION_STATE.CONNECTED), ERROR.BAD_MESSAGE);
  });

  test('роли зеркальны', () => {
    assert.equal(peerRole('grandma'), 'helper');
    assert.equal(peerRole('helper'), 'grandma');
  });
});

describe('ретрансляция', () => {
  test('пересылаются только поля из белого списка', () => {
    const payload = relayPayload(
      'offer',
      { t: 'offer', sdp: 'v=0', nonceH: 'n', secret: 'не должно уехать', sessionId: 'подмена' },
      'session-1',
    );
    assert.deepEqual(payload, { t: 'offer', sessionId: 'session-1', sdp: 'v=0', nonceH: 'n' });
  });

  test('sessionId ставит сервер, а не отправитель', () => {
    const payload = relayPayload('ice', { t: 'ice', candidate: {}, sessionId: 'чужая' }, 'моя');
    assert.equal(payload.sessionId, 'моя');
  });
});

describe('вспомогательная крипта', () => {
  test('TURN-креды совпадают со схемой coturn REST', () => {
    const now = 1_700_000_000_000;
    const { username, credential } = turnCredentials('pair-1', { secret: 's3cret', ttlSeconds: 600, now });
    const expectedUser = `${Math.floor(now / 1000) + 600}:pair-1`;
    assert.equal(username, expectedUser);
    assert.equal(credential, createHmac('sha1', 's3cret').update(expectedUser).digest('base64'));
  });

  test('safeEqual: разная длина и разное содержимое не совпадают', () => {
    assert.equal(safeEqual('abc', 'abc'), true);
    assert.equal(safeEqual('abc', 'abd'), false);
    assert.equal(safeEqual('abc', 'abcd'), false);
    assert.equal(safeEqual('abc', undefined), false);
  });

  test('хеш токена журнала стабилен', () => {
    assert.equal(sha256Base64url('token'), sha256Base64url('token'));
    assert.notEqual(sha256Base64url('token'), sha256Base64url('token2'));
  });
});
