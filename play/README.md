# Google Play listing copy

The text and graphics that go into the Play Console, kept next to the code
they describe. (The App Store copy for the iPhone / iPad and Mac editions
lives in the `md` and `md.macOS` repos, in `appstore/`. The three listings
are written separately, because the apps differ.)

| File | Play Console field | Limit | Current |
| --- | --- | --- | --- |
| `listing/short_description.txt` | Short description | 80 | 78 |
| `listing/full_description.txt` | Full description | 4000 | 3716 |
| `release-notes/en-US/default.txt` | What's new (per release) | 500 | 455 |
| `graphics/store_icon_512.png` | App icon | 512×512 | — |
| `graphics/feature_graphic_1024x500.png` | Feature graphic | 1024×500 | — |

Play's "What's new" is only **500 characters** — a tenth of the App Store's
— so it carries the headline changes, not the changelog.

## Claims that 1.2 made false — do not let them creep back

The 1.1 listing said things this version no longer supports. They were
corrected for 1.2, and Play's metadata policy (misleading claims) makes
them a real risk, not a nitpick:

- **NOT "zero permissions", NOT "does not declare INTERNET".** Since 1.2
  the manifest declares `android.permission.INTERNET`, used for exactly one
  thing: loading an image a document references by URL. The listing now
  says so plainly, and the Data safety form must match.
- **NOT "no third-party libraries".** The app bundles the offline math and
  diagram engines (KaTeX, Mermaid, Graphviz, PlantUML). Only the Kotlin
  side is dependency-light.
- **NOT "continuous content-tall pages".** PDFs are real A4 pages now.
- **No price talk** ("Free") — Play forbids promotional/price wording in
  store listings. "Ad-free and tracker-free" is a factual claim and stays.
- **No other-platform or third-party trademark references.** The old copy
  mentioned the iPhone / iPad / Mac editions; that is gone.

## Data safety

The answers that match this build: **no data collected, no data shared, no
tracking.** The INTERNET permission is disclosed as above — a request goes
only to the host named in the user's own document, and only when the
document contains a remote image.

## Wording that must stay accurate

- Private notes are hidden from the page, the PDF and the printout **only
  when the comment is on its own line**; written inline, they render.
- Pagination is line-aware for *text*. A diagram taller than the page can
  still be split, so the copy does not promise otherwise.
- **Split is for wide windows** (tablets, unfolded foldables, landscape);
  a phone-width window offers Edit and Preview.
