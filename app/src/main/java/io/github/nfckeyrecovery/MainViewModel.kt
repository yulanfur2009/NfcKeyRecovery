package io.github.nfckeyrecovery

import android.app.Application
import android.content.pm.PackageManager
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nfckeyrecovery.data.CardDump
import io.github.nfckeyrecovery.data.CardSnapshot
import io.github.nfckeyrecovery.data.EmulationProfile
import io.github.nfckeyrecovery.data.EmulationRepository
import io.github.nfckeyrecovery.data.KeyEntry
import io.github.nfckeyrecovery.data.KeyFileCodec
import io.github.nfckeyrecovery.data.KeyRepository
import io.github.nfckeyrecovery.data.KeyScanProgress
import io.github.nfckeyrecovery.data.NdefDumpCodec
import io.github.nfckeyrecovery.data.RemoteKeySource
import io.github.nfckeyrecovery.data.SectorKeyResult
import io.github.nfckeyrecovery.data.toHex
import io.github.nfckeyrecovery.hardware.ExternalDevice
import io.github.nfckeyrecovery.hardware.Proxmark3UsbAdapter
import io.github.nfckeyrecovery.nfc.NfcCardService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val keyRepository = KeyRepository(application)
    private val emulationRepository = EmulationRepository(application)
    private val cardService = NfcCardService()
    private val proxmark = Proxmark3UsbAdapter(application)
    private var lastTag: Tag? = null

    val keys = keyRepository.keys
    val sources = keyRepository.sources
    val emulationProfiles = emulationRepository.profiles
    val activeEmulationProfile = emulationRepository.activeProfile

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun onTag(tag: Tag) {
        lastTag = tag
        viewModelScope.launch {
            setBusy("正在识别卡片")
            runCatching {
                withContext(Dispatchers.IO) {
                    cardService.inspect(tag) to
                        runCatching { cardService.readNdefMessage(tag)?.toHex() }.getOrNull()
                }
            }
                .onSuccess { (card, ndefMessageHex) ->
                    _state.update {
                        it.copy(
                            card = card,
                            ndefMessageHex = ndefMessageHex,
                            dump = null,
                            keyMap = emptyList(),
                            message = "已识别 ${card.mifareType ?: "NFC 标签"}",
                            busy = false,
                            operation = null,
                        )
                    }
                }
                .onFailure(::setError)
        }
    }

    fun scanKeys() {
        val tag = lastTag ?: return setMessage("请先贴近并识别卡片")
        viewModelScope.launch {
            setBusy("正在测试候选密钥")
            runCatching {
                cardService.scanKeys(tag, keys.value) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        card = result.card,
                        keyMap = result.sectors,
                        progress = null,
                        busy = false,
                        operation = null,
                        message = "密钥映射完成：${result.sectors.count { sector -> sector.keyA != null || sector.keyB != null }}/${result.sectors.size} 个扇区",
                    )
                }
            }.onFailure(::setError)
        }
    }

    fun readDump() {
        val tag = lastTag ?: return setMessage("请先贴近并识别卡片")
        if (_state.value.keyMap.isEmpty()) return setMessage("请先执行密钥扫描")
        viewModelScope.launch {
            setBusy("正在读取卡片备份")
            runCatching { cardService.readDump(tag, _state.value.keyMap) }
                .onSuccess { dump ->
                    val extractedNdef = NdefDumpCodec.findNdefMessage(dump)?.toHex()
                    _state.update {
                        it.copy(
                            dump = dump,
                            ndefMessageHex = extractedNdef,
                            busy = false,
                            operation = null,
                            message = buildString {
                                append("备份完成：")
                                append(dump.blocks.count { block -> block.readable })
                                append("/")
                                append(dump.blocks.size)
                                append(" 个数据块")
                                if (extractedNdef != null) append("，已提取 NDEF")
                            },
                        )
                    }
                }
                .onFailure(::setError)
        }
    }

    fun writeDump(allowTrailers: Boolean) {
        val tag = lastTag ?: return setMessage("请先贴近目标卡片")
        val dump = _state.value.dump ?: return setMessage("当前没有可写入的备份")
        viewModelScope.launch {
            setBusy("正在写回已确认的备份")
            runCatching {
                cardService.writeDump(tag, dump, _state.value.keyMap, allowTrailers)
            }.onSuccess { report ->
                _state.update {
                    it.copy(
                        busy = false,
                        operation = null,
                        message = "写入 ${report.written} 块，跳过 ${report.skippedBlocks.size} 块，失败 ${report.failedBlocks.size} 块",
                    )
                }
            }.onFailure(::setError)
        }
    }

    fun importFile(fileName: String, bytes: ByteArray) {
        viewModelScope.launch {
            runCatching {
                when (fileName.substringAfterLast('.', "").lowercase()) {
                    "mfd", "bin", "dump" -> {
                        val dump = KeyFileCodec.importMfd(bytes)
                        _state.update {
                            it.copy(
                                card = null,
                                dump = dump,
                                keyMap = emptyList(),
                                ndefMessageHex = NdefDumpCodec.findNdefMessage(dump)?.toHex(),
                            )
                        }
                        0
                    }
                    "json" -> {
                        if (KeyFileCodec.looksLikeDumpJson(bytes)) {
                            val dump = KeyFileCodec.importDumpJson(bytes)
                            _state.update {
                                it.copy(
                                    card = null,
                                    dump = dump,
                                    keyMap = dump.sectors,
                                    ndefMessageHex = NdefDumpCodec.findNdefMessage(dump)?.toHex(),
                                )
                            }
                            0
                        } else {
                            keyRepository.addKeys(
                                KeyFileCodec.parseKeys(bytes, fileName, "import:$fileName"),
                            )
                        }
                    }
                    else -> keyRepository.addKeys(
                        KeyFileCodec.parseKeys(bytes, fileName, "import:$fileName"),
                    )
                }
            }.onSuccess { added ->
                setMessage(
                    if (added == 0) "已导入卡片 dump"
                    else "已新增 $added 个候选密钥",
                )
            }.onFailure(::setError)
        }
    }

    fun exportKeys(): ByteArray = KeyFileCodec.exportKeys(keys.value)

    fun exportDumpJson(): ByteArray? =
        _state.value.dump?.let(KeyFileCodec::exportDumpJson)

    fun exportDumpMfd(): ByteArray? =
        _state.value.dump?.let(KeyFileCodec::exportMfd)

    fun addManualKey(value: String) {
        val added = runCatching {
            keyRepository.addKeys(
                listOf(KeyEntry(value.trim().uppercase(), "Manual", "manual")),
            )
        }.getOrElse {
            setError(it)
            return
        }
        setMessage(if (added > 0) "已添加密钥" else "该密钥已存在或格式无效")
    }

    fun addSource(name: String, url: String, sha256: String) {
        runCatching {
            require(url.startsWith("https://", ignoreCase = true)) {
                "密钥源必须使用 HTTPS"
            }
            keyRepository.replaceSource(
                RemoteKeySource(name.trim().ifBlank { "Custom source" }, url.trim(), expectedSha256 = sha256.trim()),
            )
        }.onSuccess { setMessage("密钥源已保存") }
            .onFailure(::setError)
    }

    fun updateSource(source: RemoteKeySource) {
        viewModelScope.launch {
            setBusy("正在更新 ${source.name}")
            runCatching { keyRepository.updateSource(source) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            busy = false,
                            operation = null,
                            message = "获取 ${result.fetched} 个密钥，新增 ${result.added} 个",
                        )
                    }
                }
                .onFailure(::setError)
        }
    }

    fun updateIcemanDictionary() {
        val source = sources.value.firstOrNull {
            it.name.contains("Iceman", ignoreCase = true)
        } ?: return setMessage("未找到 Iceman 字典源")
        updateSource(source)
    }

    fun saveCurrentEmulationProfile(name: String) {
        val current = _state.value
        val card = current.card
        val dump = current.dump
        val ndef = current.ndefMessageHex
        if (card == null && dump == null) return setMessage("请先读取一张卡片或导入 dump")
        if (ndef == null && dump == null) {
            return setMessage("当前卡片没有可保存的 NDEF 或 Classic dump")
        }
        val uid = dump?.uid ?: card?.uid.orEmpty()
        val type = when {
            ndef != null && dump != null -> "DECRYPTED_CLASSIC_NDEF"
            ndef != null -> "NDEF_TAG"
            else -> "MIFARE_CLASSIC_DUMP"
        }
        val profile = EmulationProfile(
            id = "${uid.replace(":", "")}-${System.currentTimeMillis()}",
            name = name.trim().ifBlank {
                card?.mifareType ?: dump?.cardType ?: "NFC profile"
            },
            sourceUid = uid,
            sourceKind = type,
            createdAtEpochMillis = System.currentTimeMillis(),
            ndefMessageHex = ndef,
            classicDump = dump,
        )
        emulationRepository.save(profile)
        setMessage(
            if (profile.supportsAndroidHce) "卡片配置已保存，可启用 Type 4 NDEF 模拟"
            else "Classic 配置已保存，需要外接硬件进行模拟",
        )
    }

    fun activateEmulation(profile: EmulationProfile) {
        if (!getApplication<Application>().packageManager.hasSystemFeature(
                PackageManager.FEATURE_NFC_HOST_CARD_EMULATION,
            )
        ) {
            return setMessage("此手机不支持 NFC Host Card Emulation")
        }
        runCatching { emulationRepository.activate(profile.id) }
            .onSuccess { setMessage("已启用 ${profile.name}；请保持手机解锁并贴近读卡器") }
            .onFailure(::setError)
    }

    fun deactivateEmulation() {
        emulationRepository.deactivate()
        setMessage("已停止卡片模拟，恢复 NFC 读卡模式")
    }

    fun deleteEmulationProfile(profileId: String) {
        emulationRepository.delete(profileId)
        setMessage("卡片配置已删除")
    }

    fun detectExternalHardware() {
        viewModelScope.launch {
            runCatching { proxmark.detect() }
                .onSuccess { devices ->
                    _state.update {
                        it.copy(
                            externalDevices = devices,
                            message = if (devices.isEmpty()) "未检测到 Proxmark3 USB 设备"
                            else "检测到 ${devices.size} 个兼容设备",
                        )
                    }
                }
                .onFailure(::setError)
        }
    }

    private fun setBusy(operation: String) {
        _state.update { it.copy(busy = true, operation = operation, message = null) }
    }

    private fun setMessage(message: String) {
        _state.update { it.copy(message = message, error = null) }
    }

    private fun setError(error: Throwable) {
        _state.update {
            it.copy(
                busy = false,
                operation = null,
                progress = null,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }
}

data class AppState(
    val card: CardSnapshot? = null,
    val keyMap: List<SectorKeyResult> = emptyList(),
    val dump: CardDump? = null,
    val ndefMessageHex: String? = null,
    val progress: KeyScanProgress? = null,
    val externalDevices: List<ExternalDevice> = emptyList(),
    val busy: Boolean = false,
    val operation: String? = null,
    val message: String? = null,
    val error: String? = null,
)
