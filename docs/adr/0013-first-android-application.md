# ADR 0013: First Android application target

- Status: accepted for the first Android debug build
- Date: 2026-10-03
- Applies/refines [ADR 0008](0008-platform-entrypoints.md); preserves [ADR 0012](0012-bounded-text-reading.md)

## Context

Desktop already has real search/acquisition/bounded TEXT reading. Android needs
its own entrypoint/engine/XML parser, while keeping the exact UI, session and
security semantics. AGP 9 requires a separate application module from KMP libraries.

## Decision

Use four actual modules: core (pure domain), app (shared KMP library), desktopApp
(Desktop launcher/runtime/packaging), androidApp (Activity/manifest/Back/insets/APK).
Retain app's name rather than rename every existing path/task to sharedUi. No empty
modules. Both library Android targets use com.android.kotlin.multiplatform.library;
androidApp uses com.android.application and built-in Kotlin.

Share commonMain UI/controllers/ReadingSession/TextDocument/TextReader. Introduce
jvmSharedMain only for Java APIs available on both JVM and Android: URL/metadata/
security policies and engine-independent Ktor adapters. Desktop actuals use Java
engine and hardened JDK StAX; Android actuals use the official Ktor Android engine
and built-in XmlPull. A small XML-token interface shares Gutenberg mapping and
all limits; no external parser. Disable DTD processing and reject declaration/
custom-entity tokens before consuming entries. Standard XML references are verified.
No new formats, Gutenberg acquisition or broader Archive permissions.

ApplicationSources owns the active session and both source/client instances.
Cancel session before closing transports, once; detach is also idempotent.
Factories do not acquire/search automatically. MainActivity has no retained Context
in sources, destroys owned work/clients and creates a fresh session on recreation.
No retained ViewModel/state/persistence. Desktop has the same ownership boundary.

Android injects BackHandler into shared App through a callback. Loading/Ready/Error
handle Back using existing session cancellation/generation behavior, preserving
source/query/results; root Search leaves Back to Android. Shared reader is unchanged.
Android launcher handles safe system/keyboard insets. Recreation/process death loses
query/results/document/scroll position, accepted for this slice.

LazyColumn keys use a Pair<String, String> projection of PublicationId, preserving
source/local identity without ambiguous concatenation. The pair is serializable
on Android/JVM, as required for Android Bundle-compatible list keys; PublicationId
itself remains Kotlin-pure. This does not add state persistence. See the official
[Compose item-key guidance](https://developer.android.com/develop/ui/compose/lists#item-keys).

Use AGP 9.3.1 (official Kotlin 2.4.20 range), compileSdk 37 (required by Compose
1.12.1 AARs), targetSdk 37, minSdk 26 (shared java.util.Base64 without desugaring),
JDK 21 build toolchain with Android bytecode 17. See [toolchain](../TOOLCHAIN.md).
Application ID org.infinilect.app; visible name INFINILECT; original provisional icon.
INTERNET is the only directly requested permission; keep AndroidX's app-scoped
signature permission for non-exported receiver protection. HTTPS/cleartext denial,
no analytics/device IDs, and no automatic downloadable-font initialization.

## Consequences

Desktop regression and Android host tests run offline. Android XmlPull token
adapter/configuration is tested with fake tokens and a test-only upstream kXML
parser (MIT, not bundled); the actual OS XML decoder and physical
UI/network/lifecycle require a real device smoke test. Produce/inspect a standard
debug APK; no release signing/distribution or generated binaries in Git.
BOM detection counts real document bytes, excluding overflow probe; exactly EF BB BF
becomes EMPTY after stripping. Retain 512 KiB, strict UTF-8, exact EOF and handle close.
AndroidX/Android engine/plugins are documented with their original licenses.
No cache, persistence, new formats, reader settings, login/lending, telemetry or iOS.
