package ir.hirmand.phonebridge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("phone_bridge", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString("endpoint", "https://www.hirmandrealestate.ir/api/device-sync/v1") ?: ""
        set(value) = prefs.edit().putString("endpoint", value.trim()).apply()

    var token: String
        get() = readEncryptedToken()
        set(value) = writeEncryptedToken(value.trim())

    var installId: String
        get() = prefs.getString("install_id", null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("install_id", it).apply()
            }
        set(value) = prefs.edit().putString("install_id", value.trim()).apply()

    var deviceName: String
        get() = prefs.getString("device_name", "گوشی من") ?: "گوشی من"
        set(value) = prefs.edit().putString("device_name", value.trim()).apply()

    var location: Boolean
        get() = prefs.getBoolean("location", false)
        set(value) = prefs.edit().putBoolean("location", value).apply()

    var locationTrackingEnabled: Boolean
        get() = prefs.getBoolean("location_tracking_enabled", false)
        set(value) = prefs.edit().putBoolean("location_tracking_enabled", value).apply()

    var locationIntervalMinutes: Int
        get() = prefs.getInt("location_interval_minutes", 15).let { if (it in listOf(5, 15, 30, 60)) it else 15 }
        set(value) {
            val normalized = if (value in listOf(5, 15, 30, 60)) value else 15
            prefs.edit().putInt("location_interval_minutes", normalized).apply()
        }

    var lastLocationStatus: String
        get() = prefs.getString("location_tracking_status", "") ?: ""
        set(value) = prefs.edit().putString("location_tracking_status", value.take(300)).apply()

    var appBlockingEnabled: Boolean
        get() = prefs.getBoolean("app_blocking_enabled", false)
        set(value) = prefs.edit().putBoolean("app_blocking_enabled", value).apply()

    var lastAppBlockingStatus: String
        get() = prefs.getString("app_blocking_status", "") ?: ""
        set(value) = prefs.edit().putString("app_blocking_status", value.take(300)).apply()

    fun appBlockRules(): List<JSONObject> {
        val raw = prefs.getString("app_block_rules", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) add(a.getJSONObject(i))
            }
        }.getOrElse { emptyList() }
    }

    fun setAppBlockRules(rules: List<JSONObject>) {
        val a = JSONArray()
        rules.take(200).forEach { a.put(JSONObject(it.toString())) }
        prefs.edit().putString("app_block_rules", a.toString()).apply()
    }

    fun pendingLocations(): List<JSONObject> {
        val raw = prefs.getString("pending_locations", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) add(a.getJSONObject(i))
            }
        }.getOrElse { emptyList() }
    }

    fun addPendingLocation(point: JSONObject) {
        val items = pendingLocations().toMutableList()
        items.removeAll { it.optString("clientPointId") == point.optString("clientPointId") }
        items.add(JSONObject(point.toString()))
        val a = JSONArray()
        items.takeLast(1000).forEach { a.put(it) }
        prefs.edit().putString("pending_locations", a.toString()).apply()
    }

    fun removePendingLocation(clientPointId: String) {
        val a = JSONArray()
        pendingLocations().filterNot { it.optString("clientPointId") == clientPointId }.forEach { a.put(it) }
        prefs.edit().putString("pending_locations", a.toString()).apply()
    }

    var wifi: Boolean
        get() = prefs.getBoolean("wifi", false)
        set(value) = prefs.edit().putBoolean("wifi", value).apply()

    var contacts: Boolean
        get() = prefs.getBoolean("contacts", false)
        set(value) = prefs.edit().putBoolean("contacts", value).apply()

    var calls: Boolean
        get() = prefs.getBoolean("calls", false)
        set(value) = prefs.edit().putBoolean("calls", value).apply()

    var callRecordingEnabled: Boolean
        get() = prefs.getBoolean("call_recording_enabled", false)
        set(value) = prefs.edit().putBoolean("call_recording_enabled", value).apply()

    var lastCallRecordingStatus: String
        get() = prefs.getString("call_recording_status", "") ?: ""
        set(value) = prefs.edit().putString("call_recording_status", value.take(300)).apply()

    var callDirectionHint: String
        get() = prefs.getString("call_direction_hint", "") ?: ""
        set(value) = prefs.edit().putString("call_direction_hint", value.take(20)).apply()

    fun pendingCallRecordings(): List<JSONObject> {
        val raw = prefs.getString("pending_call_recordings", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) add(a.getJSONObject(i))
            }
        }.getOrElse { emptyList() }
    }

    fun addPendingCallRecording(recording: JSONObject) {
        val items = pendingCallRecordings().toMutableList()
        items.removeAll { it.optString("path") == recording.optString("path") }
        items.add(JSONObject(recording.toString()))
        val a = JSONArray()
        items.takeLast(50).forEach { a.put(it) }
        prefs.edit().putString("pending_call_recordings", a.toString()).apply()
    }

    fun removePendingCallRecording(path: String) {
        val a = JSONArray()
        pendingCallRecordings()
            .filterNot { it.optString("path") == path }
            .forEach { a.put(it) }
        prefs.edit().putString("pending_call_recordings", a.toString()).apply()
    }

    fun updatePendingCallRecording(path: String, update: JSONObject) {
        val a = JSONArray()
        pendingCallRecordings().forEach { item ->
            if (item.optString("path") == path) {
                val merged = JSONObject(item.toString())
                update.keys().forEach { key -> merged.put(key, update.opt(key)) }
                a.put(merged)
            } else {
                a.put(item)
            }
        }
        prefs.edit().putString("pending_call_recordings", a.toString()).apply()
    }

    var sms: Boolean
        get() = prefs.getBoolean("sms", false)
        set(value) = prefs.edit().putBoolean("sms", value).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", false)
        set(value) = prefs.edit().putBoolean("auto_sync", value).apply()

    var apps: Boolean
        get() = prefs.getBoolean("apps", false)
        set(value) = prefs.edit().putBoolean("apps", value).apply()

    var calendar: Boolean
        get() = prefs.getBoolean("calendar", false)
        set(value) = prefs.edit().putBoolean("calendar", value).apply()

    fun selectedFiles(): List<JSONObject> {
        val raw = prefs.getString("selected_files", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) add(a.getJSONObject(i))
            }
        }.getOrElse { emptyList() }
    }

    fun setSelectedFiles(files: List<JSONObject>) {
        val a = JSONArray()
        files.forEach { a.put(it) }
        prefs.edit().putString("selected_files", a.toString()).apply()
    }

    fun addSelectedFile(file: JSONObject) {
        val files = selectedFiles().toMutableList()
        val fresh = JSONObject(file.toString()).apply {
            remove("policyBlocked")
            remove("policyBlockedReason")
            remove("policyBlockedAt")
        }
        files.removeAll { it.optString("uri") == file.optString("uri") }
        files.add(fresh)
        setSelectedFiles(files.takeLast(20))
    }

    fun removeSelectedFile(uri: String) {
        setSelectedFiles(selectedFiles().filterNot { it.optString("uri") == uri })
    }

    fun markSelectedFileBlocked(uri: String, reason: String) {
        val updated = selectedFiles().map { file ->
            if (file.optString("uri") == uri) {
                JSONObject(file.toString()).apply {
                    put("policyBlocked", true)
                    put("policyBlockedReason", reason.take(120))
                    put("policyBlockedAt", System.currentTimeMillis())
                }
            } else file
        }
        setSelectedFiles(updated)
    }

    fun markSelectedFileUploaded(uri: String, sha256: String, fileId: String?) {
        val updated = selectedFiles().map { file ->
            if (file.optString("uri") == uri) {
                JSONObject(file.toString()).apply {
                    put("lastUploadedHash", sha256)
                    put("lastUploadedAt", System.currentTimeMillis())
                    if (!fileId.isNullOrBlank()) put("lastUploadedFileId", fileId)
                }
            } else {
                file
            }
        }
        setSelectedFiles(updated)
    }

    fun clearSelectedFiles() {
        setSelectedFiles(emptyList())
    }

    var lastSuccessfulSyncAt: Long
        get() = prefs.getLong("last_successful_sync_at", 0L)
        set(value) = prefs.edit().putLong("last_successful_sync_at", value).apply()

    var lastSnapshotHash: String
        get() = prefs.getString("last_snapshot_hash", "") ?: ""
        set(value) = prefs.edit().putString("last_snapshot_hash", value.trim()).apply()

    private fun readEncryptedToken(): String {
        val encrypted = prefs.getString(TOKEN_KEY, null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching { decrypt(encrypted) }.getOrElse { "" }
        }

        val legacy = prefs.getString(LEGACY_TOKEN_KEY, "")?.trim().orEmpty()
        if (legacy.isNotBlank()) {
            writeEncryptedToken(legacy)
            prefs.edit().remove(LEGACY_TOKEN_KEY).apply()
        }
        return legacy
    }

    private fun writeEncryptedToken(value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(TOKEN_KEY).remove(LEGACY_TOKEN_KEY).apply()
            return
        }

        runCatching {
            prefs.edit()
                .putString(TOKEN_KEY, encrypt(value))
                .remove(LEGACY_TOKEN_KEY)
                .apply()
        }.onFailure {
            prefs.edit()
                .remove(TOKEN_KEY)
                .putString(LEGACY_TOKEN_KEY, value)
                .apply()
        }
    }

    private fun getKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hirmand_phone_bridge_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TOKEN_KEY = "token_v2"
        const val LEGACY_TOKEN_KEY = "token"
    }
}
