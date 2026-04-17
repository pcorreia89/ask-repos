package askrepo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun parsesSimpleKeyValuePairs() {
        val result = parseDotenv("FOO=bar\nBAZ=qux")
        assertEquals(mapOf("FOO" to "bar", "BAZ" to "qux"), result)
    }

    @Test
    fun skipsCommentsAndBlankLines() {
        val result = parseDotenv("""
            # comment

            KEY=value
            # another comment
        """.trimIndent())
        assertEquals(mapOf("KEY" to "value"), result)
    }

    @Test
    fun stripsDoubleQuotes() {
        val result = parseDotenv("""KEY="hello world"""")
        assertEquals("hello world", result["KEY"])
    }

    @Test
    fun stripsSingleQuotes() {
        val result = parseDotenv("KEY='hello world'")
        assertEquals("hello world", result["KEY"])
    }

    @Test
    fun handlesEqualsInValue() {
        val result = parseDotenv("KEY=a=b=c")
        assertEquals("a=b=c", result["KEY"])
    }

    @Test
    fun trimsWhitespace() {
        val result = parseDotenv("  KEY  =  value  ")
        assertEquals("value", result["KEY"])
    }

    @Test
    fun skipsLinesWithoutEquals() {
        val result = parseDotenv("NOEQUALS\nKEY=val")
        assertEquals(1, result.size)
        assertEquals("val", result["KEY"])
    }

    @Test
    fun skipsLinesWithLeadingEquals() {
        val result = parseDotenv("=value\nKEY=val")
        assertEquals(1, result.size)
    }

    @Test
    fun emptyValueIsAllowed() {
        val result = parseDotenv("KEY=")
        assertEquals("", result["KEY"])
    }

    @Test
    fun preservesOrderOfEntries() {
        val result = parseDotenv("B=2\nA=1\nC=3")
        assertEquals(listOf("B", "A", "C"), result.keys.toList())
    }

    @Test
    fun defaultsAreCorrect() {
        assertEquals("claude-haiku-4-5", Defaults.ANTHROPIC_MODEL)
        assertEquals("voyage-code-3", Defaults.VOYAGE_MODEL)
        assertEquals(12, Defaults.TOP_K)
        assertEquals(1024, Defaults.MAX_TOKENS)
        assertEquals(1_000_000L, Defaults.MAX_FILE_BYTES)
        assertEquals(64, Defaults.EMBED_BATCH_SIZE)
    }

    @Test
    fun includedExtensionsContainsExpectedTypes() {
        assertTrue("kt" in Defaults.INCLUDED_EXTENSIONS)
        assertTrue("py" in Defaults.INCLUDED_EXTENSIONS)
        assertTrue("js" in Defaults.INCLUDED_EXTENSIONS)
        assertTrue("md" in Defaults.INCLUDED_EXTENSIONS)
        assertTrue("go" in Defaults.INCLUDED_EXTENSIONS)
        assertTrue("rs" in Defaults.INCLUDED_EXTENSIONS)
    }

    @Test
    fun builtinIgnoresContainsExpectedEntries() {
        assertTrue(".git" in Defaults.BUILTIN_IGNORES)
        assertTrue("node_modules" in Defaults.BUILTIN_IGNORES)
        assertTrue("build" in Defaults.BUILTIN_IGNORES)
        assertTrue("target" in Defaults.BUILTIN_IGNORES)
    }
}
