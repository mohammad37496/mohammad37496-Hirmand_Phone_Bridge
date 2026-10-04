package ir.hirmand.phonebridge.data

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("phone_bridge", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString("endpoint", "https://www.hirmandrealestate.ir/api/device-sync/v1") ?: ""
        set(value) = prefs.edit().putString("endpoint", value.trim()).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    var installId: String
        get() = prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).apply() }
        set(value) = prefs.edit().putString("install_id", value.trim()).apply()

    var deviceName: String
        get() = prefs.getString("device_name", "گوشی من") ?: "گوشی من"
        set(value) = prefs.edit().putString("device_name", value.trim()).apply()

    var location: Boolean
        get() = prefs.getBoolean("location", false)
        set(value) = prefs.edit().putBoolean("location", value).apply()

    var wifi: Boolean
        get() = prefs.getBoolean("wifi", false)
        set(value) = prefs.edit().putBoolean("wifi", value).apply()

    var contacts: Boolean
        get() = prefs.getBoolean("contacts", false)
        set(value) = prefs.edit().putBoolean("contacts", value).apply()

    var calls: Boolean
        get() = prefs.getBoolean("calls", false)
        set(value) = prefs.edit().putBoolean("calls", value).apply()

    var sms: Boolean
        get() = prefs.getBoolean("sms", false) ?: false
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
            buildList { for (i in 0 until a.length()) add(a.getJSONObject(i)) }
        }.getOrElse { emptyList() }
    }

    fun setSelectedFiles(files: List<JSONObject>) {
        val a = JSONArray()
        files.forEach { a.put(it) }
        prefs.edit().putString("selected_files", a.toString()).apply()
    }

    fun addSelectedFile(file: JSONObject) {
        val files = selectedFiles().toMutableList()
        files.removeAll { it.optString("uri") == file.optString("uri") }
        files.add(file)
        setSelectedFiles(files.takeLast(20))
    }

    fun removeSelectedFile(uri: String) {
        setSelectedFiles(selectedFiles().filterNot { it.optString("uri") == uri })
    }

    fun markSelectedFileUploaded(uri: String, sha256: String, fileId: String?) {
        val updated = selectedFiles().map { file ->
            if (file.optString("uri") == uri) {
                JSONObject(file.toString()).apply {
                    put("lastUploadedHash", sha256)
                    put("lastUploadedAt", System.currentTimeMillis())
                    if (!fileId.isNullOrBlank()) put("lastUploadedFileId", fileId)
                }
            } else file
        }
        setSelectedFiles(updated)
    }

    fun clearSelectedFiles() {
        setSelectedFiles(emptyList())
    }

    var lastSuccessfulSyncAt: Long
        get() = prefs.getLong("last_successful_sync_at", 0L)
        set(value) = prefs.edit().putLong("last_successful_sync_at", value).apply()
}
