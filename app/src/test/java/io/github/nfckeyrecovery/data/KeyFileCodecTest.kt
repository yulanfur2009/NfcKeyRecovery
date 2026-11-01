package io.github.nfckeyrecovery.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFileCodecTest {
    @Test
    fun parsesMctStyleKeyFilesAndRemovesDuplicates() {
        val input = """
            # Public defaults
            FFFFFFFFFFFF
            a0a1a2a3a4a5
            FFFFFFFFFFFF ; duplicate
            invalid
        """.trimIndent()

        val result = KeyFileCodec.parseTextKeys(input, "test")

        assertEquals(listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5"), result.map { it.value })
    }

    @Test
    fun rawDumpRoundTrips() {
        val bytes = ByteArray(1024) { (it and 0xFF).toByte() }

        val dump = KeyFileCodec.importMfd(bytes, "01:02:03:04")
        val exported = KeyFileCodec.exportMfd(dump)

        assertEquals("MIFARE Classic 1K", dump.cardType)
        assertArrayEquals(bytes, exported)
    }

    @Test
    fun jsonDumpRoundTrips() {
        val original = CardDump(
            uid = "01:02:03:04",
            cardType = "MIFARE Classic 1K",
            createdAtEpochMillis = 1L,
            sectors = listOf(SectorKeyResult(0, keyA = "FFFFFFFFFFFF")),
            blocks = listOf(BlockRecord(0, "00112233445566778899AABBCCDDEEFF")),
        )

        val encoded = KeyFileCodec.exportDumpJson(original)
        val decoded = KeyFileCodec.importDumpJson(encoded)

        assertTrue(KeyFileCodec.looksLikeDumpJson(encoded))
        assertEquals(original, decoded)
    }

    @Test
    fun decodesTransportConfigurationAccessBits() {
        val trailer = "FFFFFFFFFFFFFF078069FFFFFFFFFFFF".hexToBytes()

        val result = decodeAccessBits(trailer)

        assertTrue(result.valid)
        assertEquals(listOf(0, 0, 0, 1), result.groups)
    }

    @Test
    fun rejectsCorruptAccessBits() {
        val trailer = "FFFFFFFFFFFF00000000FFFFFFFFFFFF".hexToBytes()

        assertFalse(decodeAccessBits(trailer).valid)
    }

    @Test
    fun extractsNdefMessageFromClassicDumpDataBlocks() {
        val ndef = "D1010C55016578616D706C652E636F6D".hexToBytes()
        val tlvBlock = (byteArrayOf(0x03, ndef.size.toByte()) + ndef + byteArrayOf(0xFE.toByte()))
            .copyOf(32)
        val dump = CardDump(
            uid = "01:02:03:04",
            cardType = "MIFARE Classic 1K",
            createdAtEpochMillis = 1L,
            sectors = emptyList(),
            blocks = listOf(
                BlockRecord(4, tlvBlock.copyOfRange(0, 16).toHex()),
                BlockRecord(5, tlvBlock.copyOfRange(16, 32).toHex()),
            ),
        )

        val extracted = NdefDumpCodec.findNdefMessage(dump)

        assertArrayEquals(ndef, extracted)
        assertTrue(NdefDumpCodec.isStructurallyValidNdef(ndef))
    }
}
