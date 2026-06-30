/*
 * MarkdownInline.kt
 * md (Android)
 *
 * Inline (span-level) Markdown rendering. The block parser hands us the
 * raw text of a heading / paragraph / list item / cell; this turns the
 * inline syntax inside it — **bold**, *italic*, `code`, [links](url),
 * ~~strikethrough~~ — into a Compose `AnnotatedString` that `Text`
 * renders natively.
 *
 * The iOS / macOS app leans on Foundation's `AttributedString(markdown:)`;
 * Android has no equivalent, so this is a small hand-written inline parser
 * over the same subset the app supports (the same one MarkdownHtml emits).
 * Links carry a "URL" string annotation so the UI can make them tappable.
 */

package me.nettrash.md.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

object MarkdownInline {

    /** Parse a single block's inline text into a styled `AnnotatedString`.
     *  Links are emitted as native `LinkAnnotation.Url`, so `Text` makes
     *  them tappable (opening in the browser) with no extra wiring. */
    fun annotated(text: String, accent: Color, codeBackground: Color): AnnotatedString =
        buildAnnotatedString { renderInline(text, accent, codeBackground) }

    private fun AnnotatedString.Builder.renderInline(s: String, accent: Color, codeBg: Color) {
        var i = 0
        val n = s.length
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                append(buf.toString())
                buf.setLength(0)
            }
        }

        while (i < n) {
            val c = s[i]

            // Backslash escape — emit the next char literally.
            if (c == '\\' && i + 1 < n) {
                buf.append(s[i + 1]); i += 2; continue
            }

            // Code span `…` — literal content, must not be re-interpreted.
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush()
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(s.substring(i + 1, close))
                    }
                    i = close + 1; continue
                }
                buf.append(c); i++; continue
            }

            // Link [label](url)
            if (c == '[') {
                val link = matchLink(s, i)
                if (link != null) {
                    flush()
                    withLink(
                        LinkAnnotation.Url(
                            link.url,
                            TextLinkStyles(
                                SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                            ),
                        )
                    ) {
                        renderInline(link.label, accent, codeBg)
                    }
                    i = link.end; continue
                }
            }

            // Bold **…** / __…__ (delimiter must not appear inside).
            if (c == '*' && i + 1 < n && s[i + 1] == '*') {
                val close = findDoubleClose(s, i + 2, '*')
                if (close > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        renderInline(s.substring(i + 2, close), accent, codeBg)
                    }
                    i = close + 2; continue
                }
            }
            if (c == '_' && i + 1 < n && s[i + 1] == '_') {
                val close = findDoubleClose(s, i + 2, '_')
                if (close > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        renderInline(s.substring(i + 2, close), accent, codeBg)
                    }
                    i = close + 2; continue
                }
            }

            // Strikethrough ~~…~~
            if (c == '~' && i + 1 < n && s[i + 1] == '~') {
                val close = findDoubleClose(s, i + 2, '~')
                if (close > i + 2) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        renderInline(s.substring(i + 2, close), accent, codeBg)
                    }
                    i = close + 2; continue
                }
            }

            // Italic *…*
            if (c == '*') {
                val close = s.indexOf('*', i + 1)
                if (close > i + 1) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        renderInline(s.substring(i + 1, close), accent, codeBg)
                    }
                    i = close + 1; continue
                }
            }

            // Italic _…_ — word-boundary only, so snake_case survives.
            if (c == '_') {
                val before = if (i > 0) s[i - 1] else ' '
                if (!before.isLetterOrDigit() && before != '_') {
                    val close = s.indexOf('_', i + 1)
                    if (close > i + 1) {
                        val after = if (close + 1 < n) s[close + 1] else ' '
                        if (!after.isLetterOrDigit() && after != '_') {
                            flush()
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                renderInline(s.substring(i + 1, close), accent, codeBg)
                            }
                            i = close + 1; continue
                        }
                    }
                }
            }

            buf.append(c); i++
        }
        flush()
    }

    private data class Link(val label: String, val url: String, val end: Int)

    /** Match `[label](url)` at `i` (where `s[i] == '['`). */
    private fun matchLink(s: String, i: Int): Link? {
        val closeBracket = s.indexOf(']', i + 1)
        if (closeBracket <= i + 1) return null                 // need ≥1 label char
        if (closeBracket + 1 >= s.length || s[closeBracket + 1] != '(') return null
        val urlStart = closeBracket + 2
        var k = urlStart
        while (k < s.length && s[k] != ')' && !s[k].isWhitespace()) k++
        if (k >= s.length || s[k] != ')' || k == urlStart) return null  // need ≥1 url char + ')'
        return Link(
            label = s.substring(i + 1, closeBracket),
            url = s.substring(urlStart, k),
            end = k + 1,
        )
    }

    /** First index k ≥ start where `s[k]` and `s[k+1]` both equal `delim`. */
    private fun findDoubleClose(s: String, start: Int, delim: Char): Int {
        var k = start
        while (k + 1 < s.length) {
            if (s[k] == delim && s[k + 1] == delim) return k
            k++
        }
        return -1
    }
}
