import { WebSocketServer } from 'ws';

import { log } from './log.js';

/**
 * Канал «VPS ⇄ агент на Mac/PC» для команд управления телефоном, которые не идут
 * через WebRTC (BankID-вход по LAN-стеку: scrcpy-lan на стороне агента).
 *
 * Отдельный путь рядом с `/ws` и `/lowlat`: протокол сигналинга не трогаем.
 * Агент (скрипт scripts/vps-agent-bridge.mjs) держит исходящее WSS-подключение
 * `/agent?pairId=<id>&token=<AGENT_TOKEN>`; панель вызывает HTTP POST
 * `/api/agent-command` (http.js), сервер проксирует команду в сокет агента и ждёт
 * ответ. На одну пару — один агент: переподключение заменяет старый сокет.
 *
 * Протокол: сервер → агент `{id, cmd}`, агент → сервер `{id, res}`.
 */
export function createAgentChannel({ config }) {
  const agents = new Map();   // pairId → socket
  const pending = new Map();  // id → { resolve, timer }
  let seq = 0;

  const wss = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });

  wss.on('connection', (socket, request) => {
    const url = new URL(request.url, 'http://x');
    const pairId = url.searchParams.get('pairId') || '';
    const token = url.searchParams.get('token') || '';

    // Канал закрыт, если токен не настроен на сервере, и для чужих токенов.
    if (!config.agentToken || token !== config.agentToken || !pairId) {
      log.warn('agent: rejected connection', { pairId: pairId || '—' });
      socket.close(4403, 'forbidden');
      return;
    }

    const prev = agents.get(pairId);
    if (prev && prev.readyState === prev.OPEN) prev.close(4000, 'replaced');
    agents.set(pairId, socket);
    log.info('agent: connected', { pairId });

    socket.on('message', (data) => {
      let msg;
      try { msg = JSON.parse(data.toString('utf8')); } catch { return; }
      const entry = pending.get(msg.id);
      if (!entry) return;
      pending.delete(msg.id);
      clearTimeout(entry.timer);
      entry.resolve(msg.res ?? { ok: false, err: 'empty agent response' });
    });

    socket.on('close', () => {
      if (agents.get(pairId) === socket) agents.delete(pairId);
      log.info('agent: disconnected', { pairId });
    });
    socket.on('error', (error) => log.warn(`agent ws: ${error.message}`, { pairId }));
  });

  /**
   * Отправить команду агенту пары и дождаться ответа.
   * Резолвится объектом ответа агента; отклоняется, если агент офлайн или молчит.
   */
  function sendCommand(pairId, cmd, timeoutMs = 90_000) {
    const socket = agents.get(pairId);
    if (!socket || socket.readyState !== socket.OPEN) {
      return Promise.reject(new Error('agent offline'));
    }
    const id = ++seq;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error('agent timeout'));
      }, timeoutMs);
      pending.set(id, { resolve, timer });
      socket.send(JSON.stringify({ id, cmd }), (error) => {
        if (error) {
          pending.delete(id);
          clearTimeout(timer);
          reject(error);
        }
      });
    });
  }

  function agentOnline(pairId) {
    const socket = agents.get(pairId);
    return Boolean(socket && socket.readyState === socket.OPEN);
  }

  return { wss, sendCommand, agentOnline };
}
