# Durable local import

PR #19 adds a production local acquisition path for UTF-8 TEXT, the existing
bounded passive EPUB3 subset, and the supported PNG/JPEG CBZ subset. PR #20
adds engine-validated bounded local PDF reading. CBR/RAR/7z, DRM, bulk import and local directory browsing are unsupported.
Importing a file establishes no copyright or legal status.

```text
Android SAF / Desktop native picker
    → LocalFileSelection (one-shot bounded ResourceContent)
    → LocalPublicationImporter
    → durable owned copy + validated metadata
    → PublicationSource("local-imports") / ResourceLoader
    → TEXT / EPUB / CBZ / PDF owned preparer and reader
    → existing Library / History / ReadingProgress
```

The picker is an acquisition boundary. Android `content://` URIs and Desktop
paths exist only inside platform acquisition callbacks; they are not publication
identity, persisted metadata, reader arguments or reopening authority.

## Storage and identity

Android uses `applicationContext.filesDir/local-imports-v1`, never `cacheDir`,
shared storage or Downloads. No storage permission or persisted URI permission
is requested. The Activity owns the SAF registration; selected content uses the
application resolver. Provider metadata/query/open work runs on IO, with Android
cancellation signals for cooperative providers. A provider that ignores cancellation
or blocks a stream indefinitely remains a platform/device verification concern.

Desktop uses `local-imports-v1` beside ReadingProgress in the existing absolute
per-user persistent data root: Windows LOCALAPPDATA, macOS Application Support,
Linux XDG_DATA_HOME or the safe home fallback. Relative/unsafe environment paths
do not silently become cwd/repository storage. A native AWT FileDialog runs on the
AWT event thread; external file reading and owned storage use IO. Picker dismissal
is normal cancellation. Graphical picker operation requires human verification.

Each import has a SHA-256 digest computed during streaming copy. Its PublicationId
is `SourceId("local-imports")` + the lowercase digest; its sole resource key is
`content`, and its revision is that **actual byte digest**. This does not fabricate
a revision for any remote source. Identical bytes under any filename resolve to the
same entry/ID, retaining the first committed metadata. Different bytes under the
same filename remain separate publications. Existing Library rows update through
the normal repository contract without duplicate identities.

Owned names contain only generated UUIDs and verified digests. A completed
`<digest>.import` directory contains immutable `payload` and a version-1 binary
`record`: digest, byte count, format, display title, authors/languages and a SHA-256
record checksum. Records are bounded to 16 KiB, validated on read, and contain no
external location, resource URL, publication bytes, progress or credentials.
An unsupported/corrupt record is unavailable, never repaired by deleting user data.

## Limits and validation

- Import transfer: **32 MiB**, **8 KiB** copy/read buffers, nonempty input. A declared
  length must match exactly, including an EOF probe; unknown length is accepted
  only within the same finite limit. No whole-publication buffer is used.
- Durable namespace: **256 imports**, **512 MiB payload budget**, bounded 2,048-entry
  directory scans and one serialized import/catalog operation per application owner.
  Metadata contributes at most another 4 MiB. A pending copy adds at most 32 MiB.
- TEXT: existing **16 MiB** limit, strict UTF-8/BOM/code-point behavior and indexed
  windows remain unchanged. Import additionally rejects C0 controls except tab,
  LF, CR and form feed, and rejects C1 controls. Valid UTF-8 alone is not binary
  format authorization.
- ZIP input: existing EPUB structural preparation first, otherwise existing strict
  CBZ preparation. Ordinary/malformed/unsupported ZIP files fail. Filenames and
  provider MIME never authorize a format. A PDF prefix selects the owned PDF
  preparer; bounded engine parsing/inspection must succeed before publication.
  [PDF contracts, limits and cancellation](PDF_READER.md).
- EPUB/CBZ limits, CRC/path/ZIP checks and raster bounds remain unchanged. CBZ
  validates every page without retaining/decoding every raster. EPUB structural
  import validation does not imply every chapter supports the presentation subset;
  later rendering can still fail safely on unsupported XHTML.
- At most **8 live local payload handles**, plus the single import input. Local
  catalog operations and import copy time out after 120 seconds where cancellation
  is cooperative. PDF parsing/rendering has no interruptible deadline. Existing
  preparation storage/model limits apply in addition to the durable import budget.

Acquisition closes its input on all paths. Validation uses existing preparers and
closes their temporary documents. The local loader verifies stored resource
ownership, exact byte count and digest at EOF. It uses DirectResourceLoader, avoiding
an unnecessary second durable-byte copy in the disposable ResourceCache.

## Commit, concurrency and lifecycle

One application owner holds the namespace lock. Operations serialize on IO; resource
handles permit bounded independent sequential reading. Concurrent application
owners cannot write the namespace. Symlink roots/owned files are rejected; only
generated names reach the filesystem, with no extraction to external locations.

A copy is written in a generated `.part` directory. Only after successful validation
are payload and metadata forced to disk and the **complete directory** renamed into
its final name. Atomic same-filesystem move is preferred; if that API is unsupported,
same-directory non-replacing rename of the complete directory is used. No existing
committed import is replaced or destructively repaired. Parent-directory forcing is
best effort on providers without directory fsync; abrupt power-loss durability is
limited by the underlying OS/filesystem. Partial failure/cancellation leaves no
published entry; cancellation immediately after the commit can retain a complete,
valid import without opening it. That content remains discoverable locally.

Normal failures remove the exact temporary payload/record names. Startup cleans only
the generated stale partial namespace under the owner lock; unrelated files and
unexpected directory contents are not recursively deleted. Reader/session close
cancels work; application close also invalidates local handles and drains the owner
before releasing storage. Late picker/import results cannot navigate a new session.

## Library, History and deletion

Import commits owned content first, then durably adds its metadata to the existing
Library, then opens by **normal source re-resolution**. History is recorded only
after the existing successful reader-open path. ReadingProgress and reader settings
retain their existing independent stores and representations; no SQL migration.

If Library persistence fails, the app explicitly reports that the owned file was
imported but not added; it does not claim a successful Library mutation or open a
reader. Select **Imported files** and search `*` to recover/list owned publications.
This catalog also allows reopening retained imports after a Library removal.

Removing a Library row, clearing History, reader cleanup, cache deletion or deleting
the original external file **does not delete owned imported content**. Orphan cleanup,
explicit import deletion/export, backup and storage management are future work.
The finite budget can become full; failure is explicit, with no automatic eviction
of user-owned content. Import selection offers one file at a time.

Everything stays local. No import/progress data is sent to a source or telemetry.
Gutenberg remains catalog-only; Internet Archive acquisition, authorization and
null-revision behavior are unchanged. Local snapshots authorize neither remote
bytes nor arbitrary external paths.

Automated evidence and pending Android/Desktop acceptance gates are in
[VERIFICATION.md](VERIFICATION.md). No picker or device success is inferred from
host tests or a successful APK/distributable build.
