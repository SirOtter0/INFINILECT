# Offline publication downloads — PR #36

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

## Final automated verification and APK

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
