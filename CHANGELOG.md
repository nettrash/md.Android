# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The `versionCode` is auto-incremented on every build by a Gradle finalizer
(mirroring the iOS app's `agvtool bump`) and is not tracked here.

## [1.2] — Unreleased

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
