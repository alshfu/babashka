import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test, { describe } from 'node:test';

/**
 * Общие тест-векторы пары (`protocol/test-vectors.json`) прогоняются обеими
 * реализациями: Kotlin (android/core) и Node (здесь).
 *
 * Здесь транскрипт собирается заново, по тексту docs/protocol.md §4, а не переиспользует
 * код клиентов, — иначе тест проверял бы сам себя.
 */
const vectors = JSON.parse(
  readFileSync(new URL('../../protocol/test-vectors.json', import.meta.url), 'utf8'),
);

const secret = Buffer.from(vectors.pairSecretBase64url, 'base64url');
const hmac = (message) => createHmac('sha256', secret).update(message, 'utf8').digest('base64url');
const normalize = (algorithm, hex) => `${algorithm.trim().toLowerCase()} ${hex.trim().toUpperCase()}`;

describe('тест-векторы пары', () => {
  test('секрет пары — 32 байта', () => {
    assert.equal(secret.length, 32);
  });

  test('нормализация отпечатка DTLS', () => {
    for (const item of vectors.fingerprintNormalization) {
      assert.equal(normalize(item.algorithm, item.hex), item.expected);
    }
  });

  test('токен журнала', () => {
    assert.equal(hmac(vectors.journalContext), vectors.journalToken);
  });

  test('MAC обеих сторон совпадают с векторами', () => {
    for (const item of vectors.auth) {
      const fpH = normalize(item.fpAlgH, item.fpHexH);
      const fpG = normalize(item.fpAlgG, item.fpHexG);
      assert.equal(fpH, item.fpH);
      assert.equal(fpG, item.fpG);

      const transcript = [vectors.authContext, item.sessionId, item.nonceH, item.nonceG, fpH, fpG].join('\n');
      assert.equal(transcript, item.transcript);
      assert.equal(hmac(`grandma\n${transcript}`), item.macG);
      assert.equal(hmac(`helper\n${transcript}`), item.macH);
    }
  });

  test('подмена любой части транскрипта ломает MAC', () => {
    const item = vectors.auth[0];
    const parts = [vectors.authContext, item.sessionId, item.nonceH, item.nonceG, item.fpH, item.fpG];
    for (let index = 1; index < parts.length; index += 1) {
      const tampered = [...parts];
      tampered[index] = `${tampered[index]}x`; // ровно так выглядит MITM на сигналинге
      assert.notEqual(hmac(`grandma\n${tampered.join('\n')}`), item.macG, `часть ${index} не защищена`);
    }
  });

  test('роли не взаимозаменяемы: MAC бабушки не подходит помощнику', () => {
    for (const item of vectors.auth) assert.notEqual(item.macG, item.macH);
  });
});
