# Wikisource source-rights policy — PR #39

Reviewed **3 November 2026**. Implementation: `WikisourceRights.kt`, bounded
`WikiTextMetadata.kt`, internally configured `WikisourceEdition.kt`.
This policy records **positive source declarations**, not independent legal
clearance for every territory, edition, translation or contribution. A parser
success, a historical author, a namespace, search snippet, metadata license or
site footer is never sufficient. Source assertions can be wrong; the application
cannot independently establish authorship/authority merely from an API response.

## Independent decisions

`CatalogPublicationInfo` carries page kind, EPUB technical state and rights state.
`WikiGrant` derives its authorized boolean from one canonical rights state.
Reading/download controls require both a positive grant and a usable technical
route. A candidate `PublicationResource` is an opaque source-owned reference,
**not a permission token**. Direct loads and download-offer resolution recheck
fresh metadata/rights and the exact revision-bound resource key.

| Rights state | Meaning |
| --- | --- |
| VERIFIED | A reviewed positive source-evidence rule matched, with scope/exclusions checked. No universal jurisdiction warranty. |
| INSUFFICIENT | A declaration exists but the recognized rule cannot establish sufficient scope. |
| UNKNOWN | No recognized effective grant for this page. Not proof of copyright or of no EPUB. |
| RESTRICTED | An explicit recognized conflicting/restrictive declaration is present. |
| PENDING | Presentation can represent an unresolved verification; it grants nothing. |

An authorized user may explicitly check an export with the existing preparer.
Unknown rights never trigger a binary acquisition just to learn its format.
A previously retained file uses the original snapshot/hash without fresh network
or rights checks; this policy does not retroactively revoke or rewrite it.

## Rules and reviewed evidence

The rule IDs below are code/policy identifiers, **not persisted book identities**.
There are no approved title/page/revision lists in application code. Revision
links here document research, not a whitelist or a live template evaluator.

| Rule | Edition / recognized applied declaration | Mandatory conditions and exclusions | Official evidence reviewed |
| --- | --- | --- | --- |
| `es.work.dp100.v1` | ES, direct unparameterized `DP-100` | Namespace 0, non-auxiliary page; standalone effective work declaration. No translator/edition/illustrator claim, images, unverified templates or `<pages>` transclusion. No conflicting grant or unverified relative child-page graph. | [Plantilla:DP-100, r981774](https://es.wikisource.org/w/index.php?curid=1311&oldid=981774): refers to this work and death over 100 years; expressly warns that translations may differ. Real positive: A Venus/r1393137. |
| `ca.work.dp100.v1` | CA, direct unparameterized `DP-100` | Same work-level scope and exclusions as ES. Catalan template semantics reviewed independently. | [Plantilla:DP-100, r173700](https://ca.wikisource.org/w/index.php?curid=1860&oldid=173700). Real positive: L'emigrant/r63283. |
| `en.work.release.v1` | EN, direct unparameterized `PD-release` | Same non-auxiliary/standalone restrictions, no unknown transclusion, translator/edition/artwork or `<pages>` graph. No age calculation or unverified relative child-page graph. | [Template:PD-release, r13177145](https://en.wikisource.org/w/index.php?curid=7319&oldid=13177145): release by copyright holder. Real positive: Smokey the Bear Sutra/r15899522. |
| `en.work.pdold.v1` | EN, direct unparameterized `PD-old` | Existing EN work declaration, no translated/modern-edition/artwork claim or unknown template graph. Existing ProofreadPage references remain supported under that work declaration; this is not a new blanket Commons-license inference. | [Template:PD-old, r13177148](https://en.wikisource.org/w/index.php?curid=7615&oldid=13177148). Real regression: The New Colossus/r15492692. |
| `commons.scan.pd100.v1` | ES/EN/CA, exactly one ProofreadPage slice → same-named official index → same-named Commons File with `PD-scan` argument exactly `PD-old-100-expired` | No unverified extra prose/artwork/descendant TOC/other transclusion; exact configured namespaces, positive IDs and revisions. Declaration must be effective top-level or the actual Book Permission field. No weaker/automatic-expiry/contradictory claims. No device-clock legal inference. | [Commons PD-scan](https://commons.wikimedia.org/wiki/Template:PD-scan) and [PD-old-100-expired](https://commons.wikimedia.org/wiki/Template:PD-old-100-expired). Live chains: Canción del pirata, Rima LIII and CA Introducció, with per-page revision evidence in [audit](WIKISOURCE.md). |

Direct grants retain the raw declaration, selected-page revision URL and reviewed
template URL as source evidence. Scan grants retain the root, index and Commons
revision URLs plus the raw scan declaration. The EPUB retains its separate
`dc:rights` statements, text, attributions and passive HTTPS links. No merged or
invented license replaces those declarations.

## Effective declarations, quoted text and scope

The bounded declaration scanner does not execute MediaWiki. It excludes comments,
nowiki, preformatted examples, code, template documentation/conditional blocks,
quotations and reference examples from **positive** evidence. Nested example
parameters, template links and prose mentioning a license do not grant anything.
A second scope view retains rendered references/quotations when checking for
translation/edition statements, unknown transclusions and artwork: hiding a
foreign image in a note must not make a direct work declaration sufficient.
Malformed/unbounded metadata fails closed.

Presentation-only templates are a finite reviewed set (headers, layout and note
lists); an arbitrary transclusion is not presentation by default. The EN
[WP link template, r14965389](https://en.wikisource.org/w/index.php?title=Template:WP_link&oldid=14965389)
only constructs a Wikipedia hyperlink. It is accepted solely inside a header,
with exactly one plain bounded positional argument. It neither imports another
work nor supplies rights. Top-level use, nested/injected arguments and unknown
parameters remain insufficient.

The scan chain is deliberately narrower than full recursive edition clearance.
The current policy does **not** traverse a whole book's child pages, file images
and linked editions to infer their permission. A single licensed cover cannot
authorize forty linked poems/chapters. A site's content-admission policy is not
an independently verified worldwide reuse guarantee for every embedded object.

## Researched patterns deliberately not promoted to grants

| Pattern / evidence | Why not automatically accepted |
| --- | --- |
| ES [DP-AUTOR-100 r983602](https://es.wikisource.org/w/index.php?curid=3262&oldid=983602) | Original author's works, not a particular modern translation/edition/artwork. No inheritance from author pages. |
| ES [DP-1923 r1079623](https://es.wikisource.org/w/index.php?curid=217969&oldid=1079623), EN PD-1923, territorial PD-España | Territorial/publication conditions do not prove the required whole-page scope everywhere. |
| ES DP/DP-Arte; CA [DP r181735](https://ca.wikisource.org/w/index.php?curid=1865&oldid=181735) | Media/reproduction or broad assertions need scoped semantic evidence; they are not aliases added by name similarity. |
| EN [CC-BY-SA-4.0 r13441163](https://en.wikisource.org/w/index.php?curid=1640860&oldid=13441163) | The license permits reuse when validly applied, but the real examined examples also contained ProofreadPage scans, multiple transclusions or permission records. A token alone did not establish the complete graph's scope. A general scoped CC rule remains future work. |
| WS Export credit page / site CC BY-SA 4.0 footer | Credits and wiki contributions are distinct from original work, translation, edition, scan and image permission. |
| Cited/commented license, example, author lifespan, old year, MIME or HTTP 200 | Not effective authorization of this edition. |

EN CC research included `Open access and the humanities/Chapter 1` (1861255/
r5523684), a letter scan (1684120/r15663955), and an annotated multi-transclusion
work (1550627/r13785745). Their metadata was inspected; **no binary was acquired
or reading claimed** for these insufficient-scope cases. Likewise CA Scachs
d'amor has a direct DP-100 but additional unreviewed formatting templates; its
reader/acquisition path was not advertised as verified. Broadening these patterns
requires explicit scope handling and negative tests, not a larger title list.

## Regression evidence

`WikisourceRightsCoverageTest` exercises arbitrary titles and IDs; ES/CA effective
DP-100; EN PD-release; real-style notes/comments; the narrow EN WP link case;
translator/edition/illustrator fields and prose; images/transclusions in rendered
notes; quoted/commented/nested-only licenses; territorial/conflicting declarations.
Existing `WikisourceRightsTest` still covers Commons identity/permission placement,
weaker expiry, fake examples, multiple scans, descendant TOCs, malformed metadata,
namespace exclusions and resource-independent direct grants.

Availability tests require unknown-rights candidates to remain unreadable and
unfetched, refresh newly discovered rights without exporting, separate HTTP/format failures from rights, run actual synthetic EPUB
preparation, recover from corrupt files and prevent canceled/delayed checks from
publishing validation. Offline end-to-end regressions preserve identity, stored
locator and progress after removing only the downloaded bytes. No tests depend
on live Wikimedia in ordinary suites. Actual live results/limitations are recorded
in [WIKISOURCE.md](WIKISOURCE.md).
