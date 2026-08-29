# md for Android

The simplest Markdown editor for Android. Write Markdown on one side and
see it rendered on the other, or switch to a full-screen **Edit** or
**Preview**. Built in Kotlin + Jetpack Compose on top of the Storage
Access Framework, with a hand-written Markdown renderer. **No accounts, no
servers** — your files live wherever you keep them. The Kotlin side is
dependency-light: nothing beyond AndroidX / Jetpack Compose and Material,
and the only vendored code is the offline math / diagram engines under
`md/src/main/assets/rich/` (KaTeX with the mhchem chemistry extension,
Mermaid, Graphviz, PlantUML, and highlight.js for code).

> This is the Android port of [**md**](https://github.com/nettrash/md), the
> iPhone / iPad editor (and its native [macOS](https://github.com/nettrash/md.macOS)
> sibling). All three share the same hand-written block parser, renderer
> and themed HTML export; this port reimplements them in Kotlin and draws
> with Compose. Documents are handled through the Storage Access Framework
> — Android's equivalent of the iOS document architecture.

## Features

- **Document-based.** Open, create and save `.md` / `.markdown` files
  anywhere through the Storage Access Framework (the system file picker),
  with the buffer flushed when the app is backgrounded. Plain-text files
  open too. Open Markdown files handed in from a file manager ("Open with
  md") or shared text from any app. A **TextPack** (`.textpack`, a zipped
  TextBundle — the Markdown-with-images container Ulysses, iA Writer and Bear
  write) opens too, imported as its text for editing (its own `assets/`
  images aren't shown in the preview; a bare `.textbundle` folder isn't
  opened directly).
- **Live preview.** A built-in renderer covers the everyday Markdown you
  actually write:
  - Headings (`#`–`######`)
  - **Bold**, *italic*, `inline code`, [links](https://nettrash.me) and
    ~~strikethrough~~
  - Bullet, numbered and **task lists** (`- [ ]` / `- [x]`), with nesting
  - Fenced code blocks (```` ``` ```` and `~~~`), with horizontal scroll —
    **syntax-highlighted** in md's own quiet paper palette when the fence
    names a language (`kotlin`, `js`, …); a bare fence stays plain
  - Block quotes (including nested)
  - GitHub-style tables, with column alignment
  - **CSV / TSV blocks** (` ```csv `, ` ```tsv `) — data pasted straight
    out of a spreadsheet drawn as a table, quoted fields and all, with
    all-number columns lined up on the right; the source stays the data,
    so it can be replaced wholesale when the numbers change
  - Thematic breaks (`---`)
  - YAML / TOML **front matter** (`---` … `---` or `+++` … `+++`) at the
    very top of a file — recognised as metadata and hidden from the page,
    print and PDF, instead of showing up as a rule and stray text
  - **Footnotes** (`[^id]` in the text, `[^id]: the note` on a line of its
    own) — gathered under a rule at the foot of the rendered page and
    numbered in the order a reader meets them, each reference linking down
    to its note and each cited note linking back
- **Math and diagrams.** TeX/LaTeX math (`$…$`, `$$…$$` and ` ```math `) —
  with **chemistry** notation (`\ce{…}` / `\pu{…}`) via the bundled mhchem
  extension — plus **Mermaid** (` ```mermaid `), **Graphviz** (` ```dot `, ` ```graphviz `
  or ` ```gv `, and every layout program — `neato`, `circo`, `fdp`, `sfdp`,
  `twopi`, `osage`, `patchwork` — usable as the block language) and
  **PlantUML** (` ```plantuml `), all drawn on-device by bundled engines and
  carried through to print and Save as PDF. A raw `.puml` or `.gv` file
  handed in from a file manager opens and renders as the diagram it
  describes, source still editable.
- **Plots.** A ` ```plot ` fence is drawn as a chart: functions of `x`,
  parametric curves and plain `x,y` points, several series to one figure,
  each in its own colour and named in a legend. The directives are the
  ranges `x:` and `y:` (`auto` unless you give it one), `title:`,
  `xlabel:`, `ylabel:`, `legend:`, `grid:`, `axes:`, `width:`, `height:`
  and `samples:`; the expression language has the usual arithmetic and
  comparisons, the constants `pi` and `e`, and `sin cos tan asin acos atan
  sinh cosh tanh asinh acosh atanh sqrt cbrt abs exp exp2 ln log2 log10
  floor ceil round atan2 pow hypot`. Unlike the blocks above it needs no
  engine: the app draws the figure itself, as plain SVG, so nothing is
  bundled for it, nothing is downloaded, and it adds nothing to the app's
  size. The vector carries through to the preview, print, Save as PDF,
  the exported HTML and the **EPUB** — where it is the one rich block that
  stays a vector, the rest being written into the book as pictures — and a
  single plot can be saved with **Export Diagram as SVG**. A LaTeX export
  keeps the fence's source under a comment, the same treatment Mermaid,
  Graphviz and PlantUML get there. A fence that can't be read shows one
  `plot: …` line above its own source, rather than a hole or an error box.
- **Three layouts.** *Edit*, *Split* (side by side, re-rendering as you
  type — it stacks when the window is too narrow for two columns) and
  *Preview*, chosen with a segmented control in the app bar. The layout is
  remembered **for each file**, so a document comes back in the one you
  left it in; the 200 most recently opened are kept, on the device. A file
  the app hasn't seen before opens in Split where there is room for Split —
  a tablet, an unfolded foldable, a desktop window — and in Preview on a
  phone, where Split isn't offered anyway; a new or empty document opens in
  Edit. Articles in a book are the exception, and keep one layout, so
  stepping from chapter to chapter doesn't change the pane you're in.
- **Typewriter feel.** Warm paper background (light "fresh paper" / dark
  "carbon paper") and a serif prose face throughout, with a monospace face
  for code — the Android stand-ins for the iOS app's American Typewriter /
  Courier New.
- **Print & share.** Print the rendered document (the system dialog's
  **Save as PDF** target exports a themed PDF), or share and export it as
  a themed PDF at a page size of your choosing — A4, A5, US Letter or
  Legal, or a print-on-demand trim size (6 × 9″, 5 × 8″, 5.5 × 8.5″), the
  choice remembered and applied to the book compile too — export it as one
  self-contained `.html` file that opens anywhere with nothing beside it
  (diagrams as drawings, formulas as selectable text), export it as an
  **EPUB** e-book with the document's own headings as its table of
  contents, export it as LaTeX `.tex` source (formulas as the `$…$` you
  typed rather than a picture of them, ready to paste into a paper), export
  a single **diagram** (Mermaid, Graphviz, PlantUML or a plot — math is
  HTML text, not a drawing, so it isn't offered) as a standalone `.svg`
  vector file,
  export the document as a **TextPack** with any local images it references
  gathered into the pack's `assets/`, or share the raw Markdown source. No
  network access.

## Platform

- Android **16 (API 36)** or later.

## Build

```bash
# Build a debug APK
./gradlew :md:assembleDebug

# Run the JVM unit tests (parser + HTML export)
./gradlew :md:testDebugUnitTest

# Lint
./gradlew :md:lint
```

Requires the Android SDK (set `sdk.dir` in `local.properties`) and JDK 21
(the Gradle daemon is configured to provision it). The `versionCode`
auto-increments on every `assemble` / `bundle`, mirroring the iOS app's
`agvtool bump`.

## License

MIT — see [LICENSE](LICENSE). © 2026 nettrash.
