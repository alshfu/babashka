import { createReadStream } from 'node:fs';
import { mkdir, readFile, stat, unlink, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join, resolve } from 'node:path';

import { newId } from './ids.js';
import { log } from './log.js';

/**
 * Канал обновлений: хостинг apk/dex для телефона бабушки и push об уведомлений.
 *
 * Хранилище — каталог config.updatesDir: артефакты `<kind>-<newId>.<ext>` и по
 * манифесту на вид (`manifest-<kind>.json`). Свежая загрузка затирает прошлый
 * артефакт того же вида (best-effort: старый файл удаляется, если получилось).
 *
 * Авторизация двух уровней:
 *   · загрузка и notify — только x-agent-token (AGENT_TOKEN);
 *   · скачивание манифеста/артефакта — x-agent-token ИЛИ ?token=UPDATE_TOKEN
 *     (телефон бабушки тянет файл по ссылке из update-available, заголовки ему
 *     не положить — поэтому токен в query).
 */
const MAX_UPLOAD = 300 * 1024 * 1024;
const KINDS = new Set(['apk', 'dex']);
const SHA256_RE = /^[0-9a-f]{64}$/;

const json = (res, status, body) => {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
    'cache-control': 'no-store',
  });
  res.end(payload);
};

export function createUpdateChannel({ config, hub }) {
  const dir = resolve(config.updatesDir);

  const agentAuth = (req) =>
    Boolean(config.agentToken) && req.headers['x-agent-token'] === config.agentToken;

  const downloadAuth = (req, url) =>
    agentAuth(req) ||
    (Boolean(config.updateToken) && url.searchParams.get('token') === config.updateToken);

  const manifestPath = (kind) => join(dir, `manifest-${kind}.json`);

  async function readManifest(kind) {
    try {
      const manifest = JSON.parse(await readFile(manifestPath(kind), 'utf8'));
      return manifest && typeof manifest === 'object' ? manifest : null;
    } catch {
      return null;
    }
  }

  /** PUT /api/update: сырое тело — артефакт, метаданные в заголовках. */
  async function upload(req, res) {
    if (!agentAuth(req)) return json(res, 401, { ok: false, error: 'нет доступа' });

    const kind = req.headers['x-update-kind'];
    const version = req.headers['x-update-version'];
    const expected = req.headers['x-sha256'];
    if (!KINDS.has(kind)) return json(res, 400, { ok: false, error: 'x-update-kind: apk|dex' });
    if (typeof version !== 'string' || version.length === 0 || version.length > 40) {
      return json(res, 400, { ok: false, error: 'x-update-version: 1..40 символов' });
    }
    if (typeof expected !== 'string' || !SHA256_RE.test(expected)) {
      return json(res, 400, { ok: false, error: 'x-sha256: 64 hex-символа' });
    }

    const body = await readBody(req, MAX_UPLOAD);
    if (body === null) return json(res, 413, { ok: false, error: 'артефакт больше 300 МБ' });

    const digest = createHash('sha256').update(body).digest('hex');
    if (digest !== expected) return json(res, 400, { ok: false, error: 'sha256 не сошёлся' });

    await mkdir(dir, { recursive: true });
    const previous = await readManifest(kind);
    const file = `${kind}-${newId()}.${kind}`;
    await writeFile(join(dir, file), body);

    const manifest = {
      kind, version, sha256: digest, size: body.length, file, publishedAt: Date.now(),
    };
    await writeFile(manifestPath(kind), JSON.stringify(manifest, null, 2));

    // Прошлый артефакт того же вида больше не нужен (best-effort).
    if (previous?.file && previous.file !== file) {
      await unlink(join(dir, previous.file)).catch(() => {});
    }

    log.info('update: upload', { kind, size: manifest.size });
    return json(res, 200, {
      ok: true, kind, version, sha256: digest, size: manifest.size, url: `/update/${file}`,
    });
  }

  /** GET /api/update/manifest?kind=apk|dex — актуальный манифест вида. */
  async function manifestEndpoint(req, res, url) {
    if (!downloadAuth(req, url)) return json(res, 401, { ok: false, error: 'нет доступа' });
    const kind = url.searchParams.get('kind');
    if (!KINDS.has(kind)) return json(res, 400, { ok: false, error: 'kind: apk|dex' });
    const manifest = await readManifest(kind);
    if (!manifest) return json(res, 404, { ok: false, error: 'манифеста нет' });
    return json(res, 200, manifest);
  }

  /** GET /update/<file> — отдача артефакта. Только плоские имена из каталога. */
  async function download(req, res, url) {
    if (!downloadAuth(req, url)) return json(res, 401, { ok: false, error: 'нет доступа' });
    let name;
    try {
      name = decodeURIComponent(url.pathname.slice('/update/'.length));
    } catch {
      return json(res, 404, { ok: false, error: 'не найдено' });
    }
    if (!/^[\w.-]+$/.test(name) || name.includes('..')) {
      return json(res, 404, { ok: false, error: 'не найдено' });
    }
    const file = join(dir, name);
    if (!resolve(file).startsWith(dir)) return json(res, 404, { ok: false, error: 'не найдено' });
    try {
      const info = await stat(file);
      if (!info.isFile()) throw new Error('не файл');
      res.writeHead(200, {
        'content-type': 'application/octet-stream',
        'content-length': info.size,
        'cache-control': 'no-store',
      });
      createReadStream(file).pipe(res);
    } catch {
      json(res, 404, { ok: false, error: 'не найдено' });
    }
  }

  /** POST /api/update/notify: {kind, pairId?} — толкнуть update-available бабушке(ам). */
  async function notify(req, res) {
    if (!agentAuth(req)) return json(res, 401, { ok: false, error: 'нет доступа' });
    const body = await readJson(req);
    if (!body || !KINDS.has(body.kind)) return json(res, 400, { ok: false, error: 'kind: apk|dex' });
    if (body.pairId !== undefined && typeof body.pairId !== 'string') {
      return json(res, 400, { ok: false, error: 'pairId должен быть строкой' });
    }

    const manifest = await readManifest(body.kind);
    if (!manifest) return json(res, 404, { ok: false, error: 'манифеста нет' });

    const message = {
      t: 'update-available',
      kind: manifest.kind,
      version: manifest.version,
      url: `/update/${manifest.file}`,
      sha256: manifest.sha256,
      size: manifest.size,
    };

    let notified = 0;
    if (body.pairId) {
      if (hub.sendToGrandma(body.pairId, message)) notified = 1;
    } else {
      for (const pairId of hub.onlineGrandmas()) {
        if (hub.sendToGrandma(pairId, message)) notified += 1;
      }
    }
    log.info('update: notify', { kind: manifest.kind, count: notified });
    return json(res, 200, { ok: true, notified });
  }

  /** Маршрутизация канала обновлений. true — запрос наш и ответ уже дан. */
  async function handle(req, res, url) {
    const path = url.pathname;
    if (path === '/api/update' && req.method === 'PUT') {
      await upload(req, res);
      return true;
    }
    if (path === '/api/update/manifest' && req.method === 'GET') {
      await manifestEndpoint(req, res, url);
      return true;
    }
    if (path === '/api/update/notify' && req.method === 'POST') {
      await notify(req, res);
      return true;
    }
    if (path.startsWith('/update/') && req.method === 'GET') {
      await download(req, res, url);
      return true;
    }
    return false;
  }

  return { handle };
}

/** Тело целиком в память с пределом; сверх предела — null и сокет закрыт. */
function readBody(req, limit) {
  return new Promise((done) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        req.destroy();
        done(null);
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => done(Buffer.concat(chunks)));
    req.on('error', () => done(null));
  });
}

function readJson(req) {
  return readBody(req, 64 * 1024).then((body) => {
    if (body === null) return null;
    try {
      return JSON.parse(body.toString('utf8'));
    } catch {
      return null;
    }
  });
}
