package io.github.nfckeyrecovery.data

import android.content.Context
import io.github.nfckeyrecovery.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class KeyRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val keysFile = File(context.filesDir, "keys/keys.json")
    private val sourcesFile = File(context.filesDir, "keys/sources.json")

    private val _keys = MutableStateFlow(loadKeys())
    val keys: StateFlow<List<KeyEntry>> = _keys.asStateFlow()

    private val _sources = MutableStateFlow(loadSources())
    val sources: StateFlow<List<RemoteKeySource>> = _sources.asStateFlow()

    fun addKeys(entries: List<KeyEntry>): Int {
        val before = _keys.value.size
        _keys.value = (_keys.value + entries)
            .filter { it.value.isMifareKey() }
            .distinctBy { it.normalizedValue }
            .sortedBy { it.normalizedValue }
        saveKeys()
        return _keys.value.size - before
    }

    fun removeKey(value: String) {
        _keys.value = _keys.value.filterNot {
            it.normalizedValue == value.uppercase()
        }
        saveKeys()
    }

    fun replaceSource(source: RemoteKeySource) {
        _sources.value = (_sources.value.filterNot { it.url == source.url } + source)
            .sortedBy { it.name.lowercase() }
        saveSources()
    }

    fun removeSource(url: String) {
        _sources.value = _sources.value.filterNot { it.url == url }
        saveSources()
    }

    suspend fun updateSource(source: RemoteKeySource): SourceUpdate = withContext(Dispatchers.IO) {
        val uri = URI(source.url)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Only HTTPS key feeds are accepted"
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "NfcKeyRecovery/${BuildConfig.VERSION_NAME}")

        try {
            val status = connection.responseCode
            require(status in 200..299) { "Key feed returned HTTP $status" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= MAX_FEED_BYTES || declaredLength == -1L) {
                "Key feed exceeds the 2 MB limit"
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_FEED_BYTES) {
                        "Key feed exceeds the 2 MB limit"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val sha256 = bytes.sha256()
            if (source.expectedSha256.isNotBlank()) {
                require(sha256.equals(source.expectedSha256, ignoreCase = true)) {
                    "SHA-256 verification failed"
                }
            }
            val imported = KeyFileCodec.parseKeys(
                bytes = bytes,
                fileName = uri.path.substringAfterLast('/').ifBlank { "feed.keys" },
                source = source.name,
            )
            val added = addKeys(imported)
            replaceSource(
                source.copy(
                    lastSha256 = sha256,
                    lastUpdatedEpochMillis = System.currentTimeMillis(),
                ),
            )
            SourceUpdate(imported.size, added, sha256)
        } finally {
            connection.disconnect()
        }
    }

    private fun loadKeys(): List<KeyEntry> {
        val stored = runCatching {
            json.decodeFromString<List<KeyEntry>>(keysFile.readText())
        }.getOrNull()
        return stored ?: DEFAULT_KEYS
    }

    private fun loadSources(): List<RemoteKeySource> {
        val stored = runCatching {
            json.decodeFromString<List<RemoteKeySource>>(sourcesFile.readText())
        }.getOrNull()
        if (stored == null) return DEFAULT_SOURCES
        return (stored + DEFAULT_SOURCES.filter { default ->
            stored.none { it.url == default.url }
        }).sortedBy { it.name.lowercase() }
    }

    private fun saveKeys() {
        keysFile.parentFile?.mkdirs()
        keysFile.writeText(json.encodeToString(_keys.value))
    }

    private fun saveSources() {
        sourcesFile.parentFile?.mkdirs()
        sourcesFile.writeText(json.encodeToString(_sources.value))
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex().lowercase()

    data class SourceUpdate(
        val fetched: Int,
        val added: Int,
        val sha256: String,
    )

    companion object {
        private const val MAX_FEED_BYTES = 2 * 1024 * 1024

        val DEFAULT_KEYS = listOf(
            KeyEntry("FFFFFFFFFFFF", "Factory default", "bundled"),
            KeyEntry("000000000000", "All zero test key", "bundled"),
            KeyEntry("A0A1A2A3A4A5", "NFC Forum MAD Key A", "bundled"),
            KeyEntry("B0B1B2B3B4B5", "NFC Forum MAD Key B", "bundled"),
            KeyEntry("D3F7D3F7D3F7", "NFC Forum content key", "bundled"),
            KeyEntry("4D3A99C351DD", "NXP public sample", "bundled"),
            KeyEntry("1A982C7E459A", "NXP public sample", "bundled"),
        )

        val DEFAULT_SOURCES = listOf(
            RemoteKeySource(
                name = "NfcKeyRecovery public key feed",
                url = "https://raw.githubusercontent.com/yulanfur2009/NfcKeyRecovery/" +
                    "main/key-feed/default-keys.json",
            ),
            RemoteKeySource(
                name = "MifareClassicTool public defaults",
                url = "https://raw.githubusercontent.com/ikarus23/MifareClassicTool/" +
                    "master/Mifare%20Classic%20Tool/app/src/main/assets/key-files/std.keys",
            ),
            RemoteKeySource(
                name = "Iceman / RRG mfc_default_keys.dic",
                url = "https://raw.githubusercontent.com/RfidResearchGroup/proxmark3/" +
                    "master/client/dictionaries/mfc_default_keys.dic",
            ),
        )
    }
}
