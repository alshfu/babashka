import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

/** Идентификаторы протокола — 16 байт CSPRNG в base64url (docs/protocol.md §1). */
export function newId(bytes = 16) {
  return randomBytes(bytes).toString('base64url');
}

export function sha256Base64url(value) {
  return createHash('sha256').update(value, 'utf8').digest('base64url');
}

/** Сравнение секретоподобных строк без утечки по времени. */
export function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

/**
 * Эфемерные креды TURN по схеме coturn REST (`use-auth-secret`):
 * username = "<expiry>:<pairId>", credential = base64(HMAC-SHA1(secret, username)).
 * Статический секрет живёт только на сервере и в coturn — устройства его не знают.
 */
export function turnCredentials(pairId, { secret, ttlSeconds, now = Date.now() }) {
  const expiry = Math.floor(now / 1000) + ttlSeconds;
  const username = `${expiry}:${pairId}`;
  const credential = createHmac('sha1', secret).update(username).digest('base64');
  return { username, credential, expiresAt: expiry * 1000 };
}
