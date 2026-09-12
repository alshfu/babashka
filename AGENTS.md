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

- **B-app** — `app/` (Flutter, Note 10; **при разработке — эмулятор Mac `moderator_test`**:
  там работает весь операторский UI против боевого Redmi; пакет `com.bankid.bus`
  вытесняет настоящий BankID на эмуляторе): `KeepAliveService` — /link + TunnelProxyNative (CONNECT-прокси
  127.0.0.1:8877, весь трафик Note 10 выходит с IP выбранной A-app через /tunnel).
  Показывает список A-app-устройств, **не хранит PIN**.
- **A-app** — `android/grandma/` (Readmi): PultService (/ws agent + /tunnel phone), BankIdAgent
  (PIN-сценарий), `BankIdPinActivity` (единственный UI для первого ввода PIN — экран 1:1
  повторяет numpad BankID 7.48: палитра/логотип/иконки/Inter извлечены из APK
  `just_for_privat_use_and_test/com.bankid.bus_7.48.0.apk`, расклад с apktool лежит в
  `build/apk-extract/bankid*/`). **Zero-click**: кнопок подтверждения нет — 6-я цифра
  сохраняет PIN и закрывает экран автоматически (säkerhetskod всегда 6 цифр); ⌫ стирает,
  отмена — системным «назад». Нижний ряд: ⌫ | 0 | пустая клавиша. `MainActivity` —
  молчаливый экран статуса без единой кнопки (online/offline + имя помощника); локального
  чек-листа настроек больше нет — настройку ведёт B-app кадрами /link: `setup-open {step}`
  (A открывает системный экран шага из фона, step: battery|overlay|notifications|usage|autostart;
  для autostart факт открытия пишется в prefs `pult_setup`), `setup-query` → ответ
  `setup-status {steps:{…,paired}}` (также шлётся при ONLINE и через 10 с после setup-open).
  LanShell, AdbShell (fallback), RemoteControlService (a11y — только НЕ в момент BankID).
  PIN хранится **только здесь**, локально и криптованно (`PinStorage`).
  Удалённое управление через lowlat-страницу: LowLatService диспатчит tap/swipe/nav
  **в LanAgent (injectInputEvent), а НЕ в a11y** — сторонние касания во время
  `BankIdAgent.flowActive` игнорируются (не ломать вход).
- `scripts/android-agent/lanagent.dex` — LanAgent: app_process+shell, TCP 47201 (команды)
  / 47202 (relay→scrcpy localabstract). Запуск: `setsid sh -c 'CLASSPATH=/data/local/tmp/lanagent.dex
  app_process / LanAgent 47201 </dev/null >/sdcard/lanagent.log 2>&1 &'`.
- `server/src/` — hub/tunnel/link; heartbeat на вс ех каналах; очередь deeplink для офлайн-grandma.

### Реаниматор (удалённая перезагрузка A-app)

`POST /api/reanimate` {pairId, action} (x-agent-token) → FCM data-push `{t:'reanimate', action}` →
`PultMessagingService` → `Reanimate`: **restart** — мягкий переподключение сигналинга
(`ACTION_REANIMATE_RESTART` → `PultService.reconnect()`); **reboot** — `svc power reboot`
через shell: сначала как есть (LanAgent на loopback), не вышло — включается adb_wifi
(`AdbShell.enableWirelessDebugging()`) и опрос adbd до 60 с. Работает, пока жив системный
стек (FCM доставлен); полностью подвисшее железо software не спасти. Кнопки — страница
устройства в B-app («Starta om tjänsten» / «Starta om enheten» с подтверждением).
Банковский контур (BankIdAgent) не трогается; после ребута всё поднимается само
(BootReceiver → PultService → adb_wifi=1).

### Туннель «шведский IP» — архитектура под правило «Redmi далеко, устройства рядом»

Единый выход в сеть с IP A-app (Redmi в Швеции) поверх `/tunnel`:
- **SOCKS5-шлюз на VPS** (`scripts/tunnel-gateway.mjs`, systemd `pult-tunnel-gateway`,
  слушает `127.0.0.1:11080` на VPS): сам является app-стороной туннеля, каждый
  SOCKS-CONNECT = один streamId `/tunnel`. Это основной путь для ближних устройств —
  iPhone, Mac, Windows-машина с IDE. Доступ: `ssh -L 11080:127.0.0.1:11080 administrator@<vps>`,
  дальше браузер/IDE на `socks5h://127.0.0.1:11080` (DNS резолвится в Швеции).
  Альтернатива доступа — Tailscale (на VPS уже `tailscaled`): привязать шлюз
  к tailnet-IP и ходить напрямую.
- **Телефонов в паре может быть несколько** (тестовый режим авто-пары): слот phone
  в `/tunnel` ключуется `deviceId` (query-параметр у обеих сторон), вытеснение —
  только своего сокета. Шлюз прибит к Redmi: `TUNNEL_DEVICE=njoAYzichhY` в юните
  `pult-tunnel-gateway.service`; без `deviceId` app-сторона получает первый живой
  телефон (старое поведение). `TUNNEL_TOKEN` вшит в демо-сборку A-app
  (`android/grandma/build.gradle.kts`), поэтому туннель поднимается из коробки.
- **Нативный прокси B-app** (Android, `TunnelProxyNative` на 127.0.0.1:8877) — путь для
  Note 10, оставлен без изменений. На iOS системного прокси нет (нужен Network
  Extension, заблокирован до починки Apple ID) — iPhone ходит через VPS-шлюз.

### Тестовый режим: авто-пара без QR

Свежая установка A-app на ЛЮБОЙ телефон сразу появляется в списке устройств B-app:
при первом старте `PultService` без сохранённой пары встаёт во вшитую тестовую пару
(`AUTO_PAIR_ID`/`AUTO_PAIR_SECRET` в `android/grandma/build.gradle.kts`, оверрайд —
окружением при сборке; пустой `AUTO_PAIR_ID` выключает). Сервер секрет при hello не
проверяет — он нужен только для E2E WebRTC-сессий. Уже спаренное устройство (QR)
авто-пара не трогает. Несколько A-app в одной паре — норма: в хабе слот
`grandma:<deviceId>`, revoke и help-request работают как раньше (revoke — всем
бабушкам, «одна бабушка» для WebRTC — первая живая). QR-спаривание остаётся запасным
путём: карточка «Aktivering» в B-app → шаг `pairing` (setup-open) → PairingActivity.

### «Свой канал» B→A: BankID активируется ТОЛЬКО по запросу из B-app

A-app поднимает BankID исключительно по `Signal.Deeplink` с сервера (хаб пары
аутентифицирован; канал /link — токеном AGENT_TOKEN). Два легальных пути запроса
из B-app, оба сходятся в один и тот же `/link`-кадр `{t:deeplink,url,deviceId}`:
1. **Перехват** — Swedbank открывает `bankid://`, B-app ловит схему (Android:
   intent → KeepAliveService; iOS: app_links → DartLinkService) и пересылает.
2. **Ручной** — кнопка «Skicka bankid-länk» на странице устройства (диалог с
   вводом `bankid:///…`; Android: channel `pult.gateway/link` →
   `KeepAliveService.sendDeeplink`, iOS: DartLinkService.sendDeeplink).
Прочих путей нет: локальные тестовые диплинки `pult://bankid-login` и
`pult://setup?step=<id>` (отладка setup-open без сервера) из MainActivity
A-app — осознанное исключение для отладки без сервера. A отвечает статусами
(`deeplink-status`: sent → opened → signed/failed) обратно в /link — B-app показывает
их на странице устройства. Статус «received»-кадра нет (у протокола туннеля
стадийный словарь фиксирован — новые стадии ломали бы старые сборки B).

### Совместная разработка между городами (Windows-машина ALSH у Redmi)

Redmi (`DQ6TC64DY9PRBE4T`, 25078RA3EE) сидит на Windows-ПК `ALSH` по USB-adb
(рядом же Note10 `R58M9167C4H`). Все машины в одном tailnet (`alshfu@`):
macbook-prom4max (Mac) · alsh/100.102.35.68 (Windows) · vps-xray (VPS).

Доступ к ALSH:
| Способ | Адрес | Логин |
|---|---|---|
| SSH через VPS | `ssh -p 12222 Administrator@85.190.98.57` | Administrator |
| RDP через VPS | `85.190.98.57:13389` | Administrator |
| SSH по Tailscale | `100.102.35.68:22` | Administrator |
| RDP по Tailscale | `100.102.35.68:3389` | Administrator |

adb на ALSH: `C:\Users\Administrator\IdeaProjects\babashka\tools\platform-tools\adb.exe`
(в PATH нет; репозиторий — `C:\Users\Administrator\IdeaProjects\babashka`, IDE — IntelliJ IDEA
2026.2.2, JetBrains-аккаунт общий с Mac). Прямой сценарий: `ssh Administrator@100.102.35.68`
→ `adb -s DQ6TC64DY9PRBE4T shell …`. Реаниматор поднимает PultService без рук: пока процесс
жив, но сервис мёртв, телефон «офлайн» для сервера, но отвечает по USB.

### Живые экраны на ALSH — ВСЕГДА видны (железное правило)

У оператора нет возможности подойти к устройствам физически: окно scrcpy на ALSH —
единственный «глаз» на телефон. **Окна scrcpy Note 10 (`R58M9167C4H`) и Redmi
(`DQ6TC64DY9PRBE4T`) на рабочем столе ALSH обязаны быть видны всегда.**
scrcpy сам себя не перезапускает (любой USB/adb-сбой — и окно мертво), поэтому
это делает сторож: `scripts/win-screens-watchdog.ps1` (цикл 15 с: adb видит
оба серийника → окно живо → иначе переподъём; лог `test_logs/win-screens-watchdog.log`).
Автозапуск — задача Планировщика `PultScreensWatchdog`, ставится один раз:
`powershell -ExecutionPolicy Bypass -File scripts\install-win-screens-watchdog.ps1`.
Пропали окна на ALSH → первым делом проверить эту задачу и лог сторожа,
а не закрывать вопрос «руками».

### MIUI-разрешения grandma (сбрасываются при pm install -r — перевыдать!)

```
appops set se.pult.app SYSTEM_ALERT_WINDOW allow
appops set se.pult.app 10021 allow   # background start activity
```

При установке по USB MIUI один раз спрашивает «Installera via USB» — на Redmi
отмечено «Kom ihåg mitt val», больше не спрашивает. Если устройство на ALSH стало
`unauthorized` (или пропало из `adb devices`) — это физический уровень: кабель/
RSA-диалог на экране, софтом снаружи не лечится (все exec-каналы A-app идут через
adbd/LanAgent, которые в этот момент недоступны). Нужен человек у телефона:
переткнуть USB и подтвердить «Разрешить отладку» (с галочкой «Всегда»).

### Разово при настройке Redmi

Бинари инъекции (lanagent.dex, scrcpy-inj.jar v4.1) **вшиты в assets A-приложения**
(`grandma/src/main/assets/binaries/`) и ставятся в `/data/local/tmp` сами при первом
живом shell (`InjectionBinaries.ensure` — прогрев при коннекте + перед complete()).
Ручной adb push больше не нужен; при желании можно продублировать вручную:
`adb push scripts/android-agent/lanagent.dex /data/local/tmp/lanagent.dex`,
`adb push tools/scrcpy/scrcpy-server /data/local/tmp/scrcpy-inj.jar`.

### E2E-тест входа (обязателен после любой правки)

Swedbank → Logga in → (ничего не делать) → Note 10 показывает счёт.
Эталонные скрины: `tools/success-1..3.png`, `tools/success-autonomous.png`.
