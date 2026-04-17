package askrepo

import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal .gitignore matcher.
 *
 * Supports:
 *   - `#` comments and blank lines
 *   - trailing `/` marking directory-only patterns
 *   - leading `/` anchoring to the ignore file's directory
 *   - `**` matching zero or more path segments
 *   - `*` matching any run of characters except `/`
 *   - `?` matching any single non-`/` character
 *   - negation via leading `!`
 *
 * Deliberately unsupported (documented limitations):
 *   - character classes `[abc]`
 *   - nested .gitignore files (we only load the one at repo root;
 *     built-in ignores cover .git etc. anywhere in the tree)
 *   - `\` escaping of special characters
 *
 * Paths fed to match() are repo-relative and use `/` separators.
 */
class Gitignore private constructor(private val rules: List<Rule>) {

    private data class Rule(
        val regex: Regex,
        val negate: Boolean,
        val dirOnly: Boolean,
    )

    fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean {
        val normalized = relativePath.trim('/')
        if (normalized.isEmpty()) return false
        var ignored = false
        for (rule in rules) {
            if (matchesRule(rule, normalized, isDirectory)) {
                ignored = !rule.negate
            }
        }
        return ignored
    }

    private fun matchesRule(rule: Rule, path: String, isDirectory: Boolean): Boolean {
        // A dir-only rule should still exclude files under a matched directory,
        // even though those files are not themselves directories. Check each
        // ancestor directory in turn.
        if (rule.dirOnly) {
            var idx = path.indexOf('/')
            while (idx >= 0) {
                val ancestor = path.substring(0, idx)
                if (rule.regex.matches(ancestor)) return true
                idx = path.indexOf('/', idx + 1)
            }
            return isDirectory && rule.regex.matches(path)
        }
        return rule.regex.matches(path)
    }

    companion object {
        fun empty(): Gitignore = Gitignore(emptyList())

        fun parse(text: String): Gitignore {
            val rules = ArrayList<Rule>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trimEnd()
                if (line.isEmpty() || line.startsWith("#")) continue

                var pattern = line
                var negate = false
                if (pattern.startsWith("!")) {
                    negate = true
                    pattern = pattern.substring(1)
                }
                var dirOnly = false
                if (pattern.endsWith("/")) {
                    dirOnly = true
                    pattern = pattern.dropLast(1)
                }
                val anchored = pattern.startsWith("/") || pattern.contains("/") &&
                    !pattern.startsWith("**/")
                if (pattern.startsWith("/")) pattern = pattern.substring(1)
                if (pattern.isEmpty()) continue

                val regex = buildRegex(pattern, anchored)
                rules.add(Rule(regex, negate, dirOnly))
            }
            return Gitignore(rules)
        }

        fun load(repoRoot: Path): Gitignore {
            val gitignore = repoRoot.resolve(".gitignore")
            if (!Files.isRegularFile(gitignore)) return empty()
            return parse(Files.readString(gitignore))
        }

        private fun buildRegex(pattern: String, anchored: Boolean): Regex {
            val sb = StringBuilder()
            if (anchored) {
                sb.append("\\A")
            } else {
                sb.append("(?:\\A|.*?/)")
            }
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                        // Handle `**/`, `/**`, or bare `**`.
                        val hasSlashAfter = i + 2 < pattern.length && pattern[i + 2] == '/'
                        if (hasSlashAfter) {
                            sb.append("(?:.*/)?")
                            i += 3
                        } else {
                            sb.append(".*")
                            i += 2
                        }
                    }
                    c == '*' -> {
                        sb.append("[^/]*")
                        i++
                    }
                    c == '?' -> {
                        sb.append("[^/]")
                        i++
                    }
                    c == '.' || c == '(' || c == ')' || c == '+' || c == '|' ||
                        c == '^' || c == '$' || c == '{' || c == '}' || c == '[' ||
                        c == ']' || c == '\\' -> {
                        sb.append('\\').append(c)
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            // A pattern without a trailing slash matches the path OR any descendant of it
            // (which is how git treats e.g. `build` ignoring `build/foo.txt`).
            sb.append("(?:\\z|/.*\\z)")
            return Regex(sb.toString())
        }
    }
}
