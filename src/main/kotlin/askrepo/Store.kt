package askrepo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Serializable
data class Manifest(
    val repoPath: String,
    val model: String,
    val embeddingModel: String,
    val dim: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class FileEntry(
    val contentHash: String,
    val chunkIds: List<String>,
)

@Serializable
data class FilesIndex(
    val files: Map<String, FileEntry> = emptyMap(),
)

@Serializable
data class StoredChunk(
    val id: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val language: String,
    val text: String,
)

object Store {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val jsonCompact = Json { ignoreUnknownKeys = true }

    fun repoLocalDir(repoRoot: Path): Path = repoRoot.resolve(Defaults.INDEX_DIR)

    fun namedDir(indexBase: Path, name: String): Path = indexBase.resolve(name)

    fun resolveIndexDir(repoRoot: Path?, name: String?, indexBase: Path): Path {
        if (name != null) return namedDir(indexBase, name)
        if (repoRoot != null) return repoLocalDir(repoRoot)
        error("either --path or --name must be provided")
    }

    fun exists(dir: Path): Boolean {
        return Files.isDirectory(dir) &&
            Files.isRegularFile(dir.resolve(Defaults.MANIFEST_FILE)) &&
            Files.isRegularFile(dir.resolve(Defaults.CHUNKS_FILE)) &&
            Files.isRegularFile(dir.resolve(Defaults.VECTORS_FILE))
    }

    fun listNamedIndexes(indexBase: Path): List<String> {
        if (!Files.isDirectory(indexBase)) return emptyList()
        return Files.list(indexBase).use { stream ->
            stream.filter { Files.isDirectory(it) && exists(it) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
    }

    fun readManifest(dir: Path): Manifest? {
        val f = dir.resolve(Defaults.MANIFEST_FILE)
        if (!Files.isRegularFile(f)) return null
        return json.decodeFromString(Manifest.serializer(), Files.readString(f))
    }

    fun writeManifest(dir: Path, manifest: Manifest) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(Defaults.MANIFEST_FILE), json.encodeToString(Manifest.serializer(), manifest))
    }

    fun readFilesIndex(dir: Path): FilesIndex {
        val f = dir.resolve(Defaults.FILES_FILE)
        if (!Files.isRegularFile(f)) return FilesIndex()
        return json.decodeFromString(FilesIndex.serializer(), Files.readString(f))
    }

    fun writeFilesIndex(dir: Path, index: FilesIndex) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(Defaults.FILES_FILE), json.encodeToString(FilesIndex.serializer(), index))
    }

    fun readChunks(dir: Path): List<StoredChunk> {
        val f = dir.resolve(Defaults.CHUNKS_FILE)
        if (!Files.isRegularFile(f)) return emptyList()
        val out = ArrayList<StoredChunk>()
        Files.newBufferedReader(f).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    out.add(jsonCompact.decodeFromString(StoredChunk.serializer(), line))
                }
                line = r.readLine()
            }
        }
        return out
    }

    fun writeChunks(dir: Path, chunks: List<StoredChunk>) {
        Files.createDirectories(dir)
        Files.newBufferedWriter(
            dir.resolve(Defaults.CHUNKS_FILE),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { w ->
            for (c in chunks) {
                w.write(jsonCompact.encodeToString(StoredChunk.serializer(), c))
                w.newLine()
            }
        }
    }

    fun writeVectors(dir: Path, dim: Int, vectors: List<FloatArray>) {
        require(vectors.all { it.size == dim }) { "vector dimension mismatch" }
        Files.createDirectories(dir)
        val file = dir.resolve(Defaults.VECTORS_FILE)
        val totalBytes = 4 + vectors.size * dim * 4
        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(dim)
        for (v in vectors) for (f in v) buf.putFloat(f)
        Files.write(
            file,
            buf.array(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    fun readVectors(dir: Path): Pair<Int, List<FloatArray>> {
        val file = dir.resolve(Defaults.VECTORS_FILE)
        if (!Files.isRegularFile(file)) return 0 to emptyList()
        val bytes = Files.readAllBytes(file)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dim = buf.int
        require(dim > 0) { "invalid vector dim in ${file}" }
        val remaining = (bytes.size - 4) / 4
        val count = remaining / dim
        val list = ArrayList<FloatArray>(count)
        repeat(count) {
            val arr = FloatArray(dim)
            for (j in 0 until dim) arr[j] = buf.float
            list.add(arr)
        }
        return dim to list
    }
}
