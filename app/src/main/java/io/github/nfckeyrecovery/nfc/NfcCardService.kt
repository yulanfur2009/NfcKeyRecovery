package io.github.nfckeyrecovery.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import io.github.nfckeyrecovery.data.BlockRecord
import io.github.nfckeyrecovery.data.CardDump
import io.github.nfckeyrecovery.data.CardSnapshot
import io.github.nfckeyrecovery.data.KeyEntry
import io.github.nfckeyrecovery.data.KeyScanProgress
import io.github.nfckeyrecovery.data.KeyScanResult
import io.github.nfckeyrecovery.data.SectorKeyResult
import io.github.nfckeyrecovery.data.hexToBytes
import io.github.nfckeyrecovery.data.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class NfcCardService {
    fun readNdefMessage(tag: Tag): ByteArray? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            ndef.ndefMessage?.toByteArray() ?: ndef.cachedNdefMessage?.toByteArray()
        } finally {
            runCatching { ndef.close() }
        }
    }

    fun inspect(tag: Tag): CardSnapshot {
        val nfcA = NfcA.get(tag)
        val isoDep = IsoDep.get(tag)
        val mifare = MifareClassic.get(tag)
        val snapshot = CardSnapshot(
            uid = tag.id.toHex(":"),
            technologies = tag.techList.map { it.substringAfterLast('.') },
            atqa = nfcA?.atqa?.toHex(),
            sak = nfcA?.sak?.let { "0x%02X".format(it.toInt() and 0xFF) },
            ats = null,
            mifareType = mifare?.let(::mifareTypeName),
            sizeBytes = mifare?.size,
            sectorCount = mifare?.sectorCount,
            blockCount = mifare?.blockCount,
        )
        if (isoDep == null) return snapshot

        return runCatching {
            isoDep.connect()
            snapshot.copy(ats = isoDep.historicalBytes?.toHex())
        }.getOrDefault(snapshot).also {
            runCatching { isoDep.close() }
        }
    }

    suspend fun scanKeys(
        tag: Tag,
        candidates: List<KeyEntry>,
        onProgress: (KeyScanProgress) -> Unit,
    ): KeyScanResult = withContext(Dispatchers.IO) {
        val card = inspect(tag)
        val mifare = MifareClassic.get(tag)
            ?: error("This phone does not expose MifareClassic for this card")
        val uniqueCandidates = candidates
            .filter { it.enabled }
            .distinctBy { it.normalizedValue }
        require(uniqueCandidates.isNotEmpty()) { "No enabled candidate keys" }

        val results = mutableListOf<SectorKeyResult>()
        try {
            mifare.connect()
            mifare.timeout = 750
            for (sector in 0 until mifare.sectorCount) {
                var keyA: KeyEntry? = null
                var keyB: KeyEntry? = null
                uniqueCandidates.forEachIndexed { index, candidate ->
                    onProgress(
                        KeyScanProgress(
                            currentSector = sector,
                            sectorCount = mifare.sectorCount,
                            candidateIndex = index,
                            candidateCount = uniqueCandidates.size,
                        ),
                    )
                    val bytes = candidate.normalizedValue.hexToBytes()
                    if (keyA == null && authenticate(mifare, sector, bytes, true)) {
                        keyA = candidate
                    }
                    if (keyB == null && authenticate(mifare, sector, bytes, false)) {
                        keyB = candidate
                    }
                    if (keyA != null && keyB != null) return@forEachIndexed
                }
                results += SectorKeyResult(
                    sector = sector,
                    keyA = keyA?.normalizedValue,
                    keyB = keyB?.normalizedValue,
                    sourceA = keyA?.source,
                    sourceB = keyB?.source,
                )
            }
        } finally {
            runCatching { mifare.close() }
        }
        KeyScanResult(card, results)
    }

    suspend fun readDump(
        tag: Tag,
        keyMap: List<SectorKeyResult>,
    ): CardDump = withContext(Dispatchers.IO) {
        val card = inspect(tag)
        val mifare = MifareClassic.get(tag)
            ?: error("This phone does not expose MifareClassic for this card")
        val blocks = mutableListOf<BlockRecord>()
        try {
            mifare.connect()
            mifare.timeout = 1_000
            for (sector in 0 until mifare.sectorCount) {
                val sectorKeys = keyMap.firstOrNull { it.sector == sector }
                val authenticated = authenticateKnownSector(mifare, sector, sectorKeys)
                val firstBlock = mifare.sectorToBlock(sector)
                val blockCount = mifare.getBlockCountInSector(sector)
                repeat(blockCount) { offset ->
                    val block = firstBlock + offset
                    blocks += if (authenticated) {
                        runCatching {
                            BlockRecord(block, mifare.readBlock(block).toHex())
                        }.getOrElse {
                            BlockRecord(block, "", readable = false)
                        }
                    } else {
                        BlockRecord(block, "", readable = false)
                    }
                }
            }
        } finally {
            runCatching { mifare.close() }
        }
        CardDump(
            uid = card.uid,
            cardType = card.mifareType ?: "MIFARE Classic compatible",
            createdAtEpochMillis = System.currentTimeMillis(),
            sectors = keyMap,
            blocks = blocks,
        )
    }

    suspend fun writeDump(
        tag: Tag,
        dump: CardDump,
        keyMap: List<SectorKeyResult>,
        allowTrailers: Boolean,
    ): WriteReport = withContext(Dispatchers.IO) {
        val card = inspect(tag)
        require(card.uid.equals(dump.uid, ignoreCase = true)) {
            "Card UID does not match the backup"
        }
        val mifare = MifareClassic.get(tag)
            ?: error("This phone does not expose MifareClassic for this card")
        var written = 0
        val skipped = mutableListOf<Int>()
        val failed = mutableListOf<Int>()

        try {
            mifare.connect()
            mifare.timeout = 1_500
            for (sector in 0 until mifare.sectorCount) {
                if (!authenticateKnownSector(
                        mifare,
                        sector,
                        keyMap.firstOrNull { it.sector == sector },
                    )
                ) {
                    val first = mifare.sectorToBlock(sector)
                    repeat(mifare.getBlockCountInSector(sector)) { skipped += first + it }
                    continue
                }
                val firstBlock = mifare.sectorToBlock(sector)
                val count = mifare.getBlockCountInSector(sector)
                repeat(count) { offset ->
                    val block = firstBlock + offset
                    val isTrailer = offset == count - 1
                    val record = dump.blocks.firstOrNull { it.block == block && it.readable }
                    if (block == 0 || (isTrailer && !allowTrailers) || record == null) {
                        skipped += block
                    } else {
                        val bytes = runCatching { record.hex.hexToBytes() }.getOrNull()
                        if (bytes?.size != MifareClassic.BLOCK_SIZE) {
                            failed += block
                        } else {
                            runCatching { mifare.writeBlock(block, bytes) }
                                .onSuccess { written++ }
                                .onFailure { failed += block }
                        }
                    }
                }
            }
        } finally {
            runCatching { mifare.close() }
        }
        WriteReport(written, skipped, failed)
    }

    private fun authenticateKnownSector(
        mifare: MifareClassic,
        sector: Int,
        result: SectorKeyResult?,
    ): Boolean {
        val keyA = result?.keyA?.let { value ->
            runCatching { value.hexToBytes() }.getOrNull()
        }
        if (keyA != null && authenticate(mifare, sector, keyA, true)) return true
        val keyB = result?.keyB?.let { value ->
            runCatching { value.hexToBytes() }.getOrNull()
        }
        return keyB != null && authenticate(mifare, sector, keyB, false)
    }

    private fun authenticate(
        mifare: MifareClassic,
        sector: Int,
        key: ByteArray,
        useKeyA: Boolean,
    ): Boolean {
        if (!mifare.isConnected) {
            runCatching { mifare.connect() }.getOrElse { return false }
        }
        return try {
            if (useKeyA) mifare.authenticateSectorWithKeyA(sector, key)
            else mifare.authenticateSectorWithKeyB(sector, key)
        } catch (_: IOException) {
            runCatching { mifare.close() }
            runCatching { mifare.connect() }.isSuccess && false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun mifareTypeName(mifare: MifareClassic): String =
        when (mifare.type) {
            MifareClassic.TYPE_CLASSIC -> when (mifare.size) {
                MifareClassic.SIZE_MINI -> "MIFARE Classic Mini"
                MifareClassic.SIZE_1K -> "MIFARE Classic 1K"
                MifareClassic.SIZE_2K -> "MIFARE Classic 2K"
                MifareClassic.SIZE_4K -> "MIFARE Classic 4K"
                else -> "MIFARE Classic"
            }
            MifareClassic.TYPE_PLUS -> "MIFARE Plus (Classic-compatible)"
            MifareClassic.TYPE_PRO -> "MIFARE Pro"
            else -> "MIFARE Classic compatible"
        }
}

data class WriteReport(
    val written: Int,
    val skippedBlocks: List<Int>,
    val failedBlocks: List<Int>,
)
