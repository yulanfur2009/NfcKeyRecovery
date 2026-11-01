package io.github.nfckeyrecovery.data

object NdefDumpCodec {
    fun findNdefMessage(dump: CardDump): ByteArray? {
        val data = dump.blocks
            .filter { it.block >= 4 && it.readable && !isSectorTrailer(it.block) }
            .sortedBy { it.block }
            .mapNotNull { runCatching { it.hex.hexToBytes() }.getOrNull() }
            .fold(ByteArray(0)) { current, block -> current + block }
        return findNdefTlv(data)
    }

    fun findNdefTlv(data: ByteArray): ByteArray? {
        var index = 0
        while (index < data.size) {
            val type = data[index].toInt() and 0xFF
            when (type) {
                0x00 -> index++
                0xFE -> return null
                0x03 -> {
                    val lengthResult = readTlvLength(data, index + 1) ?: return null
                    val start = lengthResult.first
                    val length = lengthResult.second
                    if (start + length > data.size) return null
                    val message = data.copyOfRange(start, start + length)
                    return message.takeIf(::isStructurallyValidNdef)
                }
                else -> {
                    val lengthResult = readTlvLength(data, index + 1) ?: return null
                    index = lengthResult.first + lengthResult.second
                }
            }
        }
        return null
    }

    fun isStructurallyValidNdef(message: ByteArray): Boolean {
        if (message.isEmpty()) return false
        var index = 0
        var recordIndex = 0
        while (index < message.size) {
            val header = message[index++].toInt() and 0xFF
            val mb = header and 0x80 != 0
            val me = header and 0x40 != 0
            val cf = header and 0x20 != 0
            val sr = header and 0x10 != 0
            val il = header and 0x08 != 0
            if (cf || (recordIndex == 0 && !mb) || (recordIndex > 0 && mb)) return false
            if (index >= message.size) return false
            val typeLength = message[index++].toInt() and 0xFF
            val payloadLength = if (sr) {
                if (index >= message.size) return false
                message[index++].toInt() and 0xFF
            } else {
                if (index + 4 > message.size) return false
                val value = ((message[index].toLong() and 0xFF) shl 24) or
                    ((message[index + 1].toLong() and 0xFF) shl 16) or
                    ((message[index + 2].toLong() and 0xFF) shl 8) or
                    (message[index + 3].toLong() and 0xFF)
                index += 4
                if (value > Int.MAX_VALUE) return false
                value.toInt()
            }
            val idLength = if (il) {
                if (index >= message.size) return false
                message[index++].toInt() and 0xFF
            } else {
                0
            }
            val recordEnd = index.toLong() + typeLength + idLength + payloadLength
            if (recordEnd > message.size) return false
            index = recordEnd.toInt()
            recordIndex++
            if (me) return index == message.size
        }
        return false
    }

    private fun readTlvLength(data: ByteArray, lengthIndex: Int): Pair<Int, Int>? {
        if (lengthIndex >= data.size) return null
        val first = data[lengthIndex].toInt() and 0xFF
        return if (first != 0xFF) {
            (lengthIndex + 1) to first
        } else {
            if (lengthIndex + 2 >= data.size) return null
            val length = ((data[lengthIndex + 1].toInt() and 0xFF) shl 8) or
                (data[lengthIndex + 2].toInt() and 0xFF)
            (lengthIndex + 3) to length
        }
    }

    private fun isSectorTrailer(block: Int): Boolean =
        if (block < 128) {
            block % 4 == 3
        } else {
            (block - 128) % 16 == 15
        }
}
