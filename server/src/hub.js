import {
  ERROR,
  END_REASON,
  SESSION_STATE,
  checkRoute,
  parseMessage,
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
/**
 * Слот присутствия в паре — по одному на deviceId для ОБЕИХ ролей. Тестовый
 * режим (2026-09-12): A-app ставится на несколько телефонов сразу (авто-пара без
 * QR), все должны быть видны в списке устройств и управляться по deviceId —
 * вытеснять друг друга они не должны. Тот же deviceId = тот же слот (реконнект
 * приложения с прежним вытеснением, см. handleHello).
 * Там, где протоколу нужна «одна бабушка» (сессии WebRTC, fallback без deviceId),
 * берётся ПЕРВАЯ живая бабушка пары (primaryGrandma).
 */
const slotKey = (role, deviceId) => `${role}:${deviceId}`;

/** Все живые бабушки пары (A-app на нескольких телефонах — норма тестового режима). */
const grandmas = (pair) => [...pair.sockets.values()].filter((s) => s.role === 'grandma');

/** «Главная» бабушка для мест протокола, где нужна одна: первая по подключению. */
const primaryGrandma = (pair) => grandmas(pair)[0] ?? null;

export class Hub {
  #config;
  #journal;
  #push;
  #now;
  #pairs = new Map();
  // Реестр устройств живёт отдельно от пар: запись переживает разрыв соединения
  // (online=false), а сама пара может быть уже вычищена из #pairs.
  #devices = new Map();
  // socket по deviceId внутри пары (для маршрутизации deeplink к выбранной A-app).
  #deviceConns = new Map();
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
    conn.label = message.label;
    conn.model = message.model;
    conn.os = message.os;
    conn.ip = message.ip;
    conn.joined = true;
    this.#registerDevice(conn);
    this.#deviceConns.set(deviceConnKey(message.pairId, message.deviceId), conn);

    const slot = slotKey(conn.role, conn.deviceId);
    const previous = pair.sockets.get(slot);
    if (previous && previous !== conn) {
      // Смена сети у бабушки не должна оставлять «призрака», который держит присутствие.
      previous.send({ t: 'error', code: ERROR.REPLACED, message: 'connection replaced by a newer one' });
      previous.close();
      pair.sockets.delete(slot);
      // Тот же участник переподключился, пока шла сессия (внук закрыл вкладку и открыл
      // заново — браузер держит старый сокет ещё несколько секунд). WebRTC старого сокета
      // мёртв, поэтому сессию надо закрыть и уведомить вторую сторону: иначе телефон
      // остаётся в старой сессии (висит рамка), а новый сокет упирается в wrong-state.
      if (pair.session) {
        this.#broadcast(pair, { t: 'session-end', sessionId: pair.session.id, reason: END_REASON.PEER_LOST });
        this.#endSession(pair, END_REASON.PEER_LOST);
      }
    }
    pair.sockets.set(slot, conn);

    if (message.journalTokenHash) this.#journal.registerPair(pair.pairId, message.journalTokenHash);

    // Пиры: у помощника — бабушка; у бабушки — любой из помощников.
    const peers = [...pair.sockets.values()].filter((s) => s !== conn);
    const primaryPeer = conn.role === 'grandma'
      ? peers.find((s) => s.role === 'helper')
      : peers.find((s) => s.role === 'grandma');
    conn.send({
      t: 'hello-ok',
      serverTime: this.#now(),
      peerOnline: Boolean(primaryPeer),
      iceServers: this.iceServers(pair.pairId),
      ...(primaryPeer?.label !== undefined ? { peerLabel: primaryPeer.label } : {}),
      ...(primaryPeer?.model !== undefined ? { peerModel: primaryPeer.model } : {}),
    });
    for (const peer of peers) {
      peer.send({
        t: 'peer-state',
        online: true,
        ...(conn.label !== undefined ? { peerLabel: conn.label } : {}),
        ...(conn.model !== undefined ? { peerModel: conn.model } : {}),
      });
    }

    log.info('hello', { pairId: pair.pairId, role: conn.role, deviceId: conn.deviceId });

    // Отложенный отзыв доступа доставляем сразу, как телефон бабушки вышел на связь.
    if (conn.role === 'grandma' && pair.pendingRevoke) {
      conn.send({ t: 'revoke', ...pair.pendingRevoke });
    }

    // Отложенный диплинк (bankid://) ждёт бабушку — отдаём сразу после hello.
    if (conn.role === 'grandma' && pair.pendingDeeplink) {
      conn.send(pair.pendingDeeplink);
      log.info('to grandma (queued)', { pairId: pair.pairId, type: pair.pendingDeeplink.t });
      pair.pendingDeeplink = undefined;
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
    if (message.t === 'setup-status') {
      // Состояние шагов настройки с телефона бабушки — в /link-канал, мимо ROUTES.
      if (conn.role === 'grandma') {
        const steps = whitelistSetupSteps(message.steps);
        if (steps) this.onSetupStatus?.(conn.pairId, { t: 'setup-status', steps });
      }
      return;
    }
    if (message.t === 'update-status') {
      // Итог установки обновления (apk/dex) — тоже в /link-канал, мимо ROUTES.
      if (conn.role === 'grandma') {
        this.onUpdateStatus?.(conn.pairId, {
          kind: typeof message.kind === 'string' ? message.kind.slice(0, 8) : '',
          version: typeof message.version === 'string' ? message.version.slice(0, 40) : '',
          ok: message.ok === true,
          ...(typeof message.err === 'string' ? { err: message.err.slice(0, 128) } : {}),
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
    const slot = slotKey(conn.role, conn.deviceId);
    if (pair.sockets.get(slot) !== conn) return; // уже вытеснен новым сокетом

    const device = this.#devices.get(deviceKey(conn.pairId, conn.role, conn.deviceId));
    if (device) {
      device.online = false;
      device.lastSeen = this.#now();
    }
    this.#deviceConns.delete(deviceConnKey(conn.pairId, conn.deviceId));

    pair.sockets.delete(slot);
    // peer-state «офлайн» шлём только тем, для кого это правда: бабушке — когда ушёл
    // ПОСЛЕДНИЙ помощник; помощникам — когда ушла бабушка.
    const helpersLeft = [...pair.sockets.values()].some((s) => s.role === 'helper');
    for (const socket of pair.sockets.values()) {
      if (conn.role === 'grandma' && socket.role !== 'helper') continue;
      if (conn.role === 'helper' && (socket.role !== 'grandma' || helpersLeft)) continue;
      socket.send({ t: 'peer-state', online: false });
    }

    if (pair.session) {
      // The remaining side MUST be told the session is over, otherwise the phone stays
      // in CONNECTED, keeps capturing, and ignores every later help-request (state != IDLE).
      this.#broadcast(pair, { t: 'session-end', sessionId: pair.session.id, reason: END_REASON.PEER_LOST });
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
      // Бабушек может быть несколько (тестовый режим): отзыв пары получают ВСЕ.
      const targets = grandmas(pair);
      if (targets.length > 0) {
        for (const g of targets) g.send({ t: 'revoke', nonce: message.nonce, mac: message.mac });
      } else {
        pair.pendingRevoke = { nonce: message.nonce, mac: message.mac };
        this.#push?.wakeGrandma(pair.pairId, null);
      }
      conn.send({ t: 'revoke-queued', online: targets.length > 0 });
      log.info('revoke', { pairId: pair.pairId, role: conn.role });
      return;
    }
    if (message.t === 'revoke-ack') {
      pair.pendingRevoke = null;
      // Отзыв мог прислать любой из помощников — подтверждение получают все.
      this.#broadcast(pair, message);
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
    // Помощник говорит с первой живой бабушкой (в тестовом режиме их может быть
    // несколько — сессионный канал исторически один); ответы бабушки — тому помощнику,
    // который открыл текущую сессию (вне сессии — любому живому помощнику).
    let peer = null;
    if (conn.role === 'grandma') {
      if (sessionId && pair.session?.requestedByConn) peer = pair.session.requestedByConn;
      if (!peer) peer = [...pair.sockets.values()].find((s) => s.role === 'helper');
    } else {
      peer = primaryGrandma(pair);
    }
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
      requestedByConn: conn,
      requestedAt: now,
      consentedAt: null,
      screenShown: false,
      note,
      timer: null,
    };
    pair.session = session;
    pair.requestTimes.push(now);
    this.#stats.sessionsStarted += 1;

    const grandma = primaryGrandma(pair);
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
    session.requestedByConn = null; // не держим сокет помощника ссылкой после сессии
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
   * Колбэк для /link-канала: состояние шагов настройки с телефона бабушки
   * (ответ на setup-query или спонтанное обновление). Назначается из index.js.
   */
  onSetupStatus = null;

  /**
   * Колбэк для /link-канала: итог установки обновления с телефона бабушки.
   * Назначается из index.js.
   */
  onUpdateStatus = null;

  /**
   * Доставить сообщение телефону бабушки вне сессии (канал /link: диплинк BankID).
   * [deviceId] — выбранная A-app; если не указан — первая grandma пары (обратная совместимость).
   * false — бабушка офлайн; вызывающий решает, что ответить приложению.
   */
  sendToGrandma(pairId, payload, deviceId = null) {
    const pair = this.#pairs.get(pairId);
    let grandma = null;
    if (deviceId) {
      grandma = this.#deviceConns.get(deviceConnKey(pairId, deviceId)) ?? null;
    }
    if (!grandma) {
      // Без deviceId — первая живая бабушка пары (их может быть несколько).
      grandma = pair ? primaryGrandma(pair) : null;
    }
    if (!grandma) {
      // Бабушка офлайн (сон/мёртвый TCP): диплинк кладём в очередь — отдадим при hello.
      // Новый диплинк заменяет старый: ордера BankID короткоживущие, старьё не нужно.
      if (pair && payload?.t === 'deeplink') {
        pair.pendingDeeplink = payload;
        log.info('deeplink queued (grandma offline)', { pairId, deviceId });
      }
      return false;
    }
    pair.pendingDeeplink = undefined;
    grandma.send(payload);
    log.info('to grandma', { pairId, deviceId: grandma.deviceId, type: payload.t, url: payload.url });
    return true;
  }

  /** Реестр устройств пары для /api/devices — только роль grandma. */
  devices(pairIdFilter = null) {
    const out = [];
    for (const device of this.#devices.values()) {
      if (device.role !== 'grandma') continue;
      if (pairIdFilter && device.pairId !== pairIdFilter) continue;
      out.push({
        pairId: device.pairId,
        deviceId: device.deviceId,
        label: device.label,
        model: device.model,
        os: device.os,
        ip: device.ip,
        online: device.online,
        lastSeen: device.lastSeen,
      });
    }
    return out;
  }

  /** pairId всех бабушек, которые сейчас в сети (для рассылки update-available). */
  onlineGrandmas() {
    return [...this.#devices.values()]
      .filter((device) => device.role === 'grandma' && device.online)
      .map((device) => device.pairId);
  }

  #registerDevice(conn) {
    this.#devices.set(deviceKey(conn.pairId, conn.role, conn.deviceId), {
      pairId: conn.pairId,
      role: conn.role,
      deviceId: conn.deviceId,
      label: conn.label,
      model: conn.model,
      os: conn.os,
      ip: conn.ip,
      online: true,
      lastSeen: this.#now(),
    });
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
      // Слоты <role>:<deviceId> сворачиваем обратно в роли — внешний вид прежний.
      roles: [...pair.sockets.values()].map((socket) => socket.role),
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

const deviceKey = (pairId, role, deviceId) => `${pairId}:${role}:${deviceId}`;

const deviceConnKey = (pairId, deviceId) => `${pairId}:${deviceId}`;

// Ключи setup-status: шаги настройки + общий флаг paired. Неизвестные ключи
// отбрасываются, значения — только настоящие boolean (никаких «truthy» строк).
const SETUP_STATUS_KEYS = new Set(['battery', 'overlay', 'notifications', 'usage', 'autostart', 'paired']);

/** steps не-объект → null (кадр игнорируется); иначе — отфильтрованная копия. */
function whitelistSetupSteps(steps) {
  if (!steps || typeof steps !== 'object' || Array.isArray(steps)) return null;
  const clean = {};
  for (const key of Object.keys(steps)) {
    if (SETUP_STATUS_KEYS.has(key) && typeof steps[key] === 'boolean') clean[key] = steps[key];
  }
  return clean;
}

/** Отладочный MITM: портит отпечаток DTLS, чтобы проверка MAC у клиентов провалилась. */
function corruptFingerprint(sdp) {
  return sdp.replace(/a=fingerprint:(\S+) ([0-9A-Fa-f:]+)/, (match, algo, hex) => {
    const flipped = hex.startsWith('AA') ? `BB${hex.slice(2)}` : `AA${hex.slice(2)}`;
    return `a=fingerprint:${algo} ${flipped}`;
  });
}
