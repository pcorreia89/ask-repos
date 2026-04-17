package askrepo

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.methods.MethodsClient
import com.slack.api.model.event.AppMentionEvent
import java.util.concurrent.Executors

object SlackBot {

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ask-worker").also { it.isDaemon = true }
    }

    private val threadHistory = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    fun start(config: Config) {
        val appConfig = AppConfig.builder()
            .singleTeamBotToken(config.slackBotToken)
            .build()
        val app = App(appConfig)

        app.event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event
            val channel = event.channel
            val threadTs = event.threadTs ?: event.ts
            val rawText = event.text ?: ""
            val question = rawText.replace(Regex("<@[A-Z0-9]+>\\s*"), "").trim()

            val response = ctx.ack()

            executor.submit {
                try {
                    processQuestion(config, question, channel, threadTs, ctx.client(), config.slackBotToken!!)
                } catch (t: Throwable) {
                    System.err.println("error processing question: ${t.message}")
                    postMessage(ctx.client(), config.slackBotToken!!, channel, threadTs,
                        "Something went wrong: ${t.message?.take(200)}")
                }
            }

            response
        }

        val socketModeApp = SocketModeApp(config.slackAppToken, app)
        System.err.println("ask-repos slack bot starting (socket mode)...")
        val names = Store.listNamedIndexes(config.indexBase)
        System.err.println("serving ${names.size} indexed repo(s): ${names.joinToString(", ")}")
        socketModeApp.start()
    }

    private fun processQuestion(
        config: Config,
        question: String,
        channel: String,
        threadTs: String,
        client: MethodsClient,
        token: String,
    ) {
        val allowedRepos = RepoManager.reposForChannel(config, channel)
            .filter { Store.exists(Store.namedDir(config.indexBase, it)) }

        if (question.isBlank() || question.equals("help", ignoreCase = true)) {
            val repoList = if (allowedRepos.isEmpty()) {
                "No repos available in this channel."
            } else {
                allowedRepos.joinToString("\n") { "  • `$it`" }
            }
            postMessage(client, token, channel, threadTs,
                "*ask-repos* — ask questions about your codebase.\n\n" +
                    "Usage: `@ask-repos <question>` — the best repo is auto-detected.\n" +
                    "To target a specific repo: `@ask-repos <question> in <repo-name>`\n\n" +
                    "Available repos:\n$repoList")
            return
        }

        if (question.equals("list", ignoreCase = true)) {
            val repoList = if (allowedRepos.isEmpty()) {
                "No repos available in this channel."
            } else {
                allowedRepos.joinToString("\n") { "  • `$it`" }
            }
            postMessage(client, token, channel, threadTs, "Available repos:\n$repoList")
            return
        }

        if (question.startsWith("sync", ignoreCase = true)) {
            val arg = question.removePrefix("sync").trim().takeIf { it.isNotEmpty() }
            val targets = if (arg != null) {
                if (arg !in allowedRepos) {
                    postMessage(client, token, channel, threadTs,
                        "Repo `$arg` is not available in this channel.\n" +
                            "Available: ${allowedRepos.joinToString(", ") { "`$it`" }}")
                    return
                }
                listOf(arg)
            } else {
                allowedRepos
            }
            if (targets.isEmpty()) {
                postMessage(client, token, channel, threadTs, "No repos available in this channel to sync.")
                return
            }
            for (repo in targets) {
                postMessage(client, token, channel, threadTs, "Syncing `$repo`...")
                try {
                    RepoManager.sync(config, repo)
                    postMessage(client, token, channel, threadTs, "Sync complete for `$repo`.")
                } catch (e: Exception) {
                    postMessage(client, token, channel, threadTs, "Sync failed for `$repo`: ${e.message?.take(200)}")
                }
            }
            return
        }

        val (actualQuestion, repoName) = parseRepoFromQuestion(question, allowedRepos)

        if (repoName == null) {
            if (allowedRepos.isEmpty()) {
                postMessage(client, token, channel, threadTs,
                    "No repos available in this channel. Check repos.json channel config.")
                return
            }
            if (allowedRepos.size == 1) {
                handleQuestion(config, allowedRepos.first(), actualQuestion, channel, threadTs, client, token)
                return
            }
            val best = detectRepo(config, actualQuestion, allowedRepos)
            if (best != null) {
                handleQuestion(config, best, actualQuestion, channel, threadTs, client, token)
                return
            }
            postMessage(client, token, channel, threadTs,
                "Multiple repos available and I couldn't determine which one fits best. " +
                    "Please specify: `@ask-repos <question> in <repo-name>`\n\n" +
                    "Available: ${allowedRepos.joinToString(", ") { "`$it`" }}")
            return
        }

        if (repoName !in allowedRepos) {
            postMessage(client, token, channel, threadTs,
                "Repo `$repoName` is not available in this channel.\n" +
                    "Available: ${allowedRepos.joinToString(", ") { "`$it`" }}")
            return
        }

        handleQuestion(config, repoName, actualQuestion, channel, threadTs, client, token)
    }

    private data class ParsedQuestion(val question: String, val repoName: String?)

    private fun parseRepoFromQuestion(text: String, allowedRepos: List<String>): ParsedQuestion {
        val inMatch = Regex("\\s+in\\s+(\\S+)\\s*$", RegexOption.IGNORE_CASE).find(text)
        if (inMatch != null) {
            val candidate = inMatch.groupValues[1]
            if (candidate in allowedRepos) {
                val question = text.substring(0, inMatch.range.first).trim()
                return ParsedQuestion(question, candidate)
            }
        }
        return ParsedQuestion(text, null)
    }

    private fun detectRepo(config: Config, question: String, repos: List<String>): String? {
        val embedClient = EmbeddingsClient(config.voyageApiKey, config.voyageModel)
        val queryVec = embedClient.embed(listOf(question), EmbeddingsClient.InputType.QUERY).first()
        var bestRepo: String? = null
        var bestScore = 0f
        for (name in repos) {
            val indexDir = Store.namedDir(config.indexBase, name)
            if (!Store.exists(indexDir)) continue
            val chunks = Store.readChunks(indexDir)
            val (_, vectors) = Store.readVectors(indexDir)
            if (chunks.isEmpty() || vectors.isEmpty()) continue
            val top = Retrieve.topK(queryVec, chunks, vectors, 1)
            if (top.isNotEmpty() && top[0].score > bestScore) {
                bestScore = top[0].score
                bestRepo = name
            }
        }
        return bestRepo
    }

    private fun handleQuestion(
        config: Config,
        repoName: String,
        question: String,
        channel: String,
        threadTs: String,
        client: MethodsClient,
        token: String,
    ) {
        val indexDir = Store.namedDir(config.indexBase, repoName)
        if (!Store.exists(indexDir)) {
            postMessage(client, token, channel, threadTs,
                "Index `$repoName` not found. Run `ask-repos ingest --path <repo> --name $repoName` first.")
            return
        }

        val thinkingTs = postMessage(client, token, channel, threadTs, ":mag: Searching `$repoName`...")

        val history = threadHistory[threadTs]
        val augmentedQuestion = if (!history.isNullOrEmpty()) {
            val contextBlock = history.joinToString("\n---\n") { (q, a) ->
                "Q: $q\nA: ${a.take(500)}"
            }
            "Previous Q&A in this thread:\n$contextBlock\n---\n$question"
        } else {
            question
        }

        val result = Answer.answer(config, indexDir, augmentedQuestion)

        val entries = threadHistory.getOrPut(threadTs) { mutableListOf() }
        synchronized(entries) {
            entries.add(question to result.text)
            if (entries.size > 5) entries.removeAt(0)
        }
        if (threadHistory.size > 200) {
            val keysToRemove = threadHistory.keys.take(threadHistory.size / 2)
            keysToRemove.forEach { threadHistory.remove(it) }
        }

        val sources = if (result.sources.isNotEmpty()) {
            "\n\n*Sources:*\n" + result.sources.joinToString("\n") { "  • `$it`" }
        } else ""
        val fullText = result.text + sources

        if (thinkingTs != null) {
            client.chatUpdate { it.token(token).channel(channel).ts(thinkingTs).text(fullText) }
        } else {
            postMessage(client, token, channel, threadTs, fullText)
        }
    }

    private fun postMessage(
        client: MethodsClient,
        token: String,
        channel: String,
        threadTs: String,
        text: String,
    ): String? {
        val resp = client.chatPostMessage {
            it.token(token).channel(channel).threadTs(threadTs).text(text)
        }
        return if (resp.isOk) resp.ts else null
    }
}
