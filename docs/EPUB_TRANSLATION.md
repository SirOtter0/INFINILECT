# EPUB translation foundation — technical audit and provider decision

Status on 2026-10-14: **provider/licensing blocked; no translation feature is
implemented or enabled**. This document separates verified repository/upstream
facts from the proposed implementation. It is not an APK acceptance claim.

Base/main: `b08379c6a5072f7114685e4a4c9444e60edcdcc9`, the merged PR #30.
The project's original code remains GPL-3.0-or-later, without a project-specific
exception allowing linkage with ML Kit. No license, production code, dependency,
permission, application ID, signing or release configuration has been changed.

## Existing EPUB ownership and insertion points

- `OpenPublicationController` prepares the source-authorized EPUB, constructs
  `EpubReaderController`, restores publication-specific progress once and owns
  cancellation/close. `ApplicationSources` retains the application/session across
  presentation remounts; Compose does not own the publication.
- `BoundedEpubParser` produces `EpubChapter`/`EpubBlock`/`EpubRun` from validated
  EPUB2/EPUB3 resources. Runs already preserve emphasis, strong formatting and
  owned internal-link targets. Images, headings, lists, quotes, captions,
  separators and preformatted text have separate semantic fields.
- Production parses windows of at most 128 blocks/65,536 UTF-16 units/8,192 run
  append events, with two source windows retained. A displayed rolling chapter
  can contain both windows. Individual blocks are bounded to 8,192 text units.
  The private semantic cache keeps two indexed chapter files, at most 8 MiB per
  document including construction. Translation must reuse these windows rather
  than call the compatibility full-chapter API or retain a second whole book.
- Inline images can currently produce a synthetic `[Image ...]` marker inside a
  text run. A translation adapter must not mistake that marker for publisher text.
  If it needs explicit provenance, add a narrow application-semantic flag and
  matching session-cache encoding; do not infer provenance by matching arbitrary
  book text or loosen parser validation. This integration is still pending.
- `ReadingLocator.Epub` contains the original spine path, element-child ordinal
  path, Unicode code-point offset and a fallback chapter progression. UI line
  offsets are converted to those original positions; they are not durable pages.
  `presentationChanged` retires old UI callbacks and restores the live locator.
- Source `PublicationResource.revision` identifies bytes when available. Imported
  local resources use a SHA-256 revision. EPUB metadata identifier/title is not
  a trustworthy byte revision. The opener has the resource revision, but the
  reader constructor does not currently receive it: the future cache must pass
  this value through a narrow application-owned boundary, not change progress.
- EPUB preferences already have an owned persistence contract and a private,
  atomic/checksummed file store. Translation preferences should extend that
  system with backward-compatible decoding, not create a competing settings
  owner or add a database for convenience.
- `EpubReader` currently uses `SelectionContainer` without explicit selection
  state. The installed Compose 1.12.1 public API provides `SelectionState`,
  `rememberSelectionState` and `selectedTexts`. Selected annotated fragments can
  be captured without copying to, or reading from, the system clipboard.
  Lazy selection covers composed text nodes, not an entire unloaded chapter;
  selection can pin composed nodes. Generic loading/error/image-placeholder text
  must be excluded, and selection lifecycle must be bounded with the source window.
  An adapter must bind selection to the current publication/chapter generation,
  bound its size and clear it on replacement. It must never borrow another
  chapter's selection or accept stale translation callbacks.
- Existing panels use `EpubControlPanel`, with focus, Back/Escape and constrained
  scrolling. The EPUB reader resolves its own System appearance against
  `isSystemInDarkTheme`; translation controls will need the existing resolved
  App appearance supplied explicitly, without inventing a second theme store.

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

## Provider alternatives, not implemented

| Candidate | Verified fact | Remaining work/limitation |
| --- | --- | --- |
| CTranslate2 | Upstream engine license is MIT | Select/pin a release, audit model/tokenizer licenses, Android JNI/ABI integration, explicit model installation and measured memory/quality. No Android adapter/model is integrated. |
| Bergamot | Upstream translator license is MPL-2.0 | Audit secondary-license notices and complete dependency/model graph; Android native integration and bounded inference remain unverified. |
| Android framework translation | Public API exists in the inspected Android 37 SDK | Requires Android 12/API 31 and an installed on-device provider with advertised capabilities. It is not a guaranteed replacement on INFINILECT's supported devices; Desktop remains separate. |

Upstream license sources: [CTranslate2](https://github.com/OpenNMT/CTranslate2/blob/master/LICENSE),
[Bergamot](https://github.com/browsermt/bergamot-translator/blob/main/LICENSE).
Engine licensing does not automatically license a downloaded model/tokenizer.
No alternative provider is silently substituted, and no online service is proposed.

## Proposed implementation after the provider decision

These are **design targets**, not implemented/measured resource guarantees.

1. One application-owned provider contract describes supported language pairs,
   model availability, explicit installation, bounded text translation and close.
   Android infrastructure implements it; Desktop reports unavailable until it has
   a real provider. Reader/domain contracts expose no ML Kit/native SDK types.
2. Translate bounded original semantic runs/chunks, never XHTML/HTML strings.
   Preserve source block/run identity, paragraph boundaries, headings, images,
   list markers and internal-link targets. Skip preformatted/code-like content,
   synthetic reader messages and protected literal URLs; never translate identifiers,
   resource paths or structural attributes.
   Run-by-run translation preserves formatting boundaries but can reduce linguistic
   context and change word order: do not promise perfect mixed-style fidelity.
3. Prioritize the current window. One native inference permit, one active request
   and one latest replaceable intent; no full-book queue/background pretranslation.
   Initial targets: 2,048 input code points per segment, 8,192 output UTF-16 units
   per segment, at most two translated windows/131,072 units per window. Exceeding
   a limit is a controlled error, not silent truncation.
4. Render translated text inside the existing semantic blocks while keeping the
   original chapter model authoritative. Explicit source-to-rendered run maps
   convert displayed offsets to original Unicode offsets and vice versa. A
   proportional within-run mapping is deterministic but approximate, not alignment
   of individual words. Mode/typography changes restore the original locator.
5. Keep original content available during preparation/failure. Only complete
   validated projection replaces a window; show pending/error/retry separately.
   Selection results appear in an existing-style panel with languages, Copy and
   Close. Capturing/selecting/translating cannot itself advance reading progress.
6. Use an app-private atomic/checksummed cache, separate from imported files and
   progress. Keys include structured publication/resource identity, trustworthy
   byte revision, spine/block/run identity, source-text digest, language pair and
   provider/model revision. Unknown byte revisions must bypass persistent reuse.
   Initial target: 32 MiB including incomplete writes, at most 256 owned records,
   bounded eviction/corruption recovery. Clear affects translations only.
7. Require a genuine model fingerprint or persisted model-generation invalidation
   plus expiry when a provider cannot expose a version. Model downloads need an
   explicit user action; checks/reader opening must never install models. Retire
   old publication/language/window tickets, cache-clear epochs and owned jobs.
8. Extend existing EPUB preferences compatibly. Use supported metadata language
   as a suggestion, manual override and genuine provider-supported pairs. No fake
   automatic detection, paid API, account, text telemetry or remote translation.
   Selection from a translated view uses its actually displayed language as the
   source suggestion, not the original EPUB's metadata language.

## Verification and acceptance status

The two new **Desktop headless selection feasibility tests passed**, using public
Compose APIs with original synthetic text. They check a complete Unicode surrogate
pair/word with retained italic annotation, selected fragments in layout order,
explicit selection clearing and replacement by an unselected chapter. They do not
exercise the production EPUB selection adapter, ML Kit or a translation provider.

Full original-app regression on managed Linux/JDK 21/offline Gradle 9.7.1:
**1,456 Desktop / 1,235 Android-host**, zero failures, errors or skipped tests.
Android `assembleDebug` and Desktop `compileKotlin` passed (application targets
were up-to-date because production/build files are unchanged). Core is unchanged;
unrelated core suites were not rerun. Production/test/build hashes remained
unchanged through final verification. `git diff --check` and a 401-file hygiene/
signing-secret scan passed; no SDK binary, model, publication or generated build
artifact is tracked by this PR.

No selection translation, translated reading mode, downloaded model, offline
translation, translation-cache reuse or translation progress correspondence has
been verified. Their acceptance criteria remain **pending**, not passed. The
original EPUB2/EPUB3 parser/security and EPUB/TEXT/PDF/CBZ readers are unchanged.
No APK containing a functional new translator is provided by this audit.

Before physical testing, implement and test the chosen real provider; exercise
selection, complete/incomplete windows, original/translated toggles, mixed styles,
images/links/notes, long chapters, language changes, cancellation/stale work,
model installation/offline use, cache corruption/eviction/clear and semantic
reopen/typography continuity. Distinguish fake-provider unit tests from actual
Android inference, model downloads and native Desktop graphical acceptance.
