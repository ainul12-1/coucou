package com.coucou.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.coucou.android.R
import com.coucou.android.app.AppModel
import com.coucou.android.core.CalloutKind
import com.coucou.android.core.ChatMessage
import com.coucou.android.core.ChatReasons
import com.coucou.android.core.ChatRole
import com.coucou.android.core.ChatSession
import com.coucou.android.core.ChatStatus
import com.coucou.android.core.ChatTab
import com.coucou.android.core.ChatTabState
import com.coucou.android.core.IconKind
import com.coucou.android.core.MarkdownLite
import com.coucou.android.core.MdBlock
import com.coucou.android.core.MdSpan
import com.coucou.android.core.Screen
import com.coucou.android.core.Spacing
import com.coucou.android.core.TypeScale
import com.coucou.android.link.Protocol
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URL

private val URL_REGEX = Regex("""https?://[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")
private val MD_LINK_REGEX = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")

private data class SlashCommand(val cmd: String, val desc: String, val badge: String = "/")

private val ALL_SLASH_COMMANDS = listOf(
    SlashCommand("/goal", "Jalankan tugas panjang sampai tuntas tanpa henti", "GOAL"),
    SlashCommand("/plan", "Perencanaan arsitektur & implementasi bertahap", "PLAN"),
    SlashCommand("/grill-me", "Wawancara interaktif untuk mematangkan rancangan", "GRILL"),
    SlashCommand("/learn", "Ajarkan instruksi & preferensi baru ke memori agent", "LEARN"),
    SlashCommand("/ss", "Ambil screenshot tangkapan layar IDE laptop realtime", "SCREEN"),
    SlashCommand("/status", "Cek status koneksi, model AI, dan bridge IDE", "STATUS"),
    SlashCommand("/window", "Daftar & ganti jendela aktif Antigravity IDE", "WINDOW"),
    SlashCommand("/agents", "Pantau daftar subagent yang sedang aktif", "AGENTS"),
    SlashCommand("/latest", "Tampilkan kembali jawaban respon terakhir dari AI", "LATEST"),
    SlashCommand("/clear", "Bersihkan seluruh tampilan percakapan chat ini", "CLEAR"),
    SlashCommand("/decide allow", "Izinkan permintaan akses/perintah IDE laptop", "ALLOW"),
    SlashCommand("/decide deny", "Tolak permintaan akses/perintah IDE laptop", "DENY")
)

private fun extractUrls(text: String): List<String> {
    val list = mutableListOf<String>()
    MD_LINK_REGEX.findAll(text).forEach { m ->
        list.add(m.groupValues[2])
    }
    URL_REGEX.findAll(text).forEach { m ->
        val url = m.value.trimEnd('.', ',', ';', ')', '>')
        if (!list.contains(url)) {
            list.add(url)
        }
    }
    return list.distinct()
}

@Composable
private fun reasonText(code: String?): String = stringResource(
    when (ChatReasons.key(code)) {
        "off" -> R.string.chat_err_off
        "not_allowed" -> R.string.chat_err_not_allowed
        "busy" -> R.string.chat_err_busy
        "rate" -> R.string.chat_err_rate
        "too_long" -> R.string.chat_err_too_long
        "empty" -> R.string.chat_err_empty
        "no_key" -> R.string.chat_err_no_key
        "unreachable" -> R.string.chat_err_unreachable
        "auth" -> R.string.chat_err_auth
        "provider" -> R.string.chat_err_provider
        "canceled" -> R.string.chat_err_canceled
        "connection" -> R.string.chat_err_connection
        "interrupted" -> R.string.chat_err_interrupted
        else -> R.string.chat_err_internal
    },
)

internal fun chatStateTitle(state: ChatTabState): Int = when (state) {
    ChatTabState.NOT_PAIRED -> R.string.chat_off_not_paired_title
    ChatTabState.NOT_CONNECTED -> R.string.chat_off_offline_title
    ChatTabState.CHAT_OFF -> R.string.chat_off_title
    ChatTabState.NO_MODELS -> R.string.chat_nomodel_title
    ChatTabState.READY -> R.string.chat_title
}

internal fun chatStateBody(state: ChatTabState): Int = when (state) {
    ChatTabState.NOT_PAIRED -> R.string.chat_off_not_paired_body
    ChatTabState.NOT_CONNECTED -> R.string.chat_off_offline_body
    ChatTabState.CHAT_OFF, ChatTabState.NO_MODELS -> R.string.chat_off_hint
    ChatTabState.READY -> R.string.chat_title
}

@Composable
private fun ChatEmpty(state: ChatTabState, onHome: () -> Unit, onSettings: () -> Unit) {
    val t = tokens()
    val engine = remember { MochiEngine({ android.os.SystemClock.elapsedRealtimeNanos() / 1e6 }).apply { setState(BotState.SLEEPING, force = true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        ScreenTitle(stringResource(R.string.chat_title), null)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            MochiView(engine, Modifier.size(120.dp))
            Text(
                stringResource(chatStateTitle(state)), Modifier.padding(top = 16.dp),
                style = TypeScale.TITLE.style(t.text.c()).copy(textAlign = TextAlign.Center),
            )
            Text(
                stringResource(chatStateBody(state)), Modifier.padding(top = 8.dp, bottom = 20.dp),
                style = TypeScale.BODY.style(t.textDim.c()).copy(textAlign = TextAlign.Center),
            )
            when (ChatTab.action(state)) {
                Screen.HOME -> PillButton(stringResource(R.string.chat_go_pair), onHome, Modifier.fillMaxWidth(), PillKind.PRIMARY)
                Screen.SETTINGS -> PillButton(stringResource(R.string.chat_go_settings), onSettings, Modifier.fillMaxWidth(), PillKind.PRIMARY)
                else -> {}
            }
        }
    }
}

/**
 * Enhanced Chat Screen with:
 * - 1. History (Riwayat) drawer dialog button right on top
 * - 2. New Chat (Chat Baru) button right on top
 * - 3. Multi-Photo picker (Max 5) with preview strip and delete tags
 * - 4. Clickable links with vibrant blue and WhatsApp-style Meta Preview Cards
 * - 5. Telegram Bot style Slash Command autocomplete popup (/goal, /plan, /ss, etc.)
 */
@Composable
fun ChatScreen(model: AppModel, onHome: () -> Unit, onSettings: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var refusal by remember { mutableStateOf<ChatSession.Refusal?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var showSessionsDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val messages = model.chatMessages

    val context = LocalContext.current
    var uploadingPhoto by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var selectedPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }

    // Multi-photo picker from gallery (Max 5 photos)
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val combined = (selectedPhotos + uris).distinct().take(5)
            selectedPhotos = combined
            uploadError = null
        }
    }

    // Direct camera photo capture launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraPhotoUri != null) {
            val uri = cameraPhotoUri!!
            if (selectedPhotos.size < 5) {
                selectedPhotos = (selectedPhotos + uri).take(5)
                uploadError = null
            } else {
                Toast.makeText(context, "Maksimal 5 foto telah dipilih", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun takeCameraPhoto() {
        if (selectedPhotos.size >= 5) {
            Toast.makeText(context, "Maksimal 5 foto telah dipilih", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val photoFile = File.createTempFile("coucou_cam_${System.currentTimeMillis()}_", ".jpg", context.cacheDir)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            cameraPhotoUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuka kamera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        model.chatRefreshModels()
        model.loadBridgeSessions()
    }

    // Auto-scroll on new tokens or messages
    val lastLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLength) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val tabState = model.chatTabState
    if (tabState != ChatTabState.READY && messages.isEmpty()) {
        ChatEmpty(tabState, onHome, onSettings)
        return
    }

    val t = tokens()

    Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        // Top Header: Prominent [Riwayat] & [Chat Baru] buttons
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Active session info
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        model.loadBridgeSessions()
                        showSessionsDialog = true
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(if (model.chatAvailable) Color(0xFF34D399) else t.textDim.c())
                )
                Text(
                    model.activeSessionTitle ?: "Sesi Antigravity",
                    style = TypeScale.HEADLINE.style(t.text.c()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
            }

            // Top Action Buttons: Riwayat, Chat Baru, Clear
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // 1. Dedicated Riwayat (History) Button
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(t.panel2.c())
                        .border(1.dp, t.line.c(), RoundedCornerShape(14.dp))
                        .clickable {
                            model.loadBridgeSessions()
                            showSessionsDialog = true
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Riwayat", style = TypeScale.LABEL.style(t.text.c()), fontWeight = FontWeight.SemiBold)
                }

                // 2. Dedicated Chat Baru (New Chat) Button
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(t.primaryButton.c().copy(alpha = 0.2f))
                        .border(1.dp, t.primaryButton.c().copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .clickable {
                            model.startNewBridgeSession()
                            selectedPhotos = emptyList()
                            text = ""
                            Toast.makeText(context, "Sesi chat baru dimulai", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("+ Chat Baru", style = TypeScale.LABEL.style(t.primaryButton.c()), fontWeight = FontWeight.Bold)
                }

                if (messages.isNotEmpty()) {
                    Text(
                        stringResource(R.string.chat_clear),
                        Modifier
                            .clickable(role = Role.Button) { confirmClear = true }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        style = TypeScale.LABEL.style(t.textDim.c()), maxLines = 1,
                    )
                }
            }
        }

        if (!model.chatAvailable) {
            Panel(Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(chatStateTitle(tabState)), style = TypeScale.HEADLINE.style(tokens().text.c()))
                    Text(stringResource(chatStateBody(tabState)), style = TypeScale.SECONDARY.style(tokens().textDim.c()))
                }
            }
        }

        // Messages List
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(Gutter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_empty),
                        style = TypeScale.BODY.style(tokens().textDim.c()).copy(textAlign = TextAlign.Center),
                    )
                    Text(
                        "Ketik prompt, kirim hingga 5 foto, atau ketik '/' untuk rekomendasi bot perintah.",
                        style = TypeScale.SECONDARY.style(tokens().textFaint.c()).copy(textAlign = TextAlign.Center),
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    items(messages, key = { it.id }) { m -> Bubble(m) }
                }
            }
        }

        // Uploading indicator
        if (uploadingPhoto) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(t.panel2.c())
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = t.primaryButton.c())
                Text("Mengunggah foto ke Antigravity IDE...", style = TypeScale.SECONDARY.style(t.text.c()))
            }
        }

        // 5. Telegram Bot Slash Command Recommendation Popup
        if (text.startsWith("/")) {
            SlashCommandSuggestionsCard(
                query = text,
                onSelectCommand = { cmd -> text = "$cmd " }
            )
        }

        // 3. Multi-Photo Selected Thumbnail Strip (Max 5)
        if (selectedPhotos.isNotEmpty()) {
            SelectedPhotosStrip(
                photos = selectedPhotos,
                onRemovePhoto = { uri -> selectedPhotos = selectedPhotos.filter { it != uri } },
                onAddMore = { showPhotoSourceDialog = true },
                onClearAll = { selectedPhotos = emptyList() }
            )
        }

        // Quick Actions Horizontal Chips Bar
        QuickActionBar(
            chatBusy = model.chatBusy,
            onQuickAction = { cmd -> model.chatSend(cmd) },
            onStop = { model.chatCancel() }
        )

        // Bottom Input Composer
        Composer(
            model = model,
            text = text,
            refusal = refusal,
            uploadingPhoto = uploadingPhoto,
            uploadError = uploadError,
            selectedPhotosCount = selectedPhotos.size,
            onText = { text = it; refusal = null },
            onSend = {
                if (selectedPhotos.isNotEmpty()) {
                    uploadingPhoto = true
                    uploadError = null
                    val currentText = text
                    val currentPhotos = selectedPhotos
                    model.uploadMultiplePhotos(context, currentPhotos, currentText) { success, err ->
                        uploadingPhoto = false
                        if (success) {
                            selectedPhotos = emptyList()
                            text = ""
                            refusal = null
                            Toast.makeText(context, "${currentPhotos.size} foto terkirim!", Toast.LENGTH_SHORT).show()
                        } else {
                            uploadError = err
                            Toast.makeText(context, "Gagal upload: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val r = model.chatSend(text)
                    if (r == null) { text = ""; refusal = null } else refusal = r
                }
            },
            onAttachClick = { showPhotoSourceDialog = true },
        )
    }

    // Photo Source Choice Dialog (Camera vs Gallery)
    if (showPhotoSourceDialog) {
        PhotoSourceDialog(
            onDismiss = { showPhotoSourceDialog = false },
            onCamera = { takeCameraPhoto() },
            onGallery = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        )
    }

    // Sessions Selection Dialog (History)
    if (showSessionsDialog) {
        SessionsDialog(
            model = model,
            onDismiss = { showSessionsDialog = false },
            onSelectSession = { session ->
                model.loadSessionMessages(session.id, session.title)
                showSessionsDialog = false
            },
            onNewChat = {
                model.startNewBridgeSession()
                selectedPhotos = emptyList()
                text = ""
                showSessionsDialog = false
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.chat_clear_title)) },
            text = { Text(stringResource(R.string.chat_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; model.chatClear() }) {
                    Text(stringResource(R.string.chat_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * 5. Telegram Bot style Slash Command Suggestions Card.
 */
@Composable
private fun SlashCommandSuggestionsCard(
    query: String,
    onSelectCommand: (String) -> Unit
) {
    val t = tokens()
    val cleanQuery = query.trim().lowercase()
    val filtered = remember(cleanQuery) {
        if (!cleanQuery.startsWith("/")) emptyList()
        else {
            val q = cleanQuery.removePrefix("/")
            ALL_SLASH_COMMANDS.filter {
                it.cmd.removePrefix("/").lowercase().startsWith(q) ||
                it.cmd.lowercase().contains(q) ||
                it.desc.lowercase().contains(q)
            }
        }
    }

    if (filtered.isEmpty()) return

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(t.panel.c())
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(vertical = 6.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Perintah Bot (${filtered.size})",
                    style = TypeScale.LABEL.style(Color(0xFF38BDF8)),
                    fontWeight = FontWeight.Bold
                )
            }
            Text("Pilih / Ketuk", style = TypeScale.LABEL.style(t.textDim.c()), fontSize = 11.sp)
        }

        HorizontalDivider(color = t.line.c().copy(alpha = 0.5f))

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
            items(filtered, key = { it.cmd }) { cmd ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCommand(cmd.cmd) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(cmd.badge, style = TypeScale.LABEL.style(Color(0xFF38BDF8)), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            cmd.cmd,
                            style = TypeScale.LABEL.style(Color(0xFF38BDF8)),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            cmd.desc,
                            style = TypeScale.SECONDARY.style(t.textDim.c()),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * 3. Horizontal strip displaying selected photos (Max 5) with thumbnail and remove badge.
 */
@Composable
private fun SelectedPhotosStrip(
    photos: List<Uri>,
    onRemovePhoto: (Uri) -> Unit,
    onAddMore: () -> Unit,
    onClearAll: () -> Unit
) {
    val t = tokens()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.panel2.c())
            .border(1.dp, t.line.c(), RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Foto Terpilih (${photos.size}/5)",
                style = TypeScale.LABEL.style(t.text.c()),
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (photos.size < 5) {
                    Text(
                        "+ Tambah",
                        style = TypeScale.LABEL.style(t.primaryButton.c()),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onAddMore() }
                    )
                }
                Text(
                    "Hapus Semua",
                    style = TypeScale.LABEL.style(t.danger.c()),
                    modifier = Modifier.clickable { onClearAll() }
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            photos.forEach { uri ->
                Box(Modifier.size(64.dp)) {
                    UriThumbnail(
                        uri = uri,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, t.line.c(), RoundedCornerShape(10.dp))
                    )
                    // Delete button in top-right corner
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE11D48))
                            .clickable { onRemovePhoto(uri) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("X", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Thumbnail loader for local Uri using ContentResolver & BitmapFactory.
 */
@Composable
private fun UriThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bmp = BitmapFactory.decodeStream(stream, null, opts)
                    withContext(Dispatchers.Main) { bitmap = bmp }
                }
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Foto",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier.background(Color(0xFF2A2A36)), contentAlignment = Alignment.Center) {
            Text("FOTO", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Quick Action Bar with tap-to-run chips for common IDE actions.
 */
@Composable
private fun QuickActionBar(
    chatBusy: Boolean,
    onQuickAction: (String) -> Unit,
    onStop: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip("Layar IDE", onClick = { onQuickAction("/ss") })
        ActionChip("Status", onClick = { onQuickAction("/status") })
        ActionChip("Jawaban", onClick = { onQuickAction("/latest") })
        if (chatBusy) {
            ActionChip("Hentikan", isDestructive = true, onClick = onStop)
        }
        ActionChip("Izinkan", isAccent = true, onClick = { onQuickAction("/decide allow") })
        ActionChip("Tolak", isDestructive = true, onClick = { onQuickAction("/decide deny") })
    }
}

@Composable
private fun ActionChip(
    label: String,
    isAccent: Boolean = false,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val t = tokens()
    val bg = when {
        isDestructive -> t.danger.c().copy(alpha = 0.15f)
        isAccent -> t.primaryButton.c().copy(alpha = 0.2f)
        else -> t.panel2.c()
    }
    val fg = when {
        isDestructive -> t.danger.c()
        isAccent -> t.primaryButton.c()
        else -> t.text.c()
    }

    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, style = TypeScale.LABEL.style(fg), fontWeight = FontWeight.Medium)
    }
}

/**
 * 1 & 2. Modal dialog displaying past Antigravity IDE conversation sessions + New Chat button.
 */
@Composable
private fun SessionsDialog(
    model: AppModel,
    onDismiss: () -> Unit,
    onSelectSession: (com.coucou.android.app.BridgeSession) -> Unit,
    onNewChat: () -> Unit,
) {
    val t = tokens()
    val sessions = model.bridgeSessions

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(t.panel.c())
                .border(1.dp, t.line.c(), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Riwayat Percakapan", style = TypeScale.HEADLINE.style(t.text.c()))
                TextButton(onClick = onDismiss) { Text("Tutup", color = t.textDim.c()) }
            }

            Spacer(Modifier.height(8.dp))

            // 2. Button: Start New Chat
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.primaryButton.c().copy(alpha = 0.12f))
                    .border(1.dp, t.primaryButton.c().copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable { onNewChat() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("+", fontSize = 18.sp, color = t.primaryButton.c(), fontWeight = FontWeight.Bold)
                Column {
                    Text("Percakapan Baru", style = TypeScale.BODY.style(t.primaryButton.c()), fontWeight = FontWeight.Bold)
                    Text("Mulai sesi obrolan baru dengan IDE", style = TypeScale.SECONDARY.style(t.textDim.c()))
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = t.line.c())
            Spacer(Modifier.height(8.dp))

            if (model.isLoadingSessions) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = t.primaryButton.c())
                }
            } else if (sessions.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text("Belum ada riwayat sesi yang ditemukan.", style = TypeScale.BODY.style(t.textDim.c()))
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        val isCurrent = s.id == model.activeSessionId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isCurrent) t.panel2.c() else Color.Transparent)
                                .border(1.dp, if (isCurrent) t.primaryButton.c() else t.line.c().copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable { onSelectSession(s) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title,
                                    style = TypeScale.BODY.style(t.text.c()),
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${s.dateStr}  •  ${s.messageCount} pesan",
                                    style = TypeScale.SECONDARY.style(t.textDim.c()),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            if (isCurrent) {
                                Box(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(t.primaryButton.c())
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Aktif", style = TypeScale.LABEL.style(t.onPrimaryButton.c()), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chat Bubble with Clickable Links and WhatsApp-style Meta Link Preview cards.
 */
@Composable
private fun Bubble(m: ChatMessage) {
    val urls = remember(m.text) { extractUrls(m.text) }

    if (m.role == ChatRole.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer {
                val annotated = remember(m.text) {
                    styled(listOf(MdSpan(m.text)), Color(0xFF2A2A36))
                }
                ClickableUrlText(
                    annotatedText = annotated,
                    style = TypeScale.BODY.style(tokens().text.c()),
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                        .background(tokens().panel2.c())
                        .border(1.dp, tokens().line.c(), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            // 4. WhatsApp-style Meta Link Preview Card
            for (url in urls.take(2)) {
                LinkPreviewCard(url = url)
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.text.isEmpty() && m.status == ChatStatus.STREAMING) {
            TypingDots()
        } else if (m.text.isNotEmpty()) {
            SelectionContainer {
                SmoothTypingMarkdown(text = m.text, isStreaming = m.status == ChatStatus.STREAMING)
            }
            // 4. WhatsApp-style Meta Link Preview Card for Assistant
            for (url in urls.take(2)) {
                LinkPreviewCard(url = url)
            }
        }
        if (m.status == ChatStatus.FAILED) {
            Text(reasonText(m.reason), style = TypeScale.SECONDARY.style(tokens().danger.c()))
        }
    }
}

/**
 * 4. WhatsApp-style Meta Link Preview Card with Globe/Image icon, Domain badge, and Open action.
 */
@Composable
private fun LinkPreviewCard(url: String) {
    val t = tokens()
    val uriHandler = LocalUriHandler.current

    val host = remember(url) {
        try {
            val u = URI(url)
            u.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    val isImg = remember(url) {
        val l = url.lowercase()
        l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".webp") || l.contains("/screenshot")
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.panel2.c())
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable {
                try {
                    uriHandler.openUri(url)
                } catch (e: Exception) {}
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF38BDF8).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (isImg) "IMG" else "URL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    host,
                    style = TypeScale.LABEL.style(Color(0xFF38BDF8)),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("• Tautan Web", style = TypeScale.LABEL.style(t.textDim.c()), fontSize = 10.sp)
            }
            Text(
                url,
                style = TypeScale.SECONDARY.style(t.textDim.c()),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            Modifier
                .clip(CircleShape)
                .background(Color(0xFF38BDF8).copy(alpha = 0.2f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("Buka", style = TypeScale.LABEL.style(Color(0xFF38BDF8)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Super smooth typewriter progression ticker for AI answers.
 */
@Composable
private fun SmoothTypingMarkdown(text: String, isStreaming: Boolean) {
    if (!isStreaming) {
        MarkdownText(text, isTyping = false)
        return
    }

    var revealedChars by remember { mutableIntStateOf(text.length.coerceAtMost(20)) }

    LaunchedEffect(text, isStreaming) {
        while (revealedChars < text.length) {
            val remaining = text.length - revealedChars
            val step = when {
                remaining > 120 -> 12
                remaining > 50 -> 6
                remaining > 20 -> 3
                else -> 1
            }
            revealedChars = (revealedChars + step).coerceAtMost(text.length)
            delay(12L)
        }
    }

    val currentText = text.take(revealedChars)
    MarkdownText(currentText, isTyping = isStreaming && revealedChars < text.length)
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "dots",
    )
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val on = phase.toInt() == i
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(tokens().textDim.c().copy(alpha = if (on) 1f else 0.35f)),
            )
        }
    }
}

/**
 * High-fidelity Markdown renderer with Clickable URLs and rich cards.
 */
@Composable
private fun MarkdownText(text: String, isTyping: Boolean) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val t = tokens()
    val body = TypeScale.BODY.style(t.text.c())

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Para -> {
                    ClickableUrlText(styled(b.spans, t.panel2.c()), style = body)
                }

                is MdBlock.Heading -> {
                    val headingStyle = when (b.level) {
                        1 -> TypeScale.TITLE.style(t.text.c()).copy(fontWeight = FontWeight.Bold)
                        2 -> TypeScale.HEADLINE.style(t.text.c()).copy(fontWeight = FontWeight.Bold)
                        else -> TypeScale.BODY.style(t.primaryButton.c()).copy(fontWeight = FontWeight.SemiBold)
                    }
                    ClickableUrlText(styled(b.spans, t.panel2.c()), style = headingStyle, modifier = Modifier.padding(top = 4.dp))
                }

                is MdBlock.Callout -> {
                    CalloutCard(b)
                }

                is MdBlock.Bullet -> {
                    Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val bulletMarker = if (b.order != null) "${b.order}." else "•"
                        Text(bulletMarker, style = body.copy(color = t.primaryButton.c(), fontWeight = FontWeight.Bold))
                        ClickableUrlText(styled(b.spans, t.panel2.c()), style = body, modifier = Modifier.weight(1f))
                    }
                }

                is MdBlock.Code -> {
                    CodeCard(code = b.text, lang = b.lang)
                }

                is MdBlock.Image -> {
                    RemoteImageCard(url = b.url, alt = b.alt)
                }

                is MdBlock.Divider -> {
                    HorizontalDivider(color = t.line.c(), modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }

        if (isTyping) {
            val cursorTransition = rememberInfiniteTransition(label = "cursor")
            val cursorAlpha by cursorTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "c"
            )
            Box(
                Modifier
                    .size(width = 8.dp, height = 18.dp)
                    .alpha(cursorAlpha)
                    .clip(RoundedCornerShape(2.dp))
                    .background(t.primaryButton.c())
            )
        }
    }
}

/**
 * Composable rendering text with clickable link annotations.
 */
@Composable
private fun ClickableUrlText(
    annotatedText: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = annotatedText,
        style = style,
        modifier = modifier,
        onClick = { offset ->
            annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        uriHandler.openUri(annotation.item)
                    } catch (e: Exception) {
                        // Ignore invalid URIs
                    }
                }
        }
    )
}

/**
 * Rich Callout Card component: eliminates raw `>` and renders a stylish tinted container with icon badge.
 */
@Composable
private fun CalloutCard(callout: MdBlock.Callout) {
    val t = tokens()
    val (accentColor, titleText) = when (callout.kind) {
        CalloutKind.NOTE -> Pair(Color(0xFF38BDF8), callout.title ?: "CATATAN")
        CalloutKind.TIP -> Pair(Color(0xFF34D399), callout.title ?: "TIP")
        CalloutKind.IMPORTANT -> Pair(Color(0xFFA78BFA), callout.title ?: "PENTING")
        CalloutKind.WARNING -> Pair(Color(0xFFFBBF24), callout.title ?: "PERINGATAN")
        CalloutKind.CAUTION -> Pair(Color(0xFFF87171), callout.title ?: "PERHATIAN")
        CalloutKind.QUOTE -> Pair(t.textDim.c(), callout.title ?: "KUTIPAN")
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(t.panel.c())
            .border(1.dp, t.line.c(), RoundedCornerShape(10.dp))
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accentColor)
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(callout.kind.icon, fontSize = 14.sp)
                Text(titleText, style = TypeScale.LABEL.style(accentColor), fontWeight = FontWeight.Bold)
            }
            ClickableUrlText(styled(callout.spans, t.panel2.c()), style = TypeScale.BODY.style(t.text.c()))
        }
    }
}

/**
 * Rich Code Block component with language badge and Copy button.
 */
@Composable
private fun CodeCard(code: String, lang: String) {
    val t = tokens()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141419))
            .border(1.dp, t.line.c().copy(alpha = 0.5f), RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1F1F27))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                (if (lang.isNotEmpty()) lang else "CODE").uppercase(),
                style = TypeScale.LABEL.style(t.textDim.c()),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Salin",
                style = TypeScale.LABEL.style(t.primaryButton.c()),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, "Kode disalin!", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Text(
            code,
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            style = TypeScale.MONO.style(Color(0xFFE2E8F0)).copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        )
    }
}

/**
 * Inline Image Card with asynchronous download and tap-to-zoom fullscreen dialog.
 */
@Composable
private fun RemoteImageCard(url: String, alt: String) {
    val t = tokens()
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var loadError by remember(url) { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val stream = URL(url).openStream()
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                withContext(Dispatchers.Main) {
                    bitmap = bmp
                    loading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading = false
                    loadError = true
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.panel2.c())
            .border(1.dp, t.line.c(), RoundedCornerShape(14.dp))
            .clickable(enabled = bitmap != null) { fullscreen = true }
    ) {
        when {
            loading -> {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = t.primaryButton.c())
                }
            }
            bitmap != null -> {
                Column {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = alt,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        contentScale = ContentScale.Crop
                    )
                    if (alt.isNotEmpty() && alt != "Screenshot/Gambar") {
                        Text(
                            alt,
                            style = TypeScale.SECONDARY.style(t.textDim.c()),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            loadError -> {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text("Gagal memuat gambar ($url)", style = TypeScale.SECONDARY.style(t.danger.c()))
                }
            }
        }
    }

    if (fullscreen && bitmap != null) {
        Dialog(onDismissRequest = { fullscreen = false }) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clickable { fullscreen = false },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = alt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(12.dp))
                Text("Ketuk di mana saja untuk menutup", style = TypeScale.LABEL.style(Color.White))
            }
        }
    }
}

/**
 * Converts spans and raw URLs into an AnnotatedString with blue underline styling and URL tags.
 */
private fun styled(spans: List<MdSpan>, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        if (s.linkUrl != null) {
            val style = SpanStyle(
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) codeBackground else Color.Unspecified,
                color = Color(0xFF38BDF8),
                textDecoration = TextDecoration.Underline,
            )
            pushStringAnnotation(tag = "URL", annotation = s.linkUrl)
            withStyle(style) { append(s.text) }
            pop()
        } else {
            val text = s.text
            val matches = URL_REGEX.findAll(text).toList()
            if (matches.isEmpty()) {
                val style = SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontFamily = if (s.code) FontFamily.Monospace else null,
                    background = if (s.code) codeBackground else Color.Unspecified,
                )
                withStyle(style) { append(text) }
            } else {
                var lastIdx = 0
                for (m in matches) {
                    if (m.range.first > lastIdx) {
                        val before = text.substring(lastIdx, m.range.first)
                        val style = SpanStyle(
                            fontWeight = if (s.bold) FontWeight.Bold else null,
                            fontFamily = if (s.code) FontFamily.Monospace else null,
                            background = if (s.code) codeBackground else Color.Unspecified,
                        )
                        withStyle(style) { append(before) }
                    }
                    val url = m.value.trimEnd('.', ',', ';', ')')
                    val style = SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8),
                        textDecoration = TextDecoration.Underline,
                    )
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(style) { append(url) }
                    pop()
                    lastIdx = m.range.first + url.length
                }
                if (lastIdx < text.length) {
                    val remaining = text.substring(lastIdx)
                    val style = SpanStyle(
                        fontWeight = if (s.bold) FontWeight.Bold else null,
                        fontFamily = if (s.code) FontFamily.Monospace else null,
                        background = if (s.code) codeBackground else Color.Unspecified,
                    )
                    withStyle(style) { append(remaining) }
                }
            }
        }
    }
}

/**
 * Composer with multi-photo count, camera & gallery picker, send button, and sleek model selector.
 */
@Composable
private fun Composer(
    model: AppModel,
    text: String,
    refusal: ChatSession.Refusal?,
    uploadingPhoto: Boolean,
    uploadError: String?,
    selectedPhotosCount: Int,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
) {
    val limit = Protocol.CHAT_MAX_TEXT
    val length = text.trim().length
    val t = tokens()

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Sleek sub-bar above input: Compact Model Selector & Attached Photos Indicator
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModelChip(model)
            if (selectedPhotosCount > 0) {
                Text(
                    "$selectedPhotosCount/5 foto terlampir",
                    style = TypeScale.SECONDARY.style(Color(0xFF38BDF8)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Modern Attachment Button (+ icon for Camera and Gallery)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(t.panel2.c())
                    .border(1.dp, t.line.c(), RoundedCornerShape(16.dp))
                    .clickable(enabled = model.chatAvailable && !uploadingPhoto, role = Role.Button, onClick = onAttachClick),
                contentAlignment = Alignment.Center,
            ) {
                if (uploadingPhoto) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = t.primaryButton.c(), strokeWidth = 2.dp)
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "+",
                            color = t.text.c(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        if (selectedPhotosCount > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF38BDF8)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("$selectedPhotosCount", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = text, onValueChange = { onText(it.take(limit + 200)) }, modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_placeholder), color = t.textFaint.c(), fontSize = 14.sp) },
                maxLines = 4, shape = RoundedCornerShape(18.dp), enabled = model.chatAvailable,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = t.panel.c(), unfocusedContainerColor = t.panel.c(), disabledContainerColor = t.panel.c(),
                    focusedBorderColor = Color(0xFF38BDF8).copy(alpha = 0.6f), unfocusedBorderColor = t.line.c(), disabledBorderColor = t.line.c(),
                    focusedTextColor = t.text.c(), unfocusedTextColor = t.text.c(), cursorColor = Color(0xFF38BDF8),
                ),
            )

            if (model.chatBusy) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.2f))
                        .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { model.chatCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFEF4444)))
                }
            } else {
                val canSend = model.chatAvailable && ((length in 1..limit) || selectedPhotosCount > 0) && !uploadingPhoto
                val send = stringResource(R.string.chat_send)
                Box(
                    Modifier
                        .alpha(if (canSend) 1f else 0.45f)
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (canSend) t.primaryButton.c() else t.panel2.c())
                        .border(1.dp, if (canSend) Color.Transparent else t.line.c(), RoundedCornerShape(16.dp))
                        .clickable(enabled = canSend, role = Role.Button, onClick = onSend)
                        .semantics { contentDescription = send },
                    contentAlignment = Alignment.Center,
                ) {
                    CoucouIcon(IconKind.SEND, tint = if (canSend) t.onPrimaryButton.c() else t.textDim.c(), size = 20.dp)
                }
            }
        }

        if (uploadError != null) {
            Text("Upload failed: $uploadError", style = TypeScale.SECONDARY.style(t.danger.c()))
        }
        val note = when {
            refusal == ChatSession.Refusal.TOO_LONG || length > limit -> stringResource(R.string.chat_ref_too_long)
            refusal == ChatSession.Refusal.NO_MODEL -> stringResource(R.string.chat_ref_no_model)
            refusal == ChatSession.Refusal.BUSY -> stringResource(R.string.chat_ref_busy)
            refusal == ChatSession.Refusal.OFFLINE -> stringResource(R.string.chat_ref_offline)
            else -> null
        }
        if (note != null) Text(note, style = TypeScale.SECONDARY.style(t.danger.c()))
        else if (length > limit * 9 / 10) {
            Text(stringResource(R.string.chat_count, length, limit), style = TypeScale.SECONDARY.style(t.textDim.c()))
        }
    }
}

/** The model selector: sleek compact pill showing clean model name. */
@Composable
private fun ModelChip(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val models = model.chatModels
    val current = models.firstOrNull { it.id == model.chatModel }
    val t = tokens()
    val rawName = current?.label ?: "antigravity-ide"
    val cleanName = if (rawName.contains("·")) rawName.substringAfter("·").trim() else rawName.replace("Custom server", "").trim().ifEmpty { "antigravity-ide" }

    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                .clickable(enabled = models.size > 1, role = Role.Button) { open = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF34D399)))
            Text(
                cleanName,
                style = TypeScale.LABEL.style(Color(0xFFE2E8F0)).copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (models.size > 1) {
                CoucouIcon(IconKind.CHEVRON, tint = Color(0xFF94A3B8), size = 10.dp, modifier = Modifier.rotate(90f))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in models) {
                val itemLabel = if (m.label.contains("·")) m.label.substringAfter("·").trim() else m.label
                DropdownMenuItem(
                    text = { 
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (m.id == model.chatModel) Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF34D399)))
                            Text(itemLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { model.chatSelect(m.id); open = false },
                )
            }
        }
    }
}

/**
 * Modern Photo Source Dialog allowing Camera photo capture or Gallery multi-picker.
 */
@Composable
private fun PhotoSourceDialog(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    val t = tokens()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(t.panel.c())
                .border(1.dp, t.line.c(), RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Lampirkan Foto", style = TypeScale.TITLE.style(t.text.c()), fontWeight = FontWeight.Bold)
            Text("Pilih sumber foto untuk dikirim ke Antigravity IDE (maksimal 5 foto):", style = TypeScale.SECONDARY.style(t.textDim.c()))

            // Option 1: Kamera
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(t.panel2.c())
                    .border(1.dp, t.line.c(), RoundedCornerShape(14.dp))
                    .clickable { onDismiss(); onCamera() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF38BDF8).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("[CAM]", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                }
                Column(Modifier.weight(1f)) {
                    Text("Ambil Foto (Kamera)", style = TypeScale.LABEL.style(t.text.c()), fontWeight = FontWeight.Bold)
                    Text("Buka kamera HP dan foto langsung", style = TypeScale.SECONDARY.style(t.textDim.c()), fontSize = 12.sp)
                }
            }

            // Option 2: Galeri
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(t.panel2.c())
                    .border(1.dp, t.line.c(), RoundedCornerShape(14.dp))
                    .clickable { onDismiss(); onGallery() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34D399).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("[IMG]", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                }
                Column(Modifier.weight(1f)) {
                    Text("Pilih dari Galeri", style = TypeScale.LABEL.style(t.text.c()), fontWeight = FontWeight.Bold)
                    Text("Pilih hingga 5 foto dari penyimpanan", style = TypeScale.SECONDARY.style(t.textDim.c()), fontSize = 12.sp)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    "Batal",
                    style = TypeScale.LABEL.style(t.textDim.c()),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(8.dp)
                )
            }
        }
    }
}
