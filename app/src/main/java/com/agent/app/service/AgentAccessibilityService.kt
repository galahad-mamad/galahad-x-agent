package com.agent.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.app.agent.AgentCore
import com.agent.app.llm.LLMEngine
import com.agent.app.memory.AgentMemory
import com.agent.app.shell.ShellBridge

/**
 * Agent Accessibility Service - connects Android accessibility to Agent Core
 * 
 * This service:
 * 1. Provides screen reading capabilities (Accessibility Tree)
 * 2. Enables UI automation (click, type, scroll)
 * 3. Connects to Agent Core for autonomous operation
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: AgentAccessibilityService? = null
        private var agentCore: AgentCore? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
                .getBoolean("accessibility_enabled", false)
            return enabled || instance != null
        }

        fun getInstance(): AgentAccessibilityService? = instance
        
        fun getAgentCore(): AgentCore? = agentCore
        
        fun setAgentCore(core: AgentCore) {
            agentCore = core
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // Save state
        getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("accessibility_enabled", true)
            .apply()
        
        // Connect to agent core if available
        agentCore?.setAccessibilityService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are processed by ScreenReader on demand
        // No need to process every event in real-time
    }

    override fun onInterrupt() {
        // TODO: Handle interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        agentCore = null
        
        getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("accessibility_enabled", false)
            .apply()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
