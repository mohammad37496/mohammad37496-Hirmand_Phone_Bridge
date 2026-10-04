package ir.hirmand.phonebridge.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PhoneDataCollector(private val context: Context) {
    private fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun collect(prefs: AppPrefs): JSONObject = withContext(Dispatchers.IO) {
        JSONObject().apply {
            put("schema", "hirmand.phone-bridge.v1")
            put("sentAt", System.currentTimeMillis())
            put("syncId", UUID.randomUUID().toString())
            put("device", JSONObject().apply {
                put("name", prefs.deviceName)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("androidVersion", Build.VERSION.RELEASE ?: "unknown")
                put("sdkInt", Build.VERSION.SDK_INT)
                put("id", prefs.installId)
            })
            put("deviceStats", collectDeviceStats())
            if (prefs.wifi && hasWifiPermission()) put("wifi", collectWifi())
            if (prefs.location && (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION))) {
                collectLocation()?.let { put("location", it) }
            }
            if (prefs.contacts && has(Manifest.permission.READ_CONTACTS)) put("contacts", collectContacts())
            if (prefs.calls && has(Manifest.permission.READ_CALL_LOG)) put("calls", collectCalls())
            if (prefs.sms && has(Manifest.permission.READ_SMS)) put("sms", collectSms())
            if (prefs.calendar && has(Manifest.permission.READ_CALENDAR)) put("calendar", collectCalendar())
            if (prefs.apps) put("apps", collectApps())
            put("selectedFiles", JSONArray().apply {
                prefs.selectedFiles().forEach { file ->
                    put(JSONObject(file.toString()).apply { remove("uri") })
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectLocation(): JSONObject? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val location = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time } ?: return null
        return JSONObject().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracyMeters", location.accuracy)
            put("timestamp", location.time)
        }
    }

    private fun collectDeviceStats(): JSONObject {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val stat = StatFs(Environment.getDataDirectory().path)
        val available = stat.availableBytes
        val total = stat.totalBytes
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        memory?.getMemoryInfo(memInfo)
        return JSONObject().apply {
            put("batteryPercent", level ?: JSONObject.NULL)
            put("batteryCharging", battery?.isCharging ?: false)
            put("storageAvailableBytes", available)
            put("storageTotalBytes", total)
            put("ramAvailableBytes", memInfo.availMem)
            put("ramTotalBytes", memInfo.totalMem)
            put("lowMemory", memInfo.lowMemory)
        }
    }

    private fun hasWifiPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission")
    private fun collectWifi(): JSONObject = JSONObject().apply {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wm?.connectionInfo
        put("ssid", info?.ssid?.trim('"') ?: JSONObject.NULL)
        put("linkSpeedMbps", info?.linkSpeed ?: JSONObject.NULL)
        put("rssi", info?.rssi ?: JSONObject.NULL)
        put("networkId", info?.networkId ?: JSONObject.NULL)
    }

    private fun collectContacts(): JSONArray {
        val a = JSONArray()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 200) {
                a.put(JSONObject().apply {
                    put("name", c.getString(0))
                    put("number", c.getString(1))
                })
            }
        }
        return a
    }

    private fun collectCalls(): JSONArray {
        val a = JSONArray()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 200) {
                a.put(JSONObject().apply {
                    put("number", c.getString(0))
                    put("type", c.getInt(1))
                    put("date", c.getLong(2))
                    put("durationSeconds", c.getLong(3))
                })
            }
        }
        return a
    }

    private fun collectSms(): JSONArray {
        val a = JSONArray()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY),
            null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 200) {
                a.put(JSONObject().apply {
                    put("address", c.getString(0))
                    put("date", c.getLong(1))
                    put("type", c.getInt(2))
                    put("body", c.getString(3))
                })
            }
        }
        return a
    }

    private fun collectApps(): JSONArray {
        val a = JSONArray()
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = context.packageManager.queryIntentActivities(launchIntent, 0)
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
        infos.take(300).forEach { info ->
            a.put(JSONObject().apply {
                put("packageName", info.activityInfo.packageName)
                put("label", info.loadLabel(context.packageManager).toString())
                put("activity", info.activityInfo.name)
                put("versionName", runCatching { context.packageManager.getPackageInfo(info.activityInfo.packageName, 0).versionName ?: "" }.getOrDefault(""))
                put("firstInstallTime", runCatching { context.packageManager.getPackageInfo(info.activityInfo.packageName, 0).firstInstallTime }.getOrDefault(0L))
                put("lastUpdateTime", runCatching { context.packageManager.getPackageInfo(info.activityInfo.packageName, 0).lastUpdateTime }.getOrDefault(0L))
            })
        }
        return a
    }

    private fun collectCalendar(): JSONArray {
        val a = JSONArray()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION
            ),
            null, null, "${CalendarContract.Events.DTSTART} DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 200) {
                a.put(JSONObject().apply {
                    put("title", c.getString(0))
                    put("description", c.getString(1))
                    put("start", c.getLong(2))
                    put("end", c.getLong(3))
                    put("location", c.getString(4))
                })
            }
        }
        return a
    }
}
