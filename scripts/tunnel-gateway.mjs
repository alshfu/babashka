#!/usr/bin/env node
/**
 * Туннель-шлюз на VPS: SOCKS5 → IP телефона в Швеции (поверх существующего /tunnel).
 *
 * Зачем: «великое правило» — телефон (A-app) далеко, устройства пользователя рядом
 * с ним. Android-шлюз (TunnelProxyNative в B-app) работает, пока жив сам телефон
 * под рукой; но рядом может быть iPhone (где системного прокси нет), Windows-машина
 * с IDE или любой другой девайс. Вместо прокси на КАЖДОМ устройстве поднимаем ОДИН
 * SOCKS5 на VPS: он сам является app-стороной туннеля (/tunnel?side=app), а ближние
 * устройства просто направляют трафик на него (SSH-портфорвард, Tailscale и т.п.) —
 * выход в сети со шведским IP телефона.
 *
 * Протокол /tunnel (server/src/tunnel.js, android/core/.../TunnelEgress.kt):
 *   кадр = [streamId:uint32 BE][op:uint8][payload]; op: 0=open("host:port"), 1=data,
 *   2=close, 3=error. phone-сторона на op=0 открывает TCP к target и шлёт op=1;
 *   ошибки подключения — op=3. Open-ack НЕТ: phone молчит до первых данных, поэтому
 *   SOCKS-ответ «успех» шлём сразу после op=open (иначе дедлок: клиент ждёт ответа,
 *   не шлёт; phone ждёт данных, не отвечает). Провал коннекта — обрыв по op=3.
 *
 * Запуск (на VPS, рядом с сигналингом):
 *   node tunnel-gateway.mjs
 * Env:
 *   TUNNEL_LISTEN_HOST (127.0.0.1)  TUNNEL_LISTEN_PORT (11080)
 *   TUNNEL_UPSTREAM (ws://127.0.0.1:8090/tunnel)  TUNNEL_PAIR  TUNNEL_TOKEN (обязательны)
 *
 * Доступ ближних устройств: ssh -L 11080:127.0.0.1:11080 administrator@<vps>,
 * затем в браузере SOCKS5 127.0.0.1:11080 (socks5h:// — DNS резолвится в Швеции).
 */
import { createServer } from 'node:net';
import crypto from 'node:crypto';

import WebSocket from '../server/node_modules/ws/index.js';

const LISTEN_HOST = process.env.TUNNEL_LISTEN_HOST ?? '127.0.0.1';
const LISTEN_PORT = Number(process.env.TUNNEL_LISTEN_PORT ?? 11080);
const UPSTREAM = process.env.TUNNEL_UPSTREAM ?? 'ws://127.0.0.1:8090/tunnel';
const PAIR = process.env.TUNNEL_PAIR ?? '';
const TOKEN = process.env.TUNNEL_TOKEN ?? '';

if (!PAIR || !TOKEN) {
  console.error('нужны TUNNEL_PAIR и TUNNEL_TOKEN');
  process.exit(1);
}

const log = (msg, extra = '') => console.log(`${new Date().toISOString().slice(11, 19)} ${msg} ${extra}`);

const encodeFrame = (streamId, op, payload) => {
  const frame = Buffer.alloc(5 + payload.length);
  frame.writeUInt32BE(streamId >>> 0, 0);
  frame[4] = op;
  payload.copy(frame, 5);
  return frame;
};

/** Одна пара «SOCKS-клиент ⇄ поток туннеля». */
function handleConnection(client) {
  client.setNoDelay(true);

  let ws = null;
  let wsOpen = false;
  let streamId = 0;
  let replied = false; // SOCKS-ответ CONNECT уже отправлен
  let closed = false;
  let buf = Buffer.alloc(0);
  let phase = 'greet'; // greet → request → pipe
  const pending = []; // кадры data до открытия сокета туннеля

  const closeAll = (why) => {
    if (closed) return;
    closed = true;
    log('close:', why);
    try { ws?.close(); } catch {}
    try { client.destroy(); } catch {}
  };

  const sendData = (chunk) => {
    const frame = encodeFrame(streamId, 1, chunk);
    if (wsOpen && ws?.readyState === ws.OPEN) ws.send(frame, { binary: true });
    else pending.push(frame);
  };

  const socksReply = (ok) => {
    if (replied) return;
    replied = true;
    // REP: 0=успех, 5=соединение отклонено; BND.ADDR/PORT неинформативны (RFC 1928).
    client.write(Buffer.from([0x05, ok ? 0x00 : 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
  };

  const openTunnel = (target) => {
    const url = `${UPSTREAM}?pairId=${encodeURIComponent(PAIR)}&token=${encodeURIComponent(TOKEN)}&side=app`;
    streamId = crypto.randomBytes(4).readUInt32BE(0);
    log('open:', target);
    ws = new WebSocket(url, { maxPayload: 4 * 1024 * 1024 });

    // 24/7-стабильность: сокет умирает молча (переход сети, NAT-зависание). Пингуем
    // каждые 25 с; живости нет больше 70 с — рвём, клиент (браузер/IDE) пересоединится сам.
    let lastAlive = Date.now();
    let gotPhoneFrame = false;
    // Гонка «app подключился раньше phone»: сервер молча теряет op=0, phone его не
    // видит. Повторяем op=0 каждые 3 с, пока phone не ответил первым кадром (его
    // ответа на open нет по протоколу — первый же кадр означает «поток жив»).
    let openRetries = 0;
    const resendOpen = setInterval(() => {
      if (closed || gotPhoneFrame || !wsOpen) return;
      if (++openRetries > 6) { closeAll('phone не отвечает на open'); return; }
      log('resend op=0:', target);
      ws.send(encodeFrame(streamId, 0, Buffer.from(target)));
    }, 3_000);
    const keepalive = setInterval(() => {
      if (closed) { clearInterval(keepalive); clearInterval(resendOpen); return; }
      try { ws?.ping(); } catch {}
      if (Date.now() - lastAlive > 70_000) closeAll('keepalive timeout');
    }, 25_000);

    ws.on('open', () => {
      wsOpen = true;
      ws.send(encodeFrame(streamId, 0, Buffer.from(target))); // op=open
      for (const frame of pending.splice(0)) ws.send(frame, { binary: true });
      // Протокол туннеля без open-ack: phone молчит, пока не придут данные, а
      // клиент (curl/браузер) данные до ответа CONNECT не шлёт — дедлок. Отвечаем
      // «успех» сразу после op=open; если коннект на phone провалится, она пришлёт
      // op=3 и клиент получит обрыв (стандартная практика для таких туннелей).
      socksReply(true);
    });

    ws.on('message', (data, isBinary) => {
      if (!isBinary || closed || data.length < 5) return;
      lastAlive = Date.now();
      gotPhoneFrame = true;
      const op = data[4];
      const payload = data.subarray(5);
      if (op === 1) {
        try { client.write(payload); } catch { closeAll('write failed'); }
      } else if (op === 2 || op === 3) {
        closeAll(`phone ${op === 3 ? `error: ${payload.toString()}` : 'close'}`);
      }
    });
    ws.on('error', (e) => { socksReply(false); closeAll(`ws: ${e.message}`); });
    ws.on('close', () => { if (!replied) socksReply(false); closeAll('ws closed'); });
    ws.on('pong', () => { lastAlive = Date.now(); });
  };

  client.on('data', (chunk) => {
    if (closed) return;
    if (phase === 'pipe') { sendData(chunk); return; }
    buf = Buffer.concat([buf, chunk]);

    if (phase === 'greet') {
      if (buf.length < 2) return;
      if (buf[0] !== 0x05) return closeAll('не SOCKS5');
      const nMethods = buf[1];
      if (buf.length < 2 + nMethods) return;
      client.write(Buffer.from([0x05, 0x00])); // «без аутентификации»
      buf = buf.subarray(2 + nMethods);
      phase = 'request';
    }

    if (phase === 'request') {
      if (buf.length < 4) return;
      const [ver, cmd, , atyp] = buf;
      if (ver !== 0x05 || cmd !== 0x01) { // только CONNECT
        socksReply(false);
        return closeAll('кроме CONNECT ничего не поддержано');
      }
      let host;
      let offset;
      if (atyp === 0x01) {
        if (buf.length < 10) return;
        host = Array.from(buf.subarray(4, 8)).join('.');
        offset = 8;
      } else if (atyp === 0x03) {
        const len = buf[4];
        if (buf.length < 5 + len + 2) return;
        host = buf.subarray(5, 5 + len).toString();
        offset = 5 + len;
      } else if (atyp === 0x04) {
        if (buf.length < 22) return;
        host = Array.from(buf.subarray(4, 20))
          .map((b) => b.toString(16).padStart(2, '0'))
          .join('')
          .match(/.{1,4}/g).join(':');
        offset = 20;
      } else {
        socksReply(false);
        return closeAll('незнакомый ATYP');
      }
      const port = buf.readUInt16BE(offset);
      buf = Buffer.alloc(0);
      phase = 'pipe';
      openTunnel(`${host}:${port}`);
    }
  });

  client.on('error', () => closeAll('client error'));
  client.on('close', () => closeAll('client closed'));
}

const server = createServer(handleConnection);
server.listen(LISTEN_PORT, LISTEN_HOST, () => {
  log(`SOCKS5 tunnel gateway: ${LISTEN_HOST}:${LISTEN_PORT} → пара ${PAIR} через ${UPSTREAM}`);
});
