package askrepo

data class Chunk(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val language: String,
    val text: String,
)

object Chunking {
    const val MIN_CHARS = 80
    const val TARGET_CHARS = 1_500
    const val HARD_CAP_CHARS = 3_000
    const val TARGET_LINES = 60
    const val FALLBACK_OVERLAP = 150

    private val MARKDOWN_EXT = setOf("md", "mdx", "rst")
    private val CODE_EXT = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
        "go", "rs", "rb",
    )

    fun detectLanguage(filePath: String): String {
        val lower = filePath.lowercase()
        val name = lower.substringAfterLast('/')
        if (name.startsWith("readme")) return "markdown"
        if (name.startsWith("changelog")) return "markdown"
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            ext in MARKDOWN_EXT -> if (ext == "rst") "rst" else "markdown"
            ext in CODE_EXT -> ext
            else -> "text"
        }
    }

    fun chunk(filePath: String, text: String): List<Chunk> {
        val language = detectLanguage(filePath)
        val raw = when (language) {
            "markdown", "rst" -> chunkMarkdown(filePath, language, text)
            in CODE_EXT -> chunkCode(filePath, language, text)
            else -> chunkFallback(filePath, language, text)
        }
        return raw.flatMap { enforceHardCap(it) }.filter { it.text.length >= MIN_CHARS }
    }

    private fun chunkMarkdown(filePath: String, language: String, text: String): List<Chunk> {
        val lines = text.split('\n')
        val sections = splitByHeaders(lines, maxLevel = 2)
        val out = ArrayList<Chunk>()
        for (sec in sections) {
            val secText = sec.lines.joinToString("\n")
            if (secText.length <= TARGET_CHARS) {
                out.add(mkChunk(filePath, language, sec.startLine, sec.startLine + sec.lines.size - 1, secText))
                continue
            }
            // Section too big — split on H3 headers.
            val subs = splitByHeaders(sec.lines, maxLevel = 3, startLine = sec.startLine)
            val headerPath = sec.headerPath
            for (sub in subs) {
                val subText = buildString {
                    if (headerPath.isNotEmpty()) append(headerPath).append("\n\n")
                    append(sub.lines.joinToString("\n"))
                }
                out.add(mkChunk(filePath, language, sub.startLine, sub.startLine + sub.lines.size - 1, subText))
            }
        }
        return out
    }

    private data class Section(val headerPath: String, val startLine: Int, val lines: List<String>)

    private val headerRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val rstHeaderRegex = Regex("^[=\\-~^\"'`]+$")

    private fun splitByHeaders(
        lines: List<String>,
        maxLevel: Int,
        startLine: Int = 1,
    ): List<Section> {
        val sections = ArrayList<Section>()
        val pathStack = ArrayList<Pair<Int, String>>() // (level, title)
        var current = ArrayList<String>()
        var currentStart = startLine
        var currentHeaderPath = ""

        fun flush(endLine: Int) {
            if (current.isNotEmpty()) {
                sections.add(Section(currentHeaderPath, currentStart, current))
            }
        }

        for ((i, line) in lines.withIndex()) {
            val lineNum = startLine + i
            val headerMatch = headerRegex.matchEntire(line)
            val isRstHeader = i + 1 < lines.size && rstHeaderRegex.matches(lines[i + 1]) &&
                lines[i].isNotBlank() && lines[i + 1].isNotEmpty()
            if (headerMatch != null && headerMatch.groupValues[1].length <= maxLevel) {
                flush(lineNum - 1)
                val level = headerMatch.groupValues[1].length
                val title = headerMatch.groupValues[2].trim()
                while (pathStack.isNotEmpty() && pathStack.last().first >= level) pathStack.removeLast()
                pathStack.add(level to title)
                currentHeaderPath = pathStack.joinToString(" > ") { "#".repeat(it.first) + " " + it.second }
                current = ArrayList()
                current.add(line)
                currentStart = lineNum
            } else {
                current.add(line)
            }
        }
        flush(startLine + lines.size - 1)
        return sections
    }

    private fun chunkCode(filePath: String, language: String, text: String): List<Chunk> {
        val lines = text.split('\n')
        // A "block" is a run of non-blank lines, extended to include trailing blanks.
        data class Block(val start: Int, val end: Int, val text: String)
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            // Skip leading blanks.
            while (i < lines.size && lines[i].isBlank()) i++
            if (i >= lines.size) break
            val start = i + 1
            while (i < lines.size && !lines[i].isBlank()) i++
            val end = i
            blocks.add(Block(start, end, lines.subList(start - 1, end).joinToString("\n")))
        }

        val out = ArrayList<Chunk>()
        var bufStart = -1
        var bufEnd = -1
        val bufText = StringBuilder()
        fun emit() {
            if (bufStart > 0 && bufText.isNotEmpty()) {
                out.add(mkChunk(filePath, language, bufStart, bufEnd, bufText.toString()))
            }
            bufStart = -1
            bufEnd = -1
            bufText.setLength(0)
        }
        for (b in blocks) {
            val bLines = b.end - b.start + 1
            val currentLines = if (bufStart < 0) 0 else (bufEnd - bufStart + 1)
            val wouldOverflow = bufText.length + b.text.length + 1 > TARGET_CHARS ||
                currentLines + bLines > TARGET_LINES
            if (bufStart < 0) {
                bufStart = b.start
                bufEnd = b.end
                bufText.append(b.text)
            } else if (wouldOverflow) {
                emit()
                bufStart = b.start
                bufEnd = b.end
                bufText.append(b.text)
            } else {
                bufText.append("\n\n").append(b.text)
                bufEnd = b.end
            }
        }
        emit()
        return out
    }

    private fun chunkFallback(filePath: String, language: String, text: String): List<Chunk> {
        if (text.length <= TARGET_CHARS) {
            val lineCount = text.count { it == '\n' } + 1
            return listOf(mkChunk(filePath, language, 1, lineCount, text))
        }
        val out = ArrayList<Chunk>()
        val step = TARGET_CHARS - FALLBACK_OVERLAP
        var offset = 0
        while (offset < text.length) {
            val end = (offset + TARGET_CHARS).coerceAtMost(text.length)
            val slice = text.substring(offset, end)
            val startLine = text.substring(0, offset).count { it == '\n' } + 1
            val endLine = startLine + slice.count { it == '\n' }
            out.add(mkChunk(filePath, language, startLine, endLine, slice))
            if (end >= text.length) break
            offset += step
        }
        return out
    }

    private fun mkChunk(filePath: String, language: String, startLine: Int, endLine: Int, text: String): Chunk {
        val trimmed = text.trimEnd()
        val realEnd = if (trimmed.length < text.length && trimmed.isNotEmpty()) {
            startLine + trimmed.count { it == '\n' }
        } else endLine
        return Chunk(filePath, startLine, realEnd.coerceAtLeast(startLine), language, trimmed)
    }

    private fun enforceHardCap(chunk: Chunk): List<Chunk> {
        if (chunk.text.length <= HARD_CAP_CHARS) return listOf(chunk)
        val out = ArrayList<Chunk>()
        var offset = 0
        while (offset < chunk.text.length) {
            val end = (offset + HARD_CAP_CHARS).coerceAtMost(chunk.text.length)
            val slice = chunk.text.substring(offset, end)
            val startLine = chunk.startLine + chunk.text.substring(0, offset).count { it == '\n' }
            val endLine = startLine + slice.count { it == '\n' }
            out.add(Chunk(chunk.filePath, startLine, endLine, chunk.language, slice))
            offset = end
        }
        return out
    }
}
