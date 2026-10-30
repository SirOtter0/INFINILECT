# Offline publication downloads — PR #36

PR #39 extends the existing provider list with **Wikisource, Spanish reviewed
subset**. The actual EPUB 3 export of _El matadero_ passes full validation,
atomic retained publication and reopening after closing the network source and
recreating storage. Initial coverage is one complete reviewed work/revision,
not all search results. A source-specific rejection policy verifies semantic
spine/credit fingerprints and exact original attribution links after ordinary
EPUB validation, without replacing the existing manager or changing Archive's
policy. Dynamic export size is not invented; real progress may be indeterminate.
All quota, consent, leases, cancellation/recovery and safe-deletion guarantees
below remain. [Current evidence and physical checklist](WIKISOURCE.md).
The original PR #36 audit/table below is historical; its statement that no remote
EPUB adapter exists is superseded by this narrow route.

## Audit before implementation

Base: main `1d8235e294079844118e0125c5170e6f10c32df1` (PR #35 merged). Existing Library/History live in SQLDelight schema v2; locators, imports, preferences, translation models and disposable reading preparation have independent owners. Saving metadata never authorizes bytes. `OpenPublicationController` normally resolves fresh source metadata and then feeds the existing format preparer through `ResourceLoader` / streaming `ResourceContent`.

| Source | Existing acquisition authority | Retained downloads in this PR |
| --- | --- | --- |
| Internet Archive | Strict explicit CC0 `texts` subset only; no dark/restricted/private/nodownload/lending/printdisabled items, no conflicting file-level rights; bounded exact-file HTTPS redirects from refreshed official metadata | TXT and PDF only, through the same adapter and immediate pre-open validation |
| Project Gutenberg experimental | OPDS2 catalogue only; delivery links are inert and acquisition is disabled | Disabled; no new authorization assumed |
| Imported files | App-owned private validated imports, already available offline | No duplicate download; existing identity/ownership retained |
| Standard Ebooks / OAPEN / other named future providers | No operational authorized acquisition integration in the current product registry | No new provider or scraping |
| Development fixtures | Explicit developer/test sources, hidden in normal selectors | Test infrastructure only, never exposed as real downloadable catalogue content |

ArchiveMetadata already recognizes both TXT and PDF. The production source option previously enabled only the text reader; this PR enables its existing PDF preparer contract as part of the download flow. EPUB/CBZ download storage/validation and offline reader integration can be tested with original authorized synthetic providers, but no current public adapter grants these formats. Do not advertise remote EPUB/CBZ catalogues until an adapter actually authorizes them.

CC0 metadata is a source declaration, not a universal verified copyright ruling. Original source attribution, license/rights and country uncertainty remain. A retained copy is requested explicitly under this narrow acquisition policy; no loan, DRM, account, protected viewer or payload scraping is supported.

Android WorkManager would require an independent, restartable source/validation/store owner, scheduling constraints and reconciliation with the foreground runtime. Reconstructing all readers/import/model repositories inside a worker would be unsafe and excessive. The initial implementation instead uses one application-owned foreground worker: tab/detail changes do not cancel an explicit download; backgrounding Android cancels it; process/reboot recovery marks interrupted work cancelled for explicit retry. Desktop can continue while its process/window is running, including minimized operation. No service, new dependency, permission or claim of persistent background/range-resume support.

Architecture, storage bounds, data-preservation policy and verification are detailed below.

## Primary source-policy references (reviewed 2026-10-27)

- [Internet Archive file permalinks and redirects](https://archive.org/developers/items.html): official individual-file delivery route, not permission to download every item.
- [Item Metadata API](https://archive.org/developers/md-read.html) and [record fields](https://archive.org/developers/md-record.html): current item/file metadata is checked through the existing adapter; optional/unrecognized authority does not become a grant.
- [Automated access guidance](https://archive.org/developers/bots.html): retain the application's identifying User-Agent, serialization, limited concurrency and no automatic retry/prefetch. HTTP 429 ends the action; this PR adds no automatic rate-limit retry.
- [CC0 1.0 legal code](https://creativecommons.org/publicdomain/zero/1.0/legalcode.en): the selected metadata declaration is used conservatively; this is not proof of the uploader's rights or a grant of unrelated rights. No jurisdiction-wide conclusion is inferred from age, language or catalogue presence.
- [General Archive terms](https://archive.org/about/terms.php): the current page returned a JavaScript shell in both available fetch paths. A complete current general-terms review could not be independently completed here; no new blanket entitlement is claimed. The pre-existing narrow CC0 acquisition rules are reused unchanged. See also the earlier [source audit](INTERNET_ARCHIVE.md).

## Implemented architecture

`OfflineDownloadProvider` is explicit adapter opt-in. `InternetArchiveSource.downloadOffers` reuses `item`/`ArchiveMetadata.item`, including file-level restrictions and exact original resource identity. Merely requesting offers fetches metadata, not publication bytes. `FileOfflineDownloads.download` refreshes offers and requires the **exact selected resource**, including its revision; `openDownload` delegates to `loadResource`, which checks current authorization again immediately before streaming. Retry never silently chooses a different file of the same format. A new offer/consent is required if the selected resource disappears or changes revision. No arbitrary download URL enters the common manager, persistent record or UI.

Existing `ArchiveUrls` / client policy still controls HTTPS, public official hosts, bounded redirects, timeouts, content negotiation, response/body limits and cancellation. No new general URL fetcher, scrape path, credentials, storage permission or transport configuration was added. Production admission/denial and hostile redirect cases use the real adapter with deterministic HTTP fixtures. There is no live third-party book payload used as a trusted test fixture.

`OfflineDownloads` owns a Supervisor scope and one worker independently of screen composition. At most eight active/pending accepted intents and 256 records; duplicate requests are guarded before and during durable writes. State transitions are `Pending → Downloading → Downloaded`, or `Error` / `Cancelled`. Absence of a record is `Not downloaded`. Error/cancelled entries require an explicit user retry. UI mutations and state publication use the owner dispatcher; network, streaming, hashing, validation and filesystem operations run on IO. Serious `Error`s are not caught as recoverable failures.

Per-publication byte progress is a separate StateFlow, throttled to at most one ordinary event per 100 ms plus final completion. Known totals are real source/response lengths; unknown totals remain indeterminate. The main catalogue and Library filter do not emit on each byte event. Byte counters are session-only: partial transfers are not resumable and restart intentionally presents Cancelled, rather than a misleading persisted percentage.

The download control is shared by details opened from Home, Search, Library and History. The Library bookmark and Favorite heart retain their existing operations. Offers show format, declared size when known, rights and a consent explanation. The control cancels pending/running work; completed downloads require a separate deletion confirmation. Settings → Downloads lists retained publications independently of Library, with progress, retry, read, cancellation and deletion. Library has a combined Downloaded filter and a small availability mark. All new messages use existing UiText / Compose resources in English, Spanish and Catalan.

## Private storage and recovery

Android uses `filesDir/offline-downloads-v1`; Desktop uses its existing persistent application-data root. These are distinct from imports, translation models, temporary reading preparation and the resource cache. No title, URL or provider filename becomes a filesystem path. File keys are SHA-256 of length-framed original source/local identifiers; symlinks at managed file boundaries are rejected. One file lock protects against competing store owners.

A checksummed, bounded binary `.record` contains the original PublicationId, source attribution, selected resource key/revision, format, status, declared/final size, hash, completion timestamp, finite error category and monotonic revision. It is not Library membership or acquisition authority. Temporary bytes use `.part`; a completed copy uses `.payload`. Writes are private, fsynced and atomically replaced on the same filesystem. Payload publication precedes the completed record. A crash between those steps cannot turn an interrupted transfer into an authorized completed download.

At first load, Pending/Downloading records become Cancelled/Interrupted, partial and metadata temporary files are removed, and no network request starts. Missing/wrong-size completed files become Error. On acquisition, the full bounded file hash is checked before exposing a reader loader. Corrupt records are rejected visibly; their payloads remain unavailable and still count against storage. Orphan payloads are conservatively preserved rather than silently deleting potentially retained user content. Repair/selection of individual corrupt orphan records is a remaining limitation; ordinary entry deletion and explicit retry work for readable records.

A completed atomic record wins a cancellation arriving after commit. Revision guards prevent old snapshots/results from republishing removed or superseded state. Reader leases prevent removal/replacement during preparation. Closing the store retains its ownership lock until the final reading lease is released. Opening and closing content handles is idempotent; no resource is recycled while another owner uses it.

Limits:

| Budget | Value |
| --- | --- |
| Simultaneous retained transfers | 1 |
| Accepted active/pending intents | 8 |
| Retained records | 256 |
| Default retained payload quota | 512 MiB |
| User quota presets | 128, 512, 1024, 2048 MiB |
| Maximum TXT payload | 16 MiB, existing text policy |
| Maximum PDF payload | 32 MiB, existing PDF policy |
| Maximum EPUB/CBZ retained payload | 64 MiB, plus the existing reader's stricter structural/resource limits |
| Streaming/hash buffer | 32 KiB |
| Maximum metadata record | 128 KiB including checksum |
| Free-space admission reserve | Three times known size (or permitted unknown-size cap), plus 8 MiB |

Quota cannot be lowered below occupied retained bytes; it does not evict kept content. There is no unbounded prefetch or cache increase. Source Content-Length/declared size must match actual EOF; excess/short bodies, malformed content or failed validation never become Downloaded. Space is checked before payload access; concurrent external disk consumption can still cause a recoverable storage failure during streaming. Atomic rename support is required; absence is a storage error, not a partial-copy fallback.

## Validation and offline opening

The validator sniffs bytes and runs existing preparers: strict UTF-8 text and text limits, EPUB OCF/XML/ZIP policy, CBZ archive/path/ratio/PNG/JPEG policy, and the platform PDF parser. Extension and MIME alone never authorize a ready file. ZIP/XML/image/native limits are unchanged. The SHA-256 retained hash detects later local corruption; it is **not** advertised as an independent signature or publisher authenticity proof.

`OpenPublicationController` first acquires a validated local copy with the same original publication/resource identity. If present, both metadata and content come from that capability: the source is not consulted at all. Existing format preparers then create their normal disposable, owned reader representation. The lease closes after preparation; readers continue to own their usual private prepared files. A missing/corrupt retained file is rejected and recorded as a recoverable Downloads error; opening then follows the existing source path. An unavailable optional retained store likewise cannot disable original online reading. With no valid local copy and no network, the existing source failure is shown.

TXT, EPUB, CBZ and PDF reader engines are unchanged. For the same selected resource, locators use their existing identities: TXT code points, EPUB canonical spine/block anchors, CBZ's existing PAGES document identity, PDF's existing page locator. A different representation (for example choosing PDF after reading TXT) keeps separate existing progress records; this PR does not claim cross-format location mapping. EPUB2/EPUB3 fixtures run through the normal parser. Translation, selection, reader settings and imported-file semantics are untouched.

## Library, history and schema compatibility

No SQLDelight tables, schema version or migration change. Existing records remain authoritative. First use creates only the independent versioned download sidecar; no adoption, reset, reinstall or title-based matching is performed. All original Library/History/progress/tag/favorite/preferences and translation-model stores are unchanged.

- Add to Library: metadata membership only; no download.
- Download: original identity retained; does not add a Library entry or change reading state/favorites/tags.
- Remove from Library: retained copy is **kept**. Confirmation explains this; Settings → Downloads can still open it.
- Delete download: removes only this store's marker/payload/partial; no imported original, Library row, History row or progress record is removed.
- Clear temporary resource/reader cache: does not delete retained downloads.
- Open a retained copy: existing successful-reader History and conservative reading-state rules apply as usual.
- Downloaded filter: AND-combined with search/status/favorites/tags, based on retained readiness; it does not mislabel imported files as downloads. Actual file availability is rechecked on store refresh/acquisition, not by a continuous filesystem watcher.

## Platform lifecycle and limitations

Leaving details, switching tabs or closing Downloads stops UI observation, not the explicit foreground transfer. Android's existing foreground/background lifecycle hooks pause the manager and cancel active/pending work on background; returning foreground does not restart it. Desktop process shutdown cancels/drains the owner; a still-running minimized Desktop window can continue. Unexpected process death/reboot recovers as Cancelled on next startup. Retry reauthorizes and starts a complete new transfer; no HTTP range resume, Android persistent worker, notification/service, or device-reboot scheduler is implemented.

Native PDF parsing and some existing synchronous archive validation cannot promise immediate interruption inside a native call. Kotlin cancellation rejects the obsolete result and prevents commit; temporary resource cleanup completes when the underlying call returns. Validation may temporarily duplicate the archive in the existing preparer's private spool, accounted for by admission reserve; it does not read the complete source payload into a new byte array. Existing per-reader limits and native-memory caveats still apply.

No performance figures or realme C55 acceptance are inferred from host tests. No publication or private paths are logged, no telemetry is added, and no new dependency/license/permission is introduced. Update manifests, trust policy, APK installation and signing configuration from PR #35 are unchanged.

## Reproducible checks and physical acceptance

Host regression coverage includes deterministic barriers for duplicate/bounded requests, cancellation/noncooperative work, completion/cancel races, foreground lifecycle, retries, progress flow isolation, storage failure, partial recovery, exact-file selection, SHA corruption, leases/eviction, quota, source restrictions/redirects, same-ID reader integration and SQLite organization/history/progress preservation. Reader integration explicitly disables the synthetic source, destroys its owner, deletes temporary caches, constructs a fresh owner and opens all four formats. Android-host PDF uses an injected engine fixture; Desktop additionally uses real PDFBox parsing/rendering. Neither is a physical Android test.

Headless shared Compose controls are checked for explicit consent, cancellation, dialog lifetime, deletion confirmation, three locales, light/dark themes, 320 dp width and 180% fonts with scrollable content and a visible 48 dp Close action. Existing full reader, translation, security, organization, navigation, update and localization suites remain required.

Physical Android checklist (realme C55, pending):

1. Install the development APK over the existing app with the same signer. Do not uninstall or clear data. Verify previous Library, favorites, tags, History, progress and translation models.
2. In a permitted Internet Archive result, add/remove the Library bookmark and confirm no download starts. Open its download arrow, inspect TXT/PDF offers, size and rights, then explicitly consent. A Gutenberg result must not offer acquisition; an imported file must not be duplicated.
3. Monitor Settings → Downloads. Cancel a transfer; check it is not offline-ready. Retry explicitly, switch tabs during transfer, and verify one transfer and stable navigation. Put the app in background: cancellation is expected; returning must not silently retry.
4. Complete a small authorized TXT/PDF copy. Enable airplane mode with Wi-Fi disabled, force-stop and reopen the app, then open from Downloads or Library. Verify real local reading, History and same-format position after closing/reopening. Turn network back on before requesting another download.
5. Change Favorite/status/tags and combine Downloaded with existing filters. Delete the retained copy with confirmation: Library/History/progress stay. Remove a different downloaded publication from Library: its copy stays in Downloads and remains readable offline.
6. Retry after network interruption; test quota and insufficient-space messages without deleting user files. A failed/incomplete copy must never show the offline checkmark.
7. Check English/Spanish/Catalan, light/dark, narrow/landscape, 150–180% fonts, TalkBack/keyboard and close/back. Compare Home/Library scrolling with prior APK; no frame-time improvement is claimed by this feature.

Automated totals, final build/artifact results and commit references follow.

## Initial delivery verification and APK — 2026-10-27

Verified executable/test source: `253820cf74a7980a2841feb66bb4bd76271201a1`. The final documentation-only commit may follow it; application/core/platform/build/test source hashes were frozen and checked before signing.

| Suite / check | Result |
| --- | --- |
| Desktop | 1681 passed; no failures/errors/skips |
| Android-host | 1404 passed; no failures/errors/skips |
| Core JVM | 59 passed; no failures/errors/skips |
| Core Android-host | 59 passed; no failures/errors/skips |
| Android debug APK + packaged-language guard | Passed |
| Desktop compilation | Passed |
| Localization | 538 strings and 3 plurals in en/es/ca; 188 Kotlin files scanned |
| git diff --check | Passed |

**3203 test invocations passed, no failures/errors/skips**. This adds 32 unique test cases (28 shared and four Desktop-specific), 60 extra invocations over the merged baseline. The final combined task run completed in 12m18s in the managed Linux environment. An earlier complete run also passed before the last storage-recovery regressions. Initial focused compilation/test failures while developing fixtures were corrected; no existing assertion/test was weakened or disabled.

Reproduce with the repository Gradle tasks:

```sh
./gradlew --continue :core:jvmTest :core:testAndroidHostTest :app:desktopTest :app:testAndroidHostTest :androidApp:assembleDebug :androidApp:verifyDebugLanguageResources :desktopApp:compileKotlin
python3 tools/check_localization.py
git diff --check
```

The managed run used JDK 21, the installed Android SDK and the existing external headless/native-test configuration; it did not add machine-specific proxy/certificate settings to application code. Neither emulator nor physical Android execution was available. There is no claim of physical offline acceptance or realme frame-time/memory measurements.

Development APK: `INFINILECT-PR36-253820c-development.apk`, 21116470 bytes. SHA-256: `87a302fe3efd6634b29641329769ea663730e837da57a6db12bf3d5b7593165d`. Package `org.infinilect.app`, minSdk 26, unchanged development versionCode 1 / versionName 0.0.1-SNAPSHOT. Certificate SHA-256 matches the existing development installation: `95c708e6cfee94bc13ecf34d2a38cf4f1dc5eab185133f8e139629dd0be470fe`. APK v2 signature and 16 KiB alignment verified; six en/es/ca string/plural payloads match generated resources; six native libraries are byte-identical to the PR #35 baseline. Permissions are unchanged. Signing inputs and new test fixtures are absent from the APK and repository.

The APK is delivered separately in the user's private Drive; readback confirms the exact expected size and owner-only permissions. No public release or public sharing was created. Install over the prior app without uninstalling/clearing data. **Draft remains pending physical acceptance.**

## Investigación de la descarga de `RepublicanismMarxism` — 2026-10-28

### Evidencia y límites del diagnóstico

El informe físico describe un círculo con exclamación en los detalles. Es `LibraryGlyph.ERROR`: `DownloadAction` lo selecciona cuando el registro pasa a `DownloadStatus.ERROR`. El trabajador de `OfflineDownloads` llega a ese estado cuando falla `backend.download`; no identifica por sí mismo si hubo un problema HTTP, de autorización, disco o validación. Antes de esta corrección, la explicación solo estaba en Ajustes → Descargas. Los detalles no observaban ni presentaban ese error persistido. Además, `FileOfflineDownloads.offers`, la apertura y las lecturas capturaban cualquier excepción recuperable del proveedor como `CONNECTION`, perdiendo las distinciones anteriores.

Se consultó el endpoint oficial `https://archive.org/metadata/RepublicanismMarxism`. Declaraba `mediatype=texts`, licencia CC0 1.0 y ningún indicador de préstamo/acceso restringido de los comprobados por el adaptador. Ofrecía:

| Archivo | Formato reconocido | Bytes declarados |
| --- | --- | --- |
| `AestheticaFascisticaI-MassimoMorigiMarxismoRepubblicanesimo_djvu.txt` | DjVuTXT → TXT | 86.957 |
| `AestheticaFascisticaI-MassimoMorigiMarxismoRepubblicanesimo.pdf` | Text PDF → PDF | 384.189 |

Ambos cumplían las comprobaciones existentes de oferta. La declaración de la fuente no es una garantía independiente sobre los derechos del depositante ni sobre todas las jurisdicciones. Se conservan las restricciones de acceso y de archivo, sin scraping ni elusión.

La prueba inicial, con el código de transferencia anterior a estos cambios, descargó y validó **ambos archivos completos** usando `InternetArchiveSource` y `FileOfflineDownloads` reales en Linux/Desktop. Cada flujo recibió metadatos HTTP 200 y entrega 302 → 200; la redirección coincidía con una ubicación anunciada por los metadatos oficiales (`dn760009.eu.archive.org/0/items/RepublicanismMarxism`). No fue necesario ampliar hosts, formatos ni permisos. La configuración de proxy y certificados del entorno se aplicó externamente a la JVM; no se incorporó a la aplicación. Un primer intento sin esa configuración falló antes de recibir HTTP, por lo que no se utiliza como evidencia del fallo Android.

**No se ha reproducido ni confirmado la causa de la transferencia fallida del realme C55.** Los defectos demostrados son la pérdida de categoría del error y su presentación insuficiente. No se presenta esta corrección como solución verificada de un fallo específico de red o almacenamiento en Android.

### Cadena real y cambios

`PublicationPresentation` / `PublicationOrganizationActions` → `DownloadAction` → `OfflineDownloads.offers/enqueue` → `FileOfflineDownloads.offers/pending/download` → `InternetArchiveSource.downloadOffers/openDownload` → `ArchiveMetadata.item` / `ArchiveUrls.redirect` → stream privado → validadores existentes → renombrado y registro atómicos.

- `ArchiveMetadata.item` conserva exactamente la admisión anterior, y añade un motivo tipado cuando no hay oferta: restricciones, derechos no verificados o ausencia de un formato compatible. No concede nuevos derechos.
- Las rutas específicas de descarga de `InternetArchiveSource` conservan HTTP/categoría sin incluir mensajes del servidor. El lector original mantiene su contrato de errores. Una interrupción del cuerpo de Archive se distingue de una respuesta/documento inválido.
- `FileOfflineDownloads` propaga `DownloadException` en lugar de convertirla siempre en conexión; conserva cancelación, límites, validación y limpieza de `.part`.
- Los detalles muestran el error persistido en texto accesible, debajo de la fila compacta de acciones, con la misma traducción usada por Descargas. El icono conserva la acción de reintentar y expone la causa mediante semántica accesible. Las denegaciones al resolver ofertas abren el panel desplazable existente, con Cerrar siempre accesible.
- Se añaden tres mensajes en inglés/español/catalán: rechazo HTTP, derechos no verificados y formatos no admitidos. Los modelos de traducción no cambian.
- No cambian actualizador, permisos, identificador, firma, dependencias, esquema de Biblioteca, archivos importados, lectores ni presupuestos de almacenamiento/concurrencia.

### Diagnóstico local sin datos privados

`AndroidLocalDiagnostics` conecta el gestor al mismo sumidero optativo ya existente. Sigue limitado a compilaciones de desarrollo, desactivado por defecto, local, sin servidor; desactivarlo borra el registro. Se mantienen 128 líneas / 40.000 bytes y la escritura agrupada existente. No hay eventos por bloque de datos/fotograma. El registro solo recibe vocabulario cerrado y números, nunca identificadores de publicación, títulos, texto, consultas, URLs, rutas, credenciales ni mensajes completos de excepciones.

Eventos nuevos: `DOWNLOAD_STARTED`, `DOWNLOAD_RESOLVING`, `DOWNLOAD_OPENING`, `DOWNLOAD_TRANSFERRING`, `DOWNLOAD_VALIDATING`, `DOWNLOAD_COMMITTING`, `DOWNLOAD_HTTP_ERROR`, `DOWNLOAD_UNSUPPORTED_FORMAT`, `DOWNLOAD_RIGHTS_UNVERIFIED`, `DOWNLOAD_STORAGE_ERROR`, `DOWNLOAD_COMPLETED`, `DOWNLOAD_FAILED`, `DOWNLOAD_CANCELLED` y `DOWNLOAD_CAUSE`.

En estos eventos, `g` indica una **fase técnica**, no un identificador de publicación:

| g | Fase |
| --- | --- |
| 0 | Notificación del controlador, sin fase específica |
| 1 | Resolución / nueva comprobación de autorización |
| 2 | Preparación o persistencia del almacén |
| 3 | Apertura HTTP del recurso |
| 4 | Transferencia al archivo privado |
| 5 | Validación mediante el lector |
| 6 | Publicación atómica del archivo completo |

En `DOWNLOAD_STARTED` y `DOWNLOAD_VALIDATING`, `n` identifica únicamente el formato: 1=TXT, 2=EPUB, 3=CBZ, 4=PDF (0=otro). En `DOWNLOAD_HTTP_ERROR`, `n` es el estado HTTP (100–599; 0 si no se conoce). En `DOWNLOAD_FAILED`, `n` es la categoría estable: 1=no permitido, 2=conexión, 3=incompleto, 4=inválido, 5=límite, 6=espacio, 7=almacenamiento, 8=archivo ausente, 9=interrumpido, 10=en uso, 11=HTTP, 12=derechos no verificados, 13=formato incompatible. `DOWNLOAD_CAUSE` añade únicamente una causa enumerada: 1=IO, 2=acceso, 3=operación no soportada, 4=renombrado atómico no soportado, 5=DNS, 6=TLS, 7=timeout, 8=datos inválidos. 0 significa desconocida. Un mismo fallo puede emitir tanto detalle de backend como notificación final del controlador; no implica dos descargas. No se interceptan errores fatales como si fueran recuperables.

### Reproducción y validación física pendiente

`./gradlew :app:archiveOfflineDownloadCheck` es una prueba **explícita de red**, independiente de test/check/build. Solo prueba la publicación comunicada, rechaza ofertas no admitidas, descarga TXT/PDF serialmente y no imprime contenido. Tras descargar, reconstruye el almacén y vuelve a validar la copia con la fuente cerrada. El PDF usa PDFBox real en Desktop. No es una prueba de Android ni una prueba de modo avión físico. En un entorno con proxy/CA, configurar la JVM externamente sin desactivar TLS.

Las pruebas deterministas usan HTTP simulado con el adaptador, almacén y validadores reales; los documentos de prueba son originales/sintéticos. Cubren estados HTTP de metadatos y contenido, derechos, restricciones, formato EPUB no ofrecido por este adaptador, JSON/MIME inválidos, respuesta truncada, ausencia de parciales disponibles, reinicio y lectura TXT sin solicitudes remotas. Las pruebas offline de EPUB2/EPUB3 existentes comprueban documentos originales autorizados, reinicio, lectura/progreso e integridad de Biblioteca con el proveedor de prueba inaccesible. **No son una descarga EPUB de un catálogo público**: el adaptador público actual sigue ofreciendo únicamente TXT/PDF. No se ha inventado una oferta EPUB para cumplir una prueba.

Checklist del realme C55, una vez disponible una APK autorizada para su instalación:

1. No desinstalar ni borrar datos. La investigación separada de Play Protect continúa; no desactivar ni ignorar una advertencia de aplicación dañina.
2. En Ajustes → Diagnóstico de desarrollo, activar «Registrar diagnóstico local» antes de probar.
3. Abrir `RepublicanismMarxism`, pulsar Descargar, comprobar las ofertas reales y elegir TXT. Confirmar explícitamente. Repetir por separado con PDF, eliminando solo la copia descargada entre formatos si corresponde.
4. Si falla, leer el mensaje bajo las acciones; ir directamente a Ajustes → «Ver y copiar diagnóstico», actualizar y copiar el informe. Conservar especialmente las líneas `DOWNLOAD_*`. La ausencia de eventos previos a activar el registro es normal.
5. Cancelar una descarga pendiente/en curso y reintentar. Cambiar de pestaña debe conservarla; pasar Android a segundo plano la cancela según el contrato de esta PR. Una copia incompleta nunca debe aparecer disponible sin conexión.
6. Tras completar, activar modo avión y desactivar Wi-Fi, cerrar/reabrir la aplicación y abrir desde Descargas/Biblioteca. Verificar lectura y posición tras volver a abrir. Esta comprobación física sigue pendiente.
7. Confirmar que eliminar solo la descarga mantiene Biblioteca, Historial, posición, favoritos y etiquetas. Revisar mensajes en es/en/ca. Desactivar el diagnóstico al terminar para borrar el informe.

### Resultado de la verificación de esta corrección

| Comprobación | Resultado |
| --- | --- |
| Suite Desktop completa | 1.692 aprobadas; 0 fallos/errores/omitidas |
| Suite Android-host completa | 1.413 aprobadas; 0 fallos/errores/omitidas |
| Core JVM | 59 aprobadas; 0 fallos/errores/omitidas |
| Core Android-host | 59 aprobadas; 0 fallos/errores/omitidas |
| APK Android debug | Compilación correcta |
| Recursos empaquetados | Verificados en/es/ca: strings y plurales |
| Compilación Desktop | Correcta |
| Android Lint | 0 incidencias |
| Guard de localización | 541 cadenas y 3 plurales en los tres idiomas; 189 archivos Kotlin revisados |
| `git diff --check` | Correcto |

Total: **3.223 ejecuciones aprobadas**. Se añaden 11 casos (nueve compartidos y dos de interfaz Desktop), 20 ejecuciones adicionales. Se mantuvieron todas las pruebas anteriores. Durante el desarrollo, una regresión detectó la conversión errónea de un cuerpo truncado a error de conexión; la envoltura específica de lectura lo conserva ahora como incompleto. Otra comprobación existente detectó que el aviso de diagnóstico y su fallback inglés no coincidían; se corrigió el recurso/fallback y se ejecutaron de nuevo las cuatro suites completas, sin debilitar la aserción. El último conjunto completo de tareas terminó con `BUILD SUCCESSFUL`.

Comando de verificación, con el JDK/SDK y las bibliotecas nativas de pruebas configurados externamente en este entorno:

```sh
./gradlew :app:desktopTest :app:testAndroidHostTest :core:jvmTest :core:testAndroidHostTest :androidApp:assembleDebug :androidApp:verifyDebugLanguageResources :desktopApp:compileKotlin :androidApp:lintDebug
./gradlew :app:archiveOfflineDownloadCheck
python3 tools/check_localization.py
git diff --check
```

La comprobación de red posterior descargó otra vez **86.957 bytes TXT y 384.189 bytes PDF**, validó ambos, cerró la fuente y reconstruyó el almacén para validar las copias locales. Es evidencia Linux/Desktop con el motor de red y PDFBox de esa plataforma, no una instalación Android, un ensayo de modo avión en el realme ni una prueba de su motor PDF nativo.

Se comparó el manifiesto combinado de la APK debug recién compilada con la APK de desarrollo anterior de PR #36: son idénticos, incluyendo componentes, paquete, SDK y permisos. Las seis bibliotecas nativas también son idénticas byte a byte. No hay cambios de dependencias, configuración de firma ni código del actualizador. No se han utilizado las claves privadas del usuario, firmado una nueva entrega ni publicado una release en esta intervención. La causa específica del fallo Android y la instalación/validación física siguen pendientes; el diagnóstico nuevo permite obtener la evidencia sin PC.

### Evidencia física adicional: consulta de espacio libre en Android

El informe posterior muestra dos intentos PDF (`DOWNLOAD_STARTED n=4`), seguidos de `DOWNLOAD_STORAGE_ERROR g=2 n=7` y `DOWNLOAD_CAUSE g=2 n=2`. No aparece `DOWNLOAD_OPENING`: el fallo ocurre antes de solicitar los bytes. `n=2` identifica acceso/seguridad, no falta de espacio ni rechazo HTTP. Las líneas repetidas son backend + controlador, no prueba de dos transferencias simultáneas.

Se identificó una llamada incompatible con Android en la admisión de espacio: el valor por defecto de `FileOfflineDownloads.freeSpace` usaba `Files.getFileStore(path).usableSpace`. Android libcore rechaza `getFileStore` con `SecurityException`; esta misma limitación ya estaba documentada y evitada en `privateProgressPermissions`, los ajustes de lectores y el preparador TXT. La creación del registro pendiente y las operaciones previas de directorio habían funcionado; la consulta nueva de FileStore ocurre precisamente en la fase 2 indicada por el informe.

Se sustituye únicamente esa consulta por `path.toFile().usableSpace`, API de archivo compatible con Android. Se conserva la comprobación de tres veces el tamaño más 8 MiB, los límites, el trabajo en IO y el rechazo conservador si el espacio es cero/desconocido. No se solicita permiso de almacenamiento ni se altera el actualizador (su revisión es independiente). Se ha identificado y eliminado este defecto de compatibilidad; todavía no se afirma que el realme haya validado la corrección ni que se hayan descartado fallos posteriores de transferencia/validación.

Verificación de la corrección de espacio: desktopTest: {'tests': 35, 'failures': 0, 'errors': 0, 'skipped': 0}; testAndroidHostTest: {'tests': 35, 'failures': 0, 'errors': 0, 'skipped': 0}. Compilaciones Android debug y Desktop y recursos empaquetados correctos; `git diff --check` correcto. Estas son suites enfocadas de descarga/autorización, no una nueva ejecución completa de las cuatro suites anteriores. Validación física pendiente.
