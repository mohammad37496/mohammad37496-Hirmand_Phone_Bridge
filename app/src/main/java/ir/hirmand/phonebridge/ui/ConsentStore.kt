package ir.hirmand.phonebridge.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-level consent record.
 *
 * This is separate from Android runtime permissions: accepting this record never
 * grants an OS permission. Each sensitive capability still has to request its
 * own Android permission when needed.
 */
class ConsentStore(context: Context) {
    private val prefs = context.getSharedPreferences("phone_bridge_consent", Context.MODE_PRIVATE)

    companion object {
        const val CURRENT_VERSION = 1

        const val DEVICE_STATUS = "device_status"
        const val LOCATION = "location"
        const val CONTACTS_CALLS_SMS = "contacts_calls_sms"
        const val CALENDAR = "calendar"
        const val APPS = "apps"
        const val SELECTED_FILES = "selected_files"
        const val NOTIFICATIONS = "notifications"
        const val CAMERA = "camera"
        const val MICROPHONE = "microphone"

        val SCOPES = listOf(
            DEVICE_STATUS,
            LOCATION,
            CONTACTS_CALLS_SMS,
            CALENDAR,
            APPS,
            SELECTED_FILES,
            NOTIFICATIONS,
            CAMERA,
            MICROPHONE,
        )
    }

    var acceptedVersion: Int
        get() = prefs.getInt("accepted_version", 0)
        private set(value) = prefs.edit().putInt("accepted_version", value).apply()

    var acceptedAtMillis: Long
        get() = prefs.getLong("accepted_at", 0L)
        private set(value) = prefs.edit().putLong("accepted_at", value).apply()

    fun acceptedScopes(): Set<String> {
        val raw = prefs.getString("accepted_scopes", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value in SCOPES) add(value)
                }
            }
        }.getOrElse { emptySet() }
    }

    fun isAccepted(): Boolean =
        acceptedVersion >= CURRENT_VERSION && acceptedAtMillis > 0L

    fun hasScope(scope: String): Boolean =
        scope in acceptedScopes()

    fun accept(scopes: Set<String>, atMillis: Long = System.currentTimeMillis()) {
        val normalized = scopes.filter { it in SCOPES }.toSet()
        val array = JSONArray()
        normalized.sorted().forEach(array::put)

        prefs.edit()
            .putInt("accepted_version", CURRENT_VERSION)
            .putLong("accepted_at", atMillis)
            .putString("accepted_scopes", array.toString())
            .apply()
    }

    fun revoke() {
        prefs.edit()
            .putInt("accepted_version", 0)
            .putLong("accepted_at", 0L)
            .putString("accepted_scopes", "[]")
            .apply()
    }

    fun exportRecord(): JSONObject =
        JSONObject()
            .put("version", acceptedVersion)
            .put("acceptedAt", acceptedAtMillis.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("scopes", JSONArray(acceptedScopes().sorted()))
}
