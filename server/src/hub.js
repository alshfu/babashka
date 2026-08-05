import {
  ERROR,
  END_REASON,
  SESSION_STATE,
  checkRoute,
  parseMessage,
  peerRole,
  relayPayload,
  validateHello,
} from './protocol.js';
import { newId, turnCredentials } from './ids.js';
import { log } from './log.js';

const HOUR_MS = 60 * 60 * 1000;

/**
 * Ядро сигналинга: комнаты по pairId, присутствие, маршрутизация, сессии.
 *
 * Чего здесь принципиально нет: секретов пары, разбора SDP, доступа к datachannel.
 * Сервер не может ни подслушать помощь, ни выдать согласие за бабушку — он только
 * доставляет сообщения тем, кому они адресованы (docs/architecture.md §4).
 */
export class Hub {
  #config;
  #journal;
  #push;
  #now;
  #pairs = new Map();
  #stats = { sessionsStarted: 0, sessionsConnected: 0, authFailed: 0, declined: 0, noAnswer: 0 };

  constructor({ config, journal, push, now = () => Date.now() }) {
    this.#config = config;
    this.#journal = journal;
    this.#push = push;
    this.#now = now;
  }

  // ── Подключение ───────────────────────────────────────────────────────────

  /** Первое сообщение сокета обязано быть `hello`; всё остальное до него — разрыв. */
  handleHello(conn, message) {
    const check = validateHello(message);
    if (!check.ok) {
      this.#fail(conn, check.code);
      return;
    }

    const pair = this.#pair(message.pairId);
    conn.pairId = message.pairId;
    conn.role = message.role;
    conn.deviceId = message.deviceId;
    conn.joined = true;

    const previous = pair.sockets.get(conn.role);
    if (previous && previous !== conn) {
      // Смена сети у бабушки не должна оставлять «призрака», который держит присутствие.
      previous.send({ t: 'error', code: ERROR.REPLACED, message: 'connection replaced by a newer one' });
      previous.close();
      pair.sockets.delete(conn.role);
      // Тот же участник переподключился, пока шла сессия (внук закрыл вкладку и открыл
      // заново — браузер держит старый сокет ещё несколько секунд). WebRTC старого сокета
      // мёртв, поэтому сессию надо закрыть и уведомить вторую сторону: иначе телефон
      // остаётся в старой сессии (висит рамка), а новый сокет упирается в wrong-state.
      if (pair.session) {
        const other = pair.sockets.get(peerRole(conn.role));
        if (other) other.send({ t: 'session-end', sessionId: pair.session.id, reason: END_REASON.PEER_LOST });
        this.#endSession(pair, END_REASON.PEER_LOST);
      }
    }
    pair.sockets.set(conn.role, conn);

    if (message.journalTokenHash) this.#journal.registerPair(pair.pairId, message.journalTokenHash);

    const peer = pair.sockets.get(peerRole(conn.role));
    conn.send({
      t: 'hello-ok',
      serverTime: this.#now(),
      peerOnline: Boolean(peer),
      iceServers: this.iceServers(pair.pairId),
    });
    if (peer) peer.send({ t: 'peer-state', online: true });

    log.info('hello', { pairId: pair.pairId, role: conn.role, deviceId: conn.deviceId });

    // Отложенный отзыв доступа доставляем сразу, как телефон бабушки вышел на связь.
    if (conn.role === 'grandma' && pair.pendingRevoke) {
      conn.send({ t: 'revoke', ...pair.pendingRevoke });
    }

    // Телефон бабушки мог спать и проснуться от push — запрос ждёт её.
    const session = pair.session;
    if (conn.role === 'grandma' && session?.state === SESSION_STATE.AWAITING_CONSENT) {
      conn.send({
        t: 'help-request',
        sessionId: session.id,
        from: 'helper',
        at: session.requestedAt,
        ...(session.note ? { note: session.note } : {}),
      });
    }
  }

  handleRaw(conn, raw) {
    const parsed = parseMessage(raw, this.#config.maxMessageBytes);
    if (!parsed.ok) {
      // Битый кадр — это ошибка, но не повод рвать связь бабушке посреди сессии.
      conn.send({ t: 'error', code: parsed.code, message: 'message could not be parsed' });
      return;
    }
    const message = parsed.message;

    if (message.t === 'hello') {
      if (conn.joined) {
        conn.send({ t: 'error', code: ERROR.BAD_MESSAGE, message: 'duplicate hello' });
        return;
      }
      this.handleHello(conn, message);
      return;
    }

    if (!conn.joined) {
      this.#fail(conn, ERROR.NOT_AUTHENTICATED);
      return;
    }
    if (!this.#allowRate(conn)) {
      conn.send({ t: 'error', code: ERROR.RATE_LIMITED, message: 'too many messages' });
      return;
    }
    if (message.t === 'ping') {
      conn.send({ t: 'pong' });
      return;
    }
    if (message.t === 'register-push') {
      // Токен нужен только телефону бабушки: будим именно её, помощник инициатор и так активен.
      if (conn.role === 'grandma') this.#push?.register(conn.pairId, message.token);
      return;
    }
    if (message.t === 'deeplink-status') {
      // Статус подписания BankID с телефона бабушки — уходит в /link-канал
      // (Flutter-приложение шлюза), а не в WebRTC-сессию: сессии здесь может не быть.
      if (conn.role === 'grandma') {
        this.onDeeplinkStatus?.(conn.pairId, {
          ok: message.ok === true,
          stage: typeof message.stage === 'string' ? message.stage.slice(0, 32) : '',
          err: typeof message.err === 'string' ? message.err.slice(0, 128) : '',
        });
      }
      return;
    }

    this.#route(conn, message);
  }

  handleClose(conn) {
    if (!conn.joined) return;
    const pair = this.#pairs.get(conn.pairId);
    if (!pair) return;
    if (pair.sockets.get(conn.role) !== conn) return; // уже вытеснен новым сокетом

    pair.sockets.delete(conn.role);
    const peer = pair.sockets.get(peerRole(conn.role));
    if (peer) peer.send({ t: 'peer-state', online: false });

    if (pair.session) {
      // The remaining side MUST be told the session is over, otherwise the phone stays
      // in CONNECTED, keeps capturing, and ignores every later help-request (state != IDLE).
      if (peer) peer.send({ t: 'session-end', sessionId: pair.session.id, reason: END_REASON.PEER_LOST });
      this.#endSession(pair, END_REASON.PEER_LOST);
    }
    // Не выбрасываем пару, пока висит неотданный отзыв: его надо доставить бабушке.
    if (pair.sockets.size === 0 && !pair.session && !pair.pendingRevoke) {
      this.#pairs.delete(pair.pairId);
    }

    log.info('close', { pairId: conn.pairId, role: conn.role });
  }

  // ── Маршрутизация ─────────────────────────────────────────────────────────

  #route(conn, message) {
    const pair = this.#pairs.get(conn.pairId);
    if (!pair) return;
    const session = pair.session;

    const routeError = checkRoute(message.t, conn.role, session?.state ?? null);
    if (routeError) {
      // Сюда попадают попытки помощника «дать согласие за бабушку» и обратные подмены ролей.
      log.warn('route rejected', {
        pairId: pair.pairId, role: conn.role, type: message.t, code: routeError,
      });
      conn.send({ t: 'error', code: routeError, message: 'message not allowed in this state' });
      return;
    }

    if (message.t === 'help-request') {
      this.#startSession(pair, conn, message);
      return;
    }

    // Отзыв доступа: вне сессии. Если телефон бабушки офлайн — придерживаем подписанную
    // команду и доставляем на следующем hello. Сервер её подделать не может (нет секрета),
    // поэтому хранить её у себя безопасно: максимум — отложенный отказ в доступе.
    if (message.t === 'revoke') {
      const grandma = pair.sockets.get('grandma');
      if (grandma) {
        grandma.send({ t: 'revoke', nonce: message.nonce, mac: message.mac });
      } else {
        pair.pendingRevoke = { nonce: message.nonce, mac: message.mac };
        this.#push?.wakeGrandma(pair.pairId, null);
      }
      conn.send({ t: 'revoke-queued', online: Boolean(grandma) });
      log.info('revoke', { pairId: pair.pairId, role: conn.role });
      return;
    }
    if (message.t === 'revoke-ack') {
      pair.pendingRevoke = null;
      this.#relay(pair, conn, message, undefined);
      return;
    }

    if (session && message.sessionId !== session.id) {
      conn.send({ t: 'error', code: ERROR.UNKNOWN_SESSION, message: 'session is not active' });
      return;
    }

    switch (message.t) {
      case 'consent-granted':
        session.state = SESSION_STATE.CONSENTED;
        session.consentedAt = this.#now();
        pair.consecutiveNoAnswer = 0;
        this.#armIdleTimer(pair);
        break;
      case 'consent-denied':
        pair.consecutiveNoAnswer = 0;
        this.#stats.declined += 1;
        this.#relay(pair, conn, message, session.id);
        this.#endSession(pair, END_REASON.DECLINED);
        return;
      case 'auth-confirm':
        // Помощник подтвердил подлинность — дальше устройство бабушки решает, показывать ли экран.
        session.state = SESSION_STATE.CONNECTED;
        session.screenShown = true;
        this.#stats.sessionsConnected += 1;
        break;
      case 'session-end': {
        const reason = Object.values(END_REASON).includes(message.reason)
          ? message.reason
          : END_REASON.ERROR;
        if (reason === END_REASON.AUTH_FAILED) this.#stats.authFailed += 1;
        this.#relay(pair, conn, message, session.id);
        this.#endSession(pair, reason);
        return;
      }
      default:
        break;
    }

    this.#armIdleTimer(pair);
    this.#relay(pair, conn, message, session.id);
  }

  #relay(pair, conn, message, sessionId) {
    const peer = pair.sockets.get(peerRole(conn.role));
    if (!peer) {
      conn.send({ t: 'error', code: ERROR.PEER_OFFLINE, message: 'the other side is offline' });
      return;
    }
    let payload = relayPayload(message.t, message, sessionId);
    if (this.#config.evilFingerprint && typeof payload.sdp === 'string') {
      payload = { ...payload, sdp: corruptFingerprint(payload.sdp) };
    }
    peer.send(payload);
    log.debug('relay', { pairId: pair.pairId, role: conn.role, type: message.t, sessionId });
  }

  // ── Сессии ────────────────────────────────────────────────────────────────

  #startSession(pair, conn, message) {
    if (pair.session) {
      conn.send({ t: 'error', code: ERROR.WRONG_STATE, message: 'a session is already in progress' });
      return;
    }
    const limit = this.#checkRequestLimits(pair);
    if (limit) {
      // Бабушке при этом не показывается ничего: назойливость гасится на сервере.
      conn.send({ t: 'error', code: ERROR.TOO_MANY_REQUESTS, message: limit });
      log.warn('request limit', { pairId: pair.pairId, code: ERROR.TOO_MANY_REQUESTS });
      return;
    }

    const now = this.#now();
    const note = typeof message.note === 'string' ? message.note.slice(0, 60) : undefined;
    const session = {
      id: newId(),
      state: SESSION_STATE.AWAITING_CONSENT,
      requestedBy: conn.role,
      requestedAt: now,
      consentedAt: null,
      screenShown: false,
      note,
      timer: null,
    };
    pair.session = session;
    pair.requestTimes.push(now);
    this.#stats.sessionsStarted += 1;

    const grandma = pair.sockets.get('grandma');
    conn.send({ t: 'help-request-sent', sessionId: session.id, peerOnline: Boolean(grandma) });

    if (grandma) {
      grandma.send({
        t: 'help-request',
        sessionId: session.id,
        from: 'helper',
        at: now,
        ...(note ? { note } : {}),
      });
    } else {
      // Спящий телефон будим push-ом; запрос дождётся её hello (см. handleHello).
      this.#push?.wakeGrandma(pair.pairId, session.id);
    }

    session.timer = setTimeout(() => {
      pair.consecutiveNoAnswer += 1;
      this.#stats.noAnswer += 1;
      this.#broadcast(pair, { t: 'session-end', sessionId: session.id, reason: END_REASON.NO_ANSWER });
      this.#endSession(pair, END_REASON.NO_ANSWER);
    }, this.#config.consentTimeoutMs);
    session.timer.unref?.();

    log.info('session started', { pairId: pair.pairId, sessionId: session.id, role: conn.role });
  }

  /** Лимиты частоты запросов — защита бабушки от назойливости (docs/protocol.md §3). */
  #checkRequestLimits(pair) {
    const now = this.#now();
    pair.requestTimes = pair.requestTimes.filter((ts) => now - ts < HOUR_MS);
    if (pair.requestTimes.length >= this.#config.helpRequestsPerHour) {
      return 'hourly request limit exceeded';
    }
    if (pair.consecutiveNoAnswer >= this.#config.maxConsecutiveNoAnswer) {
      return 'the elder did not answer previous requests';
    }
    return null;
  }

  #armIdleTimer(pair) {
    const session = pair.session;
    if (!session) return;
    clearTimeout(session.timer);
    session.timer = setTimeout(() => {
      this.#broadcast(pair, { t: 'session-end', sessionId: session.id, reason: END_REASON.TIMEOUT });
      this.#endSession(pair, END_REASON.TIMEOUT);
    }, this.#config.sessionIdleTimeoutMs);
    session.timer.unref?.();
  }

  #endSession(pair, reason) {
    const session = pair.session;
    if (!session) return;
    clearTimeout(session.timer);
    pair.session = null;

    this.#journal.append({
      sessionId: session.id,
      pairId: pair.pairId,
      requestedBy: session.requestedBy,
      requestedAt: session.requestedAt,
      consentedAt: session.consentedAt,
      endedAt: this.#now(),
      screenShown: session.screenShown,
      reason,
    });

    if (pair.sockets.size === 0 && !pair.pendingRevoke) this.#pairs.delete(pair.pairId);
  }

  #broadcast(pair, payload) {
    for (const socket of pair.sockets.values()) socket.send(payload);
  }

  // ── Служебное ─────────────────────────────────────────────────────────────

  /**
   * Колбэк для /link-канала (шлюз BankID-диплинков): назначается из index.js.
   * Вызывается со статусом подписания от телефона бабушки.
   */
  onDeeplinkStatus = null;

  /**
   * Доставить сообщение телефону бабушки вне сессии (канал /link: диплинк BankID).
   * false — бабушка офлайн; вызывающий решает, что ответить приложению.
   */
  sendToGrandma(pairId, payload) {
    const grandma = this.#pairs.get(pairId)?.sockets.get('grandma');
    if (!grandma) return false;
    grandma.send(payload);
    log.info('to grandma', { pairId, type: payload.t });
    return true;
  }

  iceServers(pairId) {
    const servers = [{ urls: this.#config.stunUrls }];
    if (this.#config.turnHost && this.#config.turnSecret) {
      const { username, credential } = turnCredentials(pairId, {
        secret: this.#config.turnSecret,
        ttlSeconds: this.#config.turnTtlSeconds,
        now: this.#now(),
      });
      servers.push({
        urls: [`turn:${this.#config.turnHost}?transport=udp`, `turn:${this.#config.turnHost}?transport=tcp`],
        username,
        credential,
      });
    }
    return servers;
  }

  stats() {
    let online = 0;
    let sessions = 0;
    for (const pair of this.#pairs.values()) {
      if (pair.sockets.size > 0) online += 1;
      if (pair.session) sessions += 1;
    }
    return { pairsOnline: online, sessionsActive: sessions, ...this.#stats };
  }

  /** Только для тестов и /healthz — состояние без содержимого. */
  inspect(pairId) {
    const pair = this.#pairs.get(pairId);
    if (!pair) return null;
    return {
      roles: [...pair.sockets.keys()],
      session: pair.session ? { id: pair.session.id, state: pair.session.state } : null,
      requestsLastHour: pair.requestTimes.length,
      consecutiveNoAnswer: pair.consecutiveNoAnswer,
    };
  }

  shutdown() {
    for (const pair of this.#pairs.values()) {
      if (pair.session) clearTimeout(pair.session.timer);
      for (const socket of pair.sockets.values()) socket.close();
    }
    this.#pairs.clear();
  }

  #pair(pairId) {
    let pair = this.#pairs.get(pairId);
    if (!pair) {
      pair = {
        pairId, sockets: new Map(), session: null,
        requestTimes: [], consecutiveNoAnswer: 0, pendingRevoke: null,
      };
      this.#pairs.set(pairId, pair);
    }
    return pair;
  }

  #allowRate(conn) {
    const now = this.#now();
    if (now - conn.bucket.start > this.#config.rateWindowMs) {
      conn.bucket.start = now;
      conn.bucket.count = 0;
    }
    conn.bucket.count += 1;
    return conn.bucket.count <= this.#config.messagesPerWindow;
  }

  #fail(conn, code) {
    conn.send({ t: 'error', code, message: 'connection closed' });
    conn.close();
  }
}

/** Отладочный MITM: портит отпечаток DTLS, чтобы проверка MAC у клиентов провалилась. */
function corruptFingerprint(sdp) {
  return sdp.replace(/a=fingerprint:(\S+) ([0-9A-Fa-f:]+)/, (match, algo, hex) => {
    const flipped = hex.startsWith('AA') ? `BB${hex.slice(2)}` : `AA${hex.slice(2)}`;
    return `a=fingerprint:${algo} ${flipped}`;
  });
}
