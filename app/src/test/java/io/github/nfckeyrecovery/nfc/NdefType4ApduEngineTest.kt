package io.github.nfckeyrecovery.nfc

import io.github.nfckeyrecovery.data.hexToBytes
import io.github.nfckeyrecovery.data.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefType4ApduEngineTest {
    @Test
    fun servesNdefMessageThroughType4ReadSequence() {
        val message = "D1010C55016578616D706C652E636F6D".hexToBytes()
        val engine = NdefType4ApduEngine { message }

        assertEquals(
            "9000",
            engine.process("00A4040007D276000085010100".hexToBytes()).toHex(),
        )
        assertEquals(
            "9000",
            engine.process("00A4000C02E104".hexToBytes()).toHex(),
        )
        val response = engine.process("00B0000012".hexToBytes())

        assertEquals("0010", response.copyOfRange(0, 2).toHex())
        assertTrue(response.copyOfRange(2, response.size - 2).contentEquals(message))
        assertEquals("9000", response.takeLast(2).toByteArray().toHex())
    }

    @Test
    fun rejectsReadBeforeFileSelection() {
        val engine = NdefType4ApduEngine { byteArrayOf(0xD0.toByte(), 0x00, 0x00) }

        assertEquals(
            "6A82",
            engine.process("00B000000F".hexToBytes()).toHex(),
        )
    }

    @Test
    fun rejectsSelectionWhenNoProfileIsActive() {
        val engine = NdefType4ApduEngine { null }

        assertEquals(
            "6985",
            engine.process("00A4040007D276000085010100".hexToBytes()).toHex(),
        )
    }

    @Test
    fun readsCapabilityContainerAndSwitchedMessage() {
        var message = "D00000".hexToBytes()
        val engine = NdefType4ApduEngine { message }
        assertEquals(
            "9000",
            engine.process("00A4040007D276000085010100".hexToBytes()).toHex(),
        )
        assertEquals(
            "9000",
            engine.process("00A4000C02E103".hexToBytes()).toHex(),
        )
        assertEquals(
            "000F2000F600F60406E104FFFE00FF9000",
            engine.process("00B000000F".hexToBytes()).toHex(),
        )
        engine.process("00A4000C02E104".hexToBytes())
        message = "D1010154".hexToBytes()
        assertEquals(
            "0004D10101549000",
            engine.process("00B0000006".hexToBytes()).toHex(),
        )
    }
}
