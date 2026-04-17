package askrepo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetrieveTest {

    private fun chunk(id: String) = StoredChunk(id, "file.kt", 1, 10, "kt", "text")

    @Test
    fun returnsEmptyForEmptyInput() {
        val result = Retrieve.topK(floatArrayOf(1f, 0f), emptyList(), emptyList(), 5)
        assertEquals(emptyList(), result)
    }

    @Test
    fun returnsEmptyForZeroQueryVector() {
        val chunks = listOf(chunk("a"))
        val vectors = listOf(floatArrayOf(1f, 0f))
        val result = Retrieve.topK(floatArrayOf(0f, 0f), chunks, vectors, 5)
        assertEquals(emptyList(), result)
    }

    @Test
    fun skipsZeroDocumentVectors() {
        val chunks = listOf(chunk("a"), chunk("b"))
        val vectors = listOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f))
        val result = Retrieve.topK(floatArrayOf(1f, 0f), chunks, vectors, 5)
        assertEquals(1, result.size)
        assertEquals("b", result[0].chunk.id)
    }

    @Test
    fun ranksByCosineSimilarity() {
        val chunks = listOf(chunk("orthogonal"), chunk("parallel"), chunk("anti"))
        val vectors = listOf(
            floatArrayOf(0f, 1f),
            floatArrayOf(1f, 0f),
            floatArrayOf(-1f, 0f),
        )
        val query = floatArrayOf(1f, 0f)
        val result = Retrieve.topK(query, chunks, vectors, 3)
        assertEquals("parallel", result[0].chunk.id)
        assertEquals("orthogonal", result[1].chunk.id)
        assertEquals("anti", result[2].chunk.id)
        assertTrue(result[0].score > result[1].score)
        assertTrue(result[1].score > result[2].score)
    }

    @Test
    fun respectsKLimit() {
        val chunks = (1..10).map { chunk("c$it") }
        val vectors = (1..10).map { floatArrayOf(it.toFloat(), 0f) }
        val result = Retrieve.topK(floatArrayOf(1f, 0f), chunks, vectors, 3)
        assertEquals(3, result.size)
    }

    @Test
    fun identicalVectorsScoreOne() {
        val chunks = listOf(chunk("a"))
        val vectors = listOf(floatArrayOf(3f, 4f))
        val result = Retrieve.topK(floatArrayOf(3f, 4f), chunks, vectors, 1)
        assertEquals(1, result.size)
        assertTrue(result[0].score > 0.999f)
    }

    @Test
    fun oppositeVectorsScoreNegativeOne() {
        val chunks = listOf(chunk("a"))
        val vectors = listOf(floatArrayOf(-3f, -4f))
        val result = Retrieve.topK(floatArrayOf(3f, 4f), chunks, vectors, 1)
        assertEquals(1, result.size)
        assertTrue(result[0].score < -0.999f)
    }

    @Test
    fun failsOnChunkVectorLengthMismatch() {
        val chunks = listOf(chunk("a"), chunk("b"))
        val vectors = listOf(floatArrayOf(1f))
        val ex = try {
            Retrieve.topK(floatArrayOf(1f), chunks, vectors, 1)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex != null)
    }

    @Test
    fun tokenizeSplitsAndLowercases() {
        val tokens = Retrieve.tokenize("Hello World! This is a TEST_123")
        assertEquals(listOf("hello", "world", "this", "is", "test", "123"), tokens)
    }

    @Test
    fun tokenizeDropsSingleCharTokens() {
        val tokens = Retrieve.tokenize("a b cc dd")
        assertEquals(listOf("cc", "dd"), tokens)
    }

    @Test
    fun bm25ScoresHigherForMatchingTerms() {
        val chunks = listOf(
            StoredChunk("a", "auth.kt", 1, 10, "kt", "authentication login password user session"),
            StoredChunk("b", "db.kt", 1, 10, "kt", "database connection pool query migration"),
            StoredChunk("c", "auth2.kt", 1, 10, "kt", "login flow validates user password reset"),
        )
        val scores = Retrieve.bm25Scores("how does login authentication work", chunks)
        assertTrue(scores[0] > scores[1], "auth chunk should score higher than db chunk")
        assertTrue(scores[2] > scores[1], "auth2 chunk should score higher than db chunk")
    }

    @Test
    fun bm25ScoresZeroForNoMatch() {
        val chunks = listOf(
            StoredChunk("a", "f.kt", 1, 10, "kt", "completely unrelated content about weather"),
        )
        val scores = Retrieve.bm25Scores("authentication login", chunks)
        assertEquals(0f, scores[0])
    }

    @Test
    fun topKHybridCombinesBothSignals() {
        val chunks = listOf(
            StoredChunk("vec-match", "a.kt", 1, 10, "kt", "unrelated text with no keyword overlap"),
            StoredChunk("keyword-match", "b.kt", 1, 10, "kt", "authentication login password security"),
        )
        val vectors = listOf(
            floatArrayOf(1f, 0f),
            floatArrayOf(0.5f, 0.5f),
        )
        val query = floatArrayOf(1f, 0f)
        val results = Retrieve.topKHybrid("authentication login", query, chunks, vectors, 2)
        assertEquals(2, results.size)
    }
}
