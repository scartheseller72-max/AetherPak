package com.zorg.aetherpak.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zorg.aetherpak.common.AccessMode
import com.zorg.aetherpak.common.CodecType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aether_settings")

/**
 * Typed wrapper over a Preferences DataStore exposing the user's persistent defaults. All reads
 * are Flows; all writes are suspend setters.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val PREFERRED_ACCESS_MODE = stringPreferencesKey("preferred_access_mode")
        val DEFAULT_CODEC = stringPreferencesKey("default_codec")
        val INCLUDE_PRIVATE_DATA = booleanPreferencesKey("include_private_data")
        val INCLUDE_OBB = booleanPreferencesKey("include_obb")
        val INCLUDE_EXTERNAL_DATA = booleanPreferencesKey("include_external_data")
        val BACKUP_OUTPUT_DIR = stringPreferencesKey("backup_output_dir")
    }

    val preferredAccessMode: Flow<AccessMode> = store.data.map { prefs ->
        runCatching { AccessMode.valueOf(prefs[Keys.PREFERRED_ACCESS_MODE] ?: AccessMode.NONE.name) }
            .getOrDefault(AccessMode.NONE)
    }

    val defaultCodec: Flow<CodecType> = store.data.map { prefs ->
        CodecType.fromId(prefs[Keys.DEFAULT_CODEC] ?: CodecType.ZSTD.id)
    }

    val includePrivateData: Flow<Boolean> = store.data.map { it[Keys.INCLUDE_PRIVATE_DATA] ?: true }
    val includeObb: Flow<Boolean> = store.data.map { it[Keys.INCLUDE_OBB] ?: true }
    val includeExternalData: Flow<Boolean> = store.data.map { it[Keys.INCLUDE_EXTERNAL_DATA] ?: true }

    val backupOutputDir: Flow<String> = store.data.map { it[Keys.BACKUP_OUTPUT_DIR] ?: "" }

    suspend fun setPreferredAccessMode(mode: AccessMode) {
        store.edit { it[Keys.PREFERRED_ACCESS_MODE] = mode.name }
    }

    suspend fun setDefaultCodec(codec: CodecType) {
        store.edit { it[Keys.DEFAULT_CODEC] = codec.id }
    }

    suspend fun setIncludePrivateData(value: Boolean) {
        store.edit { it[Keys.INCLUDE_PRIVATE_DATA] = value }
    }

    suspend fun setIncludeObb(value: Boolean) {
        store.edit { it[Keys.INCLUDE_OBB] = value }
    }

    suspend fun setIncludeExternalData(value: Boolean) {
        store.edit { it[Keys.INCLUDE_EXTERNAL_DATA] = value }
    }

    suspend fun setBackupOutputDir(dir: String) {
        store.edit { it[Keys.BACKUP_OUTPUT_DIR] = dir }
    }
}
