import { PROTOCOL_VERSION } from './protocol.js';

/**
 * Клиент сигналинга: WebSocket с автопереподключением.
 *
 * Мобильная сеть рвётся постоянно — переподключение здесь не «улучшение»,
 * а базовое требование (ТЗ п.3). Экспоненциальная пауза с джиттером, чтобы
 * после падения сервера все устройства не вернулись одновременно.
 */
export class Signaling extends EventTarget {
  #url;
  #identity;
  #socket = null;
  #retry = 0;
  #closedByUs = false;
  #pingTimer = null;

  constructor(url, identity) {
    super();
    this.#url = url;
    this.#identity = identity;
  }

  get online() {
    return this.#socket?.readyState === WebSocket.OPEN;
  }

  connect() {
    this.#closedByUs = false;
    this.#open();
  }

  close() {
    this.#closedByUs = true;
    clearInterval(this.#pingTimer);
    this.#socket?.close();
    this.#socket = null;
  }

  send(payload) {
    if (!this.online) return false;
    this.#socket.send(JSON.stringify(payload));
    return true;
  }

  #open() {
    const socket = new WebSocket(this.#url);
    this.#socket = socket;

    socket.addEventListener('open', () => {
      this.#retry = 0;
      socket.send(JSON.stringify({
        t: 'hello',
        v: PROTOCOL_VERSION,
        pairId: this.#identity.pairId,
        role: this.#identity.role,
        deviceId: this.#identity.deviceId,
        journalTokenHash: this.#identity.journalTokenHash,
      }));
      clearInterval(this.#pingTimer);
      this.#pingTimer = setInterval(() => this.send({ t: 'ping' }), 20_000);
    });

    socket.addEventListener('message', (event) => {
      let message;
      try {
        message = JSON.parse(event.data);
      } catch {
        return;
      }
      // Нас вытеснила другая вкладка с той же ролью. Значит эта — дубликат:
      // не переподключаемся, иначе две вкладки будут бесконечно выбивать друг друга
      // («replaced» по кругу, присутствие прыгает).
      if (message.t === 'error' && message.code === 'replaced') {
        this.#closedByUs = true;
      }
      this.dispatchEvent(new CustomEvent('message', { detail: message }));
      this.dispatchEvent(new CustomEvent(message.t, { detail: message }));
    });

    socket.addEventListener('close', () => {
      clearInterval(this.#pingTimer);
      this.dispatchEvent(new CustomEvent('offline'));
      if (this.#closedByUs) return;
      const delay = Math.min(60_000, 2 ** this.#retry * 1000) * (0.5 + Math.random() / 2);
      this.#retry += 1;
      setTimeout(() => this.#open(), delay);
    });

    socket.addEventListener('error', () => socket.close());
  }
}
