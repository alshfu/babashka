/**
 * Конфигурация сервера. Всё — из окружения, значения по умолчанию годятся для локальной разработки.
 * Описание переменных: docs/server.md §2.
 */

const num = (name, fallback) => {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value)) throw new Error(`${name}: expected a number, got "${raw}"`);
  return value;
};

const flag = (name, fallback) => {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  return raw === '1' || raw.toLowerCase() === 'true';
};

export const config = {
  port: num('PORT', 8080),
  host: process.env.HOST ?? '0.0.0.0',

  // TURN. Без TURN_HOST отдаём только STUN — этого хватает в локальной сети.
  turnHost: process.env.TURN_HOST ?? '',
  turnSecret: process.env.TURN_SECRET ?? '',
  turnTtlSeconds: num('TURN_TTL', 12 * 60 * 60),
  stunUrls: (process.env.STUN_URLS ?? 'stun:stun.l.google.com:19302')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean),

  // Журнал метаданных: ни SDP, ни контента (docs/protocol.md §7).
  journalPath: process.env.JOURNAL_PATH ?? './data/journal.jsonl',
  journalRetentionDays: num('JOURNAL_RETENTION_DAYS', 90),

  // Пробуждение спящего телефона бабушки. Пусто → push отключён, работает только онлайн-путь.
  fcmKey: process.env.FCM_KEY ?? '',

  servePanel: flag('SERVE_PANEL', true),

  // TLS-листенер (https + wss) для доступа с телефонов: браузеру нужен защищённый
  // контекст (crypto + захват экрана). Телефон-бабушка при этом остаётся на обычном ws.
  tlsPort: num('TLS_PORT', 8443),
  tlsCert: process.env.TLS_CERT ?? '',
  tlsKey: process.env.TLS_KEY ?? '',
  maxMessageBytes: num('MAX_MESSAGE_BYTES', 64 * 1024),

  // Лимиты из docs/protocol.md §3.
  helpRequestsPerHour: num('HELP_REQUESTS_PER_HOUR', 5),
  maxConsecutiveNoAnswer: num('MAX_CONSECUTIVE_NO_ANSWER', 3),
  consentTimeoutMs: num('CONSENT_TIMEOUT_MS', 90_000),
  sessionIdleTimeoutMs: num('SESSION_IDLE_TIMEOUT_MS', 10 * 60_000),
  messagesPerWindow: num('MESSAGES_PER_WINDOW', 60),
  rateWindowMs: num('RATE_WINDOW_MS', 10_000),
  // Полуоткрытые сокеты над мобильным Wi-Fi надо ловить быстро, иначе сервер считает
  // телефон «в сети», пока тот молчит, и запрос помощи уходит в никуда.
  heartbeatMs: num('HEARTBEAT_MS', 12_000),

  logLevel: process.env.LOG_LEVEL ?? 'info',

  /**
   * Отладочный режим «злой сервер»: подменяет отпечаток DTLS в ретранслируемом SDP.
   * Нужен, чтобы тест доказывал, что MITM на сигналинге ломает проверку MAC
   * (docs/testing.md §2, сценарий 4). В проде всегда выключен.
   */
  evilFingerprint: flag('EVIL_FINGERPRINT', false),
};
