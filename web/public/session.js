import {
  fingerprintFromSdp,
  macEquals,
  macForRole,
  randomBase64url,
} from './protocol.js';

/**
 * Сессия помощи поверх WebRTC.
 *
 * Главный инвариант, который здесь реализован буквально:
 * захват экрана начинается ТОЛЬКО после того, как MAC помощника сошёлся
 * (docs/protocol.md §4, docs/security.md §6, п.1).
 *
 * Технически это достигается тем, что бабушка отвечает на offer трансивером
 * `sendonly` БЕЗ трека, а реальный поток подставляется через `replaceTrack`
 * уже после проверки — без повторного согласования.
 */

const CONTROL_CHANNEL = 'pult-control';

class BaseSession extends EventTarget {
  constructor({ signaling, pair, iceServers }) {
    super();
    this.signaling = signaling;
    this.pair = pair;
    this.iceServers = iceServers;
    this.pc = null;
    this.channel = null;
    this.sessionId = null;
    this.nonceH = null;
    this.nonceG = null;
    this.fpH = null;
    this.fpG = null;

    // Кандидаты второй стороны приходят раньше, чем у нас появляется соединение
    // и remote description. Выбрасывать их нельзя: на обычном домашнем интернете
    // кандидатов всего один-два, и потеря любого означает несостоявшуюся помощь.
    this.pendingIce = [];
    this.remoteReady = false;
  }

  log(text, kind = 'info') {
    this.dispatchEvent(new CustomEvent('log', { detail: { text, kind } }));
  }

  setState(state, detail = {}) {
    this.dispatchEvent(new CustomEvent('state', { detail: { state, ...detail } }));
  }

  createPeerConnection() {
    const pc = new RTCPeerConnection({ iceServers: this.iceServers });
    pc.addEventListener('icecandidate', (event) => {
      if (!this.sessionId) return;
      this.signaling.send({ t: 'ice', sessionId: this.sessionId, candidate: event.candidate });
    });
    pc.addEventListener('connectionstatechange', () => {
      this.log(`WebRTC: ${pc.connectionState}`);
      if (pc.connectionState === 'connected') this.logSelectedRoute(pc);
      if (pc.connectionState === 'failed') this.end('error');
    });
    this.pc = pc;
    return pc;
  }

  /**
   * Диагностика пути медиа: host/srflx = прямой P2P, relay = весь трафик идёт через
   * TURN (на VPS — это главный источник лагов видео, сразу видно в журнале панели).
   */
  async logSelectedRoute(pc) {
    try {
      const stats = await pc.getStats();
      for (const report of stats.values()) {
        if (report.type !== 'candidate-pair' || report.state !== 'succeeded' || !report.nominated) continue;
        const local = stats.get(report.localCandidateId);
        const remote = stats.get(report.remoteCandidateId);
        const kind = local?.candidateType === 'relay' || remote?.candidateType === 'relay' ? 'relay' : 'direct';
        this.log(
          `WebRTC route: ${kind} (local ${local?.candidateType ?? '?'}, remote ${remote?.candidateType ?? '?'})`,
          kind === 'relay' ? 'warn' : 'info',
        );
        return;
      }
    } catch { /* статистика необязательна */ }
  }

  async addIce(candidate) {
    if (!candidate) return; // конец сбора кандидатов
    if (!this.pc || !this.remoteReady) {
      this.pendingIce.push(candidate);
      return;
    }
    try {
      await this.pc.addIceCandidate(candidate);
    } catch (error) {
      this.log(`ICE candidate rejected: ${error.message}`, 'warn');
    }
  }

  /** Вызывается сразу после setRemoteDescription: отложенные кандидаты идут в дело. */
  async flushIce() {
    this.remoteReady = true;
    const queued = this.pendingIce.splice(0);
    for (const candidate of queued) await this.addIce(candidate);
  }

  attachChannel(channel) {
    this.channel = channel;
    channel.addEventListener('message', (event) => {
      let message;
      try {
        message = JSON.parse(event.data);
      } catch {
        return;
      }
      this.dispatchEvent(new CustomEvent('control', { detail: message }));
    });
  }

  sendControl(payload) {
    if (this.channel?.readyState === 'open') this.channel.send(JSON.stringify(payload));
  }

  /** Завершение: сначала гасим всё локально, потом сообщаем. Порядок важен. */
  end(reason) {
    if (!this.sessionId) return;
    const sessionId = this.sessionId;
    this.sessionId = null;
    this.sendControl({ t: 'stop' });
    this.teardown();
    this.signaling.send({ t: 'session-end', sessionId, reason });
    this.setState('idle', { reason });
    this.log(`session ended: ${reason}`, reason === 'auth-failed' ? 'error' : 'info');
  }

  teardown() {
    this.channel?.close();
    this.channel = null;
    this.pc?.getSenders().forEach((sender) => sender.track?.stop());
    this.pc?.close();
    this.pc = null;
    this.nonceH = this.nonceG = this.fpH = this.fpG = null;
    this.pendingIce = [];
    this.remoteReady = false;
  }
}

// ── Сторона помощника ───────────────────────────────────────────────────────

export class HelperSession extends BaseSession {
  requestHelp(note) {
    this.signaling.send({ t: 'help-request', ...(note ? { note } : {}) });
    this.setState('requested');
    this.log('request sent, waiting for the elder to Allow');
  }

  async handle(message) {
    switch (message.t) {
      case 'help-request-sent':
        this.sessionId = message.sessionId;
        this.setState('requested', { peerOnline: message.peerOnline });
        if (!message.peerOnline) this.log('the elder’s phone is asleep — waking it with a push', 'warn');
        break;

      case 'consent-granted':
        this.log('the elder tapped Allow');
        await this.#sendOffer();
        break;

      case 'consent-denied':
        this.setState('idle', { reason: 'declined' });
        this.log('the elder cannot right now', 'warn');
        this.sessionId = null;
        break;

      case 'answer':
        await this.#onAnswer(message);
        break;

      case 'ice':
        await this.addIce(message.candidate);
        break;

      case 'session-end':
        this.sessionId = null;
        this.teardown();
        this.setState('idle', { reason: message.reason });
        break;

      default:
        break;
    }
  }

  async #sendOffer() {
    const pc = this.createPeerConnection();
    this.attachChannel(pc.createDataChannel(CONTROL_CHANNEL, { ordered: true }));
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addEventListener('track', (event) => {
      // Трек приходит без msid: бабушка подставляет его через replaceTrack, не добавляя
      // в MediaStream. Значит `event.streams` пуст, и поток нужно собрать самим —
      // иначе видео есть, а показывать его не в чем.
      const stream = event.streams[0] ?? new MediaStream([event.track]);
      this.dispatchEvent(new CustomEvent('stream', { detail: stream }));
    });

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    this.fpH = fingerprintFromSdp(pc.localDescription.sdp);
    this.nonceH = randomBase64url();
    this.signaling.send({
      t: 'offer',
      sessionId: this.sessionId,
      sdp: pc.localDescription.sdp,
      nonceH: this.nonceH,
    });
    this.setState('connecting');
  }

  async #onAnswer(message) {
    // Отпечаток берём из ПРИШЕДШЕГО SDP: если сигналинг его подменил, MAC не сойдётся.
    this.fpG = fingerprintFromSdp(message.sdp);
    this.nonceG = message.nonceG;

    const expected = await macForRole(this.pair.key, 'grandma', {
      sessionId: this.sessionId,
      nonceH: this.nonceH,
      nonceG: this.nonceG,
      fpH: this.fpH,
      fpG: this.fpG,
    });

    if (!macEquals(expected, message.macG)) {
      this.log('the elder’s signature did not match — aborting', 'error');
      this.end('auth-failed');
      return;
    }

    await this.pc.setRemoteDescription({ type: 'answer', sdp: message.sdp });
    await this.flushIce();

    const macH = await macForRole(this.pair.key, 'helper', {
      sessionId: this.sessionId,
      nonceH: this.nonceH,
      nonceG: this.nonceG,
      fpH: this.fpH,
      fpG: this.fpG,
    });
    this.signaling.send({ t: 'auth-confirm', sessionId: this.sessionId, macH });
    this.log('pair confirmed on both sides');
    this.setState('session');
  }

  pointer(x, y, kind = 'tap') {
    this.sendControl({ t: 'pointer', x, y, kind });
  }

  // Реальный тап по экрану бабушки (доли кадра 0..1). Служба доступности нажмёт за неё.
  tap(x, y) {
    this.sendControl({ t: 'tap', x, y });
  }

  // Свайп/прокрутка: от (x1,y1) к (x2,y2), доли кадра, длительность в мс.
  swipe(x1, y1, x2, y2, ms = 250) {
    this.sendControl({ t: 'swipe', x1, y1, x2, y2, ms });
  }

  // Системная навигация телефона: 'back' | 'home' | 'recents'.
  nav(action) {
    this.sendControl({ t: 'nav', action });
  }

  // Системные функции: громкость, яркость, шторка, быстрые настройки, блокировка.
  sys(action) {
    this.sendControl({ t: 'sys', action });
  }

  // Удалённое вкл/выкл службы управления на телефоне бабушки.
  // Выкл срабатывает сразу (телефон гасит службу сам); вкл открывает у неё настройки.
  controlService(on) {
    this.sendControl({ t: 'control', on });
  }

  // Открыть ссылку на телефоне бабушки: Google Photos, YouTube, WhatsApp-чат и т.п.
  openLink(url) {
    this.sendControl({ t: 'open', url });
  }

  // Переход по элементам: 'next' | 'prev' | 'activate'.
  focus(dir) {
    this.sendControl({ t: 'focus', dir });
  }

  // Обучение: показать бабушке подготовленный слайд-инструкцию. Картинку шлём чанками по
  // тому же зашифрованному каналу — сервер её не видит. base64 без префикса data:.
  async sendSlide(dataUrl, caption) {
    const b64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
    const id = 's' + Date.now();
    const CHUNK = 12000; // ~9 КБ бинарных на чанк — безопасно для data-канала
    for (let i = 0; i < b64.length; i += CHUNK) {
      // Ждём, пока буфер канала рассосётся, иначе крупная картинка его переполнит.
      while (this.channel && this.channel.bufferedAmount > 256 * 1024) {
        await new Promise((r) => setTimeout(r, 15));
      }
      this.sendControl({ t: 'slide', id, data: b64.slice(i, i + CHUNK) });
    }
    this.sendControl({ t: 'slide-done', id, caption: (caption || '').slice(0, 120) });
  }

  hideSlide() {
    this.sendControl({ t: 'slide-hide' });
  }

  say(text) {
    this.sendControl({ t: 'say', text: text.slice(0, 60) });
  }
}

// ── Сторона бабушки ─────────────────────────────────────────────────────────

export class GrandmaSession extends BaseSession {
  #videoTransceiver = null;
  #screenStream = null;
  #redacted = false;

  /** Захват экрана. В приложении здесь MediaProjection, в стенде — getDisplayMedia. */
  captureScreen = () => navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 15 }, audio: false });

  async handle(message) {
    switch (message.t) {
      case 'help-request':
        this.sessionId = message.sessionId;
        this.setState('asked', { note: message.note, at: message.at });
        this.log('the assistant is requesting to help');
        break;

      case 'offer':
        await this.#onOffer(message);
        break;

      case 'auth-confirm':
        await this.#onAuthConfirm(message);
        break;

      case 'ice':
        await this.addIce(message.candidate);
        break;

      case 'session-end':
        this.sessionId = null;
        this.stopCapture();
        this.teardown();
        this.setState('idle', { reason: message.reason });
        break;

      default:
        break;
    }
  }

  grant() {
    if (!this.sessionId) return;
    this.signaling.send({ t: 'consent-granted', sessionId: this.sessionId });
    this.setState('granted');
    this.log('Allow tapped — waiting for the connection');
  }

  deny() {
    if (!this.sessionId) return;
    this.signaling.send({ t: 'consent-denied', sessionId: this.sessionId, reason: 'declined' });
    this.sessionId = null;
    this.setState('idle', { reason: 'declined' });
  }

  async #onOffer(message) {
    const pc = this.createPeerConnection();
    pc.addEventListener('datachannel', (event) => this.attachChannel(event.channel));

    this.fpH = fingerprintFromSdp(message.sdp);
    await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp });
    await this.flushIce();

    // Трансивер отвечает `sendonly`, но трека в нём пока нет: экран не захватывается.
    this.#videoTransceiver = pc.getTransceivers().find((t) => t.receiver.track?.kind === 'video');
    if (this.#videoTransceiver) this.#videoTransceiver.direction = 'sendonly';

    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);

    this.fpG = fingerprintFromSdp(pc.localDescription.sdp);
    this.nonceH = message.nonceH;
    this.nonceG = randomBase64url();

    const macG = await macForRole(this.pair.key, 'grandma', {
      sessionId: this.sessionId,
      nonceH: this.nonceH,
      nonceG: this.nonceG,
      fpH: this.fpH,
      fpG: this.fpG,
    });

    this.signaling.send({
      t: 'answer',
      sessionId: this.sessionId,
      sdp: pc.localDescription.sdp,
      nonceG: this.nonceG,
      macG,
    });
    this.setState('connecting');
  }

  async #onAuthConfirm(message) {
    const expected = await macForRole(this.pair.key, 'helper', {
      sessionId: this.sessionId,
      nonceH: this.nonceH,
      nonceG: this.nonceG,
      fpH: this.fpH,
      fpG: this.fpG,
    });

    if (!macEquals(expected, message.macH)) {
      // Экран не захватывался и не будет: помощник не доказал, что он из пары.
      this.log('the assistant’s signature did not match — not sharing the screen', 'error');
      this.end('auth-failed');
      return;
    }

    this.log('assistant confirmed — starting screen sharing');
    await this.startCapture();
    this.setState('session');
  }

  async startCapture() {
    this.#screenStream = await this.captureScreen();
    const [track] = this.#screenStream.getVideoTracks();
    track.addEventListener('ended', () => this.end('stopped-by-grandma'));
    await this.#videoTransceiver?.sender.replaceTrack(track);
    const { width, height } = track.getSettings();
    this.sendControl({ t: 'screen-state', on: true, w: width, h: height });
    this.dispatchEvent(new CustomEvent('local-stream', { detail: this.#screenStream }));
  }

  stopCapture() {
    this.#screenStream?.getTracks().forEach((track) => track.stop());
    this.#screenStream = null;
    this.#redacted = false;
  }

  /**
   * Гашение экрана. Решение принимает сторона бабушки — помощник отключить его не может
   * (docs/security.md §1, правило 6).
   */
  async setRedacted(on, reason = 'banking-app') {
    if (!this.#videoTransceiver) return;
    this.#redacted = on;
    const [track] = this.#screenStream?.getVideoTracks() ?? [];
    await this.#videoTransceiver.sender.replaceTrack(on ? null : track ?? null);
    this.sendControl({ t: 'redact', on, reason });
    this.log(on ? `screen hidden (${reason})` : 'screen visible again');
  }

  get redacted() {
    return this.#redacted;
  }
}
