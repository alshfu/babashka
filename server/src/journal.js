import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

import { sha256Base64url, safeEqual } from './ids.js';
import { log } from './log.js';

/**
 * Журнал сессий: ТОЛЬКО метаданные (docs/protocol.md §7).
 * Ни SDP, ни кадров, ни текста `note` — ничего, что было бы содержимым помощи.
 *
 * Записываются и несостоявшиеся сессии: отказ, молчание, провал проверки ключа.
 * Прозрачность выборочной не бывает — семья должна видеть в том числе и то,
 * как часто помощник дёргает бабушку.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

export class Journal {
  #path;
  #pairsPath;
  #retentionMs;
  #entries = [];
  #pairs = new Map(); // pairId → sha256(journalToken)
  #writeChain = Promise.resolve();
  #persist;

  constructor({ path, retentionDays, persist = true }) {
    this.#path = path;
    this.#pairsPath = path.replace(/\.jsonl$/, '') + '.pairs.json';
    this.#retentionMs = retentionDays * DAY_MS;
    this.#persist = persist && path !== ':memory:';
  }

  async load() {
    if (!this.#persist) return;
    await mkdir(dirname(this.#path), { recursive: true }).catch(() => {});
    try {
      const raw = await readFile(this.#path, 'utf8');
      for (const line of raw.split('\n')) {
        if (!line.trim()) continue;
        try {
          this.#entries.push(JSON.parse(line));
        } catch {
          log.warn('journal: malformed line skipped');
        }
      }
    } catch {
      /* журнала ещё нет — нормально при первом запуске */
    }
    try {
      const raw = await readFile(this.#pairsPath, 'utf8');
      for (const [pairId, hash] of Object.entries(JSON.parse(raw))) this.#pairs.set(pairId, hash);
    } catch {
      /* реестра пар ещё нет */
    }
    this.prune();
  }

  /**
   * Пара регистрирует хеш своего journalToken. Сам токен выводится из секрета пары,
   * поэтому сервер вычислить его не может и журнал «изнутри» не прочитает.
   * Повторная регистрация другим хешем игнорируется: иначе кто угодно, знающий pairId,
   * перехватил бы доступ к журналу.
   */
  registerPair(pairId, tokenHash) {
    if (!tokenHash) return;
    if (this.#pairs.has(pairId)) return;
    this.#pairs.set(pairId, tokenHash);
    this.#queueWrite(() => this.#savePairs());
  }

  hasPair(pairId) {
    return this.#pairs.has(pairId);
  }

  /** Проверка доступа: клиент присылает токен, сервер сравнивает его хеш с сохранённым. */
  verifyToken(pairId, token) {
    const known = this.#pairs.get(pairId);
    if (!known || typeof token !== 'string' || token.length === 0) return false;
    return safeEqual(known, sha256Base64url(token));
  }

  append(entry) {
    this.#entries.push(entry);
    log.info('journal', { pairId: entry.pairId, sessionId: entry.sessionId, reason: entry.reason });
    if (this.#persist) this.#queueWrite(() => appendFile(this.#path, `${JSON.stringify(entry)}\n`));
  }

  list(pairId) {
    return this.#entries.filter((entry) => entry.pairId === pairId);
  }

  /** Удаление журнала пары по её запросу (docs/server.md §7). */
  async deletePair(pairId) {
    this.#entries = this.#entries.filter((entry) => entry.pairId !== pairId);
    this.#pairs.delete(pairId);
    await this.#queueWrite(() => this.#rewrite());
    await this.#queueWrite(() => this.#savePairs());
  }

  /** Ротация: старше retentionDays — стираем. */
  prune(now = Date.now()) {
    const before = this.#entries.length;
    this.#entries = this.#entries.filter((entry) => now - (entry.requestedAt ?? 0) <= this.#retentionMs);
    const removed = before - this.#entries.length;
    if (removed > 0) {
      log.info('journal: rotation', { count: removed });
      if (this.#persist) this.#queueWrite(() => this.#rewrite());
    }
    return removed;
  }

  get size() {
    return this.#entries.length;
  }

  #queueWrite(task) {
    this.#writeChain = this.#writeChain
      .then(task)
      .catch((error) => log.error(`journal: write failed: ${error.message}`));
    return this.#writeChain;
  }

  async #rewrite() {
    if (!this.#persist) return;
    const body = this.#entries.map((entry) => `${JSON.stringify(entry)}\n`).join('');
    await writeFile(this.#path, body);
  }

  async #savePairs() {
    if (!this.#persist) return;
    await writeFile(this.#pairsPath, JSON.stringify(Object.fromEntries(this.#pairs)));
  }
}
