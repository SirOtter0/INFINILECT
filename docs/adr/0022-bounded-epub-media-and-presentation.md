# ADR 0022: Session EPUB presentation and bounded local raster media

- Status: Proposed in Draft PR #16
- Date: 2026-10-05
- Follows: [0021](0021-semantic-epub-reader.md)

## Context

The first passive EPUB reader is physically usable but lacks heading hierarchy,
numbered lists, presentation controls and actual illustrations. Prepared EPUB
ownership alone does not make image decoding safe. Android/Desktop decoders and
Compose image objects must not enter core, parser models or reader contracts.

## Decision

Retain bounded XHTML → owned semantic chapters → shared Compose. Add heading levels,
list markers, preformatted blocks, separators, captions and manifest-owned EpubImage
references. The parser never decodes images. Ignore publisher CSS and reject active
or external content as before. Tables have no grid/layout support.

Use an owned EpubRasterDecoder contract returning bounded ARGB pixel data, outside
chapter models. Shared EpubMediaController opens only matching manifest entries
through EpubDocument. It owns one serialized worker, a 10s deadline and at most two
visible images. Shared PNG/JPEG header preflight validates encoded/dimension bounds
before platform decoding. Android BitmapFactory first inspects bounds; Desktop
JDK ImageIO inspects dimensions before reading pixels. Native/framework objects stay
inside platform adapters and UI-only conversion. No new third-party dependency.

Limit encoded images to 2 MiB, each dimension to 2048 and pixels to 1,048,576
(4 MiB ARGB). Restrict static 8-bit PNG to basic pixel/palette/transparency chunks;
reject animated/compressed metadata/profile extensions. Accept baseline/progressive
8-bit grayscale/RGB JPEG with optional JFIF, without EXIF/ICC/other APP extensions.
These restrictions intentionally turn other legitimate images into alt fallbacks.
SVG, GIF/WebP, responsive media, remote or encoded/query/fragment aliases remain
unsupported. Keep image layout height stable while loading/failing. Media is local
reader-session presentation state, never persisted, never ResourceCache/Downloads.

EpubReaderSettings are validated session-local values (font 14–30, spacing 120–200%,
margins 8–40, system/light/dark reading theme). No settings store is needed yet.
Any layout change republishes Ready with a new callback ticket and the latest typed
EPUB locator; no chapter reparse or pixel persistence. Old callbacks are ignored.
TEXT and persistence formats/schema are unchanged. Back uses existing application
navigation, flushes progress, cancels media work and closes the prepared document.

## Consequences

Both platforms share semantics, controls, progress and media policy without a browser
or arbitrary URI loader. The trade-off is limited publisher/image compatibility and
approximately restored lines. Two semantic chapter models and two decoded frames
are retained; UI conversion also retains at most two distinct images, shared for
repeated references. Bounded provider decoding may finish after cancellation;
generation/cancellation checks discard its result. Native bitmap garbage reclamation
is platform-managed, not an exact process-heap promise. Android decoder/performance
and device layout remain manual gates; headless Desktop decoder tests are not GUI
execution. Production EPUB acquisition remains disabled. Details and budgets in
[EPUB_READER.md](../EPUB_READER.md).

## PR #16 physical-acceptance follow-up: global preferences

Physical acceptance of the initial PR #16 reader passed, but session-only settings
were found to reset unexpectedly after reader exit. That part of this decision is
superseded within the same Draft PR: settings become global persistent EPUB user
preferences behind EpubReaderSettingsStore. Application-owned coalesced IO and a
small versioned atomic file adapter keep preferences separate from progress/cache/
collections. This does not change the rendering/media decision or locator formats.
Defaults remain for missing/corrupt/future data; failed saves remain visible.
See [exact persistence/lifecycle policy](../EPUB_READER.md#durable-global-epub-preferences-pr-16-physical-test-follow-up).
