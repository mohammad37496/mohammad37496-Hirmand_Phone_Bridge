package ir.hirmand.phonebridge.location

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
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.sync.SignedRequest
import ir.hirmand.phonebridge.sync.SyncScheduler
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class LocationTrackingService : Service() {
    companion object {
        const val ACTION_START = "ir.hirmand.phonebridge.location.START"
        const val ACTION_STOP = "ir.hirmand.phonebridge.location.STOP"
        const val CHANNEL_ID = "location_tracking"
        const val NOTIFICATION_ID = 2408
    }

    private lateinit var prefs: AppPrefs
    private lateinit var locationManager: LocationManager
    private var listener: LocationListener? = null
    private var lastSentAt = 0L
    private var activeIntervalMs = 15 * 60_000L
    private val handlerThread = HandlerThread("hirmand-location").apply { start() }
    private val worker = Handler(handlerThread.looper)
    private var configRefreshTask: Runnable? = null

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
        scheduleConfigRefresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> worker.post {
                val enabled = refreshRemoteConfig()
                if (enabled != false) startTracking() else stopTracking(true)
            }
            ACTION_STOP -> stopTracking(true)
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (!prefs.locationTrackingEnabled) { stopSelf(); return }
        if (!hasLocationPermission()) {
            prefs.lastLocationStatus = "مجوز GPS برای ردیابی موقعیت صادر نشده است"
            stopSelf()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            prefs.lastLocationStatus = "GPS خاموش است؛ موقعیت‌یابی را در تنظیمات گوشی روشن کن"
            return
        }

        activeIntervalMs = prefs.locationIntervalMinutes * 60_000L
        val notification = buildNotification("ردیابی موقعیت فعال · هر " + prefs.locationIntervalMinutes + " دقیقه")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        stopLocationUpdates()
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val now = System.currentTimeMillis()
                if (lastSentAt != 0L && now - lastSentAt < activeIntervalMs) return
                lastSentAt = now
                worker.post { uploadOrQueue(location) }
            }
        }
        listener = locationListener

        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            prefs.lastLocationStatus = "مجوز GPS برای ردیابی موقعیت صادر نشده است"
            stopTracking(true)
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                activeIntervalMs,
                0f,
                locationListener,
                Looper.getMainLooper(),
            )
            if (fineGranted) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { location ->
                    worker.post { uploadOrQueue(location) }
                }
            }
            prefs.lastLocationStatus = "ردیابی موقعیت فعال · هر " + prefs.locationIntervalMinutes + " دقیقه"
        } catch (_: SecurityException) {
            prefs.lastLocationStatus = "مجوز GPS در لحظهٔ دریافت موقعیت در دسترس نبود"
            stopTracking(false)
        }
            prefs.lastLocationStatus = "دریافت موقعیت GPS از این دستگاه ممکن نشد"
            stopTracking(false)
        }
    }

    private fun uploadOrQueue(location: Location) {
        val point = JSONObject()
            .put("clientPointId", UUID.randomUUID().toString())
            .put("deviceId", prefs.installId)
            .put("recordedAt", location.time.coerceAtLeast(System.currentTimeMillis()))
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracyMeters", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
            .put("altitudeMeters", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
            .put("speedMps", if (location.hasSpeed()) location.speed else JSONObject.NULL)
            .put("bearingDegrees", if (location.hasBearing()) location.bearing else JSONObject.NULL)
            .put("provider", location.provider ?: "gps")

        prefs.addPendingLocation(point)
        val result = runCatching {
            val bytes = point.toString().toByteArray(Charsets.UTF_8)
            val endpoint = prefs.endpoint.trimEnd('/') + "/location"
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Authorization", "Bearer " + prefs.token)
                .header("X-Hirmand-Device-Id", prefs.installId)
            SignedRequest.addHeaders(requestBuilder, prefs.token, prefs.installId, bytes)
            client.newCall(requestBuilder.build()).execute().use { it.code }
        }.getOrElse { 0 }

        if (result in 200..299) {
            prefs.removePendingLocation(point.optString("clientPointId"))
            prefs.lastLocationStatus = "آخرین موقعیت با موفقیت ارسال شد"
        } else {
            prefs.lastLocationStatus = "موقعیت دریافت شد و در صف ارسال قرار گرفت"
            SyncScheduler.enqueue(this)
        }
    }

    private fun scheduleConfigRefresh() {
        val task = object : Runnable {
            override fun run() {
                val enabled = refreshRemoteConfig()
                if (enabled == false) stopTracking(true)
                worker.postDelayed(this, 5 * 60_000L)
            }
        }
        configRefreshTask = task
        worker.post(task)
    }

    private fun refreshRemoteConfig(): Boolean? {
        if (!prefs.locationTrackingEnabled || prefs.endpoint.isBlank() || prefs.token.isBlank()) return false
        return runCatching {
            val emptyBody = ByteArray(0)
            val url = prefs.endpoint.trimEnd('/') + "/location-config?deviceId=" +
                java.net.URLEncoder.encode(prefs.installId, "UTF-8")
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer " + prefs.token)
                .header("X-Hirmand-Device-Id", prefs.installId)
            SignedRequest.addHeaders(requestBuilder, prefs.token, prefs.installId, emptyBody)
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val enabled = json.optBoolean("enabled", false)
                if (!enabled) return@use false

                val remoteInterval = json.optInt("intervalMinutes", prefs.locationIntervalMinutes)
                if (remoteInterval in listOf(5, 15, 30, 60) && remoteInterval != prefs.locationIntervalMinutes) {
                    prefs.locationIntervalMinutes = remoteInterval
                    stopLocationUpdates()
                    startTracking()
                }
                true
            }
        }.getOrElse {
            prefs.lastLocationStatus = "بررسی تنظیمات ردیابی موقعیت ناموفق بود"
            null
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun stopLocationUpdates() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
    }

    private fun stopTracking(stopService: Boolean) {
        stopLocationUpdates()
        if (stopService) {
            prefs.lastLocationStatus = "ردیابی موقعیت خاموش است"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        configRefreshTask?.let(worker::removeCallbacks)
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "ردیابی موقعیت Phone Bridge", NotificationManager.IMPORTANCE_LOW)
        channel.description = "اعلان قابل مشاهده هنگام ردیابی موقعیت"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Phone Bridge · موقعیت")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
