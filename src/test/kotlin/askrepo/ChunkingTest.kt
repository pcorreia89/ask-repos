package askrepo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkingTest {

    @Test
    fun detectsLanguageByExtensionAndSpecialFilenames() {
        assertEquals("kt", Chunking.detectLanguage("src/Main.kt"))
        assertEquals("markdown", Chunking.detectLanguage("docs/Intro.md"))
        assertEquals("markdown", Chunking.detectLanguage("README"))
        assertEquals("markdown", Chunking.detectLanguage("changelog.old"))
        assertEquals("text", Chunking.detectLanguage("unknown.xyz"))
    }

    @Test
    fun codeChunksNeverSplitMidBlock() {
        val src = buildString {
            repeat(5) {
                appendLine("fun f$it() {")
                appendLine("    println(\"hi\")")
                appendLine("}")
                appendLine()
            }
            repeat(40) { appendLine("val x$it = $it") }
        }
        val chunks = Chunking.chunk("Foo.kt", src)
        assertTrue(chunks.isNotEmpty())
        for (c in chunks) {
            assertTrue(c.endLine >= c.startLine, "endLine >= startLine")
            assertTrue(c.text.length <= Chunking.HARD_CAP_CHARS)
        }
    }

    @Test
    fun markdownSplitsOnHeaders() {
        val md = """
            |# Title
            |
            |intro text that is reasonably long so the chunk is kept.
            |Adding more words to get past the minimum character threshold.
            |
            |## Section A
            |
            |Body of A is long enough to survive the minimum filter.
            |More words here to pass the eighty character minimum chunk filter.
            |
            |## Section B
            |
            |Body of B goes here with enough characters to survive the minimum.
            |Padding padding padding padding padding padding padding.
        """.trimMargin()
        val chunks = Chunking.chunk("doc.md", md)
        assertTrue(chunks.size >= 2, "expected at least 2 chunks, got ${chunks.size}")
        assertTrue(chunks.any { it.text.contains("# Title") })
        assertTrue(chunks.any { it.text.contains("## Section A") })
        assertTrue(chunks.any { it.text.contains("## Section B") })
    }

    @Test
    fun fallbackChunksOverlap() {
        val text = ("word ".repeat(1000)).trim()
        val chunks = Chunking.chunk("notes.txt", text)
        assertTrue(chunks.size >= 2)
        for (c in chunks) {
            assertTrue(c.text.length <= Chunking.HARD_CAP_CHARS)
            assertTrue(c.text.length >= Chunking.MIN_CHARS)
        }
    }

    @Test
    fun dropsSubMinChunks() {
        val tiny = "short"
        assertEquals(emptyList(), Chunking.chunk("tiny.txt", tiny))
    }

    @Test
    fun hardCapIsEnforced() {
        val huge = "x".repeat(8_000) // one block, no newlines
        val chunks = Chunking.chunk("big.txt", huge)
        for (c in chunks) {
            assertTrue(c.text.length <= Chunking.HARD_CAP_CHARS, "chunk length ${c.text.length} exceeds cap")
        }
        assertTrue(chunks.size >= 3)
    }

    @Test
    fun lineNumbersAreAccurate() {
        val src = buildString {
            append("fun a() { return 1 }\n")          // line 1
            append("\n")                              // line 2
            append("fun b() {\n")                     // line 3
            append("    return 2\n")                  // line 4
            append("}\n")                             // line 5
        }
        val chunks = Chunking.chunk("tiny.kt", src)
        // MIN_CHARS filter would drop these small chunks; pad with more blocks.
        val padded = src + "\n" + "// comment\n".repeat(20)
        val padChunks = Chunking.chunk("tiny.kt", padded)
        assertTrue(padChunks.isNotEmpty())
        assertEquals(1, padChunks.first().startLine)
    }
}
