/*
 * DiagramSvg.kt
 * md (Android)
 *
 * The pure side of "export one diagram as a real vector `.svg` file": which
 * blocks a document offers, and the fix-up that turns a diagram's rendered root
 * `<svg>` (read out of the offscreen WebView's DOM as outerHTML) into a
 * self-standing SVG document. No WebView and no I/O here — the offscreen
 * capture in between is a five-line DOM read in ui/Exporter — so all of it is a
 * plain JVM unit test. A faithful Kotlin port of the iOS / macOS `DiagramSVG`.
 *
 * Only the three *diagram* engines qualify — Mermaid, Graphviz and PlantUML
 * each render to an inline `<svg>`. Math does NOT: KaTeX lays a formula out as
 * HTML + CSS, never SVG, so a formula has no vector to export and is
 * deliberately never offered.
 *
 * The tag scanning is plain UTF-16 `String` work, not the scalar-careful
 * matching the parser needs: the input is a serializer's ASCII tag syntax,
 * never author prose, so there is no combining-mark hazard here (the same
 * reason the sibling `Epub.kt` scans this engine-generated markup with plain
 * `indexOf`). The one place author text does flow through — the fence info
 * string that picks the engine — is lower-cased and matched exactly as
 * `MarkdownHtml.renderBlock` does, so the offered list pairs index-for-index
 * with the containers that renderer emits.
 */

package me.nettrash.md.markdown

import java.util.Locale

object DiagramSvg {

    enum class Kind { MERMAID, PLANTUML, GRAPHVIZ, PLOT }

    /** One diagram the document offers for SVG export, in document order. */
    data class Diagram(
        /** 0-based position among the document's diagrams — the same order
         *  `querySelectorAll('pre.mermaid, div.plantuml, div.graphviz,
         *  div.plot')` reports the rendered containers in, so the capture step
         *  pulls the matching `<svg>` back out by this index. That selector,
         *  written out in `ui/Exporter.captureDiagramSvg`, is the one this list
         *  must stay in step with: both walk the document in order, both see
         *  exactly the four diagram families, so they pair up index-for-index.
         *
         *  It is **not** the EPUB's rich-container selector, and this pairing is
         *  the reason to say so out loud. `Epub.RICH_MARKERS` leaves `div.plot`
         *  out on purpose — those containers exist to photograph drawings the
         *  markup does not contain, and a plot is already an `<svg>` in the
         *  markup — and it carries the formulas, which this list drops because
         *  a formula has no vector to export. Two selectors, deliberately
         *  different in both directions; adding `plot` to the EPUB's list or
         *  dropping it from the capture query would each shift a figure onto the
         *  wrong ordinal. */
        val ordinal: Int,
        val kind: Kind,
        /** The Graphviz layout program (`dot` / `neato` / …) for a graphviz
         *  diagram; null for the others. Only used for the menu label. */
        val engine: String?,
        /** A short label lifted from the diagram's source — its first non-empty
         *  line — so a reader can tell two diagrams apart in the menu. Empty when
         *  the source has no non-blank line. */
        val label: String,
    ) {
        /** The engine's display name, naming the Graphviz layout when it is not
         *  the default `dot` (a `neato` graph reads quite differently). */
        val typeName: String
            get() = when (kind) {
                Kind.MERMAID -> "Mermaid"
                Kind.PLANTUML -> "PlantUML"
                Kind.GRAPHVIZ -> if (engine != null && engine != "dot") "Graphviz ($engine)" else "Graphviz"
                Kind.PLOT -> "Plot"
            }

        /** The menu row: the type, plus the source label when there is one. */
        val menuTitle: String
            get() = if (label.isEmpty()) typeName else "$typeName: $label"
    }

    /** The diagrams a document offers, in document order.
     *
     *  Mirrors exactly how `MarkdownHtml` decides what becomes a diagram, so
     *  this list pairs index-for-index with the rendered DOM's diagram
     *  containers:
     *   • a raw `.puml` / `.gv` document is one diagram — the whole file (see
     *     `MarkdownHtml.document`, which renders it without parsing Markdown);
     *   • otherwise every fenced block whose info string names Mermaid, PlantUML
     *     or a Graphviz layout — including one nested in a block quote, which
     *     `MarkdownHtml` renders by recursing into the quote, so the walk
     *     recurses too and the quoted diagram keeps its place.
     *  Math fences and every other code block are skipped: a formula is not SVG,
     *  and ordinary code is not a diagram. */
    fun diagrams(source: String): List<Diagram> {
        if (MarkdownHtml.isRawPlantUML(source)) {
            return listOf(Diagram(0, Kind.PLANTUML, null, firstLine(source)))
        }
        if (MarkdownHtml.isRawGraphviz(source)) {
            return listOf(Diagram(0, Kind.GRAPHVIZ, "dot", firstLine(source)))
        }
        val out = ArrayList<Diagram>()
        appendDiagrams(MarkdownParser.parse(source), out)
        return out
    }

    /** Walk a block list in render order, appending each diagram; recurse into
     *  block quotes so a quoted diagram lands in its document-order place
     *  (`MarkdownHtml` renders quoted blocks in line). */
    private fun appendDiagrams(blocks: List<MarkdownBlock>, out: MutableList<Diagram>) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    val classified = classify(block.language) ?: continue
                    out.add(Diagram(out.size, classified.first, classified.second, firstLine(block.code)))
                }
                is MarkdownBlock.Quote -> appendDiagrams(block.blocks, out)
                else -> continue
            }
        }
    }

    /** Classify a fence info string the way `MarkdownHtml.renderBlock` does —
     *  lower-cased with the same `Locale.ROOT`, the same three families, the
     *  same Graphviz alias table — or null for anything that is not a diagram
     *  (math, csv, plain code). Reusing `MarkdownHtml.graphvizEngines` keeps the
     *  two in lockstep: a layout added there is offered here without a second
     *  edit. */
    private fun classify(language: String?): Pair<Kind, String?>? =
        when (val lang = (language ?: "").lowercase(Locale.ROOT)) {
            "mermaid" -> Kind.MERMAID to null
            "plantuml", "puml", "plant-uml" -> Kind.PLANTUML to null
            // A plot is a diagram here even though no engine draws it: the
            // renderer put a real `<svg>` in the container, which is exactly
            // what this command saves. It is also why the capture selector in
            // `ui/Exporter` must list `div.plot` — a container this walk counts
            // and the DOM query does not would shift every later diagram onto
            // the wrong figure.
            "plot" -> Kind.PLOT to null
            else -> MarkdownHtml.graphvizEngines[lang]?.let { Kind.GRAPHVIZ to it }
        }

    /** The first non-empty line of [source], trimmed and capped so one long
     *  line can't dwarf the menu. Purely cosmetic — a human reads it, nothing
     *  re-parses it — so ordinary line splitting and trimming are fine here (the
     *  newline set matches `MarkdownHtml.lines`). */
    private fun firstLine(source: String): String {
        for (raw in source.split("\r\n", "\n", "\r", "", " ", " ")) {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                return if (trimmed.length > 40) trimmed.take(40).trim() + "…" else trimmed
            }
        }
        return ""
    }

    // MARK: - SVG fix-up

    /** Turn a diagram's rendered root `<svg …>…</svg>` (read from the DOM as
     *  outerHTML) into a standalone `.svg` document: guarantee the SVG
     *  namespace, give an unsized root real pixel dimensions from its `viewBox`,
     *  and prepend the XML prolog so the file is a well-formed standalone
     *  document any browser or vector editor opens.
     *
     *  Mermaid emits `width="100%"` and no `height` — fine inside a flowing page
     *  (the page CSS caps it), useless in a file, where it renders at zero or
     *  full-viewport height. Graphviz and PlantUML already write absolute
     *  `width`/`height`, so those are left exactly as the engine drew them. */
    fun standaloneDocument(svg: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + withResolvedSize(inNamespaced(svg))

    /** The root `<svg …>` opening tag as `[start, end)` — from `<svg` to just
     *  past the first `>` — or null if there isn't one. Engine outerHTML never
     *  puts a `>` inside the root tag's attribute values, so the first `>`
     *  really does close it. */
    private data class TagRange(val start: Int, val end: Int)

    private fun openingTag(svg: String): TagRange? {
        val open = svg.indexOf("<svg")
        if (open < 0) return null
        val close = svg.indexOf('>', open + 4)
        if (close < 0) return null
        return TagRange(open, close + 1)
    }

    /** Ensure the root carries the default SVG namespace so the standalone file
     *  is well-formed. Both engines already declare it, but a file must not lean
     *  on that. */
    private fun inNamespaced(svg: String): String {
        val tag = openingTag(svg) ?: return svg
        if (attribute("xmlns", svg.substring(tag.start, tag.end)) != null) return svg
        // Right after `<svg`, before the other attributes.
        val at = tag.start + 4
        return svg.substring(0, at) + " xmlns=\"http://www.w3.org/2000/svg\"" + svg.substring(at)
    }

    /** Give the root real dimensions when it lacks them. If both `width` and
     *  `height` are already absolute lengths the engine sized it (Graphviz,
     *  PlantUML) — leave it untouched. Otherwise, when a 4-number `viewBox` is
     *  present, set `width`/`height` to the viewBox's own width and height,
     *  which is what makes a Mermaid `width="100%"` file open at its true size. */
    private fun withResolvedSize(svg: String): String {
        val tag = openingTag(svg) ?: return svg
        val tagText = svg.substring(tag.start, tag.end)
        if (isAbsoluteLength(attribute("width", tagText)) &&
            isAbsoluteLength(attribute("height", tagText))
        ) {
            return svg
        }
        val box = viewBox(tagText) ?: return svg
        if (box.size != 4) return svg
        var newTag = setAttribute("width", box[2], tagText)
        newTag = setAttribute("height", box[3], newTag)
        return svg.substring(0, tag.start) + newTag + svg.substring(tag.end)
    }

    /** The value of a whole attribute `name="…"` (or `name='…'`) inside an
     *  opening tag as `[start, end)`. The leading space is load-bearing: it
     *  matches only a whole attribute, so `width` never captures `stroke-width`. */
    private data class ValueRange(val start: Int, val end: Int)

    private fun attributeValueRange(name: String, tag: String): ValueRange? {
        for (quote in charArrayOf('"', '\'')) {
            val key = " $name=$quote"
            val at = tag.indexOf(key)
            if (at < 0) continue
            val valueStart = at + key.length
            val close = tag.indexOf(quote, valueStart)
            if (close < 0) continue
            return ValueRange(valueStart, close)
        }
        return null
    }

    private fun attribute(name: String, tag: String): String? =
        attributeValueRange(name, tag)?.let { tag.substring(it.start, it.end) }

    /** Set `name`'s value, or add `name="value"` after `<svg` when absent. */
    private fun setAttribute(name: String, value: String, tag: String): String {
        val range = attributeValueRange(name, tag)
        if (range != null) return tag.substring(0, range.start) + value + tag.substring(range.end)
        // Past "<svg".
        return tag.substring(0, 4) + " $name=\"$value\"" + tag.substring(4)
    }

    /** Whether an attribute value is an absolute SVG length: present, and a
     *  number (optionally with a unit like `pt`/`px`), but not a percentage. A
     *  missing value and `width="100%"` are both "not absolute", which is
     *  exactly what makes a Mermaid root get resized and a Graphviz root not. */
    private fun isAbsoluteLength(value: String?): Boolean {
        val v = value?.trim() ?: return false
        if (v.isEmpty() || v.endsWith("%")) return false
        val first = v.first()
        return first == '.' || first.isDigit()
    }

    /** The `viewBox`'s space/comma-separated tokens, or null. Empty tokens are
     *  dropped (matching Swift's `split(whereSeparator:)`), so a run of spaces
     *  doesn't manufacture a phantom fifth token. */
    private fun viewBox(tag: String): List<String>? =
        attribute("viewBox", tag)?.split(' ', ',')?.filter { it.isNotEmpty() }
}
