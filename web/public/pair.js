import {
  decodePairingPacket,
  encodePairingPacket,
  importPairSecret,
  journalToken,
  randomBase64url,
  toBase64url,
} from './protocol.js';

/**
 * Хранение пары в браузере.
 *
 * Секрет живёт как НЕизвлекаемый CryptoKey в IndexedDB: его можно использовать
 * для подписи, но нельзя прочитать из JS. Это слабее Android Keystore, но заметно
 * лучше строки в localStorage (docs/android-helper.md §3).
 */

const DB_NAME = 'pult';
const STORE = 'pair';
const KEY = 'current';

function openDb() {
  return new Promise((done, fail) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => done(request.result);
    request.onerror = () => fail(request.error);
  });
}

async function withStore(mode, action) {
  const db = await openDb();
  try {
    return await new Promise((done, fail) => {
      const tx = db.transaction(STORE, mode);
      const request = action(tx.objectStore(STORE));
      request.onsuccess = () => done(request.result);
      request.onerror = () => fail(request.error);
    });
  } finally {
    db.close();
  }
}

const scopedKey = (scope) => (scope ? `${KEY}:${scope}` : KEY);

export const loadPair = (scope) => withStore('readonly', (store) => store.get(scopedKey(scope)));

export const savePair = (record, scope) =>
  withStore('readwrite', (store) => store.put(record, scopedKey(scope)));

export const forgetPair = (scope) =>
  withStore('readwrite', (store) => store.delete(scopedKey(scope)));

/**
 * Создание пары на стороне бабушки. Сырые байты секрета живут ровно столько,
 * сколько нужно, чтобы собрать пакет для передачи вблизи, — дальше только CryptoKey.
 */
export async function createPair({ name, url, scope }) {
  const pairId = randomBase64url(16);
  const secretBytes = crypto.getRandomValues(new Uint8Array(32));
  const packet = encodePairingPacket({ pairId, secretBytes, name, url });

  const key = await importPairSecret(secretBytes);
  secretBytes.fill(0);

  const record = {
    pairId,
    name,
    url,
    key,
    journalToken: await journalToken(key),
    createdAt: Date.now(),
  };
  await savePair(record, scope);
  return { record, packet };
}

/** Приём пакета на стороне помощника: NFC, QR или (в отладочном стенде) ссылка. */
export async function adoptPairingPacket(packet, { scope, fallbackUrl } = {}) {
  const { pairId, secretBytes, name, url } = decodePairingPacket(packet);
  const key = await importPairSecret(secretBytes);
  secretBytes.fill(0);

  const record = {
    pairId,
    name,
    url: url ?? fallbackUrl,
    key,
    journalToken: await journalToken(key),
    createdAt: Date.now(),
  };
  await savePair(record, scope);
  return record;
}

/** Отпечаток пары для показа человеку: «та ли это пара» без раскрытия секрета. */
export async function pairFingerprint(record) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(record.pairId));
  return toBase64url(digest).slice(0, 8);
}
