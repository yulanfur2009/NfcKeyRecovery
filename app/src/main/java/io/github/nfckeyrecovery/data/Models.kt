package io.github.nfckeyrecovery.data

import kotlinx.serialization.Serializable

@Serializable
data class KeyEntry(
    val value: String,
    val label: String = "",
    val source: String = "local",
    val enabled: Boolean = true,
) {
    val normalizedValue: String
        get() = value.uppercase()
}

@Serializable
data class KeyFeed(
    val schemaVersion: Int = 1,
    val name: String,
    val version: String,
    val description: String = "",
    val license: String = "",
    val keys: List<KeyEntry>,
)

@Serializable
data class RemoteKeySource(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val expectedSha256: String = "",
    val lastSha256: String = "",
    val lastUpdatedEpochMillis: Long? = null,
)

@Serializable
data class SectorKeyResult(
    val sector: Int,
    val keyA: String? = null,
    val keyB: String? = null,
    val sourceA: String? = null,
    val sourceB: String? = null,
)

@Serializable
data class BlockRecord(
    val block: Int,
    val hex: String,
    val readable: Boolean = true,
)

@Serializable
data class CardDump(
    val uid: String,
    val cardType: String,
    val createdAtEpochMillis: Long,
    val sectors: List<SectorKeyResult>,
    val blocks: List<BlockRecord>,
)

data class CardSnapshot(
    val uid: String,
    val technologies: List<String>,
    val atqa: String? = null,
    val sak: String? = null,
    val ats: String? = null,
    val mifareType: String? = null,
    val sizeBytes: Int? = null,
    val sectorCount: Int? = null,
    val blockCount: Int? = null,
)

data class KeyScanProgress(
    val currentSector: Int,
    val sectorCount: Int,
    val candidateIndex: Int,
    val candidateCount: Int,
)

data class KeyScanResult(
    val card: CardSnapshot,
    val sectors: List<SectorKeyResult>,
)

data class AccessBits(
    val valid: Boolean,
    val groups: List<Int>,
)
