package com.agent.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agent.app.chat.Attachment
import com.agent.app.chat.ChatController
import com.agent.app.chat.ChatMessage
import com.agent.app.ui.theme.AppColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatController: ChatController,
    onOpenSettings: () -> Unit,
    serviceEnabled: Boolean = true,
    onRefreshService: () -> Unit = {},
    providerName: String = "",
    modelName: String = "auto"
) {
    val messages by chatController.messages.collectAsState()
    val isProcessing by chatController.isProcessing.collectAsState()
    val sessions by chatController.sessions.collectAsState()
    val currentSessionId by chatController.currentSessionId.collectAsState()
    var showSessions by remember { mutableStateOf(false) }
    val agentStatus by chatController.agentStatus.collectAsState()
    // seconds ticker for the "working" indicator
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(isProcessing) {
        if (isProcessing) {
            elapsed = 0
            while (isProcessing) {
                kotlinx.coroutines.delay(1000)
                elapsed++
            }
        }
        elapsed = 0
    }
    var inputText by remember { mutableStateOf("") }
    var selectedUris by remember { mutableStateOf(listOf<Uri>()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var bannerDismissed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // file picker: images + any file
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) selectedUris = selectedUris + uris
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // re-check service state (binding arrives async after start)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            onRefreshService()
        }
    }

    Scaffold(
        containerColor = AppColors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = androidx.compose.ui.res.painterResource(
                                com.agent.app.R.drawable.ic_launcher_fg
                            ),
                            contentDescription = null,
                            modifier = Modifier
                                .size(34.dp)
                                .shadow(10.dp, RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Galahad-X", style = MaterialTheme.typography.titleMedium)
                            // status pill
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = AppColors.surfaceHigh,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(
                                                if (isProcessing) AppColors.warn else AppColors.success
                                            )
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    val modelLabel = if (modelName.isBlank()) "auto" else modelName
                                    val provLabel = providerName.ifBlank { "no provider" }
                                    Text(
                                        if (isProcessing) "${agentStatus.ifBlank { "Working" }} • $modelLabel • ${elapsed}s"
                                        else "$provLabel • $modelLabel",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (providerName.isBlank()) AppColors.warn else AppColors.textSecondary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 210.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSessions = true }) {
                        Icon(Icons.Default.List, contentDescription = "Sessions", tint = AppColors.textSecondary)
                    }
                    IconButton(onClick = { chatController.clearChat() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear chat", tint = AppColors.textSecondary)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = AppColors.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.bg
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(AppColors.bg, AppColors.bgBottom)))
                .padding(padding)
        ) {
            // service activation guide banner (Android 13+ "restricted settings") - dismissible
            if (!serviceEnabled && !bannerDismissed) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "⚠️ Accessibility service is not enabled",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { bannerDismissed = true }) {
                                Text(
                                    "✕",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "On Android 13+, apps outside the Play Store need \"restricted settings\" allowed before enabling. Steps:\n" +
                                    "1) Try the service switch once (being blocked is expected)\n" +
                                    "2) Tap \"My app settings\" → the ⋮ menu at the top → \"Allow restricted settings\"\n" +
                                    "3) Come back and flip the switch on — done ✅",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(6.dp))
                        // Android 15: sideloaded service checkboxes are greyed out → bypass via ADB
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0D1420)
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    "Android 15: checkbox greyed out? Run this once from a computer:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AppColors.textSecondary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "adb shell settings put secure enabled_accessibility_services com.agent.app/.service.AgentAccessibilityService\nadb shell settings put secure accessibility_enabled 1",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    color = AppColors.teal
                                )
                                Spacer(Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                "adb",
                                                "adb shell settings put secure enabled_accessibility_services com.agent.app/.service.AgentAccessibilityService\n" +
                                                        "adb shell settings put secure accessibility_enabled 1"
                                            )
                                        )
                                        android.widget.Toast.makeText(context, "ADB command copied ✓", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent1)
                                ) { Text("Copy ADB command") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent1)
                            ) { Text("My app settings") }
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("Accessibility settings") }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages) { message ->
                    ChatBubble(message)
                }
                if (isProcessing) {
                    item { TypingIndicator() }
                }
            }

            // file picker bar
            if (selectedUris.isNotEmpty()) {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedUris.forEach { uri ->
                            AttachmentChip(
                                uri = uri,
                                onRemove = { selectedUris = selectedUris - uri }
                            )
                        }
                    }
                }
            }

            // input bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AppColors.surface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        pickerLauncher.launch(arrayOf("image/*", "video/*", "audio/*", "application/pdf", "text/*", "*/*"))
                    }) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = "Attach file",
                            tint = AppColors.textSecondary
                        )
                    }
                    val doSend = {
                        if (inputText.isNotBlank() || selectedUris.isNotEmpty()) {
                            val msg = inputText
                            val uris = selectedUris
                            inputText = ""
                            selectedUris = emptyList()
                            chatController.sendMessage(msg, uris)
                        }
                        true
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (isProcessing) "Message (queued)..." else "Type a message...",
                                color = AppColors.textFaint
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppColors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppColors.surfaceHigh,
                            unfocusedContainerColor = AppColors.surfaceHigh,
                            focusedBorderColor = AppColors.accent1,
                            unfocusedBorderColor = AppColors.border,
                            cursorColor = AppColors.accent2
                        ),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { doSend() })
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isProcessing) {
                        // stop button - mid-job
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(50))
                                .background(AppColors.danger)
                                .clickable { chatController.stopRun() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("■", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    // send: gradient circle
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .background(AppColors.userBubble)
                            .clickable { doSend() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
            // session list sheet
            if (showSessions) {
                ModalBottomSheet(
                    onDismissRequest = { showSessions = false },
                    containerColor = AppColors.surfaceHigh
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                    ) {
                        Button(
                            onClick = {
                                chatController.newSession()
                                showSessions = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent1),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("New session")
                        }
                        Spacer(Modifier.height(8.dp))
                        if (sessions.isEmpty()) {
                            Text(
                                "No sessions yet",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        sessions.forEach { s ->
                            val isCurrent = s.id == currentSessionId
                            val dateStr = java.text.SimpleDateFormat(
                                "yyyy/MM/dd HH:mm", java.util.Locale.US
                            ).format(java.util.Date(s.updatedAt))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chatController.switchToSession(s.id)
                                        showSessions = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        s.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isCurrent) AppColors.accent2 else AppColors.textPrimary,
                                        fontWeight = if (isCurrent) FontWeight.Bold else null,
                                        maxLines = 1
                                    )
                                    Text(
                                        "$dateStr • ${s.count} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { chatController.deleteSession(s.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete session",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AttachmentChip(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    val name = remember(uri) {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else "file"
            } else "file"
        } ?: "file"
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📎 ${name.take(18)}",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.textPrimary
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val context = LocalContext.current

    val clipboard = LocalClipboardManager.current

    if (isTool) {
        // tool pill: colored side bar + label + result preview
        val parts = message.content.split("\n│ ", limit = 2)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AppColors.toolBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 3.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(AppColors.accent2)
                    )
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text(
                            text = parts[0],
                            style = MaterialTheme.typography.labelMedium,
                            color = AppColors.textPrimary
                        )
                        if (parts.size > 1) {
                            Text(
                                text = "│ " + parts[1],
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.textFaint,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
        return
    }

    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .padding(vertical = 3.dp)
                .combinedClickable(
                    onClick = { },
                    onLongClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        android.widget.Toast.makeText(context, "Message copied ✓", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
        ) {
            Text(
                text = if (isUser) "You" else "Galahad-X",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.textFaint,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
            )
            Surface(
                shape = if (isUser) RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
                        else RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp),
                color = if (isUser) Color.Transparent else AppColors.surface,
                border = if (isUser) null else androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                shadowElevation = if (isUser) 0.dp else 2.dp
            ) {
                Box(
                    modifier = if (isUser) Modifier.background(AppColors.userBubble) else Modifier
                ) {
                Column(modifier = Modifier.padding(13.dp)) {
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            color = if (isUser) Color.White else AppColors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // attachments
                    message.attachments.forEach { att ->
                        Spacer(Modifier.height(6.dp))
                        AttachmentView(att) {
                            // tap -> open the file
                            try {
                                val f = java.io.File(att.path)
                                if (f.exists()) {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".fileprovider", f
                                    )
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, att.mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
                }
        }
    }
}

@Composable
fun AttachmentView(att: Attachment, onClick: () -> Unit) {
    if (att.isImage) {
        val bitmap = remember(att.path) {
            try {
                BitmapFactory.decodeFile(att.path)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = att.name,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClick() },
                contentScale = ContentScale.FillWidth
            )
        } else {
            FileCard(att, onClick)
        }
    } else {
        FileCard(att, onClick)
    }
}

@Composable
fun FileCard(att: Attachment, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppColors.surfaceHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📄", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(att.name, style = MaterialTheme.typography.bodySmall, color = AppColors.textPrimary)
                Text(
                    "${att.mime} • ${(att.path.let { java.io.File(it).length() } / 1024)} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.textFaint
                )
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(3) { index ->
                    var scale by remember { mutableStateOf(0f) }
                    LaunchedEffect(index) {
                        kotlinx.coroutines.delay(index * 200L)
                        while (true) {
                            scale = 1f
                            kotlinx.coroutines.delay(300)
                            scale = 0.5f
                            kotlinx.coroutines.delay(300)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size((7 * scale).dp.coerceAtLeast(4.dp))
                            .clip(RoundedCornerShape(50))
                            .background(AppColors.accent2)
                    )
                }
            }
        }
    }
}
