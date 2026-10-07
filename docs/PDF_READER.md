# Bounded local PDF reader — PR #20

Only PDFs selected locally are enabled. Remote sources/acquisition are unchanged.
The publication first becomes an app-owned durable import, then enters Library,
then opens through its source and ResourceLoader. History is recorded after
preparation and the initial page render succeed. Later unsupported pages show a
controlled failure; durable progress retains the last successfully rendered page.

## Owned boundary and engine choice

```text
Publication → ResourceLoader → PdfPreparer → PdfDocument
    → renderPage(index, bounded size) → owned PdfRaster → PdfReader
```

Core owns normalized page geometry, output size, limits, typed failures, document
lifetime and exclusively owned ARGB pixels. Application code owns preparation and
reader/session orchestration. There is no reader plugin registry. Bitmap,
ParcelFileDescriptor, PDFBox, BufferedImage and Java2D remain in platform
infrastructure. Compose ImageBitmap is presentation only, through the existing
raster presentation adapter; it is absent from PDF contracts/controllers.

Android uses framework PdfRenderer, exclusively its API-21 surface available on
minSdk 26. A seekable read-only descriptor is opened on a private verified spool;
successful construction transfers ownership to the renderer. Constructor failure
closes the descriptor. One page opens at a time and closes in finally before
another opens. Bitmap pixels copy into the owned raster; the intermediate Bitmap
is recycled. No newer password, text/search, annotation or thread-safety API is
assumed. SecurityException becomes ENCRYPTED, but API 26 cannot identify every
encrypted PDF consistently; empty-password PDFs may open, unlike Desktop.

Desktop uses Apache PDFBox **3.0.8**, strict PDFParser parsing of
RandomAccessReadBufferedFile. No complete source byte array or memory mapping.
PDDocument, PDFRenderer and Java2D remain private. Rotation and UserUnit normalize
geometry; physical points and output allocation arithmetic are validated. Rendering
uses renderImage (rather than renderPageToGraphics, whose upstream transparency
limitations affect Linux). Image subsampling is enabled. A bounded raster copy
owns the result; engine images are flushed and PDDocument closes on retirement.
Annotations are filtered out. Reachable image resources (including forms/patterns),
inline images and image masks requiring missing
JBIG2/JPEG2000 ImageIO readers raise CODEC before PDFBox can log-and-omit them.
The resource check is conservative: an unused resource needing an absent codec
can also reject its page. No optional image codecs or cryptography dependencies are added. Missing/substitute
fonts and platform font differences remain graphical acceptance concerns.

One shared third-party engine was rejected for this slice: it adds Android/native
packaging and licensing obligations without improving the owned boundary. The
platform pair minimizes new dependencies and permits either adapter to be replaced.
Process isolation can replace adapters without changing PdfReader; no service,
IPC, subprocess or worker JVM is implemented here.

## Enforced budgets and ownership

| Boundary | Limit |
| --- | --- |
| Imported source / verified PDF spool | 32 MiB, nonempty, exact declared length and EOF verification |
| Durable imports, unchanged from PR #19 | 256 imports; 512 MiB aggregate payloads |
| Document | positive count, at most 2,048 pages; inspect every page's geometry |
| Geometry | finite, positive; maximum side 14,400 PDF points; aspect at most 8:1 either way |
| Requested and delivered output | side at most 2,048 px; at most 1,048,576 pixels; at most 4 MiB ARGB payload |
| Engine concurrency | one operation at a time, including across abandoned/replacement readers |
| Preparation ownership | at most two live prepared documents per application owner |
| Reader retention | one owned raster; no thumbnails, tiling or prefetch |
| Image-mask inspection on Desktop | depth 16; 32 dictionaries; 16 filters per image |
| Desktop resource codec preflight | depth 16; 1,024 resource dictionaries; 4,096 entries |

Output products use Long arithmetic and validate before adapter allocation.
Adapters may allocate additional bounded image/copy buffers; **4 MiB is the owned
raster budget, not a total process-memory cap**. PDFBox renderer caches, decoded
images/fonts, compressed streams, transparency groups and Android native parsing
can use more memory. Large/recursive content, image/decompression bombs and parser
vulnerabilities can still exhaust CPU/memory or compromise/crash the process.
These limits do not make hostile PDF parsing safe by themselves.

The loader is completely consumed, including PR #19's EOF SHA-256 verification,
before an engine opens the spool. Import publication still uses the existing
complete-directory rename with checksummed metadata. A `%PDF` prefix only selects
the PDF validation path; a fake/truncated input cannot bypass engine preparation.
Filename/MIME do not authorize bytes. Existing TEXT/EPUB/CBZ validation is preserved.
Imports remain outside cache; clearing cache, removing Library/History or deleting
the original must not delete an owned import. No external URI/path is persisted.

Spools live in the private disposable `pdf-preparation-v1` namespace, protected by
an exclusive owner lock and private permissions where supported. Full input copies
remain file-backed. Document retirement closes engine state before deleting its
spool. Startup removes only generated spool names after acquiring the lock.
Application shutdown drains cleanup before releasing ownership. Durable source
storage and its quotas are unchanged. No password/UI/password persistence exists.
Desktop rejects even PDFs decryptable with an empty password; Android framework
limitations are reported above. Unsupported encryption may map to the controlled
MALFORMED/unsupported failure when an engine cannot classify it precisely.

## Cancellation and progress

Synchronous PDF parsing/rendering has no reliable interruptible deadline. It is
off the UI thread and serialized. No coroutine timeout claims to terminate it.
Back cancels requests and invalidates their generation; a result produced after
cancellation or at a coroutine return boundary closes instead of reaching UI.
Close retires a document immediately and schedules cleanup behind running engine
work. A permanently blocked operation can delay all PDF work and shutdown cleanup;
future process isolation is needed for enforceable deadlines and working-memory
budgets. Existing acquisition/catalog deadlines are cooperative, including the
120-second import-copy deadline; they do not terminate a stuck provider/kernel call.

Next/Previous change the logical zero-based page index independently of rendered
pixels. Existing ReadingLocator.Page and the existing progress record schema are
reused with PublicationFormat.PDF and a generated `pdf-page-N` key. Navigation
exposes a requested index/loading state immediately, but submits progress only
after that page renders successfully and its ticket is still current. Failed,
cancelled or obsolete targets never advance durable progress. Back/reopen and a
new source/writer restore the last successful index, clamped to inspected page
count, without an initial reset write. Resize/recomposition keeps position.
Android recreation saves only the owned publication digest, last successfully
rendered page index and return destination in Compose saved state, then re-resolves
the local source and existing progress; no external permission or engine is retained.
Sudden termination before the asynchronous writer commits can lose the last change,
as with existing readers. Restoration requires available private storage.

## Passive-only scope and deferred work

Only static page content is rendered. There is no JavaScript, form/XFA interaction,
action execution, embedded-file extraction/execution or external resource acquisition.
Links are not interactive. Existing static appearances may differ between engines;
Android API 26 has no annotation-filter API matching Desktop's filter.

Deferred: process isolation and enforceable CPU/memory deadlines; password UI;
remote PDF acquisition; text selection/search/OCR/reflow; outlines/TOC; thumbnails;
zoom/tiling; forms, annotations/editing, signatures, printing/export; extra codecs;
import deletion/orphan management. This does not resolve the existing niri/Wayland
graphics issue or claim Android/device/Desktop graphical acceptance.

## Physical Android acceptance (pending)

- Rebuild/sign with the established external identity before attempting an update;
  expected certificate SHA-256 is
  `95c708e6cfee94bc13ecf34d2a38cf4f1dc5eab185133f8e139629dd0be470fe`.
  This supplied identity is for development/testing only; release signing is separate.
  An unsigned verification APK cannot be installed or establish update compatibility.
- On API 26 and a current physical Android version, import one/multiple-page PDFs
  through SAF, including renamed files and an available real provider.
- Confirm Library precedes open, correct fit-page/text/image/rotation rendering,
  Previous/Next edges, system/UI Back and progress restore.
- Rotate/recreate during Ready and rendering; rapid Next/Back/reopen must not flash
  obsolete content or lose the saved semantic page. Monitor descriptors/memory.
- Terminate/relaunch; delete/move the original; clear cache; reopen from Library
  and History. Reimport identical bytes: same ID. Remove metadata: copy still opens.
- Fake/truncated/encrypted, excessive page count/dimensions and malformed content
  fail without publishing invalid imports; renderer-specific cases remain device tests.
- Confirm TXT/EPUB/CBZ flows and existing readers still behave correctly.

## Graphical Desktop acceptance (pending)

- Perform native picker and reader checks on Windows and Linux, including Wayland/niri.
- Compare fit-page appearance for rotated, embedded/substituted-font, image-heavy and
  transparent PDFs; no blank/omitted optional-codec images should look successful.
- Exercise Next/Previous, resizing, Back, rapid navigation/cancellation and reopen.
- Delete original, restart, clear cache, deduplicate and remove Library/History
  metadata; verify durable bytes and saved page remain independent.
- Exercise controlled malformed/encrypted/unsupported-codec/limit failures and
  repeated open/close while monitoring memory/file handles. Host Java2D tests alone
  do not establish graphical Windows/Linux/niri acceptance.

## Official references

- [Android API reference](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
  and [Android 8.0 source](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/graphics/java/android/graphics/pdf/PdfRenderer.java).
- [PDFBox 3.0.8 source](https://github.com/apache/pdfbox/tree/3.0.8),
  [3.0 migration](https://pdfbox.apache.org/3.0/migration.html),
  [dependencies/codecs](https://pdfbox.apache.org/3.0/dependencies.html),
  [threading FAQ](https://pdfbox.apache.org/3.0/faq.html),
  [security](https://pdfbox.apache.org/security.html),
  [license](../third-party/pdfbox-LICENSE.txt) and [notice](../third-party/pdfbox-NOTICE.txt).
- [PDFRenderer allocation/transparency behavior](https://github.com/apache/pdfbox/blob/3.0.8/pdfbox/src/main/java/org/apache/pdfbox/rendering/PDFRenderer.java).

See [verification evidence](PDF_VERIFICATION.md) for actual automation and artifact status.
