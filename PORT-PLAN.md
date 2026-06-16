# Portare app vechi (BioEcho / sensor-audio-gps) → app nou (No Zero / nozero-app)

**Scop:** paritate completă de funcții în app-ul nou (`ro.noze.app`), păstrând login-ul nou
(Google via web deep-link + QR senzor) + harta web `/app`.

**Cheia care simplifică tot:** ambele app-uri au **același pachet Kotlin `com.example.logger`** →
copiem + adaptăm fișierele, nu rescriem. `applicationId` rămâne `ro.noze.app` (rebrand).

**Constrângere:** Claude poate scrie + builda (GitHub Actions) dar **NU poate testa pe telefon** →
fiecare fază = un release buildabil pe care îl testează Sebastian. Build-ul Actions = banca de test
(iterăm până e verde). Release-urile vechi rămân plasă de siguranță (main poate fi roșu temporar).

## Strategia de fuziune
- **Home = hub-ul nativ vechi** (`MainActivity` din app-ul vechi — butoanele de teren: Transect /
  Senzor fix / Înregistrări / Ascultă live / Busolă / Găsește senzor / Diagnostic).
- **Login = cel nou (web)**: hub-ul, dacă nu există token, deschide `WebActivity` la `/device-login`
  (Google deep-link `nozero://auth` SAU scanare QR senzor). Vechiul `GoogleLogin.kt` nativ NU se
  folosește (ar cere reînregistrare `ro.noze.app` în Google Cloud). Tokenul (`nozero_token`) se
  extrage din cookie-ul WebView → prefs → `UploadManager`/`CommandClient` îl folosesc ca Bearer.
- **Harta web** = `WebActivity` (actualul WebView shell din nozero-app, redenumit din `MainActivity`),
  lansat dintr-un buton „🗺 Hartă" al hub-ului + ca ecran de login.

## Inventar fișiere vechi (24 .kt) + dispoziție
| fișier | rol | portare |
|---|---|---|
| RecordingService.kt | serviciu foreground înregistrare audio WAV (transect/fix) | **port** (nativ) |
| AudioSegmentRecorder.kt | scriere WAV segmentat | **port** |
| RecordWindow.kt | fereastră nocturnă răsărit/apus (offline) | **port** (self-contained) |
| Storage.kt | folder public /BioEcho (supraviețuiește dezinstalării) | **port** (self-contained) |
| UploadManager.kt | upload S3/recordings | **port** + rewire token (cookie→prefs) |
| AntiTheftMonitor.kt | anti-furt: mișcare/BLE/baterie/SIM/storage → /api/auth/mobile-alert | **port** |
| CommandClient.kt | C&C heartbeat (poll comenzi) | **port** + rewire token (device token) |
| BirdNetClassifier.kt | BirdNET TFLite on-device | **port** |
| LiveListenActivity.kt | „Ascultă live" (Merlin) | **port** |
| CompassActivity.kt | busolă + navigație | **port** |
| MapActivity.kt | hartă navigație (osmdroid) | **port** |
| SatelliteTiles.kt | tiles satelit | **port** |
| PoisListActivity.kt | POI list/navigate → /api/pois | **port** |
| FindSensorActivity.kt | BLE find-on-approach | **port** |
| RecordingsActivity.kt | management înregistrări | **port** |
| PlaybackActivity.kt | redare | **port** |
| DiagnosticActivity.kt | self-test | **port** |
| QrScanActivity.kt | scanner QR nativ | **port** (sau web; păstrat dacă FindSensor îl cere) |
| AppUpdater.kt + UpdateChecker.kt + InstallReceiver.kt | auto-update | **port** |
| BioEchoDeviceAdminReceiver.kt | device-owner | **port** |
| GoogleLogin.kt | login Google nativ | **SKIP** (înlocuit de web deep-link) |
| MainActivity.kt | hub nativ | **port** ca MainActivity (reconciliat cu login nou) |

## Faze (fiecare = build verde + release de testat)
1. **Base-swap + green:** copiez tot src/res/assets vechi în nozero-app; redenumesc WebView-ul nou →
   `WebActivity`; merge build.gradle (deps vechi + webkit; applicationId ro.noze.app; versionCode++)
   + manifest (activities/service/receivers/permisiuni vechi + WebActivity + deep-link nozero://auth +
   label „No Zero" + icon nou). Țintă: **compilează** (= app-ul vechi sub ro.noze.app + WebView prezent).
2. **Login nou cablat:** hub `btnAuth` → `WebActivity` `/device-login`; după login, extrage `nozero_token`
   din CookieManager → prefs `nz_token`. Buton „🗺 Hartă" → WebActivity `/app`. Scoate apelurile la
   `GoogleLogin` din MainActivity.
3. **Rewire auth uploads/C&C:** `UploadManager` + `CommandClient` citesc `nz_token`/device-token din
   prefs (nu din vechiul GoogleLogin). Verifică endpoint-urile (noze.ro: /api/recordings, /api/device/*,
   /api/auth/mobile-alert, /api/pois) — există deja.
4. **Curățenie:** scoate GoogleLogin.kt + credentials deps dacă nimic nu le mai cere; verifică
   versionCode > orice instalat; testează pe telefon (Sebastian).

## Stare (2026-06-11)
- [x] **Faza 1 (base-swap)** — BUILD VERDE. Toate cele 24 .kt + res + manifest + deps compilează sub
  `ro.noze.app` (versionCode 5 / 2.4.0). `MainActivity` = hub vechi; `WebActivity` = WebView nou.
- [x] **Faza 2 (login cablat)** — `showAuthMenu` opțiunea 0 „Conectare (Google / web)" → `WebActivity`
  `/device-login` (Google deep-link `nozero://auth` SAU scanare QR web). Meniu cont → buton „🗺 Hartă" →
  `WebActivity` `/app`. Opțiunile 1/3 (QR nativ → `device_token`) + 2 (token) rămân.
- [x] **Faza 3 (jwt_token aliniat)** — `WebActivity.handleAuthDeepLink` salvează `jwt_token` (cheia citită de
  `UploadManager`/`AntiTheftMonitor`/`MapActivity`/`verifyMobileAuth`) → upload + auth merg cu login-ul nou.
- [ ] **Faza 4 (curățenie + test)** — de făcut: scoate `GoogleLogin.kt` + `doGoogleLogin()` nefolosite +
  credentials deps; icon nou (acum = cel vechi BioEcho); **test pe teren (Sebastian)**.

**RELEASE v2.4.0** = primul cu paritate completă + login nou.

### Caveat-uri pt testul pe teren (Claude NU a putut testa pe telefon)
- **Login personal:** „Autentificare" → „Conectare (Google / web)" → Google (browser revine în app) sau QR.
- **Senzor + C&C:** „Autentificare" → „Provizionează ca senzor (scan QR)" (nativ) → setează `device_token`
  (nzdev_) — necesar pt heartbeat-ul C&C (login-ul web QR setează doar `jwt_token`, NU `device_token`).
- **Recording / anti-furt / live-listen / navigație** = cod vechi proven, dar reverifică pe teren.
- Icon = temporar cel vechi (BioEcho); de înlocuit în faza 4.
