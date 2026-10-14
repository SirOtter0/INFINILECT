# Local EPUB translation

PR #31 continues the original audit at `9b516669ed4b201a6b79f79c17dfae3be6204dc2`.
Base: `b08379c6a5072f7114685e4a4c9444e60edcdcc9` (merged PR #30).
Status: **Android implementation, native cross-build and real Linux ARM/QEMU inference;
physical Android inference/acceptance pending**. Desktop original reading remains
fully operational; its translation engine is explicitly unavailable. Test providers
are used only for deterministic shared/UI tests, never as native-engine evidence.

## Provider decision and licensing

Choose **Foxlet 0.5.0**, an Android adaptation of Mozilla's Bergamot/Marian engine.
Pin upstream source `0eff2a15032362115848a496ce1df8ed9fc68650` and AAR SHA-256
`5dbb384907d69c2aba129fd15dba30dda94ede0fa556dc6a167d041ee479ec0e`.
The generated SDK is downloaded/verified by `prepareEpubTranslationSdk` under
`app/build/translation-sdk/`; no binary or model is tracked. Offline builds can
supply the exact verified AAR using `-Pinfinilect.foxletSdk=/absolute/path/to/foxlet-v0.5.0.aar`.
A checksum mismatch is a build error, not an accepted alternative SDK.

| Candidate | License and model boundary | Verified platform/maintenance findings | Decision |
| --- | --- | --- | --- |
| Original browsermt Bergamot | Engine MPL-2.0; Marian MIT; models separately licensed | Inspected head `9271618ebbdc5d21ac4dc4df9e72beb7ce644774`, last upstream push May 2024. Native/WASM API, not an Android Kotlin SDK. Current Mozilla development lives in `mozilla/translations/inference`. | Use the maintained engine adaptation, not assume Firefox's library is directly Android-ready. |
| Foxlet 0.5.0 | Original wrapper MIT; Bergamot MPL-2.0; Marian MIT; SentencePiece/ruy Apache-2.0; BSD/MIT/zlib/Unicode-3.0 and LLVM runtime licenses retained in its notices | Release September 2026; project is young. ARM64 only, SDK min API 28, explicit Kotlin/JNI lifecycle/model management. Built Android ARM64 and Linux ARM from pinned source; real models translated under QEMU. | Selected, with two pinned direct language pairs and guarded Android availability. |
| CTranslate2 | Engine MIT, CPU dependencies include Apache-2.0 ruy/cpu_features and MIT spdlog/cxxopts; engine does not license weights. Helsinki-NLP OPUS-MT en-es model card separately declares Apache-2.0. | Inspected head `8d91aa08225004755360daa43deb62b35b1ed65d`, active October 2026; official Linux x86/ARM64 and Windows x86 distributions. Android requires native cross-build, tokenizer/model conversion and Kotlin bindings; no such adapter was verified here. Default MKL/GPU binary redistribution was not audited or selected. | Viable future Desktop investigation, not silently substituted for this Android implementation. No CT2 model is bundled or used. |
| Android framework TranslationManager | Public system API; installed provider/model terms are independent | API 31+, advertised provider capabilities required. Not universally present on supported Android devices; no Desktop equivalent. | Not a universal fallback; not integrated. |
| ML Kit | SDK/models under its own Terms of Service, not the samples' Apache license | Prior audit preserved below. Current GPL distribution compatibility not established. | Not integrated; no linking exception or license change. |

The pinned Foxlet component index and complete bundled notices were inspected.
The selected MPL-covered code has no separate Exhibit B declaration marking it
incompatible with secondary licenses. MPL 2.0 section 3.3 permits combination with
GPL larger works subject to its source/notice requirements. INFINILECT stays
GPL-3.0-or-later; upstream files are not relicensed. The SDK packages complete
copyright/license texts, NOTICE and corresponding-source offer in Java resources,
which must remain in distributed APKs. No paid API, key or proprietary ML Kit
binary is included.

**Models are separately licensed.** Mozilla's model repository carries MPL-2.0;
a Mozilla collaborator explicitly confirms that current released weights use
MPL-2.0 in [issue 1434](https://github.com/mozilla/translations/issues/1434#issuecomment-4734030816).
Remote Settings records themselves currently lack a license field. This decision
relies on that primary statement and the model repository license, not merely on
free downloads or the wrapper's MIT license. Future models/providers require a
fresh distribution/license check; this is not a universal legal guarantee.
Preserve model attribution, source and MPL text when redistributing model files.

Primary sources:

- [Bergamot](https://github.com/browsermt/bergamot-translator), [MPL](https://github.com/browsermt/bergamot-translator/blob/9271618ebbdc5d21ac4dc4df9e72beb7ce644774/LICENSE)
- [Foxlet pinned source](https://github.com/yinvoke/foxlet-translate/tree/0eff2a15032362115848a496ce1df8ed9fc68650), [MIT](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/LICENSE), [NOTICE](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/NOTICE), [component license index](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/licenses/components.json), [vendor provenance](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/engine/UPSTREAM.md)
- [Foxlet release/checksums/source offer](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0)
- [Mozilla model license](https://github.com/mozilla/firefox-translations-models/blob/main/LICENSE), [pinned model registry](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/registry.json)
- [MPL larger works/secondary licenses](https://www.mozilla.org/MPL/2.0/)
- [CTranslate2 MIT](https://github.com/OpenNMT/CTranslate2/blob/8d91aa08225004755360daa43deb62b35b1ed65d/LICENSE), [installation/platforms](https://opennmt.net/CTranslate2/installation.html), [OPUS-MT model card](https://huggingface.co/Helsinki-NLP/opus-mt-en-es)
- [Android TranslationManager](https://developer.android.com/reference/android/view/translation/TranslationManager)

## Implemented architecture

`ApplicationSources` owns one `EpubTranslationService`, private result cache and
Android engine. Reader/presentation disposal cancels its own projection jobs but
does not close the retained application engine. Final application close rejects
new work, drains native work, releases models and closes the SDK. Shared contracts
contain no Context/JNI/engine types; another engine implements `EpubTranslationEngine`
and is supplied by the platform factory without rewriting the reader.

`OpenPublicationController` passes the source-authorized resource byte revision
into the EPUB controller. Import SHA-256 revisions are reused; titles/OPF IDs are
never content fingerprints. Unknown revisions bypass persistent result reuse.
`EpubTranslationController` owns one replaceable request, current semantic
projection and selection panel state. Publication, passage, language, Original,
Cancel and close retire stale jobs. A noninterruptible submitted native batch
finishes before the next can own the single inference permit; Kotlin cancellation
is **not** advertised as immediate native interruption. Its obsolete result cannot
publish, save progress or populate the cache.

The original `EpubChapter` stays authoritative. Translation builds an independent
list of projected runs, preserving original blocks, heading/list/quote kinds,
strong/emphasis flags, image references, owned internal-link targets, notes and
reading order. It translates plain text only (`TextFormat.Plain`), never XHTML,
IDs, resource paths or attributes. Explicit run provenance excludes synthetic
image messages and nested code/kbd/samp text; the private session semantic codec
retains this flag. Preformatted content and literal-URL runs stay original.
This deliberately translates each styled run independently: sentence context and
word reordering across formatting boundaries can reduce quality. It does not
promise exact typographic or word-alignment fidelity. Unsupported/oversized or
failed projection keeps Original accessible, with a clear pending/error state.

Public Compose `SelectionState.selectedTexts` captures selected annotated
fragments in layout order without reading the clipboard. Selection is scoped to
the reader/source window and cleared on replacement; image fallback messages are
excluded. Android's native text context menu adds **Translate**; an equivalent
custom accessibility action uses the same capture. Selection is limited to 2,048
Unicode code points, shows the real language pair/result, Copy and Close. Copy
writes only the result to the clipboard. Translated selections require returning
to Original; arbitrary language detection is not implemented.

Reader settings provide Original/Translated, supported language-pair choices,
model status/download/delete, Cancel/Retry and cache clearing. These live inside
the existing reader panel, not permanent artwork/text chrome. Pending passage
translation is explicitly labeled as showing Original. Settings opening does not
restart an active translation, reparse the EPUB or reopen its publication.
Application-resolved appearance is passed to EPUB System mode; explicit reader
Light/Dark preferences retain their meaning. Existing Back/focus/panel contracts
and Android appearance restoration remain in use.

## Languages, model installation and privacy

First version: **English→Spanish (2.1)** and **Spanish→English (2.0)** only. EPUB
language metadata suggests the source when recognized; unsupported/absent metadata
requires an explicit supported pair. The destination is persisted; matching source/target
keeps Original rather than silently selecting another destination. Manual source overrides
can be reset with Use EPUB language metadata. No fake detection or pivot is offered.

- en-es: 36,576,277 bytes; es-en: 37,014,089 bytes, including vocabulary/shortlist.
- Exact asset URLs, sizes and SHA-256 values come from Foxlet's pinned Mozilla
  registry; the adapter also verifies that its SDK descriptors match those pins.
- Opening an original EPUB does not download or create a native translator.
  Download requires tapping **Download model**, after seeing the approximate size.
- Downloads use verified HTTPS to Mozilla's attachment host; no redirects, insecure
  trust exceptions, automatic updates or arbitrary model URLs are added.
- Installed models are verified and stored in Android `noBackupFilesDir` under a
  dedicated owned namespace. Delete unloads native ownership first; failed deletion
  is an error. Cancelled temporary downloads are cleaned, and repairs retire damaged
  copies before creating a replacement. Models are not included in the APK.
- Model downloads reveal ordinary network information such as IP/User-Agent and
  requested model pair to Mozilla. They send no book text, profile, history or
  reading locator. Inference uses local files; no translation telemetry or remote
  text service is added. Book text/results are not logged.

Translation availability requires Android 9/API 28+, a 64-bit ARM process and a
non-low-RAM device. **The application minimum remains API 26**: only this optional
SDK's manifest minimum is overridden, with the feature guarded before SDK/native
creation. Original readers and other existing ABIs remain supported. The APK keeps
the existing graphics native libraries for all four ABIs. Desktop, older Android,
32-bit and low-RAM devices show an honest unavailable state and retain original
reading. Foxlet recommends at least a 4 GB device; real INFINILECT memory/performance
on representative Android hardware remains an acceptance task.

## Bounds, cache and position

- Source windows remain 128 blocks/65,536 UTF-16 units, with two source windows
  retained. The displayed rolling chapter may contain 256 blocks/131,072 units.
  XML/ZIP/security budgets and source ownership do not increase.
- Translation rejects oversized source presentations up front. One native worker,
  one loaded model and one active inference permit; no book-wide task queue,
  background chapter translation or neighboring translated-window cache.
- A run request is at most 8,192 code points, split into at most sixteen 2,048-point
  segments, preferring word boundaries; a segment result is at most 8,192 UTF-16 units. One complete active
  projection retains at most 256 blocks/262,144 translated UTF-16 units. Old
  projections release on replacement. A partial projection is not presented as final.
- This implementation prioritizes the current bounded passage, then displays its
  complete validated projection. While it is cold, Original is readable. It never
  requires translating all chapters first; every window can be translated as read.
- Private result cache: **4 MiB including writes, 256 records**, SHA-256 names and
  payload checksums, strict UTF-8/length decoding, LRU eviction, no symlink reads,
  owner/OS locks and atomic same-directory replacement. Cache keys include
  publication/resource identity, byte revision, exact original segment text,
  provider/config version, source/target and pinned model asset fingerprints.
  Corruption/storage failure becomes a cache miss; it cannot change the EPUB.
- Cache clearing invalidates only translation records. Model delete and cache clear
  leave publications, library, history and saved progress alone. The cache is
  disposable application-cache storage; installed models survive Android cache
  clearing and normal restarts.
- Model disk budget: **120 MiB including staged downloads, 128 filesystem entries**,
  bounded traversal with no symlink following. Only two pinned pairs are installable;
  one download runs at a time and reserves its descriptor size before construction.
- The existing typed `ReadingLocator.Epub` remains the durable location: original
  spine path, element-child ordinal path, Unicode offset and chapter progression.
  Each projected run maps source/display code points proportionally; endpoints and
  run boundaries are exact, within-run word placement is approximate. Mode/font/
  viewport changes restore the live original anchor and retire old UI tickets.
  Translation starts, partial output, failures and cancelled requests never commit
  a destination. Only the reader's valid presented/scroll callbacks can save progress.
- Existing preferences migrate from the checksummed 68-byte v1 record to an 80-byte
  v2 record, preserving typography/theme/timestamps and adding language/mode fields.
  No database, duplicate settings owner or progress-schema migration is introduced.

## Native proof and verification

Pinned source was compiled with CMake 4.4.4 (managed tool package), NDK r29
`29.0.13113456` for Android ARM64/API 28, and Debian GCC 14 for Linux ARM64.
Android JNI `libfoxlet.so` and smoke executable compiled; ELF load segments have
16 KiB alignment. The production AAR is the independently checksummed upstream
release, not an untracked replacement of its binaries.

Real en-es and es-en assets were downloaded **for this explicitly requested research**
outside the repository, with every size/hash verified. Linux ARM inference ran under
QEMU 10.0.13 with local model paths and no networking permission. Three original
synthetic sentences translated in each direction. Example en-es:
“The reader remembers your place in the book.” → “El lector recuerda tu lugar en el libro.”
The two en-es passes had matching output hashes. The upstream lifecycle probe
reported `live_models=0`, `live_graphs=0`, `release_reported_destroyed=yes` and a
matching output after reloading. A separate opt-in Kotlin JVM task invokes this
real ARM executable and validates results; this proves a local Kotlin/process
prototype, **not execution of Android's JNI adapter**.

QEMU/managed Linux, one worker, three short sentences: load 1,809 ms; first inference
1,882 ms; repeated inference 943 ms; peak RSS about 140 MiB. Emulator RSS and speed
include its environment and cannot establish phone PSS, latency or quality for a
full reading window. Foxlet's own device benchmarks are upstream evidence, not
INFINILECT device verification. No physical Android or native Desktop graphical
acceptance is claimed.

Opt-in proof (requires separately built/verified native executable and model):

```sh
gradle :app:epubNativeTranslationCheck --args='/absolute/qemu-aarch64 /absolute/arm-sysroot /absolute/smoke /absolute/en-es/config.yml'
```

Final verification used JDK 21 and the existing Gradle configuration:

| Check | Result |
| --- | --- |
| Focused translation, selection UI, import and CBZ lifecycle: Desktop | 125 passed, 0 failed/errors/skipped |
| Same focused coverage: Android-host | 121 passed, 0 failed/errors/skipped |
| Complete Desktop application regression | 1,491 passed, 0 failed/errors/skipped |
| Complete Android-host application regression | 1,268 passed, 0 failed/errors/skipped |
| Android debug application assembly | Passed |
| Desktop application Kotlin compilation | Passed |
| Opt-in Kotlin/native real-model proof | Passed; three synthetic sentences, offline |

Production/test/build fingerprints remained unchanged throughout the final run.
`git diff --check` and tracked/nonignored artifact/signing-secret checks passed.
Core is unchanged; unrelated core suites were not rerun. Coverage includes selection
UI, projected run structure, Unicode mappings, progressive long chapters,
cancellation/stale work, retry, language changes, model budget, private cache
corruption/eviction/cleanup, preference migration and durable progress.
Deterministic fake-provider tests are separate from the native proof above. Original
EPUB2/EPUB3, security, TEXT/PDF/CBZ, library/history/search tests remain enabled.

## Regression cleanup correction

The second full Android-host run exposed a pre-existing CBZ resource-registry race
in `LocalImportReaderIntegrationTest`: `CbzDocument.close()` snapshots handles under
its ownership monitor, while a render/decoder close removed a handle without that
monitor. The same callback is present at the original PR #31 head and merged main.
A deterministic gated-set/thread test reproduces the actual
`ConcurrentModificationException` before the fix. Handle unregistration now uses
that same existing monitor (one production-line change). The test instruments only
our own registry and requires successful/idempotent shutdown, empty ownership and
closed handles; it adds no production hook. Reading, decoding, gesture behavior,
archive validation and resource limits are unchanged. This correction is necessary
for regression acceptance, not a comic-reader redesign.

## Android acceptance (pending)

1. Upgrade using the matching development signing identity; retain existing library,
   profile and saved positions. Open existing EPUB2/EPUB3 publications deep in a chapter.
2. Reading settings: choose a supported source→target pair, inspect size, tap Download;
   check progress, cancellation/retry, installed status and Delete. Never install a model
   merely by opening a book. Test network/storage failure without blocking Original.
3. Select a word, Unicode phrase and mixed-format paragraph. Tap Translate in the
   native selection toolbar; verify real output/languages, Copy and Close, unchanged
   reading location and no image/error text entering the selection.
4. Choose Translated; confirm replacement **inside** paragraphs/headings, preserved
   illustrations/links/notes, and a clear Original fallback while pending or on failure.
   Return to Original. Code/URLs remain original; mixed-run quality may differ.
5. Read beyond 2,048 blocks, forward/backward across windows and TOC/internal anchors.
   Rapidly change passages and language; late responses must not replace newer content.
6. Download both supported pairs explicitly. Enable airplane mode and repeat selection
   and reading translation. Revisit translated passages, close/reopen, change typography
   and rotate; verify the same original logical place and reusable private cache.
7. Clear translation cache and delete a model separately. Check unchanged EPUB bytes,
   library/history/progress; retranslation after cache clearing still works offline if
   its model is installed. Check light/dark/automatic controls and Back/exit restoration.
8. Verify representative device RAM/latency and nontranslation readers. Report device,
   Android version and ABI; automated host tests are not this acceptance.

## Known limitations

Only two verified direct pairs; no automatic detection, whole-book export, exact word
alignment, translation of preformatted/URL runs or engine-backed Desktop support.
Within-run locator restoration is approximate. Long mixed-style sentences can lose
context because runs translate separately. Cold window translation can take time;
this PR does not promise device latency. Cancel discards a native result and waits
for its bounded batch to drain rather than interrupting C++ mid-operation. Desktop
x86/Windows reuse requires a separate native backend/build/tokenizer investigation.
The young upstream SDK and model-license statement should be monitored; no
unrestricted model update or inference server fallback is enabled.

## ML Kit: verified technical and distribution findings

The official Maven metadata and Android guide identify
`com.google.mlkit:translate:17.0.3`. Its minimum Android API is 23; INFINILECT's
minimum is 26, so that particular requirement is compatible. The interface has
`downloadModelIfNeeded`, `translate(String)` and `close`. Neither asynchronous
method accepts a cancellation token in this inspected API. Cancelling a coroutine
alone must not be described as cancelling underlying native/model work; a future
adapter must serialize ownership, reject stale results and close safely.

The published POM declares **ML Kit Terms of Service**, not Apache-2.0. Those
terms say:

> When using the ML Kit APIs, you may not reverse engineer or attempt to extract
> the source code or any related software, except to the extent that this
> restriction is expressly prohibited by applicable law.

The API terms also impose restrictions on activities/purposes. An Apache license
in the documentation footer applies to **code samples**, not automatically to
the SDK. The inspected AAR contains 124 third-party notice entries, but no entry
licensing the ML Kit/translation implementation itself under an alternative free
license. The inspected SDK artifact is 27,684,160 bytes, SHA-256
`b6194f7b42034309cf8299784b2d5d70a82a2e9287fbe1650dea4d1f3ad1fe55`.
It was inspected outside the repository and is not bundled or committed.

This evidence does **not establish compatibility** with distributing the SDK as
part of the current GPL application. No linking exception is assumed, added or
granted by this PR. A free provider or a properly established distribution/license
resolution is required before integrating ML Kit. This is an engineering
distribution gate, not a claim that all jurisdictions resolve the issue identically.

Privacy is also narrower than "no telemetry": the terms state that input text
and resulting output remain on-device, **and** that metrics about API performance
and utilization are sent to Google, with possible update/model contacts. Required
user disclosure cannot be omitted. No such SDK/network behavior has been added.

Models are downloaded dynamically; the guide describes language models as around
30 MB and recommends explicit, Wi-Fi downloads and deletion of unused models.
Translation between non-English languages uses English as an intermediary, with
quality limitations. Language metadata is not linguistic detection. Translation
guidelines require the applicable Google Cloud Translation attribution/branding
requirements and limit use to specified personal-computing device categories.

Primary sources inspected:

- [Published POM](https://dl.google.com/dl/android/maven2/com/google/mlkit/translate/17.0.3/translate-17.0.3.pom)
- [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms)
- [Google APIs terms](https://developers.google.com/terms)
- [Android translation guide](https://developers.google.com/ml-kit/language/translation/android)
- [Translation guidelines and attribution](https://developers.google.com/ml-kit/language/translation/translation-terms)
- [Translation behavior/quality](https://developers.google.com/ml-kit/language/translation)
