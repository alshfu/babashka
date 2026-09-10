# Pult — A-app & B-app

**Status:** Privat sandbox / Ej för distribution / Endast ägarens eget bruk

Detta repo innehåller ett privat system för att styra ägarens egna enheter i Sverige
från ägarens operatörstelefon. Det är **inte** en produkt, publiceras inte och ger
aldrig tredje part åtkomst. Se `AGENTS.md` för projektets järnregler.

---

## 1. Terminologi — våra appar heter så här

| Namn | Vad | Paket | Källkod | Enhet |
|---|---|---|---|---|
| **A-app** | Enhetsappen som står kvar i Sverige | `se.pult.app` | `android/grandma/` (Kotlin) | Redmi |
| **B-app** | Operatörsappen som styr | `com.bankid.bus` | `app/` (Flutter) | Note 10 |
| **Server** | Relä/hub på VPS | — | `server/` (Node.js) | `85.190.98.57` |

- En **A-app** kan styras från en **B-app**.
- En **B-app** kan styra **flera A-appar**.

---

## 2. Arkitektur

```
B-app (Note 10, operatör)                Server (VPS, Node.js)          A-app (Redmi, Sverige)
─────────────────────────                ───────────────────────        ──────────────────────
Listar A-appar (namn/modell/OS/IP)       /ws      signalering           PultService (förgrundstjänst)
Fångar bankid://-dipplänkar   ──wss──►   /link    dipplänk-relä  ──wss─► BankIdAgent → BankID → PIN
CONNECT-proxy 127.0.0.1:8877  ──wss──►   /tunnel  TCP-relä       ──wss─► TunnelEgress → svensk IP
Skärmsändning (WebView)       ──wss──►   /lowlat  H.264-relä     ──wss─► LowLat-sändare
                                         /api/update  APK/dex-OTA
```

Servern är medvetet »dum«: den lagrar inga PIN-koder, inga parhemligheter och ser
inget innehåll — bara relä och metadatajournal (90 dagars rotation).

---

## 3. B-appen (operatörsapp, Note 10)

- **Sparar aldrig PIN-koder.** B-appen innehåller ingen PIN-hantering alls.
- **Enhetslista:** visar alla A-appar som är anslutna till servern —
  namn (frivilligt), modell, OS och IP, plus online-status.
- **Per vald enhet erbjuds:**
  - **Dipplänk-vidarebefordran** — Swedbank → »Logga in« → `bankid://` fångas av
    B-appen och skickas via servern till vald A-app, som öppnar BankID och anger
    koden lokalt. B-appen går omedelbart till bakgrunden (moveTaskToBack).
  - **Skicka all trafik via enheten** — en strömbrytare sätter systemproxyn till
    `127.0.0.1:8877` (CONNECT-proxy). All webbtrafik från Note 10 går då ut via
    A-appen, så Note 10 får **samma publika IP som A-appen** i Sverige.
    Knappen »Kontrollera publik IP« verifierar utgående IP.
  - **Skärmsändning** — låglatensvy av A-appens skärm med touch tillbaka.
- Foreground service (KeepAliveService) håller WebSocket-kanalen vid liv.

## 4. A-appen (enhetsapp, Redmi i Sverige)

A-appen är en **tjänst utan skärm** i driftläget, med exakt två skärmar:

1. **PIN-skärmen (`BankIdPinActivity`)** — ser ut 1:1 som BankIDs PIN-skärm.
   Texten välkomnar användaren att skriva in sin BankID-säkerhetskod så att
   Pult-appen kan använda den i framtiden. Koden sparas **endast här**, lokalt
   och krypterat (`PinStorage`), och lämnar aldrig enheten.
2. **Parnings-/statusskärmen (`PairingActivity`)** — koppling mot servern.

Övriga komponenter: `PultService` (/ws-agent + /tunnel), `BankIdAgent`
(PIN-scenariot), `LanShell`/`AdbShell` (skal åt sig själv), `RemoteControlService`
(accessibility — aldrig aktiv under BankID), `UpdateManager` (OTA via /api/update).

> **Gyllene regeln (AGENTS.md):** PIN-flödet (adb_wifi_enabled=0 → död adbd-session →
> raiseBankId → injectInputEvent via scrcpy-server) är empiriskt utprovat och får
> **inte ändras** utan nytt E2E-test av hela inloggningen.

---

## 5. Servern (VPS)

Adress: **`wss://85.190.98.57.sslip.io:8445`** (HTTPS/WSS via Caddy + Let's Encrypt).

| Kanal | Funktion |
|---|---|
| `/ws` | Signalering (par, närvaro, heartbeat) |
| `/link` | Dipplänk B-app → A-app + signeringsstatus tillbaka |
| `/tunnel` | Multiplexade TCP-flöden (B-appens webbtrafik ut via A-app) |
| `/lowlat` | H.264-skärmrelä |
| `/agent` | Kommandokanal (AGENT_TOKEN) |
| `/api/update` + `/update/<fil>` | OTA: ladda upp APK/dex, pusha `update-available` |
| `/panel/<fil>` | Statik: `lowlat.html`, APK-distribution (`a-app.apk`, `b-app.apk`) |

### APK-distribution

```
https://85.190.98.57.sslip.io:8445/panel/b-app.apk   (B-app, operatör)
https://85.190.98.57.sslip.io:8445/panel/a-app.apk   (A-app, enhet i Sverige)
```

### Driftstatus (2026-09-11)

- Båda APK:erna på VPS:en är byggda från senaste `main` (B-app med ny
  styr-UI: enhetslista → delad skärm/tunnel/publik IP; A-app med VPS-adress
  och token inbakade). SHA256 verifierat mot lokala byggen.
- **ANR-fix på Redmi (2026-09-11):** `onDeeplink` i PultService körs nu på
  bakgrundstråd (commit `6b8e945`). Orsak: `latch.await()` på main-tråden →
  «Pult svarar inte» i loop när bankid://-deeplink levererades från VPS-kön.
  Fixad APK (sha256 `83e3d004…c5`) installerad på Redmi via
  `phone-push-apk.mjs`; MIUI-rättigheter återställda (§6); inga nya ANR i
  dropbox efter 23:37. **BankID-PIN är inlagd i PinStorage** på Redmi.
- Skärmdump av Redmi på PC:en: öppna
  `https://85.190.98.57.sslip.io:8445/panel/lowlat.html?room=demo` i
  webbläsaren (ström startas från B-appens «Dela skärm»).
- **Note 10 kör nya B-appen** (installerad 2026-09-10, byter ut gamla
  gateway-APK:n; inställningar och PIN:er behölls). **E2E-test av
  BankID-inlogg krävs innan Swedbank används** — se §7.
- **Aktuell par på VPS:en:** pairId `Dl8YrhLu00VwOz62sQr4gw`
  (Redmi ↔ Note 10 + emulator). B-app-inställningar (⚙): server
  `wss://85.190.98.57.sslip.io:8445`, kanaltoken = AGENT_TOKEN (nedan).
- **AGENT_TOKEN** finns i `/etc/systemd/system/pult.service` på VPS:en
  (skrivs inte i repot). SSH: `administrator@85.190.98.57` (lösenord hos
  ägaren). Tjänsten: `systemctl status pult`, loggar `journalctl -u pult`.
- A-app byggs för VPS med miljövariabler:
  `SIGNALING_URL=wss://85.190.98.57.sslip.io:8445/ws TUNNEL_TOKEN=<AGENT_TOKEN> UPDATE_TOKEN=<AGENT_TOKEN> ./gradlew :grandma:assembleV2Debug`
- Installation på Redmi utan USB: `node scripts/phone-push-apk.mjs <apk> 192.168.3.111 se.pult.app`
  (via LanAgent, TCP 47201), därefter MIUI-rättigheterna i §6.
- **EJ GJORT:** E2E-test av BankID-inlogg efter ANR-fixen — allt är förberett
  (PIN lagrad, rättigheter satta, Swedbank öppen på Note 10 vid
  inloggningsskärmen) men testet kräver ägarens personnummer för att starta
  BankID-ordern. Swedbank → Logga in → saldo syns, se §7.

---

## 6. Drift

```bash
# Servern lokalt
cd server && npm install && npm start        # PORT=8080
cd server && npm test                        # enhetstester

# Bygg APK:er
cd android && ./gradlew :grandma:assembleRelease     # A-app
cd app && flutter build apk --release                # B-app

# Förstagångsinställning av en A-app-enhet (ett kommando)
node scripts/setup-device.mjs phone <serial> --bankid-pin <PIN> --lock-pin <KOD>

# OTA-uppdatering till A-appar (kräver AGENT_TOKEN)
curl -X PUT https://85.190.98.57.sslip.io:8445/api/update \
  -H 'x-agent-token: <AGENT_TOKEN>' -H 'x-update-kind: apk' \
  -H 'x-update-version: <version>' -H "x-sha256: <sha256>" --data-binary @a-app.apk
curl -X POST https://85.190.98.57.sslip.io:8445/api/update/notify \
  -H 'x-agent-token: <AGENT_TOKEN>' -H 'content-type: application/json' -d '{"kind":"apk"}'
```

MIUI-rättigheter på A-app-enheten (återställs vid `pm install -r` — utfärda igen):

```bash
appops set se.pult.app SYSTEM_ALERT_WINDOW allow
appops set se.pult.app 10021 allow   # starta aktivitet från bakgrunden
```

---

## 7. Obligatoriskt E2E-test efter ändringar nära inloggningen

Swedbank → Logga in → (gör inget) → Note 10 visar kontot.
Referensskärmar: `tools/success-1..3.png`, `tools/success-autonomous.png`.

Vidare detaljer: `docs/rapport.sv.md` (verifierad teknisk rapport).
