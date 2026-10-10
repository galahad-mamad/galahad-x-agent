package com.agent.app.chat

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Skill store — Hermes-style:
 * Each skill = a folder with SKILL.md (step-by-step instructions).
 * - Save: skill_save after completing a complex repeatable task
 * - Load: every user message is matched against skills
 *   and the SKILL.md content is injected into the system prompt
 * - Update: skill_update with targeted find/replace
 */
class SkillStore(private val context: Context) {

    private val TAG = "SkillStore"

    private fun root(): File = File(context.filesDir, "skills").apply { mkdirs() }

    private fun sanitize(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').take(48)

    fun list(): String {
        val dirs = root().listFiles { f -> f.isDirectory } ?: return "no skills saved yet."
        if (dirs.isEmpty()) return "no skills saved yet."
        val sb = StringBuilder("📁 Saved skills:\n")
        dirs.sortedBy { it.name }.forEach { d ->
            val md = File(d, "SKILL.md")
            if (md.isFile) {
                val desc = md.bufferedReader().use { it.readLine() } ?: ""
                sb.append("• ${d.name}: ${desc.trimStart('#', ' ')}\n")
            }
        }
        return sb.toString()
    }

    fun load(name: String): String {
        val md = File(File(root(), sanitize(name)), "SKILL.md")
        if (!md.isFile) return "ERROR: skill '$name' not found. Run skill_list to see what exists."
        return md.readText().take(8000)
    }

    /** Fallback: load the only skill whose name contains the query (fuzzy load) */
    fun loadByPrefix(query: String): String {
        val q = sanitize(query)
        if (q.isBlank()) return "ERROR: empty skill name"
        val dirs = root().listFiles { f -> f.isDirectory } ?: return "ERROR: skill '$query' not found. Run skill_list."
        val hits = dirs.filter { it.name.contains(q) || q.contains(it.name) }
            .filter { File(it, "SKILL.md").isFile }
        return when (hits.size) {
            0 -> "ERROR: skill '$query' not found. Run skill_list to see exact names."
            1 -> File(hits[0], "SKILL.md").readText().take(8000)
            else -> "ERROR: '$query' is ambiguous: ${hits.joinToString { it.name }} - use the exact name"
        }
    }

    fun save(name: String, content: String): String {
        val n = sanitize(name)
        if (n.isBlank()) return "ERROR: invalid skill name (English letters/digits/-/_ only)"
        val dir = File(root(), n).apply { mkdirs() }
        val md = File(dir, "SKILL.md")
        val existed = md.isFile
        // simple history: the old copy goes to SKILL.md.bak
        if (existed) md.copyTo(File(dir, "SKILL.md.bak"), overwrite = true)
        md.writeText(content)
        Log.d(TAG, "skill ${if (existed) "updated" else "saved"}: $n (${content.length} chars)")
        return "SKILL_${if (existed) "UPDATED" else "SAVED"}: $n : ${content.length} chars → ${md.absolutePath}"
    }

    /** Targeted find/replace - if old is empty the text is appended */
    fun update(name: String, oldText: String, newText: String): String {
        val md = File(File(root(), sanitize(name)), "SKILL.md")
        if (!md.isFile) return "ERROR: skill '$name' not found - run skill_save first."
        val content = md.readText()
        val updated = if (oldText.isBlank()) {
            content.trimEnd() + "\n\n" + newText
        } else if (!content.contains(oldText)) {
            return "ERROR: old_text not found in SKILL.md - copy it exactly from the file."
        } else {
            content.replace(oldText, newText)
        }
        md.copyTo(File(md.parentFile, "SKILL.md.bak"), overwrite = true)
        md.writeText(updated)
        return "SKILL_UPDATED: $name (${updated.length} chars)"
    }

    fun delete(name: String): String {
        val dir = File(root(), sanitize(name))
        if (!dir.exists()) return "ERROR: skill '$name' does not exist."
        dir.deleteRecursively()
        return "SKILL_DELETED: $name"
    }

    /**
     * Match a user message against skills (name/description/body keywords),
     * Top `limit` skills with score > 0
     */
    fun matchKeywords(message: String, limit: Int = 2): List<Pair<String, String>> {
        val words = message.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .take(24)
        if (words.isEmpty()) return emptyList()
        val dirs = root().listFiles { f -> f.isDirectory } ?: return emptyList()
        val scored = mutableListOf<Triple<String, String, Int>>()
        dirs.forEach { d ->
            val md = File(d, "SKILL.md")
            if (!md.isFile) return@forEach
            val text = md.readText().take(6000).lowercase()
            val nameL = d.name.lowercase()
            var score = 0
            words.forEach { w ->
                if (nameL.contains(w)) score += 4
                if (text.contains(w)) score += 1
            }
            if (score > 0) scored.add(Triple(d.name, md.readText(), score))
        }
        return scored.sortedByDescending { it.third }
            .take(limit)
            .map { it.first to it.second }
    }
}
