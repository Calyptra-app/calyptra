# Attributions & Third-Party Notices

Calyptra is built on the work of others. This file records the sources we rely
on and the licenses under which they are used. It is maintained in good faith;
if you believe an attribution is missing or incorrect, please open an issue.

---

## Calyptra itself

Copyright (C) 2026 Point Blank Co-op and the Calyptra contributors.

Calyptra is free software, licensed under the **GNU Affero General Public
License v3.0** (AGPL-3.0). See [`LICENSE`](./LICENSE) for the full text.

---

## Bundled blocklist

The file [`app/src/main/res/raw/default_blocklist.txt`](app/src/main/res/raw/default_blocklist.txt)
is a curated domain list shipped with the app. It is derived from, and combines,
the following upstream sources:

| Source | Author | License | Upstream |
|--------|--------|---------|----------|
| HaGeZi DNS Blocklists — "Light" | HaGeZi | **GPL-3.0** | https://github.com/hagezi/dns-blocklists |
| `StevenBlack/hosts` | Steven Black & contributors | **MIT** | https://github.com/StevenBlack/hosts |

`StevenBlack/hosts` is itself an aggregation of multiple upstream lists, several
of which are published under **Creative Commons** terms (e.g. CC BY 4.0,
CC BY-SA 4.0). Those upstream attributions are preserved by reference through
the StevenBlack project's own documentation.

In addition, the Calyptra project manually curated a set of mobile-game ad-SDK,
tracker, and telemetry domains and merged them with the intersection of the
sources above.

**Combined-work licensing.** Because the bundled list incorporates GPL-3.0
material (HaGeZi), the combined blocklist data is distributed under the
**GPL-3.0**. AGPL-3.0 (the app) and GPL-3.0 (the data) are compatible for
distribution together. If you redistribute the blocklist, you must preserve
this attribution and the GPL-3.0 terms.

### Runtime updates

The app can fetch an updated blocklist at runtime (see
`app/src/main/java/com/calyptra/app/blocklist/BlocklistUpdater.kt`). The default
update URL points at the **HaGeZi "Light"** list (GPL-3.0). Any list fetched at
runtime remains under its upstream license; Calyptra does not relicense it.

---

## Third-party libraries

Calyptra depends on the following, all under the **Apache License 2.0**:

- AndroidX (core, lifecycle, navigation, activity-compose) — The Android Open Source Project
- Jetpack Compose & Material 3 — The Android Open Source Project
- Room (persistence) — The Android Open Source Project
- DataStore (preferences) — The Android Open Source Project
- WorkManager — The Android Open Source Project
- Kotlin standard library & coroutines — JetBrains s.r.o.

Their license texts are reproduced with each dependency in the build artifacts
and are available from their respective projects.
