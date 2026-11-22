package com.audiostego.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    var about by remember { mutableStateOf(false) }
    var english by remember { mutableStateOf(false) }
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
        if (granted) vm.startVoice() else vm.showHideError(tr(english, "Няма достъп до микрофона"))
    }

    Scaffold(containerColor = Ink) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "MediaStego",
                        color = Amber,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LanguageFlag(english = english, onClick = { english = !english })
                        Text(
                            "by Dr. Baysinger",
                            color = PaperDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clickable { about = true },
                        )
                    }
                }
                Text(
                    if (english) "Hidden text in sound or a picture" else "Скрит текст в звук или картина",
                    color = PaperDim,
                    fontSize = 13.sp,
                )
            }
            WarningBanner(english)
            PrimaryTabRow(
                selectedTabIndex = tab,
                containerColor = Ink,
                contentColor = Amber,
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(if (english) "Hide" else "Скрий") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(if (english) "Extract" else "Извлечи") })
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
                        english = english,
                        ui = hide,
                        vm = vm,
                        onPickCover = { pickCover.launch(carrierTypes) },
                        onPickFile = { pickPayload.launch(arrayOf("*/*")) },
                        onRecord = {
                            if (Build.VERSION.SDK_INT < 29) {
                                vm.showHideError(tr(english, "Записът на глас работи на Android 10 и по-нов (OGG/OPUS)"))
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
                        onShare = { uri ->
                            shareAsFile(context, uri, hide.outputMime, if (english) "Share as a file" else "Сподели като файл")
                        },
                    )
                } else {
                    ExtractPane(
                        english = english,
                        ui = extract,
                        vm = vm,
                        onPick = { pickStego.launch(carrierTypes) },
                        onSave = { name -> createExtract.launch(name) },
                        onShare = { uri ->
                            val name = extract.savedName.orEmpty()
                            val mime = if (name.endsWith(".png", ignoreCase = true)) "image/png" else "application/octet-stream"
                            shareAsFile(context, uri, mime, if (english) "Share as a file" else "Сподели като файл")
                        },
                        onCopy = { text -> copyText(context, text) },
                    )
                }
            }
        }
    }
    if (about) AboutDialog { about = false }
}

private val carrierTypes = arrayOf(
    "audio/wav",
    "audio/x-wav",
    "audio/*",
    "image/png",
    "application/octet-stream",
)

@Composable
private fun WarningBanner(english: Boolean) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color(0xFF2A2116))
            .border(1.dp, AmberDeep, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            if (english) "Send the result as a file, not as a voice note or a photo." else "Пращай резултата като файл, не като гласово или снимка.",
            color = Amber,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun HidePane(
    english: Boolean,
    ui: HideUi,
    vm: StegoViewModel,
    onPickCover: () -> Unit,
    onPickFile: () -> Unit,
    onRecord: () -> Unit,
    onSave: () -> Unit,
    onShare: (Uri) -> Unit,
) {
    SectionLabel(if (english) "Carrier" else "Носител")
    GhostButton(if (ui.coverName == null) if (english) "Choose WAV or PNG" else "Избери WAV или PNG" else ui.coverName, enabled = !ui.busy, onClick = onPickCover)
    ui.coverInfo?.let { Note(tr(english, it)) }

    SectionLabel(if (english) "What to hide" else "Какво да се скрие")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip(if (english) "Text" else "Текст", ui.mode == PayloadMode.TEXT) { vm.setMode(PayloadMode.TEXT) }
        ModeChip(if (english) "File" else "Файл", ui.mode == PayloadMode.FILE) { vm.setMode(PayloadMode.FILE) }
        ModeChip(if (english) "Voice" else "Глас", ui.mode == PayloadMode.VOICE) { vm.setMode(PayloadMode.VOICE) }
    }
    when (ui.mode) {
        PayloadMode.TEXT -> SecretField(ui.text, !ui.busy, english) { vm.setText(it) }
        PayloadMode.FILE -> {
            GhostButton(ui.fileName ?: if (english) "Choose a file" else "Избери файл", enabled = !ui.busy, onClick = onPickFile)
        }
        PayloadMode.VOICE -> VoiceBlock(english, ui, vm, onRecord)
    }

    SectionLabel(if (english) "Password" else "Парола")
    PasswordField(ui.password, ui.showPassword, !ui.busy, english, { vm.setHidePassword(it) }, { vm.toggleHidePassword() })
    if (ui.busy) {
        Text(tr(english, ui.progress), color = PaperDim, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { ui.progressValue },
            modifier = Modifier.fillMaxWidth(),
            color = Amber,
            trackColor = InkHigh,
        )
    }
    AmberButton(if (english) "Hide and save" else "Скрий и запиши", enabled = !ui.busy, onClick = {
        val problem = vm.hideBlocker()
        if (problem != null) vm.showHideError(problem) else onSave()
    })
    ui.error?.let { Message(tr(english, it), error = true) }
    ui.doneMessage?.let { Message(tr(english, it), error = false) }
    ui.outputUri?.let { uri ->
        Note(
            if (ui.outputMime == "image/png") {
                if (english) "PNG is shared as image/png. In a chat, choose a document, not a photo." else "PNG се споделя като image/png. В чата избери документ, не снимка."
            } else {
                if (english) "WAV is shared as a file, not as a voice message." else "WAV се споделя като файл, не като гласово съобщение."
            },
        )
        GhostButton(if (english) "Share" else "Сподели", onClick = { onShare(uri) })
    }
}

@Composable
private fun VoiceBlock(english: Boolean, ui: HideUi, vm: StegoViewModel, onRecord: () -> Unit) {
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
    Note(if (english) "OGG/OPUS, 16 kbps, mono. A short recording fits in the carrier more easily." else "OGG/OPUS, 16 kbps, моно. Кратък запис се побира по-лесно в носителя.")
    if (ui.recording) {
        val seconds = ((now - ui.recordStart) / 1000).toInt().coerceAtLeast(0)
        val clock = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        Text(if (english) "Recording… $clock" else "Запис… $clock", color = Clay, fontWeight = FontWeight.SemiBold)
        AmberButton(if (english) "Stop" else "Спри", onClick = vm::stopVoice)
    } else {
        AmberButton(if (english) "Record voice" else "Запиши глас", onClick = onRecord)
        ui.voiceLabel?.let { label ->
            Note(label)
            GhostButton(if (english) "Delete the recording" else "Изтрий записа", onClick = vm::clearVoice)
        }
    }
}

@Composable
private fun ExtractPane(
    english: Boolean,
    ui: ExtractUi,
    vm: StegoViewModel,
    onPick: () -> Unit,
    onSave: (String) -> Unit,
    onShare: (Uri) -> Unit,
    onCopy: (String) -> Unit,
) {
    var copied by remember { mutableIntStateOf(0) }
    SectionLabel(if (english) "Carrier with hidden content" else "Носител със скрито съдържание")
    GhostButton(ui.stegoName ?: if (english) "Choose WAV or PNG" else "Избери WAV или PNG", enabled = !ui.busy, onClick = onPick)
    SectionLabel(if (english) "Password" else "Парола")
    PasswordField(ui.password, ui.showPassword, !ui.busy, english, { vm.setExtractPassword(it) }, { vm.toggleExtractPassword() })
    if (ui.busy) {
        Text(tr(english, ui.progress), color = PaperDim, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { ui.progressValue },
            modifier = Modifier.fillMaxWidth(),
            color = Amber,
            trackColor = InkHigh,
        )
    }
    AmberButton(if (english) "Extract" else "Извлечи", enabled = !ui.busy, onClick = vm::extract)
    ui.error?.let { Message(tr(english, it), error = true) }
    ui.text?.let { text ->
        Message(if (english) "Text" else "Текст", error = false)
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
        GhostButton(if (copied > 0) if (english) "Copied" else "Копирано" else if (english) "Copy" else "Копирай", onClick = {
            onCopy(text)
            copied += 1
        })
    }
    ui.pendingName?.let { name ->
        Message("${if (english) "File" else "Файл"} · ${formatBytes(ui.pendingSize.toLong())}", error = false)
        Note(if (english) "This is not text. Save it as a file." else "Съдържанието не е текст. Запиши го като файл.")
        AmberButton(if (english) "Save $name" else "Запиши $name", onClick = { onSave(name) })
    }
    ui.savedUri?.let { uri ->
        Note(if (english) "Saved: ${ui.savedName ?: "file"}" else "Записано: ${ui.savedName ?: "файл"}")
        GhostButton(if (english) "Share" else "Сподели", onClick = { onShare(uri) })
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
private fun SecretField(value: String, enabled: Boolean, english: Boolean, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(116.dp),
        placeholder = { Text(if (english) "The secret text" else "Тайният текст", color = PaperDim) },
        colors = fieldColors(),
    )
}

@Composable
private fun PasswordField(
    value: String,
    visible: Boolean,
    enabled: Boolean,
    english: Boolean,
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
        placeholder = { Text(if (english) "Password" else "Парола", color = PaperDim) },
        trailingIcon = {
            Text(
                if (visible) if (english) "Hide" else "Скрий" else if (english) "Show" else "Покажи",
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

private fun shareAsFile(context: Context, uri: Uri, mime: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("MediaStego", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
        val chooser = Intent.createChooser(send, title).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
}

private const val ABOUT_EN = "Donate to Dr. Baysinger. He promises to spend it on hookers and beer."
private const val ABOUT_BG = "Дарете на Dr. Baysinger. Той обещава да ги изхарчи за курви и бело."
private const val REVOLUT = "revolut.me/georgi09qc"
private val BTC = listOf(
    "bc1qr84akpfry7sjs6759wwq722fyert9apcarpst6",
    "bc1p6hp5q20u9t9upsjt7425wgqdrs0cr8wnnlf9atu24z92pzsnq85q62uc8e",
)

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = InkRaised,
        titleContentColor = Paper,
        textContentColor = Paper,
        confirmButton = {
            TextButton(onClick = onClose) { Text("OK", color = Amber) }
        },
        title = { Text("About", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(ABOUT_EN)
                Text(ABOUT_BG, modifier = Modifier.padding(top = 12.dp))
                Text("Revolut", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                CopyLine(REVOLUT, context)
                Text("BTC address", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                BTC.forEach { CopyLine(it, context) }
            }
        },
    )
}

@Composable
private fun CopyLine(value: String, context: Context) {
    Text(
        value,
        color = Amber,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("address", value))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 6.dp),
    )
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("MediaStego", text))
}
