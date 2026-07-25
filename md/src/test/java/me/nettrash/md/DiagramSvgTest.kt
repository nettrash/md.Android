/*
 * DiagramSvgTest.kt
 * md (Android)
 *
 * The two pure halves of "export one diagram as a standalone SVG" (Feature 1),
 * mirroring the iOS mdTests "Diagram → standalone SVG" section: which blocks a
 * document offers (diagrams yes, math and plain code no), and the fix-up that
 * turns a rendered `<svg>` into a standalone file. The offscreen capture in
 * between is a five-line DOM read in ui/Exporter, device-only, not covered here.
 */

package me.nettrash.md

import me.nettrash.md.markdown.DiagramSvg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramSvgTest {

    @Test fun offersOnlyDiagramsInDocumentOrder() {
        // Inline math, a math fence and a plain code block are all NOT diagrams;
        // the three engine fences are, in document order, each with the ordinal
        // the DOM query will index it by.
        val source = "Inline \$a^2\$ math.\n\n" +
            "```math\nE=mc^2\n```\n\n" +
            "```swift\nlet x = 1\n```\n\n" +
            "```mermaid\ngraph TD; A-->B\n```\n\n" +
            "```dot\ndigraph { a -> b }\n```\n\n" +
            "```plantuml\n@startuml\nA->B\n@enduml\n```"
        val diagrams = DiagramSvg.diagrams(source)
        assertEquals(
            listOf(DiagramSvg.Kind.MERMAID, DiagramSvg.Kind.GRAPHVIZ, DiagramSvg.Kind.PLANTUML),
            diagrams.map { it.kind },
        )
        assertEquals(listOf(0, 1, 2), diagrams.map { it.ordinal })
        assertEquals(listOf(null, "dot", null), diagrams.map { it.engine })
        // The label is the first non-empty source line, so two diagrams read
        // apart in the menu.
        assertEquals("graph TD; A-->B", diagrams[0].label)
        assertEquals("@startuml", diagrams[2].label)
    }

    @Test fun offersNothingWithoutDiagrams() {
        // Prose, a formula and plain code — nothing that renders to an <svg>.
        val none = DiagramSvg.diagrams(
            "# Title\n\nText \$x^2\$ here.\n\n```swift\nlet y = 1\n```\n\n```math\nE=mc^2\n```",
        )
        assertTrue(none.isEmpty())
        assertTrue(DiagramSvg.diagrams("").isEmpty())
    }

    @Test fun recursesIntoQuotesAndCoversLayoutAliases() {
        // A diagram nested in a block quote keeps its place — MarkdownHtml
        // renders quoted blocks in line, so its container is first in the DOM —
        // and a layout-named Graphviz fence is offered with its engine.
        val source = "> ```mermaid\n> graph TD; A-->B\n> ```\n\n" +
            "```neato\ngraph { a -- b }\n```"
        val diagrams = DiagramSvg.diagrams(source)
        assertEquals(listOf(DiagramSvg.Kind.MERMAID, DiagramSvg.Kind.GRAPHVIZ), diagrams.map { it.kind })
        assertEquals(listOf(null, "neato"), diagrams.map { it.engine })
        assertEquals(listOf(0, 1), diagrams.map { it.ordinal })
    }

    @Test fun rawDiagramDocumentIsASingleDiagram() {
        // An opened `.puml` / `.gv` is one whole-file diagram (MarkdownHtml
        // renders it without parsing Markdown), so it is exactly one entry.
        val puml = DiagramSvg.diagrams("@startuml\nA->B\n@enduml")
        assertEquals(listOf(DiagramSvg.Kind.PLANTUML), puml.map { it.kind })
        assertEquals(0, puml.first().ordinal)
        assertEquals("@startuml", puml.first().label)

        val dot = DiagramSvg.diagrams("digraph { a -> b }")
        assertEquals(listOf(DiagramSvg.Kind.GRAPHVIZ), dot.map { it.kind })
        assertEquals("dot", dot.first().engine)
    }

    @Test fun menuTitlesNameEngineAndSourceSnippet() {
        val mermaid = DiagramSvg.Diagram(0, DiagramSvg.Kind.MERMAID, null, "graph TD")
        assertEquals("Mermaid", mermaid.typeName)
        assertEquals("Mermaid: graph TD", mermaid.menuTitle)

        // The default `dot` layout isn't named; a non-default one is.
        val dot = DiagramSvg.Diagram(1, DiagramSvg.Kind.GRAPHVIZ, "dot", "g")
        assertEquals("Graphviz", dot.typeName)
        val neato = DiagramSvg.Diagram(2, DiagramSvg.Kind.GRAPHVIZ, "neato", "")
        assertEquals("Graphviz (neato)", neato.typeName)
        assertEquals("Graphviz (neato)", neato.menuTitle)   // no label → type only

        // A long first line is capped so one diagram can't dwarf the menu.
        val long = DiagramSvg.diagrams("```mermaid\n" + "x".repeat(100) + "\n```")
        assertEquals(1, long.size)
        assertTrue(long[0].label.endsWith("…"))
        assertTrue(long[0].label.length <= 41)              // 40 chars + ellipsis
    }

    @Test fun standaloneSvgResolvesMermaidSizeFromViewBox() {
        // Mermaid's root is `width="100%"` with no height — unusable in a file.
        // The standalone document must carry the XML prolog, keep the SVG
        // namespace, and take real pixel width/height from the viewBox.
        val svg = "<svg id=\"m\" class=\"flowchart\" viewBox=\"0 0 200 100\" " +
            "style=\"max-width: 200px;\" width=\"100%\" " +
            "xmlns=\"http://www.w3.org/2000/svg\"><g></g></svg>"
        val out = DiagramSvg.standaloneDocument(svg)
        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"))
        assertTrue(out.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(out.contains("width=\"200\""))
        assertTrue(out.contains("height=\"100\""))
        assertFalse("the percentage width must be gone", out.contains("width=\"100%\""))
    }

    @Test fun standaloneSvgResolvesPercentWidthAndHeight() {
        // A root whose width AND height are BOTH percentages is still unsized —
        // pins that the `%` guard actually decides it (not the mere presence of
        // a height attribute), by resizing from the viewBox rather than leaving
        // "100%" in a file that would then render at zero or full-viewport size.
        val svg = "<svg viewBox=\"0 0 300 150\" width=\"100%\" height=\"100%\" " +
            "xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        val out = DiagramSvg.standaloneDocument(svg)
        assertTrue(out.contains("width=\"300\""))
        assertTrue(out.contains("height=\"150\""))
        assertFalse("neither percentage dimension may survive", out.contains("100%"))
    }

    @Test fun firstLineLabelCapsAtFortyCharacters() {
        // The menu label caps a long first line at 40 characters + an ellipsis,
        // and pins the boundary exactly: 40 characters pass through whole, 41
        // are capped. (The source snippet is cosmetic, but the cap boundary is
        // still worth nailing so it can't drift.)
        val forty = "a".repeat(40)
        val atForty = DiagramSvg.diagrams("```mermaid\n$forty\n```").first().label
        assertEquals(forty, atForty)                       // whole, no ellipsis
        assertFalse(atForty.endsWith("…"))

        val overByOne = DiagramSvg.diagrams("```mermaid\n${"a".repeat(41)}\n```").first().label
        assertEquals(41, overByOne.length)                 // 40 + ellipsis
        assertTrue(overByOne.endsWith("…"))
    }

    @Test fun standaloneSvgLeavesSizedRootAloneButAddsProlog() {
        // Graphviz / PlantUML already write absolute width/height, so the
        // viewBox must NOT overwrite them — only the prolog is added.
        val svg = "<svg width=\"120pt\" height=\"48pt\" viewBox=\"0.00 0.00 120.00 48.00\" " +
            "xmlns=\"http://www.w3.org/2000/svg\"><g/></svg>"
        val out = DiagramSvg.standaloneDocument(svg)
        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"))
        assertTrue(out.contains("width=\"120pt\""))
        assertTrue(out.contains("height=\"48pt\""))
        assertFalse("the viewBox must not resize a sized root", out.contains("width=\"120.00\""))
    }

    @Test fun standaloneSvgAddsNamespaceWhenMissing() {
        // A root without a default namespace must gain one, without disturbing
        // an already-absolute size.
        val svg = "<svg viewBox=\"0 0 10 10\" width=\"10\" height=\"10\"><g/></svg>"
        val out = DiagramSvg.standaloneDocument(svg)
        assertTrue(out.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(out.contains("width=\"10\""))
        assertTrue(out.contains("height=\"10\""))
        // A namespaced root is left with exactly one declaration.
        val already = DiagramSvg.standaloneDocument(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"5\" height=\"5\"></svg>",
        )
        assertEquals(1, already.split("xmlns=").size - 1)
    }

    @Test fun widthGuardDoesNotCaptureStrokeWidth() {
        // The leading-space guard on the attribute scan means a root that
        // carries `stroke-width` but no real `width` is still treated as
        // unsized — the scan must not read the stroke's value as the width —
        // so it resizes from the viewBox rather than adopting "2".
        val svg = "<svg viewBox=\"0 0 60 40\" stroke-width=\"2\"><path/></svg>"
        val out = DiagramSvg.standaloneDocument(svg)
        assertTrue(out.contains("width=\"60\""))
        assertTrue(out.contains("height=\"40\""))
        // The root width is a whole attribute (leading space); it must be 60,
        // never the stroke's 2 — checked in the leading-space form so the test
        // isn't fooled by `stroke-width="2"` the way the scan mustn't be.
        assertTrue(out.contains(" width=\"60\""))
        assertFalse("the stroke width must not become the root width", out.contains(" width=\"2\""))
        // The stroke-width attribute itself is left exactly as it was.
        assertTrue(out.contains("stroke-width=\"2\""))
    }
}
