package io.github.nfckeyrecovery.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class EmulationRepository(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val directory = File(context.filesDir, "emulation")
    private val profilesFile = File(directory, "profiles.json")
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<EmulationProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow(loadActiveProfile())
    val activeProfile: StateFlow<EmulationProfile?> = _activeProfile.asStateFlow()

    fun currentActiveProfile(): EmulationProfile? {
        val activeId = preferences.getString(ACTIVE_PROFILE_ID, null) ?: return null
        return loadProfiles().firstOrNull { it.id == activeId && it.supportsAndroidHce }
    }

    fun save(profile: EmulationProfile) {
        _profiles.value = (_profiles.value.filterNot { it.id == profile.id } + profile)
            .sortedByDescending { it.createdAtEpochMillis }
        persistProfiles()
    }

    fun activate(profileId: String) {
        val profile = _profiles.value.firstOrNull { it.id == profileId }
            ?: error("Card profile not found")
        require(profile.supportsAndroidHce) {
            "MIFARE Classic profiles require external emulation hardware"
        }
        preferences.edit().putString(ACTIVE_PROFILE_ID, profileId).apply()
        _activeProfile.value = profile
    }

    fun deactivate() {
        preferences.edit().remove(ACTIVE_PROFILE_ID).apply()
        _activeProfile.value = null
    }

    fun delete(profileId: String) {
        if (_activeProfile.value?.id == profileId) deactivate()
        _profiles.value = _profiles.value.filterNot { it.id == profileId }
        persistProfiles()
    }

    private fun loadProfiles(): List<EmulationProfile> =
        runCatching {
            json.decodeFromString<List<EmulationProfile>>(profilesFile.readText())
        }.getOrDefault(emptyList())

    private fun loadActiveProfile(): EmulationProfile? =
        currentActiveProfile()

    private fun persistProfiles() {
        directory.mkdirs()
        profilesFile.writeText(json.encodeToString(_profiles.value))
    }

    companion object {
        private const val PREFERENCES = "hce_preferences"
        private const val ACTIVE_PROFILE_ID = "active_profile_id"
    }
}
