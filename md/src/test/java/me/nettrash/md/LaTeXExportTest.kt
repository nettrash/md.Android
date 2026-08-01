/*
 * LaTeXExportTest.kt
 * md (Android)
 *
 * The `.tex` export — pure string work with no rendering behind it, so it
 * is covered densely: every block kind, the escaping table, and the
 * handful of places where LaTeX's own syntax bites back (an optional
 * argument eaten off the front of an item, a code block that closes its
 * own verbatim environment). Mirrors the iOS mdTests "LaTeX export (.tex)"
 * section test for test, so a divergence between the platforms shows up as
 * a failing assertion rather than as two different papers.
 *
 * The few tests with no iOS counterpart are marked: they pin the places
 * where a Kotlin Char (a UTF-16 unit) and a Swift Character (a grapheme
 * cluster) could part company, and where the JVM's regex classes and
 * Android's ICU ones disagree.
 */

package me.nettrash.md

import me.nettrash.md.book.ArticleContent
import me.nettrash.md.book.BookContent
import me.nettrash.md.book.ChapterContent
import me.nettrash.md.markdown.ColumnAlignment
import me.nettrash.md.markdown.LaTeXExport
import me.nettrash.md.markdown.MarkdownHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LaTeXExportTest {

    /** The body between `\begin{document}` and `\end{document}`, trimmed —
     *  most assertions are about the content, not the preamble. */
    private fun texBody(source: String): String {
        val tex = LaTeXExport.document(source)
        val opening = "\\begin{document}\n"
        val start = tex.indexOf(opening)
        val end = tex.lastIndexOf("\n\\end{document}")
        assertTrue("the document is not wrapped in a document environment", start >= 0 && end >= 0)
        return tex.substring(start + opening.length, end).trim()
    }

    /** The preamble: everything before `\begin{document}`. */
    private fun texPreamble(source: String): String {
        val tex = LaTeXExport.document(source)
        val start = tex.indexOf("\\begin{document}")
        return if (start < 0) tex else tex.substring(0, start)
    }

    private fun occurrences(text: String, part: String): Int = text.split(part).size - 1

    // escaping

    @Test fun escapesEverySpecialCharacter() {
        // The ten characters TeX reserves. Three of them have no backslash
        // form at all and need a command instead — a leading backslash on
        // `~` or `^` would be an accent waiting for its letter.
        assertEquals("\\#", LaTeXExport.escape("#"))
        assertEquals("\\$", LaTeXExport.escape("$"))
        assertEquals("\\%", LaTeXExport.escape("%"))
        assertEquals("\\&", LaTeXExport.escape("&"))
        assertEquals("\\_", LaTeXExport.escape("_"))
        assertEquals("\\{", LaTeXExport.escape("{"))
        assertEquals("\\}", LaTeXExport.escape("}"))
        assertEquals("\\textasciitilde{}", LaTeXExport.escape("~"))
        assertEquals("\\textasciicircum{}", LaTeXExport.escape("^"))
        assertEquals("\\textbackslash{}", LaTeXExport.escape("\\"))
    }

    @Test fun escapeDoesNotEscapeItsOwnOutput() {
        // The regression a chain of `replace` calls would produce: `\`
        // becomes `\textbackslash{}`, whose own braces and backslash are
        // then escaped again and the reader sees the command.
        assertEquals("a\\textbackslash{}b", LaTeXExport.escape("a\\b"))
        assertEquals("\\textasciitilde{}\\textasciicircum{}", LaTeXExport.escape("~^"))
        // One pass, in order, whatever the mix.
        assertEquals(
            "100\\% of \\{a\\_b\\} \\& \\textbackslash{}c " +
                "\\textasciitilde{} \\textasciicircum{} \\$\\#",
            LaTeXExport.escape("100% of {a_b} & \\c ~ ^ \$#"),
        )
    }

    @Test fun escapeLeavesUnreservedPunctuationAlone() {
        // `<`, `>` and `|` are not TeX specials — they only pick the wrong
        // glyph under an encoding nothing here selects — and the three
        // ports have to agree on this table character for character.
        assertEquals("a < b > c | d", LaTeXExport.escape("a < b > c | d"))
    }

    @Test fun escapeWalksCharactersNotGraphemeClusters() {
        // A special followed by a combining mark is a single grapheme, and
        // Swift's `Character` is a grapheme cluster: a walk over those
        // would find `#\u{FE0F}\u{20E3}` and not `#`, and leave the
        // parameter character raw. Kotlin's Char is a UTF-16 unit and has
        // never had that problem — these pin the agreed answer, so the two
        // ports cannot drift apart on it again.
        assertEquals("\\%\u0301", LaTeXExport.escape("%\u0301"))
        assertEquals("\\#\uFE0F\u20E3", LaTeXExport.escape("#\uFE0F\u20E3"))
        assertEquals("a\\%\u0301b", LaTeXExport.escapeURL("a%\u0301b"))
        // Whole-document form: the sentence after the special survives.
        assertEquals(
            "Fifty\\%\u0301 percent, and the rest survives.",
            texBody("Fifty%\u0301 percent, and the rest survives."),
        )
    }

    @Test fun percentDecodesAnImagePath() {
        // `my%20dir` is a directory on no disk anywhere: an editor writes
        // it when the author drags in a file whose folder has a space in
        // its name, and they meant `my dir`.
        assertEquals("my dir/a.png", LaTeXExport.percentDecoded("my%20dir/a.png"))
        assertEquals("a/b c.png", LaTeXExport.percentDecoded("a%2Fb%20c.png"))
        // Multi-byte sequences come back as the character they encode.
        assertEquals("\u041f.png", LaTeXExport.percentDecoded("%D0%9F.png"))
        // What is not percent-encoding is not a mistake to correct — it is
        // a file name with a `%` in it, handed back exactly as it came.
        // (`URLDecoder` would also turn every `+` into a space here.)
        assertEquals("100%.png", LaTeXExport.percentDecoded("100%.png"))
        assertEquals("a%zzb", LaTeXExport.percentDecoded("a%zzb"))
        assertEquals("trailing%2", LaTeXExport.percentDecoded("trailing%2"))
        // Bytes that are not UTF-8 are not repaired into U+FFFD either —
        // that would put a replacement character into a file name.
        assertEquals("a%C0%80b", LaTeXExport.percentDecoded("a%C0%80b"))
        assertEquals("a+b c.png", LaTeXExport.percentDecoded("a+b%20c.png"))
        assertEquals("plain.png", LaTeXExport.percentDecoded("plain.png"))
    }

    @Test fun urlEscapeGuardsOnlyWhatBreaksTheArgument() {
        // hyperref reads its URL argument with its own catcodes, so an
        // underscore or an ampersand arrives intact; escaping those would
        // put backslashes into the link itself.
        assertEquals(
            "https://x.com/a_b?c=1&d=2",
            LaTeXExport.escapeURL("https://x.com/a_b?c=1&d=2"),
        )
        // A comment character would swallow the rest of the line and a
        // parameter character is illegal there, so both are escaped.
        assertEquals(
            "https://x.com/100\\%25\\#top",
            LaTeXExport.escapeURL("https://x.com/100%25#top"),
        )
        // An unbalanced brace would end the argument early.
        assertEquals("a\\{b\\}c", LaTeXExport.escapeURL("a{b}c"))
    }

    // preamble

    @Test fun plainDocumentHasShortPreamble() {
        // Nothing but the class and the input encoding: a document with no
        // image, no link, no table and no strikethrough owes no packages.
        assertEquals(
            "\\documentclass{article}\n\\usepackage[utf8]{inputenc}\n",
            texPreamble("# Title\n\nJust prose."),
        )
    }

    @Test fun packagesFollowTheFeaturesUsed() {
        assertTrue(texPreamble("![a](x.png)").contains("\\usepackage{graphicx}"))
        assertTrue(texPreamble("[a](https://x.com)").contains("\\usepackage{hyperref}"))
        assertTrue(texPreamble("| a |\n|---|\n| b |").contains("\\usepackage{longtable}"))
        // `normalem` is not decoration: plain ulem redefines `\emph` to
        // underline, so one struck-through word would underline every
        // italic in the document.
        assertTrue(texPreamble("~~gone~~").contains("\\usepackage[normalem]{ulem}"))
        assertFalse(texPreamble("~~gone~~").contains("\\usepackage{ulem}"))
    }

    @Test fun packagesAreNotTriggeredByQuotedCode() {
        // The flags are set by the renderer as it emits each command, not
        // by scanning the finished file — a code block *about* LaTeX is not
        // a link, an image or a table.
        val preamble =
            texPreamble("```\n\\href{x}{y} \\includegraphics{z} \\begin{tabular}{l}\n```")
        assertFalse(preamble.contains("hyperref"))
        assertFalse(preamble.contains("graphicx"))
        assertFalse(preamble.contains("longtable"))
    }

    @Test fun hyperrefIsLoadedLast() {
        // The one package with a documented loading order — it redefines
        // enough of LaTeX's internals that anything after it may break.
        val preamble = texPreamble("[a](https://x.com) ![b](c.png) ~~d~~\n\n| a |\n|---|\n| b |")
        val hyperref = preamble.indexOf("\\usepackage{hyperref}")
        assertTrue("hyperref should be loaded", hyperref >= 0)
        for (other in listOf("graphicx", "ulem", "longtable")) {
            val at = preamble.indexOf(other)
            assertTrue("$other should be loaded", at >= 0)
            assertTrue("$other must come before hyperref", at < hyperref)
        }
    }

    @Test fun cyrillicPullsInFontencWithT2ALast() {
        // Without a Cyrillic encoding pdfTeX does not stop — the document
        // compiles and the Russian is simply not in it.
        val preamble = texPreamble("Привет, мир!")
        assertTrue(preamble.contains("\\usepackage[T1,T2A]{fontenc}"))
        // The order is the whole point: `fontenc` makes the *last* encoding
        // listed the document default, so `[T2A,T1]` would leave the
        // default at T1 and drop exactly the letters it was added for.
        assertFalse(preamble.contains("[T2A,T1]"))
    }

    @Test fun plainEnglishGetsNoFontenc() {
        assertFalse(texPreamble("Plain English prose.").contains("fontenc"))
    }

    @Test fun cyrillicInsideCodeStillPullsInFontenc() {
        // Verbatim content is typeset too — a code sample with a Russian
        // comment needs the encoding as much as prose does.
        assertTrue(texPreamble("```\n// комментарий\n```").contains("fontenc"))
    }

    @Test fun cyrillicOnlyInAPrivateNoteDoesNotPullInFontenc() {
        // A private note never reaches the document, so it cannot make the
        // document need anything.
        assertFalse(texPreamble("English.\n\n<!-- note: Привет -->").contains("fontenc"))
    }

    @Test fun cyrillicScanCoversEveryBlockItClaims() {
        // Each of the four blocks at its first character, because the one
        // that gets forgotten is the low end of the main block — U+0401 Ё
        // is an everyday Russian letter and sits below the alphabet proper.
        assertTrue("Ѐ U+0400", LaTeXExport.containsCyrillic("Ѐ"))
        assertTrue("Ё U+0401", LaTeXExport.containsCyrillic("Ё"))
        assertTrue("ԁ U+0501", LaTeXExport.containsCyrillic("ԁ"))
        assertTrue("U+2DE0", LaTeXExport.containsCyrillic("ⷠ"))
        assertTrue("Ꙁ U+A640", LaTeXExport.containsCyrillic("Ꙁ"))
        assertFalse("just below the block", LaTeXExport.containsCyrillic("Ͽ"))
        assertFalse(LaTeXExport.containsCyrillic("Plain Latin, ancient Greek: α β γ"))
    }

    @Test fun cyrillicScanIsNotFooledBySurrogatePairs() {
        // No iOS counterpart — a Kotlin Char is a UTF-16 unit, so a scan
        // written against code points would have to decode pairs by hand.
        // Both halves of a supplementary character sit in 0xD800…0xDFFF,
        // clear of every Cyrillic block, so the unit-by-unit scan is exact.
        assertFalse(LaTeXExport.containsCyrillic("emoji 😀 and 𝔄 and 中文"))
        assertTrue(LaTeXExport.containsCyrillic("mostly English but ё"))
        // …and the pair itself survives the escaper intact rather than
        // being split into two lone surrogates.
        assertEquals("a 😀 b", LaTeXExport.escape("a 😀 b"))
    }

    // front matter and the title block

    @Test fun frontMatterBecomesTitleBlock() {
        val tex = LaTeXExport.document(
            """
            ---
            title: On Escaping
            author: nettrash
            date: 24 July 2026
            slug: on-escaping
            tags: latex, md
            ---

            Body.
            """.trimIndent()
        )
        assertTrue(tex.contains("\\title{On Escaping}"))
        assertTrue(tex.contains("\\author{nettrash}"))
        assertTrue(tex.contains("\\date{24 July 2026}"))
        assertTrue(tex.contains("\\maketitle"))
        // Everything else is metadata *about* the document and has nowhere
        // to go in a typeset one.
        assertFalse(tex.contains("on-escaping"))
        assertFalse(tex.contains("tags"))
    }

    @Test fun titleBlockWithoutADateDoesNotInventOne() {
        // `\maketitle` stamps today when no date is given — a date nobody
        // wrote into the document.
        assertTrue(LaTeXExport.document("---\ntitle: Untimed\n---\n\nBody.").contains("\\date{}"))
    }

    @Test fun withoutFrontMatterHasNoTitleBlock() {
        val tex = LaTeXExport.document("# Heading\n\nBody.")
        assertFalse(tex.contains("\\title"))
        assertFalse(tex.contains("\\maketitle"))
    }

    @Test fun titleFieldsAreEscaped() {
        val tex = LaTeXExport.document("---\ntitle: 100% & more_stuff\n---\n\nBody.")
        assertTrue(tex.contains("\\title{100\\% \\& more\\_stuff}"))
    }

    @Test fun repeatedFrontMatterKeyKeepsTheFirst() {
        // The parser records every line the author wrote — duplicates and
        // all — and the rest of the app reads the first. The title block
        // does the same rather than silently preferring the last one.
        val tex = LaTeXExport.document("---\ntitle: First\ntitle: Second\n---\n\nBody.")
        assertTrue(tex.contains("\\title{First}"))
        assertFalse(tex.contains("Second"))
    }

    @Test fun frontMatterKeysFoldAgainstTheRootLocale() {
        // No iOS counterpart — a JVM trap, and one this codebase spells out
        // rather than relies on: `String.toLowerCase(Locale.getDefault())`
        // turns "TITLE" into "tıtle" with a dotless ı on a phone set to
        // Turkish, and the title block would vanish on that phone alone.
        // (Kotlin's own `lowercase()` is already locale-independent; the
        // explicit Locale.ROOT is what keeps it that way if the call is
        // ever rewritten, and it is what MarkdownHtml passes too.)
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertTrue(
                LaTeXExport.document("---\nTITLE: Kitap\n---\n\nBody.")
                    .contains("\\title{Kitap}"),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun cyrillicOnlyInTheTitleBlockStillPullsInFontenc() {
        // The title is typeset like any other text, and it is not part of
        // the body the scan walks — so it is scanned in its own right.
        assertTrue(
            texPreamble("---\ntitle: Привет\n---\n\nEnglish body.").contains("fontenc"),
        )
    }

    // headings

    @Test fun headingLevelsMapOntoSectioning() {
        val expected = listOf(
            "\\section{H}", "\\subsection{H}", "\\subsubsection{H}",
            "\\paragraph{H}", "\\subparagraph{H}", "\\subparagraph{H}",
        )
        for (level in 1..6) {
            assertEquals("level $level", expected[level - 1], texBody("#".repeat(level) + " H"))
        }
    }

    @Test fun headingTextIsEscapedAndFormatted() {
        assertEquals("\\section{50\\% \\textbf{off}}", texBody("# 50% **off**"))
    }

    // mathematics

    @Test fun inlineMathPassesThroughUnescaped() {
        // The reason the format exists: the formula comes back as the
        // source the author typed, not as a picture of it.
        assertEquals(
            "Then \$a_1^{2} \\frac{x}{y}\$ follows.",
            texBody("Then \$a_1^{2} \\frac{x}{y}\$ follows."),
        )
    }

    @Test fun displayMathUsesTheBracketForm() {
        assertEquals("\\[x^2\\]", texBody("\$\$x^2\$\$"))
        assertEquals("\\[x^2\\]", texBody("\\[x^2\\]"))
        assertEquals("\\[\nx^2\n\\]", texBody("```math\nx^2\n```"))
        // ```latex and ```tex name the same thing.
        assertEquals("\\[\nx^2\n\\]", texBody("```latex\nx^2\n```"))
        assertEquals("\\[\nx^2\n\\]", texBody("```tex\nx^2\n```"))
    }

    @Test fun parenMathBecomesDollarMath() {
        assertEquals("A \$a_i\$ here.", texBody("A \\(a_i\\) here."))
    }

    @Test fun currencyIsNotMistakenForMathematics() {
        // The same guard the preview uses, so the two agree about what a
        // formula is in the same document.
        assertEquals("It costs \\\$5 and \\\$10.", texBody("It costs \$5 and \$10."))
    }

    @Test fun wordGuardIsSpelledOutRatherThanBackslashW() {
        // `\w` is not one character class but three: ASCII on this JVM,
        // Unicode on Android's ICU engine, and on ICU it takes in every
        // combining mark while leaving out every number that is not a
        // decimal digit. The guard is spelled `[\p{L}\p{N}_]` on all three
        // ports so that the same span is mathematics in all three — which
        // is the whole point of this export.
        //
        // A superscript two is `\p{N}` and is *not* in ICU's `\w`, so it
        // stops what follows being a formula only if the guard is the
        // spelled-out one.
        assertEquals("\u00b2\\\$x\\\$", texBody("\u00b2\$x\$"))
        assertEquals("\u00b2\\_x\\_", texBody("\u00b2_x_"))
        // A combining mark is in ICU's `\w` and is not a letter, a number
        // or an underscore, so it stops nothing.
        assertEquals("e\u0301\$x\$", texBody("e\u0301\$x\$"))
        assertEquals("e\u0301\\emph{x}", texBody("e\u0301_x_"))
    }

    @Test fun mathInsideBackticksStaysCode() {
        // Code wins over math, exactly as in the preview.
        assertEquals("Use \\texttt{\\\$x\\\$} literally.", texBody("Use `\$x\$` literally."))
    }

    // inline formatting

    @Test fun emphasisBecomesCommands() {
        assertEquals(
            "\\textbf{b} \\textbf{b} \\emph{i} \\emph{i} \\sout{s}",
            texBody("**b** __b__ *i* _i_ ~~s~~"),
        )
    }

    @Test fun snakeCaseSurvivesUnderscoreItalic() {
        assertEquals("a\\_b\\_c is one word", texBody("a_b_c is one word"))
    }

    @Test fun underscoreItalicGuardIsUnicodeAware() {
        // No iOS counterpart — the JVM reads `\w` as ASCII and Android's
        // ICU engine reads it as Unicode, and the `(?U)` flag that fixes
        // the one is rejected outright by the other. The guard is spelled
        // `[\p{L}\p{N}_]`, which both engines agree on: a Cyrillic word
        // with an underscore in it is prose on every platform.
        assertEquals("ф\\_em\\_ф", texBody("ф_em_ф"))
    }

    @Test fun inlineCodeIsTexttt() {
        // `\texttt` rather than `\verb`, because this text has to survive
        // inside a section title, a caption and a table cell, where `\verb`
        // is not allowed. Its content is escaped like any other text.
        assertEquals("Type \\texttt{a\\_b \\& c\\%}.", texBody("Type `a_b & c%`."))
    }

    @Test fun linkBecomesHref() {
        assertEquals(
            "See \\href{https://x.com/a_b}{the docs}.",
            texBody("See [the docs](https://x.com/a_b)."),
        )
    }

    @Test fun linkLabelKeepsItsOwnMarkup() {
        // The label stays ordinary text through the link pass, so the
        // emphasis pass afterwards still finds what is inside it.
        assertEquals(
            "\\href{https://x.com}{a \\textbf{bold} label}",
            texBody("[a **bold** label](https://x.com)"),
        )
    }

    @Test fun linkTitleIsConsumedNotPrinted() {
        // A title is a browser tooltip; a typeset page has nowhere to put
        // one — but it must not be left behind as stray prose either.
        assertEquals(
            "\\href{https://x.com}{a}",
            texBody("[a](https://x.com \"hover text\")"),
        )
    }

    @Test fun imageWithAltTextBecomesACaptionedFigure() {
        assertEquals(
            """
            \begin{figure}[ht]
            \centering
            \includegraphics[width=\linewidth]{img/a_1.png}
            \caption{A wide shot}
            \end{figure}
            """.trimIndent(),
            texBody("![A wide shot](img/a_1.png)"),
        )
    }

    @Test fun captionKeepsTheAltTextsOwnMarkup() {
        // The caption is the alt text rendered as inline Markdown, not
        // dropped in raw — and it is rendered as a moving argument, which
        // is what a `\caption` is (LaTeX writes it to the `.lof`).
        assertTrue(
            texBody("![a **bold** shot](x.png)").contains("\\caption{a \\textbf{bold} shot}"),
        )
    }

    @Test fun imageWithoutAltTextIsJustTheGraphic() {
        // No caption, because an empty one prints a bare "Figure 1" — and
        // `\noindent`, because a graphic exactly `\linewidth` wide starting
        // a paragraph is pushed over the margin by the paragraph indent and
        // LaTeX reports an overfull box for every image in the document.
        assertEquals(
            "\\noindent\\includegraphics[width=\\linewidth]{img/a.png}",
            texBody("![](img/a.png)"),
        )
    }

    @Test fun altTextIsRenderedFromTheMarkupTheAuthorWrote() {
        // The alt text is captured from a string the literal-span pass has
        // already tokenised, so rendering it where it stands leaves that
        // pass's tokens inside the caption — where the outer restore never
        // looks, because it has already gone past them. The author's
        // formula then reaches the page as U+E000 ("Missing character:
        // There is no  (U+E000)"), and a code span next to a bold run
        // closes neither ("File ended while scanning use of \@xdblarg").
        //
        // So the caption is a fresh, complete pass over the Markdown they
        // actually wrote.
        assertTrue(
            texBody("![Alt with \$x\$ set](f.png)").contains("\\caption{Alt with \$x\$ set}"),
        )
        assertTrue(
            texBody("![Alt `c` and **b**](f.png)")
                .contains("\\caption{Alt \\texttt{c} and \\textbf{b}}"),
        )
        // Nothing of either pass is left in the file.
        assertFalse(texBody("![Alt with \$x\$ and `c`](f.png)").contains('\uE000'))
        assertFalse(texBody("![Alt with \$x\$ and `c`](f.png)").contains('\uE001'))
    }

    @Test fun imagePathIsPercentDecodedBeforeItIsWritten() {
        assertEquals(
            "\\noindent\\includegraphics[width=\\linewidth]{my dir/a.png}",
            texBody("![](my%20dir/a.png)"),
        )
    }

    @Test fun imagePathLaTeXCannotReadIsSkippedAndNamed() {
        // graphicx reads its argument as a file name, and there is no
        // spelling of `%` or `#` that works in one: raw, the comment
        // character eats the rest of the line; escaped, the compile stops
        // ("Missing endcsname inserted"). Neither is worth a whole
        // document, so the file is skipped and named.
        val body = texBody("Before ![alt](a#b.png) after.")
        assertFalse(body.contains("\\includegraphics"))
        assertTrue("the author's words survive", body.contains("\\emph{alt}"))
        assertTrue(body.contains("% md: image skipped"))
        assertTrue("the author is told which file", body.contains("a#b.png"))
        assertTrue(body.contains("Before"))
        assertTrue("nothing after the image is lost", body.contains("after."))
        // The comment comes last and ends its own line. `%` runs to the end
        // of the physical line, so one in front of the text would swallow
        // the text and one with nothing after it would swallow the rest of
        // a table row.
        assertTrue(body.contains("\\emph{alt}\n% md: image skipped"))
        assertTrue(body.contains("LaTeX can read.\n"))
        // A path that is not percent-encoding keeps its `%` and is refused
        // for it.
        assertTrue(texBody("![](100%.png)").contains("% md: image skipped"))
        // A package is not loaded for a graphic that was never drawn.
        assertFalse(texPreamble("![](a#b.png)").contains("graphicx"))
    }

    // lists

    @Test fun listsUseItemizeAndEnumerate() {
        assertEquals(
            """
            \begin{itemize}
            \item a
            \item b
            \end{itemize}
            """.trimIndent(),
            texBody("- a\n- b"),
        )
        assertEquals(
            """
            \begin{enumerate}
            \item a
            \item b
            \end{enumerate}
            """.trimIndent(),
            texBody("1. a\n2. b"),
        )
    }

    @Test fun nestedListsNestEnvironments() {
        assertEquals(
            """
            \begin{itemize}
            \item a
            \begin{itemize}
            \item b
            \begin{itemize}
            \item c
            \end{itemize}
            \end{itemize}
            \item d
            \end{itemize}
            """.trimIndent(),
            texBody("- a\n  - b\n    - c\n- d"),
        )
    }

    @Test fun listStartingIndentedOpensOnlyOneEnvironment() {
        // Two `\begin{itemize}` in a row is a LaTeX error ("perhaps a
        // missing \item"), and a list whose first item is already indented
        // is exactly what would produce it.
        val body = texBody("  - already indented\n    - deeper")
        assertFalse(
            "no environment may open without an item before it",
            body.contains("\\begin{itemize}\n\\begin{itemize}"),
        )
        assertEquals(2, occurrences(body, "\\begin{itemize}"))
        assertEquals(2, occurrences(body, "\\end{itemize}"))
    }

    @Test fun deepListStopsAtLaTeXsNestingLimit() {
        // LaTeX refuses to nest lists more than four deep. Every item still
        // has to appear — the deepest ones simply share the deepest level
        // LaTeX has.
        val source = (0 until 6).joinToString("\n") { " ".repeat(it * 2) + "- l$it" }
        val body = texBody(source)
        assertEquals(4, occurrences(body, "\\begin{itemize}"))
        for (level in 0 until 6) {
            assertTrue("l$level must survive", body.contains("\\item l$level"))
        }
    }

    @Test fun taskListRendersLiteralCheckboxes() {
        // A literal checkbox rather than a package for one glyph — and the
        // empty group in front of it earns its place: `\item [x]` reads the
        // bracket as the item's optional *label*, which would swallow the
        // checkbox instead of printing it.
        assertEquals(
            """
            \begin{itemize}
            \item {}[\,] open
            \item {}[x] done
            \end{itemize}
            """.trimIndent(),
            texBody("- [ ] open\n- [x] done"),
        )
    }

    @Test fun itemBeginningWithABracketIsGuarded() {
        // Same hazard, no task list in sight.
        assertTrue(texBody("- [draft] not a task").contains("\\item {}[draft]"))
        // …and an item that does not start with one is left alone.
        assertTrue(texBody("- plain").contains("\\item plain"))
    }

    // code and diagrams

    @Test fun codeBlockIsVerbatimAndUnescaped() {
        // Verbatim is verbatim: escaping it would put backslashes on the page.
        assertEquals(
            """
            \begin{verbatim}
            if (a & b) { x_1 = 100%; }
            \end{verbatim}
            """.trimIndent(),
            texBody("```\nif (a & b) { x_1 = 100%; }\n```"),
        )
    }

    @Test fun codeBlockQuotingVerbatimEndIsSplit() {
        // The one real hazard. LaTeX's verbatim terminator is matched as
        // *characters*, so a block that quotes `\end{verbatim}` would close
        // the environment early and spill the rest of the block into the
        // document as LaTeX to execute.
        val body = texBody("```\nbefore\n\\end{verbatim}\nafter\n```")
        assertTrue(
            "the terminator itself must be set with \\verb",
            body.contains("\\verb|\\end{verbatim}|"),
        )
        assertTrue(body.contains("before"))
        assertTrue("nothing after the hazard may be lost", body.contains("after"))
        // Every environment opened is closed: one more `\end{verbatim}`
        // than `\begin{verbatim}` would be the early close itself.
        val opens = occurrences(body, "\\begin{verbatim}")
        val closes = occurrences(body, "\\end{verbatim}")
        assertEquals("the block is split around the terminator", 2, opens)
        assertEquals("the extra one is inside the \\verb", opens + 1, closes)
    }

    @Test fun diagramSourceSurvivesAsVerbatim() {
        // LaTeX has no Mermaid, PlantUML or Graphviz, and md's renderers
        // are JavaScript engines that cannot travel in a .tex file. Dropping
        // the diagram would lose a whole figure without saying so.
        for (language in listOf("mermaid", "plantuml", "dot", "neato")) {
            val body = texBody("```$language\nA -> B\n```")
            assertTrue(
                "$language should name itself in a comment",
                body.startsWith("% $language diagram source"),
            )
            assertTrue(
                "$language source must survive verbatim",
                body.contains("\\begin{verbatim}\nA -> B\n\\end{verbatim}"),
            )
        }
    }

    // tables

    @Test fun tableColumnSpecFollowsTheAlignments() {
        assertEquals(
            """
            \begin{longtable}{lcr}
            \hline
            \textbf{L} & \textbf{C} & \textbf{R} \\
            \hline
            \endhead
            a & b & c \\
            \hline
            \end{longtable}
            """.trimIndent(),
            texBody("| L | C | R |\n|:--|:-:|--:|\n| a | b | c |"),
        )
    }

    @Test fun tableCellsAreEscapedAndKeepInlineMarkup() {
        val body = texBody("| a | b |\n|---|---|\n| 50% | \$x^2\$ **b** |")
        assertTrue(body.contains("50\\% & \$x^2\$ \\textbf{b} \\\\"))
    }

    @Test fun tableRowBeginningWithABracketIsGuarded() {
        // `\\` followed by `[` is a row with a vertical skip, not a row
        // beginning with a bracket.
        assertTrue(texBody("| a |\n|---|\n| [draft] |").contains("{}[draft] \\\\"))
    }

    @Test fun csvBlockBecomesTheSameTable() {
        // The parse and the "a column of figures is right-aligned" rule are
        // shared with the HTML renderer, so the same spreadsheet lands the
        // same way in the PDF and in the .tex.
        val body = texBody("```csv\nName,Qty\nBolt,12\nNut,3.5\n```")
        assertTrue("the numeric column is right-aligned", body.contains("\\begin{longtable}{lr}"))
        assertTrue(body.contains("\\textbf{Name} & \\textbf{Qty} \\\\"))
        assertTrue(body.contains("Bolt & 12 \\\\"))
        // A tab-separated block is the same table.
        assertTrue(texBody("```tsv\nName\tQty\nBolt\t12\n```").contains("\\begin{longtable}{lr}"))
    }

    @Test fun aRowWiderThanItsHeaderKeepsEveryCell() {
        // The column count is the widest row, not the header's: a cell too
        // many would otherwise be dropped on the floor, and the alignment
        // would overrun and take the rest of the document with it. (A
        // Markdown table is levelled by the parser before it gets here; a
        // ```csv block is not.)
        val body = texBody("```csv\na,b\nx,y,z\n```")
        assertTrue(body.contains("\\begin{longtable}{lll}"))
        // The header has nothing to say about the third column, so its
        // cell is empty — and empty is not bold.
        assertTrue(body.contains("\\textbf{a} & \\textbf{b} &  \\\\"))
        assertTrue("the third cell must survive", body.contains("x & y & z \\\\"))
    }

    @Test fun delimitedTableIsTheOneTheHtmlRendererUses() {
        // No iOS counterpart in this form — the shared parse is what keeps
        // the two exports agreeing, so it is asserted directly rather than
        // only through its output.
        val table = MarkdownHtml.delimitedTable("Name,Qty\nBolt,12\nNut,3.5", ',')
        assertTrue(table != null)
        checkNotNull(table)
        assertEquals(listOf("Name", "Qty"), table.header)
        assertEquals(listOf(ColumnAlignment.LEADING, ColumnAlignment.TRAILING), table.alignments)
        assertEquals(listOf(listOf("Bolt", "12"), listOf("Nut", "3.5")), table.rows)
    }

    @Test fun unparseableDelimitedBlockStaysCode() {
        // Nothing the author wrote disappears: a ```csv block that parses
        // to no table is still their text.
        assertEquals("\\begin{verbatim}\n\n\\end{verbatim}", texBody("```csv\n\n```"))
    }

    // quotes, rules and breaks

    @Test fun quotesRecurse() {
        assertEquals(
            """
            \begin{quote}
            outer

            \begin{quote}
            inner
            \end{quote}
            \end{quote}
            """.trimIndent(),
            texBody("> outer\n>\n> > inner"),
        )
    }

    @Test fun thematicBreakAndPageBreak() {
        assertEquals("\\par\\noindent\\hrulefill\\par", texBody("***"))
        assertEquals("a\n\n\\newpage\n\nb", texBody("a\n\n\\newpage\n\nb"))
    }

    @Test fun privateNotesAreDropped() {
        // As in every other output: they live in the editor and the notes
        // panel, never in the document.
        assertEquals("before\n\nafter", texBody("before\n\n<!-- note: private -->\n\nafter"))
    }

    @Test fun softBreaksBecomeLineBreaks() {
        // md shows a soft break as a break in the preview, the HTML and the
        // PDF; the .tex agrees rather than reflowing the paragraph.
        assertEquals("one\\\\\ntwo\\\\\nthree", texBody("one\ntwo\nthree"))
        // Never a trailing `\\` — "there's no line here to end" is an error.
        assertFalse(texBody("one\ntwo").endsWith("\\\\"))
    }

    @Test fun softBreakBeforeABracketIsGuarded() {
        // `\\` followed by `[` is read as `\\[length]`.
        assertEquals("line\\\\\n{}[bracketed]", texBody("line\n[bracketed]"))
    }

    @Test fun softBreakBeforeAnUndefinedFootnoteIsGuarded() {
        // Same hazard, one pass later. While the lines are being split the
        // reference is still a token, so a guard applied there sees no
        // bracket at all and `\\[\textasciicircum{}missing]` reaches LaTeX
        // as a vertical skip ("Illegal unit of measure (pt inserted)").
        // The guard has to run on the restored line, like the other two.
        assertEquals(
            "line one\\\\\n{}[\\textasciicircum{}missing] line two",
            texBody("line one\n[^missing] line two"),
        )
    }

    // footnotes

    @Test fun footnoteIsInlinedAtItsFirstReference() {
        // What a LaTeX footnote *is* — which is why this export has no
        // collected list at the foot of the document the way the HTML has.
        assertEquals(
            "Text\\footnote{The note with \\emph{emphasis}.} here.",
            texBody("Text[^a] here.\n\n[^a]: The note with *emphasis*."),
        )
    }

    @Test fun repeatedFootnoteReferencesCiteTheNumber() {
        // The number is right because every `\footnote` here is emitted in
        // the order it was numbered, so LaTeX's counter and this one agree.
        assertEquals(
            "A\\footnote{one} B\\footnote{two} C\\footnotemark[1].",
            texBody("A[^a] B[^b] C[^a].\n\n[^a]: one\n\n[^b]: two"),
        )
    }

    @Test fun footnoteNumbersFollowReadingOrderNotSpliceOrder() {
        // No iOS counterpart. The rewrite matches forward, builds each
        // replacement in reading order and only then splices from the end
        // of the string back — the two directions are not interchangeable.
        // Build them backwards and the reader meets the notes in one order
        // while they are numbered in another; with three references over
        // two notes the texts land under the wrong marks.
        assertEquals(
            "A\\footnote{n1} B\\footnotemark[1] C\\footnote{n2}.",
            texBody("A[^a] B[^a] C[^b].\n\n[^a]: n1\n\n[^b]: n2"),
        )
    }

    @Test fun undefinedFootnoteReferenceStaysLiteral() {
        // A reference with no definition is not a footnote at all, so it
        // goes back to being the text the author typed.
        assertEquals(
            "Text[\\textasciicircum{}missing] here.",
            texBody("Text[^missing] here."),
        )
    }

    @Test fun uncitedFootnoteIsStillPrinted() {
        // Dropping it would silently discard something the author wrote.
        val body = texBody("Prose.\n\n[^unused]: Nobody points at this.")
        assertTrue(body.contains("% Footnotes defined but never referenced"))
        assertTrue(body.contains("\\footnote{Nobody points at this.}"))
    }

    @Test fun footnoteInsideAFootnoteDegradesToText() {
        // LaTeX cannot nest footnotes; the inner reference becomes the text
        // the author typed, and its definition — now uncited — is printed
        // at the end rather than lost.
        val body = texBody("A[^a].\n\n[^a]: See[^b].\n\n[^b]: The target.")
        assertTrue(body.contains("\\footnote{See[\\textasciicircum{}b].}"))
        assertTrue(body.contains("\\footnote{The target.}"))
    }

    @Test fun footnoteInAHeadingIsProtected() {
        // A section title is a moving argument — LaTeX writes it to the
        // `.toc`, and a bare `\footnote` there is a compile error.
        assertEquals(
            "\\section{Head\\protect\\footnote{note}}",
            texBody("# Head[^a]\n\n[^a]: note"),
        )
    }

    @Test fun footnoteDefinitionRendersNothingWhereItWasWritten() {
        assertEquals("A\\footnote{note}.\n\nB.", texBody("A[^a].\n\n[^a]: note\n\nB."))
    }

    // restricted places
    //
    // A table cell, a footnote's own text and an image caption are places
    // LaTeX will not open a float or a display environment in. Each of
    // these was a compile that stopped at the first one, or a note whose
    // words never reached the page.

    @Test fun displayMathInATableCellIsSetInline() {
        // `\[` inside `tabular` stops the *file*, not the cell. Set inline
        // the formula is smaller than the author asked for; every symbol
        // of it is still on the page.
        val body = texBody("| formula |\n|---------|\n| \$\$x^2\$\$ |")
        assertTrue(body.contains("\$x^2\$ \\\\"))
        assertFalse(body.contains("\\["))
        // The `\[…\]` spelling of the same thing degrades the same way.
        assertTrue(texBody("| a |\n|---|\n| \\[x^2\\] |").contains("\$x^2\$ \\\\"))
    }

    @Test fun displayMathInAFootnoteIsSetInline() {
        assertEquals(
            "A\\footnote{\$x^2\$ ends it.}.",
            texBody("A[^a].\n\n[^a]: \$\$x^2\$\$ ends it."),
        )
    }

    @Test fun imageInATableCellIsNotAFloat() {
        // "LaTeX Error: Not in outer par mode." — and the whole document
        // stops there.
        val body = texBody("| picture |\n|---------|\n| ![A caption](a.png) |")
        assertTrue(
            body.contains("\\includegraphics[width=\\linewidth]{a.png} \\emph{A caption} \\\\"),
        )
        assertFalse(body.contains("\\begin{figure}"))
        assertFalse(body.contains("\\caption"))
    }

    @Test fun imageInAFootnoteIsNotAFloat() {
        // "LaTeX Error: Float(s) lost." — same again.
        val body = texBody("Text[^a] here.\n\n[^a]: See ![A caption](a.png) for detail.")
        assertTrue(
            body.contains(
                "\\footnote{See \\includegraphics[width=\\linewidth]{a.png} " +
                    "\\emph{A caption} for detail.}",
            ),
        )
        assertFalse(body.contains("\\begin{figure}"))
    }

    @Test fun footnoteCitedFromATableCellKeepsItsWords() {
        // A `table` float typesets no footnote text from inside itself:
        // the mark prints, the counter steps, and the note's words are
        // simply not in the PDF. A `longtable` is not a float, so an
        // ordinary `\footnote` sets its own words in the cell where the
        // author put the reference — and there is no `\footnotemark` /
        // `\footnotetext` split left to keep in step.
        val body = texBody("| cell |\n|------|\n| A[^a] |\n\n[^a]: Zarquon lives here.")
        assertTrue(body.contains("A\\footnote{Zarquon lives here.} \\\\"))
        assertFalse(body.contains("\\footnotemark"))
        assertFalse(body.contains("\\footnotetext"))
    }

    @Test fun footnoteNumbersKeepAgreeingAcrossATable() {
        // The counter this file keeps and the one LaTeX keeps have to stay
        // in step across the split: the note after the table is number 2,
        // and a second citation of the first one still says 1.
        val body = texBody(
            """
            | cell |
            |------|
            | A[^a] B[^a] |

            Then C[^b].

            [^a]: first

            [^b]: second
            """.trimIndent(),
        )
        assertTrue(body.contains("A\\footnote{first} B\\footnotemark[1]"))
        assertTrue(body.contains("Then C\\footnote{second}."))
    }

    @Test fun restrictionIsInheritedByAFootnoteCitedFromATable() {
        // A note cited from a table cell is still a footnote, and a float
        // in one is "Float(s) lost" wherever it was cited from.
        val body = texBody("| a |\n|---|\n| A[^a] |\n\n[^a]: \$\$x^2\$\$ and ![cap](a.png)")
        assertTrue(
            body.contains(
                "\\footnote{\$x^2\$ and \\includegraphics[width=\\linewidth]{a.png} " +
                    "\\emph{cap}}",
            ),
        )
        assertFalse(body.contains("\\begin{figure}"))
        assertFalse(body.contains("\\["))
    }

    @Test fun tableEndsAtItsOwnEnd() {
        // Nothing is collected below a table any more: the notes it cites
        // are set in its own cells.
        assertTrue(texBody("| a |\n|---|\n| b |").endsWith("\\end{longtable}"))
        assertFalse(texBody("| a |\n|---|\n| b |").contains("\\footnotetext"))
    }

    // books

    /** A two-chapter book in the reading order the PDF compile and the EPUB
     *  already use. */
    private fun sampleBook() = BookContent(
        title = "The Book",
        articles = listOf(ArticleContent("Preface", "Opening words.")),
        chapters = listOf(
            ChapterContent(
                "One",
                listOf(
                    ArticleContent("First", "# Inner\n\nA[^a].\n\n[^a]: n1"),
                    ArticleContent("Second", "B[^b].\n\n[^b]: n2"),
                ),
            ),
            ChapterContent(
                "Two",
                listOf(ArticleContent("Third", "C[^c] C[^c].\n\n[^c]: n3")),
            ),
        ),
    )

    @Test fun bookUsesTheBookClassAndTheReadingOrder() {
        val tex = LaTeXExport.book(sampleBook())
        assertTrue(tex.startsWith("\\documentclass{book}\n"))
        assertTrue(tex.contains("\\title{The Book}"))
        assertTrue(tex.contains("\\maketitle"))
        // Root articles first, then each chapter with its articles — the
        // same order the EPUB packs and the PDF compiles.
        val order = listOf(
            "\\section{Preface}", "\\chapter{One}", "\\section{First}",
            "\\section{Second}", "\\chapter{Two}", "\\section{Third}",
        )
        var cursor = 0
        for (piece in order) {
            val found = tex.indexOf(piece, cursor)
            assertTrue("$piece is missing or out of order", found >= 0)
            cursor = found + piece.length
        }
    }

    @Test fun bookPushesArticleHeadingsBelowTheirSection() {
        // An article is already a `\section`, so its own `#` has to become a
        // `\subsection` — otherwise the author's heading is the article's
        // sibling rather than its content.
        assertTrue(LaTeXExport.book(sampleBook()).contains("\\subsection{Inner}"))
    }

    @Test fun bookRestartsFootnoteNumbersAtEachChapter() {
        // `book` resets the footnote counter at every `\chapter`, so the
        // numbers `\footnotemark` cites have to restart with it — the third
        // chapter's repeated note is number 1 again, not number 3.
        assertTrue(LaTeXExport.book(sampleBook()).contains("C\\footnote{n3} C\\footnotemark[1]."))
    }

    @Test fun bookFootnoteNumbersRunOnAcrossArticles() {
        // No iOS counterpart. A chapter is one footnote counter, however
        // many articles it holds — and an uncited definition is printed as
        // a real `\footnote`, so it takes a number too. Miss either and
        // every `\footnotemark` after the first article cites the wrong
        // note.
        val content = BookContent(
            title = "B",
            articles = emptyList(),
            chapters = listOf(
                ChapterContent(
                    "C",
                    listOf(
                        ArticleContent("Orphan", "Prose.\n\n[^x]: nobody cites this"),
                        ArticleContent("Cites", "D[^d] D[^d].\n\n[^d]: n"),
                    ),
                ),
            ),
        )
        assertTrue(LaTeXExport.book(content).contains("D\\footnote{n} D\\footnotemark[2]."))
    }

    @Test fun bookArticlesDoNotBorrowEachOthersFootnotes() {
        // No iOS counterpart. A footnote definition belongs to the article
        // it was written in — `[^a]` in the next article is a reference to
        // nothing, and stays the text the author typed. Carrying the
        // definitions forward would silently attach one article's note to
        // another article's prose.
        val content = BookContent(
            title = "B",
            articles = emptyList(),
            chapters = listOf(
                ChapterContent(
                    "C",
                    listOf(
                        ArticleContent("Defines", "P[^a].\n\n[^a]: only here"),
                        ArticleContent("Borrows", "Q[^a]."),
                    ),
                ),
            ),
        )
        val tex = LaTeXExport.book(content)
        assertTrue(tex.contains("P\\footnote{only here}."))
        assertTrue(tex.contains("Q[\\textasciicircum{}a]."))
    }

    @Test fun bookChapterForgetsTheNumbersItHandedOut() {
        // No iOS counterpart. Resetting the counter at a `\chapter` is only
        // half of it: the id → number map has to go with it. Keep it and
        // the second chapter's first reference to a same-named note cites
        // the first chapter's number — and the note's text, never emitted,
        // is gone from the document altogether.
        val content = BookContent(
            title = "B",
            articles = emptyList(),
            chapters = listOf(
                ChapterContent("One", listOf(ArticleContent("A", "X[^a] Y[^a].\n\n[^a]: first"))),
                ChapterContent("Two", listOf(ArticleContent("B", "Z[^a] W[^a].\n\n[^a]: second"))),
            ),
        )
        val tex = LaTeXExport.book(content)
        assertTrue(tex.contains("X\\footnote{first} Y\\footnotemark[1]."))
        assertTrue(tex.contains("Z\\footnote{second} W\\footnotemark[1]."))
    }

    @Test fun bookCollectsPackagesAcrossEveryArticle() {
        // The preamble belongs to the whole file: a package one article
        // needs must be loaded even if nothing else in the book uses it.
        val content = BookContent(
            title = "B",
            articles = emptyList(),
            chapters = listOf(
                ChapterContent(
                    "C",
                    listOf(
                        ArticleContent("Plain", "Nothing special."),
                        ArticleContent("Rich", "![a](x.png) ~~b~~"),
                    ),
                ),
            ),
        )
        val tex = LaTeXExport.book(content)
        assertTrue(tex.contains("\\usepackage{graphicx}"))
        assertTrue(tex.contains("\\usepackage[normalem]{ulem}"))
    }

    @Test fun bookTitlesAreEscaped() {
        // Every one of the three, and an article title as much as the rest:
        // these are file and folder names, and `&`, `_` and `%` are all
        // perfectly ordinary in one.
        val tex = LaTeXExport.book(
            BookContent(
                title = "R&D 100%",
                articles = listOf(ArticleContent("Q&A_1", "Body.")),
                chapters = listOf(ChapterContent("A_B", emptyList())),
            )
        )
        assertTrue(tex.contains("\\title{R\\&D 100\\%}"))
        assertTrue(tex.contains("\\section{Q\\&A\\_1}"))
        assertTrue(tex.contains("\\chapter{A\\_B}"))
    }

    // whole documents

    @Test fun documentIsWellFormed() {
        val tex = LaTeXExport.document("# H\n\nBody with \$x\$ and a [link](https://x.com).")
        assertTrue(tex.startsWith("\\documentclass{article}\n"))
        assertTrue(tex.endsWith("\\end{document}\n"))
        assertEquals(1, occurrences(tex, "\\begin{document}"))
        assertEquals(1, occurrences(tex, "\\end{document}"))
    }

    @Test fun emptyDocumentStillCompilesAsOne() {
        val tex = LaTeXExport.document("")
        assertTrue(tex.contains("\\begin{document}"))
        assertTrue(tex.contains("\\end{document}"))
    }

    // grapheme clusters vs UTF-16 (regression — third review)
    //
    // Kotlin walks UTF-16 units, so `contains`, `startsWith`, `split` and
    // `replace` all see an ASCII character that has a combining mark, a
    // variation selector or a ZWJ after it. Swift's match extended grapheme
    // clusters and do not: the two together are one `Character` that is not
    // equal to the plain one, so every guard in the Swift editions silently
    // stopped firing and the ports diverged on every such input. These
    // tests are the behaviour the Swift copies had to be rebuilt to match,
    // and they are here so a change on this side cannot walk away from it.

    /** Three ways to make the character in front part of a longer grapheme:
     *  a combining acute, a variation selector, a zero-width joiner. */
    private val marks = listOf("\u0301", "\uFE0F", "\u200D")

    @Test fun everyEscapeFiresWithACombiningMarkAfterIt() {
        val table = listOf(
            "#" to "\\#", "${'$'}" to "\\${'$'}", "%" to "\\%", "&" to "\\&", "_" to "\\_",
            "{" to "\\{", "}" to "\\}",
            "~" to "\\textasciitilde{}", "^" to "\\textasciicircum{}",
            "\\" to "\\textbackslash{}",
        )
        for ((special, escaped) in table) {
            for (mark in marks) {
                assertEquals(
                    "$special with a mark after it must still be escaped",
                    "a$escaped${mark}b",
                    LaTeXExport.escape("a$special${mark}b"),
                )
            }
        }
        val urls = listOf(
            "%" to "\\%", "#" to "\\#", "{" to "\\{", "}" to "\\}",
            "\\" to "\\textbackslash{}",
        )
        for ((special, escaped) in urls) {
            for (mark in marks) {
                assertEquals("a$escaped${mark}b", LaTeXExport.escapeURL("a$special${mark}b"))
            }
        }
    }

    @Test fun everyGuardFiresWithACombiningMarkAfterIt() {
        for (mark in marks) {
            // The bracket guard at all three of its callers: `\item [x]`,
            // `\\[x]` and `& [x] \\` each read the bracket as an optional
            // argument and eat the words behind it.
            assertTrue("list item guard", texBody("- [${mark}draft] item").contains("\\item {}["))
            assertTrue("soft-break guard", texBody("line\n[${mark}draft]").contains("\\\\\n{}["))
            assertTrue(
                "table row guard",
                texBody("| a |\n|---|\n| [${mark}draft] |").contains("{}["),
            )
            // The percent-decoder's own early exit: a `%` it cannot see is
            // a `%` it hands to `\includegraphics`.
            assertEquals("a%${mark}b", LaTeXExport.percentDecoded("a%${mark}b"))
            assertTrue(
                "an unreadable path must still be refused",
                texBody("![](a%${mark}b.png)").contains("% md: image skipped"),
            )
        }
    }

    @Test fun verbatimIsSplitAroundATerminatorCarryingAMark() {
        // `verbatim` scans for the *characters* `\end{verbatim}`, so a
        // terminator the split did not see closes the environment early and
        // the rest of the author's code block is executed as LaTeX.
        for (mark in marks) {
            val body = texBody("```\nbefore\n\\end{verbatim}$mark\nafter\n```")
            assertTrue(
                "the terminator must be set with \\verb",
                body.contains("\\verb|\\end{verbatim}|"),
            )
            assertTrue("nothing after it may be lost", body.contains("after"))
            assertEquals(
                "the block is split around the terminator",
                2,
                body.split("\\begin{verbatim}").size - 1,
            )
        }
    }

    @Test fun tokenRestoreSurvivesAMarkAfterTheToken() {
        // A token is U+E000, digits, U+E001, and a mark can land after the
        // last of those. A grapheme-matching restore then never finds it:
        // raw U+E000 lands in the .tex and the author's span is gone.
        for (mark in marks) {
            val body = texBody("A `code` span$mark and ${'$'}x^2${'$'}$mark after.")
            assertFalse("no token may reach the file", body.contains('\uE000'))
            assertFalse(body.contains('\uE001'))
            assertTrue(body.contains("\\texttt{code}"))
            assertTrue(body.contains("${'$'}x^2${'$'}"))
        }
    }

    @Test fun softBreaksSplitOnEveryNewlineIncludingCRLF() {
        // `\r\n` is one grapheme cluster, so Swift's
        // `components(separatedBy: "\n")` does not split there at all and a
        // document written on Windows reflows into one paragraph. This side
        // splits, and the Swift copies were rebuilt to agree.
        assertEquals(listOf("a\r", "b"), "a\r\nb".split("\n"))
        // A CRLF document is normalised by the parser before it reaches the
        // writer, so the whole-document form is the same on both ports too.
        assertEquals("a\\\\\nb", texBody("a\r\nb"))
        assertEquals("a\\\\\nb\\\\\nc", texBody("a\nb\nc"))
    }

    // tables break across pages (regression — third review)

    @Test fun tableIsALongtableAndNotAFloat() {
        // A float cannot break across a page, so a table taller than one is
        // *truncated*: exit 0, the PDF stops at the row that filled the
        // page, and the only trace is a "Float too large for page" warning
        // in a log nobody reads. Nothing here may be a float.
        val rows = (1..70).joinToString("\n") { "| r$it | c$it |" }
        val body = texBody("| A | B |\n|---|---|\n" + rows)
        assertTrue(body.startsWith("\\begin{longtable}{ll}"))
        assertTrue(body.endsWith("\\end{longtable}"))
        assertFalse(body.contains("\\begin{table}"))
        assertFalse(body.contains("\\begin{tabular}"))
        for (index in 1..70) {
            assertTrue("row $index", body.contains("r$index & c$index \\\\"))
        }
    }

    @Test fun longtableRepeatsItsHeaderOnEveryPage() {
        // A reader who turns to the second page of a table needs to know
        // what its columns are, and `\endhead` is the whole of that.
        val body = texBody("| A | B |\n|---|---|\n| a | b |")
        val head = body.indexOf("\\endhead")
        assertTrue("the header must be marked as one", head > 0)
        assertTrue(
            "the header row belongs above \\endhead",
            body.substring(0, head).contains("\\textbf{A} & \\textbf{B}"),
        )
        assertTrue(
            "the body rows below it",
            body.substring(head).contains("a & b \\\\"),
        )
    }

    // images LaTeX cannot include (regression — third review)

    @Test fun imagePathWithBracesOrABackslashIsSkipped() {
        // `escapeURL` turns each of these into a control sequence, which is
        // right for `\href` and fatal for `\includegraphics`: graphicx
        // reads its argument as a file name, so `\{` is not a character it
        // can open and the whole document fails.
        for (path in listOf("img/{a}.png", "img/a}.png", "img\\b.png")) {
            val body = texBody("Before ![alt]($path) after.")
            assertFalse(path, body.contains("\\includegraphics"))
            assertTrue(path, body.contains("% md: image skipped"))
            assertTrue("the alt text survives $path", body.contains("\\emph{alt}"))
            assertTrue("nothing after it is lost", body.contains("after."))
        }
        assertTrue(texBody("![](img/a-b_1.png)").contains("\\includegraphics"))
    }

    @Test fun remoteAndDataImagesAreSkippedAndNamed() {
        // TeX fetches nothing: `\includegraphics{https://…}` is "File not
        // found" and the document stops there. A `data:` URI is a picture
        // with no file name at all. Both are the same answer as a name
        // graphicx cannot read.
        for (path in listOf(
            "https://nettrash.me/favicon.ico",
            "http://x.com/a.png",
            "data:image/png;base64,iVBORw0KGgo=",
        )) {
            val body = texBody("![A picture]($path)")
            assertFalse(path, body.contains("\\includegraphics"))
            assertTrue(path, body.contains("% md: image skipped"))
            assertTrue("the author is told which one", body.contains(path))
            assertTrue("the alt text survives", body.contains("\\emph{A picture}"))
        }
        // A relative path with a colon in it is a file somebody can really
        // have, and is not refused for the shape of its name.
        assertTrue(texBody("![](notes:draft.png)").contains("\\includegraphics"))
    }

    @Test fun altTextSurvivesWhereThereIsNoCaption() {
        // Three places the author's own description of a picture used to be
        // dropped without a word. This project's rule is that nothing
        // written vanishes silently.
        assertTrue(
            texBody("| p |\n|---|\n| ![In a cell](a.png) |").contains("\\emph{In a cell}"),
        )
        assertTrue(
            texBody("A[^a].\n\n[^a]: ![In a note](a.png)").contains("\\emph{In a note}"),
        )
        assertTrue(
            texBody("![On a skipped one](a#b.png)").contains("\\emph{On a skipped one}"),
        )
        // It is the Markdown the author wrote, rendered — not the raw text.
        assertTrue(
            texBody("| p |\n|---|\n| ![Alt with `c`](a.png) |")
                .contains("\\emph{Alt with \\texttt{c}}"),
        )
    }

    @Test fun softBreakAfterAnImageAloneOnItsLineIsNotWritten() {
        // `\end{figure}\\` is "There's no line here to end" — a float
        // begins no line — and it stops the whole document. So is a `\\`
        // after a comment that is all the line holds.
        val figure = texBody("![A caption](a.png)\nafter")
        assertTrue(figure.contains("\\end{figure}\nafter"))
        assertFalse(figure.contains("\\end{figure}\\\\"))

        // A skipped image with alt text does set a line, so the break after
        // it is written — and it is safe, because the paragraph the `\emph`
        // opened is still open when the comment's newline ends.
        val skipped = texBody("![Alt](https://x.com/a.png)\nafter")
        assertTrue(skipped.contains("\\emph{Alt}"))
        assertTrue(skipped.contains("after"))
        assertFalse("never a blank line after a \\\\", skipped.contains("\\\\\n\n"))

        // Without alt text there is nothing but the comment, so no break is
        // written before it and none after it either.
        val bare = texBody("![](https://x.com/a.png)\nafter")
        assertTrue(bare.contains("% md: image skipped"))
        assertTrue(bare.contains("after"))
        assertFalse("a comment begins no line to end", bare.contains("\\\\"))
        assertFalse("and no paragraph break either", bare.contains("\n\n"))

        assertEquals("one\\\\\ntwo", texBody("one\ntwo"))
    }

    // mathematics that carries its own separators

    @Test fun displayMathWithItsOwnSeparatorsGetsAnAligned() {
        // `&` is an alignment tab wherever it is read, so `\[a &= b\]` is
        // "Misplaced alignment tab character" in ordinary body text; and in
        // a table cell a top-level `\\` ends the *row* from inside math
        // mode. Both stop the whole file, and both are what `aligned` is
        // for.
        assertEquals(
            "\\[\\begin{aligned}a &= b \\\\ c &= d\\end{aligned}\\]",
            texBody("${'$'}${'$'}a &= b \\\\ c &= d${'$'}${'$'}"),
        )
        assertTrue(
            texBody("| f |\n|---|\n| ${'$'}${'$'}a \\\\ b${'$'}${'$'} |")
                .contains("${'$'}\\begin{aligned}a \\\\ b\\end{aligned}${'$'} \\\\"),
        )
        // A formula that opens an environment of its own already owns its
        // separators; wrapping it would change what it aligns on.
        assertEquals(
            "\\[\\begin{aligned} p &= q \\end{aligned}\\]",
            texBody("${'$'}${'$'}\\begin{aligned} p &= q \\end{aligned}${'$'}${'$'}"),
        )
        // And a formula with neither is left exactly as written.
        assertEquals("\\[x^2\\]", texBody("${'$'}${'$'}x^2${'$'}${'$'}"))
    }

    @Test fun amsmathIsLoadedForAnyMathematics() {
        // `\begin{aligned}` in a ```math fence is "Environment aligned
        // undefined" without it, and that is one of the eight examples md
        // ships. Guessing which constructs a formula reached for is how
        // that happened; a document with any mathematics loads amsmath.
        for (source in listOf(
            "${'$'}x^2${'$'}",
            "${'$'}${'$'}x^2${'$'}${'$'}",
            "\\[x^2\\]",
            "\\(x^2\\)",
            "```math\n\\begin{aligned}a &= b\\end{aligned}\n```",
            "| f |\n|---|\n| ${'$'}x${'$'} |",
        )) {
            assertTrue(source, texPreamble(source).contains("\\usepackage{amsmath}"))
        }
        // And not for a document with none — the preamble stays short
        // enough to read at a glance.
        assertFalse(texPreamble("Just prose.").contains("amsmath"))
        assertFalse(
            "a code block quoting a formula is not one",
            texPreamble("```\n${'$'}x^2${'$'}\n```").contains("amsmath"),
        )
    }

    @Test fun bookArticlesEachNumberTheirOwnNotes() {
        // Two articles of one chapter, each numbering its own notes from
        // `[^1]` — which is the normal thing, not a clash: an id belongs to
        // the file it was written in. Carrying the numbers across made the
        // second article's `[^1]` a `\footnotemark[1]` citing the *first*
        // article's note, and the words written for the second were never
        // printed at all.
        val content = BookContent(
            title = "B",
            articles = emptyList(),
            chapters = listOf(
                ChapterContent(
                    "C",
                    listOf(
                        ArticleContent("One", "First[^1].\n\n[^1]: The first note."),
                        ArticleContent("Two", "Second[^1].\n\n[^1]: The second note."),
                    ),
                ),
            ),
        )
        val tex = LaTeXExport.book(content)
        assertTrue(tex.contains("First\\footnote{The first note.}"))
        assertTrue(
            "the second article's own note must be printed",
            tex.contains("Second\\footnote{The second note.}"),
        )
        assertFalse(tex.contains("\\footnotemark"))
    }

    @Test fun noCommandItEmitsIsEverEscaped() {
        // The ordering guarantee, stated as a property: after a document
        // full of specials and markup, no command this file produced has
        // been through the escaper (`\textbackslash{}textbf` would be the
        // symptom).
        val tex = LaTeXExport.document(
            """
            **bold** with 100% & _under_ and `a_b`, a [link](https://x.com/a_b),
            ![alt](p.png), ${'$'}x_1^2${'$'} and ~~gone~~.

            | a & b |
            |-------|
            | 50%   |
            """.trimIndent()
        )
        assertFalse(tex.contains("\\textbackslash{}text"))
        assertFalse(tex.contains("\\textbackslash{}begin"))
        assertFalse("no command's braces were escaped", tex.contains("\\{}"))
        assertTrue(tex.contains("\\textbf{bold}"))
        assertTrue(tex.contains("\$x_1^2\$"))
    }

    // the last silent losses (regression — fourth review)

    @Test fun footnoteCitedFromATableHeaderKeepsItsWords() {
        // A `longtable` typesets its header row *once*, into the box
        // `\endhead` reinserts at every page break — and LaTeX throws a
        // footnote insertion made inside a box away. A plain `\footnote` in
        // a header cell therefore compiles at exit 0 with the note's words
        // on no page at all: the same silent loss the longtable switch
        // cured for body cells, still alive in the head. So the head
        // carries the mark and the note's text is written after the table,
        // where it is read exactly once.
        val body = texBody("| h1[^1] | h2 |\n|---|---|\n| c1 | c2 |\n\n[^1]: Zarquon in the head.")
        assertTrue(body.contains("\\textbf{h1\\stepcounter{footnote}\\footnotemark[1]}"))
        assertTrue(body.contains("\\end{longtable}\n\\footnotetext[1]{Zarquon in the head.}"))
        assertFalse(
            "never an insertion inside the saved head box",
            body.contains("\\footnote{Zarquon"),
        )

        // `\footnotemark[n]` does not step LaTeX's counter, so the head
        // steps it by hand. Without that every number after it is one too
        // low: the body cell's note would print the header's number and
        // two notes would share it.
        val mixed = texBody(
            """
            | ha[^a] | hb |
            |--------|----|
            | b[^b]  | c  |

            Tail[^c].

            [^a]: note a

            [^b]: note b

            [^c]: note c
            """.trimIndent()
        )
        assertTrue(mixed.contains("\\textbf{ha\\stepcounter{footnote}\\footnotemark[1]}"))
        assertTrue(mixed.contains("b\\footnote{note b}"))
        assertTrue(mixed.contains("Tail\\footnote{note c}."))
        assertTrue(mixed.contains("\\end{longtable}\n\\footnotetext[1]{note a}"))

        // A note cited again from a body cell is the number, as ever — the
        // split is only about where the *text* goes.
        assertTrue(
            texBody("| h[^a] |\n|---|\n| A[^a] |\n\n[^a]: n")
                .contains("A\\footnotemark[1] \\\\")
        )

        // And a note first cited from a body cell keeps the plain
        // `\footnote` it always had: the head is the only exception, and a
        // table without a note in its head owes nothing after itself.
        val plain = texBody("| h |\n|---|\n| A[^a] |\n\n[^a]: n")
        assertTrue(plain.contains("A\\footnote{n} \\\\"))
        assertFalse(plain.contains("\\footnotetext"))
        assertTrue(plain.endsWith("\\end{longtable}"))
    }

    @Test fun headerCellCarryingAnAlignmentTabIsGrouped() {
        // `\textbf` is not `{\bfseries …}`: it *reads* its argument, with a
        // delimited macro that a top-level `&` ends the row out from
        // underneath — "Argument of \check@nocorr@ has an extra }" for a
        // formula, the same failure in `\href@split` for a query string,
        // and either stops the whole file. An extra group is the whole fix:
        // TeX reads `&` as an alignment tab only at the outermost brace
        // level of a cell.
        assertTrue(
            texBody("| ${'$'}a &= b${'$'} | h |\n|---|---|\n| c | d |")
                .contains("\\textbf{{\$\\begin{aligned}a &= b\\end{aligned}\$}}")
        )
        assertTrue(
            texBody("| [x](http://e.com/?a=1&b=2) | h |\n|---|---|\n| c | d |")
                .contains("\\textbf{{\\href{http://e.com/?a=1&b=2}{x}}}")
        )

        // Written only where there is a tab to guard. An ordinary header
        // cell is untouched, and so is one holding the author's own
        // ampersand — by then it is `\&`, which is a character and not a
        // tab.
        assertTrue(
            texBody("| A | B |\n|---|---|\n| a | b |").contains("\\textbf{A} & \\textbf{B}")
        )
        assertTrue(
            texBody("| a & b | B |\n|---|---|\n| c | d |")
                .contains("\\textbf{a \\& b} & \\textbf{B}")
        )

        // A body cell needs none of it: nothing reads an argument there,
        // and the `aligned` the formula was given owns its own tab.
        assertTrue(
            texBody("| h |\n|---|\n| ${'$'}a &= b${'$'} |")
                .contains("\$\\begin{aligned}a &= b\\end{aligned}\$ \\\\")
        )
    }

    @Test fun imagePathWithADoubleQuoteIsSkipped() {
        // graphicx quotes a file name that has spaces in it with a pair of
        // `"`, so one the author wrote breaks graphicx's own parser: "Use
        // of ??? doesn't match its definition", and the document stops
        // there. A sweep of all 95 printable ASCII characters through
        // `unreadableImage` found it the only one that was neither refused
        // here nor compilable, so it joins `% # { } \`.
        assertNotNull(LaTeXExport.unreadableImage("a\"b.png"))
        val body = texBody("Before ![alt](a\"b.png) after.")
        assertFalse(body.contains("\\includegraphics"))
        assertTrue(body.contains("% md: image skipped — a\"b.png"))
        assertTrue("the alt text survives", body.contains("\\emph{alt}"))
        assertTrue("and so does the rest of the sentence", body.contains("after."))

        // A `"` in a *link* is refused nothing: hyperref reads its argument
        // as a URL and opens no file.
        assertNull(LaTeXExport.unreadableImage("a-b_1.png"))
        assertTrue(texBody("[x](http://e.com/a\"b)").contains("\\href{http://e.com/a\"b}{x}"))
    }

    @Test fun authorsOwnTokenSentinelsCannotBeReadAsTokens() {
        // U+E000 and U+E001 are what an inline pass wraps a span index in.
        // A document carrying them of its own had its *own* characters read
        // back as a token index: `text **b <E000>0<E001> x** more` came out
        // as `text \textbf{b \textbf{ x} more` — the command duplicated, a
        // word dropped, the braces unbalanced, and the file refused with
        // "File ended while scanning use of \textbf". They are stripped
        // from everything that enters the writer, and nothing typesettable
        // goes with them: inputenc has no definition for either, so a
        // document that kept them would stop at "Unicode character U+E000
        // not set up for use with LaTeX" instead.
        assertEquals(
            "text \\textbf{b 0 x} more",
            texBody("text **b \uE0000\uE001 x** more"),
        )
        // Every other place the author's text is read: a formula, which is
        // the one span copied through unescaped; a code span; a table cell;
        // and the front matter, which never reaches an inline pass at all.
        assertEquals(
            "A \$x_1\$ and \\texttt{cd} here.",
            texBody("A ${'$'}x\uE000_1\uE001${'$'} and `c\uE000d` here."),
        )
        assertTrue(
            texBody("| h\uE0011 | b |\n|---|---|\n| c | d |")
                .contains("\\textbf{h1} & \\textbf{b}")
        )
        assertTrue(
            texPreamble("---\ntitle: Ti\uE000tle\n---\n\nBody.").contains("\\title{Title}")
        )

        // The strip itself, and that it is the only thing it touches.
        assertEquals("abc", LaTeXExport.withoutSentinels("a\uE000b\uE001c"))
        assertEquals("plain", LaTeXExport.withoutSentinels("plain"))
        assertEquals("\uE002\uF8FF", LaTeXExport.withoutSentinels("\uE002\uF8FF"))
    }

    @Test fun skippedImageNamesItsFileBelowTheAltText() {
        // The alt text comes *first* and the comment after it, and it has
        // to: `%` runs to the end of its physical line, so a comment in
        // front of the words would swallow the words it is there to
        // explain. (The CHANGELOG said the opposite of the code for a
        // release and a half.)
        assertTrue(
            LaTeXExport.document("![Alt words](https://x.com/a.png)").contains(
                "\\emph{Alt words}\n% md: image skipped — https://x.com/a.png " +
                    "is a URL, and LaTeX has nothing to fetch it with.\n"
            )
        )

        // And an image is a captioned float wherever it stands in body
        // text, not only alone in a paragraph. A graphic `\linewidth` wide
        // has nowhere to sit inside a sentence — set in the flow it would
        // be an overfull line on every image in the document — so LaTeX is
        // left to place it, and the words around it are untouched.
        val sentence = texBody("See ![the chart](c.png) for details.")
        assertTrue(sentence.startsWith("See \\begin{figure}[ht]"))
        assertTrue(sentence.contains("\\caption{the chart}"))
        assertTrue(sentence.endsWith("\\end{figure} for details."))
    }
}
