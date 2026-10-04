package ir.hirmand.phonebridge.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.sync.SignedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RemoteControlService : Service() {
    companion object {
        const val ACTION_START = "ir.hirmand.phonebridge.remote.START"
        const val ACTION_STOP = "ir.hirmand.phonebridge.remote.STOP"
        private const val CHANNEL_ID = "remote_control"
        private const val NOTIFICATION_ID = 2410
        private const val POLL_MS = 5_000L
        private const val LOCATION_TIMEOUT_MS = 20_000L
        private const val DATA_CHUNK_MAX_BYTES = 350 * 1024
    }

    private lateinit var prefs: AppPrefs
    private lateinit var locationManager: LocationManager
    private val thread = HandlerThread("hirmand-remote").apply { start() }
    private val worker = Handler(thread.looper)
    private var polling = false
    private var locationListener: LocationListener? = null
    private var locationTimeout: Runnable? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRemote()
            ACTION_START -> startRemote()
        }
        return START_STICKY
    }

    private fun startRemote() {
        if (!prefs.remoteControlEnabled) {
            stopSelf()
            return
        }
        if (!hasFineLocation()) {
            prefs.lastRemoteControlStatus = "برای ریموت کنترل لوکیشن، مجوز دقیق GPS لازم است"
            stopSelf()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            prefs.lastRemoteControlStatus = "GPS خاموش است؛ ریموت کنترل لوکیشن آماده نیست"
            stopSelf()
            return
        }

        val notification = buildNotification("ریموت کنترل فعال · منتظر فرمان")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (polling) return
        polling = true
        prefs.lastRemoteControlStatus = "ریموت کنترل فعال است · منتظر فرمان پنل"
        worker.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!polling || !prefs.remoteControlEnabled) return
            fetchCommand()
            worker.postDelayed(this, POLL_MS)
        }
    }

    private fun fetchCommand() {
        val endpoint = prefs.endpoint.trim()
        val token = prefs.token.trim()
        val deviceId = prefs.installId.trim()
        if (!EndpointPolicy.isAllowed(endpoint) || token.isBlank() || deviceId.isBlank()) return

        runCatching {
            val empty = ByteArray(0)
            val url = endpoint.trimEnd('/') + "/remote-control/command?deviceId=" +
                URLEncoder.encode(deviceId, "UTF-8")
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $token")
                .header("X-Hirmand-Device-Id", deviceId)
            SignedRequest.addHeaders(builder, token, deviceId, empty)

            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return
                val json = JSONObject(response.body?.string().orEmpty())
                val command = json.optJSONObject("command") ?: return
                val id = command.optString("id").trim()
                when (command.optString("action")) {
                    "get_location" -> if (id.isNotBlank()) handleGetLocation(id)
                    "restore_data" -> if (id.isNotBlank()) handleRestoreData(id, command.optJSONObject("payload") ?: JSONObject())
                }
            }
        }.onFailure {
            prefs.lastRemoteControlStatus = "ارتباط با فرمان ریموت برقرار نشد؛ دوباره تلاش می‌شود"
        }
    }

    private fun handleGetLocation(commandId: String) {
        if (!hasFineLocation()) {
            postLocationResult(commandId, false, null, "مجوز دقیق GPS در دسترس نیست")
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            postLocationResult(commandId, false, null, "GPS خاموش است")
            return
        }

        prefs.lastRemoteControlStatus = "فرمان گرفتن لوکیشن دریافت شد · در حال تعیین موقعیت"
        clearLocationRequest()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                clearLocationRequest()
                postLocationResult(commandId, true, location, null)
            }
        }
        locationListener = listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    { runnable -> worker.post(runnable) },
                ) { location ->
                    if (location != null) {
                        clearLocationRequest()
                        postLocationResult(commandId, true, location, null)
                    } else {
                        clearLocationRequest()
                        postLocationResult(commandId, false, null, "GPS نتوانست موقعیت فعلی را تعیین کند")
                    }
                }
            }.onFailure {
                requestSingleUpdate(listener)
            }
        } else {
            requestSingleUpdate(listener)
        }

        val timeout = Runnable {
            if (locationListener != null) {
                clearLocationRequest()
                postLocationResult(commandId, false, null, "دریافت موقعیت GPS در مهلت تعیین‌شده انجام نشد")
            }
        }
        locationTimeout = timeout
        worker.postDelayed(timeout, LOCATION_TIMEOUT_MS)
    }

    private fun requestSingleUpdate(listener: LocationListener) {
        runCatching {
            locationManager.requestSingleUpdate(
                LocationManager.GPS_PROVIDER,
                listener,
                worker.looper,
            )
        }.onFailure {
            clearLocationRequest()
        }
    }

    private fun clearLocationRequest() {
        locationListener?.let { runCatching { locationManager.removeUpdates(it) } }
        locationListener = null
        locationTimeout?.let(worker::removeCallbacks)
        locationTimeout = null
    }

    private fun postLocationResult(commandId: String, success: Boolean, location: Location?, error: String?) {
        val result = if (success && location != null) {
            JSONObject()
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracyMeters", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
                .put("altitudeMeters", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
                .put("speedMps", if (location.hasSpeed()) location.speed else JSONObject.NULL)
                .put("bearingDegrees", if (location.hasBearing()) location.bearing else JSONObject.NULL)
                .put("provider", location.provider ?: "gps")
                .put("recordedAt", location.time)
        } else {
            JSONObject()
        }
        postJson(
            path = "/remote-control/result",
            body = JSONObject()
                .put("deviceId", prefs.installId)
                .put("commandId", commandId)
                .put("action", "get_location")
                .put("success", success)
                .put("error", error ?: JSONObject.NULL)
                .put("result", result),
        )
    }

    private fun handleRestoreData(commandId: String, payload: JSONObject) {
        val dataType = payload.optString("dataType").trim()
        val requestedCount = payload.optInt("requestedCount", 0)
        val allowedCounts = setOf(15, 30, 60, 100, 250, 500, 1000, 5000, 10000)
        if (requestedCount !in allowedCounts) {
            postDataFailure(commandId, "تعداد درخواستی ریموت معتبر نیست")
            return
        }

        when (dataType) {
            "sms" -> {
                if (!prefs.remoteRestoreSmsEnabled) {
                    postDataFailure(commandId, "بازگردانی پیامک‌های ریموت در خود گوشی فعال نشده است")
                    return
                }
                if (!has(Manifest.permission.READ_SMS)) {
                    postDataFailure(commandId, "مجوز خواندن پیامک روی گوشی فعال نیست")
                    return
                }
                prefs.lastRemoteControlStatus = "بازگردانی پیامک‌ها · در حال جمع‌آوری $requestedCount مورد آخر"
                val rows = collectRemoteSms(requestedCount)
                postDataChunks(commandId, dataType, rows)
            }
            "incoming_calls" -> {
                if (!prefs.remoteRestoreIncomingCallsEnabled) {
                    postDataFailure(commandId, "بازگردانی تماس‌های دریافتی در خود گوشی فعال نشده است")
                    return
                }
                if (!has(Manifest.permission.READ_CALL_LOG)) {
                    postDataFailure(commandId, "مجوز خواندن تاریخچه تماس‌ها روی گوشی فعال نیست")
                    return
                }
                prefs.lastRemoteControlStatus = "بازگردانی تماس‌های دریافتی · در حال جمع‌آوری $requestedCount مورد آخر"
                val rows = collectRemoteIncomingCalls(requestedCount)
                postDataChunks(commandId, dataType, rows)
            }
            else -> postDataFailure(commandId, "نوع دیتای ریموت معتبر نیست")
        }
    }

    @Suppress("MissingPermission")
    private fun collectRemoteSms(limit: Int): List<JSONObject> {
        val result = ArrayList<JSONObject>(limit)
        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.BODY,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID,
            ),
            null,
            null,
            Telephony.Sms.DATE + " DESC",
        )?.use { c ->
            val addressIndex = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val dateIndex = c.getColumnIndex(Telephony.Sms.DATE)
            val bodyIndex = c.getColumnIndex(Telephony.Sms.BODY)
            val readIndex = c.getColumnIndex(Telephony.Sms.READ)
            val threadIndex = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            while (c.moveToNext() && result.size < limit) {
                result.add(JSONObject().apply {
                    put("address", if (addressIndex >= 0) c.getString(addressIndex) ?: "" else "")
                    put("date", if (dateIndex >= 0 && !c.isNull(dateIndex)) c.getLong(dateIndex) else 0L)
                    put("body", if (bodyIndex >= 0) c.getString(bodyIndex) ?: "" else "")
                    put("read", readIndex >= 0 && !c.isNull(readIndex) && c.getInt(readIndex) != 0)
                    put("threadId", if (threadIndex >= 0 && !c.isNull(threadIndex)) c.getLong(threadIndex) else 0L)
                })
            }
        }
        return result
    }

    @Suppress("MissingPermission")
    private fun collectRemoteIncomingCalls(limit: Int): List<JSONObject> {
        val result = ArrayList<JSONObject>(limit)
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NEW,
                CallLog.Calls.PRESENTATION,
            ),
            CallLog.Calls.TYPE + "=?",
            arrayOf(CallLog.Calls.INCOMING_TYPE.toString()),
            CallLog.Calls.DATE + " DESC",
        )?.use { c ->
            val numberIndex = c.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIndex = c.getColumnIndex(CallLog.Calls.DATE)
            val durationIndex = c.getColumnIndex(CallLog.Calls.DURATION)
            val newIndex = c.getColumnIndex(CallLog.Calls.NEW)
            val presentationIndex = c.getColumnIndex(CallLog.Calls.PRESENTATION)
            while (c.moveToNext() && result.size < limit) {
                result.add(JSONObject().apply {
                    put("number", if (numberIndex >= 0) c.getString(numberIndex) ?: "" else "")
                    put("date", if (dateIndex >= 0 && !c.isNull(dateIndex)) c.getLong(dateIndex) else 0L)
                    put("durationSeconds", if (durationIndex >= 0 && !c.isNull(durationIndex)) c.getLong(durationIndex) else 0L)
                    put("new", newIndex >= 0 && !c.isNull(newIndex) && c.getInt(newIndex) != 0)
                    put("presentation", if (presentationIndex >= 0 && !c.isNull(presentationIndex)) c.getInt(presentationIndex) else 0)
                })
            }
        }
        return result
    }

    private fun postDataChunks(commandId: String, dataType: String, rows: List<JSONObject>) {
        val chunks = ArrayList<JSONArray>()
        var current = JSONArray()
        var currentBytes = 2

        rows.forEach { row ->
            val rowBytes = row.toString().toByteArray(Charsets.UTF_8).size
            if (current.length() > 0 && currentBytes + rowBytes + 1 > DATA_CHUNK_MAX_BYTES) {
                chunks.add(current)
                current = JSONArray()
                currentBytes = 2
            }
            current.put(row)
            currentBytes += rowBytes + 1
        }
        if (current.length() > 0 || rows.isEmpty()) chunks.add(current)

        for (index in chunks.indices) {
            val finalChunk = index == chunks.lastIndex
            val body = JSONObject()
                .put("deviceId", prefs.installId)
                .put("commandId", commandId)
                .put("action", "restore_data")
                .put("dataType", dataType)
                .put("chunkIndex", index)
                .put("chunkCount", chunks.size)
                .put("totalCount", rows.size)
                .put("final", finalChunk)
                .put("success", true)
                .put("rows", chunks[index])
            if (!postJson(path = "/remote-control/data", body = body)) {
                prefs.lastRemoteControlStatus = "ارسال داده‌های بازگردانی‌شده ناموفق بود؛ فرمان دوباره بررسی می‌شود"
                return
            }
        }
        prefs.lastRemoteControlStatus =
            "بازگردانی " + (if (dataType == "sms") "پیامک" else "تماس دریافتی") +
            " کامل شد · " + rows.size + " مورد دریافت شد"
    }

    private fun postDataFailure(commandId: String, error: String) {
        postJson(
            path = "/remote-control/data",
            body = JSONObject()
                .put("deviceId", prefs.installId)
                .put("commandId", commandId)
                .put("action", "restore_data")
                .put("dataType", "")
                .put("success", false)
                .put("error", error)
                .put("rows", JSONArray()),
        )
        prefs.lastRemoteControlStatus = error
    }

    private fun postJson(path: String, body: JSONObject): Boolean {
        val endpoint = prefs.endpoint.trim()
        val token = prefs.token.trim()
        val deviceId = prefs.installId.trim()
        if (!EndpointPolicy.isAllowed(endpoint) || token.isBlank() || deviceId.isBlank()) return false

        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val requestBuilder = Request.Builder()
            .url(endpoint.trimEnd('/') + path)
            .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("X-Hirmand-Device-Id", deviceId)
        SignedRequest.addHeaders(requestBuilder, token, deviceId, bytes)

        return runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation() = has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun stopRemote() {
        polling = false
        worker.removeCallbacks(poll)
        clearLocationRequest()
        prefs.lastRemoteControlStatus = "ریموت کنترل خاموش است"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        polling = false
        worker.removeCallbacks(poll)
        clearLocationRequest()
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ریموت کنترل Phone Bridge",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "اعلان قابل مشاهده هنگام آماده‌بودن Phone Bridge برای فرمان ریموت"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Phone Bridge · ریموت کنترل")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
