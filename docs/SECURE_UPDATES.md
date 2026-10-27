# Secure release updates — PR #35

## Audit and boundary before implementation

Base: `main` at `0e191ae557a27077e652793a82471c1c156d77f6`, after PR #34. Android is `org.infinilect.app`, API 26–37, version code 1 / `0.0.1-SNAPSHOT`; no public release version or release signing configuration exists. The four current APK ABIs and the development signing procedure are retained. Desktop has no published package-version policy. A bounded official GitHub API check on 2026-10-27 returned an empty release list. No release, manifest signing key or public certificate trust anchor was found. A development APK is not a public trusted release.

Reusable pieces: the platform Ktor engines (OkHttp Android / Java Desktop), explicit redirect policies, application-owned sources and coroutine lifetime, private IO storage, Settings composition and the en/es/ca `UiText`/Compose Resources tables. Library, History, readers, progress, translation and their stores are outside this change.

The independent manifest verification root must be supplied through a reviewed future source change. This PR must ship with an empty production trust configuration. Metadata discovery is useful without this root; public APK preparation/installation must fail closed. No key is generated or inferred from downloaded data or the development certificate.

## Implemented architecture

- `updates/Updates.kt` (common): structured versions, channels, immutable UI states, a process-local prepared-update capability, backend contract and `UpdateController`. One operation is accepted at a time. A generation ticket rejects stale results; cancellation joins old work before cleanup/replacement. Preference writes use a conflated worker independently of transfer work.
- `updates/UpdateSettings.kt` (common): compact Settings section, installed identity, channel choice, optional checks, last attempt/result, plain-text notes, progress, explicit download consent and separate install consent. `UpdateNotice` observes only the notice Boolean in the shell, not download-progress emissions. All authored text/semantics use existing en/es/ca resources.
- `updates/GitHubUpdates.kt` (JVM shared): existing Ktor engine factory, fixed official API, restricted asset redirects, private bounded streaming, atomic preference record, full-file hashing and verifier/installer orchestration. One transfer lease protects the candidate across multiple owners/processes until discard or system handoff. Its lock lives with persistent preferences, outside clearable cache. No remote metadata becomes a local filename.
- `updates/UpdateTrust.kt` (JVM shared): bounded strict UTF-8/JSON, signed-envelope verification, manifest policy and APK identity checks. The production trust factory is intentionally empty. Tests inject separate runtime-generated keys.
- `updates/AndroidUpdates.kt`: PackageManager installed/archive identity, ABI inspection without loading native libraries, PackageInstaller full-install sessions, explicit source-permission UI and a non-exported callback receiver. Application context only; no Activity is retained.
- `updates/DesktopUpdates.kt`: version checks and one fixed official Releases link. It never prepares an APK or executes an installer.

`ApplicationSources` owns the controller. The existing foreground/background hooks provide check opportunities; the Settings composition owns download preparation. Leaving Settings cancels/discards downloads or verified candidates. Metadata checks are application-owned and may complete after changing tabs; backgrounding cancels outstanding checks/transfers. There is no stale screen callback or navigation lock. Closing drains preferences, joins cancelled work, removes temporary data and closes the dedicated client/engine. No reader, collection or translation operation depends on this service.

## Version and channel policy

Published stable tags must be `vMAJOR.MINOR.PATCH` (an unprefixed equivalent is accepted); beta tags must be `vMAJOR.MINOR.PATCH-beta.N`, with a positive integer N. Components are numeric, bounded, and have no leading zeros. Beta numbers compare numerically; a final release sorts after its betas. Arbitrary prereleases (`rc`, `alpha`, unnamed GitHub prereleases, snapshots or build metadata) are not adopted as beta releases. Stable excludes every GitHub prerelease; Beta permits explicitly matching beta tags and stable releases. Drafts and versions no newer than the installed structured version are excluded.

The installed snapshot form is recognized only for comparison, not as a valid publication tag. Android reads its real versionName/code, SDK, supported ABIs, current certificate set and debuggable flag from PackageManager. Desktop currently uses the matching existing `0.0.1-SNAPSHOT` baseline/code 1; publishing a Desktop build must update that source constant together with the Android/packaging version policy. This PR does not change either platform's distribution version.

Discovery scans at most three pages of 30 releases, choosing the highest accepted structured version. An empty result means no newer accepted release **in that bounded recent listing**, not a universal assertion about all historical tags. Listed notes, sizes and versions remain untrusted discovery metadata; availability explicitly says install compatibility is not yet verified. Android code compatibility is authoritative only after a trusted manifest and archive inspection. Downgrades, equal Android codes and non-increasing semantic versions are rejected independently.

Manual checks are always available once the small preference record has loaded; no five-second navigation delay or startup popup is added. Optional automatic checks are **off by default**, opportunistic on foreground use and limited to one attempt per 24 hours, including failed attempts. There is no background scheduler or permanent service: an app left continuously foreground for days does not run an independent timer. Android additionally requires a validated unmetered network and skips battery saver. Manual checks may use a metered connection. Neither path downloads automatically. A dismissed tag is remembered; later automatic discoveries of the same tag do not repeat the notice.

## Independent trust and manifest format

The GitHub account, TLS, release metadata, checksums and assets are not an independent signature authority. An account/asset replacement alone must not authorize installation. `productionUpdateTrust()` contains **no manifest keys and no public APK certificate pins**. It rejects preparation before requesting an asset, and the UI disables Update with an honest explanation. Debuggable builds are separately ineligible for trusted public installation even if a future key configuration is supplied. No development certificate becomes a production root.

The future reviewed configuration must embed an offline-controlled RSA public key of at least 3072 bits (X.509 SubjectPublicKeyInfo DER), a stable key identifier, a separate expected public APK certificate SHA-256 set, and any revoked key identifiers. The corresponding private key must remain outside GitHub asset storage, source, APKs, logs and this environment. SHA256withRSA is available through Android API 26/JCA without a crypto dependency.

Each release must contain exactly one `infinilect-update-v1.json` asset. Its envelope has exactly these string fields:

```json
{"keyId":"<reviewed-key-id>","payload":"<base64-exact-UTF-8-payload>","signature":"<base64-RSA-SHA256-signature-over-payload-bytes>"}
```

The signed payload has exactly the following fields (angle-bracket entries are documentation placeholders, not actual deployable values):

```json
{
  "schema": 1,
  "repository": "SirOtter0/INFINILECT",
  "tag": "v1.2.3",
  "channel": "stable",
  "package": "org.infinilect.app",
  "versionCode": 123,
  "minSdk": 26,
  "abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
  "certificateSha256": "<64-lowercase-hex-public-certificate-digest>",
  "asset": "infinilect-v1.2.3-universal.apk",
  "size": 12345678,
  "sha256": "<64-lowercase-hex-whole-APK-digest>",
  "issuedAt": 1700000000000,
  "expiresAt": 1700086400000
}
```

The signature is checked **before parsing the payload**. Exact signed bytes avoid JSON canonicalization ambiguity. Duplicate keys, unexpected fields, invalid UTF-8/JSON, deep documents, non-finite numbers, missing/wrong types and unsupported schema fail closed. A manifest expires at most 30 days after issuance; future issue times beyond five minutes and expired manifests are rejected. Device clock correctness remains an assumption; there is no invented trusted-time service.

The signed repository/tag/channel must agree with the fixed source and discovered release. The named APK must be an exact official release asset, with identical signed and listed size; size/hash/certificate syntax is bounded. Signed code must exceed the installed code (and remain within Android's code range), version must be newer, minimum SDK must be supported and at least 26, and at least one signed ABI must be supported. The signed certificate must match both a separate embedded public pin and the **current** installed signer set. This initial policy requires one exact signer; signing-lineage rotation/multiple signers are intentionally unsupported and blocked.

After downloading, the whole file's exact length and SHA-256 are verified before PackageManager archive parsing. Package, version name/code, minimum SDK, ABI set and signer must agree with the signed manifest and current install. The complete hash/identity is checked again immediately before copying to Android. Only the exact process-local verifier capability can reach installation; a raw path, URL or release-provided token cannot. The OS performs final APK signature/installation validation. Host identity fixtures are mocks, not a claim of real Android archive verification.

### Bootstrap, rotation and withdrawal

Public activation needs a separately reviewed key/certificate configuration and a real publishing/signing process. Recommended publishing procedure: build the intended non-debug APK; verify its public signer and monotonically increasing version code; compute final bytes/hash; author the bounded payload with a short validity period; sign its exact bytes offline; publish APK plus envelope to the official repository only after publication authorization. A checksum uploaded beside an APK alone does not satisfy this process.

A new manifest key must arrive through an already trusted app build or a manually verified bootstrap, not a key supplied by the release being checked. Keep old/new roots only for an explicit transition and never reuse the same public key under a new identifier to evade revocation. Remove/revoke old identifiers in a reviewed app update. Withdrawing a GitHub release removes it from subsequent discovery, and short manifest expiry bounds replay; an already prepared, still-valid candidate is not remotely revoked instantly. Compromise of a pinned offline key requires a separately trusted recovery build/root rotation. This PR does not claim universal offline revocation or trusted time.

The installed **development** population cannot silently migrate to a different public APK certificate. Continue manual same-certificate development updates; decide and review a legitimate public-signing/lineage migration separately. Do not ask users to uninstall or erase data to bypass a mismatch.

## Network, storage and resource limits

| Boundary | Policy |
| --- | --- |
| API | Only `https://api.github.com/repos/SirOtter0/INFINILECT/releases`; three fixed pages, no API redirects, no credentials/cookies |
| Initial asset | Exact `https://github.com/SirOtter0/INFINILECT/releases/download/<validated-tag>/<validated-name>` |
| Redirects | At most three; only HTTPS `release-assets.githubusercontent.com` / `objects.githubusercontent.com` with `/github-production-release-asset/` paths, no userinfo/port/fragment, URL ≤8 KiB; any new CDN policy needs review |
| Metadata | ≤1 MiB/page, depth 16, 20,000 JSON nodes, 64 keys/object, 256-character keys, ≤30 assets/release; notes ≤16,000 characters |
| Manifest | Envelope ≤32 KiB, decoded signed payload ≤16 KiB |
| Transfer | One operation/lease, ≤256 MiB APK, 32 KiB streaming buffer; declared and actual sizes checked; private-storage free-space preflight |
| Time | 5 s connection / 15 s idle socket / 120 s request; cancellation remains available |
| Progress | At most one normal progress emission per 100 ms plus final/verifying state; no frame polling |
| Disk | Fixed `payload.part` then atomic `candidate.apk` in private cache; no filename from metadata; incomplete/failed/cancelled/stale candidates deleted; process restart discards all candidates |
| Preferences | Separate private persistent `updates-v1/update.preferences`, ≤1 KiB, checksum detection and atomic fsynced replacement; stable enum names, no existing preference/schema migration |

The persistent transfer lock prevents a second owner from evicting/replacing a candidate while it is in use. Android receives its own streamed PackageInstaller session copy before the app removes its candidate; no public file provider or broad URI permission is needed. The OS-owned session consumes additional system staging space; this does not increase app image/model/cache budgets. Memory/CPU/frame timing on realme C55 remains physical-test work, not inferred from host tests.

TLS/hostname validation stays with the unchanged platform engines (OkHttp Android, Java Desktop). Expected offline, TLS/connection, timeout, HTTP/rate limit, JSON/redirect, trust, integrity, storage, busy-owner and installer errors become finite localized states. No raw exception, server content, token, book text, query or profile data is logged. Fatal Errors are not broadly caught. No telemetry, API key, signing private key or extra runtime dependency is introduced.

## Android installation and data safety

After a verified candidate, Install first checks `canRequestPackageInstalls()`. Only then can the user explicitly open Android's permission page for this package and return to retry Install. `REQUEST_INSTALL_PACKAGES` enables this official user-approved path; `ACCESS_NETWORK_STATE` supports the opt-in validated/unmetered check. No storage, notification, accessibility-service, device-admin or privileged install permission is added.

PackageInstaller uses `MODE_FULL_INSTALL`, the fixed package name, a bounded `base.apk` session copy and fsync. API 31+ explicitly requires user action; older non-privileged installations use system confirmation. An explicit, non-exported receiver handles the system-held mutable PendingIntent needed for status extras and launches the system's pending confirmation. No downloaded field becomes an Intent, command, path or installer parameter. Failed/uncommitted sessions are abandoned. Cancellations/failures are reported when the app returns; success is not announced merely because commit/handoff succeeded. The next process reads the actual installed version from PackageManager.

No uninstall, data clearing, package/signing change, data migration or reader/storage rewrite occurs. Library, History, favorites, tags, locators, preferences, imported files and translation models stay in their existing directories/stores. Public trust and physical install-over behavior are **not established** by a successful build.

## Verification and limits

Deterministic tests use MockEngine, barriers and original synthetic bytes. Manifest signatures use real RSA-3072 keys generated only in test memory; APK inspection/handoff is mocked on host. There is no trusted third-party APK fixture and no live-service dependency in unit tests. Headless shared Compose tests exercise actual Settings/notes at 320×440 dp, en/es/ca, light/dark, 100/180% font sizes, accessible actions and the disabled public-install boundary. They are not Android/TalkBack/device evidence.

Final automated counts, builds and artifact results are recorded after verification. Initial research's official GitHub API response was HTTP 200 / `[]`; no release or APK was published to manufacture a success scenario.

## Physical acceptance checklist

1. Install the same-development-certificate APK over the current version, without uninstalling/clearing data. Check Library, History, favorites/tags and all reader positions before and after; confirm translation models still work offline.
2. Open Settings → Updates in es/en/ca, light/dark, narrow portrait/landscape and large fonts. Verify the installed version/code, selected channel, scrolling, notes Close action and accessible labels/keyboard/TalkBack.
3. Check Stable and Beta. With the current empty official catalogue, expect no newer release. Disconnect and retry: a localized recoverable error, no reader/navigation lock. Test return after reconnecting and rapid repeated presses.
4. Leave automatic checks off: no startup network check/popup. Opt in, foreground on a validated unmetered network, then repeat tab/background/foreground transitions: at most one attempt per day; metered/battery-saver skips automatic attempts. Manual checks remain possible. Persist the choice/channel across restart and cache clearing.
5. Public Update must remain disabled with the trust explanation; no APK download, permission request or installer should appear. This is the expected safe result for this development build, **not** a translation/model or networking failure.
6. After a separately authorized real trust/publishing setup, test signed stable/beta manifests on supported Android ABIs; explicit download/cancel/retry, leaving Settings/backgrounding, corrupt/expired/oversized/wrong-signer/wrong-package/old-code/unsupported-ABI artifacts, blocked redirects, two owners, storage failure and process death. Untrusted cases must never open installation.
7. Only with a trusted compatible non-debug installation, test permission decline/allow, separate Install consent, system confirmation/cancellation, resumed failure and actual version after restart. Compare all data again. Do not publish test releases or replace production assets merely to perform this checklist.

## Primary references

- [GitHub Releases REST API](https://docs.github.com/en/rest/releases/releases#list-releases-for-a-repository) and [release assets](https://docs.github.com/en/rest/releases/assets).
- [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller), [SessionParams.requireUserAction](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)) and [package-install permission check](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()).
- [Android application signing](https://developer.android.com/studio/publish/app-signing) and [SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo).
- [Java signature algorithms](https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html#signature-algorithms).

No source/assets are copied from Mihon. Existing GPL-3.0-or-later and dependency licenses remain unchanged.
