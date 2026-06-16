# Security Policy

Calyptra is maintained by its open-source contributors (the **Calyptra
maintainers**). We take the security of an app that runs on children's devices
seriously and welcome responsible disclosure.

## Reporting a vulnerability

**Please report security issues privately through GitHub Security Advisories:**

- <https://github.com/Calyptra-app/calyptra/security/advisories/new>

Do **not** open a public GitHub issue, pull request, or discussion for a
suspected vulnerability, and please do not disclose it publicly until we have had
a reasonable chance to investigate and ship a fix.

When reporting, it helps to include:

- A description of the issue and why you believe it is a security problem.
- Steps to reproduce, or a proof of concept.
- The app version (and Android version, if relevant).
- Any suggested remediation.

## What we will do

- We aim to **acknowledge** your report within a few business days.
- We will keep you informed of our assessment and of progress toward a fix.
- We will credit reporters who wish to be credited once a fix is released
  (please let us know your preference).

These are good-faith targets from a small volunteer project, not a contractual SLA.

## Scope

In scope:

- The **Calyptra Android application** (the source in this repository).
- The **blocklist update mechanism** (the HTTPS fetch of blocklists and how the
  app handles the downloaded content).

Out of scope:

- Vulnerabilities in third-party services the app talks to (for example GitHub's
  raw content hosting, or the upstream DNS resolvers CleanBrowsing and Cloudflare
  for Families) — please report those to the respective provider.
- Issues that require a physical-access attacker who already controls the device
  at the OS level.

## Release integrity

Calyptra is distributed as a signed APK (it is not on the Google Play Store).
Each published release includes the **SHA-256** checksum of the APK. Before
installing, recipients can verify that the file they received matches the
published SHA-256 to confirm it has not been tampered with in transit.

## Source and license

Calyptra is free software, licensed under the **GNU Affero General Public License
v3.0**. The complete source is available at:

- <https://github.com/Calyptra-app/calyptra>

Because the source is public, the app's behavior — including its network activity
and how it handles data — can be independently audited.

## Privacy

For how the app handles data, see [`PRIVACY.md`](./PRIVACY.md). Privacy questions
(as opposed to security vulnerabilities) can be raised as a normal GitHub issue:
<https://github.com/Calyptra-app/calyptra/issues>.
