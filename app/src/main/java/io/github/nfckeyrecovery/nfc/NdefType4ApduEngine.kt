package io.github.nfckeyrecovery.nfc

class NdefType4ApduEngine(
    private val messageProvider: () -> ByteArray?,
) {
    private var selectedFile = SelectedFile.NONE

    fun process(command: ByteArray): ByteArray {
        if (isSelectNdefApplication(command)) {
            selectedFile = SelectedFile.NONE
            return if (messageProvider() != null) STATUS_OK
            else STATUS_CONDITIONS_NOT_SATISFIED
        }
        selectedFileFromCommand(command)?.let { file ->
            selectedFile = file
            return STATUS_OK
        }
        if (isReadBinary(command)) {
            val file = when (selectedFile) {
                SelectedFile.CAPABILITY_CONTAINER -> capabilityContainer()
                SelectedFile.NDEF -> ndefFile() ?: return STATUS_CONDITIONS_NOT_SATISFIED
                SelectedFile.NONE -> return STATUS_FILE_NOT_FOUND
            }
            val offset = ((command[2].toInt() and 0xFF) shl 8) or
                (command[3].toInt() and 0xFF)
            val requested = (command[4].toInt() and 0xFF).let {
                if (it == 0) 256 else it
            }
            if (offset > file.size) return STATUS_WRONG_PARAMETERS
            return file.copyOfRange(offset, minOf(offset + requested, file.size)) + STATUS_OK
        }
        return STATUS_INSTRUCTION_NOT_SUPPORTED
    }

    fun reset() {
        selectedFile = SelectedFile.NONE
    }

    private fun ndefFile(): ByteArray? {
        val message = messageProvider() ?: return null
        if (message.size > 0xFFFE) return null
        return byteArrayOf(
            ((message.size shr 8) and 0xFF).toByte(),
            (message.size and 0xFF).toByte(),
        ) + message
    }

    private fun capabilityContainer(): ByteArray = byteArrayOf(
        0x00, 0x0F,
        0x20,
        0x00, 0xF6.toByte(),
        0x00, 0xF6.toByte(),
        0x04, 0x06,
        0xE1.toByte(), 0x04,
        0xFF.toByte(), 0xFE.toByte(),
        0x00,
        0xFF.toByte(),
    )

    private fun isSelectNdefApplication(command: ByteArray): Boolean {
        if (command.size < 12 || command[1] != 0xA4.toByte()) return false
        val length = command[4].toInt() and 0xFF
        if (length != NDEF_AID.size || command.size < 5 + length) return false
        return command.copyOfRange(5, 5 + length).contentEquals(NDEF_AID)
    }

    private fun selectedFileFromCommand(command: ByteArray): SelectedFile? {
        if (command.size < 7 || command[1] != 0xA4.toByte()) return null
        val length = command[4].toInt() and 0xFF
        if (length != 2) return null
        val fileId = ((command[5].toInt() and 0xFF) shl 8) or
            (command[6].toInt() and 0xFF)
        return when (fileId) {
            0xE103 -> SelectedFile.CAPABILITY_CONTAINER
            0xE104 -> SelectedFile.NDEF
            else -> null
        }
    }

    private fun isReadBinary(command: ByteArray): Boolean =
        command.size >= 5 && command[1] == 0xB0.toByte()

    private enum class SelectedFile {
        NONE,
        CAPABILITY_CONTAINER,
        NDEF,
    }

    companion object {
        private val NDEF_AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01,
        )
        private val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val STATUS_WRONG_PARAMETERS = byteArrayOf(0x6B, 0x00)
        private val STATUS_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69, 0x85.toByte())
        private val STATUS_INSTRUCTION_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }
}
