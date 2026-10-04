package ir.hirmand.phonebridge.blocking

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.sync.SignedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppBlockAccessibilityService : AccessibilityService() {
    private lateinit var prefs: AppPrefs
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private var rules: List<AppBlockRule> = emptyList()
    private var foregroundPackage = ""
    private var overlay: View? = null
    private var overlayPackage = ""
    private var overlayRuleId = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (foregroundPackage.isNotBlank()) evaluatePackage(foregroundPackage)
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    private val policyRefresh = object : Runnable {
        override fun run() {
            refreshPolicy()
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = AppPrefs(applicationContext)
        mainHandler.post(ticker)
        mainHandler.post { refreshPolicy() }
        mainHandler.postDelayed(policyRefresh, 60_000L)
        prefs.lastAppBlockingStatus = "سرویس بلاک برنامه‌ها فعال است"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isBlank()) return
        foregroundPackage = pkg
        evaluatePackage(pkg)
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onDestroy() {
        removeOverlay()
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(policyRefresh)
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun evaluatePackage(packageName: String) {
        if (!::prefs.isInitialized || !prefs.appBlockingEnabled || packageName == applicationContext.packageName) {
            removeOverlay()
            return
        }

        val rule = rules.firstOrNull {
            it.packageName == packageName && it.isActive(ZonedDateTime.now())
        }

        if (rule == null) {
            removeOverlay()
            return
        }

        showOverlay(packageName, rule)
    }

    private fun refreshPolicy() {
        if (!::prefs.isInitialized || !prefs.appBlockingEnabled) {
            rules = emptyList()
            removeOverlay()
            return
        }

        val endpoint = prefs.endpoint.trim()
        val token = prefs.token.trim()
        val deviceId = prefs.installId.trim()
        if (!EndpointPolicy.isAllowed(endpoint) || token.isBlank() || deviceId.isBlank()) {
            prefs.lastAppBlockingStatus = "برای دریافت قوانین بلاک، Endpoint و توکن دستگاه باید تنظیم باشند"
            return
        }

        val url = endpoint.trimEnd('/') + "/app-block-config?deviceId=" +
            java.net.URLEncoder.encode(deviceId, "UTF-8")
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("X-Hirmand-Device-Id", deviceId)
        SignedRequest.addHeaders(requestBuilder, token, deviceId, ByteArray(0))

        networkExecutor.execute {
            runCatching {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    body
                }
            }.onSuccess { body ->
                val json = org.json.JSONObject(body)
                val enabled = json.optBoolean("enabled", false)
                val array = json.optJSONArray("rules") ?: JSONArray()
                val parsed = if (enabled) {
                    (0 until array.length()).mapNotNull { index ->
                        AppBlockRule.fromJson(array.optJSONObject(index) ?: org.json.JSONObject())
                    }
                } else {
                    emptyList()
                }
                mainHandler.post {
                    rules = parsed
                    prefs.lastAppBlockingStatus = "قوانین بلاک بروزرسانی شد · ${parsed.size} قانون"
                    if (foregroundPackage.isNotBlank()) evaluatePackage(foregroundPackage)
                }
            }.onFailure {
                mainHandler.post {
                    prefs.lastAppBlockingStatus = "اتصال به قوانین بلاک ناموفق بود؛ آخرین قوانین ذخیره‌شده حفظ شدند"
                    if (foregroundPackage.isNotBlank()) evaluatePackage(foregroundPackage)
                }
            }
        }
    }

    private fun showOverlay(packageName: String, rule: AppBlockRule) {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        if (overlay != null && overlayPackage == packageName && overlayRuleId == rule.id) return

        removeOverlay()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(250, 10, 14, 22))
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 56, 48, 56)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(28, 34, 46))
                cornerRadius = 34f
                setStroke(2, Color.rgb(70, 80, 100))
            }
        }

        val title = TextView(this).apply {
            text = "این برنامه فعلاً مسدود است"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val app = TextView(this).apply {
            text = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        val message = TextView(this).apply {
            text = rule.message.ifBlank { "در این بازهٔ زمانی امکان استفاده از این برنامه وجود ندارد." }
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val schedule = TextView(this).apply {
            text = "${rule.startTime} تا ${rule.endTime}"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            gravity = Gravity.CENTER
        }

        val home = Button(this).apply {
            text = "بازگشت به صفحه اصلی"
            setOnClickListener {
                removeOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }

        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        card.addView(app, LinearLayout.LayoutParams(-1, -2))
        card.addView(message, LinearLayout.LayoutParams(-1, -2))
        card.addView(schedule, LinearLayout.LayoutParams(-1, -2))
        card.addView(home, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
        root.addView(card, FrameLayout.LayoutParams(
            -1,
            -2,
            Gravity.CENTER,
        ).apply {
            leftMargin = 30
            rightMargin = 30
        })

        val params = WindowManager.LayoutParams(
            -1,
            -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching {
            wm.addView(root, params)
            overlay = root
            overlayPackage = packageName
            overlayRuleId = rule.id
        }.onFailure {
            if (::prefs.isInitialized) prefs.lastAppBlockingStatus = "نمایش صفحهٔ بلاک روی گوشی ممکن نشد"
        }
    }

    private fun removeOverlay() {
        val current = overlay ?: return
        overlay = null
        overlayPackage = ""
        overlayRuleId = ""
        runCatching {
            (getSystemService(WINDOW_SERVICE) as? WindowManager)?.removeView(current)
        }
    }
}
