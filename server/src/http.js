import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { log } from './log.js';

const STATIC_ROOT = resolve(fileURLToPath(new URL('../static', import.meta.url)));
const APK_RE = /^[\w.-]+\.apk$/;
const APK_SHA_RE = /^[\w.-]+\.apk\.sha256$/;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.sha256': 'text/plain; charset=utf-8',
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

export function createRequestHandler({ config, hub, journal, startedAt, agentChannel = null, updateChannel = null, push = null }) {
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

      if (path === '/api/devices' && req.method === 'GET') {
        return devicesEndpoint(req, res, config, hub);
      }

      if (path === '/api/agent-command' && req.method === 'POST') {
        return await agentCommandEndpoint(req, res, config, agentChannel);
      }

      if (path === '/api/reanimate' && req.method === 'POST') {
        return await reanimateEndpoint(req, res, config, push);
      }

      if (path === '/api/journal') {
        return journalEndpoint(req, res, url, journal);
      }

      if (updateChannel && await updateChannel.handle(req, res, url)) {
        return undefined;
      }

      if (path === '/') {
        const body = 'nordic-gateway ok\n';
        res.writeHead(200, {
          'content-type': 'text/plain; charset=utf-8',
          'content-length': Buffer.byteLength(body),
        });
        return res.end(body);
      }

      if (path.startsWith('/panel')) {
        return await serveViewer(res, path);
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
 * Удалённая реанимация телефона (FCM data-push → телефон решает сам):
 *   restart — мягкий перезапуск сигналинга; reboot — перезагрузка устройства.
 * Доступ — по x-agent-token, как у /api/agent-command. Неизвестный/пустой
 * action сводится к «wake» (телефон просто проснётся) — консервативный дефолт.
 */
const REANIMATE_ACTIONS = new Set(['wake', 'restart', 'reboot']);

async function reanimateEndpoint(req, res, config, push) {
  const token = req.headers['x-agent-token'];
  if (!config.agentToken || token !== config.agentToken) {
    return json(res, 401, { ok: false, error: 'нет доступа' });
  }
  if (!push) return json(res, 503, { ok: false, error: 'push отключён' });

  const body = await readJsonBody(req);
  const pairId = body?.pairId;
  if (typeof pairId !== 'string' || pairId.length === 0) {
    return json(res, 400, { ok: false, error: 'нужен pairId' });
  }
  const requested = typeof body?.action === 'string' ? body.action : 'wake';
  const action = REANIMATE_ACTIONS.has(requested) ? requested : 'wake';
  const sent = await push.reanimate(pairId, action);
  log.info('reanimate', { pairId, action, sent });
  return json(res, 200, { ok: true, sent, action });
}

/**
 * Реестр телефонов бабушек (server/src/hub.js #devices): кто онлайн, как подписан,
 * когда видели в последний раз. Доступ — по x-agent-token, как у /api/agent-command.
 */
function devicesEndpoint(req, res, config, hub) {
  const token = req.headers['x-agent-token'];
  if (!config.agentToken || token !== config.agentToken) {
    return json(res, 401, { error: 'нет доступа к реестру устройств' });
  }
  const url = new URL(req.url, 'http://localhost');
  return json(res, 200, hub.devices(url.searchParams.get('pairId') || null));
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

/**
 * Статика /panel: низколатентный просмотрщик, который Flutter-приложение грузит
 * по адресу /panel/lowlat.html (app/lib/main.dart), и раздача APK
 * (/panel/a-app.apk, /panel/b-app.apk). Только плоские имена файлов.
 */
async function serveViewer(res, path) {
  const relative = normalize(path.replace(/^\/panel\/?/, '')).replace(/^(\.\.[/\\])+/, '');
  if (relative !== 'lowlat.html' && relative !== 'lowlat.js' &&
      !APK_RE.test(relative) && !APK_SHA_RE.test(relative)) {
    return json(res, 404, { error: 'не найдено' });
  }

  const file = join(STATIC_ROOT, relative);
  if (!file.startsWith(STATIC_ROOT)) {
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
    json(res, 404, { error: 'не найдено' });
  }
}
