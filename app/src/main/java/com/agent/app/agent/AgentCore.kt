package com.agent.app.agent

import android.content.Context
import android.util.Log
import com.agent.app.llm.LLMEngine
import com.agent.app.memory.AgentMemory
import com.agent.app.screen.ScreenReader
import com.agent.app.service.AgentAccessibilityService
import com.agent.app.shell.ShellBridge
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray

/**
 * Agent Core - ported from the Galahad agent to Kotlin
 * 
 * Architecture: Perceive -> Reason -> Act
 * - Perceive: ScreenReader + ShellBridge
 * - Reason: LLMEngine (API/Local/Hybrid)  
 * - Act: ScreenReader actions + ShellBridge commands
 * 
 * No root or Termux required
 */
class AgentCore(
    private val context: Context,
    private val llm: LLMEngine,
    private val memory: AgentMemory,
    private val shell: ShellBridge
) {
    companion object {
        private const val TAG = "AgentCore"
        private const val MAX_ITERATIONS = 10
    }

    private var isRunning = false
    private var currentJob: Job? = null
    private var screenReader: ScreenReader? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Agent state
    private var currentTask: String = ""
    private var iterationCount: Int = 0
    private var lastAction: String = ""
    private var conversationHistory: MutableList<Map<String, String>> = mutableListOf()

    fun setAccessibilityService(service: AgentAccessibilityService) {
        screenReader = ScreenReader(service)
    }

    /**
     * Start the agent loop
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        currentJob = scope.launch {
            while (isRunning) {
                try {
                    agentLoop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in agent loop", e)
                    delay(5000)
                }
            }
        }
    }

    /**
     * Stop the agent loop
     */
    fun stop() {
        isRunning = false
        currentJob?.cancel()
    }

    /**
     * Execute a single command from user
     */
    suspend fun executeCommand(command: String): String {
        currentTask = command
        iterationCount = 0
        conversationHistory.clear()
        
        return try {
            val result = runAgentLoop(command)
            // Save to memory
            memory.addMemory("Task: $command\nResult: $result", 0.7f, "task")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Main agent loop - Perceive → Reason → Act
     */
    private suspend fun agentLoop() {
        while (isRunning && iterationCount < MAX_ITERATIONS) {
            iterationCount++
            
            // 1. PERCEIVE - gather environment info
            val screenContent = screenReader?.getScreenContent() ?: ""
            val interactiveElements = screenReader?.getInteractiveElements() ?: emptyList()
            val systemInfo = getSystemInfo()
            
            // 2. REASON - decide via LLM
            val prompt = buildAgentPrompt(screenContent, interactiveElements, systemInfo)
            val response = llm.generateResponse(prompt, getRelevantContext())
            
            // 3. ACT - execute the decision
            val actionResult = executeAgentAction(response)
            
            // check for completion
            if (actionResult.contains("DONE") || actionResult.contains("COMPLETED")) {
                break
            }
            
            delay(1000)
        }
    }

    /**
     * Run agent loop for a specific command
     */
    private suspend fun runAgentLoop(command: String): String {
        var finalResult = ""
        
        while (iterationCount < MAX_ITERATIONS) {
            iterationCount++
            
            // Perceive
            val screenContent = screenReader?.getScreenContent() ?: ""
            val interactiveElements = screenReader?.getInteractiveElements() ?: emptyList()
            
            // Reason
            val prompt = buildCommandPrompt(command, screenContent, interactiveElements)
            val response = llm.generateResponse(prompt, getRelevantContext())
            
            // Act
            val result = executeAgentAction(response)
            finalResult = result
            
            if (result.contains("DONE") || result.contains("COMPLETED")) {
                break
            }
            
            delay(1000)
        }
        
        return finalResult
    }

    /**
     * Build prompt for the agent
     */
    private fun buildAgentPrompt(
        screenContent: String,
        elements: List<com.agent.app.screen.UIElement>,
        systemInfo: Map<String, String>
    ): String {
        val elementsText = elements.joinToString("\n") { element ->
            buildString {
                if (element.text.isNotBlank()) append("Text: ${element.text}")
                if (element.contentDescription.isNotBlank()) append(" (${element.contentDescription})")
                if (element.isClickable) append(" [clickable]")
                if (element.isFocusable) append(" [focusable]")
                if (element.resourceId.isNotBlank()) append(" [id: ${element.resourceId}]")
            }
        }

        return """
            You are an Android Agent controlling a phone. 
            
            Current screen content:
            $screenContent
            
            Interactive elements:
            $elementsText
            
            System info:
            ${systemInfo.entries.joinToString("\n") { "${it.key}: ${it.value}" }}
            
            Task: $currentTask
            Iteration: $iterationCount/$MAX_ITERATIONS
            Last action: $lastAction
            
            Decide the next action. Respond in JSON format:
            {"action": "click|type|scroll|shell|wait|done", "target": "element text or id", "value": "text to type or command", "reason": "why"}
            
            If task is complete, respond with: {"action": "done", "result": "summary"}
        """.trimIndent()
    }

    /**
     * Build prompt for specific command
     */
    private fun buildCommandPrompt(
        command: String,
        screenContent: String,
        elements: List<com.agent.app.screen.UIElement>
    ): String {
        val elementsText = elements.joinToString("\n") { element ->
            buildString {
                if (element.text.isNotBlank()) append("Text: ${element.text}")
                if (element.contentDescription.isNotBlank()) append(" (${element.contentDescription})")
                if (element.isClickable) append(" [clickable]")
                if (element.resourceId.isNotBlank()) append(" [id: ${element.resourceId}]")
            }
        }

        return """
            Execute this command: $command
            
            Current screen:
            $screenContent
            
            Interactive elements:
            $elementsText
            
            Iteration: $iterationCount/$MAX_ITERATIONS
            
            Respond in JSON format:
            {"action": "click|type|scroll|shell|wait|done", "target": "element text or id", "value": "text to type or command", "reason": "why"}
            
            If command is complete, respond with: {"action": "done", "result": "summary"}
        """.trimIndent()
    }

    /**
     * Execute action based on LLM response
     */
    private suspend fun executeAgentAction(response: String): String {
        return try {
            // Parse JSON response
            val json = JSONObject(response)
            val action = json.optString("action", "wait")
            val target = json.optString("target", "")
            val value = json.optString("value", "")
            val reason = json.optString("reason", "")

            Log.d(TAG, "Action: $action, Target: $target, Value: $value, Reason: $reason")

            lastAction = "$action: $target"

            when (action) {
                "click" -> {
                    if (target.isNotBlank()) {
                        screenReader?.clickByText(target) ?: false
                        "Clicked on: $target"
                    } else {
                        "No target specified for click"
                    }
                }
                "type" -> {
                    if (value.isNotBlank()) {
                        screenReader?.typeText(value) ?: false
                        "Typed: $value"
                    } else {
                        "No value specified for type"
                    }
                }
                "scroll" -> {
                    screenReader?.scrollDown() ?: false
                    "Scrolled down"
                }
                "shell" -> {
                    if (value.isNotBlank()) {
                        val result = shell.execute(value)
                        if (result.isSuccess) {
                            result.output
                        } else {
                            "Shell error: ${result.error}"
                        }
                    } else {
                        "No command specified for shell"
                    }
                }
                "wait" -> {
                    delay(2000)
                    "Waited 2 seconds"
                }
                "done" -> {
                    value.ifBlank { "Task completed" }
                }
                else -> {
                    "Unknown action: $action"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action", e)
            "Error executing action: ${e.message}"
        }
    }

    /**
     * Get system information
     */
    private fun getSystemInfo(): Map<String, String> {
        return try {
            val result = shell.execute("getprop ro.build.version.release")
            val androidVersion = if (result.isSuccess) result.output.trim() else "unknown"
            
            val batteryResult = shell.execute("dumpsys battery | grep level")
            val battery = if (batteryResult.isSuccess) batteryResult.output.trim() else "unknown"
            
            mapOf(
                "android_version" to androidVersion,
                "battery" to battery,
                "app_package" to context.packageName
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "unknown"))
        }
    }

    /**
     * Get relevant context from memory
     */
    private fun getRelevantContext(): String {
        return memory.getRelevantMemories(currentTask, 3)
            .joinToString("\n") { it.content }
    }

    /**
     * Get current screen content
     */
    fun getScreenContent(): String {
        return screenReader?.getScreenContent() ?: ""
    }

    /**
     * Get interactive elements
     */
    fun getInteractiveElements(): List<com.agent.app.screen.UIElement> {
        return screenReader?.getInteractiveElements() ?: emptyList()
    }

    /**
     * Execute shell command
     */
    fun executeShell(command: String): String {
        val result = shell.execute(command)
        return if (result.isSuccess) result.output else "Error: ${result.error}"
    }

    /**
     * Click on element by text
     */
    fun clickByText(text: String): Boolean {
        return screenReader?.clickByText(text) ?: false
    }

    /**
     * Type text
     */
    fun typeText(text: String): Boolean {
        return screenReader?.typeText(text) ?: false
    }

    /**
     * Scroll down
     */
    fun scrollDown(): Boolean {
        return screenReader?.scrollDown() ?: false
    }

    /**
     * Press back
     */
    fun pressBack(): Boolean {
        return screenReader?.pressBack() ?: false
    }

    /**
     * Press home
     */
    fun pressHome(): Boolean {
        return screenReader?.pressHome() ?: false
    }

    /**
     * Get agent status
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "is_running" to isRunning,
            "current_task" to currentTask,
            "iteration" to iterationCount,
            "last_action" to lastAction,
            "screen_content_length" to getScreenContent().length,
            "interactive_elements_count" to getInteractiveElements().size
        )
    }
}
