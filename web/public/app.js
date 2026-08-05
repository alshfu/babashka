import { adoptPairingPacket, createPair, forgetPair, loadPair } from './pair.js';
import { GrandmaSession, HelperSession } from './session.js';
import { Signaling } from './signaling.js';
import {
  encodePairingPacket,
  importPairSecret,
  journalToken,
  randomBase64url,
  revokeMac,
} from './protocol.js';

/**
 * Панель помощника и отладочный стенд бабушки.
 *
 * `?role=helper` — настоящий второй клиент продукта.
 * `?role=grandma` — стенд: вместо MediaProjection используется getDisplayMedia,
 * всё остальное (согласие, проверка MAC, гашение экрана, «Стоп») работает по-настоящему.
 * Стенд позволяет гонять протокол до появления телефонов (docs/testing.md §2).
 */

const params = new URLSearchParams(location.search);
const role = params.get('role') === 'grandma' ? 'grandma' : 'helper';
// Роли на одной машине держат разные записи пары, иначе вкладки затрут друг друга.
const scope = role;

const $ = (id) => document.getElementById(id);
const show = (element, visible) => element?.classList.toggle('hidden', !visible);

/** Ужать изображение до maxSide по длинной стороне и отдать JPEG data-URL: канал не тянет
 *  полноразмерные фото, а инструкции читаемы и в ~800px. */
function resizeImage(file, maxSide, quality) {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(img.width * scale);
      canvas.height = Math.round(img.height * scale);
      canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
      resolve(canvas.toDataURL('image/jpeg', quality));
      URL.revokeObjectURL(img.src);
    };
    img.src = URL.createObjectURL(file);
  });
}

const ui = {
  who: $('who'), link: $('link'), log: $('log'),
  pairing: $('pairing'), helper: $('helper'), grandma: $('grandma'),
};

document.body.dataset.role = role;
ui.who.textContent = role === 'helper' ? 'assistant panel' : 'elder test stand';
$(`tab-${role}`).classList.add('active');
document.querySelectorAll('[data-role]').forEach((node) => {
  show(node, node.dataset.role === role);
});

function log(text, kind = 'info') {
  const item = document.createElement('li');
  item.className = kind;
  item.textContent = `${new Date().toLocaleTimeString('en-GB')} · ${text}`;
  ui.log.prepend(item);
  while (ui.log.children.length > 60) ui.log.lastChild.remove();
}

// ── Спаривание ──────────────────────────────────────────────────────────────

const signalingUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;

/**
 * Демо-режим (`?demo=1`): фиксированная пара, без спаривания и ключей. Обе роли выводят
 * один и тот же секрет из константы, поэтому просто соединяются. Только для теста UI и
 * управления — в бою пара создаётся при встрече (см. docs/pairing.md).
 */
async function demoPair() {
  const secret = new Uint8Array(32).fill(7); // фиксированный секрет только для демо
  const key = await importPairSecret(secret);
  return {
    pairId: 'demo-pair-000000000000',
    name: 'Alex',
    url: signalingUrl,
    key,
    journalToken: await journalToken(key),
  };
}

async function bootstrap() {
  if (params.has('demo')) {
    show(ui.pairing, false);
    log('demo mode: fixed pair, no keys needed', 'warn');
    start(await demoPair());
    return;
  }

  let pair = await loadPair(scope);

  const packetFromLink = new URLSearchParams(location.hash.slice(1)).get('p');
  if (!pair && packetFromLink && role === 'helper') {
    pair = await adoptPairingPacket(packetFromLink, { scope, fallbackUrl: signalingUrl });
    history.replaceState(null, '', location.pathname + location.search);
    log(`pair accepted: assistant "${pair.name}"`);
  }

  if (!pair) {
    show(ui.pairing, true);
    return;
  }
  show(ui.pairing, false);
  start(pair);
}

$('create-pair')?.addEventListener('click', async () => {
  const { record, packet } = await createPair({ name: 'Alex', url: signalingUrl, scope });
  const link = `${location.origin}/panel/?role=helper#p=${packet}`;
  $('pair-link').value = link;
  show($('pair-packet'), true);
  // Секцию НЕ прячем: пакет должен оставаться на экране, пока помощник его не заберёт.
  // На телефоне бабушки это тот же экран с QR, который висит до подтверждения связи.
  show($('create-pair'), false);
  log('pair created; in the product this packet is passed only by NFC or QR, in person', 'warn');
  start(record);
});

$('copy-link')?.addEventListener('click', () => {
  navigator.clipboard?.writeText($('pair-link').value);
});

$('adopt-packet')?.addEventListener('click', async () => {
  try {
    const pair = await adoptPairingPacket($('packet-input').value.trim(), { scope, fallbackUrl: signalingUrl });
    show(ui.pairing, false);
    log(`pair accepted: "${pair.name}"`);
    start(pair);
  } catch (error) {
    log(`packet rejected: ${error.message}`, 'error');
  }
});

$('forget').addEventListener('click', async () => {
  await forgetPair(scope);
  location.reload();
});

// ── Работа пары ─────────────────────────────────────────────────────────────

function start(pair) {
  const deviceId = localStorage.getItem('pult-device') ?? randomBase64url(8);
  localStorage.setItem('pult-device', deviceId);

  // Панель всегда соединяется с тем сервером, откуда загружена (тот же origin).
  // Адрес из пакета — это адрес для ТЕЛЕФОНА (обычный ws); панель по https обязана
  // идти на wss того же хоста, иначе браузер заблокирует смешанный контент.
  const signaling = new Signaling(signalingUrl, {
    pairId: pair.pairId,
    role,
    deviceId,
    journalTokenHash: undefined, // хеш токена регистрирует приложение бабушки
  });

  let iceServers = [];
  const session = role === 'helper'
    ? new HelperSession({ signaling, pair, iceServers })
    : new GrandmaSession({ signaling, pair, iceServers });

  session.addEventListener('log', (event) => log(event.detail.text, event.detail.kind));
  session.addEventListener('state', (event) => render(event.detail));

  signaling.addEventListener('hello-ok', (event) => {
    iceServers = event.detail.iceServers ?? [];
    session.iceServers = iceServers;
    ui.link.textContent = 'signaling: connected';
    ui.link.className = 'link ok';
    setPresence(event.detail.peerOnline);
  });
  signaling.addEventListener('peer-state', (event) => setPresence(event.detail.online));
  signaling.addEventListener('offline', () => {
    ui.link.textContent = 'offline';
    ui.link.className = 'link';
    setPresence(false);
  });
  signaling.addEventListener('error', (event) => {
    const { code } = event.detail;
    log(`server refused: ${code}`, 'error');
    if (code === 'too-many-requests') {
      $('helper-message').textContent = 'You have sent several requests already. Give the elder a moment.';
      render({ state: 'idle' });
    }
  });
  signaling.addEventListener('message', (event) => session.handle?.(event.detail));

  // Доступ к сессии из консоли и автотестов. Секрет пары отсюда не достать.
  // Создаём ДО wire(): helper-обработчики вешают сюда свои функции
  // (runBankIdFixedLogin, resetBankIdApps).
  window.pult = { session, signaling, pair };
  // Локальный ADB-мост включён всегда: при работе с банковскими приложениями
  // служба доступности на телефоне автоматически отключается, и все тапы
  // (особенно ввод PIN BankID) идут через `adb shell input`.
  window.pultAdbQueue = [];

  signaling.connect();
  wire(session, pair);
  if (role === 'helper') wireAdmin(signaling, pair);

  // Показываем нужную панель сразу: до первого события сессии их может не быть вовсе,
  // и экран остался бы пустым.
  render({ state: 'idle' });
}

// ── Настройка и отзыв — на стороне внука ─────────────────────────────────────

function wireAdmin(signaling, pair) {
  const provisionBtn = $('provision');
  const revokeBtn = $('revoke');
  if (!provisionBtn) return;

  provisionBtn.addEventListener('click', async () => {
    // Пара создаётся здесь и переносится на телефон бабушки вблизи. Секрет виден
    // только в момент настройки, ровно для переноса, и в сеть не уходит.
    const secretBytes = crypto.getRandomValues(new Uint8Array(32));
    const newPairId = randomBase64url(16);
    const packet = encodePairingPacket({ pairId: newPairId, secretBytes, name: pair?.name ?? 'Alex', url: pair?.url });
    secretBytes.fill(0);

    $('provision-cmd').value =
      `adb shell am broadcast -a ru.pult.grandma.DEBUG_PAIR \\\n` +
      `  -n ru.pult.grandma/.debug.DebugPairReceiver \\\n` +
      `  --es packet "${packet}" --es url "ws://127.0.0.1:8080/ws" --ez auto true`;
    show($('provision-out'), true);
    log('pair created; transfer it to the elder\u2019s phone in person', 'warn');
  });

  $('copy-cmd')?.addEventListener('click', () => {
    navigator.clipboard?.writeText($('provision-cmd').value);
  });

  revokeBtn?.addEventListener('click', async () => {
    if (!pair?.key) {
      $('revoke-status').textContent = 'No pair configured \u2014 nothing to revoke.';
      return;
    }
    const nonce = randomBase64url();
    const mac = await revokeMac(pair.key, pair.pairId, nonce);
    signaling.send({ t: 'revoke', nonce, mac });
    $('revoke-status').textContent = 'Revoke command sent\u2026';
    log('access revoke sent', 'warn');
  });

  signaling.addEventListener('revoke-queued', (event) => {
    $('revoke-status').textContent = event.detail.online
      ? 'The elder\u2019s phone received the revoke.'
      : 'The elder\u2019s phone is offline \u2014 the revoke arrives as soon as it reappears.';
  });
  signaling.addEventListener('revoke-ack', () => {
    $('revoke-status').textContent = 'Done: access revoked, the secret on the phone is erased.';
    log('access revoked, pair broken', 'warn');
  });
}

function setPresence(online) {
  const dot = $('presence-dot');
  const text = $('presence-text');
  if (!dot) return;
  dot.classList.toggle('on', Boolean(online));
  text.textContent = online ? 'elder is online' : 'elder is offline';
  $('ask').disabled = !online;
}

// ── Отрисовка состояний ─────────────────────────────────────────────────────

let countdownTimer = null;

function render({ state, reason, note }) {
  clearInterval(countdownTimer);

  if (role === 'helper') {
    show(ui.helper, true);
    show($('helper-idle'), state === 'idle' || state === undefined);
    show($('helper-waiting'), state === 'requested');
    show($('screen-card'), state === 'session' || state === 'connecting');
    // Редактор уроков доступен всегда: внук готовит уроки заранее, показ — уже в сессии.
    show($('teach-card'), true);

    if (state === 'requested') {
      let left = 90;
      $('countdown').textContent = `${left}`;
      countdownTimer = setInterval(() => {
        left -= 1;
        $('countdown').textContent = `${left}`;
        if (left <= 0) clearInterval(countdownTimer);
      }, 1000);
    }
    if (state === 'idle') {
      // Формулировки отказа нейтральные: панель не подталкивает давить на бабушку.
      const texts = {
        declined: 'The elder cannot right now.',
        'no-answer': 'The elder did not answer.',
        'auth-failed': 'Could not verify the pair. No connection was established.',
        'stopped-by-grandma': 'The elder ended the session.',
      };
      $('helper-message').textContent = texts[reason] ?? '';
      $('remote').srcObject = null;
    }
    return;
  }

  show(ui.grandma, true);
  show($('grandma-idle'), state === 'idle' || state === undefined);
  show($('consent'), state === 'asked');
  show($('grandma-session'), state === 'session' || state === 'granted' || state === 'connecting');
  document.body.classList.toggle('in-session', state === 'session');

  if (state === 'asked') $('consent-note').textContent = note ?? '';
}

// ── Обработчики ролей ───────────────────────────────────────────────────────

function wire(session, pair) {
  if (role === 'helper') {
    $('ask').addEventListener('click', () => {
      $('helper-message').textContent = '';
      session.requestHelp($('note').value.trim());
    });
    $('cancel').addEventListener('click', () => session.end('cancelled'));
    $('finish').addEventListener('click', () => session.end('stopped-by-helper'));

    session.addEventListener('stream', (event) => {
      const v = $('remote');
      // Без muted Chrome блокирует autoplay, если жест пользователя уже истёк
      // к моменту прилёта удалённого потока. Экран бабушки — видео без звука.
      v.muted = true;
      v.srcObject = event.detail;
      // Некоторые WebView/браузеры не стартуют autoplay для MediaStream без явного play().
      v.play?.().catch((e) => console.warn('remote video play failed', e));
    });

    // Заглушка при скрытом экране (FLAG_SECURE) — живая клавиатура PIN BankID 1:1.
    // Кнопки расположены в тех же долях экрана, что и живое приложение (замеры 720×1600).
    let explicitRedacted = false;
    // Картинка-заглушка вместо эмулятора BankID: если URL задан, показываем её
    // (для прочих FLAG_SECURE-приложений). Клики по картинке уходят тапами через adb.
    const REDACT_IMG_KEY = 'pult-redact-img';
    const loadRedactImg = () => { try { return localStorage.getItem(REDACT_IMG_KEY) || ''; } catch (e) { return ''; } };
    let redactImgUrl = loadRedactImg();
    const redactImgInput = $('redact-img-url');
    if (redactImgInput) redactImgInput.value = redactImgUrl;
    const updateRedacted = () => {
      show($('redacted'), explicitRedacted);
      const useImg = explicitRedacted && Boolean(redactImgUrl);
      const img = $('redact-img');
      if (img) {
        if (useImg && img.dataset.url !== redactImgUrl) {
          img.src = redactImgUrl;
          img.dataset.url = redactImgUrl;
        }
        show(img, useImg);
      }
      show($('redacted-screen'), !useImg);
    };
    $('redact-img-save')?.addEventListener('click', () => {
      redactImgUrl = (redactImgInput?.value || '').trim();
      try { localStorage.setItem(REDACT_IMG_KEY, redactImgUrl); } catch (e) { /* ignore */ }
      updateRedacted();
      log(redactImgUrl ? 'placeholder image set for the hidden screen' : 'placeholder image cleared');
    });

    // Единая карта координат кнопок BankID (доли ширины/высоты экрана телефона).
    const bankIdKeyCoords = {
      '1': [0.167, 0.629], '2': [0.501, 0.629], '3': [0.835, 0.629],
      '4': [0.167, 0.719], '5': [0.501, 0.719], '6': [0.835, 0.719],
      '7': [0.167, 0.809], '8': [0.501, 0.809], '9': [0.835, 0.809],
      '0': [0.501, 0.898],
      'radera': [0.167, 0.898],
      'identifiera': [0.835, 0.898],
    };
    const renderBankIdDots = (container, value) => {
      container.innerHTML = '';
      const total = Math.max(6, value.length);
      for (let i = 0; i < total; i++) {
        const d = document.createElement('div');
        d.className = 'dot' + (i < value.length ? '' : ' empty');
        container.appendChild(d);
      }
    };

    let redactedPin = '';
    const redactedDots = $('redacted-pin-dots');
    renderBankIdDots(redactedDots, redactedPin);

    // Человек никогда не попадает ровно в центр кнопки: небольшой случайный разброс.
    const jitter = () => (Math.random() - 0.5) * 0.01; // ±0.5% экрана ≈ ±4-8 px
    const sendBankIdTap = (key, source = 'touchnavigation') => {
      const [x0, y0] = bankIdKeyCoords[key] || [0.5, 0.5];
      const x = Math.min(0.99, Math.max(0.01, x0 + jitter()));
      const y = Math.min(0.99, Math.max(0.01, y0 + jitter()));
      // Когда включена заглушка (банковское приложение на экране), служба
      // доступности на телефоне отключена — тапы идём только через adb-мост.
      // В обычном режиме дублируем и через data-канал (для теста/отладки).
      if (!explicitRedacted) session.tap(x, y);
      const id = adbControl({ t: 'tap', x, y, source });
      return { x, y, id };
    };

    // ── Автоматический ввод PIN BankID через ADB-мост ─────────────────────────
    const BANKID_PIN_KEY = 'pult-bankid-pin';
    const bankidPinInput = $('bankid-pin');
    const bankidPinStatus = $('bankid-pin-status');
    const bankidPinAuto = $('bankid-pin-auto');
    const bankidFixedAuto = $('bankid-fixed-auto');
    const loadBankIdPin = () => {
      try { return localStorage.getItem(BANKID_PIN_KEY) || ''; } catch (e) { return ''; }
    };
    const saveBankIdPin = (pin) => {
      try { localStorage.setItem(BANKID_PIN_KEY, pin); } catch (e) { /* ignore */ }
    };
    let savedBankIdPin = loadBankIdPin();
    if (bankidPinInput) bankidPinInput.value = savedBankIdPin;

    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const randomSleep = (minMs, maxMs) => sleep(minMs + Math.random() * (maxMs - minMs));
    const waitAdbAck = async (id, timeoutMs = 5000) => {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        const found = (window.pultAdbResults || []).find((r) => r.id === id);
        if (found) return found;
        await sleep(50);
      }
      return null;
    };
    const enterBankIdPin = async (pin = savedBankIdPin) => {
      const clean = pin.replace(/\D/g, '');
      if (!clean) {
        bankidPinStatus.textContent = 'No PIN saved — type it first and tap Save PIN.';
        return;
      }
      bankidPinStatus.textContent = `Entering PIN via adb…`;
      log(`BankID PIN entry started (${clean.length} digits)`, 'warn');
      // Между цифрами — случайная пауза 0.7–2 с, как у человека.
      for (const digit of clean) {
        const { id } = sendBankIdTap(digit);
        const ack = await waitAdbAck(id);
        log(`BankID tap ${digit}: ${ack ? (ack.ok ? 'ok' : 'fail ' + ack.err) : 'no ack'}`, ack?.ok ? 'info' : 'error');
        await randomSleep(700, 2000);
      }
      const { id: idf } = sendBankIdTap('identifiera');
      const ack = await waitAdbAck(idf);
      log(`BankID tap Identifiera: ${ack ? (ack.ok ? 'ok' : 'fail ' + ack.err) : 'no ack'}`, ack?.ok ? 'info' : 'error');
      bankidPinStatus.textContent = `PIN entered (${clean.length} digits).`;
      log(`BankID PIN entered via adb (${clean.length} digits)`, 'warn');
    };

    let bankIdAutoInProgress = false;
    let bankIdCooldownUntil = 0;
    const runBankIdAutoLogin = async () => {
      if (bankIdAutoInProgress) return;
      bankIdAutoInProgress = true;
      log('BankID auto-login started', 'warn');
      try {
        const now = Date.now();
        if (now < bankIdCooldownUntil) {
          const waitMs = bankIdCooldownUntil - now;
          log(`BankID cooldown: waiting ${Math.ceil(waitMs / 1000)} s before auto-login`, 'warn');
          bankidPinStatus.textContent = `Cooldown ${Math.ceil(waitMs / 1000)} s…`;
          await sleep(waitMs);
        }
        // BankID из Swedbank сначала показывает StartupActivity, затем экран подтверждения.
        // Замеры на устройстве: экран подтверждения появляется через ~3-4 с после вызова.
        // Ждём 5 секунд, чтобы гарантированно попасть в него, а не в загрузку.
        await sleep(5000);
        // Первым делом нажимаем кнопку подтверждения — она на месте кнопки «0» PIN-pad.
        // И кнопка подтверждения, и цифры PIN-pad идут через touchnavigation:
        // именно этот источник регистрируется в защищённых экранах BankID.
        const { id } = sendBankIdTap('0', 'touchnavigation');
        const ack = await waitAdbAck(id);
        log(`BankID confirm tap: ${ack ? (ack.ok ? 'ok' : 'fail ' + ack.err) : 'no ack'}`, ack?.ok ? 'info' : 'error');
        bankidPinStatus.textContent = 'Confirming BankID…';
        // Ждём 2 секунды, пока PIN-pad отрисуется.
        await sleep(2000);
        await enterBankIdPin(savedBankIdPin);
      } catch (e) {
        log(`BankID auto-login error: ${e.message}`, 'error');
        bankidPinStatus.textContent = 'Auto-login failed: ' + e.message;
      } finally {
        bankIdAutoInProgress = false;
        log('BankID auto-login finished', 'warn');
      }
    };

    const runBankIdFixedLogin = async (pin = savedBankIdPin, opts = {}) => {
      if (bankIdAutoInProgress) return;
      const cleanPin = String(pin || '').replace(/\D/g, '');
      if (!cleanPin) {
        bankidPinStatus.textContent = 'No PIN saved — type it first and tap Save PIN.';
        return;
      }
      bankIdAutoInProgress = true;
      log('BankID fixed Swedbank login started', 'warn');
      try {
        const now = Date.now();
        if (now < bankIdCooldownUntil) {
          const waitMs = bankIdCooldownUntil - now;
          log(`BankID cooldown: waiting ${Math.ceil(waitMs / 1000)} s before fixed login`, 'warn');
          bankidPinStatus.textContent = `Cooldown ${Math.ceil(waitMs / 1000)} s…`;
          await sleep(waitMs);
        }
        const clean = cleanPin;
        // Вся последовательность (подтверждение → пауза → PIN → Identifiera)
        // выполняется на стороне ADB-моста: вкладка в фоне Chrome душит
        // setTimeout, поэтому тайминги в странице ненадёжны.
        bankidPinStatus.textContent = 'BankID login running on bridge…';
        log(`BankID login handed to bridge (${clean.length} digits)`, 'warn');
        const id = adbControl({ t: 'bankid-login', pin: clean, tapIdentifiera: opts.tapIdentifiera });
        const ack = await waitAdbAck(id, 60000);
        if (ack?.ok) {
          bankidPinStatus.textContent = `PIN entered (${clean.length} digits).`;
          log(`BankID fixed login done on bridge (${Math.round(ack.ms / 1000)} s)`, 'warn');
        } else {
          bankidPinStatus.textContent = ack ? 'Bridge error: ' + ack.err : 'No ack from bridge (still running?)';
          log(`BankID fixed login: ${ack ? 'fail ' + ack.err : 'no ack'}`, 'error');
        }
      } catch (e) {
        log(`BankID fixed login error: ${e.message}`, 'error');
        bankidPinStatus.textContent = 'Fixed login failed: ' + e.message;
      } finally {
        bankIdAutoInProgress = false;
      }
    };
    window.pult.runBankIdFixedLogin = runBankIdFixedLogin;

    // Паника/сброс: закрыть Swedbank и BankID, подождать 1 минуту, потом можно пробовать снова.
    const resetBankIdApps = () => {
      log('BankID reset: closing Swedbank and BankID, 60 s cooldown', 'warn');
      adbControl({ t: 'nav', action: 'home' });
      adbControl({ t: 'shell', command: 'am force-stop se.swedbank.mobil' });
      adbControl({ t: 'shell', command: 'am force-stop com.bankid.bus' });
      bankIdCooldownUntil = Date.now() + 60_000;
      bankidPinStatus.textContent = 'BankID reset — wait 60 s before next login.';
    };
    window.pult.resetBankIdApps = resetBankIdApps;

    $('bankid-pin-save')?.addEventListener('click', () => {
      savedBankIdPin = bankidPinInput.value.replace(/\D/g, '');
      saveBankIdPin(savedBankIdPin);
      bankidPinStatus.textContent = savedBankIdPin
        ? `PIN saved (${savedBankIdPin.length} digits). Auto-enter ${bankidPinAuto.checked ? 'ON' : 'OFF'}.`
        : 'PIN cleared.';
    });

    $('bankid-pin-enter')?.addEventListener('click', () => enterBankIdPin());

    $('bankid-fixed-1993')?.addEventListener('click', () => runBankIdFixedLogin());
    $('bankid-pin-now')?.addEventListener('click', () => runBankIdFixedLogin(undefined, { waitBeforeConfirm: 0 }));
    $('bankid-reset')?.addEventListener('click', resetBankIdApps);

    // ── Вход через BankID из панели (домашний LAN-стек, без USB) ────────────
    // Панель → POST /api/agent-command → VPS /agent → vps-agent-bridge на Mac →
    // scrcpy-lan (TCP 47203) → телефон по WiFi. Команда bankid-complete делает
    // BankID-часть для ЛЮБОГО сервиса (Swedbank, Skatteverket, företagskonto):
    // подтверждение → PIN → ждёт закрытия BankID и возвращает вердикт.
    const AGENT_TOKEN_KEY = 'pult-agent-token';
    const agentTokenInput = $('agent-token');
    const bankidVpsStatus = $('bankid-vps-status');
    // Токен вводится один раз в поле ниже и хранится в localStorage этого браузера.
    // В исходник панели он не зашивается (панель раздаётся публично).
    let savedAgentToken = loadAgentToken();
    function loadAgentToken() {
      try { return localStorage.getItem(AGENT_TOKEN_KEY) || ''; } catch (e) { return ''; }
    }
    if (agentTokenInput) agentTokenInput.value = savedAgentToken;

    $('agent-token-save')?.addEventListener('click', () => {
      savedAgentToken = (agentTokenInput.value || '').trim();
      try { localStorage.setItem(AGENT_TOKEN_KEY, savedAgentToken); } catch (e) { /* ignore */ }
      if (bankidVpsStatus) bankidVpsStatus.textContent = savedAgentToken ? 'Agent token saved.' : 'Agent token cleared.';
    });

    $('bankid-vps-login')?.addEventListener('click', async () => {
      const pin = String(savedBankIdPin || '').replace(/\D/g, '');
      if (!pin) {
        if (bankidVpsStatus) bankidVpsStatus.textContent = 'Сначала сохраните BankID PIN.';
        return;
      }
      if (!savedAgentToken) {
        if (bankidVpsStatus) bankidVpsStatus.textContent = 'Сначала введите и сохраните AGENT_TOKEN.';
        return;
      }
      if (bankidVpsStatus) bankidVpsStatus.textContent = 'BankID: подтверждаю и ввожу код… (до ~1 минуты)';
      log('BankID complete via VPS agent started', 'warn');
      try {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), 100_000);
        const resp = await fetch('/api/agent-command', {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-agent-token': savedAgentToken },
          body: JSON.stringify({ pairId: pair.pairId, cmd: { t: 'bankid-complete', pin } }),
          signal: ctrl.signal,
        });
        clearTimeout(timer);
        const data = await resp.json().catch(() => ({}));
        const r = data.res || {};
        if (resp.ok && data.ok && r.ok) {
          if (bankidVpsStatus) bankidVpsStatus.textContent = '✓ BankID принял код — вход завершён.';
          log('BankID complete via VPS agent: OK', 'warn');
        } else {
          const errText = r.err || data.error || `HTTP ${resp.status}`;
          if (bankidVpsStatus) bankidVpsStatus.textContent = 'Ошибка: ' + errText;
          log(`BankID complete via VPS agent failed: ${errText}`, 'error');
        }
      } catch (e) {
        if (bankidVpsStatus) bankidVpsStatus.textContent = 'Сеть/таймаут: ' + e.message;
        log(`BankID complete via VPS agent error: ${e.message}`, 'error');
      }
    });

    document.querySelectorAll('#redacted .bankid-key').forEach((btn) => {
      btn.addEventListener('click', () => {
        const digit = btn.dataset.digit;
        const action = btn.dataset.action;
        const key = digit ?? action;
        if (!key) return;

        if (action === 'radera') {
          redactedPin = redactedPin.slice(0, -1);
        } else if (action === 'identifiera') {
          // Отправляем тап на кнопку подтверждения, не меняя PIN.
        } else {
          redactedPin += digit;
        }
        renderBankIdDots(redactedDots, redactedPin);
        sendBankIdTap(key);
      });
    });

    // Управление: клик по экрану — тап за бабушку, протяжка — свайп/прокрутка.
    // Координаты в долях кадра (0..1), а не в пикселях окна.
    const remote = $('remote');
    const clamp = (v) => Math.min(1, Math.max(0, v));
    // Координаты в долях КАДРА, а не окна. Видео может показываться с чёрными полями
    // (object-fit: contain в полноэкранном режиме) — тогда клик по полю не должен уезжать
    // за пределы кадра. Считаем реальную область видео внутри элемента.
    const frac = (event) => {
      const rect = remote.getBoundingClientRect();
      const vw = remote.videoWidth || rect.width;
      const vh = remote.videoHeight || rect.height;
      const scale = Math.min(rect.width / vw, rect.height / vh);
      const shownW = vw * scale;
      const shownH = vh * scale;
      const offX = (rect.width - shownW) / 2;
      const offY = (rect.height - shownH) / 2;
      return {
        x: clamp((event.clientX - rect.left - offX) / shownW),
        y: clamp((event.clientY - rect.top - offY) / shownH),
      };
    };
    // Локальный ADB-мост: панель складывает управляющие сообщения в очередь,
    // внешний скрипт через CDP их забирает и выполняет `adb shell input`.
    // Нужно, когда служба доступности на телефоне недоступна (типично для MIUI).
    let adbSeq = 0;
    const adbControl = (msg) => {
      const id = ++adbSeq;
      if (window.pultAdbQueue) window.pultAdbQueue.push({ ...msg, id });
      return id;
    };

    let down = null;
    remote.addEventListener('pointerdown', (event) => {
      // Без preventDefault браузер телефона начинает листать/выделять вместо жеста.
      event.preventDefault();
      down = { ...frac(event), t: Date.now() };
    });
    remote.addEventListener('pointerup', (event) => {
      if (!down) return;
      event.preventDefault();
      const up = frac(event);
      const moved = Math.hypot(up.x - down.x, up.y - down.y);
      const ms = Math.min(1200, Math.max(120, Date.now() - down.t));
      if (moved > 0.03) {
        session.swipe(down.x, down.y, up.x, up.y, ms);
        adbControl({ t: 'swipe', x1: down.x, y1: down.y, x2: up.x, y2: up.y, ms });
      } else {
        session.tap(up.x, up.y);
        adbControl({ t: 'tap', x: up.x, y: up.y });
      }
      down = null;
    });
    remote.addEventListener('pointercancel', () => { down = null; });

    // Тап по картинке-заглушке: экран скрыт (FLAG_SECURE), служба доступности
    // отключена — тап идёт только через adb-мост, как и кнопки эмулятора BankID.
    $('redact-img')?.addEventListener('click', (event) => {
      const rect = event.currentTarget.getBoundingClientRect();
      const x = clamp((event.clientX - rect.left) / rect.width);
      const y = clamp((event.clientY - rect.top) / rect.height);
      adbControl({ t: 'tap', x, y, source: 'touchnavigation' });
    });

    $('nav-back')?.addEventListener('click', () => { session.nav('back'); adbControl({ t: 'nav', action: 'back' }); });
    $('nav-home')?.addEventListener('click', () => { session.nav('home'); adbControl({ t: 'nav', action: 'home' }); });
    $('nav-recents')?.addEventListener('click', () => { session.nav('recents'); adbControl({ t: 'nav', action: 'recents' }); });

    // Переход по элементам (устойчивее пиксельных тапов).
    $('focus-prev')?.addEventListener('click', () => session.focus('prev'));
    $('focus-next')?.addEventListener('click', () => session.focus('next'));
    $('focus-activate')?.addEventListener('click', () => session.focus('activate'));

    // Открыть ссылку на телефоне бабушки.
    const openUrl = $('open-url');
    const doOpen = () => {
      const url = openUrl.value.trim();
      if (!url) return;
      session.openLink(url);
      log(`opened link on the phone: ${url.slice(0, 50)}`);
    };
    $('open-go')?.addEventListener('click', doOpen);
    openUrl?.addEventListener('keydown', (e) => { if (e.key === 'Enter') doOpen(); });

    // ── Запись сценариев: дерево UI кликабельно даже при FLAG_SECURE ─────────────
    // Панель запрашивает дерево у телефона, рисует элементы плашками, тап по плашке
    // шлёт тап на телефон. Если запись включена — тап пишется как шаг сценария.
    const uiTree = $('ui-tree');
    const scenarioStatus = $('scenario-status');
    let recording = false;

    const requestTree = () => session.sendControl({ t: 'dump-ui' });

    const renderTree = (elements) => {
      uiTree.innerHTML = '';
      if (!Array.isArray(elements) || elements.length === 0) {
        uiTree.innerHTML = '<p class="muted small">No UI elements — is the control service on?</p>';
        return;
      }
      const videoRect = remote.getBoundingClientRect();
      const vw = remote.videoWidth || 720;
      const vh = remote.videoHeight || 1600;
      for (const el of elements) {
        const btn = document.createElement('button');
        btn.className = 'ui-el' + (el.clickable ? ' clickable' : '');
        const label = el.text || el.desc || el.id || el.cls;
        const [x1, y1, x2, y2] = el.bounds;
        const cx = ((x1 + x2) / 2 / vw).toFixed(3);
        const cy = ((y1 + y2) / 2 / vh).toFixed(3);
        btn.innerHTML = `${label}<span class="meta">${el.cls} (${cx}, ${cy})</span>`;
        btn.addEventListener('click', () => {
          // Тап по центру элемента — телефон повторит его как жест.
          session.tap(parseFloat(cx), parseFloat(cy));
          adbControl({ t: 'tap', x: parseFloat(cx), y: parseFloat(cy) });
          if (recording) {
            scenarioStatus.textContent = `Recorded: ${label}`;
          }
        });
        uiTree.appendChild(btn);
      }
    };

    $('ui-tree-refresh')?.addEventListener('click', () => {
      requestTree();
      scenarioStatus.textContent = 'Requesting UI tree…';
    });

    // ── Запись и воспроизведение сценариев BankID — всё через панель ───────────
    // Эмулятор запускается прямо на телефоне (open-emulator). Запись ведёт общий
    // ScenarioRecorder на телефоне: start-recording / stop-recording. Тапы по копии
    // экрана на телефоне пишутся с координатами живого BankID. «Replay» воспроизводит
    // сохранённый сценарий на настоящем BankID.
    const pinRecordStatus = $('pin-record-status');
    let pinRecording = false;
    let pinBuffer = '';

    const pinDisplay = $('pin-display');
    const updatePinDisplay = () => renderBankIdDots(pinDisplay, pinBuffer);

    const setPinRecording = (on) => {
      pinRecording = on;
      recording = on;
      document.body.classList.toggle('recording', on);
      $('pin-record-stop').classList.toggle('hidden', !on);
      $('pin-record-start').classList.toggle('hidden', on);
    };

    $('pin-open-emulator')?.addEventListener('click', () => {
      session.sendControl({ t: 'open-emulator' });
      pinRecordStatus.textContent = 'Opening BankID emulator on the phone…';
      log('opening BankID emulator on the phone', 'warn');
    });

    $('pin-record-start')?.addEventListener('click', () => {
      setPinRecording(true);
      pinBuffer = '';
      updatePinDisplay();
      session.sendControl({ t: 'start-recording' });
      pinRecordStatus.textContent = 'Recording on the phone — tap the PIN on the BankID emulator screen.';
      log('started PIN recording on the phone', 'warn');
    });

    $('pin-record-stop')?.addEventListener('click', () => {
      setPinRecording(false);
      const name = $('scenario-name').value.trim() || 'BankID PIN';
      session.sendControl({ t: 'stop-recording', name });
      pinRecordStatus.textContent = `Saving on the phone: ${name}…`;
      log(`stopped recording, saving as: ${name}`, 'warn');
    });

    $('scenario-replay')?.addEventListener('click', () => {
      const name = $('scenario-name').value.trim();
      if (!name) { pinRecordStatus.textContent = 'Enter scenario name first'; return; }
      session.sendControl({ t: 'replay-scenario', name });
      pinRecordStatus.textContent = `Replaying: ${name} on real BankID…`;
      log(`replaying scenario: ${name}`, 'warn');
    });

    // Визуальный эмулятор в панели — только подсказка/эталон, тапы здесь НЕ пишутся.
    document.querySelectorAll('#pinpad-emulator .bankid-key').forEach((btn) => {
      btn.addEventListener('click', () => {
        const digit = btn.dataset.digit;
        const action = btn.dataset.action;
        if (action === 'radera') pinBuffer = pinBuffer.slice(0, -1);
        else if (action !== 'identifiera') pinBuffer += digit;
        updatePinDisplay();
      });
    });

    // ── Обучение: редактор уроков/слайдов с аннотациями ─────────────────────────
    // Слайд = { src: dataURL, marks: [{x,y}] (доли), caption }. Метки рисуются поверх
    // картинки. Урок = набор слайдов, хранится в localStorage. Черновик автосохраняется,
    // чтобы обновление вкладки не потеряло работу.
    const DRAFT_KEY = 'pult-teach-draft';
    const LESSONS_KEY = 'pult-lessons';
    let slides = [];
    let slideIdx = 0;
    let shownIdx = -1; // какой слайд сейчас на экране бабушки
    const teachPos = $('teach-pos');
    const strip = $('teach-strip');
    const canvas = $('teach-canvas');
    const cap = $('teach-caption');
    const onLbl = $('teach-on');
    const cur = () => slides[slideIdx];
    const live = () => session.channel && session.channel.readyState === 'open';

    const saveDraft = () => {
      try { localStorage.setItem(DRAFT_KEY, JSON.stringify(slides)); } catch (e) { /* переполнено — переживём */ }
    };

    // Нарисовать слайд (картинка + красные метки «нажми сюда») в canvas.
    const paint = (slide, cv) => new Promise((resolve) => {
      const img = new Image();
      img.onload = () => {
        cv.width = img.width; cv.height = img.height;
        const g = cv.getContext('2d');
        g.drawImage(img, 0, 0);
        for (const m of slide.marks) {
          const x = m.x * img.width, y = m.y * img.height;
          const r = Math.max(24, img.width * 0.06);
          g.strokeStyle = '#EF4444'; g.lineWidth = Math.max(6, img.width * 0.012);
          g.beginPath(); g.arc(x, y, r, 0, 7); g.stroke();
          g.beginPath(); g.moveTo(x, y + r * 2.4); g.lineTo(x, y + r + 6); g.stroke();
          g.beginPath(); g.moveTo(x - r * 0.5, y + r + r * 0.6); g.lineTo(x, y + r + 4); g.lineTo(x + r * 0.5, y + r + r * 0.6); g.stroke();
        }
        resolve();
      };
      img.onerror = () => { cv.width = 0; cv.height = 0; resolve(); };
      img.src = slide.src;
    });

    const renderPreview = async () => {
      if (!cur()) { canvas.width = 0; canvas.height = 0; cap.value = ''; return; }
      await paint(cur(), canvas);
      cap.value = cur().caption || '';
    };
    const renderStrip = () => {
      strip.innerHTML = '';
      slides.forEach((s, i) => {
        const cell = document.createElement('div');
        cell.className = 'thumb' + (i === slideIdx ? ' sel' : '');
        const t = document.createElement('img');
        t.src = s.src;
        t.addEventListener('click', () => { slideIdx = i; refresh(); });
        cell.appendChild(t);
        if (i === shownIdx) { const b = document.createElement('span'); b.className = 'badge'; b.textContent = '●'; cell.appendChild(b); }
        strip.appendChild(cell);
      });
    };
    const refresh = () => {
      teachPos.textContent = slides.length ? `${slideIdx + 1} / ${slides.length}` : '—';
      onLbl.textContent = shownIdx >= 0 ? `On elder’s screen: ${shownIdx + 1}` : 'On elder’s screen: —';
      renderStrip(); renderPreview();
    };

    // Показать текущий слайд на экране бабушки (запекаем метки в картинку).
    const showCurrent = async () => {
      if (!cur()) return;
      if (!live()) { log('connect a session first, then show the slide', 'warn'); return; }
      const flat = document.createElement('canvas');
      await paint(cur(), flat);
      session.sendSlide(flat.toDataURL('image/jpeg', 0.6), cur().caption);
      shownIdx = slideIdx; refresh();
      log(`showing slide ${slideIdx + 1}/${slides.length} on the elder’s screen`);
    };

    $('teach-files')?.addEventListener('change', async (event) => {
      for (const file of event.target.files) {
        const src = await resizeImage(file, 800, 0.6);
        slides.push({ src, marks: [], caption: '' });
      }
      slideIdx = slides.length - 1; saveDraft(); refresh();
    });
    // Клик по превью → метка в этой точке (в долях кадра).
    canvas?.addEventListener('click', (e) => {
      if (!cur()) return;
      const rect = canvas.getBoundingClientRect();
      cur().marks.push({ x: (e.clientX - rect.left) / rect.width, y: (e.clientY - rect.top) / rect.height });
      saveDraft(); renderPreview();
    });
    $('teach-undo')?.addEventListener('click', () => { cur()?.marks.pop(); saveDraft(); renderPreview(); });
    $('teach-clear')?.addEventListener('click', () => { if (cur()) { cur().marks = []; saveDraft(); renderPreview(); } });
    $('teach-del-slide')?.addEventListener('click', () => {
      if (!cur()) return;
      slides.splice(slideIdx, 1);
      if (slideIdx >= slides.length) slideIdx = Math.max(0, slides.length - 1);
      if (shownIdx === slideIdx) shownIdx = -1;
      saveDraft(); refresh();
    });
    cap?.addEventListener('input', () => { if (cur()) { cur().caption = cap.value; saveDraft(); } });

    const go = (i) => { slideIdx = i; refresh(); if ($('teach-auto').checked && live()) showCurrent(); };
    $('teach-prev')?.addEventListener('click', () => { if (slideIdx > 0) go(slideIdx - 1); });
    $('teach-next')?.addEventListener('click', () => { if (slideIdx < slides.length - 1) go(slideIdx + 1); });
    $('teach-start')?.addEventListener('click', () => { if (!slides.length) return; slideIdx = 0; refresh(); showCurrent(); });
    $('teach-show')?.addEventListener('click', showCurrent);
    $('teach-hide')?.addEventListener('click', () => { session.hideSlide(); shownIdx = -1; refresh(); log('slide hidden'); });

    // ── Уроки: сохранение/загрузка/переиспользование ────────────────────────────
    const lessons = () => { try { return JSON.parse(localStorage.getItem(LESSONS_KEY) || '{}'); } catch (e) { return {}; } };
    const renderLessons = () => {
      const list = $('lesson-list');
      const keep = list.value;
      list.innerHTML = '';
      for (const name of Object.keys(lessons())) {
        const o = document.createElement('option'); o.value = name; o.textContent = name; list.appendChild(o);
      }
      list.value = keep;
    };
    $('lesson-save')?.addEventListener('click', () => {
      const name = $('lesson-name').value.trim();
      if (!name) { log('name the lesson first', 'warn'); return; }
      if (!slides.length) { log('nothing to save', 'warn'); return; }
      const all = lessons(); all[name] = slides;
      try { localStorage.setItem(LESSONS_KEY, JSON.stringify(all)); }
      catch (e) { log('storage full — remove old lessons or use fewer/smaller images', 'error'); return; }
      renderLessons(); $('lesson-list').value = name; log(`lesson saved: ${name} (${slides.length} slides)`);
    });
    $('lesson-load')?.addEventListener('click', () => {
      const name = $('lesson-list').value;
      const l = lessons()[name];
      if (!l) return;
      slides = JSON.parse(JSON.stringify(l)); slideIdx = 0; shownIdx = -1;
      $('lesson-name').value = name; saveDraft(); refresh(); log(`lesson loaded: ${name}`);
    });
    $('lesson-del')?.addEventListener('click', () => {
      const name = $('lesson-list').value; if (!name) return;
      const all = lessons(); delete all[name];
      localStorage.setItem(LESSONS_KEY, JSON.stringify(all)); renderLessons();
    });
    $('teach-new')?.addEventListener('click', () => {
      slides = []; slideIdx = 0; shownIdx = -1; $('lesson-name').value = ''; saveDraft(); refresh();
    });

    // Восстановить черновик после обновления вкладки.
    try {
      const d = JSON.parse(localStorage.getItem(DRAFT_KEY) || '[]');
      if (Array.isArray(d) && d.length) { slides = d; }
    } catch (e) { /* нет черновика */ }
    renderLessons(); refresh();

    // Громкость, яркость, шторка, быстрые настройки, блокировка.
    document.querySelectorAll('[data-sys]').forEach((button) => {
      button.addEventListener('click', () => session.sys(button.dataset.sys));
    });

    // Удалённое вкл/выкл службы управления на телефоне бабушки.
    // Состояние приходит от телефона (control-state) — кнопка его отражает.
    const controlToggle = $('control-toggle');
    let controlOn = null;
    const renderControl = () => {
      if (controlOn === null) { controlToggle.textContent = 'Control: —'; return; }
      controlToggle.textContent = controlOn ? 'Control: ON — tap to disable' : 'Control: OFF — tap to enable';
      controlToggle.classList.toggle('danger', controlOn);
    };
    controlToggle?.addEventListener('click', () => {
      // Выкл срабатывает сразу; вкл открывает настройки у бабушки (включает человек).
      const target = !(controlOn === true);
      session.controlService(target);
      if (!target) controlOn = false; // отключение оптимистично — телефон подтвердит
      renderControl();
    });

    // Полноэкранный режим. Свой класс `immersive` — основа (работает везде, включая
    // iOS, где requestFullscreen для обычных элементов не поддерживается), нативный
    // fullscreen — сверху, чтобы спрятать адресную строку браузера.
    const stage = $('stage');
    const setImmersive = (on) => {
      stage.classList.toggle('immersive', on);
      $('fullscreen').textContent = on ? '⤢ Exit' : '⛶ Full screen';
    };
    $('fullscreen')?.addEventListener('click', async () => {
      const on = !stage.classList.contains('immersive');
      setImmersive(on);
      if (on) {
        await stage.requestFullscreen?.().catch(() => {});
      } else if (document.fullscreenElement) {
        await document.exitFullscreen?.().catch(() => {});
      }
    });
    // Выход по Escape/системной кнопке должен снимать и наш класс, иначе панель
    // останется растянутой поверх страницы.
    document.addEventListener('fullscreenchange', () => {
      if (!document.fullscreenElement) setImmersive(false);
    });

    document.querySelectorAll('[data-say]').forEach((button) => {
      button.addEventListener('click', () => session.say(button.dataset.say));
    });

    // Срабатывать только на НОВОЕ открытие BankID: если BankID уже был открыт,
    // когда поднялась сессия, не гоняем автовход по неизвестному экрану.
    // Сессия всегда начинается с видимого экрана — поэтому стартуем с true.
    let seenUnredacted = true;
    session.addEventListener('control', (event) => {
      const message = event.detail;
      if (message.t === 'redact') {
        explicitRedacted = Boolean(message.on);
        updateRedacted();
        log(message.on ? 'the elder opened the banking app \u2014 screen hidden' : 'screen visible again', 'warn');
        if (!explicitRedacted) seenUnredacted = true;
        // Автовход запускаем только когда на экране именно BankID (com.bankid.bus),
        // а не сам Swedbank — иначе тапы уйдут в экран входа банка, а не в PIN-pad.
        const notInCooldown = Date.now() >= bankIdCooldownUntil;
        if (explicitRedacted && message.reason === 'banking-app' && message.pkg === 'com.bankid.bus'
            && bankidPinAuto?.checked && savedBankIdPin && seenUnredacted && notInCooldown) {
          runBankIdAutoLogin();
        }
        if (explicitRedacted && message.reason === 'banking-app' && message.pkg === 'com.bankid.bus'
            && bankidFixedAuto?.checked && seenUnredacted && notInCooldown) {
          runBankIdFixedLogin();
        }
      }
      if (message.t === 'screen-state') log(`sharing started: ${message.w}\u00d7${message.h}`);
      if (message.t === 'stop') log('session stopped on the elder\u2019s side', 'warn');
      if (message.t === 'control-state') {
        controlOn = Boolean(message.enabled);
        renderControl();
        log(controlOn ? 'control service is ON' : 'control service is OFF');
      }
      if (message.t === 'ui-tree') {
        renderTree(message.tree);
        scenarioStatus.textContent = `UI tree: ${(message.tree || []).length} elements`;
      }
    });
    return;
  }

  $('helper-name').textContent = pair.name ?? 'assistant';
  $('consent-title').textContent = `${pair.name ?? 'Alex'} would like to help`;
  $('session-title').textContent = `${pair.name ?? 'Alex'} is assisting you`;

  $('allow').addEventListener('click', () => session.grant());
  $('deny').addEventListener('click', () => session.deny());
  $('stop').addEventListener('click', () => session.end('stopped-by-grandma'));

  $('redact').addEventListener('click', async () => {
    await session.setRedacted(!session.redacted);
    $('redact').textContent = session.redacted
      ? 'Close \u201cbanking app\u201d'
      : 'Open \u201cbanking app\u201d';
  });

  session.addEventListener('local-stream', (event) => {
    $('local').srcObject = event.detail;
  });

  session.addEventListener('control', (event) => {
    const message = event.detail;
    if (message.t === 'pointer') {
      const marker = $('local-pointer');
      const video = $('local').getBoundingClientRect();
      marker.style.left = `${video.left + message.x * video.width}px`;
      marker.style.top = `${video.top + message.y * video.height}px`;
      show(marker, true);
      clearTimeout(marker.dataset.timer);
      marker.dataset.timer = setTimeout(() => show(marker, false), 3000);
    }
    if (message.t === 'say') $('said').textContent = message.text;
    if (message.t === 'stop') log('the assistant ended the session');
  });
}

bootstrap().catch((error) => log(`could not start the panel: ${error.message}`, 'error'));
