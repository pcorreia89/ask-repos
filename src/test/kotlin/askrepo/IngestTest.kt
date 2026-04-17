package askrepo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestTest {

    @Test
    fun includesKotlinFiles() {
        assertTrue(Ingest.isIncludedFile("src/Main.kt"))
        assertTrue(Ingest.isIncludedFile("build.gradle.kts"))
    }

    @Test
    fun includesPythonFiles() {
        assertTrue(Ingest.isIncludedFile("app.py"))
        assertTrue(Ingest.isIncludedFile("deep/nested/module.py"))
    }

    @Test
    fun includesJavascriptAndTypescript() {
        assertTrue(Ingest.isIncludedFile("index.js"))
        assertTrue(Ingest.isIncludedFile("app.ts"))
        assertTrue(Ingest.isIncludedFile("Component.tsx"))
        assertTrue(Ingest.isIncludedFile("Component.jsx"))
    }

    @Test
    fun includesGoAndRust() {
        assertTrue(Ingest.isIncludedFile("main.go"))
        assertTrue(Ingest.isIncludedFile("lib.rs"))
    }

    @Test
    fun includesMarkdownAndText() {
        assertTrue(Ingest.isIncludedFile("docs/guide.md"))
        assertTrue(Ingest.isIncludedFile("notes.txt"))
        assertTrue(Ingest.isIncludedFile("spec.rst"))
    }

    @Test
    fun includesReadmeAndChangelog() {
        assertTrue(Ingest.isIncludedFile("README"))
        assertTrue(Ingest.isIncludedFile("README.txt"))
        assertTrue(Ingest.isIncludedFile("README.anything"))
        assertTrue(Ingest.isIncludedFile("CHANGELOG"))
        assertTrue(Ingest.isIncludedFile("changelog.old"))
    }

    @Test
    fun excludesBinaryAndUnknownExtensions() {
        assertFalse(Ingest.isIncludedFile("image.png"))
        assertFalse(Ingest.isIncludedFile("archive.zip"))
        assertFalse(Ingest.isIncludedFile("data.bin"))
        assertFalse(Ingest.isIncludedFile("app.exe"))
        assertFalse(Ingest.isIncludedFile("style.css"))
    }

    @Test
    fun excludesFilesWithNoExtensionUnlessSpecialName() {
        assertFalse(Ingest.isIncludedFile("Makefile"))
        assertFalse(Ingest.isIncludedFile("Dockerfile"))
        assertFalse(Ingest.isIncludedFile("LICENSE"))
    }

    @Test
    fun handlesDeepPaths() {
        assertTrue(Ingest.isIncludedFile("a/b/c/d/e/f.kt"))
        assertFalse(Ingest.isIncludedFile("a/b/c/d/e/f.png"))
    }

    @Test
    fun includesJavaAndRuby() {
        assertTrue(Ingest.isIncludedFile("App.java"))
        assertTrue(Ingest.isIncludedFile("script.rb"))
    }

    @Test
    fun includesMdxFiles() {
        assertTrue(Ingest.isIncludedFile("doc.mdx"))
    }
}
