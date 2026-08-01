/**
 * Протокол «Пульта» в браузере: MAC пары, nonce, разбор отпечатков DTLS.
 *
 * Это ровно та же арифметика, что в `android/core` и в тест-векторах
 * `protocol/test-vectors.json`. Расхождение здесь = клиенты не договорятся,
 * поэтому менять только вместе с векторами.
 */

export const PROTOCOL_VERSION = 1;
export const AUTH_CONTEXT = 'pult/v1/auth';
export const JOURNAL_CONTEXT = 'pult/v1/journal';
export const REVOKE_CONTEXT = 'pult/v1/revoke';

// ── base64url ───────────────────────────────────────────────────────────────

export function toBase64url(bytes) {
  let binary = '';
  for (const byte of new Uint8Array(bytes)) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

export function fromBase64url(text) {
  const padded = text.replaceAll('-', '+').replaceAll('_', '/');
  const binary = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, '='));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function randomBase64url(byteLength = 16) {
  return toBase64url(crypto.getRandomValues(new Uint8Array(byteLength)));
}

// ── Ключ пары ───────────────────────────────────────────────────────────────

/**
 * Секрет пары импортируется как НЕизвлекаемый ключ: подписать им можно,
 * прочитать из JS — нет. Даже XSS не уносит секрет с собой.
 */
export function importPairSecret(rawBytes) {
  return crypto.subtle.importKey(
    'raw',
    rawBytes,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

const encoder = new TextEncoder();

export async function hmac(key, message) {
  const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(message));
  return toBase64url(signature);
}

export const journalToken = (key) => hmac(key, JOURNAL_CONTEXT);

/** Подпись команды отзыва: сервер её подделать не может, телефон бабушки проверяет локально. */
export const revokeMac = (key, pairId, nonce) =>
  hmac(key, `${REVOKE_CONTEXT}\n${pairId}\n${nonce}`);

// ── Отпечатки DTLS ──────────────────────────────────────────────────────────

/**
 * Нормализация отпечатка: алгоритм в нижнем регистре, hex — в верхнем.
 * Браузеры и Android пишут их по-разному, а в MAC должна входить одна и та же строка.
 */
export function normalizeFingerprint(algorithm, hex) {
  return `${algorithm.trim().toLowerCase()} ${hex.trim().toUpperCase()}`;
}

export function fingerprintFromSdp(sdp) {
  const match = /^a=fingerprint:(\S+)\s+(\S+)/m.exec(sdp ?? '');
  if (!match) throw new Error('SDP has no a=fingerprint — the connection is not secure');
  return normalizeFingerprint(match[1], match[2]);
}

// ── Взаимная аутентификация ─────────────────────────────────────────────────

/** Транскрипт, общий для обеих сторон (docs/protocol.md §4). */
export function authTranscript({ sessionId, nonceH, nonceG, fpH, fpG }) {
  return [AUTH_CONTEXT, sessionId, nonceH, nonceG, fpH, fpG].join('\n');
}

export const macForRole = (key, role, parts) =>
  hmac(key, `${role}\n${authTranscript(parts)}`);

/** Сравнение MAC без утечки по времени: длина + побитовое накопление разницы. */
export function macEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ── Пакет спаривания ────────────────────────────────────────────────────────

/**
 * Пакет, который передаётся ТОЛЬКО вблизи — по NFC или QR с экрана (docs/pairing.md).
 * Ни отправка ссылкой, ни диктовка по телефону не предусмотрены и не должны появиться.
 */
export function encodePairingPacket({ pairId, secretBytes, name, url }) {
  return toBase64url(
    encoder.encode(JSON.stringify({ v: 1, pid: pairId, sec: toBase64url(secretBytes), name, url })),
  );
}

export function decodePairingPacket(packed) {
  const json = new TextDecoder().decode(fromBase64url(packed));
  const data = JSON.parse(json);
  if (data.v !== 1 || !data.pid || !data.sec) throw new Error('pairing packet not recognized');
  return { pairId: data.pid, secretBytes: fromBase64url(data.sec), name: data.name ?? 'Alex', url: data.url };
}
