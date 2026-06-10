# Build self-hosted pe git.tcx.ro (Gitea Actions)

Scop: să compilezi `nozero-app` pe propriul tău Gitea, nu doar pe GitHub. APK-ul rezultă ca **artefact**
în pagina run-ului (Actions → run → Artifacts).

> **Nu trebuie să instalezi manual Android SDK** dacă folosești un runner pe **Docker**: acțiunea
> `android-actions/setup-android` descarcă SDK-ul la fiecare build (exact ca pe GitHub). „Instalarea SDK"
> manuală e necesară doar dacă vrei un runner **pe gazdă** (vezi Varianta B).

## 1. Activează Actions pe Gitea
În `app.ini` (config-ul Gitea), adaugă/verifică:
```ini
[actions]
ENABLED = true
```
Restart Gitea. Apoi pe repo: **Settings → Actions → Enable**.

## 2. Instalează un runner (Varianta A — Docker, recomandat)
Pe un host cu **Docker** (poate fi chiar serverul Gitea sau altul cu acces la net):
```bash
# 1. ia binarul act_runner (verifică ultima versiune pe gitea.com/gitea/act_runner/releases)
wget -O act_runner https://gitea.com/gitea/act_runner/releases/download/v0.2.11/act_runner-0.2.11-linux-amd64
chmod +x act_runner

# 2. ia un RUNNER TOKEN din Gitea: Site Administration → Actions → Runners → Create new runner
#    (sau pe repo: Settings → Actions → Runners). Copiază tokenul.

# 3. înregistrează runnerul cu eticheta ubuntu-latest mapată la o imagine Docker cu node+tools
./act_runner register --no-interactive \
  --instance https://git.tcx.ro \
  --token <RUNNER_TOKEN> \
  --name nozero-builder \
  --labels "ubuntu-latest:docker://catthehacker/ubuntu:act-latest"

# 4. pornește-l (sau fă-l serviciu systemd)
./act_runner daemon
```
Cerințe: **Docker** instalat pe host + acces la internet (descarcă imaginea + acțiunile de pe github.com +
SDK-ul Android). Imaginea `catthehacker/ubuntu:act-latest` are deja node/curl/unzip etc.

## 3. Push → build
Cu runnerul pornit, fiecare `push` pe `main` sau tag `v*` declanșează `.gitea/workflows/build.yml`.
Vezi progresul în tab-ul **Actions** al repo-ului; descarci `nozero-<ver>.apk` din **Artifacts**.

## Varianta B — runner pe gazdă cu SDK pre-instalat (fără Docker)
Dacă preferi SDK instalat pe server (cum ai zis):
```bash
# JDK 17
apt-get install -y openjdk-17-jdk
# cmdline-tools Android
mkdir -p /opt/android-sdk/cmdline-tools && cd /opt/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
export ANDROID_HOME=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0"
# Gradle 8.9
wget https://services.gradle.org/distributions/gradle-8.9-bin.zip && unzip gradle-8.9-bin.zip -d /opt
export PATH=$PATH:/opt/gradle-8.9/bin
```
Apoi înregistrează act_runner cu o etichetă **host** (`--labels "host:host"`) și scoate din workflow pașii
`setup-java`/`setup-android`/`setup-gradle` (folosești toolurile gazdei). Mai fragil — recomand Varianta A.

## Notă
- **GitHub Actions** (`.github/workflows/android.yml`) rămâne calea principală + creează Releases la tag.
- **Gitea Actions** (acest workflow) = build paralel pe infra ta; APK ca artefact (Gitea n-are GitHub Releases).
- Ambele folosesc același `app/build.gradle` + keystore → APK identic.
