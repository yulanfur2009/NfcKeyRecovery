package io.github.nfckeyrecovery.hardware

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.nfckeyrecovery.data.CardDump
import io.github.nfckeyrecovery.data.SectorKeyResult

interface ExternalRecoveryAdapter {
    val name: String
    suspend fun detect(): List<ExternalDevice>
    suspend fun recover(request: RecoveryRequest): RecoveryResult
}

data class ExternalDevice(
    val id: String,
    val displayName: String,
    val vendorId: Int,
    val productId: Int,
)

data class RecoveryRequest(
    val dump: CardDump?,
    val knownKeys: List<SectorKeyResult>,
    val strategy: RecoveryStrategy = RecoveryStrategy.AUTO,
)

enum class RecoveryStrategy {
    AUTO,
    NESTED,
    HARDNESTED,
    STATIC_NONCE,
}

sealed interface RecoveryResult {
    data class Success(val keys: List<SectorKeyResult>) : RecoveryResult
    data class Unsupported(val reason: String) : RecoveryResult
    data class Failed(val message: String) : RecoveryResult
}

class Proxmark3UsbAdapter(context: Context) : ExternalRecoveryAdapter {
    private val usbManager = context.getSystemService(UsbManager::class.java)

    override val name: String = "Proxmark3 USB"

    override suspend fun detect(): List<ExternalDevice> =
        usbManager.deviceList.values
            .filter(::looksLikeProxmark)
            .map { device ->
                ExternalDevice(
                    id = device.deviceName,
                    displayName = device.productName ?: "Proxmark3-compatible USB device",
                    vendorId = device.vendorId,
                    productId = device.productId,
                )
            }

    override suspend fun recover(request: RecoveryRequest): RecoveryResult =
        RecoveryResult.Unsupported(
            "USB transport is ready for device discovery. Recovery commands require " +
                "validation against the selected Proxmark3 model and firmware.",
        )

    private fun looksLikeProxmark(device: UsbDevice): Boolean {
        val name = "${device.manufacturerName} ${device.productName}".lowercase()
        return name.contains("proxmark") ||
            device.vendorId in setOf(0x2D2D, 0x9AC4, 0x1D50)
    }
}
