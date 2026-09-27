/*
 * DocumentTypesTest.kt
 * md (Android)
 *
 * The name helpers behind Save As / export / Share Source suggestions:
 * every extension md opens is stripped — in any letter case, exactly once
 * — before the new one goes on, so `Notes.mkd` suggests `Notes.md` and
 * `Notes.pdf`, never `Notes.mkd.md` or `Notes.mkd.pdf`.
 */

package me.nettrash.md

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentTypesTest {

    @Test fun baseNameStripsEveryOpenableExtension() {
        for (extension in DocumentTypes.ALL) {
            assertEquals(extension, "Notes", DocumentTypes.baseName("Notes.$extension"))
        }
    }

    @Test fun baseNameIgnoresLetterCase() {
        assertEquals("NOTES", DocumentTypes.baseName("NOTES.MD"))
        assertEquals("Readme", DocumentTypes.baseName("Readme.Md"))
        assertEquals("diagram", DocumentTypes.baseName("diagram.PUML"))
        assertEquals("Doc", DocumentTypes.baseName("Doc.TextPack"))
    }

    @Test fun baseNameStripsExactlyOneExtension() {
        assertEquals("Notes.mkd", DocumentTypes.baseName("Notes.mkd.md"))
        assertEquals("Notes.md", DocumentTypes.baseName("Notes.md.md"))
    }

    @Test fun baseNameLeavesForeignExtensionsAlone() {
        assertEquals("archive.tar.gz", DocumentTypes.baseName("archive.tar.gz"))
        assertEquals("graph.dot", DocumentTypes.baseName("graph.dot"))
        assertEquals("Untitled", DocumentTypes.baseName("Untitled"))
        assertEquals("v1.2", DocumentTypes.baseName("v1.2"))
    }

    @Test fun baseNameOfABareExtensionIsEmpty() {
        assertEquals("", DocumentTypes.baseName(".md"))
        assertEquals("", DocumentTypes.baseName(""))
    }

    @Test fun markdownFileNameSwapsTheExtensionForMd() {
        assertEquals("Notes.md", DocumentTypes.markdownFileName("Notes.md"))
        assertEquals("Notes.md", DocumentTypes.markdownFileName("Notes.markdown"))
        assertEquals("Notes.md", DocumentTypes.markdownFileName("Notes.mkd"))
        assertEquals("Notes.md", DocumentTypes.markdownFileName("Notes.mdtxt"))
        assertEquals("diagram.md", DocumentTypes.markdownFileName("diagram.puml"))
        assertEquals("graph.md", DocumentTypes.markdownFileName("graph.gv"))
        assertEquals("readme.md", DocumentTypes.markdownFileName("readme.txt"))
        assertEquals("Doc.md", DocumentTypes.markdownFileName("Doc"))
    }

    @Test fun markdownFileNameFallsBackToUntitled() {
        assertEquals("Untitled.md", DocumentTypes.markdownFileName(""))
        assertEquals("Untitled.md", DocumentTypes.markdownFileName("   "))
        assertEquals("Untitled.md", DocumentTypes.markdownFileName(".md"))
    }
}
