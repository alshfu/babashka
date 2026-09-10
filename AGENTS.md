# Pult — правила проекта (агентам: не ломать!)

## Indbox-regel / Projektstatus

**Detta är INTE en produkt som ska lanseras och INTE en app för publik användning.**
Här och nu arbetar vi med en privat indbox/sandbox:
- Endast ägda enheter används.
- Endast ägda BankID används.
- Automatiseringen är endast för ägarens eget administrativa bruk — till exempel betalning av egna räkningar vid vistelse utomlands.
- Systemet får INTE vidareutvecklas för att ge tredje part åtkomst till någon annans BankID, eller för att kringgå sanktioner, AML-regler eller finansiella säkerhetskontroller.
- Ingenting i detta repo är avsett för distribution till allmänheten.

Alla ändringar ska respektera detta och inte göras med publik release som mål.

## Золотое правило

**Процесс ввода BankID PIN через Pult-приложения НЕ ИЗМЕНЯТЬ и НЕ ЛОМАТЬ.**
Он выстрадан эмпирически 09.09.2026 и проверен сериями успешных входов.
Любая правка около него — только с повторным E2E-тестом входа.

## Архитектура входа Swedbank/BankID (рабочая, зафиксировать)

```
Note 10 (B-app / Controller)               Readme (A-app / Agent)                  PC-сервер
─────────────────────────                  ──────────────────────────              ───────────
Swedbank → tap «Logga in»
  → bankid:// intent (autostarttoken)
  → B-app (com.bankid.bus)
       перехватывает, moveTaskToBack(!)
       KeepAliveService → /link  ──────────►  server /link (hub) ──► A-app (se.pult.app)
       с выбранным deviceId                                           onDeeplink:
                                                                      1) adb_wifi_enabled=0
                                                                      2) ЖДЁТ смерти adbd-сессии
                                                                      3) raiseBankId (прямой startActivity)
                                                                      4) если PIN ещё не сохранён —
                                                                             BankIdPinActivity (1:1 BankID UI)
                                                                      5) BankIdAgent.complete():
                                                                         LanShell → LanAgent (TCP 47201)
                                                                         scrcpy-server через relay 47202
                                                                         injectInputEvent → PIN
```

### Железные инварианты (нарушение = вход мёртв)

1. **PIN вводится ТОЛЬКО injectInputEvent через scrcpy-server** (shell UID 2000,
   `SOURCE_TOUCHSCREEN`). BankID 7.48 фильтрует: `input tap` (uinput_nav),
   a11y dispatchGesture, HID-клики мыши. Клавиатурный HID `/type` — поле принимает,
   но сценарий ненадёжен (не использовать).
2. **adb_wifi_enabled=0 ДО открытия BankID** и ждать реальной смерти adbd-листенера
   (hasLiveSession через adbd, не LanShell). Иначе экран «bedrägerier» и заказ мёртв.
3. **На Redmi НЕТ включённых accessibility-служб** во время входа (BankID детектирует).
4. **Шлюз уходит с переднего плана сразу** после пересылки диплинка (moveTaskToBack) —
   Swedbank в фоне отменяет заказ по таймауту.
5. **Токен одноразовый**: второй `am start` с тем же autostarttoken сжигает заказ.
   Дедупликация 30 с в PultService — не трогать.
6. **LanAgent резaет вывод до 2000 символов** — чтение экрана только фильтром
   на устройстве (`grep -oE 'text=...'` в shell-команде), никогда не `cat` целиком.
7. Тайминги: заказ живёт ~3 мин; pad умирает по бездействию ~45-60 с. Весь сценарий
   ≤ 20 с от заказа до submit.

### Компоненты

- **B-app** — `app/` (Flutter, Note 10): `KeepAliveService` — /link + TunnelProxyNative (CONNECT-прокси
  127.0.0.1:8877, весь трафик Note 10 выходит с IP выбранной A-app через /tunnel).
  Показывает список A-app-устройств, **не хранит PIN**.
- **A-app** — `android/grandma/` (Readmi): PultService (/ws agent + /tunnel phone), BankIdAgent
  (PIN-сценарий), `BankIdPinActivity` (единственный UI для первого ввода PIN), LanShell,
  AdbShell (fallback), RemoteControlService (a11y — только НЕ в момент BankID).
  PIN хранится **только здесь**, локально и криптованно (`PinStorage`).
  Удалённое управление через lowlat-страницу: LowLatService диспатчит tap/swipe/nav
  **в LanAgent (injectInputEvent), а НЕ в a11y** — сторонние касания во время
  `BankIdAgent.flowActive` игнорируются (не ломать вход).
- `scripts/android-agent/lanagent.dex` — LanAgent: app_process+shell, TCP 47201 (команды)
  / 47202 (relay→scrcpy localabstract). Запуск: `setsid sh -c 'CLASSPATH=/data/local/tmp/lanagent.dex
  app_process / LanAgent 47201 </dev/null >/sdcard/lanagent.log 2>&1 &'`.
- `server/src/` — hub/tunnel/link; heartbeat на вс ех каналах; очередь deeplink для офлайн-grandma.

### MIUI-разрешения grandma (сбрасываются при pm install -r — перевыдать!)

```
appops set se.pult.app SYSTEM_ALERT_WINDOW allow
appops set se.pult.app 10021 allow   # background start activity
```

### Разово при настройке Redmi

```
adb push scripts/android-agent/lanagent.dex /data/local/tmp/lanagent.dex
adb push tools/scrcpy/scrcpy-server /data/local/tmp/scrcpy-inj.jar   # v4.1, BankIdAgent SERVER_CMD
```

### E2E-тест входа (обязателен после любой правки)

Swedbank → Logga in → (ничего не делать) → Note 10 показывает счёт.
Эталонные скрины: `tools/success-1..3.png`, `tools/success-autonomous.png`.
