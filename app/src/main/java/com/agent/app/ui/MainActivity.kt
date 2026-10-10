package com.agent.app.ui

import android.content.Context
import com.agent.app.llm.ProviderStore
import androidx.compose.ui.platform.LocalContext
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agent.app.agent.AgentCore
import com.agent.app.agent.ToolRegistry
import com.agent.app.chat.ChatController
import com.agent.app.screen.ScreenReader
import com.agent.app.llm.LLMEngine
import com.agent.app.memory.AgentMemory
import com.agent.app.service.AgentAccessibilityService
import com.agent.app.shell.ShellBridge
import com.agent.app.ui.theme.AndroidAgentTheme

class MainActivity : ComponentActivity() {
    private lateinit var agentCore: AgentCore
    private lateinit var llmEngine: LLMEngine
    private lateinit var memory: AgentMemory
    private lateinit var shell: ShellBridge
    private lateinit var chatController: ChatController
    private lateinit var toolRegistry: ToolRegistry

    var serviceEnabled by mutableStateOf(false)

    @Suppress("DEPRECATION")
    fun refreshServiceState() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val fromList = try {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
        } catch (e: Exception) { false }
        // fallback: read Settings directly (while the binding isn't ready yet)
        val fromSettings = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
        serviceEnabled = am.isEnabled && (fromList || fromSettings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshServiceState()

        // floating bubble: overlay permission asked once (declinable)
        try {
            val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
            if (!android.provider.Settings.canDrawOverlays(this) &&
                !prefs.getBoolean("asked_overlay", false)
            ) {
                prefs.edit().putBoolean("asked_overlay", true).apply()
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (_: Exception) {}
        
        // Initialize components
        llmEngine = LLMEngine()
        memory = AgentMemory(this)
        shell = ShellBridge()
        agentCore = AgentCore(this, llmEngine, memory, shell)
        toolRegistry = ChatController.instance?.let { ChatController.instance!!.registry } ?: ToolRegistry(
            context = applicationContext,
            screenReader = {
                AgentAccessibilityService.getInstance()?.let { ScreenReader(it) }
            },
            shell = shell,
            memory = memory
        )
        // process-wide controller: not rebuilt on every onCreate so chat state
        // and the running loop survive Home/return
        chatController = ChatController.instance
            ?: ChatController(applicationContext, llmEngine, memory, toolRegistry)
        // UI settings must apply to the same controller's engine
        llmEngine = chatController.engine
        
        // Connect accessibility service
        AgentAccessibilityService.getInstance()?.let {
            agentCore.setAccessibilityService(it)
        }
        
        setContent {
            AndroidAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        agentCore, chatController, llmEngine, serviceEnabled,
                        onRefreshService = { refreshServiceState() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
        AgentAccessibilityService.getInstance()?.let {
            agentCore.setAccessibilityService(it)
        }
    }
}

@Composable
fun AppNavigation(
    agentCore: AgentCore,
    chatController: ChatController,
    llmEngine: LLMEngine,
    serviceEnabled: Boolean = true,
    onRefreshService: () -> Unit = {}
) {
    val navController = rememberNavController()
    
    // multi-provider/models - ProviderStore is the source of truth
    val ctx = LocalContext.current
    var providers by remember {
        mutableStateOf(ProviderStore.ensureSeeded(ctx, "", "", "auto").first)
    }
    var activeId by remember {
        mutableStateOf(ProviderStore.ensureSeeded(ctx, "", "", "auto").second)
    }
    val activeProvider = providers.find { it.id == activeId } ?: providers.firstOrNull()
    var apiModel by remember { mutableStateOf(activeProvider?.model ?: "auto") }
    var apiKey by remember { mutableStateOf(activeProvider?.apiKey ?: "") }
    var apiEndpoint by remember { mutableStateOf(activeProvider?.baseUrl ?: "") }
    
    // Update LLM engine when settings change
    LaunchedEffect(apiKey, apiEndpoint, apiModel) {
        llmEngine.setApiKey(apiKey)
        llmEngine.setApiEndpoint(apiEndpoint)
        llmEngine.setApiModel(apiModel)
    }

    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                chatController = chatController,
                onOpenSettings = { navController.navigate("settings") },
                serviceEnabled = serviceEnabled,
                onRefreshService = onRefreshService,
                providerName = activeProvider?.name ?: "",
                modelName = apiModel
            )
        }
        composable("settings") {
            SettingsScreen(
                providers = providers,
                activeId = activeId,
                onProvidersChange = { list, act ->
                    ProviderStore.saveAll(ctx, list, act)
                    providers = list
                    activeId = act
                    val a = list.find { it.id == act } ?: list.firstOrNull()
                    apiKey = a?.apiKey ?: ""
                    apiEndpoint = a?.baseUrl ?: ""
                    apiModel = a?.model ?: "auto"
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
