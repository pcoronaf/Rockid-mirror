package com.rokidmirror.sender.control

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "view_profiles")

/** Local persistence for [ViewProfile]s plus the last selected profile and preset. */
class ProfileRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profilesKey = stringPreferencesKey("profiles_json")
    private val activeKey = stringPreferencesKey("active_profile_id")
    private val presetKey = stringPreferencesKey("preset")
    private val serializer = ListSerializer(ViewProfile.serializer())

    val profiles: Flow<List<ViewProfile>> = context.profileStore.data.map { prefs ->
        prefs[profilesKey]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: ViewProfile.defaults
    }

    val activeProfileId: Flow<String> = context.profileStore.data.map { it[activeKey] ?: ViewProfile.FULL_SCREEN_ID }
    val preset: Flow<String?> = context.profileStore.data.map { it[presetKey] }

    suspend fun save(profile: ViewProfile) {
        val current = profiles.first().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current += profile
        context.profileStore.edit { it[profilesKey] = json.encodeToString(serializer, current) }
    }

    suspend fun delete(id: String) {
        val current = profiles.first().filterNot { it.id == id }
        context.profileStore.edit { it[profilesKey] = json.encodeToString(serializer, current.ifEmpty { ViewProfile.defaults }) }
    }

    suspend fun setActive(id: String) { context.profileStore.edit { it[activeKey] = id } }
    suspend fun setPreset(wireName: String) { context.profileStore.edit { it[presetKey] = wireName } }
}
