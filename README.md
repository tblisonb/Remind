# Remind

Tasks with optional notes and (recurring) reminders: an Android app, plus a self-hosted server
with a web UI for the desktop.

| Module | What |
| --- | --- |
| `app/` | Android app: Kotlin + Jetpack Compose, Room, `AlarmManager` for exact reminders |
| `shared/` | Plain Kotlin used by both: recurrence math, repeat labels, sync data types |
| `server/` | Ktor server: SQLite, sync API, server-rendered web UI |
| `desktop/` | Desktop reminders: a small background service showing native Plasma notifications |
| `deploy/` | Hardened systemd unit and install script |

## Toolchain (no Android Studio needed)

Everything lives under `~/Android`:

| Path | What |
| --- | --- |
| `~/Android/jdk-21` | Temurin JDK 21 (Gradle/AGP need JDK 17+) |
| `~/Android/Sdk` | Android SDK: `cmdline-tools`, `platform-tools` (adb), `platforms;android-37.0`, build-tools |

Add to `~/.bashrc`:

```sh
export JAVA_HOME="$HOME/Android/jdk-21"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"
```

`local.properties` (git-ignored) points Gradle at the SDK. Gradle itself is fetched by `./gradlew`.
Manage SDK packages with `sdkmanager --list_installed`, `sdkmanager --update`, etc.

## Everyday commands

```sh
./gradlew installDebug                     # build + install on the connected phone
adb shell am start -n com.tlisonbee.remind/.ui.MainActivity
./gradlew test                             # unit tests (shared recurrence logic, server)
./gradlew lintDebug                        # Android lint -> app/build/reports/lint-results-debug.html
./gradlew assembleRelease                  # minified release APK (needs signing config, see below)

adb logcat --pid="$(adb shell pidof com.tlisonbee.remind)"   # app logs
adb shell dumpsys alarm | grep -A4 com.tlisonbee.remind      # scheduled alarms
```

`./gradlew --continuous installDebug` rebuilds and reinstalls on every save.

### Testing reminders

- Set a reminder 1–2 minutes out, lock the phone, and wait.
- `adb shell am force-stop com.tlisonbee.remind` wipes the app's alarms (an Android rule);
  opening the app again re-arms them.
- `adb reboot` checks that alarms are restored after boot.

## Layout

```
app/src/main/java/com/tlisonbee/remind/
  RemindApp.kt              Application: wires DB, scheduler, notifier (manual DI)
  data/                     Task entity, Room DAO/database, TaskRepository (all mutations)
  reminders/                Recurrence math, AlarmManager scheduling, notifications, receivers
  ui/                       MainActivity, list + edit screens (Compose), ViewModel
app/schemas/                Exported Room schemas (commit these; needed for migrations)
shared/src/main/kotlin/…    Recurrence, RepeatUnit, TaskDto / SyncRequest / SyncResponse
server/src/main/kotlin/…    Main (config), Application (plugins, auth, API), Web (HTML pages), TaskStore (SQLite)
```

## How reminders work

- `remindAt` is the first occurrence (the "anchor"), stored as local wall-clock time. Recurrences
  are always computed from the anchor, so monthly on the 31st clamps to Feb 28/29 and then returns
  to the 31st, and "9:00 daily" stays at 9:00 across DST.
- `nextReminderAt` is the one alarm currently armed per task. When it fires, the notification is
  shown and the next occurrence is scheduled.
- Notification actions: **Done** (completes a one-time task; for a recurring task it just dismisses
  this occurrence) and **Snooze 1h**.
- Alarms are re-armed on boot, app update, time/time zone change and app launch.
- Marking a task done in the list stops its reminders (including recurring ones).

## Sync

In the app: **⚙ (top right) → Sync**, enter the server URL (e.g. `https://<pc>.<tailnet>.ts.net`) and
the access token (`sudo cat /etc/remind/token`), then **Connect**. The first sync uploads every
existing task.

- The app syncs on launch, a few seconds after any edit, every 15 minutes in the background
  (when online), and on pull-to-refresh in the task list.
- Each task carries `updatedAt`; when the same task was changed in two places, the newer edit wins.
- Reminder alarms stay on the phone: synced changes just re-arm them, so reminders fire with or
  without the server.
- HTTPS is required. Debug builds additionally allow `http://localhost:8765`, for testing against a
  dev server on the PC over USB: `adb reverse tcp:8765 tcp:8765`.
- The token is stored in app-private storage and excluded from Android backups.
- If syncing keeps failing for 12 hours (e.g. Tailscale stopped), the app posts one quiet notification
  ("Not synced since …", on its own low-importance channel); it clears itself after the next
  successful sync. Reminders keep working either way.
- **LineageOS:** if *restricted networking mode* is on, new apps get no network at all and sync fails
  with "connection refused". Allow it under **App info → Remind → Mobile data usage → Network access**.

## Desktop reminders (KDE Plasma)

`remind-desktop` runs as a systemd user service with your Plasma session. It keeps a local copy of
your tasks (synced every minute) and shows reminders as native notifications with **Done** and
**Snooze 1h**; clicking the notification opens the task in the web UI. Reminders fire from the local
copy, so they work while the server or network is down (e.g. a laptop away from home).

Install or update from source on this machine (no sudo; uses the system Java 17+ runtime):

```sh
./gradlew :desktop:installDist && desktop/build/install/remind-desktop/install.sh
```

First-time setup (asks for the server URL and access token, checks the connection, then starts it):

```sh
remind-desktop setup
systemctl --user start remind-desktop
```

As one paste:

```sh
remind-desktop setup && systemctl --user start remind-desktop
```

Other commands: `remind-desktop status` (upcoming reminders), `remind-desktop open` (web UI),
`remind-desktop test-notification` (checks notifications and buttons), and
`journalctl --user -u remind-desktop -f` for logs.

- Reminder notifications stay on screen until you click **Done**/**Snooze** or dismiss them: once a
  Plasma popup times out, its buttons stop working, which would defeat the point.
- **Done** completes a one-time task everywhere; on a recurring task it just dismisses this
  occurrence. Snooze is per device. You'll get each reminder on the phone and each desktop.
- Config lives in `~/.config/remind/` (token file is owner-only), the task cache in
  `~/.local/state/remind/`. The service is sandboxed: read-only home except that cache directory.
- The background process uses roughly 70–90 MB of memory.

### Other Plasma machines (self-contained package)

`./gradlew :desktop:bundledDist` builds
`desktop/build/distributions/remind-desktop-<version>-linux-x86_64.tar.gz` (~30 MB), which bundles
a trimmed Java runtime, so the target machine needs nothing installed beyond Plasma and systemd
(any distro, e.g. Arch). Publish it as a GitHub release with `deploy/release-desktop.sh` (bump
`version` in `desktop/build.gradle.kts` first).

On another machine, with `gh` logged in, install or update with:

```sh
gh release download desktop-v0.1.0 --repo tblisonb/Remind --pattern '*.tar.gz*'
sha256sum -c remind-desktop-0.1.0-linux-x86_64.tar.gz.sha256
tar xzf remind-desktop-0.1.0-linux-x86_64.tar.gz
remind-desktop-0.1.0-linux-x86_64/install.sh
```

As one paste:

```sh
gh release download desktop-v0.1.0 --repo tblisonb/Remind --pattern '*.tar.gz*' && sha256sum -c remind-desktop-0.1.0-linux-x86_64.tar.gz.sha256 && tar xzf remind-desktop-0.1.0-linux-x86_64.tar.gz && remind-desktop-0.1.0-linux-x86_64/install.sh
```

On a first install, `install.sh` asks for the server URL and token and starts the service; on later
runs it updates in place and restarts it. (Or copy the tarball over with `scp` instead of `gh`.)
The machine needs to be on your tailnet to reach the server.

## Release builds

Debug builds are signed with an auto-generated debug key, which is fine for a personal install.
For a proper release APK, create a keystore (`keytool -genkeypair -v -keystore remind.jks
-keyalg RSA -keysize 4096 -validity 10000 -alias remind`) and add a `signingConfigs` block to
`app/build.gradle.kts`. Note that switching signing keys requires uninstalling the debug build.

## Server

### Running it locally while developing

```sh
openssl rand -base64 32 > /tmp/remind-token
REMIND_TOKEN_FILE=/tmp/remind-token ./gradlew :server:run     # http://127.0.0.1:8765, DB in server/remind.db
```

Configuration is all environment variables: `REMIND_TOKEN_FILE` (or `REMIND_TOKEN`, ≥ 32 chars),
`REMIND_HOST` (default `127.0.0.1`), `REMIND_PORT` (default `8765`), `REMIND_DB` (default `remind.db`).

### API

`POST /api/sync` with `Authorization: Bearer <token>`:

```json
{ "since": 0, "changes": [ { "id": "<uuid>", "title": "…", "updatedAt": 1760400000000, "createdAt": 1760400000000, … } ] }
```

The server stores each pushed task unless it already has a newer `updatedAt`, then returns every
task it accepted after `since`, plus a new `cursor` to send next time. Deletes are `deleted: true`
tombstones. Field definitions live in `shared/…/Sync.kt`.

### Installing as a service

One-time prerequisites:

```sh
sudo apt install openjdk-21-jre-headless
```

Install or update (re-run after every change):

```sh
./gradlew :server:installDist && sudo deploy/install.sh
sudo cat /etc/remind/token        # the access token for the web login and the app
journalctl -u remind -f           # logs
```

First install as one paste (Java runtime, build, install, show the token):

```sh
sudo apt install -y openjdk-21-jre-headless && ./gradlew :server:installDist && sudo deploy/install.sh && sudo cat /etc/remind/token
```

This puts the program in `/opt/remind` (root-owned), the database in `/var/lib/remind`, and the token
in `/etc/remind/token` (root-only, generated on first install). The service runs as an unprivileged
throwaway user (`DynamicUser`) with no access to home directories, a read-only filesystem except its
database directory, a syscall filter, and a network restriction allowing only loopback traffic.
Check the sandbox score with `systemd-analyze security remind`.

Back up `/var/lib/private/remind/remind.db` (that is where `DynamicUser` keeps state).

### Remote access with Tailscale

The server only listens on `127.0.0.1`, so nothing on your LAN or the internet can reach it, and no
router or firewalld port needs opening. Tailscale makes it reachable from your own devices only:

1. Install Tailscale on the PC (Debian 13):
   ```sh
   cd "$(mktemp -d)"
   curl -fsSLO https://pkgs.tailscale.com/stable/debian/trixie.noarmor.gpg
   curl -fsSLO https://pkgs.tailscale.com/stable/debian/trixie.tailscale-keyring.list
   sudo install -m 644 trixie.noarmor.gpg /usr/share/keyrings/tailscale-archive-keyring.gpg
   sudo install -m 644 trixie.tailscale-keyring.list /etc/apt/sources.list.d/tailscale.list
   sudo apt update && sudo apt install tailscale
   sudo tailscale up
   ```
   As one paste:
   ```sh
   cd "$(mktemp -d)" && curl -fsSLO https://pkgs.tailscale.com/stable/debian/trixie.noarmor.gpg && curl -fsSLO https://pkgs.tailscale.com/stable/debian/trixie.tailscale-keyring.list && sudo install -m 644 trixie.noarmor.gpg /usr/share/keyrings/tailscale-archive-keyring.gpg && sudo install -m 644 trixie.tailscale-keyring.list /etc/apt/sources.list.d/tailscale.list && sudo apt update && sudo apt install -y tailscale && sudo tailscale up
   ```
2. Install Tailscale on the phone (F-Droid or Play Store) and sign in to the same account.
3. In the Tailscale admin console under **DNS**, enable MagicDNS and HTTPS certificates.
4. Proxy the server onto your tailnet with HTTPS:
   ```sh
   sudo tailscale serve --bg http://127.0.0.1:8765
   tailscale serve status            # shows the https://<pc>.<tailnet>.ts.net URL
   ```
   Use `serve`, never `funnel`: funnel publishes to the whole internet.

Tailscale connections are outbound-only and WireGuard-encrypted; only devices signed in to your
tailnet can connect, and the access token is still required on top of that.
