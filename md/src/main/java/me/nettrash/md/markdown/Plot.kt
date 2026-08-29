/*
 * Plot.kt
 * md (Android)
 *
 * The ```plot fence: source text in, an SVG string out.
 *
 * THE ONE PROPERTY WORTH PROTECTING
 * ---------------------------------
 * This file is **pure and synchronous**. No engine, no bundled asset, no
 * `<script>`, no network, no platform API — a string goes in and a string comes
 * out. That is what makes every surface work for free: the live preview, the
 * self-contained HTML export, print, PDF, EPUB, LaTeX and the "export this
 * diagram as SVG" command all receive a finished `<svg>` in the bytes
 * `MarkdownHtml` already returns. A plot-only document never loads an engine
 * and never touches the WebView.
 *
 * `renderPlot` memoises itself on the fence text (a 32-entry LRU, guarded by a
 * lock), which is the one piece of mutable state in the file and does not spend
 * the property above: the memo is on a pure function of its input, so it can
 * only ever return the bytes a fresh render would have. It is here because this
 * is the *only* rich block drawn synchronously in the renderer — every other one
 * is escaped text with an engine working on it later in the page — so without a
 * memo the live preview redraws every figure in the document on every keystroke.
 *
 * That purity is also why nothing here may reach for `android.*` or for
 * Compose: the unit-test classpath carries JUnit and nothing else, so a single
 * Android import would put the whole renderer beyond the reach of a test — the
 * same rule `MarkdownHtml`, `HtmlExport`, `DiagramSvg`, `Epub` and
 * `LaTeXExport` already keep.
 *
 * A literal port of `md.vscode/src/render/plot.ts` — same function names, same
 * order, same comments — which is itself written in the subset of TypeScript
 * that translates without reinterpretation: hand-written scanning instead of
 * regular expressions, flat records instead of generics, explicit loops instead
 * of clever reductions. The four ports are checked against one another with the
 * same oracle (`plot-vectors.json`) and the same golden figure.
 *
 * WHAT IT IS A PORT OF, AND WHERE IT DELIBERATELY DIVERGES
 * -------------------------------------------------------
 * The geometry comes from nettrash.me's own plotter
 * (`frontend/src/components/math.rs`: `render_plot_svg`, `nice_step`,
 * `format_label`), so a figure drawn here lands where the site draws it. Four
 * behaviours of that plotter are **bugs**, and they are fixed here rather than
 * inherited — each one is called out at the code that fixes it:
 *
 *   1. `floor`, `ceil` and `round` draw nothing on the site (its preprocessor
 *      rewrites every name to `math::…` while evalexpr binds those three bare),
 *      so all 1001 samples fail. They work here.
 *   2. A comparison yields a Boolean the site's eval closure throws away, so
 *      `x > 0` is a blank chart. Here comparisons yield 1.0 / 0.0, which makes
 *      `(x > 0) * sqrt(x)` the half-domain idiom it should be.
 *   3. `^` is right-associative here — `2^3^2` is 512, not evalexpr's 64.
 *   4. Everything is a double: `5/2` is 2.5, not evalexpr's integer 2.
 *
 * and two more in the axis code, where the site's own output is visibly wrong:
 * tick labels are rounded rather than truncated (a tick at −4 printed "-3"),
 * and tick positions are computed by index rather than accumulated (a tick at 0
 * printed "-5.6e-17").
 *
 * NUMBER FORMATTING IS THE CROSS-PLATFORM HAZARD
 * ----------------------------------------------
 * Rust's `{:.1e}` writes `1.0e3`; C's `%.1e` writes `1.0e+03`; Java's
 * `String.format` the same; JavaScript's `toExponential(1)` writes `1.0e+3`.
 * Worse, the *rounding* differs: 1250 is `1.2e3` in Rust (ties to even) and
 * `1.3e+3` in Java and JavaScript (ties away from zero). So the formatters at
 * the foot of this file are written by hand, round ties to even on the exact
 * binary value, and emit the Rust spelling. **Do not replace them with
 * `String.format`.**
 *
 * `Math.pow` IS THE OTHER ONE, TWICE
 * -----------------------------------
 * The platform's power function is wrong for this renderer in two unrelated
 * ways, and both are handled here rather than inherited:
 *
 *   * **Accuracy, in the geometry.** OpenJDK's `Math.pow(10.0, -5.0)` lands one
 *     ULP below the true 1e-5, where Rust's and V8's are exact, so `niceStep`
 *     built three of the eighty-one recorded tick spacings one ULP light — and a
 *     step is what every gridline, tick and label of an axis is laid out from.
 *     `powerOfTen` builds the power of ten exactly instead; see the note there,
 *     and note that `StrictMath.pow` is no better.
 *   * **Semantics, in the expression language.** `Math.pow` answers NaN for
 *     `pow(1, NaN)`, `pow(1, ±∞)` and `pow(-1, ±∞)`, where C99 and IEEE 754 —
 *     and therefore Swift and Rust — all answer 1.0. `powIEEE` restores those
 *     five, so `^` and `pow(a,b)` mean the same thing in all four ports.
 *
 * Those are the only lines of this file that are not a word-for-word
 * translation of the reference.
 */

package me.nettrash.md.markdown

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.math.truncate

object Plot {

    // MARK: - The entry point

    /**
     * One ```plot fence, as the block of markup the renderer embeds.
     *
     * The container is emitted **unconditionally** — for a good plot, an empty
     * block and a broken one alike. Every export path counts `div.plot`
     * containers to pair a rendered figure with its source block, so a fence
     * that emitted nothing would shift every later diagram onto the wrong
     * figure.
     *
     * A block that cannot be parsed keeps its source text visible under one
     * `plot: …` line, which is the family's rule for every rich block: never a
     * hole, never an error box.
     *
     * **Memoised on [source]** — see [RENDER_CACHE_CAPACITY]. Drawing is the one
     * rich block that happens synchronously, in the renderer, on every keystroke;
     * the cache is what keeps a document's other figures from being redrawn while
     * the author types a sentence somewhere else.
     */
    fun renderPlot(source: String): String {
        synchronized(renderCacheLock) { renderCache[source] }?.let { return it }
        // Rendering runs OUTSIDE the lock: a 5000-sample figure is tens of
        // milliseconds, and no export path should ever queue behind another's
        // drawing. Two threads racing the same fence both draw it and both
        // store the same bytes, which costs one wasted render and nothing else —
        // this is a pure function of `source`.
        val rendered = renderPlotUncached(source)
        // An oversized figure is not memoised. The capacity bounds the *count*,
        // not the bytes, and one fence may legally reach 1.8 MB (24 series x
        // `samples: 5000` at 2000x2000), so thirty-two of those would retain
        // ~57 MB for the life of the process — on a phone, a plausible way to be
        // killed for memory. It is also the figure a memo helps least: rare, and
        // hundreds of milliseconds to draw either way. Everything ordinary sits
        // far below the line (the default figure is 16,888 bytes), so this bounds
        // the memo at roughly 4 MB and costs the common case nothing.
        if (rendered.length <= LARGEST_MEMOISED_VALUE) {
            synchronized(renderCacheLock) { renderCache[source] = rendered }
        }
        return rendered
    }

    /**
     * How many rendered fences to keep. 32 is what md.vscode's own preview
     * client already uses for its diagram cache, so the four ports bound the
     * same thing the same way.
     *
     * Bounded rather than open: an editing session types thousands of distinct
     * intermediate fences, and every one of them is a key nothing will ask for
     * again. Thirty-two is comfortably more than any document shows at once.
     *
     * The bound is on entries, not bytes, which is the honest trade: a typical
     * figure is ~17 KB and thirty-two of them are half a megabyte, while a
     * document of thirty-two *maximal* fences (24 series × 5000 samples) would
     * hold megabytes here. That document already built every one of those
     * strings into a single HTML document to be rendered at all, so this retains
     * a second copy of something the renderer had to materialise anyway — it is
     * not a new class of cost, and the LRU releases it as soon as the author
     * edits elsewhere.
     */
    private const val RENDER_CACHE_CAPACITY = 32

    /**
     * The largest container worth remembering, in UTF-16 code units — eight
     * times the default figure. See [renderPlot] for why there is a byte bound
     * as well as an entry bound.
     */
    private const val LARGEST_MEMOISED_VALUE = 128 * 1024

    /**
     * The cache's monitor.
     *
     * **This has to be thread-safe**, and not merely as a precaution: the live
     * preview renders on the main thread (`RichWebView`, straight out of the
     * composition), while `EpubExporter` builds every article's markup on
     * `Dispatchers.Default` — an export the author starts and then keeps typing
     * through. Those two really do call this at the same time.
     *
     * `LinkedHashMap` in access order *mutates on read* — a get reorders the
     * list — so two concurrent *lookups* are enough to corrupt it; a
     * `ConcurrentHashMap` would not have given LRU anyway. A plain
     * `synchronized` block is the right primitive: the critical sections are one
     * map operation long, and the drawing itself happens outside them.
     */
    private val renderCacheLock = Any()

    /**
     * source → the finished container string, least-recently-used first out.
     *
     * **Failures are cached too**, which is why the value is whatever
     * [renderPlotUncached] returned rather than a nullable success:
     * a half-typed fence is a parse failure, that is the state a fence spends
     * most of its life in, and re-running the parser on every keystroke to
     * re-learn "expected ')'" is exactly the cost this is here to remove.
     *
     * `accessOrder = true` makes a *read* count as a use, so the figures a
     * document is currently showing stay in while the intermediate junk ages out.
     */
    private val renderCache = object : LinkedHashMap<String, String>(
        RENDER_CACHE_CAPACITY * 2,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > RENDER_CACHE_CAPACITY
    }

    /** Number of fences actually drawn — every cache miss, none of the hits.
     *  Written here, read only by the tests: a cache is invisible from its
     *  output by construction, so this is the only way to assert that a repeat
     *  render did not redraw. */
    private var renderCount = 0L

    /** Test-only view of the cache: entries, drawings, keys. `internal`, so
     *  nothing outside the module — and nothing on a rendering path — reads it. */
    internal fun renderCacheStateForTest(): Triple<Int, Long, Set<String>> =
        synchronized(renderCacheLock) { Triple(renderCache.size, renderCount, renderCache.keys.toSet()) }

    /** Test-only: empty the cache and zero the draw count, so one test's fences
     *  cannot make another's look like hits. */
    internal fun resetRenderCacheForTest() {
        synchronized(renderCacheLock) {
            renderCache.clear()
            renderCount = 0L
        }
    }

    /** [renderPlot] without the memo: the actual drawing. */
    private fun renderPlotUncached(source: String): String {
        synchronized(renderCacheLock) { renderCount++ }
        return try {
            "<div class=\"plot\">${plotSVG(source)}</div>"
        } catch (error: Throwable) {
            // `Throwable` and not `PlotError`, exactly as the TypeScript catches
            // everything rather than its own error type: whatever a fence holds,
            // the reader gets a container with their own text in it. The depth
            // and node budgets below are what keep this from ever having to
            // catch a StackOverflowError, which on this platform is a crash the
            // JVM only sometimes lets a handler survive.
            val message = if (error is PlotError) error.message.orEmpty() else error.toString()
            "<div class=\"plot\"><pre>${escapeHTML("plot: $message\n$source")}</pre></div>"
        }
    }

    /**
     * The `<svg>` element for a fence, or a [PlotError] describing why not.
     *
     * An empty block — no series at all — is not an error and draws nothing,
     * the same way an empty Mermaid block does.
     */
    fun plotSVG(source: String): String {
        val spec = parsePlot(source)
        // A block with nothing in it draws nothing, the way an empty Mermaid
        // block does. A block the author *did* write in — directives but no
        // series — is not empty, and returning "" there would swallow what they
        // typed into a container with no figure and no explanation. Draw the
        // empty axes those directives describe: it shows the range and title
        // took effect, and the missing curve is then obviously the missing curve.
        if (spec.series.isEmpty() && !spec.hasDirectives) return ""
        return draw(spec)
    }

    /** Everything that makes a block unrenderable, with the message the reader sees. */
    class PlotError(message: String) : Exception(message)

    // MARK: - The fence

    /** `legend: on | off | auto`. */
    enum class Legend { ON, OFF, AUTO }

    /** A parsed ```plot block: the directives, resolved, and the series in order. */
    class PlotSpec {
        var xMin: Double = -10.0
        var xMax: Double = 10.0

        /** null when `y: auto` (the default) — the range is fitted to the samples. */
        var yMin: Double? = null
        var yMax: Double? = null
        var title: String = ""
        var xLabel: String = ""
        var yLabel: String = ""
        var legend: Legend = Legend.AUTO
        var grid: Boolean = true
        var axes: Boolean = true
        var width: Int = DEFAULT_WIDTH
        var height: Int = DEFAULT_HEIGHT
        var samples: Int = DEFAULT_SAMPLES
        val series: MutableList<Series> = ArrayList()

        /**
         * Whether the fence carried at least one directive.
         *
         * Distinguishes a genuinely empty block (draws nothing, like an empty
         * Mermaid block) from one the author wrote directives into but no series
         * — which must still draw, or their text vanishes into a container with
         * nothing in it.
         */
        var hasDirectives: Boolean = false
    }

    enum class SeriesKind { FUNCTION, PARAMETRIC, POINTS }

    /**
     * One curve.
     *
     * Three shapes, one flat record rather than three classes: a [kind] tag and
     * the fields each kind uses, which is the shape that ports to a Swift
     * `enum` with associated values and to Kotlin without either language
     * needing a downcast in the drawing code.
     */
    class Series(
        val kind: SeriesKind,
        /** The author's own label, or null — in which case the legend shows [source]. */
        val label: String?,
        /** The source text of the series, verbatim, for the legend and for messages. */
        val source: String,
        /** `FUNCTION`: y = f(x). `PARAMETRIC`: the x half. Unused by `POINTS`. */
        val expression: Node?,
        /** `PARAMETRIC` only: the y half. */
        val yExpression: Node?,
        /** `PARAMETRIC` only: the parameter's name and range. */
        val parameter: String,
        val tMin: Double,
        val tMax: Double,
        /** `POINTS` only. */
        val points: List<Point>,
    )

    data class Point(val x: Double, val y: Double)

    /** The directive keys. Anything else before a colon is a series, not an error. */
    private val DIRECTIVES = listOf(
        "x", "y", "title", "xlabel", "ylabel", "legend", "grid", "axes", "width", "height", "samples",
    )

    private const val DEFAULT_WIDTH = 600
    private const val DEFAULT_HEIGHT = 400
    private const val DEFAULT_SAMPLES = 1000
    private const val MIN_WIDTH = 160
    private const val MAX_WIDTH = 2000
    private const val MIN_HEIGHT = 120
    private const val MAX_HEIGHT = 2000
    private const val MIN_SAMPLES = 50
    private const val MAX_SAMPLES = 5000

    /**
     * The most series one fence may draw.
     *
     * `samples`, `width` and `height` are all clamped, but the series count is
     * the one input an author sets just by adding lines — and the preview
     * re-renders the whole document on every keystroke. A thousand-series fence
     * takes seconds and builds a multi-megabyte string, which is a frozen editor
     * rather than a slow one. Twenty-four is well past any legible figure (the
     * palette holds eight) and cheap at the sampling limit.
     */
    private const val MAX_SERIES = 24

    /**
     * The deepest an expression may nest.
     *
     * The parser and evaluator both recurse, so `((((…x…))))` or `x+x+x+…` can
     * exhaust the stack. On this platform that is a `StackOverflowError` — a
     * crash, not an exception — where §1.7 promises `plot: <what>`, so the guard
     * is load-bearing here in a way it is not in TypeScript.
     */
    private const val MAX_DEPTH = 128

    /**
     * The most nodes one expression may hold.
     *
     * The depth guard cannot see a long *flat* chain: `x+x+x+…` parses through
     * the left-associative loop at constant depth, then blows the stack in the
     * evaluator, which walks the resulting left-deep tree recursively. Budgeting
     * nodes at parse time catches both shapes in one place, before anything is
     * evaluated 1000 times over.
     */
    private const val MAX_NODES = 4096

    /**
     * Read a fence into a [PlotSpec].
     *
     * Blank lines are ignored, a line whose first non-space character is `#` is
     * a comment, and order is free — directives may follow series. A line is a
     * directive when it reads `key: value` **and the key is known**, so `f: x`
     * still plots rather than failing on an unknown directive.
     */
    fun parsePlot(source: String): PlotSpec {
        val spec = PlotSpec()

        for (raw in splitLines(source)) {
            val line = trim(raw)
            if (line.isEmpty()) continue
            if (line[0] == '#') continue
            val directive = matchDirective(line)
            if (directive != null) {
                applyDirective(spec, directive.key, directive.value)
                spec.hasDirectives = true
                continue
            }
            if (spec.series.size == MAX_SERIES) {
                throw PlotError("too many series (limit $MAX_SERIES)")
            }
            spec.series.add(parseSeries(line))
        }

        if (!(spec.xMax > spec.xMin)) throw PlotError("x range must be increasing")
        val yMin = spec.yMin
        val yMax = spec.yMax
        if (yMin != null && yMax != null && !(yMax > yMin)) {
            throw PlotError("y range must be increasing")
        }
        return spec
    }

    private data class Keyed(val key: String, val value: String)

    /**
     * `key: value`, whatever the key.
     *
     * Hand-scanned rather than matched with `^\s*([a-z][a-z-]*)\s*:\s*(.*)$`,
     * because the same scan has to exist in Swift and TypeScript.
     */
    private fun matchKeyed(line: String): Keyed? {
        var index = 0
        while (index < line.length && isSpace(line[index])) index++
        val start = index
        if (index >= line.length || !isLowerLetter(line[index])) return null
        while (index < line.length && (isLowerLetter(line[index]) || line[index] == '-')) {
            index++
        }
        val key = line.substring(start, index)
        while (index < line.length && isSpace(line[index])) index++
        if (index >= line.length || line[index] != ':') return null
        return Keyed(key, trim(line.substring(index + 1)))
    }

    /**
     * The same line, when the key is one this renderer knows.
     *
     * The known-key test is what keeps an unknown `key:` line from failing the
     * block: `f: x` is a series labelled `f`, not a complaint about a directive
     * nobody meant to write.
     */
    private fun matchDirective(line: String): Keyed? {
        val keyed = matchKeyed(line)
        if (keyed == null || !contains(DIRECTIVES, keyed.key)) return null
        return keyed
    }

    private fun applyDirective(spec: PlotSpec, key: String, value: String) {
        if (key == "x") {
            val range = parseRange(value, "x")
            spec.xMin = range.min
            spec.xMax = range.max
            return
        }
        if (key == "y") {
            if (lowercased(value) == "auto") {
                spec.yMin = null
                spec.yMax = null
                return
            }
            val range = parseRange(value, "y")
            spec.yMin = range.min
            spec.yMax = range.max
            return
        }
        if (key == "title") {
            spec.title = value
            return
        }
        if (key == "xlabel") {
            spec.xLabel = value
            return
        }
        if (key == "ylabel") {
            spec.yLabel = value
            return
        }
        if (key == "legend") {
            when (lowercased(value)) {
                "on" -> { spec.legend = Legend.ON; return }
                "off" -> { spec.legend = Legend.OFF; return }
                "auto" -> { spec.legend = Legend.AUTO; return }
            }
            throw PlotError("legend must be 'on', 'off' or 'auto'")
        }
        if (key == "grid" || key == "axes") {
            val word = lowercased(value)
            if (word != "on" && word != "off") throw PlotError("$key must be 'on' or 'off'")
            if (key == "grid") spec.grid = word == "on"
            else spec.axes = word == "on"
            return
        }
        if (key == "width") {
            spec.width = clampInteger(number(value, "width"), MIN_WIDTH, MAX_WIDTH)
            return
        }
        if (key == "height") {
            spec.height = clampInteger(number(value, "height"), MIN_HEIGHT, MAX_HEIGHT)
            return
        }
        // `samples`, the only key left.
        spec.samples = clampInteger(number(value, "samples"), MIN_SAMPLES, MAX_SAMPLES)
    }

    private data class Range(val min: Double, val max: Double)

    /**
     * `A..B`.
     *
     * Both ends are parsed as constant expressions rather than bare number
     * literals, so `x: -pi..pi` and `x: 0..2*pi` work. A number literal is the
     * simplest such expression, so nothing that the stricter reading accepts is
     * lost, and an end that mentions a variable is an error rather than a silent
     * zero.
     */
    private fun parseRange(value: String, key: String): Range {
        val at = indexOfPair(value, '.', '.')
        if (at < 0) throw PlotError("$key range must be written min..max")
        val min = constant(value.substring(0, at), key)
        val max = constant(value.substring(at + 2), key)
        if (!(max > min)) throw PlotError("$key range must be increasing")
        return Range(min, max)
    }

    /** A constant expression: no variable, finite. */
    private fun constant(text: String, key: String): Double {
        val trimmed = trim(text)
        if (trimmed.isEmpty()) throw PlotError("$key range must be written min..max")
        val value = evaluate(parseExpression(trimmed, ""), "", 0.0)
        if (!isFiniteNumber(value)) throw PlotError("$key range must be finite")
        return value
    }

    private fun number(value: String, key: String): Double {
        val parsed = evaluate(parseExpression(trim(value), ""), "", 0.0)
        if (!isFiniteNumber(parsed)) throw PlotError("$key must be a number")
        return parsed
    }

    /** A pixel count or a sample count: rounded to a whole number, then clamped. */
    private fun clampInteger(value: Double, low: Int, high: Int): Int {
        val whole = roundTiesAway(value)
        if (whole < low) return low
        if (whole > high) return high
        return whole.toInt()
    }

    /**
     * One series line.
     *
     * The label is everything left of the first top-level `=` that is not part
     * of `==`, `<=`, `>=` or `!=`. There is no ambiguity to resolve: the
     * expression language has no assignment, so a bare `=` is always a label
     * separator.
     */
    private fun parseSeries(line: String): Series {
        var label: String? = null
        var body = line
        val at = labelSeparator(line)
        if (at >= 0) {
            label = trim(line.substring(0, at))
            body = trim(line.substring(at + 1))
            if (label.isEmpty()) label = null
        } else if (!isPointsLine(line)) {
            // `f: x` — a `key:` line whose key is no directive of ours. The key
            // is the label and the rest is the series, which is what lets an
            // unknown directive plot instead of failing the block. `points:` is
            // the one colon that means something else, and it is claimed above.
            val keyed = matchKeyed(line)
            if (keyed != null && keyed.value.isNotEmpty()) {
                label = keyed.key
                body = keyed.value
            }
        }
        if (body.isEmpty()) throw PlotError("a series needs an expression")

        val points = parsePointsSeries(label, body)
        if (points != null) return points
        val parametric = parseParametricSeries(label, body)
        if (parametric != null) return parametric

        return Series(
            kind = SeriesKind.FUNCTION,
            label = label,
            source = body,
            expression = parseExpression(body, "x"),
            yExpression = null,
            parameter = "x",
            tMin = 0.0,
            tMax = 0.0,
            points = emptyList(),
        )
    }

    /** The index of the label `=`, or −1. */
    private fun labelSeparator(line: String): Int {
        var depth = 0
        for (index in line.indices) {
            val c = line[index]
            if (c == '(') depth++
            else if (c == ')') depth--
            else if (c == '=' && depth == 0) {
                // `==` is a comparison, and `<=`, `>=`, `!=` end with one.
                if (index + 1 < line.length && line[index + 1] == '=') return -1
                if (index > 0) {
                    val before = line[index - 1]
                    if (before == '=' || before == '<' || before == '>' || before == '!') return -1
                }
                return index
            }
        }
        return -1
    }

    private const val POINTS_PREFIX = "points:"

    /** Does this line open a points series? */
    private fun isPointsLine(line: String): Boolean {
        if (line.length < POINTS_PREFIX.length) return false
        return lowercased(line.substring(0, POINTS_PREFIX.length)) == POINTS_PREFIX
    }

    /** `points: x,y x,y …`, or null when this is not a points series. */
    private fun parsePointsSeries(label: String?, body: String): Series? {
        if (!isPointsLine(body)) return null
        val prefix = POINTS_PREFIX

        val points = ArrayList<Point>()
        for (token in splitWhitespace(body.substring(prefix.length))) {
            val comma = token.indexOf(',')
            if (comma < 0) throw PlotError("points must be x,y pairs — '$token' is not one")
            points.add(
                Point(
                    x = constant(token.substring(0, comma), "point"),
                    y = constant(token.substring(comma + 1), "point"),
                ),
            )
        }
        if (points.isEmpty()) throw PlotError("points needs at least one x,y pair")
        return Series(
            kind = SeriesKind.POINTS,
            label = label,
            source = body,
            expression = null,
            yExpression = null,
            parameter = "",
            tMin = 0.0,
            tMax = 0.0,
            points = points,
        )
    }

    /**
     * `(fx(t), fy(t)) for t in A..B`, or null when this is not a parametric
     * series.
     *
     * The test is deliberately narrow — an opening parenthesis whose match is
     * followed by the word `for`, with exactly one top-level comma inside — so
     * that `(x+1)*2` stays an ordinary function of x.
     */
    private fun parseParametricSeries(label: String?, body: String): Series? {
        if (body[0] != '(') return null
        val close = matchingParenthesis(body, 0)
        if (close < 0) return null
        val tail = trim(body.substring(close + 1))
        if (!startsWithWord(tail, "for")) return null

        val inside = body.substring(1, close)
        val comma = topLevelComma(inside)
        if (comma < 0) throw PlotError("a parametric series needs (x(t), y(t))")

        // `for t in A..B`
        val rest = trim(tail.substring(3))
        var index = 0
        while (index < rest.length && isIdentifierPart(rest[index])) index++
        val parameter = rest.substring(0, index)
        if (parameter.isEmpty() || !isIdentifierStart(parameter[0])) {
            throw PlotError("expected a parameter name after 'for'")
        }
        val afterName = trim(rest.substring(index))
        if (!startsWithWord(afterName, "in")) throw PlotError("expected 'in' after '$parameter'")
        val range = parseRange(trim(afterName.substring(2)), parameter)

        return Series(
            kind = SeriesKind.PARAMETRIC,
            label = label,
            source = body,
            expression = parseExpression(trim(inside.substring(0, comma)), parameter),
            yExpression = parseExpression(trim(inside.substring(comma + 1)), parameter),
            parameter = parameter,
            tMin = range.min,
            tMax = range.max,
            points = emptyList(),
        )
    }

    // MARK: - The expression language: tokens

    private enum class TokenKind { NUMBER, NAME, OPERATOR, OPEN, CLOSE, COMMA, END }

    /**
     * A token.
     *
     * [kind] is one of number, name, operator, `(`, `)`, `,`, end; [text]
     * carries the spelling of a name or an operator and [value] the value of a
     * number. One flat record rather than a union, because the parser only ever
     * asks two questions of a token and a union would cost every port a
     * downcast.
     */
    private class Token(val kind: TokenKind, val text: String, val value: Double)

    /** The two-character operators, longest match first — `<=` before `<`. */
    private val LONG_OPERATORS = listOf("||", "&&", "==", "!=", "<=", ">=")
    private val SHORT_OPERATORS = listOf("+", "-", "*", "/", "%", "^", "<", ">", "!")

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (isSpace(c)) {
                index++
                continue
            }
            if (isDigit(c) || (c == '.' && index + 1 < text.length && isDigit(text[index + 1]))) {
                val scanned = scanNumber(text, index)
                tokens.add(Token(TokenKind.NUMBER, text.substring(index, scanned.end), scanned.value))
                index = scanned.end
                continue
            }
            if (isIdentifierStart(c)) {
                val start = index
                while (index < text.length && isIdentifierPart(text[index])) index++
                tokens.add(Token(TokenKind.NAME, text.substring(start, index), 0.0))
                continue
            }
            if (c == '(') {
                tokens.add(Token(TokenKind.OPEN, "(", 0.0))
                index++
                continue
            }
            if (c == ')') {
                tokens.add(Token(TokenKind.CLOSE, ")", 0.0))
                index++
                continue
            }
            if (c == ',') {
                tokens.add(Token(TokenKind.COMMA, ",", 0.0))
                index++
                continue
            }
            val two = if (index + 1 < text.length) text.substring(index, index + 2) else ""
            if (two.length == 2 && contains(LONG_OPERATORS, two)) {
                tokens.add(Token(TokenKind.OPERATOR, two, 0.0))
                index += 2
                continue
            }
            if (contains(SHORT_OPERATORS, c.toString())) {
                tokens.add(Token(TokenKind.OPERATOR, c.toString(), 0.0))
                index++
                continue
            }
            throw PlotError("unexpected character '$c'")
        }
        tokens.add(Token(TokenKind.END, "", 0.0))
        return tokens
    }

    private data class Scanned(val end: Int, val value: Double)

    /**
     * A number literal: `123`, `1.5`, `.5`, `5.`, `1e-3`, `1.2E+4`. No hex, no
     * digit separators.
     *
     * The digits are handed to the platform's own decimal→binary conversion,
     * which is correctly rounded everywhere this ships (it is the one place
     * where the platform is more trustworthy than anything written by hand).
     * The scanner's grammar is narrower than `Double.parseDouble`'s — no `0x`,
     * no `Infinity`, no trailing `f`/`d` type suffix — so the two platforms'
     * looser parsers never get to disagree.
     */
    private fun scanNumber(text: String, start: Int): Scanned {
        var index = start
        while (index < text.length && isDigit(text[index])) index++
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && isDigit(text[index])) index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            var lookahead = index + 1
            if (lookahead < text.length && (text[lookahead] == '+' || text[lookahead] == '-')) {
                lookahead++
            }
            if (lookahead < text.length && isDigit(text[lookahead])) {
                index = lookahead
                while (index < text.length && isDigit(text[index])) index++
            }
        }
        val literal = text.substring(start, index)
        val value = literal.toDoubleOrNull() ?: throw PlotError("'$literal' is not a number")
        if (value.isNaN()) throw PlotError("'$literal' is not a number")
        return Scanned(index, value)
    }

    // MARK: - The expression language: the tree

    enum class NodeKind { NUMBER, VARIABLE, UNARY, BINARY, CALL }

    /**
     * An expression node.
     *
     * As with [Token] this is one record with the fields each kind uses, so the
     * tree ports to Swift and TypeScript without generics.
     */
    class Node(
        val kind: NodeKind,
        /** `NUMBER`. */
        val value: Double,
        /** `VARIABLE` (its name), `UNARY` / `BINARY` (the operator), `CALL` (the function). */
        val text: String,
        /** `UNARY` (the operand), `BINARY` (the left side). */
        val left: Node?,
        /** `BINARY`. */
        val right: Node?,
        /** `CALL`. */
        val arguments: List<Node>,
    )

    private fun numberNode(value: Double): Node =
        Node(NodeKind.NUMBER, value, "", null, null, emptyList())

    private fun variableNode(name: String): Node =
        Node(NodeKind.VARIABLE, 0.0, name, null, null, emptyList())

    private fun unaryNode(operator: String, operand: Node): Node =
        Node(NodeKind.UNARY, 0.0, operator, operand, null, emptyList())

    private fun binaryNode(operator: String, left: Node, right: Node): Node =
        Node(NodeKind.BINARY, 0.0, operator, left, right, emptyList())

    private fun callNode(name: String, args: List<Node>): Node =
        Node(NodeKind.CALL, 0.0, name, null, null, args)

    /**
     * The function roster — exactly the site's names and arity, and nothing
     * else.
     *
     * `min`, `max`, `if`, `log` and evalexpr's other builtins are deliberately
     * absent: the roster is the contract four implementations share, and a name
     * that works in one of them and not the others is worse than a name that
     * works in none.
     */
    private val FUNCTIONS = listOf(
        "sin", "cos", "tan", "asin", "acos", "atan",
        "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
        "sqrt", "cbrt", "abs", "exp", "exp2", "ln", "log2", "log10",
        "floor", "ceil", "round",
        "atan2", "pow", "hypot",
    )

    /** The three two-argument functions; everything else in [FUNCTIONS] takes one. */
    private val BINARY_FUNCTIONS = listOf("atan2", "pow", "hypot")

    private fun arity(name: String): Int = if (contains(BINARY_FUNCTIONS, name)) 2 else 1

    /** Precedence, loosest to tightest. `^` is the only right-associative level. */
    private fun precedence(operator: String): Int {
        if (operator == "||") return 1
        if (operator == "&&") return 2
        if (operator == "==" || operator == "!=") return 3
        if (operator == "<" || operator == "<=" || operator == ">" || operator == ">=") return 3
        if (operator == "+" || operator == "-") return 4
        if (operator == "*" || operator == "/" || operator == "%") return 5
        if (operator == "^") return 6
        return 0
    }

    private const val POWER_PRECEDENCE = 6

    /**
     * Parse [text] as an expression in which [variable] is the only free name
     * (besides the constants `pi` and `e`). Pass `""` for a constant expression.
     *
     * Precedence climbing, hand-written — no dependency, and the same twenty
     * lines in every port.
     */
    fun parseExpression(text: String, variable: String): Node {
        val parser = Parser(tokenize(text), variable)
        val node = parser.expression(1)
        parser.expectEnd()
        return node
    }

    private class Parser(private val tokens: List<Token>, private val variable: String) {
        private var index = 0
        private var depth = 0
        private var nodes = 0

        /** Charge one node against the budget, so a flat chain cannot outrun the depth guard. */
        private fun count() {
            if (++nodes > MAX_NODES) throw PlotError("expression too large")
        }

        fun expression(minimum: Int): Node {
            if (++depth > MAX_DEPTH) throw PlotError("expression nested too deeply")
            try {
                return expressionInner(minimum)
            } finally {
                depth--
            }
        }

        private fun expressionInner(minimum: Int): Node {
            var left = unary()
            while (true) {
                val token = peek()
                if (token.kind != TokenKind.OPERATOR) break
                val level = precedence(token.text)
                if (level == 0 || level < minimum) break
                index++
                // `^` is RIGHT-associative — `2^3^2` is 2^(3^2) = 512. The site's
                // evalexpr makes it left-associative and answers 64; this is the
                // deliberate divergence, not an accident of the algorithm.
                val next = if (token.text == "^") level else level + 1
                val right = expression(next)
                count()
                left = binaryNode(token.text, left, right)
            }
            return left
        }

        private fun unary(): Node {
            val token = peek()
            if (token.kind == TokenKind.OPERATOR &&
                (token.text == "-" || token.text == "+" || token.text == "!")
            ) {
                index++
                // The operand is parsed at the `^` level, which is what makes
                // unary minus bind *looser* than exponentiation: `-x^2` is
                // −(x²), and `-2^2` is −4.
                val operand = expression(POWER_PRECEDENCE)
                count()
                return unaryNode(token.text, operand)
            }
            return primary()
        }

        private fun primary(): Node {
            val token = peek()
            if (token.kind == TokenKind.NUMBER) {
                index++
                return numberNode(token.value)
            }
            if (token.kind == TokenKind.OPEN) {
                index++
                val inner = expression(1)
                if (peek().kind != TokenKind.CLOSE) throw PlotError("expected ')'")
                index++
                return inner
            }
            if (token.kind == TokenKind.NAME) {
                index++
                return name(token.text)
            }
            if (token.kind == TokenKind.END) throw PlotError("the expression ends too early")
            if (token.kind == TokenKind.CLOSE) throw PlotError("unmatched ')'")
            throw PlotError("unexpected '${token.text}'")
        }

        private fun name(spelling: String): Node {
            if (peek().kind == TokenKind.OPEN) {
                if (!contains(FUNCTIONS, spelling)) throw PlotError("unknown function '$spelling'")
                index++
                val args = ArrayList<Node>()
                if (peek().kind != TokenKind.CLOSE) {
                    args.add(expression(1))
                    while (peek().kind == TokenKind.COMMA) {
                        index++
                        args.add(expression(1))
                    }
                }
                if (peek().kind != TokenKind.CLOSE) throw PlotError("expected ')'")
                index++
                val wanted = arity(spelling)
                if (args.size != wanted) {
                    throw PlotError(
                        "$spelling takes $wanted argument${if (wanted == 1) "" else "s"}, not ${args.size}",
                    )
                }
                return callNode(spelling, args)
            }
            if (spelling == "pi" || spelling == "e") {
                return numberNode(if (spelling == "pi") Math.PI else Math.E)
            }
            if (variable.isNotEmpty() && spelling == variable) return variableNode(spelling)
            if (contains(FUNCTIONS, spelling)) {
                throw PlotError("$spelling is a function — write $spelling(…)")
            }
            throw PlotError("unknown name '$spelling'")
        }

        fun expectEnd() {
            val token = peek()
            if (token.kind == TokenKind.END) return
            if (token.kind == TokenKind.CLOSE) throw PlotError("unmatched ')'")
            throw PlotError("unexpected '${token.text}'")
        }

        private fun peek(): Token = tokens[index]
    }

    // MARK: - Evaluation

    /**
     * Evaluate [node] with [variable] bound to [value].
     *
     * Every value is an IEEE-754 double and **evaluation never fails**: a domain
     * error is NaN or ±∞, which breaks the curve where it happens rather than
     * failing the block. Everything that can be wrong about an expression — an
     * unknown name, the wrong number of arguments, a missing parenthesis — was
     * settled once, at parse time.
     *
     * Comparisons and the Boolean operators yield 1.0 and 0.0, which is the
     * second deliberate divergence from the site: there they produce a Boolean
     * the eval closure discards, so `(x > 0) * sqrt(x)` draws nothing at all.
     */
    fun evaluate(node: Node, variable: String, value: Double): Double {
        if (node.kind == NodeKind.NUMBER) return node.value
        if (node.kind == NodeKind.VARIABLE) return if (node.text == variable) value else Double.NaN
        if (node.kind == NodeKind.UNARY) {
            val operand = evaluate(node.left!!, variable, value)
            if (node.text == "-") return -operand
            if (node.text == "+") return operand
            return if (operand == 0.0) 1.0 else 0.0
        }
        if (node.kind == NodeKind.BINARY) {
            val left = evaluate(node.left!!, variable, value)
            val right = evaluate(node.right!!, variable, value)
            return binary(node.text, left, right)
        }
        // A call.
        val first = evaluate(node.arguments[0], variable, value)
        if (node.arguments.size == 2) {
            val second = evaluate(node.arguments[1], variable, value)
            if (node.text == "atan2") return atan2(first, second)
            if (node.text == "pow") return powIEEE(first, second)
            return hypot(first, second)
        }
        return unary(node.text, first)
    }

    private fun binary(operator: String, left: Double, right: Double): Double {
        if (operator == "+") return left + right
        if (operator == "-") return left - right
        if (operator == "*") return left * right
        // Division is real division: `5/2` is 2.5. evalexpr's integer division
        // answers 2, which is a trap in a plotting language.
        if (operator == "/") return left / right
        if (operator == "%") return left % right
        if (operator == "^") return powIEEE(left, right)
        if (operator == "==") return if (left == right) 1.0 else 0.0
        if (operator == "!=") return if (left != right) 1.0 else 0.0
        if (operator == "<") return if (left < right) 1.0 else 0.0
        if (operator == "<=") return if (left <= right) 1.0 else 0.0
        if (operator == ">") return if (left > right) 1.0 else 0.0
        if (operator == ">=") return if (left >= right) 1.0 else 0.0
        if (operator == "&&") return if (left != 0.0 && right != 0.0) 1.0 else 0.0
        return if (left != 0.0 || right != 0.0) 1.0 else 0.0
    }

    /**
     * `base ^ exponent`, as C99 and IEEE 754 define `pow` — which is **not**
     * what `Math.pow` does in five cases.
     *
     * C99 §F.9.4.4 (and IEEE 754-2008's `pow`) fix two families of answers that
     * nothing else in the language reaches:
     *
     *   * `pow(+1, y)` is 1 for **any** y — ±∞ and NaN included. One raised to
     *     anything is one; there is no sequence of arguments near +1 that gives
     *     anything else, so the value is not "unknown".
     *   * `pow(-1, ±∞)` is 1, because −1 raised to any large integer is ±1 and
     *     the limit of the magnitude is 1.
     *
     * Java disagrees on both: `Math.pow` documents "if the absolute value of
     * the first argument equals 1 and the second argument is infinite, then the
     * result is NaN", and its NaN-exponent rule has no `+1` exception either.
     * Measured, all five come back NaN — `pow(1, NaN)`, `pow(1, ±∞)` and
     * `pow(-1, ±∞)` — where Swift's and Rust's (both C99 `pow` underneath) give
     * 1.0. §1.3 of the spec says everything here is an IEEE-754 double, so the
     * four ports must agree, and the two that agree with IEEE are right.
     *
     * Every other argument pair goes straight to `Math.pow`: for finite
     * exponents `Math.pow(1.0, y)` already answers 1.0, so this special case
     * changes nothing a curve actually samples — only the edge an expression
     * like `1^(1/x)` walks into at x = 0.
     */
    private fun powIEEE(base: Double, exponent: Double): Double {
        if (base == 1.0) return 1.0
        if (base == -1.0 && exponent.isInfinite()) return 1.0
        return Math.pow(base, exponent)
    }

    private fun unary(name: String, v: Double): Double {
        if (name == "sin") return sin(v)
        if (name == "cos") return cos(v)
        if (name == "tan") return tan(v)
        if (name == "asin") return asin(v)
        if (name == "acos") return acos(v)
        if (name == "atan") return atan(v)
        if (name == "sinh") return sinh(v)
        if (name == "cosh") return cosh(v)
        if (name == "tanh") return tanh(v)
        if (name == "asinh") return asinh(v)
        if (name == "acosh") return acosh(v)
        if (name == "atanh") return atanh(v)
        if (name == "sqrt") return sqrt(v)
        if (name == "cbrt") return cbrt(v)
        if (name == "abs") return abs(v)
        if (name == "exp") return exp(v)
        if (name == "exp2") return Math.pow(2.0, v)
        if (name == "ln") return ln(v)
        if (name == "log2") return log2(v)
        if (name == "log10") return log10(v)
        // floor / ceil / round DRAW. On the site they silently produce nothing:
        // `preprocess_math_expr` rewrites every roster name to `math::…` while
        // evalexpr binds exactly these three bare, so `math::floor` is unbound
        // and all 1001 samples fail. `round` is ties-away-from-zero, as Rust's
        // is — never the platform's ties-to-even (`Math.rint`) or ties-up
        // (`Math.round`) rounding.
        if (name == "floor") return floor(v)
        if (name == "ceil") return ceil(v)
        return roundTiesAway(v)
    }

    // MARK: - Drawing

    /** The palette, purple first so a one-series plot matches the site's colour. */
    private val PALETTE = listOf(
        "#673AB7", "#E5390F", "#0F9D58", "#F4B400", "#00838F", "#C2185B", "#5D4037", "#455A64",
    )

    /** The site's margin, and the room each extra asks for beyond it. */
    private const val MARGIN = 40
    private const val TITLE_ROOM = 24
    private const val AXIS_LABEL_ROOM = 18
    private const val LEGEND_MINIMUM = 72
    private const val LEGEND_PADDING = 28
    private const val LEGEND_FONT = 11

    /** Never let the extras eat the figure: the plot area keeps at least this. */
    private const val MIN_PLOT = 40

    /** One sampled point of a series, and whether it may be drawn. */
    private data class Sample(val x: Double, val y: Double)

    private fun draw(spec: PlotSpec): String {
        // Sample first: `y: auto` fits the range to what the series actually
        // produce, so the samples have to exist before the geometry does. They
        // are kept and reused for the drawing pass — sampling twice would be
        // both slower and one more chance for the two passes to disagree.
        val sampled = ArrayList<List<Sample>>()
        for (series in spec.series) sampled.add(sample(series, spec))

        val yRange = resolveY(spec, sampled)
        val yMin = yRange.min
        val yMax = yRange.max

        val labels = ArrayList<String>()
        for (series in spec.series) labels.add(series.label ?: series.source)
        val showLegend =
            spec.legend == Legend.ON ||
                (spec.legend == Legend.AUTO && (spec.series.size >= 2 || hasExplicitLabel(spec.series)))

        val width = spec.width
        val height = spec.height

        var legendWidth = 0
        if (showLegend) {
            var longest = 0.0
            for (label in labels) longest = maxOf(longest, textWidth(label, LEGEND_FONT))
            legendWidth = maxOf(LEGEND_MINIMUM.toDouble(), ceil(longest) + LEGEND_PADDING).toInt()
            legendWidth = minOf(legendWidth, maxOf(0, width - 2 * MARGIN - MIN_PLOT))
            if (legendWidth < LEGEND_MINIMUM / 2) legendWidth = 0
        }

        val horizontal = fitMargins(
            width,
            MARGIN + (if (spec.yLabel.isNotEmpty()) AXIS_LABEL_ROOM else 0),
            MARGIN + legendWidth,
        )
        val vertical = fitMargins(
            height,
            MARGIN + (if (spec.title.isNotEmpty()) TITLE_ROOM else 0),
            MARGIN + (if (spec.xLabel.isNotEmpty()) AXIS_LABEL_ROOM else 0),
        )
        val left = horizontal.low
        val top = vertical.low
        val plotW = width - left - horizontal.high
        val plotH = height - top - vertical.high

        val xMin = spec.xMin
        val xMax = spec.xMax
        val sx: (Double) -> Double = { x -> left + ((x - xMin) / (xMax - xMin)) * plotW }
        val sy: (Double) -> Double = { y -> top + ((yMax - y) / (yMax - yMin)) * plotH }

        val xStep = niceStep(xMax - xMin)
        val yStep = niceStep(yMax - yMin)
        val xTicks = ticks(xMin, xMax, xStep)
        val yTicks = ticks(yMin, yMax, yStep)

        val out = StringBuilder()
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\"")
        out.append(" width=\"$width\" height=\"$height\" role=\"img\">")
        // The accessible name. No `id` anywhere in this SVG, deliberately: a
        // document may hold two plots and a duplicated id is how one figure ends
        // up wearing another's clip path. An `<svg role="img">` takes its name
        // from its own `<title>` with no `aria-labelledby` to point at, so there
        // is nothing to number.
        out.append("<title>").append(escapeHTML(accessibleName(spec, labels))).append("</title>")

        // Ink is `currentColor` with opacity throughout — never the site's
        // #e0e0e0 / #999 / #666 / #ccc. `renderBlock` is theme-blind in all four
        // repos, so the one SVG has to be right in the light preview, the dark
        // preview, print, an exported page and a saved standalone file.
        // `currentColor` is what does that; a baked grey is a light-mode
        // assumption. For the same reason there is no `style="background:white"`
        // on the root, and no `<style>` element: SVG `<style>` inside an HTML
        // document is document-scoped and leaks.
        if (spec.grid) {
            out.append("<g stroke=\"currentColor\" stroke-width=\"0.5\" opacity=\"0.15\">")
            for (tick in xTicks) {
                val at = fixed(sx(tick), 1)
                out.append("<line x1=\"$at\" y1=\"${fixed(top.toDouble(), 1)}\" x2=\"$at\"")
                out.append(" y2=\"${fixed((top + plotH).toDouble(), 1)}\"/>")
            }
            for (tick in yTicks) {
                val at = fixed(sy(tick), 1)
                out.append("<line x1=\"${fixed(left.toDouble(), 1)}\" y1=\"$at\"")
                out.append(" x2=\"${fixed((left + plotW).toDouble(), 1)}\" y2=\"$at\"/>")
            }
            out.append("</g>")
        }

        if (spec.axes) {
            out.append("<g stroke=\"currentColor\" stroke-width=\"1\" opacity=\"0.4\">")
            if (yMin <= 0 && yMax >= 0) {
                val at = fixed(sy(0.0), 1)
                out.append("<line x1=\"${fixed(left.toDouble(), 1)}\" y1=\"$at\"")
                out.append(" x2=\"${fixed((left + plotW).toDouble(), 1)}\" y2=\"$at\"/>")
            }
            if (xMin <= 0 && xMax >= 0) {
                val at = fixed(sx(0.0), 1)
                out.append("<line x1=\"$at\" y1=\"${fixed(top.toDouble(), 1)}\" x2=\"$at\"")
                out.append(" y2=\"${fixed((top + plotH).toDouble(), 1)}\"/>")
            }
            out.append("</g>")

            out.append("<g font-size=\"10\" fill=\"currentColor\" opacity=\"0.65\" font-family=\"sans-serif\">")
            for (tick in xTicks) {
                out.append("<text x=\"${fixed(sx(tick), 1)}\" y=\"${fixed((top + plotH + 15).toDouble(), 1)}\"")
                out.append(" text-anchor=\"middle\">").append(escapeHTML(formatLabel(tick))).append("</text>")
            }
            for (tick in yTicks) {
                out.append("<text x=\"${fixed((left - 5).toDouble(), 1)}\" y=\"${fixed(sy(tick), 1)}\"")
                out.append(" text-anchor=\"end\" dominant-baseline=\"middle\">")
                out.append(escapeHTML(formatLabel(tick))).append("</text>")
            }
            out.append("</g>")

            out.append("<rect x=\"${fixed(left.toDouble(), 1)}\" y=\"${fixed(top.toDouble(), 1)}\"")
            out.append(" width=\"${fixed(plotW.toDouble(), 1)}\" height=\"${fixed(plotH.toDouble(), 1)}\"")
            out.append(" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1\" opacity=\"0.25\"/>")
        }

        if (spec.title.isNotEmpty()) {
            // Centred on the plot area, not on the canvas: the xlabel below is,
            // and a legend gutter would otherwise push the two out of line with
            // each other.
            out.append("<text x=\"${fixed(left + plotW / 2.0, 1)}\" y=\"${fixed((top - 14).toDouble(), 1)}\"")
            out.append(" text-anchor=\"middle\" font-size=\"13\" font-weight=\"600\" fill=\"currentColor\"")
            out.append(" opacity=\"0.85\" font-family=\"sans-serif\">")
            out.append(escapeHTML(spec.title)).append("</text>")
        }
        if (spec.xLabel.isNotEmpty()) {
            out.append("<text x=\"${fixed(left + plotW / 2.0, 1)}\" y=\"${fixed((height - 8).toDouble(), 1)}\"")
            out.append(" text-anchor=\"middle\" font-size=\"11\" fill=\"currentColor\" opacity=\"0.85\"")
            out.append(" font-family=\"sans-serif\">")
            out.append(escapeHTML(spec.xLabel)).append("</text>")
        }
        if (spec.yLabel.isNotEmpty()) {
            val x = fixed(14.0, 1)
            val y = fixed(top + plotH / 2.0, 1)
            out.append("<text x=\"$x\" y=\"$y\" text-anchor=\"middle\" transform=\"rotate(-90 $x $y)\"")
            out.append(" font-size=\"11\" fill=\"currentColor\" opacity=\"0.85\" font-family=\"sans-serif\">")
            out.append(escapeHTML(spec.yLabel)).append("</text>")
        }

        for (index in spec.series.indices) {
            out.append(
                polylines(sampled[index], spec.series[index], colour(index), sx, sy, xMin, xMax, yMin, yMax),
            )
        }

        if (legendWidth > 0) {
            val x = width - horizontal.high + 8
            out.append("<g font-size=\"11\" font-family=\"sans-serif\">")
            for (index in labels.indices) {
                val y = top + 12 + index * 16
                out.append("<line x1=\"${fixed(x.toDouble(), 1)}\" y1=\"${fixed((y - 4).toDouble(), 1)}\"")
                out.append(" x2=\"${fixed((x + 14).toDouble(), 1)}\" y2=\"${fixed((y - 4).toDouble(), 1)}\"")
                out.append(" stroke=\"${colour(index)}\" stroke-width=\"2\"/>")
                out.append("<text x=\"${fixed((x + 20).toDouble(), 1)}\" y=\"${fixed(y.toDouble(), 1)}\"")
                out.append(" fill=\"currentColor\" opacity=\"0.85\">")
                out.append(escapeHTML(labels[index])).append("</text>")
            }
            out.append("</g>")
        }

        out.append("</svg>")
        return out.toString()
    }

    /**
     * The polylines of one series.
     *
     * A point is drawable when it is finite **and** inside the window. Anything
     * else ends the current run and the next drawable point starts a new one —
     * which is what makes `tan(x)` seven branches instead of one figure-wide
     * spike. A run of a single point is still emitted, exactly as the site
     * emits it.
     */
    private fun polylines(
        samples: List<Sample>,
        series: Series,
        stroke: String,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
    ): String {
        val out = StringBuilder()
        val run = StringBuilder()
        var started = false
        val marks = ArrayList<String>()
        for (point in samples) {
            val drawable =
                isFiniteNumber(point.x) &&
                    isFiniteNumber(point.y) &&
                    point.x >= xMin &&
                    point.x <= xMax &&
                    point.y >= yMin &&
                    point.y <= yMax
            if (drawable) {
                val at = "${fixed(sx(point.x), 2)},${fixed(sy(point.y), 2)}"
                if (started) run.append(' ')
                run.append(at)
                started = true
                if (series.kind == SeriesKind.POINTS) {
                    marks.add(
                        "<circle cx=\"${fixed(sx(point.x), 2)}\" cy=\"${fixed(sy(point.y), 2)}\"" +
                            " r=\"2.5\" fill=\"$stroke\"/>",
                    )
                }
            } else if (started) {
                out.append("<polyline points=\"$run\" fill=\"none\" stroke=\"$stroke\" stroke-width=\"2\"/>")
                run.setLength(0)
                started = false
            }
        }
        if (run.isNotEmpty()) {
            out.append("<polyline points=\"$run\" fill=\"none\" stroke=\"$stroke\" stroke-width=\"2\"/>")
        }
        for (mark in marks) out.append(mark)
        return out.toString()
    }

    /**
     * The samples of one series.
     *
     * `x_i = xMin + (i / samples) * (xMax - xMin)`, `i` in `0…samples`
     * **inclusive** — that expression and not the cheaper `xMin + i * dx`,
     * which differs in the last bits for 221 of the 1001 default samples and
     * does change the output.
     */
    private fun sample(series: Series, spec: PlotSpec): List<Sample> {
        val out = ArrayList<Sample>()
        if (series.kind == SeriesKind.POINTS) {
            for (point in series.points) out.add(Sample(point.x, point.y))
            return out
        }
        if (series.kind == SeriesKind.PARAMETRIC) {
            val span = series.tMax - series.tMin
            for (i in 0..spec.samples) {
                val t = series.tMin + (i.toDouble() / spec.samples) * span
                out.add(
                    Sample(
                        x = evaluate(series.expression!!, series.parameter, t),
                        y = evaluate(series.yExpression!!, series.parameter, t),
                    ),
                )
            }
            return out
        }
        val span = spec.xMax - spec.xMin
        for (i in 0..spec.samples) {
            val x = spec.xMin + (i.toDouble() / spec.samples) * span
            out.add(Sample(x, evaluate(series.expression!!, "x", x)))
        }
        return out
    }

    /**
     * `y: auto` — fit the finite samples, then pad 5 % on each side.
     *
     * A series that produces nothing finite contributes nothing; when no series
     * does, the range falls back to −1…1. A flat series has no span to take 5 %
     * of, so it is padded by 5 % of its own value, or by 1 when that is zero too.
     */
    private fun resolveY(spec: PlotSpec, sampled: List<List<Sample>>): Range {
        val specMin = spec.yMin
        val specMax = spec.yMax
        if (specMin != null && specMax != null) return Range(specMin, specMax)
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        for (samples in sampled) {
            for (point in samples) {
                if (!isFiniteNumber(point.y)) continue
                if (point.y < low) low = point.y
                if (point.y > high) high = point.y
            }
        }
        if (!isFiniteNumber(low) || !isFiniteNumber(high)) return Range(-1.0, 1.0)
        val span = high - low
        if (span > 0) return padded(low - span * 0.05, high + span * 0.05, low, high)
        val padding = abs(high) * 0.05
        val pad = if (padding > 0) padding else 1.0
        return padded(low - pad, high + pad, low, high)
    }

    /**
     * The padded range, or the unpadded one when padding overflowed to infinity.
     *
     * `high - low` overflows for a series straddling ~1e308, and `high + pad`
     * overflows for a flat series above ~1.71e308. Either way every `sy()` would
     * come out NaN and the emitted polyline would read `points="40.00,NaN …"` —
     * a curve that silently vanishes with no `plot:` line to explain it. Falling
     * back to the unpadded bounds keeps such a plot drawable; if even those are
     * not finite the caller's `-1..1` default has already been returned.
     */
    private fun padded(min: Double, max: Double, low: Double, high: Double): Range {
        if (isFiniteNumber(min) && isFiniteNumber(max) && max > min) return bounded(min, max)
        if (high > low) return bounded(low, high)
        return bounded(low - 1, high + 1)
    }

    /**
     * A range whose *width* is finite, not merely whose ends are.
     *
     * `sy()` divides by `yMax - yMin`, so a series spanning ~1e308 makes that
     * subtraction overflow even though both bounds are ordinary doubles — and
     * then every coordinate is NaN and the curve silently vanishes. Halving each
     * end keeps the width representable; anything beyond is outside the window
     * and the break rule already omits it, which is the same treatment any other
     * out-of-range point gets.
     */
    private fun bounded(min: Double, max: Double): Range {
        if (!(max > min)) return Range(-1.0, 1.0)
        // Only a range whose *width* overflows needs help. Halving both ends
        // halves the width — both ends are finite here, so this always
        // terminates in one step — and leaves a genuinely huge but narrow range
        // such as [1.69e308, 1.71e308] exactly as the author asked for it.
        if (isFiniteNumber(max - min)) return Range(min, max)
        return Range(min / 2, max / 2)
    }

    private fun accessibleName(spec: PlotSpec, labels: List<String>): String {
        if (spec.title.isNotEmpty()) return spec.title
        if (labels.isEmpty()) return "Plot"
        return "Plot of ${labels.joinToString(", ")}"
    }

    private fun hasExplicitLabel(series: List<Series>): Boolean {
        for (one in series) if (one.label != null) return true
        return false
    }

    private fun colour(index: Int): String = PALETTE[index % PALETTE.size]

    /**
     * An estimate of a string's width at [size] pixels, for the legend gutter
     * only.
     *
     * 0.6 em per character is the usual approximation for a sans-serif face and
     * needs no font metrics, which is what keeps this file free of the platform.
     * It is used to reserve space, never to position anything, so an estimate
     * that is a few pixels out costs a few pixels of gutter.
     */
    private fun textWidth(text: String, size: Int): Double = countCharacters(text) * size * 0.6

    private data class Margins(val low: Int, val high: Int)

    /**
     * Two margins that leave at least [MIN_PLOT] between them.
     *
     * Without this a 160 × 120 figure carrying a title, both axis labels and a
     * legend would compute a negative plot area and draw itself inside out.
     */
    private fun fitMargins(total: Int, low: Int, high: Int): Margins {
        if (total - low - high >= MIN_PLOT) return Margins(low, high)
        val room = maxOf(0, total - MIN_PLOT)
        val sum = low + high
        if (sum <= 0) return Margins(0, 0)
        val scaled = floor((room.toDouble() * low) / sum).toInt()
        return Margins(scaled, room - scaled)
    }

    // MARK: - Axes

    /**
     * The site's tick spacing, ported exactly.
     *
     * ```
     * rough = range / 8 ; mag = 10^floor(log10 rough) ; norm = rough / mag
     * step  = (norm<=1.5 ? 1 : norm<=3 ? 2 : norm<=7 ? 5 : 10) * mag
     * ```
     */
    fun niceStep(range: Double): Double {
        val rough = range / 8
        val magnitude = powerOfTen(floor(log10(rough)))
        val normalised = rough / magnitude
        var step = 10.0
        if (normalised <= 1.5) step = 1.0
        else if (normalised <= 3) step = 2.0
        else if (normalised <= 7) step = 5.0
        return step * magnitude
    }

    /**
     * `10^n` for the whole `n` [niceStep] wants — correctly rounded, which
     * `Math.pow` is not.
     *
     * This is the one line of the geometry where the platform had to be taken
     * out of the loop, and it is not a stylistic call: OpenJDK 21 answers
     * `Math.pow(10.0, -5.0)` one ULP below the true 1e-5 (…368f0 against
     * …368f1), and `StrictMath.pow` agrees with it. Rust and V8 both give the
     * correctly-rounded value, so three of the eighty-one recorded tick
     * spacings came out one ULP light — a visible defect, because that
     * magnitude is then multiplied by 1, 2, 5 or 10 and becomes the *step* every
     * gridline, tick and label of the axis is laid out from.
     *
     * `BigDecimal(unscaled, scale)` is `unscaled × 10^-scale`, so a scale of −n
     * is exactly 10^n with no rounding at all, and `toDouble()` rounds that
     * exact decimal once, correctly. ±∞ and NaN have no whole exponent to build
     * one from, and beyond ±400 every answer is 0 or ∞ exactly — `pow` is right
     * for all of those, so they go back to it.
     */
    private fun powerOfTen(exponent: Double): Double {
        if (!isFiniteNumber(exponent)) return Math.pow(10.0, exponent)
        if (exponent > 400 || exponent < -400) return Math.pow(10.0, exponent)
        return BigDecimal(BigInteger.ONE, -exponent.toInt()).toDouble()
    }

    /** How far past `max` a tick may land and still be drawn: one part in 10⁹ of a step. */
    private const val TICK_EPSILON = 1e-9

    /** A safety valve. [niceStep] gives eight to eleven ticks; a thousand is a bug. */
    private const val MAX_TICKS = 1000

    /**
     * The ticks of one axis.
     *
     * **Computed by index, never accumulated.** The site does `gx += step`,
     * which on `[-1, 1]` at step 0.2 reaches −5.55e−17 instead of 0 and prints
     * the tick at the origin as "-5.6e-17". `first + i * step` lands on an exact
     * zero wherever the arithmetic can, which is the whole fix.
     *
     * The epsilon is the other half of it: a tick that *is* the maximum can miss
     * it by one ulp (`[0.0001, 0.0009]` at step 0.0001 computes
     * 9.000000000000001e-4), and dropping the last tick of an axis because of a
     * rounding error is a visible defect.
     */
    fun ticks(min: Double, max: Double, step: Double): List<Double> {
        val out = ArrayList<Double>()
        if (!(step > 0) || !isFiniteNumber(step)) return out
        val first = ceil(min / step) * step
        val limit = max + step * TICK_EPSILON
        for (i in 0 until MAX_TICKS) {
            val tick = first + i * step
            if (tick > limit) break
            out.add(tick)
        }
        return out
    }

    /**
     * A tick's label, ported from the site's `format_label` **with the two
     * corrections its own output argues for**:
     *
     * ```
     * v == 0                      -> "0"
     * |v| >= 1000 or |v| < 0.01   -> exponential, one fraction digit
     * |v - round(v)| < 1e-9       -> integer, ROUNDED (the site truncates)
     * otherwise                   -> two fraction digits
     * ```
     *
     * The site prints `val as i64`, which truncates: a tick at −4 that arrives
     * as −3.9999999999999996 prints "-3", out of order, in the middle of the
     * axis.
     */
    fun formatLabel(v: Double): String {
        if (v == 0.0) return "0"
        if (v.isNaN()) return formatFixed(v, 2)
        val magnitude = abs(v)
        if (magnitude >= 1000 || magnitude < 0.01) return formatExponential(v, 1)
        val rounded = roundTiesAway(v)
        if (abs(v - rounded) < 1e-9) return formatFixed(rounded, 0)
        return formatFixed(v, 2)
    }

    // MARK: - Number formatting, by hand

    /**
     * Significant digits taken from the *exact* binary value.
     *
     * Twenty-five is enough to decide any rounding this file performs, and the
     * argument is worth writing down because every port depends on it. A double
     * is a dyadic rational m/2^k; a decimal tie at the second significant digit
     * (or at the second fraction digit) is a rational k/10^m with a small m. Two
     * such numbers that are not equal differ by at least 1/(2^52 · 10^m) — about
     * 10⁻¹⁸ relatively — which is a hundred million times larger than the 10⁻²⁵
     * the last of these digits resolves. So a digit string that reads "…5000…0"
     * here *is* an exact tie, and ties-to-even is safe to apply to it.
     *
     * Getting these digits is the one platform call that has to be right, and on
     * this platform it is `java.math.BigDecimal(v)`, which is exact by
     * construction. **Not** `String.format("%.24e", v)`: Java's Formatter pads
     * with zeros past the seventeenth digit, which would turn 0.0125 (really
     * 0.012500000000000000693…) into an exact tie and round it the wrong way.
     * The rounding to 25 digits is HALF_UP on the magnitude, which is what
     * ECMA-262 specifies for `toExponential` ("if there are two such n, pick the
     * larger") and so keeps this identical to the TypeScript reference.
     */
    private const val SIGNIFICANT_DIGITS = 25

    private data class Digits(val digits: String, val exponent: Int)

    private fun significantDigits(value: Double): Digits {
        val rounded = BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS, RoundingMode.HALF_UP))
        var digits = rounded.unscaledValue().toString()
        val exponent = digits.length - 1 - rounded.scale()
        if (digits.length < SIGNIFICANT_DIGITS) digits += zeros(SIGNIFICANT_DIGITS - digits.length)
        return Digits(digits, exponent)
    }

    /**
     * Rust's `{:.<n>e}` — `1.0e3`, `-5.0e-3`, `4.9e-324`.
     *
     * No `+` on the exponent and no zero padding (C and Java write `1.0e+03`),
     * and ties round to even on the exact value (Java's `String.format` rounds
     * 1250 up to `1.3e+03`, where Rust writes `1.2e3`).
     */
    fun formatExponential(v: Double, fractionDigits: Int): String {
        if (v.isNaN()) return "NaN"
        if (v == Double.POSITIVE_INFINITY) return "inf"
        if (v == Double.NEGATIVE_INFINITY) return "-inf"
        val sign = if (isNegative(v)) "-" else ""
        val magnitude = abs(v)
        if (magnitude == 0.0) {
            return sign + "0" + (if (fractionDigits > 0) ".${zeros(fractionDigits)}" else "") + "e0"
        }
        val scanned = significantDigits(magnitude)
        val rounded = roundDigits(scanned.digits, fractionDigits + 1)
        val exponent = scanned.exponent + (if (rounded.overflow) 1 else 0)
        val digits = rounded.digits
        val fraction = if (fractionDigits > 0) ".${digits.substring(1)}" else ""
        return "$sign${digits[0]}${fraction}e$exponent"
    }

    /**
     * Rust's `{:.<n>}` — `2.50`, `-0.00`, `1000.00`.
     *
     * Ties to even on the exact value: 0.125 is `0.12` and 8.125 is `8.12`,
     * where `String.format("%.2f", …)` writes `0.13` and `8.13`. The sign
     * survives a rounded-away zero (`-0.00`), which is what Rust prints and what
     * keeps the two comparable.
     */
    fun formatFixed(v: Double, fractionDigits: Int): String {
        if (v.isNaN()) return "NaN"
        if (v == Double.POSITIVE_INFINITY) return "inf"
        if (v == Double.NEGATIVE_INFINITY) return "-inf"
        val sign = if (isNegative(v)) "-" else ""
        val magnitude = abs(v)

        var whole = "0"
        var fraction = ""
        if (magnitude != 0.0) {
            val scanned = significantDigits(magnitude)
            val wholeLength = scanned.exponent + 1
            if (wholeLength <= 0) {
                fraction = zeros(-wholeLength) + scanned.digits
            } else if (wholeLength >= scanned.digits.length) {
                whole = scanned.digits + zeros(wholeLength - scanned.digits.length)
            } else {
                whole = scanned.digits.substring(0, wholeLength)
                fraction = scanned.digits.substring(wholeLength)
            }
        }

        if (fraction.length <= fractionDigits) {
            fraction += zeros(fractionDigits - fraction.length)
        } else {
            val kept = fraction.substring(0, fractionDigits)
            val next = fraction[fractionDigits] - '0'
            var up = next > 5
            if (next == 5) {
                var more = false
                for (index in fractionDigits + 1 until fraction.length) {
                    if (fraction[index] != '0') {
                        more = true
                        break
                    }
                }
                if (more) {
                    up = true
                } else {
                    val previous =
                        if (fractionDigits > 0) fraction[fractionDigits - 1] - '0'
                        else whole[whole.length - 1] - '0'
                    up = previous % 2 == 1
                }
            }
            if (!up) {
                fraction = kept
            } else {
                val carried = increment(kept)
                if (carried.overflow) {
                    // `.99` + 1 is `1.00`: the fraction goes back to zeros and
                    // the carry lands on the integer part, which is the one
                    // place a digit string is allowed to grow (999 -> 1000).
                    fraction = zeros(fractionDigits)
                    whole = increment(whole).digits
                } else {
                    fraction = carried.digits
                }
            }
        }

        return "$sign$whole${if (fractionDigits > 0) ".$fraction" else ""}"
    }

    /** [formatFixed], for the coordinates the emitter writes. */
    private fun fixed(v: Double, fractionDigits: Int): String = formatFixed(v, fractionDigits)

    private data class Rounded(val digits: String, val overflow: Boolean)

    /**
     * Round a digit string to [keep] digits, ties to even, reporting whether the
     * carry ran off the front — in which case the digits are `1` followed by
     * zeros and the caller owes the exponent a 1.
     */
    private fun roundDigits(digits: String, keep: Int): Rounded {
        if (keep >= digits.length) return Rounded(digits + zeros(keep - digits.length), false)
        val kept = digits.substring(0, keep)
        val next = digits[keep] - '0'
        var up = next > 5
        if (next == 5) {
            var more = false
            for (index in keep + 1 until digits.length) {
                if (digits[index] != '0') {
                    more = true
                    break
                }
            }
            up = if (more) true else (digits[keep - 1] - '0') % 2 == 1
        }
        if (!up) return Rounded(kept, false)
        val carried = increment(kept)
        if (!carried.overflow) return Rounded(carried.digits, false)
        // "999" + 1 is "1000"; renormalised to `keep` digits that is "100" one
        // decimal place further left.
        return Rounded("1" + zeros(keep - 1), true)
    }

    /** [digits] + 1, keeping the length; `overflow` says the carry ran off the front. */
    private fun increment(digits: String): Rounded {
        val out = CharArray(digits.length)
        for (index in digits.indices) out[index] = digits[index]
        var index = out.size - 1
        while (index >= 0) {
            if (out[index] == '9') {
                out[index] = '0'
                index--
            } else {
                out[index] = out[index] + 1
                return Rounded(String(out), false)
            }
        }
        return Rounded("1" + String(out), true)
    }

    /**
     * Rust's `f64::round`: halfway cases go away from zero.
     *
     * Not `Math.round`, which sends −2.5 to −2, not `Math.rint`, which sends 2.5
     * to 2, and not the `floor(v + 0.5)` trick, which sends 0.49999999999999994
     * to 1.
     */
    private fun roundTiesAway(v: Double): Double {
        val whole = truncate(v)
        val fraction = v - whole
        if (fraction >= 0.5) return whole + 1
        if (fraction <= -0.5) return whole - 1
        return whole
    }

    private fun isNegative(v: Double): Boolean = v < 0 || (v == 0.0 && 1 / v < 0)

    private fun zeros(count: Int): String = if (count > 0) "0".repeat(count) else ""

    // MARK: - Small string helpers
    //
    // Written out rather than reached for, because the four ports have four
    // different ideas of what `trim` and `split` mean and the differences are
    // exactly the kind that survive a code review. In particular Kotlin's own
    // `trim()` is banned in this codebase's parsing paths (see
    // `MarkdownParser.trimSpaces`): it is neither the Foundation
    // `.whitespaces` set the Apple ports use nor the two characters this
    // grammar defines. The expression language's whitespace is exactly space
    // and tab — the lines were already split — so it is spelled out here, the
    // same two characters in all four ports.

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t'

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    private fun isLowerLetter(c: Char): Boolean = c in 'a'..'z'

    private fun isIdentifierStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_'

    private fun isIdentifierPart(c: Char): Boolean = isIdentifierStart(c) || isDigit(c)

    private fun isFiniteNumber(v: Double): Boolean =
        !v.isNaN() && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY

    private fun trim(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isSpace(text[start])) start++
        while (end > start && isSpace(text[end - 1])) end--
        return text.substring(start, end)
    }

    private fun lowercased(text: String): String {
        // The locale-independent one, never `lowercase(Locale.getDefault())`: a
        // Turkish locale spells `AUTO` with a dotless ı and the directive would
        // stop matching.
        return text.lowercase()
    }

    /** Lines, on CRLF, LF or CR — the parser's own set, not the wide Unicode one. */
    private fun splitLines(source: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        while (index < source.length) {
            val c = source[index]
            if (c == '\n') {
                out.add(current.toString())
                current.setLength(0)
            } else if (c == '\r') {
                out.add(current.toString())
                current.setLength(0)
                if (index + 1 < source.length && source[index + 1] == '\n') index++
            } else {
                current.append(c)
            }
            index++
        }
        out.add(current.toString())
        return out
    }

    private fun splitWhitespace(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (index in text.indices) {
            val c = text[index]
            if (isSpace(c)) {
                if (current.isNotEmpty()) out.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun contains(list: List<String>, value: String): Boolean {
        for (one in list) if (one == value) return true
        return false
    }

    /** The index of the first [first][second] pair, or −1. */
    private fun indexOfPair(text: String, first: Char, second: Char): Int {
        var index = 0
        while (index + 1 < text.length) {
            if (text[index] == first && text[index + 1] == second) return index
            index++
        }
        return -1
    }

    /** The index of the `)` matching the `(` at [open], or −1. */
    private fun matchingParenthesis(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            val c = text[index]
            if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
                if (depth == 0) return index
            }
        }
        return -1
    }

    /** The index of the first comma at parenthesis depth 0, or −1. */
    private fun topLevelComma(text: String): Int {
        var depth = 0
        for (index in text.indices) {
            val c = text[index]
            if (c == '(') depth++
            else if (c == ')') depth--
            else if (c == ',' && depth == 0) return index
        }
        return -1
    }

    /** Does [text] begin with [word] as a whole word? */
    private fun startsWithWord(text: String, word: String): Boolean {
        if (text.length < word.length) return false
        if (text.substring(0, word.length) != word) return false
        if (text.length == word.length) return true
        return !isIdentifierPart(text[word.length])
    }

    /** Characters, counting an astral pair as one — the closest analogue of Swift's `Character`. */
    private fun countCharacters(text: String): Int = text.codePointCount(0, text.length)

    /**
     * The five XML entities, and no other.
     *
     * Identical to `MarkdownHtml.escape`, and duplicated rather than shared
     * because that one is private to a file this must not depend on: every
     * author string in the figure — the title, both axis labels, every legend
     * label — passes through here before it reaches the markup. The EPUB body is
     * XHTML, i.e. XML, so one raw `&` makes the whole content document
     * unparseable and the book unopenable; a named entity such as `&nbsp;` is
     * undefined in XML and would do the same.
     */
    private fun escapeHTML(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
