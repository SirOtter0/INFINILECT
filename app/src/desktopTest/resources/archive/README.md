These small, curated fixtures reproduce the structure of official Internet Archive
search/item metadata observed on 2026-10-02. They contain no publication text.
`item.json` adds a second language to exercise array mapping. `search.json` uses
a synthetic total/title to exercise pagination. Tests also mutate these fixtures
to represent unknown rights, private files, and restricted/lending items.
The 2026-10-03 review adds root server/directory/workable and alternate-location
fields from that official item response. Additional node/item combinations in
tests are synthetic policy cases, not claims of live observations.

The official test item advertises CC0 1.0. Fixture structure and test mutations
are provided under INFINILECT's GPL-3.0-or-later terms; upstream declarations
and file hashes are preserved as data, not relicensed.
