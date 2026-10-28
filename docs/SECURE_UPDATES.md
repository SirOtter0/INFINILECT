# Secure release updates — PR #35 / permission hardening PR #37

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

No current variant declares `REQUEST_INSTALL_PACKAGES` (PR #37). The retained installer code can only be enabled by a reviewed variant that declares the capability, is not debuggable, and has independent trusted keys/certificate pins. Only after all these conditions and candidate verification can the future Install path check `canRequestPackageInstalls()` and explicitly open Android’s source-permission page. `ACCESS_NETWORK_STATE` remains necessary for optional validated/unmetered metadata checks, independently of installation. No storage, notification, accessibility-service, device-admin or privileged install permission is added.

PackageInstaller uses `MODE_FULL_INSTALL`, the fixed package name, a bounded `base.apk` session copy and fsync. API 31+ explicitly requires user action; older non-privileged installations use system confirmation. An explicit, non-exported receiver handles the system-held mutable PendingIntent needed for status extras and launches the system's pending confirmation. No downloaded field becomes an Intent, command, path or installer parameter. Failed/uncommitted sessions are abandoned. Cancellations/failures are reported when the app returns; success is not announced merely because commit/handoff succeeded. The next process reads the actual installed version from PackageManager.

No uninstall, data clearing, package/signing change, data migration or reader/storage rewrite occurs. Library, History, favorites, tags, locators, preferences, imported files and translation models stay in their existing directories/stores. Public trust and physical install-over behavior are **not established** by a successful build.

## Historical verification — PR #35

Deterministic tests use MockEngine, barriers and original synthetic bytes. Manifest signatures use real RSA-3072 keys generated only in test memory; APK inspection/handoff is mocked on host. There is no trusted third-party APK fixture and no live-service dependency in unit tests. Headless shared Compose tests exercise actual Settings/notes at 320×440 dp, en/es/ca, light/dark, 100/180% font sizes, accessible actions and the disabled public-install boundary. They are not Android/TalkBack/device evidence.

Verified runtime/test source: `01137f67823632f69275d79bf66e258893397d8e`. The final combined run completed successfully in **13m28s**:

| Suite | Passed | Failures / errors / skipped |
| --- | ---: | --- |
| App Desktop | 1,649 | 0 / 0 / 0 |
| App Android-host | 1,376 | 0 / 0 / 0 |
| Core JVM | 59 | 0 / 0 / 0 |
| Core Android-host | 59 | 0 / 0 / 0 |
| **Total** | **3,143** | **0 / 0 / 0** |

Command tasks: `:core:jvmTest :core:testAndroidHostTest :app:desktopTest :app:testAndroidHostTest :androidApp:assembleDebug :androidApp:verifyDebugLanguageResources :desktopApp:compileKotlin`, using the environment's verified Skiko path and forced execution of all test tasks. Android debug and Desktop compile passed in that run. The localization guard passes with **506 strings and 3 plurals per en/es/ca**; `git diff --check` passes. No existing test/assertion was deleted, weakened or disabled. Development guards caught acceptance of a non-JSON `NaN` literal and a resource-table/enum synchronization issue during editing; the parser/resources were corrected before this frozen-source run.

The update coverage adds 24 Desktop and 23 Android-host cases: structured versions/channels, persisted/dismissed/last-check state, opt-in throttling, duplicate presses, cancellation and a temporarily non-cooperative provider, retry, strict JSON, real synthetic RSA signatures/revocation/expiry, package/code/SDK/ABI/certificate mismatches, full-byte hash/tamper checks, unsupported redirects, rate/HTTP/TLS/connection/timeout failures, interrupted transfer cleanup, storage errors, cross-owner candidate protection and actual narrow/large-font shared UI in three languages and both themes. Existing collection/progress/migration/reader/translation/source/security regressions also ran.

The opt-in `:app:githubUpdateCheck` then passed in **16s**, executing the real Desktop Java-engine production metadata path, not MockEngine: official repository, Stable channel, **no newer release, trusted installation false, zero asset requests**. The initial API inspection likewise returned HTTP 200 / `[]`. These host calls used the managed environment's proxy and CA trust (forwarded by an external init script, not application code); they do **not** establish physical Android networking behavior. No release or APK was published to manufacture a success scenario. Unit tests remain independent of live availability.

### Development APK

`INFINILECT-PR35-01137f6-development.apk`, **21,001,782 bytes**, SHA-256 `1a2bd39df6d0d935b2f5622a8a9e3e4cf4d1c815337fedbe0a4abf2bb05dce85`, built from the verified source above. It is signed outside the unchanged Gradle configuration with the original supplied development certificate; signer equality, one signer, v2 verification and 16 KiB alignment pass. Package, version name/code, minimum API 26 and all four ABIs remain unchanged. All six packaged language CVRs match generated resources; all six native libraries match the preceding PR #34 artifact byte-for-byte. Signing inputs and synthetic test/integration fixtures are absent. The only effective permission additions are the two documented above. No runtime dependency or license change was introduced.

Private Drive delivery was read back with exact uploaded size and owner-only permission. No keystore/password was uploaded or committed; no release or public sharing was created. This is an install-over development artifact, **not a trusted public update**. Its hash helps identify this delivered file; a hash on a release alone is still not an independent authenticity root.

### Remaining boundaries

Public update installation is intentionally unavailable until the reviewed real key/certificate/publishing and development-to-public-signing strategy exist. Android PackageManager/PackageInstaller, permission screens, cancellation callbacks, actual install-over data preservation, TalkBack and realme C55 performance remain physically unverified; host inspector/handoff fixtures do not execute those Android APIs. Desktop has no installer. Checks are bounded/opportunistic rather than a background timer, clock/expiry and offline revocation have the limits above, and this initial catalogue policy rejects any listed asset metadata outside 1 byte–256 MiB (including unrelated assets); a publisher needing larger Desktop assets must review that metadata policy independently of the APK transfer budget. No current release exists to validate a complete public end-to-end update. Keep Draft pending physical review.

## Physical acceptance checklist

1. Only if Android accepts the same-development-certificate APK with Play Protect enabled, install over the current version without uninstalling/clearing data. Do not bypass a harmful-app warning. Check Library, History, favorites/tags and all reader positions before and after; confirm translation models still work offline.
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

## PR #37 — mínima autoridad del instalador Android

### Auditoría y causa del permiso innecesario

Base verificada: `main` `209e3e918132f144fc5c588d0db6eca271d8a704` (PR #36 fusionada). Se auditaron `androidApp/build.gradle.kts`, el manifiesto principal, `AndroidUpdates`, `GitHubUpdates`, `UpdateTrust`, `UpdateController`, sus interfaces/UI y los manifiestos combinados originales de las dos variantes. No hay flavors, variantes beta ni módulos de macrobenchmark Android. `app` y `core` son bibliotecas KMP, no otras aplicaciones instalables. Beta/Estable son canales de metadatos, no variantes Gradle.

`REQUEST_INSTALL_PACKAGES` se añadió sin condición al manifiesto común en PR #35. Por tanto, llegaba tanto a `debug` como a `release`, aunque `productionUpdateTrust()` sigue vacío (sin claves de manifiesto ni certificados públicos autorizados) y el backend ya rechazaba además cualquier instalación debuggable. **Ninguna variante actual puede instalar legítimamente actualizaciones.** No basta con que una variante se llame release.

| Variante | Instalador actual | REQUEST_INSTALL_PACKAGES antes | Después | Otros permisos de uso |
| --- | --- | --- | --- | --- |
| debug | Bloqueado: desarrollo, sin confianza y ahora sin declaración | Sí | Ausente | INTERNET, ACCESS_NETWORK_STATE, permiso interno signature de AndroidX |
| release | Bloqueado: sin confianza y ahora sin declaración; sin configuración de firma pública | Sí | Ausente | Los mismos |
| Desktop | No instala APK | No aplica | No aplica | Solo consulta y enlace oficial |

Se conserva `ACCESS_NETWORK_STATE`: `AndroidUpdateInstaller.automaticAllowed()` consulta la red activa/capacidades VALIDATED y NOT_METERED y el modo de ahorro de energía para las comprobaciones automáticas optativas. Ese uso sigue existiendo cuando la instalación está deshabilitada. La consulta manual y los canales permanecen disponibles; ninguna comprobación descarga una APK automáticamente.

### Cambios y coherencia entre manifiesto y runtime

- El manifiesto principal utiliza una marca `tools:node="remove"` para la declaración ordinaria de `REQUEST_INSTALL_PACKAGES`, también frente a manifiestos de dependencias. No se añade ningún permiso, flavor ni opción Gradle para activarlo.
- `UpdateInstaller.installationSupported` es una capacidad explícita, independiente del consentimiento/grant del usuario. Android la obtiene una vez de **las requestedPermissions de su propio paquete**, mediante la API pública `PackageManager.GET_PERMISSIONS`. No depende de metadatos remotos ni de si el usuario concedió «instalar desde esta fuente». Desktop la declara falsa.
- El backend requiere conjuntamente Android + no debuggable + capacidad declarada + raíces de confianza configuradas. Revalida la condición antes de preparar, instalar y solicitar el permiso. El adaptador Android también rechaza la entrega y la apertura de la pantalla de permiso si falta la declaración.
- Añadir claves por sí solo ya no puede habilitar una APK sin capacidad declarada. El grant Android continúa siendo otro requisito independiente del futuro flujo legítimo, después de verificación y consentimiento.
- `verifyDebugUpdaterManifest` / `verifyReleaseUpdaterManifest` se registran mediante `androidComponents.onVariants` sobre `SingleArtifact.MERGED_MANIFEST`. Cualquier nueva variante recibe también el guard. Se ejecutan en `assemble<Variant>` y `check`, sin dependencia externa. Rechazan cualquier conjunto de permisos distinto del revisado, incluyendo declaraciones alternativas `uses-permission-sdk-23`, y exigen exactamente un callback receiver explícitamente no exportado y sin intent-filter.
- `testUpdaterManifestPolicy` comprueba un manifiesto seguro y siete modificaciones rechazadas (permiso ordinario, permiso SDK, permiso privilegiado, receiver exportado, exported omitido, receiver ausente y paquete diferente). Son ocho controles de build separados de los casos JUnit.

No cambian versiones, SDK, applicationId, configuración/certificados de firma, recursos de idioma, dependencias de ejecución, lectores, traducción ni el sistema de descargas de PR #36. El gate nuevo consulta metadatos del paquete una vez por propietario, no por fotograma ni por recomposición. No se inicia ningún servicio.

Una futura distribución con instalación propia necesita una revisión explícita de variante/manifiesto/guard, claves y certificados públicos legítimos, firma y proceso de publicación. No se propone habilitarla mediante una propiedad `-P` ni por ser release. También debe validarse en Android su ruta hoy inalcanzable; la auditoría observa que `prepare` aún usa `Files.getFileStore` para el preflight de espacio, API incompatible con Android como se vio en PR #36. Este cambio de mínimo privilegio no activa esa ruta ni modifica esa operación: corregir/verificar ese requisito forma parte de la activación futura, no de habilitar permisos en builds que hoy no pueden instalar.

### Revisión de la cadena de seguridad conservada

- UI: comprobaciones automáticas desactivadas por defecto; consentimiento separado para descargar e instalar. Sin instalación silenciosa ni evasión de Play Protect.
- Confianza: claves/pins incorporados por código revisado; nunca aceptados desde GitHub o preferencias. RSA ≥3072/SHA256, claves revocadas y validez temporal comprobadas antes de aceptar el payload del manifiesto. No se añade la clave/certificado de desarrollo como raíz pública.
- Artefactos: tamaño/SHA-256 completo, paquete fijo, versiones crecientes, mínimo SDK, ABI y certificado coincidentes con manifiesto firmado e instalación actual. Se repiten hash/identidad antes de handoff. Android realiza su validación final de firma; los mocks host no prueban PackageInstaller real.
- Red: HTTPS y confianza de plataforma; endpoints oficiales fijos, tres redirecciones a CDN autorizadas, límites de JSON/descarga y cancelación existentes. No se amplían hosts, tamaños, concurrency ni políticas TLS.
- Componentes: `UpdateInstallReceiver` sigue `exported=false`, sin intent-filter, y valida acción e identificador de sesión del callback. El PendingIntent es explícito y está en manos del sistema. Se conserva la Activity MAIN/LAUNCHER y el receiver AndroidX protegido por `android.permission.DUMP`; no aparecen componentes exportados nuevos.
- Archivos: nombres privados fijos, bloqueo de propietario, parciales eliminados, publicación atómica y copia de sesión del sistema. No se exponen rutas mediante FileProvider o grants URI amplios. Los errores visibles son códigos localizados, sin mensajes de servidor/credenciales ni datos de libros.

### Comparación reproducible de APK y revisión de Play Protect

Comparar con el original disponible `INFINILECT-PR35-01137f6-development.apk`, no una reconstrucción presentada como original. Su SHA-256 es `1a2bd39df6d0d935b2f5622a8a9e3e4cf4d1c815337fedbe0a4abf2bb05dce85`; certificado público SHA-256 `95c708e6cfee94bc13ecf34d2a38cf4f1dc5eab185133f8e139629dd0be470fe`. Es una firma de desarrollo para comprobar identidad, no un pin de actualización pública.

```sh
apkanalyzer manifest permissions INFINILECT-PR35-01137f6-development.apk
apkanalyzer manifest permissions INFINILECT-PR37-development.apk
apkanalyzer manifest print INFINILECT-PR35-01137f6-development.apk
apkanalyzer manifest print INFINILECT-PR37-development.apk
apksigner verify --verbose --print-certs INFINILECT-PR35-01137f6-development.apk
apksigner verify --verbose --print-certs INFINILECT-PR37-development.apk
sha256sum INFINILECT-PR35-01137f6-development.apk INFINILECT-PR37-development.apk
```

En los XML, revisar paquete, SDK/versiones, requestedPermissions, exported/permission de cada componente y ausencia de servicios nuevos. Comparar además las bibliotecas nativas por entrada ZIP/SHA-256, las versiones de dependencias empaquetadas y el diff Gradle (no hay dependencias nuevas). La APK release local es **sin firmar** y solo sirve para comprobar la variante; no es una versión pública ni una APK que deba instalarse sobre desarrollo.

**Se confirma un permiso innecesario, no la causa del bloqueo de Google.** Ni retirarlo, ni una firma válida, ni un Lint limpio garantizan que Play Protect acepte la APK. No hay prueba física disponible en este entorno. No se modifica identidad/certificado, ofusca comportamiento ni desactiva seguridad para cambiar la detección.

Si persiste el aviso, reunir sin datos personales: APK exacta/SHA-256/tamaño, procedencia y commit; certificado y verificación v2/v3; manifiestos/permisos/componentes antes/después; inventario de dependencias/nativos y resultados de pruebas; captura completa del aviso, fecha y versiones de Android/Play Protect aportadas por el propietario. Usar las [indicaciones oficiales](https://developers.google.com/android/play-protect/warning-dev-guidance) y el [formulario oficial de revisión](https://support.google.com/googleplay/android-developer/contact/protectappeals). El formulario puede requerir evidencia de un análisis externo: revisarlo con el propietario antes de cualquier subida a VirusTotal u otro servicio. No se ha subido ninguna APK a un escáner, enviado apelación, publicado APK/release ni cambiado la visibilidad de archivos.

### Validación física pendiente — realme C55

1. Mantener Play Protect activo. Si bloquea la APK, no pulsar «instalar de todas formas»: conservar la advertencia completa para su revisión. No desinstalar ni borrar datos para probar una identidad/firma distinta.
2. Solo si Android permite una actualización legítima con el mismo certificado, comprobar Biblioteca/Historial, favoritos/etiquetas, posiciones y modelos antes/después.
3. Ajustes → Actualizaciones: comprobar versión instalada, consultar Estable/Beta, errores de conexión/reintento y persistencia de preferencias. Actualizar/instalar debe permanecer deshabilitado por configuración; no debe abrirse «instalar desde esta fuente» ni el instalador del sistema.
4. Revisar comprobaciones automáticas optativas con Wi-Fi no medido, ahorro de batería y regreso a primer plano. El arranque no debe descargar APK ni iniciar un servicio permanente.
5. Comprobar los lectores y una copia offline ya conservada con modo avión/Wi-Fi apagado. Probar TXT/PDF autorizados, cancelación/reintento y eliminación solo de la descarga como regresión de PR #36; sin tocar datos existentes.
6. Verificar es/en/ca, fuente grande y navegación. Estos pasos están preparados para el propietario; no se presentan como ya ejecutados físicamente.

### Verificación ejecutada — PR #37

Ejecución sobre el código congelado del cambio, con JDK 21, SDK/build-tools 37 y las rutas de Skiko/proxy/CA del entorno gestionado configuradas fuera del repositorio. Las cuatro suites se forzaron a ejecutar; no se desactivaron ni debilitaron pruebas.

| Suite | Casos | Fallos / errores / omitidos |
| --- | ---: | --- |
| Desktop | 1.695 | 0 / 0 / 0 |
| Android-host | 1.416 | 0 / 0 / 0 |
| Core JVM | 59 | 0 / 0 / 0 |
| Core Android-host | 59 | 0 / 0 / 0 |
| **Total** | **3.229** | **0 / 0 / 0** |

Las tres regresiones compartidas nuevas verifican las 16 combinaciones de plataforma/desarrollo/capacidad/confianza, la consulta de versiones sin capacidad de instalación y el bloqueo de una entrega ya preparada cuando la capacidad deja de estar disponible. Continúan pasando las pruebas existentes de firmas RSA reales con claves sintéticas, autenticidad/integridad, metadatos/artefactos no autorizados, redirecciones, cancelación, limpieza y errores. Estas pruebas no utilizan una clave pública de producción ni prueban PackageInstaller físico.

Además de JUnit: ocho casos sintéticos de política de manifiesto y los dos guards de manifiestos finales pasan. Como control negativo, ambos guards se ejecutaron también contra los XML combinados **originales** guardados antes del cambio: los dos fallaron por el permiso adicional, como se esperaba. Después se volvieron a ejecutar contra los XML corregidos y pasaron. No se basa la comprobación solo en buscar texto en el manifiesto fuente.

Tareas ejecutadas (mediante el wrapper externo del entorno; estos son sus equivalentes Gradle):

```sh
./gradlew --continue :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:assembleRelease \
  :androidApp:verifyDebugLanguageResources :androidApp:check \
  :androidApp:lintRelease :desktopApp:compileKotlin
./gradlew :androidApp:verifyDebugUpdaterManifest :androidApp:verifyReleaseUpdaterManifest
python3 tools/check_localization.py
git diff --check
```

- Ejecución completa: **BUILD SUCCESSFUL**, 12 min 54 s. Android debug, Android release **sin firmar** y compilación Desktop correctos.
- Lint Android debug y release: **cero incidencias** en ambos informes XML.
- Guard de localización: **541 cadenas y 3 plurales por idioma en en/es/ca**, 189 archivos Kotlin revisados. Los seis CVR empaquetados coinciden con los generados, tanto en debug como en release.
- Comprobación real optativa `:app:githubUpdateCheck`: **BUILD SUCCESSFUL**, 14 s; repositorio oficial, canal estable, ninguna versión nueva, instalación de confianza falsa y **cero solicitudes de assets**. Es una llamada host mediante el proxy/CA del entorno, no una prueba de red Android física.
- Diff: ocho archivos limitados al manifiesto, guard Gradle, capacidad del adaptador/backend, pruebas y este documento. No cambian descargas offline, lectores, core, SQL/migraciones, catálogo de modelos, dependencias, permisos adicionales, recursos ni firma Gradle. Pasan sus regresiones incluidas en las suites completas.

### APK local verificable — PR #37

Se preparó **localmente**, fuera del árbol Git y sin publicar ni subir a Drive/escáner/release, `INFINILECT-PR37-development.apk`:

- Tamaño: **21.136.950 bytes**.
- SHA-256: `64e5a0acef49aefc39746181d8a688469d0b10d128e3a56a3f4b35cc78516897`.
- Certificado SHA-256: `95c708e6cfee94bc13ecf34d2a38cf4f1dc5eab185133f8e139629dd0be470fe`, idéntico al original #35; un firmante, firmas **v2 y v3 válidas** y alineación de 16 KiB verificada en ambos archivos.
- Paquete `org.infinilect.app`, versionCode `1`, versionName `0.0.1-SNAPSHOT`, minSdk `26` y targetSdk `37` conservados. La firma se aplicó fuera de Gradle con el certificado de desarrollo suministrado; no se cambió la configuración de firma ni se incorporaron secretos al repositorio/APK.
- La inspección de los manifiestos **dentro de las APK** debug/release confirma exactamente los tres permisos restantes de la tabla. Frente a la APK #35 solo se retira `REQUEST_INSTALL_PACKAGES`; no se añade otro permiso. Componentes y atributos exported/permission coinciden; el callback del actualizador sigue privado.
- Seis bibliotecas nativas y 53 entradas empaquetadas de versiones de dependencias idénticas al original #35. No hay clases de fixtures de actualizaciones/descargas ni archivos de firma/contraseñas empaquetados.

La igualdad del certificado permite preparar una actualización sobre la instalación previa, pero **no demuestra** que Android/Play Protect la acepten ni que los datos se conserven en una instalación física aún no ejecutada. No hay emulador/dispositivo disponible para validar PackageManager, permisos de instalación, TalkBack, aviso de Play Protect o lectura en modo avión. El bloqueo de Google sigue pendiente de comprobar; la PR se mantiene Draft, sin merge ni publicación.
