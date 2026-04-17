package askrepo

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class SyncJob(
    val name: String,
    val messages: CopyOnWriteArrayList<String> = CopyOnWriteArrayList(),
    @Volatile var done: Boolean = false,
    @Volatile var error: String? = null,
)

object AdminServer {

    private val syncJobs = ConcurrentHashMap<String, SyncJob>()

    fun start(config: Config) {
        val server = embeddedServer(Netty, port = config.adminPort, host = "0.0.0.0") {
            install(Authentication) {
                basic("admin") {
                    realm = "ask-repos Admin"
                    validate { credentials ->
                        if (credentials.name == config.adminUser && credentials.password == config.adminPassword) {
                            UserIdPrincipal(credentials.name)
                        } else null
                    }
                }
            }

            routing {
                get("/admin/health") {
                    call.respondText("ok")
                }

                authenticate("admin") {
                    get("/admin") {
                        val registry = RepoManager.loadRegistry(config)
                        val indexed = Store.listNamedIndexes(config.indexBase)
                        call.respondPage("Dashboard") {
                            h1 { +"ask-repos Admin" }
                            p { +"Indexed repos: ${indexed.size} | Registered repos: ${registry.repos.size}" }

                            h2 { +"Repos" }
                            if (registry.repos.isEmpty()) {
                                p { +"No repos configured yet. "; a(href = "/admin/repos/add") { +"Add one." } }
                            } else {
                                table {
                                    thead {
                                        tr {
                                            th { +"Name" }
                                            th { +"Provider" }
                                            th { +"Workspace / Repo" }
                                            th { +"Branch" }
                                            th { +"Channels" }
                                            th { +"Indexed" }
                                            th { +"Last Updated" }
                                            th { +"Actions" }
                                        }
                                    }
                                    tbody {
                                        for (repo in registry.repos) {
                                            val isIndexed = repo.name in indexed
                                            val manifest = if (isIndexed) Store.readManifest(Store.namedDir(config.indexBase, repo.name)) else null
                                            tr {
                                                td { +repo.name }
                                                td { +repo.provider }
                                                td { +"${repo.workspace}/${repo.repo}" }
                                                td { +repo.branch }
                                                td { +(repo.channels.joinToString(", ").ifEmpty { "all" }) }
                                                td { +(if (isIndexed) "yes" else "no") }
                                                td { +(manifest?.updatedAt ?: "-") }
                                                td {
                                                    style = "white-space:nowrap"
                                                    a(href = "/admin/repos/${repo.name}/edit", classes = "icon-btn") {
                                                        title = "Edit"
                                                        +"\u270E"
                                                    }
                                                    form(action = "/admin/repos/${repo.name}/sync", method = FormMethod.post) {
                                                        style = "display:inline"
                                                        button(type = ButtonType.submit, classes = "icon-btn") {
                                                            title = "Sync"
                                                            +"\u21BB"
                                                        }
                                                    }
                                                    form(action = "/admin/repos/${repo.name}/delete", method = FormMethod.post) {
                                                        style = "display:inline"
                                                        button(type = ButtonType.submit, classes = "icon-btn danger") {
                                                            title = "Delete"
                                                            attributes["onclick"] = "return confirm('Delete ${repo.name}?')"
                                                            +"\u2716"
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            br()
                            a(href = "/admin/repos/add") { +"+ Add repo" }
                        }
                    }

                    get("/admin/repos/add") {
                        call.respondRepoForm("Add Repo", RepoEntry(name = "", provider = "github"), isNew = true)
                    }

                    post("/admin/repos/add") {
                        val params = call.receiveParameters()
                        val entry = entryFromParams(params)
                        if (entry.name.isBlank()) {
                            call.respondPage("Error") {
                                h1 { +"Name is required" }
                                a(href = "/admin/repos/add") { +"Back" }
                            }
                            return@post
                        }
                        val registry = RepoManager.loadRegistry(config)
                        if (registry.repos.any { it.name == entry.name }) {
                            call.respondPage("Error") {
                                h1 { +"Repo '${entry.name}' already exists" }
                                a(href = "/admin/repos/add") { +"Back" }
                            }
                            return@post
                        }
                        try {
                            RepoManager.saveRegistry(config, registry.copy(repos = registry.repos + entry))
                            call.respondRedirect("/admin?msg=added")
                        } catch (e: Exception) {
                            call.respondRedirect("/admin?error=${encodeParam(e.message ?: "Failed to add repo")}")
                        }
                    }

                    get("/admin/repos/{name}/edit") {
                        val name = call.parameters["name"]!!
                        val registry = RepoManager.loadRegistry(config)
                        val entry = registry.repos.find { it.name == name }
                        if (entry == null) {
                            call.respondPage("Not Found") { h1 { +"Repo '$name' not found" } }
                            return@get
                        }
                        call.respondRepoForm("Edit Repo", entry, isNew = false)
                    }

                    post("/admin/repos/{name}/edit") {
                        val name = call.parameters["name"]!!
                        try {
                            val params = call.receiveParameters()
                            val updated = entryFromParams(params).copy(name = name)
                            val registry = RepoManager.loadRegistry(config)
                            val newRepos = registry.repos.map { if (it.name == name) updated else it }
                            RepoManager.saveRegistry(config, registry.copy(repos = newRepos))
                            call.respondRedirect("/admin?msg=updated")
                        } catch (e: Exception) {
                            call.respondRedirect("/admin?error=${encodeParam(e.message ?: "Failed to update '$name'")}")
                        }
                    }

                    post("/admin/repos/{name}/delete") {
                        val name = call.parameters["name"]!!
                        try {
                            val registry = RepoManager.loadRegistry(config)
                            val newRepos = registry.repos.filter { it.name != name }
                            RepoManager.saveRegistry(config, registry.copy(repos = newRepos))
                            call.respondRedirect("/admin?msg=deleted")
                        } catch (e: Exception) {
                            call.respondRedirect("/admin?error=${encodeParam(e.message ?: "Failed to delete '$name'")}")
                        }
                    }

                    post("/admin/repos/{name}/sync") {
                        val name = call.parameters["name"]!!
                        val existing = syncJobs[name]
                        if (existing != null && !existing.done) {
                            call.respondRedirect("/admin/repos/$name/sync-progress")
                            return@post
                        }
                        val job = SyncJob(name)
                        syncJobs[name] = job
                        Thread {
                            try {
                                RepoManager.sync(config, name) { msg -> job.messages.add(msg) }
                            } catch (e: Exception) {
                                job.error = e.message ?: "Unknown error"
                                job.messages.add("Error: ${job.error}")
                            } finally {
                                job.done = true
                            }
                        }.start()
                        call.respondRedirect("/admin/repos/$name/sync-progress")
                    }

                    get("/admin/repos/{name}/sync-progress") {
                        val name = call.parameters["name"]!!
                        call.respondPage("Syncing $name") {
                            h1 { +"Syncing $name" }
                            div {
                                id = "log"
                                style = "background:#1e293b;color:#e2e8f0;padding:16px;border-radius:8px;font-family:monospace;font-size:13px;min-height:200px;max-height:400px;overflow-y:auto;white-space:pre-wrap"
                            }
                            p {
                                id = "status"
                                style = "font-weight:600;margin-top:12px"
                                +"Connecting..."
                            }
                            script {
                                unsafe {
                                    raw("""
const log = document.getElementById('log');
const status = document.getElementById('status');
const es = new EventSource('/admin/repos/$name/sync-events');
es.onmessage = function(e) {
  log.textContent += e.data + '\n';
  log.scrollTop = log.scrollHeight;
};
es.addEventListener('done', function(e) {
  status.textContent = e.data;
  status.style.color = '#16a34a';
  es.close();
});
es.addEventListener('error-msg', function(e) {
  status.textContent = e.data;
  status.style.color = '#dc2626';
  es.close();
});
es.onerror = function() {
  if (es.readyState === EventSource.CLOSED) return;
  status.textContent = 'Connection lost';
  status.style.color = '#dc2626';
  es.close();
};
""".trimIndent())
                                }
                            }
                            br()
                            a(href = "/admin") { +"\u2190 Back to Dashboard" }
                        }
                    }

                    get("/admin/repos/{name}/sync-events") {
                        val name = call.parameters["name"]!!
                        val job = syncJobs[name]
                        call.response.header(HttpHeaders.ContentType, "text/event-stream")
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header(HttpHeaders.Connection, "keep-alive")
                        call.respondTextWriter {
                            if (job == null) {
                                write("event: error-msg\ndata: No sync job found for '$name'\n\n")
                                flush()
                                return@respondTextWriter
                            }
                            var sent = 0
                            while (!job.done || sent < job.messages.size) {
                                while (sent < job.messages.size) {
                                    write("data: ${job.messages[sent]}\n\n")
                                    flush()
                                    sent++
                                }
                                if (!job.done) Thread.sleep(200)
                            }
                            if (job.error != null) {
                                write("event: error-msg\ndata: Sync failed: ${job.error}\n\n")
                            } else {
                                write("event: done\ndata: Sync completed successfully\n\n")
                            }
                            flush()
                            syncJobs.remove(name)
                        }
                    }
                }
            }
        }
        server.start(wait = false)
        System.err.println("admin UI running on http://0.0.0.0:${config.adminPort}/admin")
    }

    private fun encodeParam(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun entryFromParams(params: Parameters): RepoEntry {
        val channels = (params["channels"] ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return RepoEntry(
            name = (params["name"] ?: "").trim(),
            provider = (params["provider"] ?: "github").trim(),
            workspace = (params["workspace"] ?: "").trim(),
            repo = (params["repo"] ?: "").trim(),
            branch = (params["branch"] ?: "main").trim().ifEmpty { "main" },
            channels = channels,
        )
    }

    private suspend fun ApplicationCall.respondPage(pageTitle: String, block: BODY.() -> Unit) {
        respondHtml {
            head {
                title { +"ask-repos — $pageTitle" }
                style {
                    unsafe {
                        raw(CSS)
                    }
                }
            }
            body {
                nav {
                    a(href = "/admin") { +"Dashboard" }
                    a(href = "/admin/repos/add") { +"Add Repo" }
                }
                val msg = request.queryParameters["msg"]
                val error = request.queryParameters["error"]
                if (msg != null) {
                    div("flash success") { +msg }
                }
                if (error != null) {
                    div("flash error") { +error }
                }
                block()
            }
        }
    }

    private suspend fun ApplicationCall.respondRepoForm(title: String, entry: RepoEntry, isNew: Boolean) {
        val action = if (isNew) "/admin/repos/add" else "/admin/repos/${entry.name}/edit"
        respondPage(title) {
            h1 { +title }
            form(action = action, method = FormMethod.post) {
                if (isNew) {
                    label { +"Name"; input(type = InputType.text, name = "name") { value = entry.name; required = true } }
                } else {
                    p { +"Name: "; strong { +entry.name } }
                }
                label {
                    +"Provider"
                    select {
                        name = "provider"
                        option { value = "github"; if (entry.provider == "github") selected = true; +"GitHub" }
                        option { value = "bitbucket"; if (entry.provider == "bitbucket") selected = true; +"Bitbucket" }
                    }
                }
                label { +"Workspace / Org"; input(type = InputType.text, name = "workspace") { value = entry.workspace; required = true } }
                label { +"Repository"; input(type = InputType.text, name = "repo") { value = entry.repo; required = true } }
                label { +"Branch"; input(type = InputType.text, name = "branch") { value = entry.branch; placeholder = "main" } }
                label { +"Channels (comma-separated IDs)"; input(type = InputType.text, name = "channels") { value = entry.channels.joinToString(", ") } }
                br()
                submitInput { value = if (isNew) "Add Repo" else "Save Changes" }
                +" "
                a(href = "/admin") { +"Cancel" }
            }
        }
    }

    private const val CSS = """
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 960px; margin: 0 auto; padding: 20px; color: #333; }
nav { display: flex; gap: 20px; padding: 12px 0; border-bottom: 2px solid #2563eb; margin-bottom: 24px; }
nav a { text-decoration: none; color: #2563eb; font-weight: 600; }
nav a:hover { text-decoration: underline; }
h1 { color: #1e293b; }
h2 { color: #334155; margin-top: 28px; }
table { width: 100%; border-collapse: collapse; margin-top: 12px; }
th, td { border: 1px solid #e2e8f0; padding: 8px 12px; text-align: left; }
th { background: #f1f5f9; font-weight: 600; }
tr:nth-child(even) { background: #f8fafc; }
form label { display: block; margin: 12px 0 4px; font-weight: 600; }
form input[type=text], form select { display: block; width: 100%; padding: 8px; border: 1px solid #cbd5e1; border-radius: 4px; margin-top: 4px; box-sizing: border-box; }
form input[type=submit] { background: #2563eb; color: white; border: none; padding: 8px 20px; border-radius: 4px; cursor: pointer; font-size: 14px; }
form input[type=submit]:hover { background: #1d4ed8; }
a { color: #2563eb; }
.icon-btn { background: none; border: 1px solid #cbd5e1; border-radius: 4px; cursor: pointer; font-size: 16px; padding: 4px 8px; margin-right: 4px; color: #2563eb; text-decoration: none; display: inline-block; line-height: 1; }
.icon-btn:hover { background: #eff6ff; border-color: #2563eb; }
.icon-btn.danger { color: #dc2626; }
.icon-btn.danger:hover { background: #fef2f2; border-color: #dc2626; }
.flash { padding: 8px 16px; border-radius: 4px; margin-bottom: 16px; }
.flash.success { background: #dcfce7; border: 1px solid #86efac; }
.flash.error { background: #fef2f2; border: 1px solid #fca5a5; color: #dc2626; }
"""
}
