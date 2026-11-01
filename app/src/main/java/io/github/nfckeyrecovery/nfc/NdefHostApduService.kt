package io.github.nfckeyrecovery.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import io.github.nfckeyrecovery.data.EmulationRepository
import io.github.nfckeyrecovery.data.hexToBytes

class NdefHostApduService : HostApduService() {
    private val repository by lazy { EmulationRepository(applicationContext) }
    private val engine by lazy {
        NdefType4ApduEngine {
            repository.currentActiveProfile()
                ?.ndefMessageHex
                ?.hexToBytes()
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray =
        engine.process(commandApdu)

    override fun onDeactivated(reason: Int) {
        engine.reset()
    }
}
