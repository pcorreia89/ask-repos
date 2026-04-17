package askrepo

import java.nio.file.Files
import java.nio.file.Path

object Defaults {
    const val ANTHROPIC_MODEL = "claude-haiku-4-5"
    const val VOYAGE_MODEL = "voyage-code-3"
    const val TOP_K = 12
    const val MAX_TOKENS = 1024

    const val ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages"
    const val VOYAGE_ENDPOINT = "https://api.voyageai.com/v1/embeddings"
    const val ANTHROPIC_VERSION = "2023-06-01"

    const val INDEX_DIR = ".ask-repos"
    const val MANIFEST_FILE = "manifest.json"
    const val FILES_FILE = "files.json"
    const val CHUNKS_FILE = "chunks.jsonl"
    const val VECTORS_FILE = "vectors.bin"

    const val MAX_FILE_BYTES = 1_000_000L
    const val EMBED_BATCH_SIZE = 64
    const val HTTP_RETRIES = 3

    val INCLUDED_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
        "go", "rs", "rb", "md", "mdx", "txt", "rst",
    )

    val ALWAYS_INCLUDE_NAMES_PREFIX = listOf("README", "CHANGELOG")

    val BUILTIN_IGNORES = listOf(
        ".git", "node_modules", ".venv", "venv", "dist", "build",
        "target", ".gradle", ".idea", ".ask-repos", "out",
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
        "Cargo.lock", "Gemfile.lock", "poetry.lock", "go.sum",
    )
}

data class Config(
    val anthropicApiKey: String,
    val voyageApiKey: String,
    val anthropicModel: String,
    val voyageModel: String,
    val topK: Int,
    val maxTokens: Int,
    val indexBase: Path,
    val adminUser: String,
    val adminPassword: String,
    val adminPort: Int,
    val slackBotToken: String?,
    val slackAppToken: String?,
    val bitbucketToken: String?,
    val githubToken: String?,
    val syncIntervalMinutes: Int?,
    val webhookSecret: String?,
) {
    companion object {
        fun load(workingDir: Path): Config {
            val env = HashMap<String, String>()
            env.putAll(System.getenv())
            val dotenv = workingDir.resolve(".env")
            if (Files.isRegularFile(dotenv)) {
                for ((k, v) in parseDotenv(Files.readString(dotenv))) {
                    env.putIfAbsent(k, v)
                }
            }

            val anthropic = env["ANTHROPIC_API_KEY"].orEmpty().trim()
            val voyage = env["VOYAGE_API_KEY"].orEmpty().trim()
            if (anthropic.isEmpty() || voyage.isEmpty()) {
                System.err.println(
                    "error: ANTHROPIC_API_KEY and VOYAGE_API_KEY must be set " +
                        "(in the environment or in ./.env). See .env.example."
                )
                kotlin.system.exitProcess(2)
            }

            val defaultBase = Path.of(System.getProperty("user.home"), ".ask-repos", "indexes")
            return Config(
                adminUser = env["ADMIN_USER"]?.takeIf { it.isNotBlank() } ?: "admin",
                adminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() } ?: "admin",
                adminPort = env["ADMIN_PORT"]?.toIntOrNull() ?: 3000,
                anthropicApiKey = anthropic,
                voyageApiKey = voyage,
                anthropicModel = env["ANTHROPIC_MODEL"]?.takeIf { it.isNotBlank() }
                    ?: Defaults.ANTHROPIC_MODEL,
                voyageModel = env["VOYAGE_MODEL"]?.takeIf { it.isNotBlank() }
                    ?: Defaults.VOYAGE_MODEL,
                topK = env["ASK_REPOS_TOP_K"]?.toIntOrNull() ?: Defaults.TOP_K,
                maxTokens = env["ASK_REPOS_MAX_TOKENS"]?.toIntOrNull() ?: Defaults.MAX_TOKENS,
                indexBase = Path.of(env["ASK_REPOS_INDEX_BASE"] ?: defaultBase.toString()),
                slackBotToken = env["SLACK_BOT_TOKEN"]?.takeIf { it.isNotBlank() },
                slackAppToken = env["SLACK_APP_TOKEN"]?.takeIf { it.isNotBlank() },
                bitbucketToken = env["BITBUCKET_TOKEN"]?.takeIf { it.isNotBlank() },
                githubToken = env["GITHUB_TOKEN"]?.takeIf { it.isNotBlank() },
                syncIntervalMinutes = env["SYNC_INTERVAL_MINUTES"]?.toIntOrNull(),
                webhookSecret = env["WEBHOOK_SECRET"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

internal fun parseDotenv(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim()
        var value = line.substring(eq + 1).trim()
        if (value.length >= 2 &&
            ((value.startsWith('"') && value.endsWith('"')) ||
                (value.startsWith('\'') && value.endsWith('\'')))
        ) {
            value = value.substring(1, value.length - 1)
        }
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}
