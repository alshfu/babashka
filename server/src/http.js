import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { log } from './log.js';

const PANEL_ROOT = resolve(fileURLToPath(new URL('../../web/public', import.meta.url)));

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const json = (res, status, body) => {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
    'cache-control': 'no-store',
  });
  res.end(payload);
};

export function createRequestHandler({ config, hub, journal, startedAt, agentChannel = null }) {
  return async function handle(req, res) {
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname;

    try {
      if (path === '/healthz') {
        const stats = hub.stats();
        return json(res, 200, { ok: true, uptime: Math.floor((Date.now() - startedAt) / 1000), ...stats });
      }

      if (path === '/metrics') {
        return metrics(res, hub, journal);
      }

      if (path === '/api/ice') {
        const pairId = url.searchParams.get('pairId');
        if (!pairId) return json(res, 400, { error: 'pairId обязателен' });
        return json(res, 200, { iceServers: hub.iceServers(pairId) });
      }

      if (path === '/api/agent-command' && req.method === 'POST') {
        return await agentCommandEndpoint(req, res, config, agentChannel);
      }

      if (path === '/api/journal') {
        return journalEndpoint(req, res, url, journal);
      }

      if (config.servePanel && (path === '/' || path.startsWith('/panel'))) {
        // Демо-стенд: голый адрес (без query) — сразу в панель помощника,
        // чтобы ссылка работала даже обрезанной мессенджером/браузером.
        // Ссылки с параметрами (role, demo, pairing-пакет) идут как шли.
        if ((path === '/' || path === '/panel' || path === '/panel/') && !url.search) {
          res.writeHead(302, { location: '/panel/?role=helper&demo=1' });
          return res.end();
        }
        return await servePanel(res, path);
      }

      return json(res, 404, { error: 'не найдено' });
    } catch (error) {
      log.error(`http: ${error.message}`, { path });
      return json(res, 500, { error: 'внутренняя ошибка' });
    }
  };
}

/**
 * Команда агенту пары (BankID-вход и прочие действия LAN-стека на Mac).
 * Авторизация — тем же AGENT_TOKEN, что и у самого агента на /agent (заголовок
 * x-agent-token). Тело: {"pairId": "...", "cmd": {...}}. Ответ — JSON агента как есть.
 */
async function agentCommandEndpoint(req, res, config, agentChannel) {
  const token = req.headers['x-agent-token'];
  if (!config.agentToken || token !== config.agentToken) {
    return json(res, 403, { ok: false, error: 'нет доступа к агенту' });
  }
  if (!agentChannel) return json(res, 503, { ok: false, error: 'канал агента отключён' });

  const body = await readJsonBody(req);
  if (!body || typeof body !== 'object') return json(res, 400, { ok: false, error: 'плохой JSON' });
  const { pairId, cmd } = body;
  if (!pairId || !cmd || typeof cmd !== 'object') {
    return json(res, 400, { ok: false, error: 'нужны pairId и cmd' });
  }
  try {
    const result = await agentChannel.sendCommand(pairId, cmd);
    return json(res, 200, { ok: true, res: result });
  } catch (error) {
    const offline = error.message === 'agent offline';
    return json(res, offline ? 503 : 504, { ok: false, error: error.message });
  }
}

function readJsonBody(req, limit = 64 * 1024) {
  return new Promise((resolveBody) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) { req.destroy(); resolveBody(null); return; }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try { resolveBody(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { resolveBody(null); }
    });
    req.on('error', () => resolveBody(null));
  });
}

/**
 * Журнал пары. Доступ — по journalToken, который выводится из секрета пары:
 * сервер хранит только его хеш и сам такой токен предъявить не может (docs/protocol.md §7).
 */
function journalEndpoint(req, res, url, journal) {
  const pairId = url.searchParams.get('pairId');
  const token = req.headers['x-journal-token'];
  if (!pairId) return json(res, 400, { error: 'pairId обязателен' });
  if (!journal.verifyToken(pairId, typeof token === 'string' ? token : '')) {
    // Один и тот же ответ и на «пары нет», и на «токен неверный» — чтобы наличие пары не утекало.
    return json(res, 403, { error: 'нет доступа к журналу' });
  }
  if (req.method === 'DELETE') {
    return journal.deletePair(pairId).then(() => json(res, 200, { ok: true }));
  }
  return json(res, 200, { pairId, entries: journal.list(pairId) });
}

function metrics(res, hub, journal) {
  const stats = hub.stats();
  const lines = [
    '# HELP pult_pairs_online Пар с хотя бы одним подключённым устройством',
    '# TYPE pult_pairs_online gauge',
    `pult_pairs_online ${stats.pairsOnline}`,
    '# HELP pult_sessions_active Активных сессий',
    '# TYPE pult_sessions_active gauge',
    `pult_sessions_active ${stats.sessionsActive}`,
    '# HELP pult_sessions_started_total Запросов помощи',
    '# TYPE pult_sessions_started_total counter',
    `pult_sessions_started_total ${stats.sessionsStarted}`,
    '# HELP pult_sessions_connected_total Сессий, дошедших до показа экрана',
    '# TYPE pult_sessions_connected_total counter',
    `pult_sessions_connected_total ${stats.sessionsConnected}`,
    '# HELP pult_auth_failed_total Провалов проверки ключа пары (норма — ноль)',
    '# TYPE pult_auth_failed_total counter',
    `pult_auth_failed_total ${stats.authFailed}`,
    '# HELP pult_declined_total Запросов, на которые бабушка ответила отказом',
    '# TYPE pult_declined_total counter',
    `pult_declined_total ${stats.declined}`,
    '# HELP pult_no_answer_total Запросов без ответа',
    '# TYPE pult_no_answer_total counter',
    `pult_no_answer_total ${stats.noAnswer}`,
    '# HELP pult_journal_entries Записей в журнале',
    '# TYPE pult_journal_entries gauge',
    `pult_journal_entries ${journal.size}`,
    '',
  ].join('\n');
  res.writeHead(200, { 'content-type': 'text/plain; version=0.0.4' });
  res.end(lines);
}

async function servePanel(res, path) {
  const relative = path === '/' || path === '/panel' || path === '/panel/'
    ? 'index.html'
    : normalize(path.replace(/^\/panel\/?/, '')).replace(/^(\.\.[/\\])+/, '');

  const file = join(PANEL_ROOT, relative);
  if (!file.startsWith(PANEL_ROOT)) {
    return json(res, 403, { error: 'запрещено' });
  }
  try {
    const info = await stat(file);
    if (!info.isFile()) throw new Error('не файл');
    res.writeHead(200, {
      'content-type': MIME[extname(file)] ?? 'application/octet-stream',
      'content-length': info.size,
      'cache-control': 'no-cache',
    });
    createReadStream(file).pipe(res);
  } catch {
    json(res, 404, { error: 'панель не найдена' });
  }
}
