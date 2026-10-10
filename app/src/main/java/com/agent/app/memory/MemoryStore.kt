package com.agent.app.memory

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Persistent memory:
 * - two files: memory.md (2200 char cap) + user.md (1375 cap)
 * - entries are separated by a section-sign delimiter
 * - memory tool: add/replace/remove + atomic batched operations
 * - every change checks the budget cap against the final length; rejection reports usage
 */
class MemoryStore(context: Context) {

    companion object {
        private const val TAG = "MemoryStore"
        private const val DELIM = "\n§\n"
        const val MEMORY_CHAR_LIMIT = 2200
        const val USER_CHAR_LIMIT = 1375
    }

    private val dir = java.io.File(context.filesDir, "memory").apply { mkdirs() }

    private fun fileFor(target: String) =
        java.io.File(dir, if (target == "user") "user.md" else "memory.md")

    private fun limitFor(target: String) =
        if (target == "user") USER_CHAR_LIMIT else MEMORY_CHAR_LIMIT

    fun read(target: String): List<String> {
        val f = fileFor(target)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readText().split(DELIM).map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun usage(target: String): String {
        val total = read(target).joinToString(DELIM).length
        return "$total/${limitFor(target)} chars"
    }

    /** Smart error report: current inventory + retry guidance */
    private fun missingOldTextError(target: String, action: String): Map<String, Any> {
        val entries = read(target)
        val inv = entries.mapIndexed { i, e -> "  ${i + 1}. ${e.take(80)}" }
            .joinToString("\n").ifBlank { "  (empty)" }
        return mapOf(
            "success" to false,
            "error" to "$action requires old_text - pick the target entry from the inventory below:\n$inv",
            "usage" to usage(target)
        )
    }

    fun add(target: String, content: String): Map<String, Any> {
        val c = content.trim()
        if (c.isEmpty()) return mapOf("success" to false, "error" to "content is empty")
        val entries = read(target)
        if (entries.any { it.equals(c, ignoreCase = true) }) {
            return mapOf("success" to false, "error" to "entry already exists - not saved", "usage" to usage(target))
        }
        val newEntries = entries + c
        val newTotal = newEntries.joinToString(DELIM).length
        val limit = limitFor(target)
        if (newTotal > limit) {
            return mapOf(
                "success" to false,
                "error" to "budget full: $newTotal/$limit - free space with replace/remove first",
                "usage" to "$newTotal/$limit chars"
            )
        }
        fileFor(target).writeText(newEntries.joinToString(DELIM))
        Log.d(TAG, "add[$target]: $c")
        return mapOf("success" to true, "usage" to "$newTotal/$limit chars")
    }

    fun replace(target: String, oldText: String, newContent: String): Map<String, Any> {
        val entries = read(target)
        val idx = entries.indexOfFirst { it.contains(oldText) }
        if (idx < 0) return missingOldTextError(target, "replace")
        val newEntries = entries.toMutableList().apply { set(idx, newContent.trim()) }
        val newTotal = newEntries.joinToString(DELIM).length
        val limit = limitFor(target)
        if (newTotal > limit) {
            return mapOf(
                "success" to false,
                "error" to "budget exceeded after replace: $newTotal/$limit - shorten the text",
                "usage" to "$newTotal/$limit chars"
            )
        }
        fileFor(target).writeText(newEntries.joinToString(DELIM))
        Log.d(TAG, "replace[$target]: ${oldText.take(40)} -> ${newContent.take(40)}")
        return mapOf("success" to true, "usage" to "$newTotal/$limit chars")
    }

    fun remove(target: String, oldText: String): Map<String, Any> {
        val entries = read(target)
        val idx = entries.indexOfFirst { it.contains(oldText) }
        if (idx < 0) return missingOldTextError(target, "remove")
        val newEntries = entries.toMutableList().apply { removeAt(idx) }
        fileFor(target).writeText(newEntries.joinToString(DELIM))
        Log.d(TAG, "remove[$target]: ${oldText.take(40)}")
        return mapOf("success" to true, "usage" to usage(target))
    }

    /** Atomic batch apply: validate on a copy, check budget once, then save */
    fun applyBatch(target: String, operations: List<JSONObject>): Map<String, Any> {
        val working = read(target).toMutableList()
        var applied = 0
        for (op in operations) {
            when (val a = op.optString("action")) {
                "add" -> {
                    val c = op.optString("content").trim()
                    if (c.isNotEmpty() && working.none { it.equals(c, ignoreCase = true) }) {
                        working.add(c); applied++
                    }
                }
                "replace" -> {
                    val i = working.indexOfFirst { it.contains(op.optString("old_text")) }
                    if (i >= 0) { working[i] = op.optString("content").trim(); applied++ }
                }
                "remove" -> {
                    val i = working.indexOfFirst { it.contains(op.optString("old_text")) }
                    if (i >= 0) { working.removeAt(i); applied++ }
                }
                else -> return mapOf("success" to false, "error" to "unknown action '$a' - only add/replace/remove")
            }
        }
        val newTotal = working.joinToString(DELIM).length
        val limit = limitFor(target)
        if (newTotal > limit) {
            return mapOf(
                "success" to false,
                "error" to "budget rejected: $newTotal/$limit - nothing written (atomic). free space first",
                "usage" to "$newTotal/$limit chars"
            )
        }
        fileFor(target).writeText(working.joinToString(DELIM))
        Log.d(TAG, "batch[$target]: $applied/${operations.size} ops")
        return mapOf("success" to true, "applied" to applied, "ops" to operations.size, "usage" to "$newTotal/$limit chars")
    }
}
