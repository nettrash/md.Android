# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The `versionCode` is auto-incremented on every build by a Gradle finalizer
(mirroring the iOS app's `agvtool bump`) and is not tracked here.

## [1.3] — 2026-07-24

### Added


- **Chemical equations.** A formula written in the `\ce{…}` notation —
  `$\ce{H2SO4 + 2 OH- -> SO4^2- + 2 H2O}$` — now typesets as proper
  chemistry: subscripts, charge states, reaction arrows and the rest. It
  works anywhere maths already does, inside the same delimiters (`$…$`,
  `$$…$$` or a ` ```math ` block), and `\pu{…}` writes physical units the
  same way. This rides in on **mhchem**, the chemistry extension from the
  very **KaTeX 0.17.0** build the app already carries for maths (MIT-
  licensed, ~33 KB) — it loads only when a document contains maths and runs
  entirely **on-device**, no network. Because it is maths, it reaches the
  preview, print, an exported PDF, exported HTML and an exported EPUB alike.
- **Syntax highlighting.** A fenced code block with a language hint —
  ` ```swift `, ` ```kotlin `, ` ```js `, and the like — is now
  syntax-highlighted, so keywords, comments and strings stand apart from the
  rest of the code. The colours are a small **hand-written theme in md's own
  paper palette** — keywords in the warm accent, comments muted and italic,
  strings a quieter shade of the ink, everything else plain ink — rather than
  a stock highlight.js theme whose bright colours would fight the typewriter
  look; the code face stays Courier New, and it tracks the light and dark
  themes like the rest of the page. The highlighting is drawn by
  **highlight.js** (v11.11.1, BSD-3-Clause, the ~40-language "common" build,
  ~124 KB), pulled in **on-device** only when a document actually has a
  highlightable block, no network. It shows in the preview, print, an
  exported PDF and exported HTML; an exported **EPUB keeps its code blocks
  plain**, since that file is built before the highlighter runs. A bare fence
  with no language is left plain — a guess would only miscolour prose and
  shell transcripts nobody marked as code — and the diagram, maths and table
  fences (`mermaid`, `dot`, `math`, `csv`, …) keep their own handling.
- **Graphviz diagrams.** A fenced block tagged `dot`, `graphviz` or `gv` is
  now laid out by **Graphviz**, the classic tool for graphs that are
  described rather than drawn. Each of its layout programs can be named as
  the block's language instead — `neato`, `circo`, `fdp`, `sfdp`, `twopi`,
  `osage` and `patchwork` — so the same graph can be given a hierarchy, a
  spring model, a circle or a radial fan by changing one word. A bare `.gv`
  file opens in md the way a `.puml` file already does — hand one to the
  app from a file manager or "Open with md" — and renders as the diagram in
  the preview, print and exported PDF while the source stays fully
  editable. All of it draws **on-device** from an engine the app has
  carried all along as PlantUML's own back end, so this costs no extra
  download and no network access. Graphviz's other usual extension, `.dot`,
  is deliberately left unclaimed — it is already spoken for by Word
  templates on md's other platforms, and the three apps open the same file
  types — so rename such a file to `.gv` to open it; fenced ` ```dot `
  blocks are unaffected.
- **YAML and TOML front matter.** A file written for a blog, a site
  generator or a notes app almost always opens with a block of metadata —
  title, author, date — fenced off above the text. md used to render that
  opening `---` as a horizontal rule and the metadata under it as stray
  prose, so any such file looked broken the moment it was handed to the
  app. Both conventions are now understood — YAML between `---` lines
  (closed by `---` or `...`) and TOML between `+++` lines — and the block is
  recognised as metadata rather than text and hidden: the page begins at
  the first heading in the preview, in print, in a shared or exported PDF
  and in an exported EPUB alike. The block stays in the file and is saved
  back untouched, so whatever else you hand the file to still finds it —
  and md's three apps read it identically. Its fields are read as plain
  `key: value` (or `key = value`) pairs rather than by a full YAML parser —
  enough for a title and an author; a list or a nested key inside the block
  is passed over rather than understood, and so long as one plain field
  remains beside it the block is still hidden whole. A block that holds no
  recognisable field at all is not metadata and is not treated as such:
  `---`, three bullets and `---` stay
  a rule, a list and a rule, so prose that merely happens to sit between
  two rules is never hidden from the reader. And it only counts at the very
  top of a document, and only when the fence is closed again: a document
  that simply opens with a horizontal rule keeps its rule, and a `---`
  further down is the thematic break it always was.
- **Footnotes.** An aside that would interrupt a sentence can now be sent
  to the foot of the page instead, in the spelling GitHub and Pandoc
  already use. Mark the spot in your text with `[^id]`, and write the note
  itself on a line of its own as `[^id]: the note` — wrapping it over as
  many lines as it needs, and putting it wherever in the file suits you,
  since it never renders where it is written. The notes are gathered under
  a rule at the foot of the rendered page — in the preview, in print, in a
  shared or exported PDF and in an exported EPUB alike — and numbered: each
  reference in the text becomes a small numbered link down to its note, and
  each note you cited ends in an arrow that takes the reader back to where
  it was first cited. The numbering follows the order a reader meets the
  references rather than the order the notes happen to be written in, so
  moving a note around the file changes nothing on the page — and md's
  three apps number them identically. Two kindnesses are deliberate: a
  reference with no note behind it stays exactly the text you typed rather
  than becoming a link that leads nowhere, and a note you wrote but never
  cited is still printed, after the cited ones — nothing you wrote is
  dropped in silence.
- **CSV and TSV blocks draw as tables.** A table of figures usually begins
  life in a spreadsheet, and turning it into Markdown's pipes and dashes by
  hand is the sort of work nobody wants to do twice. Paste the data as it
  comes instead — into a fenced block tagged `csv`, or `tsv` for the
  tab-separated text a spreadsheet puts on the clipboard — and md draws it
  as an ordinary table in the preview, in print, in a shared or exported
  PDF, in an exported HTML file and in an exported EPUB alike, while the
  source stays the data it always was. That is the point of it: when next
  month's numbers arrive, the block is replaced wholesale with a fresh copy
  rather than edited cell by cell. The first row is the header. Quoting
  works the way a spreadsheet writes it — a field wrapped in quotes may
  hold a comma or even a line break, a doubled quote inside such a field is
  one literal quote, and a quote that opens nothing, the inch mark in
  `5" pipe`, is simply a character. A column whose values are all numbers
  is lined up on the right, so the decimal points sit under one another; a
  single piece of text in the column and it stays left-aligned, as text
  should be. md's three apps read a block identically, down to the
  alignment. This is a fenced block and nothing more — md still neither
  opens nor saves `.csv` files.
- **Export as HTML.** A new "Export as HTML…" entry in the menu, beside
  "Export as PDF…", saves the rendered document anywhere via the system
  create-document picker as **one self-contained `.html` file** — ready to
  share from there like any other file. It is a single file that opens
  anywhere — a browser, a phone, a machine that has never heard of md — with
  nothing beside it: no engines, no folder of assets, and no engine left in
  the page to run. The only thing an exported page can still reach for is an
  image you linked to yourself: those are written out as the links they
  were, not fetched and embedded. What is saved is the finished page rather
  than the recipe for one: every Mermaid, Graphviz and PlantUML diagram has
  already been drawn and travels as a drawing, and every formula has already
  been typeset and travels as real text — so a reader can copy a formula out
  of the page, and it stays sharp at any zoom. A document with formulas
  carries the typesetting fonts it needs inside the file, and those fonts
  are most of what it weighs; a document without formulas carries none of
  them and is a few kilobytes. And because an exported file is read on a
  screen rather than on paper, the author's `\newpage` markers appear as the
  dashed rules the preview shows rather than as the page breaks the PDF
  gets.
- **Export as LaTeX.** A new "Export as LaTeX…" entry in the menu,
  beside "Export as HTML…", saves the document anywhere via the system
  create-document picker as a `.tex` file — ready to share from there
  like any other file. This is the one export where your mathematics
  comes out as mathematics: everything else md produces turns a formula
  into a picture — a PDF page, an EPUB, a printout — or into the markup
  a browser typesets, while a `.tex` file hands it back as the `$…$` you
  typed, ready to paste into a paper and go on editing. The rest of the
  document travels with it: headings become `\section` and its deeper
  relatives, emphasis becomes `\textbf` and `\emph`, lists become
  `itemize` and `enumerate` nested the way you nested them, a table —
  and a `csv` or `tsv` block alike — becomes a `longtable` that keeps
  your column alignment and breaks across pages, repeating its header
  row, so a table longer than a page keeps every row instead of stopping
  at the one that filled it. Code becomes `verbatim`, a quote becomes
  `quote`, and your `\newpage` markers stay the `\newpage` they already
  look like. An image becomes `\includegraphics`, set in a captioned
  `figure` wherever you wrote it in body text and gave it alt text — a
  graphic as wide as the text block has nowhere to stand inside a
  sentence, so the float is left for LaTeX to place; inside a table cell
  or a footnote there is no float to be had, so the graphic goes in bare
  with your alt text in italic beside it. When LaTeX cannot include the
  picture at all — the file name has a character `\includegraphics`
  reads as something other than a name (a `"` among them, since graphicx
  quotes file names with those itself), or the image is a `https://` or
  `data:` URL, which TeX will not fetch — the image is skipped rather
  than taking the whole document down with it, and your alt text is set
  in its place, with a comment naming the file on the line below it.
  Front matter becomes the title block: `title`, `author` and `date`
  become `\title`, `\author` and `\date` with a `\maketitle`. A `[^id]`
  footnote is written into the text at the point you first cited it,
  which is how LaTeX itself would have you write it — a second citation
  becomes a `\footnotemark` carrying the same number, a note first cited
  from a table's header row has its text set just after the table,
  because LaTeX prints nothing from inside a header it repeats on every
  page, and a note you never cited is printed at the end all the same.
  In a book each article numbers its own notes, so two articles that
  both start at `[^1]` — which is the ordinary way to write them — each
  keep their own note's words. A whole book exports the same way from
  the book's menu, as one `book`-class file with each chapter a
  `\chapter` and each article a `\section`, in the order the compiled
  PDF and the EPUB read them; md's three apps write the same `.tex` from
  the same document. Three things are worth knowing. LaTeX has no
  renderer for a Mermaid, Graphviz or PlantUML diagram, so a diagram's
  source travels as a `verbatim` block under a comment naming its
  language — kept for you to decide what to do with rather than quietly
  dropped. A display formula that is really a multi-line one — a `\\` or
  an `&` at its own top level, with no environment of its own around
  them — is given an `aligned` so it sets where you meant it instead of
  stopping the compile; and in a table cell or a footnote, where LaTeX
  will not open a display at all, a `$$…$$` is set inline rather than
  being lost. And the preamble asks for exactly the packages the
  document actually uses and no others: `graphicx` only if there is an
  image, `hyperref` only if there is a link, `ulem` only for a
  strikethrough, `longtable` only if there is a table, and the T2A font
  encoding only when the text has Cyrillic in it, which the default
  encoding would otherwise drop without a word — plus `amsmath` whenever
  there is any mathematics at all, since that is where `aligned` and its
  relatives live. So a plain piece of prose comes out with a short,
  clean preamble — and whatever is in it is what your TeX installation
  has to be able to find.
- **The diagram types that were already there.** md has bundled Mermaid and
  PlantUML since 1.1, but the examples only ever showed a flowchart or two,
  so most of what they can draw went unnoticed. The "Diagrams" example
  document now shows the range. Mermaid draws `flowchart`,
  `sequenceDiagram`, `classDiagram`, `stateDiagram-v2`, `erDiagram`,
  `journey`, `gantt`, `pie`, `quadrantChart`, `requirementDiagram`,
  `gitGraph`, `C4Context`, `mindmap`, `timeline`, `kanban`, `sankey-beta`,
  `xychart-beta`, `block-beta`, `packet-beta`, `architecture-beta`,
  `radar-beta` and `treemap-beta`. PlantUML draws the UML family inside
  `@startuml` — sequence, class, activity, state, component, use case,
  object and deployment — plus the C4 standard library, ArchiMate, timing
  diagrams and even sudoku. Beyond UML it draws a good deal more, each with
  its own opener: `@startmindmap` and `@startwbs` (mind maps and work
  breakdowns), `@startgantt` (schedules), `@startsalt` (interface
  wireframes), `@startjson` and `@startyaml` (data structures),
  `@startebnf` (grammars), `@startregex` (regular expressions as railroad
  diagrams), `@startnwdiag` (networks), `@startchen`
  (entity–relationship), `@startditaa` (ASCII art turned into a drawing),
  and `@startlatex` / `@startmath` (formulas). Nothing was added to the
  app to make these work — they were always there, only undocumented.

- **Open PlantUML files.** `.puml` (and `.plantuml`) documents now open in
  md — hand one to the app from a file manager or "Open with md". A file that
  is a raw PlantUML diagram (`@startuml … @enduml`, with no code fence)
  renders as the diagram in the preview, print and exported PDF, while the
  source stays fully editable and saves as plain UTF-8 text.
- **Export a single document as EPUB.** A whole book has been able to
  become an EPUB since 1.2; now a single open document can too, from a
  new "Export as EPUB…" entry in the menu, saved anywhere through the
  system create-document picker, beside "Export as HTML…" and "Export as
  LaTeX…". What a reader opens is not one flat entry standing in for the
  whole file but the document itself, laid out with its own headings as
  the table of contents — the same outline the document already lists, in
  the same order — so every section is somewhere the reader can jump to.
  The title is taken from the front matter's `title:` field when the
  document has one and from the file's own name when it does not: a lone
  document has no folder to borrow a name from, so the file name has the
  last word. Everything the book export already does, this does — every
  formula and every Mermaid, Graphviz or PlantUML diagram is drawn once
  and travels as a picture, and it is the same self-contained EPUB — and
  the identifier is derived from that title, so the same document exports
  as the same publication every time, updating the copy a reader already
  has rather than settling in beside it; md's three apps derive that
  identifier identically, so a document made into an EPUB on the phone and
  on the Mac is one publication, the same courtesy a book is given just
  below. There is no title page: a single document is its own first page,
  and the contents point at it rather than at a cover.
- **A page size for the PDF.** The PDF — a shared or exported one, and the
  whole-book PDF compile — always came out as A4, which suits a printout
  and little else a document is really made into: a booklet wants A5, a
  reader in the States wants US Letter or Legal, and a paperback printed
  on demand wants one of the trim sizes a print service asks for and will
  not accept as A4. So the size is now yours to choose — A4, A5, US
  Letter, US Legal, or the paperback trims 6 × 9″, 5 × 8″ and 5.5 × 8.5″ —
  from a "PDF Page Size" picker beside "Export as PDF…" in the menu, and
  the same picker in the book's menu, where it matters most: a book can be
  compiled at the very size it will be printed at rather than at one no
  press would take. The choice is remembered from one export to the next,
  and the page's margins scale with the paper, so a 6 × 9 page is not left
  wearing the wide margins A4 was cut for. A4 stays the default, and an A4
  export is unchanged to the pixel; the size reaches only the exported or
  shared PDF and its margins — the preview, the HTML export and the EPUB
  keep their own — and a printout to paper is left alone, since a paper
  proof goes out on whatever the printer is holding. md's three apps size
  a page from the same table of points.
- **Export a diagram as SVG.** A new "Export Diagram as SVG…" submenu in the
  overflow menu lists the document's diagrams — one row apiece, named by
  engine and a line of the source so two of them are told apart — and saves
  the one you choose as a standalone `.svg` file where you point the system's
  create-document picker: a real vector drawing that opens in any browser or
  vector editor and stays sharp at any size. Only the three drawing engines
  are offered — **Mermaid**, **Graphviz** and **PlantUML** — since those are
  the blocks that render to vector; math is not among them, because KaTeX
  sets a formula as HTML and text rather than as a drawing, so a formula has
  no vector to hand over and is left off the list. The diagram is laid out
  once through the same offline engines the preview uses and its finished
  vector is written straight out, not a picture taken of it. Mermaid draws
  without stating a height — which would open a standalone file at no height
  at all — so md fills the real width and height in from the drawing's own
  coordinates before it saves; Graphviz and PlantUML already give their size
  and are left untouched. And a diagram whose source never drew — a syntax
  error, say — has no vector to export, so md says as much rather than
  leaving an empty file behind.
- **Open and export TextPack.** A TextPack (`.textpack`, a zipped TextBundle)
  is the Markdown-with-images container that Ulysses, iA Writer and Bear
  write, and md now opens one — handed in from a file manager, "Open with
  md", or the open picker: the `text.md` inside becomes the editable
  document. A new "Export as TextPack…" action writes the current document
  back out as one — a zip holding `text.md`, the small `info.json` the format
  expects, and an `assets/` folder — and this is where images are handled. A
  picture you linked by a plain relative name (`![](photo.png)`, the file
  sitting beside your document) is copied into `assets/` and its link
  rewritten to point there, so the pack carries the image with it; a picture
  md cannot find, or one linked on the web or embedded inline as data, is
  left exactly as you wrote it — a link that already led nowhere stays a
  visible broken link rather than being quietly dropped. A few limits are
  worth stating plainly. md reads and writes the zipped `.textpack`; a bare
  `.textbundle` — the unzipped folder form — is not opened directly, since a
  directory package doesn't come through Android's file handoff the way a
  single file does, so zip it (or export a `.textpack`) and md reads it. A
  pack is imported for editing rather than adopted as a file to save back
  into, because writing only the text into the zip would drop the `assets/`
  and `info.json` it carries — so keep the work as a Markdown file, or export
  a fresh pack. And md's document is only its text, and its preview has never
  shown a local image, so an opened pack's own `assets/` pictures are not
  displayed: those refs render as the broken images they point at while you
  edit the prose. The round-trip is the words; the pictures travel inside the
  file, not on the page.

### Fixed


- **An exported book keeps its identity.** Every EPUB export was stamped
  with a freshly invented identifier, so as far as a reader was concerned
  each export was a different publication: fix a typo, export again, and
  the new file settled into the library beside the old one instead of
  replacing it — two copies of the same book, and then three. A book's
  identifier is now derived from its title, so the same book exports as the
  same publication however many times you export it, and a reader updates
  the copy it already has. Rename the book and it becomes a new one, which
  is what a new name ought to mean. md's three apps derive it identically,
  so the same book exported from the phone and from the Mac is one
  publication rather than two. Nothing else about the file's metadata
  changed: the export still carries no author and no cover.

## [1.2] — 2026-07-14

### Added

- **Share Rendered PDF.** Share the rendered document as a PDF — real A4
  pages with line-aware page breaks, matching the iOS / macOS export: no
  line of text or diagram is sliced through the middle at a page boundary,
  and the author's `\newpage` markers start fresh pages.
- **Export as PDF.** Save that same PDF anywhere via the system
  create-document picker.
- **Autosave.** The document now saves itself about a second after typing
  pauses (once it has a writable location), so the file on disk always
  matches the editor; the flush-on-background stays as a safety net.
- **Table of contents.** A "Contents" button in the app bar lists every
  heading in the document; choosing one scrolls the preview straight to it
  (switching to Preview first when needed). Headings now carry GitHub-style
  anchors, so `[…](#section)` links navigate inside the document too.
- **Page breaks.** Write `\newpage` (or `\pagebreak`) on its own line — the
  Pandoc convention — to end a page where *you* decide: shared and exported
  PDFs and printouts start a fresh A4 page there, and the preview shows a
  subtle dashed rule.
- **Synchronized scrolling in Split.** On a wide screen, the editor and
  the preview scroll as one: move either pane and the other follows
  proportionally. Jumping to a heading from the table of contents still
  lands the preview on its precise spot.
- **Word and character count.** Every document shows its live word and
  character count in a footer under the page.
- **Private author notes.** `<!-- note: … -->` comments are the writer's
  working notes: a new "Notes…" panel lists them, and they never appear in
  the preview, the PDF, or print. (Other HTML comments are now dropped from
  the rendered output as well.)
- **Writer mode: books.** Create a book from scratch ("New Book…" — name it,
  then choose the folder it lives in) or open an existing folder tree as one
  ("Open Book…"): its subfolders are chapters, its Markdown files are
  articles, ordered by numeric filename prefix ("01-intro.md") and then
  alphabetically. The book navigator opens any article and creates new
  chapters and articles in place; the book is remembered across launches.
- **Images.** `![alt](url "title")` now renders in the preview and in shared
  and exported PDFs — including linked images (`[![…](…)](…)`) and images
  embedded as `data:` URLs. Links also honour an optional title. Images
  keep their original size, capped to the page width. Fetching a document's
  remote images is the app's only network use (the new INTERNET permission
  exists solely for that — the app itself still sends nothing anywhere).
- **Built-in examples.** A new "Examples" entry in the menu opens ready-made
  documents showing everything md can do — formatting, tables, code,
  images, math, diagrams, and the writer tools — each as a fresh untitled
  document of your own to explore and edit. "Example Book…" in the same
  menu unpacks a small sample book into a folder you choose and opens it,
  so chapters and articles can be seen in action.
- **Book management.** Every chapter and article row in the book sheet
  now has a menu to Rename, Move Up / Move Down, or Delete it. Reordering
  is written back to the filenames — the whole group is renumbered with
  tidy "01-", "02-" prefixes — so the order is real, portable, and visible
  in any file manager.
- **Compile a book to PDF.** The book's new menu renders the entire book —
  a title page, then every chapter and article in reading order, each
  starting on a fresh page — through the same PDF pipeline as a single
  document, ready to share or save as "&lt;Book name&gt;.pdf".
- **Export a book as EPUB.** "Export as EPUB…" packages the book as a
  standard EPUB 3 — chapters and articles in reading order with a proper
  table of contents — that opens in Play Books and other readers. Math
  formulas and Mermaid / PlantUML diagrams are rendered by the app's own
  offline engines and embedded as images, so they display in any reader.

### Changed

- **Paper is white.** Printouts and PDFs no longer carry the on-screen
  paper tint — the tinted block ended mid-page against the white A4
  margins — and always use the light ink, even from a dark-mode device:
  the whole page is one color, the way a manuscript prints. The preview
  keeps its warm paper and dark theme on screen.
- The "Print / Save as PDF…" menu item is now "Print…", mirroring iOS — the
  dedicated PDF actions above are the way to get a PDF.
- Printed and exported documents now use a smaller body size (11 pt, down
  from 13 pt) — standard print typography that fits more of the document per
  page — and long code lines wrap instead of being clipped at the code
  block's edge (on screen they scroll; paper can't). The on-screen preview
  is unchanged.

### Fixed

- **Legacy text files decode correctly.** A legacy-encoded file without a
  byte-order mark (Windows-1251 Cyrillic, say) could be misread as UTF-16 —
  mojibake that the autosave would then have baked into the file as UTF-8.
  UTF-16 is now only detected by its BOM, and Windows-1251 joined the
  decode fallbacks, so such files open as the text they are.

## [1.1] — 2026-07-05

### Added

- **Math, Mermaid and PlantUML in the preview.** The rendered preview now draws
  TeX/LaTeX math — `$…$` inline and `$$…$$` display, plus ` ```math ` blocks,
  the way GitHub does — as well as **Mermaid** graphs (` ```mermaid `) and
  **PlantUML** diagrams (` ```plantuml `). Everything renders **on-device** from
  bundled engines: no network (the app still declares zero permissions, not even
  INTERNET), no accounts, nothing leaves your device. The same rendering flows
  through to Print / Save as PDF.

### Changed

- The rendered preview now uses the same HTML/WebView rendering as Print / Save
  as PDF (previously a separate native Compose renderer), so the preview and the
  exported document are identical.

## [1.0] — 2026-07-02

### Added

- Initial release: the Android port of the iOS / macOS
  [md](https://github.com/nettrash/md) Markdown editor, built in Kotlin +
  Jetpack Compose with no third-party dependencies. Targets **Android 16
  (API 36)**, mirroring the iOS app's modern-baseline strategy.
- Document handling through the **Storage Access Framework**: open, create
  and save `.md` / `.markdown` files anywhere; plain-text files open and
  round-trip. Opens Markdown files via `ACTION_VIEW` ("Open with md") and
  shared text via `ACTION_SEND`. The writable buffer is flushed when the
  app is backgrounded.
- Hand-written block-level Markdown parser (`MarkdownParser`) ported
  byte-faithfully from the Swift original, feeding a Compose renderer
  (`MarkdownView`); inline spans (bold, italic, code, links,
  strikethrough) become an `AnnotatedString` via `MarkdownInline`, with
  tappable links. Covers headings, paragraphs, bullet / ordered / task
  lists (with nesting), fenced code blocks (``` and `~~~`), block quotes
  (nested), GitHub tables with column alignment, and thematic breaks.
- Edit / Split / Preview layout switch as a segmented control in the app
  bar, width-adaptive like iOS: a wide window (tablet, unfolded foldable,
  desktop, or large phone in landscape) offers all three modes — Split
  showing editor and preview side by side and re-rendering live as you type
  (stacking when the Split window itself is narrow) — while a phone-width
  window offers just Edit and Preview, since Split needs horizontal room. The
  layout is remembered, and a remembered Split reappears when the window
  widens again.
- **Typewriter theme.** Warm paper background — "fresh paper" in light
  mode, "carbon paper" in dark — with a serif prose face and a monospace
  code face (Android's stand-ins for American Typewriter / Courier New),
  and a warm-amber accent. Mapped onto Material 3.
- **Print & share.** Print the rendered document through an offscreen
  WebView + Android's PrintManager (the system dialog's "Save as PDF"
  target exports a themed PDF matching light / dark); share the raw
  Markdown source through the system share sheet. No network access.
- Adaptive launcher icon: a transparent cream "md" glyph (American
  Typewriter Bold, matching the iOS / macOS icon) sized inside the adaptive
  safe zone, over a diagonal warm-brown gradient background — supplied at
  xhdpi / xxhdpi / xxxhdpi.
- Unit tests (46 cases) covering the Markdown parser, the
  `MarkdownHtml` export, and the width-adaptive layout rule — including
  regression coverage for setext headings, wrapped list items, `C#`-style
  headings, tab-indented lists and bounded block-quote nesting — run on the
  JVM with `./gradlew test`.
