import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test, { describe } from 'node:test';

import {
  authTranscript,
  fingerprintFromSdp,
  fromBase64url,
  importPairSecret,
  journalToken,
  macEquals,
  macForRole,
  normalizeFingerprint,
  randomBase64url,
  toBase64url,
} from '../public/protocol.js';

/**
 * Браузерная реализация крипто прогоняется по тем же векторам, что сервер и Android.
 * Модуль `protocol.js` намеренно не трогает DOM — поэтому его можно проверить в Node:
 * Web Crypto, btoa/atob и TextEncoder здесь настоящие, те же самые.
 */
const vectors = JSON.parse(
  readFileSync(new URL('../../protocol/test-vectors.json', import.meta.url), 'utf8'),
);

const pairKey = () => importPairSecret(fromBase64url(vectors.pairSecretBase64url));

describe('веб-панель: крипто пары', () => {
  test('base64url туда и обратно', () => {
    const bytes = new Uint8Array([0, 1, 250, 251, 252, 253, 254, 255]);
    assert.deepEqual([...fromBase64url(toBase64url(bytes))], [...bytes]);
    assert.equal(toBase64url(bytes).includes('+'), false);
    assert.equal(toBase64url(bytes).includes('/'), false);
    assert.equal(toBase64url(bytes).includes('='), false);
  });

  test('nonce — 16 байт и не повторяется', () => {
    const seen = new Set();
    for (let i = 0; i < 1000; i += 1) {
      const nonce = randomBase64url(16);
      assert.equal(fromBase64url(nonce).length, 16);
      assert.equal(seen.has(nonce), false);
      seen.add(nonce);
    }
  });

  test('нормализация отпечатка совпадает с векторами', () => {
    for (const item of vectors.fingerprintNormalization) {
      assert.equal(normalizeFingerprint(item.algorithm, item.hex), item.expected);
    }
  });

  test('отпечаток вынимается из SDP', () => {
    const sdp = ['v=0', 'a=fingerprint:SHA-256 aa:bb:cc', 'a=setup:actpass'].join('\r\n');
    assert.equal(fingerprintFromSdp(sdp), 'sha-256 AA:BB:CC');
    assert.throws(() => fingerprintFromSdp('v=0'), /нет a=fingerprint/);
  });

  test('MAC обеих сторон совпадают с векторами', async () => {
    const key = await pairKey();
    for (const item of vectors.auth) {
      const parts = {
        sessionId: item.sessionId,
        nonceH: item.nonceH,
        nonceG: item.nonceG,
        fpH: item.fpH,
        fpG: item.fpG,
      };
      assert.equal(authTranscript(parts), item.transcript);
      assert.equal(await macForRole(key, 'grandma', parts), item.macG);
      assert.equal(await macForRole(key, 'helper', parts), item.macH);
    }
  });

  test('токен журнала совпадает с вектором', async () => {
    assert.equal(await journalToken(await pairKey()), vectors.journalToken);
  });

  test('секрет пары неизвлекаем из JS', async () => {
    const key = await pairKey();
    assert.equal(key.extractable, false);
    await assert.rejects(() => crypto.subtle.exportKey('raw', key));
  });

  test('подмена отпечатка (MITM на сигналинге) ломает проверку', async () => {
    const key = await pairKey();
    const item = vectors.auth[0];
    const tampered = await macForRole(key, 'grandma', {
      sessionId: item.sessionId,
      nonceH: item.nonceH,
      nonceG: item.nonceG,
      fpH: 'sha-256 DE:AD:BE:EF', // сервер подсунул свой отпечаток
      fpG: item.fpG,
    });
    assert.equal(macEquals(tampered, item.macG), false);
  });

  test('сравнение MAC: длина и содержимое', () => {
    assert.equal(macEquals('abc', 'abc'), true);
    assert.equal(macEquals('abc', 'abd'), false);
    assert.equal(macEquals('abc', 'abcd'), false);
    assert.equal(macEquals('abc', null), false);
  });
});
