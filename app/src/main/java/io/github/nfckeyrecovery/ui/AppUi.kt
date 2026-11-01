package io.github.nfckeyrecovery.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nfckeyrecovery.AppState
import io.github.nfckeyrecovery.MainViewModel
import io.github.nfckeyrecovery.data.KeyEntry
import io.github.nfckeyrecovery.data.RemoteKeySource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AppColors = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFF53D6B5),
    onPrimary = Color(0xFF05251E),
    secondary = Color(0xFFF2B84B),
    background = Color(0xFF111418),
    surface = Color(0xFF1A1E23),
    surfaceVariant = Color(0xFF242A31),
    onSurface = Color(0xFFF3F5F7),
    onSurfaceVariant = Color(0xFFBAC2CC),
    error = Color(0xFFFF7B72),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcKeyRecoveryApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val keys by viewModel.keys.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    var pendingWrite by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "import.keys"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) viewModel.importFile(name, bytes)
    }
    val exportKeysLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { writeBytes(context, it, viewModel.exportKeys()) } }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> viewModel.exportDumpJson()?.let { bytes -> uri?.let { writeBytes(context, it, bytes) } } }
    val exportMfdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> viewModel.exportDumpMfd()?.let { bytes -> uri?.let { writeBytes(context, it, bytes) } } }

    LaunchedEffect(state.message, state.error) {
        (state.error ?: state.message)?.let { snackbar.showSnackbar(it) }
    }

    MaterialTheme(colorScheme = AppColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("NFC Key Recovery", fontWeight = FontWeight.SemiBold)
                            Text(
                                "授权卡片分析工具",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.UploadFile, contentDescription = "导入文件")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    listOf(
                        Triple("读卡", Icons.Default.Nfc, 0),
                        Triple("密钥", Icons.Default.Key, 1),
                        Triple("文件", Icons.Default.Save, 2),
                        Triple("硬件", Icons.Default.Usb, 3),
                    ).forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
            ) {
                when (tab) {
                    0 -> ReaderScreen(
                        state = state,
                        onScan = viewModel::scanKeys,
                        onRead = viewModel::readDump,
                        onWrite = { pendingWrite = true },
                    )
                    1 -> KeysScreen(
                        keys = keys,
                        sources = sources,
                        onAddKey = viewModel::addManualKey,
                        onAddSource = viewModel::addSource,
                        onUpdateSource = viewModel::updateSource,
                    )
                    2 -> FilesScreen(
                        state = state,
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onExportKeys = { exportKeysLauncher.launch("nfc-keys.keys") },
                        onExportJson = { exportJsonLauncher.launch("card-backup.json") },
                        onExportMfd = { exportMfdLauncher.launch("card-backup.mfd") },
                    )
                    3 -> HardwareScreen(state, viewModel::detectExternalHardware)
                }

                if (state.busy) {
                    BusyOverlay(state)
                }
            }
        }

        if (pendingWrite) {
            WriteConfirmationDialog(
                onDismiss = { pendingWrite = false },
                onConfirm = { allowTrailers ->
                    pendingWrite = false
                    viewModel.writeDump(allowTrailers)
                },
            )
        }
    }
}

@Composable
private fun ReaderScreen(
    state: AppState,
    onScan: () -> Unit,
    onRead: () -> Unit,
    onWrite: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        Icons.Default.CreditCard,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.card?.mifareType ?: "等待 NFC 卡片",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            state.card?.uid ?: "将卡片贴近手机 NFC 感应区域",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        state.card?.let { card ->
            item {
                SectionTitle("卡片信息")
            }
            item {
                InfoGrid(
                    listOf(
                        "技术" to card.technologies.joinToString(", "),
                        "ATQA" to (card.atqa ?: "不可用"),
                        "SAK" to (card.sak ?: "不可用"),
                        "ATS" to (card.ats ?: "未返回"),
                        "容量" to card.sizeBytes?.let { "$it bytes" }.orEmpty(),
                        "布局" to card.sectorCount?.let { "$it 扇区 / ${card.blockCount} 块" }.orEmpty(),
                    ),
                )
            }
            item {
                SectionTitle("操作")
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("扫描密钥")
                    }
                    OutlinedButton(
                        onClick = onRead,
                        enabled = state.keyMap.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("读取备份")
                    }
                }
            }
            if (state.dump != null) {
                item {
                    OutlinedButton(
                        onClick = onWrite,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.secondary,
                        ),
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("写回同一张卡")
                    }
                }
            }
            if (state.keyMap.isNotEmpty()) {
                item { SectionTitle("扇区密钥映射") }
                items(state.keyMap) { sector ->
                    SectorRow(
                        sector.sector,
                        sector.keyA ?: "未找到",
                        sector.keyB ?: "未找到",
                    )
                }
            }
        }
    }
}

@Composable
private fun KeysScreen(
    keys: List<KeyEntry>,
    sources: List<RemoteKeySource>,
    onAddKey: (String) -> Unit,
    onAddSource: (String, String, String) -> Unit,
    onUpdateSource: (RemoteKeySource) -> Unit,
) {
    var manualKey by remember { mutableStateOf("") }
    var sourceName by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var sourceHash by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("手动添加候选密钥") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualKey,
                    onValueChange = { manualKey = it.take(12) },
                    label = { Text("12 位十六进制") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        onAddKey(manualKey)
                        manualKey = ""
                    },
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加密钥")
                }
            }
        }
        item { SectionTitle("在线密钥源") }
        items(sources) { source ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, fontWeight = FontWeight.Medium)
                        Text(
                            source.url,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        source.lastUpdatedEpochMillis?.let {
                            Text(
                                "更新于 ${formatTime(it)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { onUpdateSource(source) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "更新密钥源")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = sourceName,
                onValueChange = { sourceName = it },
                label = { Text("源名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = sourceUrl,
                onValueChange = { sourceUrl = it },
                label = { Text("GitHub Raw / HTTPS 地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = sourceHash,
                onValueChange = { sourceHash = it.take(64) },
                label = { Text("固定 SHA-256（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = {
                    onAddSource(sourceName, sourceUrl, sourceHash)
                    sourceName = ""
                    sourceUrl = ""
                    sourceHash = ""
                },
                enabled = sourceUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存密钥源")
            }
        }
        item { SectionTitle("当前候选密钥 · ${keys.size}") }
        items(keys) { key ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(key.normalizedValue, fontWeight = FontWeight.Medium)
                    Text(
                        "${key.label.ifBlank { "未命名" }} · ${key.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun FilesScreen(
    state: AppState,
    onImport: () -> Unit,
    onExportKeys: () -> Unit,
    onExportJson: () -> Unit,
    onExportMfd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("导入")
        ActionRow(
            icon = Icons.Default.UploadFile,
            title = "导入密钥或 dump",
            detail = ".keys · .txt · .json · .mfd · .bin",
            onClick = onImport,
        )
        SectionTitle("导出")
        ActionRow(
            icon = Icons.Default.Key,
            title = "候选密钥列表",
            detail = "兼容 MCT 的逐行 .keys 文本",
            onClick = onExportKeys,
        )
        ActionRow(
            icon = Icons.Default.Save,
            title = "结构化 JSON 备份",
            detail = "包含卡片信息、密钥映射和数据块",
            enabled = state.dump != null,
            onClick = onExportJson,
        )
        ActionRow(
            icon = Icons.Default.Memory,
            title = "原始 MFD 备份",
            detail = "兼容常见 1K/4K 原始 dump 工具",
            enabled = state.dump != null,
            onClick = onExportMfd,
        )
    }
}

@Composable
private fun HardwareScreen(state: AppState, onDetect: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Proxmark3", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "USB 检测与恢复适配层",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Button(onClick = onDetect, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("检测外接设备")
            }
        }
        if (state.externalDevices.isEmpty()) {
            item {
                Text(
                    "尚未连接设备。购买后可依据具体型号和固件完成 nested、hardnested 与 static-nonce 命令通道。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.externalDevices) { device ->
                ActionRow(
                    icon = Icons.Default.Usb,
                    title = device.displayName,
                    detail = "VID %04X · PID %04X".format(device.vendorId, device.productId),
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun BusyOverlay(state: AppState) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3111418)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(state.operation ?: "正在处理")
                state.progress?.let { progress ->
                    val total = progress.sectorCount * progress.candidateCount
                    val current = progress.currentSector * progress.candidateCount +
                        progress.candidateIndex + 1
                    LinearProgressIndicator(
                        progress = { current.toFloat() / total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "扇区 ${progress.currentSector + 1}/${progress.sectorCount}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun WriteConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var allowTrailers by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Security, contentDescription = null) },
        title = { Text("确认写回备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("仅允许写入 UID 与备份一致的卡片。块 0 永远跳过，写入前请保持卡片稳定。")
                TextButton(onClick = { allowTrailers = !allowTrailers }) {
                    Text(if (allowTrailers) "已允许扇区尾块写入" else "扇区尾块保持跳过")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(allowTrailers) }) { Text("确认写入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun InfoGrid(values: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            values.filter { it.second.isNotBlank() }.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        label,
                        modifier = Modifier.weight(0.28f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(value, modifier = Modifier.weight(0.72f))
                }
            }
        }
    }
}

@Composable
private fun SectorRow(sector: Int, keyA: String, keyB: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "%02d".format(sector),
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("Key A  $keyA", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Key B  $keyB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(icon, contentDescription = null)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary,
    )
}

private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getString(0)
    }
}

private fun formatTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
