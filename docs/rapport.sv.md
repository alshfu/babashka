# Teknisk rapport: ekosystemet Pult / Nordic Gateway

**Status:** Internt arbetsdokument / Ej för distribution / Endast personligt bruk
**Språk:** Svenska (akademisk och teknisk register)
**Datum:** 2026-08-05

---

## 1. Sammanfattning

Ekosystemet består av tre aktörer: en **operatörsenhet** (smartphone inom räckhåll),
en **reläserver (VPS)** och en **enhet placerad i Sverige** (den svenska enheten).
Systemets huvuduppgift är att låta ägaren använda svenska tjänster (Swedbank, BankID
och liknande) från operatörsenheten, medan signering och nätverkstrafik fysiskt utförs
från enheten i Sverige. Lösningen bygger på tre kompletterande kanaler:

1. **Deeplink-gateway** — BankID-diplänkar fångas upp på operatörsenheten och
   vidarebefordras till den svenska enheten, där BankID öppnas och säkerhetskoden
   matas in automatiskt av enheten själv.
2. **TCP-tunnel** — operatörsenhetens applikationstrafik (Swedbank-appen) går ut på
   internet via den svenska enhetens IP-adress.
3. **Egen ADB-kanal** — en i applikationen inbäddad ADB-klient (TLS-parning mot lokal
   `adbd`) ger enheten ett skal med UID 2000 utan externa beroenden (varken Shizuku,
   dator eller USB krävs).

Samtliga delar har verifierats i drift: fullständig inloggning i Swedbank med konton
synliga, mätning av tunnelns utgångs-IP, samt självläkande efter omstart av enheten
utan någon manuell åtgärd.

---

## 2. Systemöversikt

```
┌────────────────────────┐         ┌──────────────────────┐         ┌─────────────────────────┐
│  OPERATÖRSENHET        │         │  RELÄSERVER (VPS)    │         │  ENHET I SVERIGE        │
│  (Flutter-gateway,     │         │  (Node.js)           │         │  (se.pult.app)          │
│   com.bankid.bus)      │         │                      │         │                         │
│                        │ wss     │  /ws    signalering  │  wss    │  PultService            │
│  Swedbank-appen ───────┼────────►│  /link  deeplink     │────────►│  BankIdAgent            │
│  bankid:// avlyssnas   │ deeplink│  /agent agentkanal   │ deeplink│  → öppnar BankID        │
│  CONNECT-proxy 8877 ───┼────────►│  /tunnel TCP-relä    │────────►│  → PIN via scrcpy       │
│  WebView lowlat ───────┼────────►│  /lowlat H.264-relä  │────────►│  → TunnelEgress         │
│                        │         │                      │  TLS    │  → AdbShell (egen ADB)  │
└────────────────────────┘         └──────────────────────┘         └─────────────────────────┘
```

Designprinciperna är oförändrade från den ursprungliga arkitekturen: servern är
»dum« (endast signalering och relä, inga parhemligheter), E2E-kryptering för
skärmvisning (WebRTC DTLS-SRTP respektive TLS för ADB-kanalen), och
minimering av manuella moment till exakt noll i driftläget.

---

## 3. Komponenterna i detalj

### 3.1. Operatörsappen (Flutter, paketnamn `com.bankid.bus`)

Operatörsappen är det synliga gränssnittet för ägaren. Dess paketnamn är av
arkitekturskäl `com.bankid.bus` (version 7.47.0), eftersom Swedbank kontrollerar
närvaron av BankID-applikationen genom paketnamnet och adresserar deeplänken direkt
till det paketet. En verklig BankID-installation på operatörsenheten är varken
nödvändig eller önskvärd — signeringen sker i Sverige.

**Funktioner:**

- **Avlyssning av deeplänkar.** Aktivitet-alias `com.bankid.bus.activities.InitActivity`
  med intent-filter för `bankid://` och `https://app.bankid.com` (Swedbank skickar
  https-varianten, `BankIdAppUtil`). Länken normaliseras till `bankid:///` och skickas
  över kanalen `/link`. Domänen `app.bankid.com` är registrerad som standardhanterare
  (`pm set-app-links-user-selection`).
- **Lokal CONNECT-proxy** (`127.0.0.1:8877`). Systemnära HTTP-CONNECT-proxy som
  multiplexar TCP-flöden över WebSocket `/tunnel`. En strömbrytare i UI:t skriver
  den globala proxyinställningen (`Settings.Global.HTTP_PROXY`) via
  `WRITE_SECURE_SETTINGS` — hela enhetens trafik kan slås av/på med en knapptryckning.
- **Skärmsändning (screencast).** Inbäddad WebView mot `/panel/lowlat.html`
  (H.264 → WebCodecs). Taps och swipes skickas tillbaka över samma WebSocket.
- **Foreground service.** Motverkar Samsungs Freecess-frysning av bakgrundsprocessen,
  som annars bröt WebSocket-kanalen efter cirka 8 sekunder.
- **Statusflöde.** Kanalstatus, signeringsstadier (skickad → öppnad → signerad/misslyckad)
  och tunnelns aktivitetsmätare visas i realtid.

### 3.2. Enhetsappen (`se.pult.app`, Android/Kotlin)

Enhetsappen är en permanent förgrundstjänst (PultService) med flera samverkande delar:

- **`BankIdAgent`** — fullföljer BankID-inloggningen lokalt: låser upp skärmen
  (lösenkod från app-inställningarna), öppnar deeplänken via `am start` (kringgår
  Android 14:s BAL-restriktion), läser UI-texter via `uiautomator`, matar in
  säkerhetskoden genom touch-injektion i scrcpy-serverns kontrollsocket
  (samma nivå som fysisk beröring; BankID accepterar inte accessibility-gester
  eller `input tap`).
- **`AdbShell`** — egen ADB-klient (biblioteket libadb-android). Genererar och lagrar
  ett eget RSA-nyckelpar i appens privata katalog, genomför TLS-parning mot lokal
  `adbd` en enda gång, och kör därefter godtyckliga skalkommandon som UID 2000.
  Detta ersätter fullständigt Shizuku och den tidigare persistenta LanAgent-processen.
- **`TunnelEgress`** — utgående ände av TCP-tunneln: öppnar målsocklar mot externa
  värdar och multiplexar byteströmmar tillbaka över WebSocket.
- **`BootBridgeService`** — startbrygga med FGS-typen `remoteMessaging`, den enda
  typ som tillåts från `BOOT_COMPLETED` på Android 15 (både `mediaProjection` och
  `dataSync` är förbjudna där). Den startar huvudtjänsten från ett lagligt
  förgrundstillstånd och avslutar sig själv.
- **Lowlat-sändaren** (`LowLatencyStreamer`) — skärmdump via MediaProjection →
  H.264-baseline (MediaCodec) → annex-b-ramar över WebSocket `/lowlat`.
  Innehåller dropplogik vid backpressure samt automatisk återanslutning.
- **Övriga mekanismer:** parprotokoll med HMAC-autentisering, sessionskontroll,
  MediaProjection-hantering, FCM-registrering för uppvakning, ScenarioRecorder/
  RemoteControlService (accessibility) för elementbaserad styrning, samt
  ForegroundAppWatcher som släcker skärmbilden vid bankappar (FLAG_SECURE-respekt).

### 3.3. Reläservern (VPS, Node.js)

Servern exponerar fem isolerade kanaler på samma HTTP(S)-lyssnare (Caddy terminerar
TLS med Let's Encrypt-certifikat):

| Kanal | Protokoll | Funktion |
|---|---|---|
| `/ws` | JSON-signalering | Par-konton, hjälpförfrågningar, samtycke, SDP/ICE-relä, återkallelse |
| `/link` | JSON, token | Deeplink-vidarebefordran (app → telefon) och status tillbaka; även `screencast`-styrsignaler |
| `/agent` | JSON, token | Kommandokanal för den lokala Mac-bryggan (befintlig utvecklingskedja) |
| `/lowlat` | Binärrelä | H.264-ramar enhet ⇄ panel; keyframe-cache för omedelbar bild; backpressure-dropp |
| `/tunnel` | Binärrelä | Multiplexade TCP-flöden (`[streamId:4][op:1][payload]`, op: open/data/close/error) |

Alla tokenkanaler autentiseras mot `AGENT_TOKEN`. Serverloggen saniteras till
ett fastställt fältregister (inga användardata, endast driftmetadata).

---

## 4. Kärnmekanismer

### 4.1. Deeplink-gateway — signering på distans

Flödet steg för steg (verifierat med riktiga autostarttoken):

1. Användaren trycker »Logga in« i Swedbank på operatörsenheten.
2. Swedbank kontrollerar närvaron av `com.bankid.bus` (vår shim finns) och skickar
   `VIEW https://app.bankid.com/?autostarttoken=<uuid>&redirect=null`.
3. Shimen fångar länken, normaliserar till `bankid:///…` och skickar den över `/link`
   till servern, som reläer vidare till den svenska enhetens signaleringsuttag.
4. Enheten öppnar BankID via `am start` (shell-UID kringgår BAL), status `opened`
   returneras.
5. `BankIdAgent.complete` fullföljer: upplåsning → eventuell bekräftelseskärm →
   PIN-siffror med människoliknande tidsjitter → »Identifiera« → inväntar att BankID
   stängs → status `signed`.
6. Swedbank på operatörsenheten pollar sitt eget API och visar kontona.

Total tid för hela kedjan (knapptryck → signerat): cirka 25–35 sekunder, dominerat
av avsiktliga människoliknande pauser i PIN-inmatningen. Själva nätverkssteget
(deeplink) är under sekunden.

### 4.2. TCP-tunneln — svensk utgångs-IP

Systemproxyn på operatörsenheten pekar på `127.0.0.1:8877`. Varje `CONNECT host:port`
blir en ström över `/tunnel`; den svenska enheten öppnar målsocketen och svarar.
Verifierat med `api.ipify.org` (utgångs-IP = enhetens IP) och med riktig trafik:
`auth.api.swedbank.se:443` och `content.swedbank.se:443` passerade tunneln med
flera kilobyte data i vardera riktningen.

Tre icke-triviala fel åtgärdades vid införandet: dubbel `Socket.listen` (single-subscription)
som dödade strömmar efter 200-svaret, kapplöpning där tidiga data-ramar tappades innan
TCP-connect slutförts (löst med väntekö per ström), samt RST-kapplöpning vid stängning
(`destroy()` direkt efter `add()` — ersatt av flush + graceful FIN).

### 4.3. Egen ADB-kanal — autonomi utan Shizuku

Tidigare design krävde Shizuku med manuell parning via meddelandeshyllan — skört
på MIUI. Nu bär applikationen hela kedjan själv:

- **Parning:** `AdbShell.pair(port, kod)` — SPAKE2/TLS mot `adbd`. Nyckelparet
  (2048-bitars RSA, självsignerat X.509) skapas vid första start och ligger i
  `filesDir`. Parningen överlever omstarter och djup urladdning.
- **Automatisk parning (`autoPair`)** — helt utan manuell inmatning: applikationen
  öppnar utvecklarinställningarna, rullar listan med korta långsamma accessibility-
  dragningsrörelser (800 ms — snabba flings ignoreras av MIUI), slår på
  »Trådlös felsökning«-brytaren, öppnar parkopplingsdialogen, **läser sjusiffrig kod
  och port från skärmen** via sin egen accessibility-tjänst och parar sig.
  Tre fallgropar åtgärdades: dialogen är ett separat fönster (dumpsökningen täcker nu
  alla fönster), `ACTION_SCROLL_FORWARD` hoppar förbi 1,5 skärm (därför små steg med
  dump efter varje), samt dubbeltap för att dämpa listans tröghet.
- **Exekvering:** `openStream("shell:…")` — kommandon som UID 2000, inklusive
  `am start`, `uiautomator dump`, `dumpsys`, samt start av scrcpy-servern.
- **Fjärkontrollskommandon utanför appen:** broadcast `se.pult.app.PAIR`
  (med eller utan argument) hanteras av förgrundstjänsten — TLS-handskakningen
  ryms inte i broadcast-fönstret.

### 4.4. Autonomi efter omstart

Kedjan efter daglig strömtimer-omstart (brandkyndig placering) utan någon människa:

1. `BootReceiver` → `BootBridgeService` (remoteMessaging — laglig FGS-typ från boot).
2. `PultService` startar, aktiverar `adb_wifi_enabled=1` via `WRITE_SECURE_SETTINGS`.
3. Signaleringsanslutning till VPS etableras (auto-reconnect), tunneln ansluter.
4. Vid första kommandot: `AdbShell` återansluter mot `adbd` med den lagrade nyckeln.
5. `BankIdAgent` respawnar scrcpy-servern vid behov och fullföljer flödet.

MIUI-specifika tillägg: autostart-AppOps (`MIUIOP 10008 allow`), battery-whitelist,
samt `PROJECT_MEDIA allow` för dialogfri skärminspelning.

### 4.5. Skärmsändning och låg latens

Prototypen `/lowlat` (H.264 över WebSocket → WebCodecs) byggdes för interaktiv
styrning. Identifierat grundfel: total avsaknad av backpressure-hantering på tre noder
(telefonens OkHttp-kö, reläets utgångsbuffer, webbläsarens avkodarkö) — fördröjningen
växte obegränsat. Åtgärd: delta-ramar kasseras till nästa keyframe (≤ 1 s) när
buffertvärden överskrids, på samtliga tre noder. Därtill: keyframe-cache på reläet,
så att en panel som ansluter mitt i en statisk skärm får bild inom ~0,4 s i stället
för oändlig väntan. Enheten rekonstruerar sin WebSocket automatiskt vid reläomstarter.

### 4.6. Signalprotokoll och kryptografi

Oförändrat från arkitekturen: `pairId`/`pairSecret`, HMAC-SHA256-transkription
över sessionens nonces och DTLS-fingeravtryck, servern lagrar endast
metadatajournaler (sessionId, tider, resultat) med 90 dagars rotation.
Meddelandetyperna har utökats med `deeplink`, `deeplink-status` och `screencast`
(servern validerar `bankid:///`-prefix och fältlängder).

---

## 5. Sekundära mekanismer

- **Accessibility-tjänsten (RemoteControlService):** elementbaserad styrning
  (fokussteg/aktivering), UI-träd som JSON för panelen, scroll via
  `ACTION_SCROLL_FORWARD/BACKWARD`, samt läsning av systemdialoger för autoPair.
  Tjänsten deaktiveras automatiskt vid BankID (dess profilkrav) och återaktiveras efter.
- **Touch-injektion i tre nivåer:** scrcpy-server (fungerar i BankID), `input`-skal
  (systemfönster, låsskärm), accessibility-gester (övriga appar).
- **Verktygsskript:** `setup-device.mjs` (en kommando = fullständig enhetsdrift:
  installation, rättigheter, a11y, autostart, autoPair), `phone-push-apk.mjs`
  (installation av APK utan USB via base64-chunkar över LanAgent/LAN),
  `lowlat-start.mjs` (sändningsstart med consent-automatik),
  `vps-agent-bridge.mjs` och övriga bevarade utvecklingsbryggor.
- **Panelsystemet (web):** fullfjädrad operatörspanel med lektioner (undervisningsslides),
  scenariorecorder, BankID-emulator, ui-trädvy, redact-hantering och
  WebRTC-ruttlogg (direct/relay).

---

## 6. Verifiering och testresultat

| Test | Resultat |
|---|---|
| Enhetstester server (Node) | 54/54 godkända |
| Enhetstester panel (kryptovektorer) | 9/9 godkända |
| Deeplink med falsk token | korrekt felhantering och status åter |
| Fullständig Swedbank-inloggning | **Konton synliga** (Privatkonto, transaktioner) |
| Tunnelns utgångs-IP | = enhetens IP (185.222.239.219 vid testtillfället) |
| Självparning (autoPair) | `true (paired)` utan manuell inmatning |
| Omstartstest (2×) | apparaten själv uppe, `hello` + `tunnel: join`, deeplink öppnar BankID |
| Lowlat backpressure | dropp-loggning bekräftad, latensen växer inte längre |

---

## 7. Kända begränsningar och risker

- **BankID »secure start«** (animerad QR): om Swedbank aktiverar det tvångsvis
  bryts deeplink-flödet. Reservväg: vidarebefordran av QR-bildrutor (redan
  förberedd teknik i lowlat).
- **Proxyrespekt:** Swedbank följer systemproxyn (verifierat); andra appar kan
  ignorera den. Plan B: per-app VPN (VpnService) på operatörsenheten.
- **MIUI-egenheter:** installationsdialogen för USB kräver manuell bekräftelse vid
  första sidoinstallationen; Freecess kräver förgrundstjänst och whitelists.
- **Begränsningar i CONNECT-proxyn:** endast CONNECT (https); inga ACL-regler —
  hela enheten proxieras när brytaren är på.

---

## 8. Bilaga: driftskommandon

```bash
# Fullständig enhetsdrift (APK, rättigheter, a11y, autostart, autoPair) — ett kommando
node scripts/setup-device.mjs phone <serial> --bankid-pin <PIN> --lock-pin <KOD>
node scripts/setup-device.mjs gateway <serial> --reinstall

# Självparning direkt på enheten (utan argument = helautomatisk)
adb shell am broadcast -a se.pult.app.PAIR -p se.pult.app

# APK-distribution
https://89-127-235-17.sslip.io/panel/gateway.apk   (operatörsapp)
https://89-127-235-17.sslip.io/panel/pult-demo.apk (enhetsapp)

# Strömbrytare för global proxy (om manuell styrning behövs)
settings put global http_proxy 127.0.0.1:8877   # på
settings put global http_proxy :0               # av
```

---

*Rapporten beskriver systemet i dess verifierade tillstånd per ovanstående datum.
Arkitekturdokumentet finns i samma katalog (readme.md, svensk version readme.sv.md).*
