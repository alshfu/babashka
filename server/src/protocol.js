/**
 * Правила протокола, которые сервер обязан соблюдать (docs/protocol.md).
 *
 * Главное здесь — таблица ROUTES: она задаёт, кто что имеет право отправлять.
 * Именно она реализует два инварианта безопасности:
 *   · инициировать помощь может только помощник;
 *   · согласие может дать только бабушка.
 */

export const PROTOCOL_VERSION = 1;

export const ROLES = Object.freeze(['grandma', 'helper']);

export const ERROR = Object.freeze({
  BAD_MESSAGE: 'bad-message',
  NOT_AUTHENTICATED: 'not-authenticated',
  UNKNOWN_SESSION: 'unknown-session',
  PEER_OFFLINE: 'peer-offline',
  REPLACED: 'replaced',
  RATE_LIMITED: 'rate-limited',
  TOO_MANY_REQUESTS: 'too-many-requests',
  UNSUPPORTED_VERSION: 'unsupported-version',
  WRONG_ROLE: 'wrong-role',
  WRONG_STATE: 'wrong-state',
});

export const END_REASON = Object.freeze({
  STOPPED_BY_GRANDMA: 'stopped-by-grandma',
  STOPPED_BY_HELPER: 'stopped-by-helper',
  DECLINED: 'declined',
  NO_ANSWER: 'no-answer',
  CANCELLED: 'cancelled',
  AUTH_FAILED: 'auth-failed',
  PEER_LOST: 'peer-lost',
  TIMEOUT: 'timeout',
  ERROR: 'error',
});

/** Состояния сессии на сервере. Сервер не знает, что происходит внутри WebRTC. */
export const SESSION_STATE = Object.freeze({
  AWAITING_CONSENT: 'awaiting-consent',
  CONSENTED: 'consented',
  CONNECTED: 'connected',
});

/**
 * from — единственная роль, которой разрешено отправлять сообщение (`any` = обе);
 * state — в каком состоянии сессии сообщение имеет смысл (null = сессия не нужна).
 */
export const ROUTES = Object.freeze({
  // Отзыв доступа: инициирует только помощник, подпись проверяет телефон бабушки.
  // Сессия для этого не нужна.
  revoke: { from: 'helper', state: null },
  'revoke-ack': { from: 'grandma', state: null },

  'help-request': { from: 'helper', state: null, opensSession: true },
  'consent-granted': { from: 'grandma', state: [SESSION_STATE.AWAITING_CONSENT] },
  'consent-denied': { from: 'grandma', state: [SESSION_STATE.AWAITING_CONSENT] },
  offer: { from: 'helper', state: [SESSION_STATE.CONSENTED] },
  answer: { from: 'grandma', state: [SESSION_STATE.CONSENTED] },
  'auth-confirm': { from: 'helper', state: [SESSION_STATE.CONSENTED] },
  ice: { from: 'any', state: [SESSION_STATE.CONSENTED, SESSION_STATE.CONNECTED] },
  'session-end': {
    from: 'any',
    state: [SESSION_STATE.AWAITING_CONSENT, SESSION_STATE.CONSENTED, SESSION_STATE.CONNECTED],
  },
});

export const peerRole = (role) => (role === 'grandma' ? 'helper' : 'grandma');

const isNonEmptyString = (value, max = 512) =>
  typeof value === 'string' && value.length > 0 && value.length <= max;

/** Разбор входящего кадра. Ошибки описываются кодом, а не текстом от клиента. */
export function parseMessage(raw, maxBytes) {
  if (typeof raw !== 'string') {
    // Бинарных кадров в протоколе нет вообще.
    return { ok: false, code: ERROR.BAD_MESSAGE };
  }
  if (Buffer.byteLength(raw, 'utf8') > maxBytes) {
    return { ok: false, code: ERROR.BAD_MESSAGE };
  }
  let message;
  try {
    message = JSON.parse(raw);
  } catch {
    return { ok: false, code: ERROR.BAD_MESSAGE };
  }
  if (message === null || typeof message !== 'object' || Array.isArray(message)) {
    return { ok: false, code: ERROR.BAD_MESSAGE };
  }
  if (!isNonEmptyString(message.t, 64)) {
    return { ok: false, code: ERROR.BAD_MESSAGE };
  }
  return { ok: true, message };
}

export function validateHello(message) {
  if (message.v !== PROTOCOL_VERSION) return { ok: false, code: ERROR.UNSUPPORTED_VERSION };
  if (!ROLES.includes(message.role)) return { ok: false, code: ERROR.BAD_MESSAGE };
  if (!isNonEmptyString(message.pairId, 64)) return { ok: false, code: ERROR.BAD_MESSAGE };
  if (!isNonEmptyString(message.deviceId, 64)) return { ok: false, code: ERROR.BAD_MESSAGE };
  if (message.journalTokenHash !== undefined && !isNonEmptyString(message.journalTokenHash, 128)) {
    return { ok: false, code: ERROR.BAD_MESSAGE };
  }
  return { ok: true };
}

/**
 * Проверка права отправить сообщение: роль и состояние сессии.
 * Возвращает код ошибки или null, если сообщение допустимо.
 */
export function checkRoute(type, role, sessionState) {
  const route = ROUTES[type];
  if (!route) return ERROR.BAD_MESSAGE;
  if (route.from !== 'any' && route.from !== role) return ERROR.WRONG_ROLE;
  if (route.state === null) return null;
  if (!sessionState) return ERROR.UNKNOWN_SESSION;
  if (!route.state.includes(sessionState)) return ERROR.WRONG_STATE;
  return null;
}

/** Поля, которые сервер ретранслирует. Всё остальное отбрасывается. */
const RELAY_FIELDS = {
  revoke: ['nonce', 'mac'],
  'revoke-ack': ['ok'],
  'help-request': ['note'],
  'consent-granted': [],
  'consent-denied': ['reason'],
  offer: ['sdp', 'nonceH'],
  answer: ['sdp', 'nonceG', 'macG'],
  'auth-confirm': ['macH'],
  ice: ['candidate'],
  'session-end': ['reason'],
};

export function relayPayload(type, message, sessionId) {
  const payload = { t: type, sessionId };
  for (const field of RELAY_FIELDS[type] ?? []) {
    if (message[field] !== undefined) payload[field] = message[field];
  }
  return payload;
}
