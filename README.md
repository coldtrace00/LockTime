# LockTime

**A simple, reliable and fully open-source app time limiter for Android.**
The interface is in English and Ukrainian (follows the phone's language).

Pick apps (Instagram, TikTok, YouTube…), set a daily limit — say, 1 hour — and
once it's used up, the app simply won't open until midnight.
No passwords, no "5 more minutes", no "ignore for today".

[**⬇ Download LockTime.apk**](https://github.com/coldtrace00/LockTime/releases/latest/download/LockTime.apk)

[Українська](README.uk.md) · **English**

---

## Principles

- **No internet.** The app has no `INTERNET` permission, so Android physically
  won't let it send anything. No accounts, no server, no ads, no analytics.
- **No third-party libraries.** Just the Android SDK and Kotlin. The whole code
  base is a handful of files you can actually read end to end.
- **Data stays on the phone.** Limits are stored on the device; cloud backup is
  disabled (`allowBackup="false"`).
- **Honest about limitations.** What can be bypassed is described [below](#honest-limitations).

## Features

| Feature | How it works |
|---|---|
| Daily limit | Per app. Quick buttons: 5, 10, 15, 30, 45 min, 1 h. Resets at midnight. |
| Weekend limit | Optionally a different limit for Saturday and Sunday. |
| Open limit | E.g. Instagram no more than 10 times a day. Fights the "just a quick look" habit. |
| Night mode | Apps marked "at night" are fully blocked during chosen hours (default 23:00–07:00). |
| Website blocking | When an app is blocked, its website in the browser (instagram.com, tiktok.com, youtube.com…) is closed too. |
| Blocking | Back to the home screen plus a screen explaining why. Checked every 3 s, so it kicks in even mid-scroll. |
| Warnings | Notifications 15, 10 and 5 min before the limit runs out, each once a day. |
| Statistics | How much time went into each app over the last 7 days. |
| "Tighten now, loosen tomorrow" | Applies to every setting: time limits, opens, night mode and its hours. |
| Uninstall protection | Optional. While on, LockTime can't be uninstalled, force-stopped, or have its service disabled. |
| Removing protection takes 72 h | After the request, the app shows the date and time protection will be lifted. Changing the clock doesn't help. |

## Installation

1. On the phone, open
   [LockTime.apk](https://github.com/coldtrace00/LockTime/releases/latest/download/LockTime.apk)
   and install it (allow installs from the browser; on a Play Protect warning
   choose "Install anyway").
2. Open LockTime and grant the permissions at the top of the screen:

   | Permission | Why |
   |---|---|
   | Usage access | To know how much time you spent in each app today |
   | Accessibility service | To notice which app is open and close it |
   | Notifications | For "15 / 10 / 5 min left" warnings |
   | No battery optimization | So the system doesn't put LockTime to sleep |

   > **Android 13+:** if the service switch is greyed out ("Restricted setting"),
   > go to Settings → Apps → LockTime → ⋮ (top right) →
   > **Allow restricted settings**, then turn the service on again.

3. Tap an app in the list and set limits: minutes on weekdays and weekends,
   number of opens, block at night. An empty field means no limit.
4. For a test, a 1-minute limit is handy. Once you're sure everything works,
   turn on uninstall protection if you want.

### Uninstalling LockTime when protection is on

1. Open LockTime and turn off the "Uninstall protection" switch.
2. The app shows when protection will be removed (in 72 hours).
3. After that, uninstall LockTime like any other app.

There is always an emergency exit — [safe mode](#honest-limitations).

## How it works

```
┌────────────────────┐   "window opened" event      ┌──────────────────┐
│ Android            │ ───────────────────────────▶ │ LimitService     │
│ (accessibility)    │    + timer every 3 s         │                  │
└────────────────────┘                              │ 1. which app?    │
                                                    │ 2. any rule?     │
┌────────────────────┐   how much time today?       │ 3. used so far?  │
│ UsageStatsManager  │ ◀─────────────────────────── │                  │
└────────────────────┘                              └───┬──────────┬───┘
                                                        │          │
                                time / opens / night    │          │ ≤15/10/5 min left
                                                        ▼          ▼
                                              BlockActivity    notification
                                              (+ Home button)
```

| File | What it does |
|---|---|
| [`LimitService.kt`](app/src/main/java/io/github/timelock/LimitService.kt) | The accessibility service. Receives "another app is on screen" events from Android, checks the current app every 3 s, blocks (time, opens, night), reads browser address bars, sends warnings, and with protection on closes settings screens that mention LockTime. |
| [`UsageTracker.kt`](app/src/main/java/io/github/timelock/UsageTracker.kt) | Counts screen time and opens since midnight from system `UsageEvents`, plus 7-day totals for statistics. |
| [`Prefs.kt`](app/src/main/java/io/github/timelock/Prefs.kt) | App rules (`Rule`), the night window (`NightWindow`), the "tighten now, loosen tomorrow" logic and the 72-hour protection countdown. |
| [`MainActivity.kt`](app/src/main/java/io/github/timelock/MainActivity.kt) | Main screen: permissions, protection, night mode, app list and the rules dialog. |
| [`StatsActivity.kt`](app/src/main/java/io/github/timelock/StatsActivity.kt) | Weekly statistics. |
| [`BlockActivity.kt`](app/src/main/java/io/github/timelock/BlockActivity.kt) | Block screen with the reason (time, opens, night). |
| [`res/values*/strings.xml`](app/src/main/res/) | UI texts: `values` — English, `values-uk` — Ukrainian. |
| [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) | The full list of permissions — you can verify there's no internet access. |

### Details

- **Time tracking.** Android keeps a log of "app opened / moved to background"
  events. LockTime walks that log from midnight and sums the intervals. When
  another app opens or the screen turns off, the current interval closes.
- **Opens.** An open is switching into the app from another app or from a
  turned-off screen. Moving between screens inside the app doesn't count.
  Once opens exceed the limit, the app is closed.
- **"Tighten now, loosen tomorrow".** A new rule is compared with the current one
  field by field. Stricter fields apply immediately; the full new rule applies
  the next day. Night window: wider applies now, narrower or shifted — tomorrow.
- **Websites.** In known browsers (Chrome, Firefox, Edge, Brave, Opera, Vivaldi,
  DuckDuckGo and the phone's built-in browser) the service reads the address bar.
  If the site of a currently blocked app is open, the browser is closed the same
  way. The site list is `SITES` in `LimitService.kt`.
- **Blocking.** When time is up, the service presses "Home" and shows the block
  screen. If the app is opened again, it closes within ~3 s.
- **72-hour countdown.** It runs not on the phone's clock but on
  `SystemClock.elapsedRealtime()` — Android's internal stopwatch, which can't be
  changed in settings. The stopwatch resets on reboot, so time while the phone
  is off isn't counted, and the removal date shifts by that amount.
- **Uninstall protection.** When settings, the package installer or Play Store
  is open, the service looks for the text "LockTime" on screen. If found, it
  presses "Back" and "Home". So the uninstall, force-stop and service-disable
  pages can't be opened. Side effect: the general app list may also close while
  the LockTime row is visible in it.
- **Languages.** All texts live in string resources. Android picks Ukrainian if
  the phone's language is Ukrainian, and English otherwise.

## Honest limitations

Any blocker on Android can be bypassed, because the system gives the phone's
owner full control. LockTime doesn't make bypassing impossible — it makes it
slow and inconvenient, which is usually enough in a moment of temptation.

- **Safe mode** (hold the power button → hold "Power off"): third-party apps
  don't run and LockTime can be uninstalled. This is Android's deliberate
  emergency exit; no app can block it.
- **Changing the date in settings** doesn't shorten the 72 hours, but **does
  affect daily limits**: set the date to tomorrow and the "new day" starts early.
  Keep automatic date and time on.
- **Browser:** a site is blocked only while the app itself is blocked, and only
  for sites on the list. Browser time doesn't count toward the app's limit.
  Unknown browsers aren't checked.
- **Other profiles / secure folders:** copies of apps in a secure folder, work
  profile or "dual apps" are separate apps; they need their own limits.

## Building from source

**Automatically.** Every push to `main` builds the APK in GitHub Actions and
publishes it under **Releases**.

**Permanent signing key.** For new versions to install over old ones (and with
protection on, the old version can't be uninstalled), the APK must always be
signed with the same key. Add two repository secrets
(Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the `.jks` key file, base64-encoded |
| `KEYSTORE_PASSWORD` | the key password (alias — `locktime`) |

Without the secrets the build still succeeds, but with a temporary key.

**Locally** (requires the Android SDK or Android Studio):

```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

## License

[MIT](LICENSE) — use, modify and share freely.
