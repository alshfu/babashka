import { config } from './config.js';

const LEVELS = { error: 0, warn: 1, info: 2, debug: 3 };

/**
 * Логи без содержимого: разрешены только тип сообщения, роль, идентификаторы.
 * SDP, ICE-кандидаты, `note` и что угодно, приехавшее от клиента, сюда не попадают
 * (docs/server.md §6).
 */
const SAFE_FIELDS = new Set([
  'pairId', 'role', 'type', 'sessionId', 'reason', 'code', 'deviceId',
  'count', 'ms', 'port', 'pairs', 'sessions', 'path', 'status',
]);

function sanitize(fields) {
  const out = {};
  for (const [key, value] of Object.entries(fields ?? {})) {
    if (!SAFE_FIELDS.has(key)) continue;
    out[key] = typeof value === 'string' ? value.slice(0, 64) : value;
  }
  return out;
}

function emit(level, message, fields) {
  if (LEVELS[level] > (LEVELS[config.logLevel] ?? LEVELS.info)) return;
  const line = { ts: new Date().toISOString(), level, msg: message, ...sanitize(fields) };
  process.stdout.write(`${JSON.stringify(line)}\n`);
}

export const log = {
  error: (message, fields) => emit('error', message, fields),
  warn: (message, fields) => emit('warn', message, fields),
  info: (message, fields) => emit('info', message, fields),
  debug: (message, fields) => emit('debug', message, fields),
};
