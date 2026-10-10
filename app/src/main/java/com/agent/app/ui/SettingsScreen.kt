package com.agent.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.app.llm.ProviderCfg
import com.agent.app.ui.theme.AppColors
import com.agent.app.llm.ProviderStore
import com.agent.app.memory.MemoryStore
import com.agent.app.service.AgentAccessibilityService
import kotlinx.coroutines.launch

/**
 * Multi API/provider/model manager + permissions + memory.
 * OpenAI-style: add endpoint+key -> fetch the model list -> multi-select -> switch.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    providers: List<ProviderCfg>,
    activeId: String?,
    onProvidersChange: (List<ProviderCfg>, String?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isAccessibilityEnabled by remember {
        mutableStateOf(AgentAccessibilityService.isEnabled(context))
    }

    // editor state
    var editing by remember { mutableStateOf<ProviderCfg?>(null) }
    var editorOpen by remember { mutableStateOf(false) }   // separate from editing: null also means adding a new provider
    var isNew by remember { mutableStateOf(false) }
    var formName by remember { mutableStateOf("") }
    var formUrl by remember { mutableStateOf("") }
    var formKey by remember { mutableStateOf("") }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var errMsg by remember { mutableStateOf<String?>(null) }

    fun openEditor(p: ProviderCfg?) {
        editing = p; editorOpen = true; isNew = p == null
        formName = p?.name ?: ""
        formUrl = p?.baseUrl ?: ""
        formKey = p?.apiKey ?: ""
        fetchedModels = p?.models ?: emptyList()
        selectedModels = p?.models?.toSet() ?: emptySet()
        errMsg = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editorOpen) (if (isNew) "Add Provider" else "Edit Provider") else "Providers & Models") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (editorOpen) editorOpen = false else onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!editorOpen) {
                // ---------- provider list ----------
                Text("API PROVIDERS", style = MaterialTheme.typography.labelLarge, color = AppColors.textSecondary)

                providers.forEach { p ->
                    val isActive = p.id == activeId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                        border = BorderStroke(if (isActive) 2.dp else 1.dp, if (isActive) AppColors.accent1 else AppColors.border)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall,
                                    color = if (isActive) AppColors.accent1 else AppColors.textPrimary)
                                Spacer(Modifier.width(8.dp))
                                if (isActive) Text("● ACTIVE", fontSize = 10.sp, color = AppColors.accent1)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { openEditor(p) }) { Text("EDIT", fontSize = 11.sp) }
                                TextButton(onClick = {
                                    if (providers.size > 1) {
                                        val rest = providers.filter { it.id != p.id }
                                        val newActive = if (p.id == activeId) rest.first().id else activeId
                                        onProvidersChange(rest, newActive)
                                    } else errMsg = "cannot delete the last provider"
                                }) { Text("DEL", fontSize = 11.sp, color = AppColors.danger) }
                            }
                            Text(p.baseUrl, fontSize = 11.sp, color = AppColors.textSecondary)
                            if (p.models.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    p.models.forEach { m ->
                                        val sel = m == p.model
                                        FilterChip(
                                            selected = sel,
                                            onClick = {
                                                val np = p.copy(model = m)
                                                onProvidersChange(providers.map { if (it.id == p.id) np else it }, activeId)
                                            },
                                            label = { Text(m, fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = AppColors.accent1,
                                                selectedLabelColor = AppColors.bg
                                            )
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(
                                    onClick = { onProvidersChange(providers, p.id) },
                                    label = { Text(if (isActive) "Active — in use" else "Activate this provider", fontSize = 11.sp) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (isActive) AppColors.accent1.copy(alpha = 0.18f) else AppColors.bg
                                    )
                                )
                            }
                        }
                    }
                }
                errMsg?.let { Text(it, color = AppColors.danger, fontSize = 12.sp) }

                OutlinedButton(
                    onClick = { openEditor(null) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, AppColors.accent1)
                ) { Text("+ Add API Provider", color = AppColors.accent1) }

            } else {
                // ---------- add/edit form ----------
                OutlinedTextField(formName, { formName = it }, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(formUrl, { formUrl = it }, label = { Text("Base URL (https://.../v1)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(formKey, { formKey = it }, label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation())

                Button(
                    onClick = {
                        loadingModels = true; errMsg = null
                        scope.launch {
                            val r = ProviderStore.fetchModels(formUrl.trim(), formKey.trim())
                            r.onSuccess {
                                fetchedModels = it
                                selectedModels = it.toSet()
                                loadingModels = false
                            }.onFailure {
                                loadingModels = false
                                errMsg = "model list failed: ${it.message?.take(160)}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loadingModels && formUrl.isNotBlank()
                ) {
                    Text(if (loadingModels) "⏳ Fetching models..." else "📋 Get model list from API")
                }

                errMsg?.let { Text(it, color = AppColors.danger, fontSize = 12.sp) }

                // save/cancel buttons - before the long list so they stay visible
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (formUrl.isBlank()) { errMsg = "Base URL is empty"; return@Button }
                            val id = editing?.id ?: ProviderStore.newId()
                            val keptModel = editing?.model?.takeIf { it in selectedModels }
                                ?: selectedModels.firstOrNull() ?: editing?.model ?: ""
                            val np = ProviderCfg(id, formName.ifBlank { "Provider" },
                                formUrl.trim(), formKey.trim(), selectedModels.toList(), keptModel)
                            val rest = providers.filter { it.id != id }
                            val newList = rest + np
                            onProvidersChange(newList, if (activeId == null || id == activeId) id else activeId)
                            editorOpen = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                    OutlinedButton(onClick = { editorOpen = false }, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                }

                // manual model name - even if the endpoint list lacks it
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text("Model name (optional)") },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    Button(onClick = {
                        val m = customModel.trim()
                        if (m.isNotBlank()) {
                            selectedModels = selectedModels + m
                            fetchedModels = (fetchedModels + m).distinct()
                            customModel = ""
                        }
                    }) { Text("+", fontSize = 20.sp) }
                }

                if (fetchedModels.isNotEmpty()) {
                    OutlinedTextField(
                        value = modelQuery,
                        onValueChange = { modelQuery = it },
                        label = { Text("🔍 Search models...") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    val filtered = if (modelQuery.isBlank()) fetchedModels
                        else fetchedModels.filter { it.contains(modelQuery.trim(), ignoreCase = true) }
                    Text("SELECT MODELS (${selectedModels.size}/${fetchedModels.size} • showing ${filtered.size})",
                        style = MaterialTheme.typography.labelLarge, color = AppColors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        filtered.forEach { m ->
                            val sel = m in selectedModels
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    selectedModels = if (sel) selectedModels - m else selectedModels + m
                                },
                                label = { Text(m, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppColors.accent1,
                                    selectedLabelColor = AppColors.bg
                                )
                            )
                        }
                    }
                }

            }

            Divider(color = AppColors.border)

            // ---------- permissions ----------
            Text("PERMISSIONS", style = MaterialTheme.typography.labelLarge, color = AppColors.textSecondary)
            Button(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isAccessibilityEnabled) "Accessibility: ON ✓" else "Enable Accessibility Service")
            }
            OutlinedButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Enable Floating Bubble (Overlay)") }
            OutlinedButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text("Enable Notification Access") }

            Divider(color = AppColors.border)

            // ---------- memory (actually read from disk) ----------
            Text("MEMORY", style = MaterialTheme.typography.labelLarge, color = AppColors.textSecondary)
            val store = remember { MemoryStore(context) }
            val userMem = remember(store) { store.read("user") }
            val genMem = remember(store) { store.read("memory") }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppColors.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("👤 User: ${userMem.size} entries (${userMem.joinToString("§").length}/${MemoryStore.USER_CHAR_LIMIT})",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.textPrimary)
                    Text("🧠 Memory: ${genMem.size} entries (${genMem.joinToString("§").length}/${MemoryStore.MEMORY_CHAR_LIMIT})",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.textPrimary)
                    userMem.take(4).forEach { Text("• $it", fontSize = 11.sp, color = AppColors.textSecondary) }
                    genMem.take(4).forEach { Text("• $it", fontSize = 11.sp, color = AppColors.textSecondary) }
                }
            }
        }
    }
}
