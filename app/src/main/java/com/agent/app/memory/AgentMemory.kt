package com.agent.app.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Memory(
    val id: String,
    val content: String,
    val timestamp: Long,
    val importance: Float,
    val category: String
)

data class ScheduledTask(
    val id: String,
    val prompt: String,
    val triggerTime: Long,
    val repeat: RepeatType,
    val status: TaskStatus
)

enum class RepeatType { NONE, DAILY, WEEKLY, MONTHLY }
enum class TaskStatus { PENDING, DONE, CANCELLED }

class AgentMemory(context: Context) {
    private val prefs = context.getSharedPreferences("agent_memory", Context.MODE_PRIVATE)
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    init {
        loadMemories()
        loadTasks()
    }

    fun addMemory(content: String, importance: Float = 0.5f, category: String = "general") {
        val memory = Memory(
            id = System.currentTimeMillis().toString(),
            content = content,
            timestamp = System.currentTimeMillis(),
            importance = importance,
            category = category
        )
        val updated = _memories.value + memory
        _memories.value = updated
        saveMemories(updated)
    }

    fun removeMemory(id: String) {
        val updated = _memories.value.filter { it.id != id }
        _memories.value = updated
        saveMemories(updated)
    }

    fun addTask(prompt: String, triggerTime: Long, repeat: RepeatType = RepeatType.NONE) {
        val task = ScheduledTask(
            id = System.currentTimeMillis().toString(),
            prompt = prompt,
            triggerTime = triggerTime,
            repeat = repeat,
            status = TaskStatus.PENDING
        )
        val updated = _tasks.value + task
        _tasks.value = updated
        saveTasks(updated)
    }

    fun completeTask(id: String) {
        val updated = _tasks.value.map {
            if (it.id == id) it.copy(status = TaskStatus.DONE) else it
        }
        _tasks.value = updated
        saveTasks(updated)
    }

    fun getRelevantMemories(query: String, limit: Int = 5): List<Memory> {
        return _memories.value
            .sortedByDescending { it.importance }
            .take(limit)
    }

    private fun loadMemories() {
        val json = prefs.getString("memories", null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            _memories.value = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Memory(
                    id = o.optString("id"),
                    content = o.optString("content"),
                    timestamp = o.optLong("timestamp"),
                    importance = o.optDouble("importance", 0.5).toFloat(),
                    category = o.optString("category", "general")
                )
            }
        } catch (e: Exception) {
            _memories.value = emptyList()
        }
    }

    private fun saveMemories(memories: List<Memory>) {
        val arr = org.json.JSONArray()
        memories.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("id", it.id)
                    .put("content", it.content)
                    .put("timestamp", it.timestamp)
                    .put("importance", it.importance.toDouble())
                    .put("category", it.category)
            )
        }
        prefs.edit().putString("memories", arr.toString()).apply()
    }

    private fun loadTasks() {
        val json = prefs.getString("tasks", null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            _tasks.value = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ScheduledTask(
                    id = o.optString("id"),
                    prompt = o.optString("prompt"),
                    triggerTime = o.optLong("triggerTime"),
                    repeat = try { RepeatType.valueOf(o.optString("repeat", "NONE")) } catch (e: Exception) { RepeatType.NONE },
                    status = try { TaskStatus.valueOf(o.optString("status", "PENDING")) } catch (e: Exception) { TaskStatus.PENDING }
                )
            }
        } catch (e: Exception) {
            _tasks.value = emptyList()
        }
    }

    private fun saveTasks(tasks: List<ScheduledTask>) {
        val arr = org.json.JSONArray()
        tasks.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("id", it.id)
                    .put("prompt", it.prompt)
                    .put("triggerTime", it.triggerTime)
                    .put("repeat", it.repeat.name)
                    .put("status", it.status.name)
            )
        }
        prefs.edit().putString("tasks", arr.toString()).apply()
    }
}
