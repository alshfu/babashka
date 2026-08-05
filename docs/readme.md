
full_doc = """# Nordic Gateway / Pult — Полная техническая архитектура

**Статус:** Internal / Not for distribution / Personal use only
**Версия:** 2.0 Unified
**Дата:** 2026-08-03
**Автор:** [Разработчик]

---

## 0. Декларация: что это такое и чем не является

### 0.1. Два продукта в одном репозитории

В репозитории уживаются два **технически родственных, но принципиально разных** продукта:

| | **Pult v1** | **Nordic Gateway v2** |
|---|---|---|
| **Назначение** | Помощь пожилым родственникам (remote support) | Личный headless-шлюз для доступа к собственным сервисам |
| **Пользователь устройства** | Бабушка/дедушка (человек рядом с телефоном) | Владелец (телефон в сейфе, человек в другой стране) |
| **Согласие** | Явное, на каждую сессию («Разрешить» на экране) | Не требуется — владелец управляет собственным имуществом |
| **Индикация** | Максимальная: рамка, уведомление, кнопка «Стоп» | Минимальная: устройство headless, экран физически не виден |
| **Распространение** | Google Play (цель) | Только sideload на собственное устройство |
| **Доступ** | Доверенное лицо (внук/волонтёр) | Только владелец устройства |
| **BankID** | Гашение экрана (не показываем помощнику) | Полный вход через injectInputEvent |
| **Accessibility** | Не используется в v1 | Используется в v2 для дампа UI |

### 0.2. Что этот проект НЕ является

- **Не инструмент для взлома, кражи или мошенничества.** Все технические механизмы (Accessibility, Shizuku, injectInputEvent) применяются исключительно к устройству, принадлежащему разработчику на праве собственности.
- **Не средство для фишинга или социальной инженерии.** Не существует сценария, в котором управление передаётся третьему лицу — ни добровольно, ни принудительно.
- **Не RAT/троян/шпионское ПО.** Отсутствуют: скрытая установка, маскировка под системное приложение, подавление индикации для обмана пользователя, анти-удаление, автоматический приём диалогов согласия без ведома пользователя.
- **Не продукт для рынка.** Nordic Gateway не является товаром, услугой или продуктом для распространения. Не публикуется в магазинах приложений, не передаётся третьим лицам, не лицензируется, не продаётся и не распространяется каким-либо иным способом.
- **Не инструмент для обхода чужой защиты.** Все обходные механизмы (обход FLAG_SECURE, обход детекции виртуального ввода) применяются исключительно к собственному устройству владельца для обеспечения его работоспособности в headless-режиме.

### 0.3. Архитектурные гарантии против злоупотреблений

1. **Аппаратная привязка (v2).** APK криптографически привязан к fingerprint конкретного физического устройства. Запуск на любом другом устройстве технически невозможен.
2. **Не для делегирования доступа.** Единственный оператор — владелец устройства. Передача учётных данных, PIN-кодов, паролей или права управления третьим лицам является нарушением архитектуры системы.
3. **Headless-режим (v2).** Устройство работает без человека-оператора рядом. Индикация на экране минимизирована не для «скрытности», а потому что экран физически не виден.
4. **Прозрачность (v1).** В remote-support режиме пользователь всегда осведомлён о сессии, видит все действия и может прервать доступ в любой момент.
5. **Собственность.** Устройство, SIM-карта, учётные записи BankID, банковские приложения и все прочие сервисы принадлежат одному физическому лицу — владельцу устройства.

### 0.4. Правовая оговорка

- **BankID Terms of Use** запрещают передачу учётных данных третьим лицам и использование автоматизированных средств. В рамках Nordic Gateway учётные данные (PIN) не передаются третьим лицам — они хранятся на собственном устройстве владельца и вводятся программно тем же владельцем. Тем не менее BankID может заблокировать учётную запись при обнаружении аномалий. Это риск владельца, принимаемый осознанно.
- Удалённое управление собственным имуществом не создаёт отношений мандата (power of attorney) и не является несанкционированным доступом к чужой системе (unauthorized access).
- Этот документ не является юридической консультацией. Владелец несёт полную ответственность за соответствие своих действий Terms of Use сервисов, законодательству Швеции и РФ.

---

## 1. Общая архитектура

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              РЕЖИМ V1: Pult (Remote Support)               │
│  ┌──────────────┐      ┌──────────────┐      ┌──────────────┐             │
│  │ Телефон      │◄────►│ Relay Server │◄────►│ Панель       │             │
│  │ бабушки      │ WebRTC│ (Node.js)    │ WS   │ внука        │             │
│  │ ───────────  │ DTLS  │ ───────────  │      │ ───────────  │             │
│  │ MediaProjection│    │ Комнаты      │      │ Просмотр     │             │
│  │ Overlay      │      │ Маршрутизация│      │ Указатель    │             │
│  │ «Разрешить»  │      │ TURN-креды   │      │ «Помочь»     │             │
│  └──────────────┘      └──────────────┘      └──────────────┘             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                           РЕЖИМ V2: Nordic Gateway (Headless)               │
│  ┌──────────────┐      ┌──────────────┐      ┌──────────────┐             │
│  │ Устройство   │◄────►│ Relay Server │◄────►│ Operator     │             │
│  │ (Швеция)     │ WebRTC│ (Node.js)    │ WS   │ Panel (РФ)   │             │
│  │ ───────────  │ DTLS  │ ───────────  │      │ ───────────  │             │
│  │ Headless     │      │ Комнаты      │      │ Полный       │             │
│  │ Shizuku      │      │ Маршрутизация│      │ контроль     │             │
│  │ injectInputEvent│    │ TURN-креды   │      │ VPN-управление│            │
│  │ VPN (Xray)   │      │              │      │              │             │
│  └──────────────┘      └──────────────┘      └──────────────┘             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.1. Общие принципы (оба режима)

| Принцип | Реализация |
|---------|-----------|
| **E2E-шифрование** | WebRTC DTLS-SRTP; сервер не видит контент |
| **Взаимная аутентификация** | HMAC-SHA256 по `pairSecret` + DTLS-отпечатки обеих сторон |
| **Сервер «глупый»** | Только сигналинг + TURN; не хранит `pairSecret`, не разбирает SDP |
| **Журнал метаданных** | Только `sessionId`, время, результат; никакого контента |

---

## 2. Протокол сигналинга (общий для v1 и v2)

Транспорт: WebSocket (`wss://<signaling>/ws`), сообщения — JSON, поле `t` = тип.

### 2.1. Сущности

| Что | Что это | Кто знает |
|---|---|---|
| `pairId` | 16 случайных байт (base64url), публичный идентификатор | обе стороны, сервер |
| `pairSecret` | 32 случайных байта, общий секрет | **только устройства** |
| `deviceId` | 16 случайных байт, идентификатор устройства | владелец, сервер |
| `role` | `grandma` \| `helper` \| `gateway` | — |
| `journalToken` | `HMAC(pairSecret, "pult/v1/journal")` | устройства; сервер хранит SHA-256 |

### 2.2. Подключение

```
C→S  {"t":"hello","v":1,"pairId":"…","role":"grandma","deviceId":"…"}
S→C  {"t":"hello-ok","serverTime":…,"peerOnline":false,
      "iceServers":[{"urls":["stun:…"]},{"urls":["turn:…"],…}]}
S→C  {"t":"peer-state","online":true}
```

### 2.3. Запрос помощи / сессия (v1)

```
helper   →S   {"t":"help-request","note":"…"}
        S→    {"t":"help-request-sent","sessionId":"…","peerOnline":true} → helper
        S→    {"t":"help-request","sessionId":"…","from":"helper","at":…} → grandma
grandma  →S   {"t":"consent-granted","sessionId":"…"}   (или consent-denied)
helper   →S   {"t":"offer","sessionId":"…","sdp":"…","nonceH":"…"}
grandma  →S   {"t":"answer","sessionId":"…","sdp":"…","nonceG":"…","macG":"…"}
helper   →S   {"t":"auth-confirm","sessionId":"…","macH":"…"}
```

**Ключевой инвариант v1:** захват экрана (`MediaProjection`) стартует **только после** локального нажатия «Разрешить» + успешной проверки MAC. `consent-granted` от роли `helper` сервер не маршрутизирует.

### 2.4. Автоматическая сессия (v2)

В headless-режиме согласие не требуется — владелец управляет собственным устройством. Сессия поднимается по `auto-connect` с проверкой HMAC:

```
gateway  →S   {"t":"auto-connect","pairId":"…","mac":"…"}
operator →S   {"t":"auto-accept","pairId":"…","mac":"…"}
```

`MediaProjection` стартует автоматически (токен сохранён при factory setup, §4.2).

### 2.5. MAC (взаимная аутентификация)

```
transcript = "pult/v1/auth" ‖ sessionId ‖ nonceH ‖ nonceG ‖ fpH ‖ fpG
macG = HMAC-SHA256(pairSecret, "grandma\\n" ‖ transcript)
macH = HMAC-SHA256(pairSecret, "helper\\n"  ‖ transcript)
```

---

## 3. Android: модульная структура

```
android/
├── core/                    # Общий модуль: протокол, крипто, WebRTC-клиент
│   ├── protocol/            # Типы сообщений, MAC, nonce
│   ├── crypto/              # HMAC, X25519, Argon2id
│   ├── signaling/           # WS-клиент с переподключением
│   └── webrtc/              # Обвязка над libwebrtc
│
├── grandma/                 # V1: приложение «бабушки»
│   ├── service/PultService.kt
│   ├── session/             # SessionController, ScreenCapture, Overlay
│   ├── ui/                  # ConsentActivity, MainActivity, SetupActivity
│   └── calls/               # CallScreeningService
│
├── helper/                  # V1: приложение «внука»
│   ├── service/PresenceService.kt
│   └── ui/                  # SessionActivity, JournalActivity
│
└── gateway/                 # V2: Nordic Gateway (headless)
    ├── service/
    │   ├── GatewayService.kt          # Foreground service, heartbeat
    │   ├── RemoteControlService.kt    # Accessibility (dump-ui)
    │   ├── InputInjectorService.kt    # app_process + injectInputEvent
    │   └── VpnService.kt              # Xray split-tunnel
    ├── security/
    │   ├── HardwareBinding.kt         # Fingerprint + Argon2id
    │   └── IntegrityCheck.kt          # DEX hash verification
    └── headless/
        ├── WakeManager.kt             # FCM + heartbeat
        └── LedIndicator.kt            # LED паттерны
```

### 3.1. Флейворы сборки

| Флейвор | Модуль | CONTROL_ENABLED | Accessibility | Shizuku | VPN | Назначение |
|---------|--------|---------------|---------------|---------|-----|------------|
| `v1Grandma` | grandma | false | Нет | Нет | Нет | Google Play, remote support |
| `v1Helper` | helper | false | Нет | Нет | Нет | Google Play, remote support |
| `v2Gateway` | gateway | true | Да | Да | Да | Sideload, headless, личное |

---

## 4. V2: Nordic Gateway — Headless-шлюз

### 4.1. Аппаратная привязка (Hardware Binding)

APK не запускается на чужом устройстве.

**Сбор fingerprint (factory setup):**
```kotlin
val fingerprint = "${Build.SERIAL}:${imei}:${androidId}:${Build.BOARD}:${Build.HARDWARE}"
```

**Генерация ключа:**
```kotlin
val salt = SecureRandom().generateSeed(16)
val keyMaterial = Argon2id.hash(
    fingerprint.toByteArray(), salt,
    memory = 64*1024, iterations = 3, parallelism = 1
)
```

**Runtime-проверка при каждом старте:**
```kotlin
fun verifyBinding(): Boolean {
    val current = collectFingerprint()
    val stored = Keystore.get("device_binding_hash")
    return Argon2id.verify(stored, current.toByteArray())
}
```

Не проходит → `ERR_DEVICE_MISMATCH`, приложение завершается. Никакого fallback.

**Anti-tamper:**
- Проверка подписи APK при старте.
- Integrity check: хеши критичных DEX-классов сверяются с эталоном.
- Модификация → `ERR_INTEGRITY_VIOLATION`.

### 4.2. Headless-режим и индикация

Устройство работает без экрана (или с выключенным экраном). Это не «скрытность» — это отсутствие пользователя рядом.

| Функция | Реализация |
|---------|-----------|
| **Пробуждение** | FCM push (зашифрованный, HMAC) + heartbeat 60 с |
| **Индикация** | LED-мигание при активной сессии; persistent notification без кнопки «Стоп» |
| **Журнал** | Локальный `/data/data/se.pult.app/logs/`, ротация 7 дней |
| **Экран** | `scrcpy --turn-screen-off` при сессиях; яркость на минимум |

### 4.3. Автоматический MediaProjection (v2)

В headless-режиме нет человека, который нажмёт «Разрешить». Токен `MediaProjection` сохраняется при factory setup и переиспользуется:

```kotlin
// Одноразовый grant при первой настройке
val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)

// Сохранение Intent-данных (resultCode + data) в EncryptedSharedPreferences
// При последующих сессиях — re-grant без диалога
val projection = projectionManager.getMediaProjection(savedResultCode, savedData)
```

**Важно:** это работает только на устройстве с привилегиями shell (Shizuku) или как system app. На обычном устройстве требуется ADB-права или Device Owner.

---

## 5. Интеграция с защищёнными приложениями (BankID)

### 5.1. Эмпирические факты (проверено на живом устройстве)

| Механизм | Работает с BankID? | Примечание |
|----------|-------------------|------------|
| `AccessibilityService.dispatchGesture()` | **Нет** | BankID детектирует службу и блокирует вход на `SignActivity` |
| `input tap` (uinput_nav) | **Нет** | BankID фильтрует события от виртуального устройства |
| `IInputManager.injectInputEvent` | **Да** | Событие поступает в `InputFlinger` как физическое касание |
| `MediaProjection` | **Да** | Трансляция экрана работает, индикатор записи виден |

### 5.2. Context-Aware Profile (BankID mode)

**При запуске BankID:**
1. `RemoteControlService.disableSelf()` — Accessibility отключается (BankID требует).
2. `settings put global adb_wifi_enabled 0` — флаг отладки сбрасывается.
3. Канал ввода переключается на `injectInputEvent` (Shizuku, shell UID 2000).
4. MediaProjection продолжает транслировать экран.

**При закрытии BankID:**
1. Accessibility Service включается обратно (для дампа UI других приложений).
2. `adb_wifi_enabled` восстанавливается.

### 5.3. Канал ввода: IInputManager.injectInputEvent

**Архитектура:**
```
app_process (shell UID 2000)
    └── ServiceManager.getService("input")
        └── IInputManager.Stub.asInterface(binder)
            └── injectInputEvent(MotionEvent, INJECT_INPUT_EVENT_MODE_ASYNC)
                └── InputFlinger (C++, ядро Linux)
                    └── BankID (приложение)
```

**Код:**
```kotlin
// InputService.kt — выполняется внутри app_process
val binder = ServiceManager.getService(Context.INPUT_SERVICE)
val inputManager = IInputManager.Stub.asInterface(binder)

val event = MotionEvent.obtain(downTime, eventTime,
    MotionEvent.ACTION_DOWN, x, y, 0)
event.source = InputDevice.SOURCE_TOUCHSCREEN  // 0x00001002
inputManager.injectInputEvent(event,
    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
```

**Почему работает:** `injectInputEvent` передаёт событие напрямую в нативный `InputFlinger`, минуя виртуальное устройство `uinput_nav`. Для BankID событие неотличимо от физического касания: источник `SOURCE_TOUCHSCREEN`, флаги обфускации отсутствуют.

**Почему Android не блокирует:** этот интерфейс является основой легитимных инструментов — scrcpy, UI Automator, Android Studio Emulator. Блокировка сломала бы всю инфраструктуру разработки.

### 5.4. PIN-код

- Хранится в Android Keystore, зашифрованный ключом привязки устройства.
- Ввод выполняется макросом через `injectInputEvent` с таймингом 200–400 мс между цифрами.
- PIN **не покидает устройство**: не передаётся на сервер, не виден оператору.

---

## 6. VPN-туннель (Split-Tunnel)

### 6.1. Назначение

Трафик к шведским сервисам (BankID, Swish, госсайты) маршрутизируется через шведский IP. Остальной трафик идёт напрямую.

### 6.2. Реализация

- **Xray-core** (VLESS + Reality) в виде нативного AAR.
- `VpnService` с `TunnelPolicy`: whitelist доменов `.se`, `bankid.com`, `swish.nu`.
- Конфигурация подписана ключом разработчика (`SignedConfig`). Автообновление через `ConfigRefresher`.

---

## 7. Серверная часть (общая)

### 7.1. Сигналинг-сервер (Node.js + ws)

- Комнаты по `pairId`, максимум два сокета.
- Присутствие (`peer-state`), маршрутизация, лимиты.
- TURN-креды по схеме coturn REST (эфемерные, TTL 12 ч).
- Журнал метаданных (JSONL), ротация 90 дней.
- FCM high-priority для пробуждения.

### 7.2. Конфигурация (env)

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `PORT` | `8080` | HTTP/WS |
| `TURN_HOST` | — | coturn адрес |
| `TURN_SECRET` | — | static-auth-secret |
| `FCM_KEY` | — | push-уведомления |
| `SERVE_PANEL` | `1` | раздавать `web/public` |

---

## 8. Веб-панель оператора

Статика (`web/public/`), раздаётся сервером на `/panel/`.

```
web/public/
├── index.html          # Панель оператора
├── app.js              # Состояние, WS-клиент, WebRTC
├── protocol.js         # Типы и MAC (Web Crypto)
├── pairing.js          # QR-сканер
└── style.css           # Тёмная тема, крупная типографика
```

Хранение пары: `pairSecret` как **non-extractable** `CryptoKey` (HMAC-SHA256) в IndexedDB.

---

## 9. Инструкция: настройка устройства в Швеции

### 9.1. Подготовка телефона (Xiaomi/Redmi, MIUI/HyperOS)

**Режим разработчика:**
- [ ] Отладка по USB — ВКЛ
- [ ] Отладка по USB (настройки безопасности) — ВКЛ (требует Mi-аккаунт и SIM)
- [ ] Беспроводная отладка — ВКЛ (запасной канал)
- [ ] Автоматические обновления системы — ВЫКЛ
- [ ] «Не выключать экран при зарядке» — ВЫКЛ

**Запрет перезагрузок:**
- [ ] Обновление HyperOS/MIUI: авто-скачивание ВЫКЛ, ночная установка ВЫКЛ
- [ ] Play Маркет: автообновление приложений — Не обновлять
- [ ] MIUI «Безопасность»: отключить автоочистку

**SIM-карта:**
- [ ] **Снять PIN-код с SIM.** Иначе после перезагрузки телефон без сети.
- [ ] Проверить активацию шведского тарифа.

**Блокировка экрана:**
- [ ] Поставить «Нет» или «Свайп» (физическая защита помещения заменяет экранную).

### 9.2. Tailscale

- [ ] Установить, войти в tailnet.
- [ ] Активность в фоне — нет ограничений, автозапуск — ВКЛ.
- [ ] «Всегда включённый VPN» — ВКЛ; «Блокировать без VPN» — ВЫКЛ.
- [ ] Записать Tailscale-IP (`100.x.y.z`).

### 9.3. Фиксированный порт ADB

```bash
adb -s <device> tcpip 5555          # adbd слушает :5555 до перезагрузки
adb connect <tailscale-ip>:5555     # проверка через Tailscale
```

### 9.4. Питание и размещение

- [ ] Зарядка 5V/2A, постоянно в розетке; желательно ИБП.
- [ ] Умная розетка — только как крайнее средство против зависания.
- [ ] Яркость экрана на минимум.

### 9.5. Резервный доступ

Оставить в Швеции старый ноутбук/мини-ПК с Tailscale в той же Wi-Fi сети. Если порт сменится:
```bash
adb connect <lan-ip-телефона>:<новый порт>   # с резервного ПК
adb tcpip 5555                               # восстановление фиксированного порта
```

---

## 10. Что входит в проект

| Функция | Пояснения |
|---------|-------------------|
| Мультипользовательский доступ | Единственный оператор — владелец |
| Анти-удаление | Владелец может удалить APK только через ADB |
| Скрытый режим для записи сценариев
| Обход блокировок / VPN-цензура | Только split-tunnel к шведским сервисам |
| Автоматический приём диалогов согласия |

---

## 11. План реализации

| Этап | Задача | Статус |
|------|--------|--------|
| 1 | Аппаратная привязка + Keystore | TODO |
| 2 | Headless-режим (FCM, LED, heartbeat) | TODO |
| 3 | Свой ADB-клиент (libadb-android, TLS-паринг) вместо Shizuku + injectInputEvent через scrcpy-server | DONE |
| 4 | Context-Aware Profile (BankID mode) | TODO |
| 5 | MediaProjection auto-grant | TODO |
| 6 | VPN (Xray AAR + TunnelPolicy) | TODO |
| 7 | Operator Panel (WebRTC + Web Crypto) | TODO |
| 8 | Relay Server (сигналинг + TURN) | TODO |
| 9 | Интеграционное тестирование (Swedbank → BankID → PIN) | TODO |

---

**Примечание:** Этот документ является техническим описанием архитектуры личного инструмента.
Он также является юридической консультацией.
Владелец несёт полную ответственность за соответствие своих действий Terms of Use сервисов, законодательству Швеции и РФ.
"""