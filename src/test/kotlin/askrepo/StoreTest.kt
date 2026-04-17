package askrepo

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {

    private fun tempDir() = Files.createTempDirectory("store-test")

    @Test
    fun existsReturnsFalseForEmptyDir() {
        val dir = tempDir()
        assertFalse(Store.exists(dir))
    }

    @Test
    fun existsReturnsTrueWhenAllFilesPresent() {
        val dir = tempDir()
        val manifest = Manifest("repo", "model", "embed", 3, "2024-01-01", "2024-01-01")
        val chunks = listOf(StoredChunk("c1", "f.kt", 1, 5, "kt", "code"))
        val vectors = listOf(floatArrayOf(1f, 2f, 3f))
        Store.writeManifest(dir, manifest)
        Store.writeChunks(dir, chunks)
        Store.writeVectors(dir, 3, vectors)
        assertTrue(Store.exists(dir))
    }

    @Test
    fun manifestRoundTrip() {
        val dir = tempDir()
        val manifest = Manifest("/path/to/repo", "claude-haiku-4-5", "voyage-code-3", 128, "2024-01-01T00:00:00Z", "2024-06-15T12:30:00Z")
        Store.writeManifest(dir, manifest)
        val loaded = Store.readManifest(dir)
        assertEquals(manifest, loaded)
    }

    @Test
    fun readManifestReturnsNullWhenMissing() {
        val dir = tempDir()
        assertNull(Store.readManifest(dir))
    }

    @Test
    fun filesIndexRoundTrip() {
        val dir = tempDir()
        val index = FilesIndex(mapOf(
            "src/Main.kt" to FileEntry("abc123", listOf("c1", "c2")),
            "README.md" to FileEntry("def456", listOf("c3")),
        ))
        Store.writeFilesIndex(dir, index)
        val loaded = Store.readFilesIndex(dir)
        assertEquals(index, loaded)
    }

    @Test
    fun readFilesIndexReturnsEmptyWhenMissing() {
        val dir = tempDir()
        assertEquals(FilesIndex(), Store.readFilesIndex(dir))
    }

    @Test
    fun chunksRoundTrip() {
        val dir = tempDir()
        val chunks = listOf(
            StoredChunk("c1", "a.kt", 1, 10, "kt", "fun main() {}"),
            StoredChunk("c2", "b.md", 1, 5, "markdown", "# Title\nContent"),
        )
        Store.writeChunks(dir, chunks)
        val loaded = Store.readChunks(dir)
        assertEquals(chunks, loaded)
    }

    @Test
    fun readChunksReturnsEmptyWhenMissing() {
        val dir = tempDir()
        assertEquals(emptyList(), Store.readChunks(dir))
    }

    @Test
    fun vectorsRoundTrip() {
        val dir = tempDir()
        val vectors = listOf(
            floatArrayOf(1.0f, 2.0f, 3.0f),
            floatArrayOf(4.0f, 5.0f, 6.0f),
        )
        Store.writeVectors(dir, 3, vectors)
        val (dim, loaded) = Store.readVectors(dir)
        assertEquals(3, dim)
        assertEquals(2, loaded.size)
        assertTrue(vectors[0].contentEquals(loaded[0]))
        assertTrue(vectors[1].contentEquals(loaded[1]))
    }

    @Test
    fun readVectorsReturnsEmptyWhenMissing() {
        val dir = tempDir()
        val (dim, vecs) = Store.readVectors(dir)
        assertEquals(0, dim)
        assertEquals(emptyList(), vecs)
    }

    @Test
    fun vectorDimensionMismatchThrows() {
        val dir = tempDir()
        val ex = try {
            Store.writeVectors(dir, 3, listOf(floatArrayOf(1f, 2f)))
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex != null)
    }

    @Test
    fun listNamedIndexesFindsValidIndexes() {
        val base = tempDir()
        val valid = base.resolve("my-repo")
        Files.createDirectories(valid)
        Store.writeManifest(valid, Manifest("repo", "m", "e", 3, "now", "now"))
        Store.writeChunks(valid, listOf(StoredChunk("c1", "f.kt", 1, 1, "kt", "x".repeat(100))))
        Store.writeVectors(valid, 3, listOf(floatArrayOf(1f, 2f, 3f)))

        val empty = base.resolve("empty-dir")
        Files.createDirectories(empty)

        val names = Store.listNamedIndexes(base)
        assertEquals(listOf("my-repo"), names)
    }

    @Test
    fun listNamedIndexesReturnsEmptyForMissingBase() {
        val base = tempDir().resolve("nonexistent")
        assertEquals(emptyList(), Store.listNamedIndexes(base))
    }

    @Test
    fun resolveIndexDirPrefersNameOverPath() {
        val base = tempDir()
        val result = Store.resolveIndexDir(base.resolve("repo"), "my-name", base)
        assertEquals(base.resolve("my-name"), result)
    }

    @Test
    fun resolveIndexDirFallsBackToRepoLocal() {
        val repo = tempDir()
        val result = Store.resolveIndexDir(repo, null, tempDir())
        assertEquals(repo.resolve(Defaults.INDEX_DIR), result)
    }

    @Test
    fun resolveIndexDirThrowsWhenBothNull() {
        val ex = try {
            Store.resolveIndexDir(null, null, tempDir())
            null
        } catch (e: IllegalStateException) { e }
        assertTrue(ex != null)
    }
}
