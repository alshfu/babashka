import WebSocket from 'ws';

import { createApp } from '../src/index.js';

/** Поднимает сервер на случайном порту с журналом в памяти. */
export async function startTestServer(overrides = {}) {
  const app = await createApp({
    port: 0,
    host: '127.0.0.1',
    journalPath: ':memory:',
    logLevel: 'error',
    ...overrides,
  });
  const port = await app.listen();
  return { app, port, url: `ws://127.0.0.1:${port}/ws`, base: `http://127.0.0.1:${port}` };
}

/** Тестовый клиент: копит входящие и умеет ждать сообщение нужного типа. */
export class TestClient {
  #socket;
  #queue = [];
  #waiters = [];

  static connect(url) {
    const client = new TestClient();
    client.#socket = new WebSocket(url);
    client.#socket.on('message', (data) => client.#receive(JSON.parse(data.toString('utf8'))));
    return new Promise((done, fail) => {
      client.#socket.once('open', () => done(client));
      client.#socket.once('error', fail);
    });
  }

  #receive(message) {
    const index = this.#waiters.findIndex((waiter) => waiter.type === message.t);
    if (index >= 0) {
      const [waiter] = this.#waiters.splice(index, 1);
      clearTimeout(waiter.timer);
      waiter.done(message);
      return;
    }
    this.#queue.push(message);
  }

  send(payload) {
    this.#socket.send(JSON.stringify(payload));
  }

  sendRaw(payload) {
    this.#socket.send(payload);
  }

  async hello({ pairId, role, deviceId = `dev-${role}`, journalTokenHash, label, model } = {}) {
    this.send({ t: 'hello', v: 1, pairId, role, deviceId, journalTokenHash, label, model });
    return this.next('hello-ok');
  }

  next(type, timeoutMs = 2000) {
    const index = this.#queue.findIndex((message) => message.t === type);
    if (index >= 0) return Promise.resolve(this.#queue.splice(index, 1)[0]);
    return new Promise((done, fail) => {
      const timer = setTimeout(() => {
        const at = this.#waiters.findIndex((waiter) => waiter.timer === timer);
        if (at >= 0) this.#waiters.splice(at, 1);
        fail(new Error(`не дождались "${type}"; пришло: ${this.#queue.map((m) => m.t).join(', ') || '—'}`));
      }, timeoutMs);
      this.#waiters.push({ type, done, fail, timer });
    });
  }

  /** Проверка «ничего не пришло»: используется там, где сообщение НЕ должно доставляться. */
  async silentFor(ms) {
    await new Promise((done) => setTimeout(done, ms));
    return [...this.#queue];
  }

  get received() {
    return [...this.#queue];
  }

  close() {
    this.#socket.close();
    return new Promise((done) => this.#socket.once('close', done));
  }
}

export const pairId = (suffix) => `pair-${suffix}-${Math.random().toString(36).slice(2, 8)}`;

/** Минимальный SDP с отпечатком DTLS — сервер его не разбирает, но тесты MITM-режима на нём стоят. */
export const fakeSdp = (fingerprint = 'AA:BB:CC:DD') =>
  ['v=0', 'm=video 9 UDP/TLS/RTP/SAVPF 96', `a=fingerprint:sha-256 ${fingerprint}`].join('\r\n');
