# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The `versionCode` is auto-incremented on every build by a Gradle finalizer
(mirroring the iOS app's `agvtool bump`) and is not tracked here.

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
