package io.github.nfckeyrecovery.data

private val HEX_KEY = Regex("^[0-9A-Fa-f]{12}$")

fun String.isMifareKey(): Boolean = HEX_KEY.matches(trim())

fun String.hexToBytes(): ByteArray {
    val normalized = trim()
        .removePrefix("0x")
        .replace(" ", "")
        .replace(":", "")
    require(normalized.length % 2 == 0) { "Hex value must contain complete bytes" }
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

fun decodeAccessBits(trailer: ByteArray): AccessBits {
    if (trailer.size != 16) return AccessBits(false, emptyList())

    val b6 = trailer[6].toInt() and 0xFF
    val b7 = trailer[7].toInt() and 0xFF
    val b8 = trailer[8].toInt() and 0xFF
    val c1 = (b7 shr 4) and 0x0F
    val c2 = b8 and 0x0F
    val c3 = (b8 shr 4) and 0x0F
    val invC1 = b6 and 0x0F
    val invC2 = (b6 shr 4) and 0x0F
    val invC3 = b7 and 0x0F
    val valid = invC1 == (c1 xor 0x0F) &&
        invC2 == (c2 xor 0x0F) &&
        invC3 == (c3 xor 0x0F)
    val groups = (0 until 4).map { group ->
        val bit = 1 shl group
        val c1Bit = if ((c1 and bit) != 0) 1 else 0
        val c2Bit = if ((c2 and bit) != 0) 1 else 0
        val c3Bit = if ((c3 and bit) != 0) 1 else 0
        (c1Bit shl 2) or (c2Bit shl 1) or c3Bit
    }
    return AccessBits(valid, groups)
}
