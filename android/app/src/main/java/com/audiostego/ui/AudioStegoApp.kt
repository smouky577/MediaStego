package com.audiostego.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

@Composable
fun AudioStegoApp(vm: StegoViewModel = viewModel()) {
    val hide by vm.hide.collectAsStateWithLifecycle()
    val extract by vm.extract.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onCoverPicked(uri)
    }
    val pickPayload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onPayloadPicked(uri)
    }
    val pickStego = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onStegoPicked(uri)
    }
    val createWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.embedTo(uri, vm.suggestedOutputName())
    }
    val createPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.embedTo(uri, vm.suggestedOutputName())
    }
    val createExtract = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveExtracted(uri)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice() else vm.showHideError("Няма достъп до микрофона")
    }

    Scaffold(containerColor = Ink) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("MediaStego", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
                Text("Скрит текст в звук или картина", color = PaperDim, fontSize = 13.sp)
            }
            WarningBanner()
            PrimaryTabRow(
                selectedTabIndex = tab,
                containerColor = Ink,
                contentColor = Amber,
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Скрий") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Извлечи") })
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (tab == 0) {
                    HidePane(
                        ui = hide,
                        vm = vm,
                        onPickCover = { pickCover.launch(carrierTypes) },
                        onPickFile = { pickPayload.launch(arrayOf("*/*")) },
                        onRecord = {
                            if (Build.VERSION.SDK_INT < 29) {
                                vm.showHideError("Записът на глас работи на Android 10 и по-нов (OGG/OPUS)")
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                vm.startVoice()
                            } else {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onSave = {
                            val problem = vm.hideBlocker()
                            if (problem != null) vm.showHideError(problem)
                            else if (hide.carrier == "PNG") createPng.launch(vm.suggestedOutputName())
                            else createWav.launch(vm.suggestedOutputName())
                        },
                        onShare = { uri -> shareAsFile(context, uri, hide.outputMime) },
                    )
                } else {
                    ExtractPane(
                        ui = extract,
                        vm = vm,
                        onPick = { pickStego.launch(carrierTypes) },
                        onSave = { name -> createExtract.launch(name) },
                        onShare = { uri ->
                            val name = extract.savedName.orEmpty()
                            val mime = if (name.endsWith(".png", ignoreCase = true)) "image/png" else "application/octet-stream"
                            shareAsFile(context, uri, mime)
                        },
                        onCopy = { text -> copyText(context, text) },
                    )
                }
            }
        }
    }
}

private val carrierTypes = arrayOf(
    "audio/wav",
    "audio/x-wav",
    "audio/*",
    "image/png",
    "application/octet-stream",
)

@Composable
private fun WarningBanner() {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color(0xFF2A2116))
            .border(1.dp, AmberDeep, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Пращай резултата като файл, не като гласово или снимка.", color = Amber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            "Чатовете компресират глас и снимки. WAV и PNG трябва да стигнат байт по байт, иначе скритото изчезва.",
            color = PaperDim,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun HidePane(
    ui: HideUi,
    vm: StegoViewModel,
    onPickCover: () -> Unit,
    onPickFile: () -> Unit,
    onRecord: () -> Unit,
    onSave: () -> Unit,
    onShare: (Uri) -> Unit,
) {
    SectionLabel("Носител")
    GhostButton(if (ui.coverName == null) "Избери WAV или PNG" else ui.coverName, enabled = !ui.busy, onClick = onPickCover)
    ui.coverInfo?.let { Note(it) }

    SectionLabel("Какво да се скрие")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip("Текст", ui.mode == PayloadMode.TEXT) { vm.setMode(PayloadMode.TEXT) }
        ModeChip("Файл", ui.mode == PayloadMode.FILE) { vm.setMode(PayloadMode.FILE) }
        ModeChip("Глас", ui.mode == PayloadMode.VOICE) { vm.setMode(PayloadMode.VOICE) }
    }
    when (ui.mode) {
        PayloadMode.TEXT -> SecretField(ui.text, !ui.busy) { vm.setText(it) }
        PayloadMode.FILE -> {
            GhostButton(ui.fileName ?: "Избери файл", enabled = !ui.busy, onClick = onPickFile)
        }
        PayloadMode.VOICE -> VoiceBlock(ui, vm, onRecord)
    }

    SectionLabel("Парола")
    PasswordField(ui.password, ui.showPassword, !ui.busy, { vm.setHidePassword(it) }, { vm.toggleHidePassword() })
    if (ui.busy) {
        Text(ui.progress, color = PaperDim, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { ui.progressValue },
            modifier = Modifier.fillMaxWidth(),
            color = Amber,
            trackColor = InkHigh,
        )
    }
    AmberButton("Скрий и запиши", enabled = !ui.busy, onClick = {
        val problem = vm.hideBlocker()
        if (problem != null) vm.showHideError(problem) else onSave()
    })
    ui.error?.let { Message(it, error = true) }
    ui.doneMessage?.let { Message(it, error = false) }
    ui.outputUri?.let { uri ->
        Note(
            if (ui.outputMime == "image/png") {
                "PNG се споделя като image/png. В чата избери документ, не снимка."
            } else {
                "WAV се споделя като файл, не като гласово съобщение."
            },
        )
        GhostButton("Сподели", onClick = { onShare(uri) })
    }
}

@Composable
private fun VoiceBlock(ui: HideUi, vm: StegoViewModel, onRecord: () -> Unit) {
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(ui.recording) {
        view.keepScreenOn = ui.recording
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.recording) {
        while (ui.recording) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    Note("OGG/OPUS, 16 kbps, моно. Кратък запис се побира по-лесно в носителя.")
    if (ui.recording) {
        val seconds = ((now - ui.recordStart) / 1000).toInt().coerceAtLeast(0)
        Text("Запис… ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = Clay, fontWeight = FontWeight.SemiBold)
        AmberButton("Спри", onClick = vm::stopVoice)
    } else {
        AmberButton("Запиши глас", onClick = onRecord)
        ui.voiceLabel?.let { label ->
            Note(label)
            GhostButton("Изтрий записа", onClick = vm::clearVoice)
        }
    }
}

@Composable
private fun ExtractPane(
    ui: ExtractUi,
    vm: StegoViewModel,
    onPick: () -> Unit,
    onSave: (String) -> Unit,
    onShare: (Uri) -> Unit,
    onCopy: (String) -> Unit,
) {
    var copied by remember { mutableIntStateOf(0) }
    SectionLabel("Носител със скрито съдържание")
    GhostButton(ui.stegoName ?: "Избери WAV или PNG", enabled = !ui.busy, onClick = onPick)
    SectionLabel("Парола")
    PasswordField(ui.password, ui.showPassword, !ui.busy, { vm.setExtractPassword(it) }, { vm.toggleExtractPassword() })
    if (ui.busy) {
        Text(ui.progress, color = PaperDim, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { ui.progressValue },
            modifier = Modifier.fillMaxWidth(),
            color = Amber,
            trackColor = InkHigh,
        )
    }
    AmberButton("Извлечи", enabled = !ui.busy, onClick = vm::extract)
    ui.error?.let { Message(it, error = true) }
    ui.text?.let { text ->
        Message("Текст", error = false)
        SelectionContainer {
            Text(
                text,
                color = Paper,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(InkRaised)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        GhostButton(if (copied > 0) "Копирано" else "Копирай", onClick = {
            onCopy(text)
            copied += 1
        })
    }
    ui.pendingName?.let { name ->
        Message("Файл · ${formatBytes(ui.pendingSize.toLong())}", error = false)
        Note("Съдържанието не е текст. Запиши го като файл.")
        AmberButton("Запиши $name", onClick = { onSave(name) })
    }
    ui.savedUri?.let { uri ->
        Note("Записано: ${ui.savedName ?: "файл"}")
        GhostButton("Сподели", onClick = { onShare(uri) })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Sage, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Note(text: String) {
    Text(text, color = PaperDim, fontSize = 13.sp)
}

@Composable
private fun Message(text: String, error: Boolean) {
    Text(
        text,
        color = if (error) Clay else Sage,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .padding(12.dp),
    )
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Amber else InkHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Ink else Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SecretField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(140.dp),
        placeholder = { Text("Тайният текст", color = PaperDim) },
        colors = fieldColors(),
    )
}

@Composable
private fun PasswordField(
    value: String,
    visible: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onToggle: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        placeholder = { Text("Парола", color = PaperDim) },
        trailingIcon = {
            Text(
                if (visible) "Скрий" else "Покажи",
                color = Amber,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(end = 8.dp),
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Paper,
            unfocusedTextColor = Paper,
            focusedBorderColor = Amber,
            unfocusedBorderColor = Line,
            cursorColor = Amber,
        ),
    )
}

@Composable
private fun fieldColors() = androidx.compose.material3.TextFieldDefaults.colors(
    focusedTextColor = Paper,
    unfocusedTextColor = Paper,
    focusedContainerColor = InkRaised,
    unfocusedContainerColor = InkRaised,
    cursorColor = Amber,
    focusedIndicatorColor = Amber,
    unfocusedIndicatorColor = Line,
)

@Composable
private fun AmberButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Amber,
            contentColor = Ink,
            disabledContainerColor = InkHigh,
            disabledContentColor = PaperDim,
        ),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GhostButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper, disabledContentColor = PaperDim),
    ) {
        Text(text, maxLines = 1)
    }
}

private fun shareAsFile(context: Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("MediaStego", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Сподели като файл").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("MediaStego", text))
}
