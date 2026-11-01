package io.github.nfckeyrecovery.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object KeyFileCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun parseKeys(bytes: ByteArray, fileName: String, source: String): List<KeyEntry> {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "json" -> parseJsonKeys(bytes.decodeToString(), source)
            else -> parseTextKeys(bytes.decodeToString(), source)
        }.distinctBy { it.normalizedValue }
    }

    fun parseTextKeys(text: String, source: String): List<KeyEntry> =
        text.lineSequence()
            .map { it.substringBefore('#').substringBefore(';').trim() }
            .filter { it.isMifareKey() }
            .map { KeyEntry(it.uppercase(), source = source) }
            .distinctBy { it.normalizedValue }
            .toList()

    fun parseJsonKeys(text: String, source: String): List<KeyEntry> {
        val element = json.parseToJsonElement(text)
        val entries = when (element) {
            is JsonArray -> element
            is JsonObject -> element["keys"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return entries.mapNotNull { item ->
            when (item) {
                is JsonObject -> {
                    val value = item["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (!value.isMifareKey()) return@mapNotNull null
                    KeyEntry(
                        value = value.uppercase(),
                        label = item["label"]?.jsonPrimitive?.content.orEmpty(),
                        source = source,
                    )
                }
                else -> {
                    val value = item.jsonPrimitive.content
                    value.takeIf(String::isMifareKey)
                        ?.let { KeyEntry(it.uppercase(), source = source) }
                }
            }
        }
    }

    fun exportKeys(keys: List<KeyEntry>): ByteArray =
        keys.joinToString("\n", postfix = "\n") { entry ->
            if (entry.label.isBlank()) entry.normalizedValue
            else "${entry.normalizedValue} # ${entry.label}"
        }.encodeToByteArray()

    fun exportKeyFeed(feed: KeyFeed): ByteArray =
        json.encodeToString(feed).encodeToByteArray()

    fun exportDumpJson(dump: CardDump): ByteArray =
        json.encodeToString(dump).encodeToByteArray()

    fun exportMfd(dump: CardDump): ByteArray {
        val maxBlock = dump.blocks.maxOfOrNull { it.block } ?: return ByteArray(0)
        val output = ByteArray((maxBlock + 1) * 16)
        dump.blocks.filter { it.readable }.forEach { block ->
            val bytes = runCatching { block.hex.hexToBytes() }.getOrNull()
            if (bytes?.size == 16) {
                bytes.copyInto(output, block.block * 16)
            }
        }
        return output
    }

    fun importMfd(bytes: ByteArray, uid: String = ""): CardDump {
        require(bytes.isNotEmpty() && bytes.size % 16 == 0) {
            "Raw dump size must be a non-zero multiple of 16 bytes"
        }
        return CardDump(
            uid = uid,
            cardType = when (bytes.size) {
                320 -> "MIFARE Classic Mini"
                1024 -> "MIFARE Classic 1K"
                2048 -> "MIFARE Classic 2K"
                4096 -> "MIFARE Classic 4K"
                else -> "MIFARE Classic compatible"
            },
            createdAtEpochMillis = System.currentTimeMillis(),
            sectors = emptyList(),
            blocks = bytes.toList()
                .chunked(16)
                .mapIndexed { index, values ->
                    BlockRecord(index, values.toByteArray().toHex())
                },
        )
    }
}
