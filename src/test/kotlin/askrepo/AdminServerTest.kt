package askrepo

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*

class AdminServerTest {

    private fun testConfig(base: java.nio.file.Path): Config = Config(
        anthropicApiKey = "test-key",
        voyageApiKey = "test-key",
        anthropicModel = Defaults.ANTHROPIC_MODEL,
        voyageModel = Defaults.VOYAGE_MODEL,
        topK = Defaults.TOP_K,
        maxTokens = Defaults.MAX_TOKENS,
        indexBase = base.resolve("indexes"),
        adminUser = "admin",
        adminPassword = "secret",
        adminPort = 0,
        slackBotToken = null,
        slackAppToken = null,
        bitbucketToken = null,
        githubToken = null,
        syncIntervalMinutes = null,
        webhookSecret = "test-secret",
    )

    private fun adminTest(block: suspend ApplicationTestBuilder.(Config) -> Unit) {
        val base = Files.createTempDirectory("admin-test")
        val config = testConfig(base)
        Files.createDirectories(config.indexBase)
        testApplication {
            application { with(AdminServer) { configure(config) } }
            block(config)
        }
        base.toFile().deleteRecursively()
    }

    private suspend fun ApplicationTestBuilder.authGet(url: String) =
        client.get(url) { basicAuth("admin", "secret") }

    private suspend fun ApplicationTestBuilder.authPost(url: String, formData: String = "") =
        client.post(url) {
            basicAuth("admin", "secret")
            if (formData.isNotEmpty()) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formData)
            }
        }

    @Test
    fun healthEndpointReturnsOk() = adminTest { _ ->
        val response = client.get("/admin/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun dashboardRequiresAuth() = adminTest { _ ->
        val response = client.get("/admin")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun dashboardShowsEmptyState() = adminTest { _ ->
        val response = authGet("/admin")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "No repos configured yet")
    }

    @Test
    fun addRepoShowsForm() = adminTest { _ ->
        val response = authGet("/admin/repos/add")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "Add Repo")
    }

    @Test
    fun addRepoCreatesEntry() = adminTest { config ->
        val response = authPost("/admin/repos/add", "name=test-repo&provider=github&workspace=org&repo=myrepo&branch=main&channels=")
        assertEquals(HttpStatusCode.Found, response.status)
        val registry = RepoManager.loadRegistry(config)
        assertEquals(1, registry.repos.size)
        assertEquals("test-repo", registry.repos[0].name)
        assertEquals("github", registry.repos[0].provider)
    }

    @Test
    fun addRepoRejectsBlankName() = adminTest { _ ->
        val response = authPost("/admin/repos/add", "name=&provider=github&workspace=org&repo=myrepo&branch=main&channels=")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "Name is required")
    }

    @Test
    fun addRepoPreventseDuplicates() = adminTest { config ->
        val entry = RepoEntry("dup", "github", "org", "repo")
        RepoManager.saveRegistry(config, RepoRegistry(listOf(entry)))
        val response = authPost("/admin/repos/add", "name=dup&provider=github&workspace=org&repo=repo&branch=main&channels=")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "already exists")
    }

    @Test
    fun editRepoUpdatesEntry() = adminTest { config ->
        val entry = RepoEntry("my-repo", "github", "org", "old-repo", "main")
        RepoManager.saveRegistry(config, RepoRegistry(listOf(entry)))
        val response = authPost("/admin/repos/my-repo/edit", "provider=bitbucket&workspace=ws&repo=new-repo&branch=develop&channels=C1,C2")
        assertEquals(HttpStatusCode.Found, response.status)
        val updated = RepoManager.loadRegistry(config).repos[0]
        assertEquals("my-repo", updated.name)
        assertEquals("bitbucket", updated.provider)
        assertEquals("new-repo", updated.repo)
        assertEquals("develop", updated.branch)
        assertEquals(listOf("C1", "C2"), updated.channels)
    }

    @Test
    fun editRepoNotFoundShowsError() = adminTest { _ ->
        val response = authGet("/admin/repos/nonexistent/edit")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "not found")
    }

    @Test
    fun deleteRepoRemovesEntry() = adminTest { config ->
        val entries = listOf(
            RepoEntry("keep", "github", "org", "a"),
            RepoEntry("delete-me", "github", "org", "b"),
        )
        RepoManager.saveRegistry(config, RepoRegistry(entries))
        val response = authPost("/admin/repos/delete-me/delete")
        assertEquals(HttpStatusCode.Found, response.status)
        val registry = RepoManager.loadRegistry(config)
        assertEquals(1, registry.repos.size)
        assertEquals("keep", registry.repos[0].name)
    }

    @Test
    fun dashboardShowsReposTable() = adminTest { config ->
        val entries = listOf(RepoEntry("my-repo", "github", "acme", "backend", "main"))
        RepoManager.saveRegistry(config, RepoRegistry(entries))
        val response = authGet("/admin")
        val body = response.bodyAsText()
        assertContains(body, "my-repo")
        assertContains(body, "acme/backend")
    }

    @Test
    fun webhookRejectsWithoutSecret() = adminTest { config ->
        val entries = listOf(RepoEntry("my-repo", "github", "acme", "backend"))
        RepoManager.saveRegistry(config, RepoRegistry(entries))
        val response = client.post("/webhook/my-repo")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun webhookRejectsWrongSecret() = adminTest { config ->
        val entries = listOf(RepoEntry("my-repo", "github", "acme", "backend"))
        RepoManager.saveRegistry(config, RepoRegistry(entries))
        val response = client.post("/webhook/my-repo?secret=wrong")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun webhookReturnsNotFoundForUnknownRepo() = adminTest { _ ->
        val response = client.post("/webhook/nonexistent?secret=test-secret")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun webhookTriggersSync() = adminTest { config ->
        val entries = listOf(RepoEntry("my-repo", "github", "acme", "backend"))
        RepoManager.saveRegistry(config, RepoRegistry(entries))
        val response = client.post("/webhook/my-repo?secret=test-secret")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "sync started")
    }
}
