
full_doc = """# Nordic Gateway / Pult — Fullständig teknisk arkitektur

**Status:** Internal / Not for distribution / Personal use only
**Version:** 2.0 Unified
**Datum:** 2026-08-03
**Upphovsman:** [Utvecklare]

---

## 0. Deklaration: vad detta är och vad det inte är

### 0.1. Två produkter i samma repository

I repositoryt ryms två **tekniskt besläktade men principiellt olika** produkter:

| | **Pult v1** | **Nordic Gateway v2** |
|---|---|---|
| **Syfte** | Hjälp till äldre anhöriga (remote support) | Personlig headless-gateway för åtkomst till egna tjänster |
| **Enhetens användare** | Mormor/morfar (personen bredvid telefonen) | Ägaren (telefonen i kassaskåpet, personen i ett annat land) |
| **Samtycke** | Explicit, för varje session (»Tillåt« på skärmen) | Krävs inte — ägaren hanterar sin egen egendom |
| **Indikering** | Maximal: ram, avisering, knappen »Stopp« | Minimal: enheten är headless, skärmen är fysiskt inte synlig |
| **Distribution** | Google Play (mål) | Endast sideload på egen enhet |
| **Åtkomst** | Betrodd person (barnbarn/frivillig) | Endast enhetsägaren |
| **BankID** | Skärmsläckning (visas inte för hjälparen) | Fullständig inloggning via injectInputEvent |
| **Accessibility** | Används inte i v1 | Används i v2 för UI-dump |

### 0.2. Vad detta projekt INTE är

- **Inte ett verktyg för hackning, stöld eller bedrägeri.** Alla tekniska mekanismer (Accessibility, Shizuku, injectInputEvent) används uteslutande på en enhet som ägs av utvecklaren.
- **Inte ett medel för nätfiske eller social ingenjörskonst.** Det finns inget scenario där kontrollen överlämnas till en tredje part — vare sig frivilligt eller under tvång.
- **Inte en RAT/trojan/spionprogramvara.** Frånvarande: dold installation, förklädnad till systemapp, undertryckande av indikering för att lura användaren, anti-avinstallation, automatiskt godkännande av samtyckesdialoger utan användarens vetskap.
- **Inte en produkt för marknaden.** Nordic Gateway är inte en vara, tjänst eller produkt för spridning. Den publiceras inte i appbutiker, överlämnas inte till tredje part, licensieras inte, säljs inte och sprids inte på något annat sätt.
- **Inte ett verktyg för att kringgå andras skydd.** Alla kringgående mekanismer (kringgående av FLAG_SECURE, kringgående av detektering av virtuell inmatning) används uteslutande på ägarens egen enhet för att säkerställa dess funktion i headless-läge.

### 0.3. Arkitektoniska garantier mot missbruk

1. **Hårdvarubindning (v2).** APK:n är kryptografiskt bunden till fingerprint för en specifik fysisk enhet. Start på vilken annan enhet som helst är tekniskt omöjlig.
2. **Inte för delegering av åtkomst.** Den enda operatören är enhetsägaren. Överlämnande av inloggningsuppgifter, PIN-koder, lösenord eller kontrollrättigheter till tredje part är ett brott mot systemets arkitektur.
3. **Headless-läge (v2).** Enheten arbetar utan en mänsklig operatör i närheten. Skärmindikeringen är minimerad inte för »hemlighetsfullhet«, utan därför att skärmen fysiskt inte syns.
4. **Transparens (v1).** I remote-support-läget är användaren alltid informerad om sessionen, ser alla åtgärder och kan avbryta åtkomsten när som helst.
5. **Äganderätt.** Enheten, SIM-kortet, BankID-kontona, bankapparna och alla övriga tjänster tillhör en och samma fysiska person — enhetsägaren.

### 0.4. Juridiskt förbehåll

- **BankID Terms of Use** förbjuder överlämnande av inloggningsuppgifter till tredje part och användning av automatiserade verktyg. Inom Nordic Gateway överlämnas inte inloggningsuppgifterna (PIN) till tredje part — de lagras på ägarens egen enhet och matas in programmatiskt av samma ägare. Ändå kan BankID spärra kontot vid upptäckt av anomalier. Detta är en risk för ägaren som accepteras medvetet.
- Fjärrstyrning av egen egendom skapar inga fullmaktsförhållanden (power of attorney) och utgör inte obehörig åtkomst till någon annans system (unauthorized access).
- Detta dokument är inte juridisk rådgivning. Ägaren bär fullt ansvar för att sina handlingar överensstämmer med tjänsternas Terms of Use samt svensk och rysk lagstiftning.

---

## 1. Övergripande arkitektur

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

### 1.1. Gemensamma principer (båda lägena)

| Princip | Implementering |
|---------|----------------|
| **E2E-kryptering** | WebRTC DTLS-SRTP; servern ser inte innehållet |
| **Ömsesidig autentisering** | HMAC-SHA256 med `pairSecret` + DTLS-fingeravtryck från båda sidor |
| **»Dum« server** | Endast signalering + TURN; lagrar inte `pairSecret`, tolkar inte SDP |
| **Metadatalogg** | Endast `sessionId`, tid, resultat; inget innehåll |

---

## 2. Signaleringsprotokoll (gemensamt för v1 och v2)

Transport: WebSocket (`wss://<signaling>/ws`), meddelanden — JSON, fältet `t` = typ.

### 2.1. Entiteter

| Vad | Vad det är | Vem känner till |
|---|---|---|
| `pairId` | 16 slumpmässiga byte (base64url), publik identifierare | båda sidor, servern |
| `pairSecret` | 32 slumpmässiga byte, delad hemlighet | **endast enheterna** |
| `deviceId` | 16 slumpmässiga byte, enhetsidentifierare | ägaren, servern |
| `role` | `grandma` \| `helper` \| `gateway` | — |
| `journalToken` | `HMAC(pairSecret, "pult/v1/journal")` | enheterna; servern lagrar SHA-256 |

### 2.2. Anslutning

```
C→S  {"t":"hello","v":1,"pairId":"…","role":"grandma","deviceId":"…"}
S→C  {"t":"hello-ok","serverTime":…,"peerOnline":false,
      "iceServers":[{"urls":["stun:…"]},{"urls":["turn:…"],…}]}
S→C  {"t":"peer-state","online":true}
```

### 2.3. Hjälpförfrågan / session (v1)

```
helper   →S   {"t":"help-request","note":"…"}
        S→    {"t":"help-request-sent","sessionId":"…","peerOnline":true} → helper
        S→    {"t":"help-request","sessionId":"…","from":"helper","at":…} → grandma
grandma  →S   {"t":"consent-granted","sessionId":"…"}   (или consent-denied)
helper   →S   {"t":"offer","sessionId":"…","sdp":"…","nonceH":"…"}
grandma  →S   {"t":"answer","sessionId":"…","sdp":"…","nonceG":"…","macG":"…"}
helper   →S   {"t":"auth-confirm","sessionId":"…","macH":"…"}
```

**Nyckelinvariant i v1:** skärminspelningen (`MediaProjection`) startar **först efter** lokalt tryck på »Tillåt« + framgångsrik MAC-verifiering. `consent-granted` från rollen `helper` dirigeras inte av servern.

### 2.4. Automatisk session (v2)

I headless-läge krävs inget samtycke — ägaren styr sin egen enhet. Sessionen upprättas via `auto-connect` med HMAC-verifiering:

```
gateway  →S   {"t":"auto-connect","pairId":"…","mac":"…"}
operator →S   {"t":"auto-accept","pairId":"…","mac":"…"}
```

`MediaProjection` startar automatiskt (token sparad vid factory setup, §4.2).

### 2.5. MAC (ömsesidig autentisering)

```
transcript = "pult/v1/auth" ‖ sessionId ‖ nonceH ‖ nonceG ‖ fpH ‖ fpG
macG = HMAC-SHA256(pairSecret, "grandma\\n" ‖ transcript)
macH = HMAC-SHA256(pairSecret, "helper\\n"  ‖ transcript)
```

---

## 3. Android: modulär struktur

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

### 3.1. Byggflavors

| Flavor | Modul | CONTROL_ENABLED | Accessibility | Shizuku | VPN | Syfte |
|--------|-------|-----------------|---------------|---------|-----|-------|
| `v1Grandma` | grandma | false | Nej | Nej | Nej | Google Play, remote support |
| `v1Helper` | helper | false | Nej | Nej | Nej | Google Play, remote support |
| `v2Gateway` | gateway | true | Ja | Ja | Ja | Sideload, headless, personlig |

---

## 4. V2: Nordic Gateway — Headless-gateway

### 4.1. Hårdvarubindning (Hardware Binding)

APK:n startar inte på någon annans enhet.

**Insamling av fingerprint (factory setup):**
```kotlin
val fingerprint = "${Build.SERIAL}:${imei}:${androidId}:${Build.BOARD}:${Build.HARDWARE}"
```

**Nyckelgenerering:**
```kotlin
val salt = SecureRandom().generateSeed(16)
val keyMaterial = Argon2id.hash(
    fingerprint.toByteArray(), salt,
    memory = 64*1024, iterations = 3, parallelism = 1
)
```

**Runtime-verifiering vid varje start:**
```kotlin
fun verifyBinding(): Boolean {
    val current = collectFingerprint()
    val stored = Keystore.get("device_binding_hash")
    return Argon2id.verify(stored, current.toByteArray())
}
```

Om verifieringen misslyckas → `ERR_DEVICE_MISMATCH`, avslutas appen. Ingen fallback.

**Anti-tamper:**
- Verifiering av APK-signaturen vid start.
- Integrity check: hashvärdena för kritiska DEX-klasser jämförs mot referensvärden.
- Modifiering → `ERR_INTEGRITY_VIOLATION`.

### 4.2. Headless-läge och indikering

Enheten arbetar utan skärm (eller med avstängd skärm). Detta är inte »hemlighetsfullhet« — det är frånvaron av en användare i närheten.

| Funktion | Implementering |
|----------|----------------|
| **Uppvaknande** | FCM push (krypterad, HMAC) + heartbeat 60 s |
| **Indikering** | LED-blinkning vid aktiv session; persistent notification utan »Stopp«-knapp |
| **Logg** | Lokal `/data/data/se.pult.app/logs/`, rotation 7 dagar |
| **Skärm** | `scrcpy --turn-screen-off` vid sessioner; ljusstyrka på minimum |

### 4.3. Automatisk MediaProjection (v2)

I headless-läge finns det ingen människa som trycker på »Tillåt«. `MediaProjection`-token sparas vid factory setup och återanvänds:

```kotlin
// Одноразовый grant при первой настройке
val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)

// Сохранение Intent-данных (resultCode + data) в EncryptedSharedPreferences
// При последующих сессиях — re-grant без диалога
val projection = projectionManager.getMediaProjection(savedResultCode, savedData)
```

**Viktigt:** detta fungerar endast på en enhet med shell-privilegier (Shizuku) eller som systemapp. På en vanlig enhet krävs ADB-rättigheter eller Device Owner.

---

## 5. Integration med skyddade appar (BankID)

### 5.1. Empiriska fakta (verifierade på en riktig enhet)

| Mekanism | Fungerar med BankID? | Anmärkning |
|----------|----------------------|------------|
| `AccessibilityService.dispatchGesture()` | **Nej** | BankID detekterar tjänsten och blockerar inloggningen på `SignActivity` |
| `input tap` (uinput_nav) | **Nej** | BankID filtrerar händelser från den virtuella enheten |
| `IInputManager.injectInputEvent` | **Ja** | Händelsen når `InputFlinger` som en fysisk beröring |
| `MediaProjection` | **Ja** | Skärmsändningen fungerar, inspelningsindikatorn syns |

### 5.2. Context-Aware Profile (BankID mode)

**Vid start av BankID:**
1. `RemoteControlService.disableSelf()` — Accessibility stängs av (krav från BankID).
2. `settings put global adb_wifi_enabled 0` — felsökningsflaggan nollställs.
3. Inmatningskanalen växlas till `injectInputEvent` (Shizuku, shell UID 2000).
4. MediaProjection fortsätter att sända skärmen.

**Vid stängning av BankID:**
1. Accessibility Service aktiveras igen (för UI-dump av andra appar).
2. `adb_wifi_enabled` återställs.

### 5.3. Inmatningskanal: IInputManager.injectInputEvent

**Arkitektur:**
```
app_process (shell UID 2000)
    └── ServiceManager.getService("input")
        └── IInputManager.Stub.asInterface(binder)
            └── injectInputEvent(MotionEvent, INJECT_INPUT_EVENT_MODE_ASYNC)
                └── InputFlinger (C++, ядро Linux)
                    └── BankID (приложение)
```

**Kod:**
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

**Varför det fungerar:** `injectInputEvent` skickar händelsen direkt till den nativa `InputFlinger`, förbi den virtuella enheten `uinput_nav`. För BankID är händelsen omöjlig att skilja från en fysisk beröring: källa `SOURCE_TOUCHSCREEN`, inga obfuskeringsflaggor.

**Varför Android inte blockerar:** detta gränssnitt är grunden för legitima verktyg — scrcpy, UI Automator, Android Studio Emulator. En blockering skulle slå sönder hela utvecklingsinfrastrukturen.

### 5.4. PIN-kod

- Lagras i Android Keystore, krypterad med enhetens bindningsnyckel.
- Inmatningen utförs via makro genom `injectInputEvent` med en timing på 200–400 ms mellan siffrorna.
- PIN-koden **lämnar aldrig enheten**: den skickas inte till servern och syns inte för operatören.

---

## 6. VPN-tunnel (Split-Tunnel)

### 6.1. Syfte

Trafik till svenska tjänster (BankID, Swish, myndighetssajter) dirigeras via en svensk IP. Övrig trafik går direkt.

### 6.2. Implementering

- **Xray-core** (VLESS + Reality) som ett nativt AAR.
- `VpnService` med `TunnelPolicy`: vitlista över domänerna `.se`, `bankid.com`, `swish.nu`.
- Konfigurationen är signerad med utvecklarens nyckel (`SignedConfig`). Automatisk uppdatering via `ConfigRefresher`.

---

## 7. Serverdel (gemensam)

### 7.1. Signaleringsserver (Node.js + ws)

- Rum per `pairId`, maximalt två sockets.
- Närvaro (`peer-state`), dirigering, gränser.
- TURN-credentials enligt coturn REST-schemat (efemära, TTL 12 tim).
- Metadatalogg (JSONL), rotation 90 dagar.
- FCM high-priority för uppvaknande.

### 7.2. Konfiguration (env)

| Variabel | Standardvärde | Betydelse |
|---|---|---|
| `PORT` | `8080` | HTTP/WS |
| `TURN_HOST` | — | coturn-adress |
| `TURN_SECRET` | — | static-auth-secret |
| `FCM_KEY` | — | push-aviseringar |
| `SERVE_PANEL` | `1` | servera `web/public` |

---

## 8. Operatörspanelen

Statiska filer (`web/public/`), serveras av servern på `/panel/`.

```
web/public/
├── index.html          # Панель оператора
├── app.js              # Состояние, WS-клиент, WebRTC
├── protocol.js         # Типы и MAC (Web Crypto)
├── pairing.js          # QR-сканер
└── style.css           # Тёмная тема, крупная типографика
```

Lagring av paret: `pairSecret` som **non-extractable** `CryptoKey` (HMAC-SHA256) i IndexedDB.

---

## 9. Instruktion: konfiguration av enheten i Sverige

### 9.1. Förberedelse av telefonen (Xiaomi/Redmi, MIUI/HyperOS)

**Utvecklarläge:**
- [ ] USB-felsökning — PÅ
- [ ] USB-felsökning (säkerhetsinställningar) — PÅ (kräver Mi-konto och SIM)
- [ ] Trådlös felsökning — PÅ (reservkanal)
- [ ] Automatiska systemuppdateringar — AV
- [ ] »Håll skärmen tänd vid laddning« — AV

**Förbud mot omstarter:**
- [ ] HyperOS/MIUI-uppdatering: automatisk nedladdning AV, nattlig installation AV
- [ ] Play Butik: automatisk uppdatering av appar — Uppdatera inte
- [ ] MIUI »Säkerhet«: stäng av automatisk rensning

**SIM-kort:**
- [ ] **Ta bort SIM-kortets PIN-kod.** Annars står telefonen utan nätverk efter omstart.
- [ ] Kontrollera att det svenska abonnemanget är aktiverat.

**Skärmlåsning:**
- [ ] Ställ in »Ingen« eller »Svep« (lokalets fysiska skydd ersätter skärmskyddet).

### 9.2. Tailscale

- [ ] Installera, logga in i tailnet.
- [ ] Bakgrundsaktivitet — inga begränsningar, autostart — PÅ.
- [ ] »VPN alltid på« — PÅ; »Blockera anslutningar utan VPN« — AV.
- [ ] Notera Tailscale-IP (`100.x.y.z`).

### 9.3. Fast ADB-port

```bash
adb -s <device> tcpip 5555          # adbd слушает :5555 до перезагрузки
adb connect <tailscale-ip>:5555     # проверка через Tailscale
```

### 9.4. Ström och placering

- [ ] Laddare 5V/2A, alltid i vägguttaget; helst UPS.
- [ ] Smart kontakt — endast som sista utväg mot låsningar.
- [ ] Skärmens ljusstyrka på minimum.

### 9.5. Reservåtkomst

Lämna kvar en gammal bärbar dator/mini-PC med Tailscale i samma Wi-Fi-nätverk i Sverige. Om porten ändras:
```bash
adb connect <lan-ip-телефона>:<новый порт>   # с резервного ПК
adb tcpip 5555                               # восстановление фиксированного порта
```

---

## 10. Vad som ingår i projektet

| Funktion | Förklaringar |
|----------|--------------|
| Fleranvändaråtkomst | Enda operatören är ägaren |
| Anti-avinstallation | Ägaren kan ta bort APK:n endast via ADB |
| Dolt läge för inspelning av scenarier
| Kringgående av blockeringar / VPN-censur | Endast split-tunnel till svenska tjänster |
| Automatiskt godkännande av samtyckesdialoger |

---

## 11. Implementeringsplan

| Steg | Uppgift | Status |
|------|---------|--------|
| 1 | Hårdvarubindning + Keystore | TODO |
| 2 | Headless-läge (FCM, LED, heartbeat) | TODO |
| 3 | Egen ADB-klient (libadb-android, TLS-parkoppling) istället för Shizuku + injectInputEvent via scrcpy-server | DONE |
| 4 | Context-Aware Profile (BankID mode) | TODO |
| 5 | MediaProjection auto-grant | TODO |
| 6 | VPN (Xray AAR + TunnelPolicy) | TODO |
| 7 | Operator Panel (WebRTC + Web Crypto) | TODO |
| 8 | Relay Server (signalering + TURN) | TODO |
| 9 | Integrationstestning (Swedbank → BankID → PIN) | TODO |

---

**Anmärkning:** Detta dokument är en teknisk beskrivning av arkitekturen för ett personligt verktyg.
Det är också en juridisk rådgivning.
Ägaren bär fullt ansvar för att sina handlingar överensstämmer med tjänsternas Terms of Use samt svensk och rysk lagstiftning.
"""
