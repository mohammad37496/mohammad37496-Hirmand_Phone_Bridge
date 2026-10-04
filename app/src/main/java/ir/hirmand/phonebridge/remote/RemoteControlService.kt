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
import androidx.core.content.ContextCompat
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.sync.SignedRequest
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
