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

/** Users often paste host.com/v1 without a scheme - make it a usable URL */
private fun normUrl(u: String): String {
    val raw = u.trim()
    if (raw.isEmpty()) return ""
    val s = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    // strip trailing slashes, but never touch a bare "https://" (scheme only)
    return if (s.removePrefix("https://").removePrefix("http://").isEmpty()) s else s.trimEnd('/')
}

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
    val pkgVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
        catch (e: Exception) { "?" }
    }
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
    var loadingModels by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var errMsg by remember { mutableStateOf<String?>(null) }
    // single explicit active-model selection (radio semantics — never a hidden multi-select default)
    var activeModelSel by remember { mutableStateOf("") }

    fun openEditor(p: ProviderCfg?) {
        editing = p; editorOpen = true; isNew = p == null
        formName = p?.name ?: ""
        formUrl = p?.baseUrl ?: ""
        formKey = p?.apiKey ?: ""
        fetchedModels = p?.models ?: emptyList()
        activeModelSel = p?.model ?: ""
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
                                    onClick = {
                                        // fix a legacy schemeless Base URL at activation time too
                                        val fixed = providers.map {
                                            if (it.id == p.id && normUrl(it.baseUrl).isNotBlank())
                                                it.copy(baseUrl = normUrl(it.baseUrl)) else it
                                        }
                                        onProvidersChange(fixed, p.id)
                                    },
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
                            val r = ProviderStore.fetchModels(normUrl(formUrl), formKey.trim())
                            r.onSuccess {
                                fetchedModels = it
                                // keep the current active model if it still exists; never auto-pick
                                if (activeModelSel.isNotBlank() && activeModelSel !in it) activeModelSel = ""
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
                            // the one chip the user tapped IS the active model; no silent fallback
                            val allModels = (fetchedModels + (if (activeModelSel.isNotBlank()) listOf(activeModelSel) else emptyList())).distinct()
                            val np = ProviderCfg(id, formName.ifBlank { "Provider" },
                                normUrl(formUrl), formKey.trim(), allModels, activeModelSel.trim())
                            val rest = providers.filter { it.id != id }
                            val newList = rest + np
                            // saving a provider means the user wants to USE it now - activate it
                            onProvidersChange(newList, id)
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
                            fetchedModels = (fetchedModels + m).distinct()
                            activeModelSel = m
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
                    Text(if (activeModelSel.isBlank()) "TAP A MODEL TO SELECT IT (${filtered.size} shown)"
                         else "ACTIVE MODEL: $activeModelSel  (tap another to switch)",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (activeModelSel.isBlank()) AppColors.danger else AppColors.accent1)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        filtered.forEach { m ->
                            val sel = m == activeModelSel
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    activeModelSel = m
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

            Divider(color = AppColors.border)

            // ---------- contact / bug reports ----------
            Text("CONTACT US", style = MaterialTheme.typography.labelLarge, color = AppColors.textSecondary)
            Text("Found a bug or have feedback? Report it — reports in Persian or English are welcome.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textSecondary)
            Button(onClick = {
                context.startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/hitlerfortest")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Report on Telegram — @hitlerfortest") }
            OutlinedButton(onClick = {
                context.startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://instagram.com/galahad_mamad")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Instagram — galahad_mamad") }

            Text("Galahad-X Agent v$pkgVersion", fontSize = 11.sp, color = AppColors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
