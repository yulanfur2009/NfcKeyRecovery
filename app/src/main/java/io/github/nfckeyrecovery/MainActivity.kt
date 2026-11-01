package io.github.nfckeyrecovery

import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.nfckeyrecovery.ui.NfcKeyRecoveryApp
import io.github.nfckeyrecovery.nfc.NdefHostApduService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val cardEmulation by lazy {
        nfcAdapter?.takeIf {
            packageManager.hasSystemFeature("android.hardware.nfc.hce")
        }?.let(CardEmulation::getInstance)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NfcKeyRecoveryApp(viewModel)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.activeEmulationProfile.collect { profile ->
                    if (profile == null) {
                        cardEmulation?.unsetPreferredService(this@MainActivity)
                        enableNfcReaderMode()
                    } else {
                        nfcAdapter?.disableReaderMode(this@MainActivity)
                        cardEmulation?.setPreferredService(
                            this@MainActivity,
                            ComponentName(this@MainActivity, NdefHostApduService::class.java),
                        )
                    }
                }
            }
        }
    }

    private fun enableNfcReaderMode() {
        nfcAdapter?.enableReaderMode(
            this,
            viewModel::onTag,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B,
            Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            },
        )
    }

    override fun onPause() {
        cardEmulation?.unsetPreferredService(this)
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }
}
