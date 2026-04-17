package askrepo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitignoreTest {

    @Test
    fun emptyIgnoresNothing() {
        val g = Gitignore.parse("")
        assertFalse(g.isIgnored("any/file.kt", false))
    }

    @Test
    fun skipsCommentsAndBlankLines() {
        val g = Gitignore.parse(
            """
            |# a comment
            |
            |build
            """.trimMargin()
        )
        assertTrue(g.isIgnored("build", true))
        assertTrue(g.isIgnored("build/classes/foo.class", false))
    }

    @Test
    fun starMatchesWithinSegment() {
        val g = Gitignore.parse("*.log")
        assertTrue(g.isIgnored("foo.log", false))
        assertTrue(g.isIgnored("deep/path/foo.log", false))
        assertFalse(g.isIgnored("foo.log.bak", false))
    }

    @Test
    fun leadingSlashIsRootAnchored() {
        val g = Gitignore.parse("/build")
        assertTrue(g.isIgnored("build", true))
        assertTrue(g.isIgnored("build/foo", false))
        assertFalse(g.isIgnored("sub/build", true))
    }

    @Test
    fun doubleStarMatchesAnyDepth() {
        val g = Gitignore.parse("**/node_modules")
        assertTrue(g.isIgnored("node_modules", true))
        assertTrue(g.isIgnored("a/node_modules", true))
        assertTrue(g.isIgnored("a/b/c/node_modules/x/y.js", false))
    }

    @Test
    fun trailingSlashMeansDirectoryOnly() {
        val g = Gitignore.parse("logs/")
        assertTrue(g.isIgnored("logs", true))
        assertFalse(g.isIgnored("logs", false))
        assertTrue(g.isIgnored("logs/today.log", false))
    }

    @Test
    fun negationReinstates() {
        val g = Gitignore.parse(
            """
            |*.log
            |!keep.log
            """.trimMargin()
        )
        assertTrue(g.isIgnored("drop.log", false))
        assertFalse(g.isIgnored("keep.log", false))
    }

    @Test
    fun patternContainingSlashIsAnchored() {
        val g = Gitignore.parse("src/generated")
        assertTrue(g.isIgnored("src/generated", true))
        assertTrue(g.isIgnored("src/generated/foo.kt", false))
        assertFalse(g.isIgnored("other/src/generated", true))
    }

    @Test
    fun questionMarkMatchesSingleCharExceptSlash() {
        val g = Gitignore.parse("file?.txt")
        assertTrue(g.isIgnored("file1.txt", false))
        assertFalse(g.isIgnored("file12.txt", false))
        assertFalse(g.isIgnored("file/.txt", false))
    }
}
