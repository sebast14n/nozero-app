# No Zero — app v2.0 (shell + module web)

Framework **slim** Android pentru No Zero (noze.ro): un **WebView** care încarcă **module web** servite
de `noze.ro/static/modules/<nume>/` și expune `window.NZBridge` cu capabilități native. „Aplicația nu știe
de lilieci / transecte" — modulul web aduce UI-ul + logica; nativul aduce doar ce browserul nu poate.

Repo separat de `sensor-audio-gps` (app-ul vechi, rămâne live). Package păstrat `com.example.logger` +
același keystore (`bioecho-debug.keystore`) → **același SHA-1** → OAuth Google merge fără re-înregistrare,
până terminăm testele.

## Faza 1 (aici) — keystone
Shell + **bridge v1 = `location` + `identity` + `module-loader`**. La pornire încarcă modulul **transect**
(`/static/modules/transect/index.html`) și-i dă **GPS nativ** prin bridge. Restul capabilităților
(audio/BLE/fundal) vin pe faze (vezi `docs-Sebastian/DESIGN-app-v2.md` → „RUTA v2.x").

**Bridge v1 (JS → nativ):**
| `window.NZBridge.*` | face |
|---|---|
| `watchLocation(cb)` / `stopLocation()` | GPS continuu (FusedLocation) → `cb({lat,lon,acc})` |
| `getLocation(ok, err)` | un fix GPS |
| `getIdentity()` | `{deviceId, token, email, native}` (token/email = null deocamdată — auth = Faza 1.5) |
| `loadModule(name)` | încarcă alt modul din noze.ro |
| `log(msg)` | Logcat (tag `NZBridge`) |

Implementare: `NZBridge.kt` (`@JavascriptInterface`, expus ca `window.NZAndroid`) + `assets/bridge.js`
(shim injectat la **document-start** care construiește `window.NZBridge` peste `NZAndroid`). Modulul web
folosește `window.NZBridge || API-browser`, deci **rulează și în browser** (GPS browser) **și în app** (GPS nativ).

## Build & test
- **Build:** push un tag `vX.Y.Z` (sau pe `main`) → GitHub Actions buildează `nozero-<ver>.apk` (release pe tag).
  Fără wrapper local — Actions folosește `gradle/actions/setup-gradle`. AGP 8.7.3, Kotlin 2.0.21, SDK 35, JDK 17.
- **Test pe telefon:** sideload APK-ul → la pornire cere permisiunea de locație → se încarcă transectul →
  „▶ Pornește" → **traseul GPS trebuie să se deseneze** (GPS nativ prin bridge). Apasă lung pe hartă =
  destinație; „📟 Senzor" = amplasare.
- **Debug WebView:** Chrome desktop → `chrome://inspect` (telefon pe USB, debugging on) → inspectezi modulul live.
- **Iterație rapidă:** modulul e pe noze.ro → editezi + `scp` → re-deschizi în app, **fără rebuild**. Doar
  schimbările de bridge/nativ cer build nou.

## Cunoscut în Faza 1 (intenționat, nu bug-uri)
- Sync-ul modulului (`/api/field/sync`) folosește cookie → în WebView (nelogat) va da 401. **GPS + randarea
  modulului** = ce testăm acum; legarea auth (identity → Bearer) = Faza 1.5.
- **`NZBridge.liveSpecies()` NU e implementat** — modulul transect îl apelează pentru sugestii BirdNET live,
  dar e gated, deci cade pe lista placeholder de 5 specii. Sugestiile native vin în **Faza 3** (BirdNET nativ).
- Service worker-ul PWA poate fi limitat în WebView — modulul tratează eșecul grațios.
- `window.NZBridge` e un getter lazy (rezolvă la acces dacă `NZAndroid` e prezent) → robust la ordinea de
  injectare pe diverse WebView-uri. Dacă `WebViewFeature.DOCUMENT_START_SCRIPT` lipsește pe un telefon vechi,
  shim-ul nu se injectează și modulul cade pe GPS-ul browser (degradare grațioasă).
