package askrepo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerTest {

    @Test
    fun answerReturnsNoIndexMessageWhenMissing() {
        val dir = java.nio.file.Files.createTempDirectory("answer-test")
        val config = Config(
            anthropicApiKey = "test",
            voyageApiKey = "test",
            anthropicModel = "model",
            voyageModel = "model",
            topK = 5,
            maxTokens = 512,
            indexBase = dir,
            adminUser = "admin",
            adminPassword = "admin",
            adminPort = 3000,
            slackBotToken = null,
            slackAppToken = null,
            bitbucketToken = null,
            githubToken = null,
        )
        val result = Answer.answer(config, dir, "what does this do?")
        assertTrue(result.text.contains("No index found"))
        assertEquals(emptyList(), result.sources)
    }

    @Test
    fun systemPromptContainsKeyInstructions() {
        assertTrue(Answer.SYSTEM_PROMPT.contains("ONLY the provided context"))
        assertTrue(Answer.SYSTEM_PROMPT.contains("I couldn't find this"))
        assertTrue(Answer.SYSTEM_PROMPT.contains("plain language"))
    }
}
