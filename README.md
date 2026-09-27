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

- **Document-based.** Open, create and save Markdown files anywhere
  through the Storage Access Framework (the system file picker), with the
  buffer flushed when the app is backgrounded. Every Markdown spelling
  opens — `.md`, `.markdown`, `.mdown`, `.markdn`, `.mdtext`, `.mdtxt`,
  `.mkd`, `.mkdn`, `.mdwn`, `.mkdown` — as do plain-text files (`.txt`,
  `.text`). Open Markdown files handed in from a file manager ("Open with
  md") or shared text from any app; md is offered for each of those
  extensions by name, so a file manager that types `.mkd` as a generic
  binary still hands it over (extensions are matched as written — lower
  case, which is what every tool writes). A **TextPack** (`.textpack`, a zipped
  TextBundle — the Markdown-with-images container Ulysses, iA Writer and Bear
  write) opens too, imported as its text for editing (its own `assets/`
  images aren't shown in the preview; a bare `.textbundle` folder isn't
  opened directly). A file handed in that way **opens for editing** and
  autosaves like any other whenever the app that sent it granted write
  access — md is offered for "Edit with md" as well as "Open with md". One
  handed over read-only says so beside its name, with an **Edit…** action
  that re-opens it through the picker.
- **Nothing is lost without a word.** A buffer with unsaved changes and
  nowhere to autosave them — an untitled draft, text shared in from another
  app, an imported TextPack, a read-only file — asks before it is replaced:
  **Save**, **Discard** or **Cancel**, whether the replacement is New,
  Open…, an example, or a document another app just handed over, and on Back
  as well. A document with a file behind it is never in that position: its
  edits are a beat from disk, so it keeps saving and leaving in silence.
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

  The preview renders in a process of its own, and that process can be taken
  away — a huge diagram, or the system reclaiming memory. md brings it back
  by itself; if it stops twice in a row, the pane says so in one line and
  comes back as soon as you edit the document, instead of taking the app
  down with it.
- **Math and diagrams.** TeX/LaTeX math (`$…$`, `$$…$$` and ` ```math `) —
  with **chemistry** notation (`\ce{…}` / `\pu{…}`) via the bundled mhchem
  extension — plus **Mermaid** (` ```mermaid `), **Graphviz** (` ```dot `, ` ```graphviz `
  or ` ```gv `, and every layout program — `neato`, `circo`, `fdp`, `sfdp`,
  `twopi`, `osage`, `patchwork` — usable as the block language) and
  **PlantUML** (` ```plantuml `), all drawn on-device by bundled engines and
  carried through to print and Save as PDF. A raw PlantUML file (`.puml`,
  `.plantuml`, `.iuml`, `.pu`) or Graphviz file (`.gv`) handed in from a
  file manager opens and renders as the diagram it describes, source still
  editable.
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
  single plot can be saved with **Export ▸ Diagram as SVG**. A LaTeX export
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
- **Find and Replace.** The **Find** button in the top bar (or Ctrl+F on a
  hardware keyboard) opens a bar under the
  app bar with a query box,
  Previous / Next, a replacement box, **Replace** and **All**. Matching
  ignores case, wraps around the end of the document and takes the query
  literally — there are no regular expressions and no options to get wrong,
  the same one rule md uses on every platform. Replace changes the match you
  are standing on and moves to the next, so pressing it repeatedly walks the
  document; **All** replaces every match in one go, and a single Undo takes
  the whole thing back. Searching from Preview brings the editor on screen
  for as long as you are searching and puts you back in Preview when you
  close the bar, without changing the layout the file is remembered in.
- **Hardware keyboard.** The chords are md's own, the same on Android,
  Windows, the Mac and the iPad: Ctrl+N new, Ctrl+O open, Ctrl+S save,
  Ctrl+Shift+S save as, Ctrl+P print, Ctrl+1 / Ctrl+2 / Ctrl+3 for the three
  layouts, Ctrl+Shift+B for the book, Ctrl+F to find, and Ctrl+Alt+↑ /
  Ctrl+Alt+↓ to step to the previous or next article of the open book. They
  answer wherever you are working — with the caret in the editor, in the find
  bar, or reading in Preview. The editor keeps its own keys — Ctrl+A, Ctrl+Z,
  Ctrl+Y, Ctrl+X, Ctrl+C and Ctrl+V are the text field's and md never takes
  them.
- **Typing.** Return inside a list continues it — the next bullet, the next
  number, an empty task box, the `>` of a quote, a fresh row under a table —
  and Return on an empty item ends the list instead; a hardware
  Shift+Return always inserts a plain line break. The first letter of every
  line and of every sentence (after `.`, `!` or `?` and a space) is
  capitalized as you type, Markdown-aware: not inside a code fence, a code
  span, math, a table or a link address, and never a URL, a path or an
  `@handle`. md does the capitalizing, so the keyboard's own sentence
  capitalization is off — which is what keeps code fences lowercase. To keep
  a word lowercase at a sentence start (`md`, `iOS`, `npm`), delete the
  capital md wrote and type the letter again; it stays lowercase. Both are
  switches on the overflow menu's **Typing** page, **Continue Lists and Tables** and
  **Capitalize Sentences**, on by default and applied to the next keystroke.
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

- Android **12 (API 31)** or later.
- Mermaid diagrams need Android System WebView **94 or later**, which Google
  Play keeps current on its own. On a WebView that never updates (Android 12
  shipped with 91), a Mermaid block shows its source text; the other engines —
  Graphviz, PlantUML, KaTeX, the plots — draw regardless.

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
