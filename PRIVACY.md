<!-- TODO: confirm privacy@pointblank.gr / security@pointblank.gr are monitored mailboxes, or replace with the preferred contact. -->

# Calyptra Privacy Policy

**Last updated: 2026-06-16**

Calyptra is an on-device ad and tracker blocker for Android, designed to run on
children's devices. This policy explains, in plain language, what the app does
with data. It is written for a parent or guardian, not a lawyer.

## In one paragraph

Calyptra does its filtering **on the phone itself**. We (the people who make the
app) run **no servers**, keep **no accounts**, collect **no analytics**, and
have **no crash reporting or telemetry**. We never receive any personal data
about you or your child — there is simply nowhere for it to be sent to us. The
app stores a small amount of information **on the device only** (see below). The
only things that ever leave the phone are (1) periodic downloads of the public
blocklist and (2) DNS lookups — both for the websites your child is *allowed* to
visit and to switch search engines into SafeSearch and YouTube into Restricted
Mode — which, exactly as on any normal internet connection, are forwarded to a
DNS provider and are visible to that provider and to your network. Section 3
lists this outbound activity in full; we do **not** claim that "nothing ever
leaves the phone."

---

## 1. Who is responsible for your data (the "data controller")

**Point Blank Co-op**, a cooperative established in Greece, operates the Calyptra
project.

- **Contact for privacy questions:** privacy@pointblank.gr

Because Calyptra processes data **only on your device** and we operate no
back-end systems, in practice the person who controls the data day-to-day is the
adult who installs and manages the app on the child's device. We have no remote
access to it.

This policy is provided in the spirit of Article 13 of the EU General Data
Protection Regulation (GDPR). Greece is in the EU, so the GDPR applies, and
because the app is used on children's devices we take children's data
particularly seriously (see Section 6).

---

## 2. What Calyptra stores on the device

All of the following is kept **locally on the phone**. None of it is transmitted
to us or to any third party. It is removed when the app is uninstalled (and a
parent can clear it sooner — see Section 8).

| What | Detail | What it is **not** |
|------|--------|--------------------|
| **Protection-state events** | A timestamp plus a state label (for example: protection enabled, disabled, or revoked). Used to show the parent a simple on/off timeline. | **Never** domains, search queries, page addresses, or any record of what was browsed. |
| **Daily block count** | A single number per day (how many lookups were blocked). | Not a list of blocked sites — just a count. |
| **Allowlist of websites** | Domains the parent has chosen to always allow. | This is parent input, not a log of where the child went. |
| **Allowed-app list** | Package names of apps the parent has chosen to exempt from filtering. | This list never leaves the device. |
| **Parental PIN** | Stored only as a one-way **PBKDF2-HMAC-SHA256** hash (100,000 iterations) with a random per-install salt, compared in constant time. | The PIN itself is **never** stored in plain text and **cannot** be reversed from the stored value. |
| **Settings** | Your preferences (e.g. SafeSearch on/off, YouTube restriction level). | — |

**Important:** Calyptra does **not** keep a browsing history, a list of visited
or blocked domains, or any per-query log. The protection timeline a parent sees
reflects only when protection was turned on or off — not what was looked at.

The retention table records at most **500 protection events**, and never deletes
events from the **last 30 days** (so the recent timeline stays accurate). Older
events beyond that window are pruned automatically.

---

## 3. What leaves the device

There are exactly three kinds of outbound network activity. We list them plainly
so you understand the limits of "on-device."

### 3.1 Blocklist downloads

About once a week, Calyptra fetches an updated public blocklist over HTTPS from
GitHub:

- `https://raw.githubusercontent.com/hagezi/dns-blocklists`

These requests send only a fixed app identifier in the `User-Agent` header (for
example `Calyptra/1.3.0 (Android)`). They contain **no account, no device
identifier, and nothing about your child**. GitHub, as the host, can see the
request and the requesting IP address, as it can for anyone downloading a file.

### 3.2 DNS lookups for allowed sites (please read this)

When your child visits a site that is **not** blocked, the device still has to
look up that site's address. Calyptra forwards these DNS lookups to two
third-party "family-safe" DNS resolvers:

- **CleanBrowsing Family** — `185.228.168.168` (primary) —
  <https://cleanbrowsing.org/privacy/>
- **Cloudflare for Families** — `1.1.1.3` (fallback) —
  <https://www.cloudflare.com/privacypolicy/>

This is the same kind of DNS lookup that happens on **any** internet connection.
As a result, **the DNS resolver you use and your network/ISP can see the stream
of domains being looked up** — this is inherent to how the internet works, and
it is true with or without Calyptra. We do not store this stream, but we cannot
make it invisible to the resolver or the network. Please review the providers'
privacy policies linked above to understand how *they* handle DNS queries.

Both of these resolvers are operated by **United States-based** companies, so
using them involves an international transfer of the DNS query stream outside the
EU (see Section 9).

### 3.3 SafeSearch / Restricted Mode setup

When protection starts, Calyptra resolves a small set of fixed "force-safe"
endpoints for search engines and YouTube so that SafeSearch and YouTube
Restricted Mode can be enforced at the DNS level. These are ordinary DNS lookups
of provider-published addresses and carry no information about your child.

That is the **complete** list of what leaves the device. There are no other
network calls — no analytics beacons, no ad requests, no error/crash uploads.

---

## 4. What we do **not** do

- We do **not** sell, rent, or share personal data. There is none to sell.
- We do **not** serve ads or use advertising identifiers.
- We do **not** embed analytics, tracking, or attribution SDKs.
- We do **not** collect crash reports or usage telemetry.
- We do **not** require or offer an account or sign-in.

---

## 5. Permissions Calyptra requests, and why

| Permission | Why it is needed |
|------------|------------------|
| `INTERNET` | To download the blocklist and to forward DNS lookups for allowed sites. |
| `BIND_VPN_SERVICE` + `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | To run the **local, on-device** VPN that performs the DNS filtering. This VPN does not route your traffic to us or anyone else — it exists so the app can inspect DNS lookups locally. |
| `RECEIVE_BOOT_COMPLETED` | To re-enable protection automatically after the phone restarts, so a child cannot disable filtering simply by rebooting. |
| `QUERY_ALL_PACKAGES` | So a parent can pick **any** installed app for the per-app allowlist. The resulting list is used only on the device and **never leaves it**. |
| `POST_NOTIFICATIONS` | To show the ongoing protection notification and alert the parent if protection stops. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | To ask Android not to kill the protection service in the background, so filtering keeps running. |

---

## 6. Children's data

Calyptra is intended to run on a child's device and is configured by a parent or
guardian. We treat the child as a **data subject** with heightened protection.

- **Lawful basis.** The processing that happens on the device is set up and
  controlled by the **holder of parental responsibility** for the child. That
  adult decides what to allow, what to block, and whether to keep using the app.
- **It is a supervision tool.** Calyptra can block whole categories of content
  and shows the supervising adult a **protection timeline** of on/off state
  changes. It does **not** show what the child browsed or searched, because the
  app does not record that.
- **Transparency with the child.** We strongly recommend that the supervising
  adult talk to the child about the fact that the device is filtered and
  supervised, in a way that is appropriate for the child's age and maturity.
  Monitoring is healthiest when it is not a secret.

If you believe a child's data has somehow been mishandled, contact
privacy@pointblank.gr.

---

## 7. How long data is kept (retention)

- **Protection events:** capped at 500 entries, with the most recent 30 days
  always preserved; older entries are pruned automatically.
- **Daily block counts:** kept as small daily totals on the device.
- **Allowlists, settings, PIN hash:** kept until you change or remove them.
- **Everything** is stored only on the device and is **deleted when the app is
  uninstalled** (or when you clear the app's data).

---

## 8. Your rights and how to exercise them

Under the GDPR you have rights including access, rectification, erasure,
restriction, and objection. Because Calyptra holds the relevant data **only on
your device and we hold no copy**, you exercise most of these rights directly:

- **Access / review:** Open the app to see the allowlist, settings, and the
  protection timeline.
- **Rectification:** Change allowlists, settings, or the PIN in the app.
- **Erasure:** Uninstall the app, or clear its data via Android Settings → Apps
  → Calyptra → Storage → *Clear data*. This removes the on-device data,
  including the stored PIN hash.
- **Portability / restriction / objection:** Since we never receive your data,
  there is nothing for us to export or restrict on our side.

If you have a question we can actually help with, email privacy@pointblank.gr.
You also have the right to lodge a complaint with your local supervisory
authority; in Greece this is the Hellenic Data Protection Authority (HDPA).

---

## 9. Backups and device transfer

- **Cloud backup is disabled.** The app sets `android:allowBackup="false"`, so
  Android's automatic cloud backup never uploads Calyptra's data on any Android
  version. Its database, settings, and parental PIN material stay on the device
  and are **not** copied to Google's backup service.
- **Device-to-device transfer:** if you use Android's built-in *device-to-device*
  migration (e.g. moving to a new phone), Android may copy the app's local data —
  its database and stored settings — directly between your two devices. The
  parental PIN material is **explicitly excluded** from this transfer, and
  nothing passes through us.

---

## 10. International transfers

We operate no servers, so we make no international transfer ourselves. However,
as described in Section 3.2, DNS lookups for allowed sites are forwarded to
DNS resolvers operated by **United States-based** providers (Cloudflare and
CleanBrowsing). Using the app therefore involves your device sending DNS queries
to infrastructure outside the EU. Please consult those providers' privacy
policies (linked in Section 3.2) for how they handle that data.

---

## 11. Security

- The parental PIN is stored only as a salted, iterated one-way hash
  (PBKDF2-HMAC-SHA256, 100,000 iterations) and verified in constant time.
- Blocklist updates are fetched over HTTPS.
- See [`SECURITY.md`](./SECURITY.md) for how to report a security issue.

---

## 12. Changes to this policy

If we change this policy, we will update the **Last updated** date at the top and
publish the new version in the project repository. Material changes will be
described in the project's release notes.

---

*Calyptra is free software, licensed under the GNU Affero General Public License
v3.0. The full source code is available at
<https://github.com/Calyptra-app/calyptra>, so anyone can verify exactly what the
app does with data.*
