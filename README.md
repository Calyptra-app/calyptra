<p align="center">
  <img src="calyptra-icons/android-chrome-192x192.png" width="96" alt="Calyptra">
</p>

<h1 align="center">Calyptra</h1>

<p align="center">
  <b>A privacy-first, on-device ad &amp; tracker blocker for Android — built for children's devices.</b><br>
  No root. No cloud. No telemetry. Nothing leaves the phone.
</p>

---

## What it does

Calyptra runs a **local VPN** on the device and intercepts DNS lookups. When an
app or browser tries to reach a known ad, tracker, or telemetry domain, Calyptra
quietly refuses the lookup — so the ad never loads. Everything happens **on the
device itself**: there is no remote server, no account, and no data collection.

It is designed to be simple enough for a child to use (one tap to turn
protection on) while giving a parent control behind a PIN.

- 🔒 **Zero data leakage** — all filtering is local; nothing is sent anywhere.
- 👶 **Kid-friendly** — a one-tap home screen, with parent settings behind a PIN.
- 🎮 **Works everywhere** — blocks ads in games, browsers, and other apps.
- 🛡️ **No root required** — uses Android's built-in `VpnService`.
- ✅ **Whitelist** — allow specific apps if blocking breaks them.

> **Status:** Beta. Distributed as an APK (not on the Google Play Store, whose
> policy prohibits ad-blocking VPN apps).

## How it works

```
App / browser  →  DNS query  →  Calyptra (local VpnService)
                                    │
                          domain on blocklist?
                            ├── yes →  refuse (NXDOMAIN)  →  ad never loads
                            └── no  →  forward to upstream DNS  →  normal traffic
```

The route is scoped to DNS only (`10.0.0.1/32`), so Calyptra inspects lookups
without proxying the rest of your traffic. See [`docs/semantic/`](docs/semantic/)
for the architecture and component map.

**Stack:** Kotlin · Jetpack Compose + Material 3 · Room · DataStore · WorkManager · Min SDK 26.

## Build

Requires Android Studio (or the Android SDK + JDK 17).

```bash
git clone https://github.com/Calyptra-app/calyptra.git
cd calyptra
./gradlew assembleDebug      # debug APK → app/build/outputs/apk/debug/
```

A signed release build reads its keystore from environment variables
(`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); the keystore
itself is never committed.

## Install (sharing the APK)

1. Send the `.apk` to the recipient (Telegram/WhatsApp as a *file*, Drive link, or USB).
2. On their phone, open the file. Android warns about "unknown apps" — tap
   **Settings** in that prompt and enable **Allow from this source**.
3. Go back and tap **Install**.
4. Open Calyptra, tap **Enable Protection**, and **Allow** the VPN request.

## Privacy

Calyptra collects nothing. There are no analytics, no crash reporting, no
accounts, and no network calls except (optionally) downloading an updated
blocklist. All blocking decisions are made locally on the device.

## License

Calyptra is free software, licensed under the **GNU Affero General Public
License v3.0** — see [`LICENSE`](./LICENSE).

The bundled blocklist combines third-party sources (HaGeZi, StevenBlack) under
their own licenses. See [`ATTRIBUTIONS.md`](./ATTRIBUTIONS.md) for full credits
and terms.
