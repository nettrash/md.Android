# md for Android

The simplest Markdown editor for Android. Write Markdown on one side and
see it rendered on the other, or switch to a full-screen **Edit** or
**Preview**. Built in Kotlin + Jetpack Compose on top of the Storage
Access Framework, with a hand-written Markdown renderer. **No third-party
dependencies, no accounts, no servers** — your files live wherever you
keep them.

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
  md") or shared text from any app.
- **Live preview.** A built-in renderer covers the everyday Markdown you
  actually write:
  - Headings (`#`–`######`)
  - **Bold**, *italic*, `inline code`, [links](https://nettrash.me) and
    ~~strikethrough~~
  - Bullet, numbered and **task lists** (`- [ ]` / `- [x]`), with nesting
  - Fenced code blocks (```` ``` ```` and `~~~`), with horizontal scroll
  - Block quotes (including nested)
  - GitHub-style tables, with column alignment
  - Thematic breaks (`---`)
- **Three layouts.** *Edit*, *Split* (side by side, re-rendering as you
  type — it stacks on a narrow screen) and *Preview*, chosen with a
  segmented control in the app bar. The layout is remembered.
- **Typewriter feel.** Warm paper background (light "fresh paper" / dark
  "carbon paper") and a serif prose face throughout, with a monospace face
  for code — the Android stand-ins for the iOS app's American Typewriter /
  Courier New.
- **Print & share.** Print the rendered document (the system dialog's
  **Save as PDF** target exports a themed PDF), or share the raw Markdown
  source. No network access.

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

MIT — see [LICENSE](LICENSE). © 2026 nettrash (Ivan Alekseev).
