import { log } from './log.js';

/**
 * Пробуждение спящего телефона бабушки (FCM high-priority).
 *
 * В push уходит только «постучались, зайди на сигналинг» — ни имени помощника,
 * ни note, ни чего-либо ещё: содержимое запроса бабушка увидит уже в приложении,
 * после того как оно поднимет сокет.
 *
 * Без FCM_KEY работает как заглушка: сценарий «телефон спит» просто не сработает,
 * онлайн-путь останется рабочим. Для пилота этого достаточно.
 */
export function createPush({ config, fetchImpl = globalThis.fetch }) {
  const tokens = new Map(); // pairId → registration token

  return {
    register(pairId, token) {
      if (typeof token === 'string' && token.length > 0) tokens.set(pairId, token);
    },

    forget(pairId) {
      tokens.delete(pairId);
    },

    async wakeGrandma(pairId, sessionId) {
      const token = tokens.get(pairId);
      if (!config.fcmKey || !token) {
        log.debug('push skipped: no key or token', { pairId, sessionId });
        return false;
      }
      try {
        const response = await fetchImpl('https://fcm.googleapis.com/fcm/send', {
          method: 'POST',
          headers: {
            authorization: `key=${config.fcmKey}`,
            'content-type': 'application/json',
          },
          body: JSON.stringify({
            to: token,
            priority: 'high',
            // data-only: показывать окно решает приложение, а не система уведомлений.
            data: { t: 'wake', sessionId },
          }),
        });
        if (!response.ok) {
          log.warn('push отклонён', { pairId, status: response.status });
          return false;
        }
        return true;
      } catch (error) {
        log.warn(`push not sent: ${error.message}`, { pairId });
        return false;
      }
    },
  };
}
