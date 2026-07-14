/*
 * ExamplesTest.kt
 * md (Android)
 *
 * Tests over the bundled examples (assets/examples): the eight sample
 * documents the Examples menu lists and the "Example Book" tree that
 * Example Book… copies into a user-picked folder. The file set is pinned —
 * the menu and the copied book are only as good as what ships — and every
 * example must parse and render, so a broken sample fails here rather than
 * in front of a first-time user.
 */

package me.nettrash.md

import me.nettrash.md.markdown.MarkdownHtml
import me.nettrash.md.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExamplesTest {

    /** The root example files, exactly as the Examples menu lists them. */
    private val rootExamples = listOf(
        "01-Welcome.md", "02-Formatting.md", "03-Tables.md", "04-Code.md",
        "05-Images.md", "06-Math.md", "07-Diagrams.md", "08-Writer Tools.md",
    )

    /** The example book's articles, relative to its folder. */
    private val bookArticles = listOf(
        "01-Preface.md",
        "02-Getting Started/01-The Editor.md",
        "02-Getting Started/02-Saving and Export.md",
        "03-Going Further/01-Rich Content.md",
        "03-Going Further/02-Writing a Book.md",
    )

    /** Assets aren't on the unit-test classpath (only `res` resources are),
     *  so the examples are read straight off disk: relative to the module
     *  directory Gradle runs tests in, falling back through `user.dir` for
     *  runners with another working directory. */
    private val examplesDir: File = run {
        val direct = File("src/main/assets/examples")
        if (direct.exists()) direct
        else File(checkNotNull(System.getProperty("user.dir")) { "no user.dir" })
            .resolve("src/main/assets/examples")
    }

    @Test fun examplesDirectoryShips() {
        assertTrue("missing ${examplesDir.absolutePath}", examplesDir.isDirectory)
    }

    @Test fun rootExamplesAreExactlyTheMenuEntries() {
        val names = examplesDir
            .listFiles { file -> file.isFile && file.extension == "md" }
            ?.map { it.name }
            ?.sorted()
        assertEquals(rootExamples, names)
    }

    @Test fun exampleBookCarriesItsArticles() {
        val book = examplesDir.resolve("Example Book")
        assertTrue("missing ${book.absolutePath}", book.isDirectory)
        val articles = book.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(book).path.replace(File.separatorChar, '/') }
            .sorted()
            .toList()
        assertEquals(bookArticles, articles)
    }

    @Test fun everyExampleParsesAndRenders() {
        val all = examplesDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .toList()
        assertEquals(rootExamples.size + bookArticles.size, all.size)
        for (file in all) {
            val source = file.readText()
            assertTrue("${file.name} parsed to no blocks", MarkdownParser.parse(source).isNotEmpty())
            val html = MarkdownHtml.document(source, title = file.nameWithoutExtension, dark = false)
            assertTrue("${file.name} rendered no body", html.contains("<body"))
        }
    }
}
