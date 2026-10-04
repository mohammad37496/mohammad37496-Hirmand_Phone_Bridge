package ir.hirmand.phonebridge.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.provider.CallLog
import android.provider.Telephony
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.ContextCompat
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.sync.SignedRequest
import ir.hirmand.phonebridge.ui.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RemoteControlService : Service() {
    companion object {
        const val ACTION_START = "ir.hirmand.phonebridge.remote.START"
        const val ACTION_STOP = "ir.hirmand.phonebridge.remote.STOP"
        const val ACTION_APPROVE_DATA = "ir.hirmand.phonebridge.remote.APPROVE_DATA"
        const val ACTION_DENY_DATA = "ir.hirmand.phonebridge.remote.DENY_DATA"
        private const val CHANNEL_ID = "remote_control"
        private const val NOTIFICATION_ID = 2410
        private const val POLL_MS = 5_000L
        private const val LOCATION_TIMEOUT_MS = 20_000L
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
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
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
            ACTION_APPROVE_DATA -> approvePendingData()
            ACTION_DENY_DATA -> denyPendingData()
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
                java.net.URLEncoder.encode(deviceId, "UTF-8")
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
                }
            }
        }.onFailure {
            prefs.lastRemoteControlStatus = "ارتباط با فرمان ریموت برقرار نشد؛ دوباره تلاش می‌شود"
        }
    }

    private fun handleGetLocation(commandId: String) {
        if (!hasFineLocation()) {
            postResult(commandId, false, null, "مجوز دقیق GPS در دسترس نیست")
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            postResult(commandId, false, null, "GPS خاموش است")
            return
        }

        prefs.lastRemoteControlStatus = "فرمان گرفتن لوکیشن دریافت شد · در حال تعیین موقعیت"
        clearLocationRequest()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                clearLocationRequest()
                postResult(commandId, true, location, null)
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
                        postResult(commandId, true, location, null)
                    } else {
                        clearLocationRequest()
                        postResult(commandId, false, null, "GPS نتوانست موقعیت فعلی را تعیین کند")
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
                postResult(commandId, false, null, "دریافت موقعیت GPS در مهلت تعیین‌شده انجام نشد")
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
            postResult("", false, null, "درخواست موقعیت GPS از این دستگاه ممکن نشد")
        }
    }

    private fun clearLocationRequest() {
        locationListener?.let { runCatching { locationManager.removeUpdates(it) } }
        locationListener = null
        locationTimeout?.let(worker::removeCallbacks)
        locationTimeout = null
    }

    private fun postResult(commandId: String, success: Boolean, location: Location?, error: String?) {
        if (commandId.isBlank()) return
        val endpoint = prefs.endpoint.trim()
        val token = prefs.token.trim()
        val deviceId = prefs.installId.trim()
        if (!EndpointPolicy.isAllowed(endpoint) || token.isBlank() || deviceId.isBlank()) return

        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("commandId", commandId)
            .put("action", "get_location")
            .put("success", success)
            .put("error", error ?: JSONObject.NULL)

        if (success && location != null) {
            body.put("result", JSONObject()
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracyMeters", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
                .put("altitudeMeters", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
                .put("speedMps", if (location.hasSpeed()) location.speed else JSONObject.NULL)
                .put("bearingDegrees", if (location.hasBearing()) location.bearing else JSONObject.NULL)
                .put("provider", location.provider ?: "gps")
                .put("recordedAt", location.time))
        } else {
            body.put("result", JSONObject())
        }

        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        worker.post {
            runCatching {
                val requestBuilder = Request.Builder()
                    .url(endpoint.trimEnd('/') + "/remote-control/result")
                    .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .header("X-Hirmand-Device-Id", deviceId)
                SignedRequest.addHeaders(requestBuilder, token, deviceId, bytes)
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        prefs.lastRemoteControlStatus =
                            if (success) "آخرین فرمان ریموت با موفقیت اجرا شد" else "فرمان ریموت ناموفق بود"
                    }
                }
            }
        }
    }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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

    private fun updateNotification(text: String, approval: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, approval))
    }

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

    private fun buildNotification(text: String, approval: Boolean = false): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("remote_data_approval", true)
        val pending = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Phone Bridge · ریموت کنترل")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(!approval)
            .setAutoCancel(false)
            .build()
    }
}    private fun prepareRestoreApproval(commandId: String, payload: JSONObject) {
        if (prefs.pendingRemoteDataCommandId.isNotBlank()) return
        val dataType = payload.optString("dataType").trim()
        val requestedCount = payload.optInt("requestedCount", 0)
        val allowedCounts = setOf(15, 30, 60, 100, 250, 500, 1000, 5000, 10000)
        if (dataType !in setOf("sms", "incoming_calls") || requestedCount !in allowedCounts) {
            postDataFailure(commandId, dataType, "درخواست بازگردانی روی گوشی معتبر نیست")
            return
        }
        val allowedByUser = when (dataType) {
            "sms" -> prefs.sms
            "incoming_calls" -> prefs.calls
            else -> false
        }
        if (!allowedByUser) {
            postDataFailure(commandId, dataType, "این نوع بازگردانی در خود گوشی فعال نشده است")
            return
        }

        prefs.pendingRemoteDataCommandId = commandId
        prefs.pendingRemoteDataType = dataType
        prefs.pendingRemoteDataCount = requestedCount
        prefs.promptedRemoteDataCommandId = ""
        prefs.lastRemoteControlStatus =
            "درخواست بازگردانی " + if (dataType == "sms") "پیامک‌های دریافتی" else "تماس‌های دریافتی" +
            " منتظر تأیید شماست"
        showApprovalNotification(dataType, requestedCount)
    }

    private fun approvePendingData() {
        val commandId = prefs.pendingRemoteDataCommandId
        val dataType = prefs.pendingRemoteDataType
        val count = prefs.pendingRemoteDataCount
        if (commandId.isBlank() || count <= 0) return
        if (!prefs.remoteControlEnabled) {
            if (commandId.isNotBlank()) {
                postDataFailure(commandId, prefs.pendingRemoteDataType, "ریموت کنترل در این گوشی خاموش شده است")
            }
            prefs.clearPendingRemoteData()
            return
        }
        if (dataType == "sms" && !has(Manifest.permission.READ_SMS)) {
            postDataFailure(commandId, dataType, "مجوز پیامک روی گوشی فعال نیست")
            prefs.clearPendingRemoteData()
            return
        }
        if (dataType == "incoming_calls" && !has(Manifest.permission.READ_CALL_LOG)) {
            postDataFailure(commandId, dataType, "مجوز تاریخچه تماس‌ها روی گوشی فعال نیست")
            prefs.clearPendingRemoteData()
            return
        }

        prefs.lastRemoteControlStatus =
            "تأیید شد · در حال جمع‌آوری " + count + " " + if (dataType == "sms") "پیامک آخر" else "تماس دریافتی آخر"

        val rows = when (dataType) {
            "sms" -> collectRemoteSms(count)
            "incoming_calls" -> collectRemoteIncomingCalls(count)
            else -> emptyList()
        }
        postDataChunks(commandId, dataType, rows)
    }

    private fun denyPendingData() {
        val commandId = prefs.pendingRemoteDataCommandId
        val dataType = prefs.pendingRemoteDataType
        if (commandId.isNotBlank()) {
            postDataFailure(commandId, dataType, "درخواست بازگردانی از داخل گوشی رد شد")
        }
        prefs.clearPendingRemoteData()
        prefs.lastRemoteControlStatus = "درخواست بازگردانی رد شد"
        updateNotification("ریموت کنترل فعال · منتظر فرمان")
    }

    @Suppress("MissingPermission")
    private fun collectRemoteSms(limit: Int): List<JSONObject> {
        val result = ArrayList<JSONObject>(limit)
        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.READ, Telephony.Sms.THREAD_ID),
            null, null, Telephony.Sms.DATE + " DESC",
        )?.use { c ->
            val address = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val date = c.getColumnIndex(Telephony.Sms.DATE)
            val body = c.getColumnIndex(Telephony.Sms.BODY)
            val read = c.getColumnIndex(Telephony.Sms.READ)
            val thread = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            while (c.moveToNext() && result.size < limit) {
                result.add(JSONObject()
                    .put("address", if (address >= 0) c.getString(address) ?: "" else "")
                    .put("date", if (date >= 0 && !c.isNull(date)) c.getLong(date) else 0L)
                    .put("body", if (body >= 0) c.getString(body) ?: "" else "")
                    .put("read", read >= 0 && !c.isNull(read) && c.getInt(read) != 0)
                    .put("threadId", if (thread >= 0 && !c.isNull(thread)) c.getLong(thread) else 0L))
            }
        }
        return result
    }

    @Suppress("MissingPermission")
    private fun collectRemoteIncomingCalls(limit: Int): List<JSONObject> {
        val result = ArrayList<JSONObject>(limit)
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.NEW, CallLog.Calls.PRESENTATION),
            CallLog.Calls.TYPE + "=?",
            arrayOf(CallLog.Calls.INCOMING_TYPE.toString()),
            CallLog.Calls.DATE + " DESC",
        )?.use { c ->
            val number = c.getColumnIndex(CallLog.Calls.NUMBER)
            val date = c.getColumnIndex(CallLog.Calls.DATE)
            val duration = c.getColumnIndex(CallLog.Calls.DURATION)
            val newFlag = c.getColumnIndex(CallLog.Calls.NEW)
            val presentation = c.getColumnIndex(CallLog.Calls.PRESENTATION)
            while (c.moveToNext() && result.size < limit) {
                result.add(JSONObject()
                    .put("number", if (number >= 0) c.getString(number) ?: "" else "")
                    .put("date", if (date >= 0 && !c.isNull(date)) c.getLong(date) else 0L)
                    .put("durationSeconds", if (duration >= 0 && !c.isNull(duration)) c.getLong(duration) else 0L)
                    .put("new", newFlag >= 0 && !c.isNull(newFlag) && c.getInt(newFlag) != 0)
                    .put("presentation", if (presentation >= 0 && !c.isNull(presentation)) c.getInt(presentation) else 0))
            }
        }
        return result
    }

    private fun postDataChunks(commandId: String, dataType: String, rows: List<JSONObject>) {
        val chunks = ArrayList<JSONArray>()
        var current = JSONArray()
        var bytes = 2
        rows.forEach { row ->
            val size = row.toString().toByteArray(Charsets.UTF_8).size
            if (current.length() > 0 && bytes + size + 1 > DATA_CHUNK_MAX_BYTES) {
                chunks.add(current)
                current = JSONArray()
                bytes = 2
            }
            current.put(row)
            bytes += size + 1
        }
        if (current.length() > 0 || rows.isEmpty()) chunks.add(current)

        chunks.forEachIndexed { index, chunk ->
            val ok = postJson(
                "/remote-control/data",
                JSONObject()
                    .put("deviceId", prefs.installId)
                    .put("commandId", commandId)
                    .put("action", "restore_data")
                    .put("dataType", dataType)
                    .put("chunkIndex", index)
                    .put("chunkCount", chunks.size)
                    .put("totalCount", rows.size)
                    .put("final", index == chunks.lastIndex)
                    .put("success", true)
                    .put("rows", chunk)
            )
            if (!ok) {
                prefs.lastRemoteControlStatus = "ارسال نتیجهٔ بازگردانی ناموفق بود"
                return
            }
        }

        prefs.lastRemoteControlStatus =
            "بازگردانی " + if (dataType == "sms") "پیامک" else "تماس‌های دریافتی" +
            " کامل شد · " + rows.size + " مورد"
        prefs.clearPendingRemoteData()
        updateNotification("ریموت کنترل فعال · منتظر فرمان")
    }

    private fun postDataFailure(commandId: String, dataType: String, error: String) {
        postJson(
            "/remote-control/data",
            JSONObject()
                .put("deviceId", prefs.installId)
                .put("commandId", commandId)
                .put("action", "restore_data")
                .put("dataType", dataType)
                .put("success", false)
                .put("error", error)
                .put("rows", JSONArray())
        )
        updateNotification("ریموت کنترل فعال · منتظر فرمان")
    }

    private fun showApprovalNotification(dataType: String, count: Int) {
        val label = if (dataType == "sms") "پیامک‌های دریافتی" else "تماس‌های دریافتی"
        updateNotification("درخواست $label · $count مورد · برای تأیید، اعلان را باز کن", true)
    }


