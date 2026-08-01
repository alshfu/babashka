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

  signaling.connect();
  wire(session, pair);
  if (role === 'helper') wireAdmin(signaling, pair);

  // `?debug=1` — доступ к сессии из консоли и из автотестов стенда.
  // Секрет пары отсюда не достать: он живёт неизвлекаемым CryptoKey.
  if (params.has('debug')) window.pult = { session, signaling, pair };
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
      $('remote').srcObject = event.detail;
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
      if (moved > 0.03) {
        session.swipe(down.x, down.y, up.x, up.y, Math.min(1200, Math.max(120, Date.now() - down.t)));
      } else {
        session.tap(up.x, up.y);
      }
      down = null;
    });
    remote.addEventListener('pointercancel', () => { down = null; });

    $('nav-back')?.addEventListener('click', () => session.nav('back'));
    $('nav-home')?.addEventListener('click', () => session.nav('home'));
    $('nav-recents')?.addEventListener('click', () => session.nav('recents'));

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

    session.addEventListener('control', (event) => {
      const message = event.detail;
      if (message.t === 'redact') {
        show($('redacted'), message.on);
        log(message.on ? 'the elder opened the banking app \u2014 screen hidden' : 'screen visible again', 'warn');
      }
      if (message.t === 'screen-state') log(`sharing started: ${message.w}\u00d7${message.h}`);
      if (message.t === 'stop') log('session stopped on the elder\u2019s side', 'warn');
      if (message.t === 'control-state') {
        controlOn = Boolean(message.enabled);
        renderControl();
        log(controlOn ? 'control service is ON' : 'control service is OFF');
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
