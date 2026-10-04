package ir.hirmand.phonebridge.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.ComponentName
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ir.hirmand.phonebridge.BuildConfig
import ir.hirmand.phonebridge.calls.CallRecordingService
import ir.hirmand.phonebridge.location.LocationTrackingService
import ir.hirmand.phonebridge.blocking.AppBlockAccessibilityService
import ir.hirmand.phonebridge.remote.RemoteControlService
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.data.LocalQueueDb
import ir.hirmand.phonebridge.databinding.ActivityMainBinding
import ir.hirmand.phonebridge.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var db: LocalQueueDb
    private var latestUpdateUrl: String? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshUi()
        val missing = selectedPermissions().count {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        binding.statusText.text =
            if (missing == 0) "همهٔ مجوزهای انتخاب‌شده آماده‌اند"
            else "برخی مجوزها هنوز تأیید نشده‌اند"
        if (prefs.callRecordingEnabled && missing == 0) {
            startCallRecordingMonitor()
        }
    }

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            binding.statusText.text = "فایلی انتخاب نشد"
            return@registerForActivityResult
        }
        uris.take(20).forEach { uri -> saveSelectedFile(uri) }
        renderSelectedFiles()
        binding.statusText.text = "${uris.take(20).size} مورد برای همگام‌سازی دستی انتخاب شد"
    }

    private fun saveSelectedFile(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )

        var name = uri.lastPathSegment ?: "فایل"
        var size = 0L
        cursor?.use {
            if (it.moveToFirst()) {
                name = it.getString(0) ?: name
                size = if (!it.isNull(1)) it.getLong(1) else 0L
            }
        }

        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        prefs.addSelectedFile(org.json.JSONObject().apply {
            put("uri", uri.toString())
            put("name", name)
            put("sizeBytes", size)
            put("mimeType", mime)
            put("selectedAt", System.currentTimeMillis())
        })
    }

    private fun renderSelectedFiles() {
        binding.selectedFilesText.text =
            prefs.selectedFiles().takeLast(8).reversed().joinToString("\n") {
                val size = it.optLong("sizeBytes", 0L)
                val status =
                    if (it.optString("lastUploadedHash").isNotBlank()) "ارسال‌شده"
                    else "در انتظار ارسال"
                "• ${it.optString("name", "فایل")} · ${if (size > 0) formatSize(size) else "اندازه نامشخص"} · $status"
            }.ifBlank { "هنوز فایلی انتخاب نشده است" }

        binding.selectedFilesCountText.text =
            "${prefs.selectedFiles().size} مورد انتخاب‌شده"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L ->
            String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L ->
            String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)
        db = LocalQueueDb(this)
        loadState()
        wireUi()
        observeWorkState()
        if (prefs.autoSync) {
            SyncScheduler.schedulePeriodic(this)
        } else {
            SyncScheduler.cancelPeriodic(this)
        }
        refreshUi()
        val recordingPermissionsReady = selectedPermissions()
            .filter { it == Manifest.permission.READ_PHONE_STATE || it == Manifest.permission.RECORD_AUDIO || it == Manifest.permission.POST_NOTIFICATIONS }
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (prefs.callRecordingEnabled && recordingPermissionsReady) {
            startCallRecordingMonitor()
        }
        if (prefs.remoteControlEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startRemoteControlMonitor()
        }
        checkForUpdate(showNoUpdate = false)
    }

    private fun observeWorkState() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("manual-sync")
            .observe(this) { infos ->
                when (infos.firstOrNull()?.state) {
                    WorkInfo.State.RUNNING ->
                        binding.statusText.text = "در حال جمع‌آوری و ارسال داده…"
                    WorkInfo.State.SUCCEEDED -> {
                        binding.statusText.text = "همگام‌سازی با موفقیت انجام شد"
                        refreshUi()
                    }
                    WorkInfo.State.ENQUEUED ->
                        binding.statusText.text = "همگام‌سازی در صف اجراست"
                    WorkInfo.State.FAILED ->
                        binding.statusText.text = "ارسال انجام نشد؛ تنظیمات یا اتصال را بررسی کن"
                    else -> Unit
                }
            }
    }

    private fun wireUi() {
        binding.tabGroup.check(binding.tabDashboard.id)
        binding.tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                binding.tabDashboard.id -> showSection(0)
                binding.tabModules.id -> showSection(1)
                binding.tabSettings.id -> showSection(2)
            }
        }

        val switches = listOf(
            binding.locationSwitch,
            binding.wifiSwitch,
            binding.contactsSwitch,
            binding.callsSwitch,
            binding.smsSwitch,
            binding.calendarSwitch,
            binding.appsSwitch,
        )
        switches.forEach { it.setOnCheckedChangeListener { _, _ -> refreshUi() } }

        binding.locationTrackingSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.locationTrackingEnabled = checked
            if (checked) {
                val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) startLocationTrackingMonitor() else requestSelectedPermissions()
            } else {
                runCatching { startService(Intent(this, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP)) }
                prefs.lastLocationStatus = "ردیابی موقعیت خاموش است"
                binding.locationTrackingStatusText.text = prefs.lastLocationStatus
            }
            refreshUi()
        }

        binding.appBlockingSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.appBlockingEnabled = checked
            if (checked) {
                refreshAppBlockingStatus()
                if (!isAppBlockingAccessibilityEnabled()) {
                    openAppBlockingSettings()
                    binding.statusText.text = "برای اعمال بلاک، دسترسی سرویس برنامه‌ها را در تنظیمات فعال کن"
                }
            } else {
                prefs.lastAppBlockingStatus = "مدیریت بلاک برنامه‌ها خاموش است"
                refreshAppBlockingStatus()
            }
            refreshUi()
        }

        binding.appBlockingSettingsButton.setOnClickListener {
            openAppBlockingSettings()
        }

        binding.remoteControlSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.remoteControlEnabled = checked
            if (checked) {
                val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine) startRemoteControlMonitor() else {
                    binding.statusText.text = "برای ریموت کنترل، مجوز دقیق GPS لازم است"
                    requestSelectedPermissions()
                }
            } else {
                runCatching {
                    startService(
                        Intent(this, RemoteControlService::class.java)
                            .setAction(RemoteControlService.ACTION_STOP)
                    )
                }
                prefs.lastRemoteControlStatus = "ریموت کنترل خاموش است"
                binding.remoteControlStatusText.text = prefs.lastRemoteControlStatus
            }
            refreshUi()
        }

        binding.callRecordingSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.callRecordingEnabled = checked
            if (checked) {
                binding.statusText.text = "ضبط تماس فعال شد؛ مجوزها و سرویس آماده‌باش بررسی می‌شوند"
                requestSelectedPermissions()
            } else {
                runCatching {
                    startService(Intent(this, CallRecordingService::class.java).setAction(CallRecordingService.ACTION_DISABLE))
                }
                prefs.lastCallRecordingStatus = "ضبط تماس خاموش است"
                binding.statusText.text = "ضبط تماس خاموش شد"
            }
            refreshUi()
        }

        binding.autoSyncSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoSync = checked
            if (checked) {
                SyncScheduler.schedulePeriodic(this)
                binding.statusText.text = "همگام‌سازی خودکار فعال شد"
            } else {
                SyncScheduler.cancelPeriodic(this)
                binding.statusText.text = "همگام‌سازی خودکار خاموش شد"
            }
        }

        binding.saveButton.setOnClickListener {
            saveState()
            binding.statusText.text = "تنظیمات ذخیره شد"
            refreshUi()
        }

        binding.requestPermissionsButton.setOnClickListener {
            saveState()
            requestSelectedPermissions()
        }

        binding.syncButton.setOnClickListener {
            saveState()
            startManualSync()
        }

        binding.pickFilesButton.setOnClickListener {
            pickFilesLauncher.launch(
                arrayOf("image/*", "video/*", "audio/*", "application/pdf", "text/*")
            )
        }

        binding.testConnectionButton.setOnClickListener {
            saveState()
            testConnection()
        }

        binding.checkUpdateButton.setOnClickListener {
            checkForUpdate(showNoUpdate = true)
        }

        binding.downloadUpdateButton.setOnClickListener {
            latestUpdateUrl?.let { url ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure {
                    binding.updateStatusText.text = "باز کردن لینک بروزرسانی ممکن نیست"
                }
            }
        }

        binding.registerDeviceButton.setOnClickListener {
            saveState()
            registerDevice()
        }

        binding.retryDeadLettersButton.setOnClickListener {
            val dead = db.countDeadLetters()
            if (dead == 0) {
                binding.statusText.text = "مورد متوقف‌شده‌ای برای تلاش مجدد وجود ندارد"
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("تلاش مجدد بسته‌های متوقف‌شده")
                .setMessage("" + dead + " بستهٔ ناموفق دوباره وارد صف ارسال می‌شوند.")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("تلاش مجدد") { _, _ ->
                    val restored = db.retryDeadLetters()
                    if (restored > 0) SyncScheduler.enqueue(this)
                    binding.statusText.text = "" + restored + " بسته دوباره در صف قرار گرفت"
                    refreshUi()
                }
                .show()
        }

        binding.clearQueueButton.setOnClickListener {
            val queued = db.count()
            if (queued == 0) {
                binding.statusText.text = "صف محلی خالی است"
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("پاک‌کردن صف محلی")
                .setMessage("" + queued + " بسته‌ای که هنوز به سرور ارسال نشده‌اند حذف می‌شوند. ادامه می‌دهی؟")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("پاک‌کردن") { _, _ ->
                    db.clear()
                    binding.statusText.text = "صف محلی پاک شد"
                    refreshUi()
                }
                .show()
        }

        binding.clearDeadLettersButton.setOnClickListener {
            val dead = db.countDeadLetters()
            if (dead == 0) {
                binding.statusText.text = "مورد متوقف‌شده‌ای وجود ندارد"
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("حذف خطاهای متوقف‌شده")
                .setMessage("" + dead + " بستهٔ ناموفق برای همیشه از گوشی حذف می‌شوند.")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("حذف") { _, _ ->
                    db.clearDeadLetters()
                    binding.statusText.text = "خطاهای متوقف‌شده حذف شدند"
                    refreshUi()
                }
                .show()
        }
    }

    private fun showSection(index: Int) {
        binding.dashboardSection.visibility =
            if (index == 0) android.view.View.VISIBLE else android.view.View.GONE
        binding.modulesSection.visibility =
            if (index == 1) android.view.View.VISIBLE else android.view.View.GONE
        binding.settingsSection.visibility =
            if (index == 2) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun loadState() {
        binding.endpointInput.setText(prefs.endpoint)
        binding.tokenInput.setText(prefs.token)
        binding.deviceNameInput.setText(prefs.deviceName)
        binding.locationSwitch.isChecked = prefs.location
        binding.locationTrackingSwitch.isChecked = prefs.locationTrackingEnabled
        binding.locationTrackingStatusText.text = prefs.lastLocationStatus.ifBlank { "ردیابی موقعیت خاموش است" }
        binding.appBlockingSwitch.isChecked = prefs.appBlockingEnabled
        binding.appBlockingStatusText.text = prefs.lastAppBlockingStatus.ifBlank { "مدیریت بلاک برنامه‌ها خاموش است" }
        binding.remoteControlSwitch.isChecked = prefs.remoteControlEnabled
        binding.remoteControlStatusText.text = prefs.lastRemoteControlStatus.ifBlank { "ریموت کنترل خاموش است" }
        binding.wifiSwitch.isChecked = prefs.wifi
        binding.contactsSwitch.isChecked = prefs.contacts
        binding.callsSwitch.isChecked = prefs.calls
        binding.smsSwitch.isChecked = prefs.sms
        binding.calendarSwitch.isChecked = prefs.calendar
        binding.appsSwitch.isChecked = prefs.apps
        binding.callRecordingSwitch.isChecked = prefs.callRecordingEnabled
        binding.autoSyncSwitch.isChecked = prefs.autoSync
        binding.callRecordingStatusText.text = prefs.lastCallRecordingStatus.ifBlank { "ضبط خاموش است" }
        renderSelectedFiles()
        binding.deviceSummaryText.text = prefs.deviceName
        binding.deviceDetailsText.text =
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE ?: "نامشخص"} · SDK ${Build.VERSION.SDK_INT} · Phone Bridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        refreshDeviceStats()
    }

    private fun refreshDeviceStats() {
        val batteryIntent =
            registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val batteryLevel = batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100 / scale) else null
        }

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
        val activityManager = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        val ramGb = info.totalMem / 1024.0 / 1024.0 / 1024.0

        binding.batteryText.text = batteryLevel?.let { "$it٪" } ?: "—"
        binding.ramText.text = String.format(java.util.Locale.US, "%.1f GB", ramGb)
        binding.storageText.text = String.format(java.util.Locale.US, "%.1f GB", freeGb)
    }

    private fun saveState() {
        prefs.endpoint = binding.endpointInput.text?.toString().orEmpty()
        prefs.token = binding.tokenInput.text?.toString().orEmpty()
        prefs.deviceName =
            binding.deviceNameInput.text?.toString().orEmpty().ifBlank { "گوشی من" }
        prefs.location = binding.locationSwitch.isChecked
        prefs.locationTrackingEnabled = binding.locationTrackingSwitch.isChecked
        prefs.appBlockingEnabled = binding.appBlockingSwitch.isChecked
        prefs.remoteControlEnabled = binding.remoteControlSwitch.isChecked
        prefs.wifi = binding.wifiSwitch.isChecked
        prefs.contacts = binding.contactsSwitch.isChecked
        prefs.calls = binding.callsSwitch.isChecked
        prefs.sms = binding.smsSwitch.isChecked
        prefs.calendar = binding.calendarSwitch.isChecked
        prefs.apps = binding.appsSwitch.isChecked
        prefs.callRecordingEnabled = binding.callRecordingSwitch.isChecked
        prefs.autoSync = binding.autoSyncSwitch.isChecked
    }

    private fun selectedPermissions(): List<String> = buildList {
        if (prefs.location || prefs.locationTrackingEnabled || prefs.remoteControlEnabled) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (prefs.contacts) add(Manifest.permission.READ_CONTACTS)
        if (prefs.calls) {
            add(Manifest.permission.READ_CALL_LOG)
        }
        if (prefs.callRecordingEnabled) {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (prefs.remoteControlEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (prefs.sms) add(Manifest.permission.READ_SMS)
        if (prefs.calendar) add(Manifest.permission.READ_CALENDAR)
    }

    private fun startRemoteControlMonitor() {
        if (!prefs.remoteControlEnabled) return
        runCatching {
            startForegroundService(
                Intent(this, RemoteControlService::class.java)
                    .setAction(RemoteControlService.ACTION_START)
            )
            prefs.lastRemoteControlStatus = "ریموت کنترل فعال است · منتظر فرمان پنل"
        }.onFailure {
            prefs.lastRemoteControlStatus = "شروع سرویس ریموت کنترل ممکن نشد"
        }
        binding.remoteControlStatusText.text = prefs.lastRemoteControlStatus
    }

    private fun startLocationTrackingMonitor() {
        if (!prefs.locationTrackingEnabled) return
        runCatching {
            startForegroundService(
                Intent(this, LocationTrackingService::class.java)
                    .setAction(LocationTrackingService.ACTION_START)
            )
            prefs.lastLocationStatus = "ردیابی موقعیت فعال است"
        }.onFailure {
            prefs.lastLocationStatus = "شروع سرویس ردیابی موقعیت ممکن نشد"
        }
        binding.locationTrackingStatusText.text = prefs.lastLocationStatus
    }

    private fun startCallRecordingMonitor() {
        if (!prefs.callRecordingEnabled) return
        runCatching {
            startForegroundService(
                Intent(this, CallRecordingService::class.java)
                    .setAction(CallRecordingService.ACTION_ENABLE_MONITOR)
            )
            prefs.lastCallRecordingStatus = "ضبط تماس آماده است؛ اعلان آماده‌باش فعال شد"
        }.onFailure {
            prefs.lastCallRecordingStatus = "فعال‌سازی سرویس آماده‌باش ضبط تماس ممکن نشد"
        }
    }

    private fun requestSelectedPermissions() {
        val permissions = selectedPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissions.isEmpty()) {
            binding.statusText.text = "مجوزهای انتخاب‌شده از قبل آماده‌اند"
            return
        }

        val labels = buildList {
            if (prefs.location || prefs.locationTrackingEnabled || prefs.remoteControlEnabled) add("موقعیت GPS / ریموت کنترل")
            if (prefs.contacts) add("مخاطبین")
            if (prefs.calls) add("تاریخچه تماس‌ها")
            if (prefs.callRecordingEnabled) add("ضبط تماس، وضعیت تلفن و اعلان ضبط")
            if (prefs.remoteControlEnabled) add("موقعیت GPS و اعلان ریموت کنترل")
            if (prefs.sms) add("پیامک‌ها")
            if (prefs.calendar) add("تقویم")
        }

        AlertDialog.Builder(this)
            .setTitle("تأیید دسترسی‌ها")
            .setMessage(
                "اپ فقط برای این موارد مجوز می‌خواهد:\n\n" +
                    "${labels.joinToString("، ")}\n\n" +
                    "این دسترسی‌ها فقط برای ماژول‌هایی استفاده می‌شوند که خودت روشن کرده‌ای.\n\n" +
                    "ضبط تماس فقط وقتی کلید ضبط روشن باشد فعال می‌شود و هنگام ضبط اعلان قابل‌مشاهده نمایش داده می‌شود."
            )
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ادامه") { _, _ ->
                permissionLauncher.launch(permissions.toTypedArray())
            }
            .show()
    }

    private fun checkForUpdate(showNoUpdate: Boolean = false) {
        val endpoint = prefs.endpoint.trim()
        if (!EndpointPolicy.isAllowed(endpoint)) {
            if (showNoUpdate) binding.updateStatusText.text = "Endpoint برای بررسی بروزرسانی مجاز نیست"
            return
        }

        binding.checkUpdateButton.isEnabled = false
        if (showNoUpdate) binding.updateStatusText.text = "در حال بررسی نسخهٔ جدید…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = Uri.parse(endpoint.trimEnd('/') + "/update")
                        .buildUpon()
                        .appendQueryParameter("versionCode", BuildConfig.VERSION_CODE.toString())
                        .build()
                        .toString()
                    val request = Request.Builder().url(url).get().build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
                        JSONObject(body)
                    }
                }
            }

            binding.checkUpdateButton.isEnabled = true
            result.onSuccess { json ->
                val available = json.optBoolean("updateAvailable", false)
                val latest = json.optJSONObject("latest")
                val latestName = latest?.optString("versionName").orEmpty().ifBlank { "نسخهٔ جدید" }
                val latestCode = latest?.optInt("versionCode", 0) ?: 0
                latestUpdateUrl = latest?.optString("downloadUrl").orEmpty().ifBlank { null }
                val notes = latest?.optString("releaseNotes").orEmpty()
                val force = latest?.optBoolean("forceUpdate", false) == true && latestUpdateUrl != null

                if (!available || latestCode <= BuildConfig.VERSION_CODE) {
                    latestUpdateUrl = null
                    binding.downloadUpdateButton.visibility = android.view.View.GONE
                    binding.updateStatusText.text =
                        "نسخهٔ ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) به‌روز است"
                    return@onSuccess
                }

                binding.updateStatusText.text =
                    "نسخهٔ جدید موجود است: $latestName ($latestCode)" +
                        if (notes.isNotBlank()) " · $notes" else ""
                binding.downloadUpdateButton.visibility =
                    if (latestUpdateUrl != null) android.view.View.VISIBLE else android.view.View.GONE

                if (force) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("بروزرسانی ضروری است")
                        .setMessage("برای ادامهٔ استفاده باید Phone Bridge را به نسخهٔ $latestName ارتقا بدهی.")
                        .setPositiveButton("دریافت بروزرسانی") { _, _ ->
                            latestUpdateUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                        }
                        .setCancelable(false)
                        .show()
                } else if (showNoUpdate) {
                    android.widget.Toast.makeText(this@MainActivity, "نسخهٔ جدید آماده است.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                if (showNoUpdate) {
                    binding.updateStatusText.text = "بررسی بروزرسانی انجام نشد؛ اتصال را بررسی کن"
                }
            }
        }
    }

    private fun startManualSync() {
        if (!EndpointPolicy.isAllowed(prefs.endpoint)) {
            showEndpointHelp()
            return
        }
        if (prefs.token.isBlank()) {
            binding.statusText.text = "توکن سرور را در تنظیمات وارد کن"
            binding.tabGroup.check(binding.tabSettings.id)
            return
        }

        val missing = selectedPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            binding.tabGroup.check(binding.tabModules.id)
            binding.statusText.text = "ابتدا مجوزهای انتخاب‌شده را تأیید کن"
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        SyncScheduler.enqueue(this)
        binding.statusText.text = "درخواست همگام‌سازی ثبت شد؛ در صورت نبود شبکه در صف می‌ماند"
    }

    private fun registerDevice() {
        val endpoint = prefs.endpoint.trim()
        val pairingToken = binding.pairingTokenInput.text?.toString()?.trim().orEmpty()

        if (!EndpointPolicy.isAllowed(endpoint)) {
            showEndpointHelp()
            return
        }
        if (pairingToken.isBlank()) {
            binding.statusText.text = "کلید ثبت دستگاه را وارد کن"
            return
        }

        binding.registerDeviceButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = JSONObject()
                        .put("device", JSONObject()
                            .put("id", prefs.installId)
                            .put("name", prefs.deviceName)
                            .put("manufacturer", Build.MANUFACTURER)
                            .put("model", Build.MODEL)
                            .put("androidVersion", Build.VERSION.RELEASE ?: "unknown")
                            .put("sdkInt", Build.VERSION.SDK_INT)
                            .put("appVersionName", BuildConfig.VERSION_NAME)
                            .put("appVersionCode", BuildConfig.VERSION_CODE))

                    val request = Request.Builder()
                        .url(endpoint.trimEnd('/') + "/register")
                        .header("Authorization", "Bearer " + pairingToken)
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
                        val json = JSONObject(body)
                        val deviceToken = json.optString("deviceToken").trim()
                        if (deviceToken.isBlank()) throw IllegalStateException("توکن دستگاه از سرور دریافت نشد")
                        deviceToken
                    }
                }
            }

            binding.registerDeviceButton.isEnabled = true
            result.fold(
                onSuccess = { deviceToken ->
                    prefs.token = deviceToken
                    binding.tokenInput.setText(deviceToken)
                    binding.pairingTokenInput.text?.clear()
                    binding.statusText.text = "دستگاه ثبت شد؛ توکن اختصاصی ساخته شد"
                    refreshUi()
                    testConnection()
                },
                onFailure = { error ->
                    binding.statusText.text = error.message?.take(120) ?: "ثبت دستگاه انجام نشد"
                },
            )
        }
    }
    private fun testConnection() {
        val endpoint = prefs.endpoint.trim()
        if (!EndpointPolicy.isAllowed(endpoint)) {
            showEndpointHelp()
            return
        }
        if (prefs.token.isBlank()) {
            binding.statusText.text = "برای آزمون اتصال، توکن را وارد کن"
            binding.tabGroup.check(binding.tabSettings.id)
            return
        }

        binding.testConnectionButton.isEnabled = false
        binding.connectionBadge.text = "در حال بررسی"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val healthUrl = endpoint.trimEnd('/')
                    val request = Request.Builder()
                        .url(healthUrl)
                        .header("Authorization", "Bearer " + prefs.token)
                        .header("X-Hirmand-Device-Id", prefs.installId)
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        response.isSuccessful to response.code
                    }
                }.getOrElse { false to 0 }
            }

            binding.testConnectionButton.isEnabled = true

            if (result.first) {
                binding.connectionBadge.text = "متصل"
                binding.statusText.text =
                    "اتصال به سرور برقرار است · HTTP ${result.second}"
            } else {
                binding.connectionBadge.text = "بدون اتصال"
                binding.statusText.text =
                    "سرور در دسترس نیست یا احراز هویت ناموفق است"
            }
        }
    }

    private fun showEndpointHelp() {
        AlertDialog.Builder(this)
            .setTitle("آدرس سرور را بررسی کن")
            .setMessage(
                "HTTPS برای سرور عمومی مجاز است. برای HTTP فقط از localhost یا یک آدرس خصوصی LAN مثل 192.168.x.x استفاده کن."
            )
            .setPositiveButton("متوجه شدم", null)
            .show()
    }

    private fun isAppBlockingAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, AppBlockAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAppBlockingSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            binding.statusText.text = "باز کردن تنظیمات دسترسی‌پذیری ممکن نشد"
        }
    }

    private fun refreshAppBlockingStatus() {
        val enabled = isAppBlockingAccessibilityEnabled()
        val local = prefs.appBlockingEnabled
        binding.appBlockingStatusText.text = when {
            !local -> "مدیریت بلاک برنامه‌ها خاموش است"
            enabled -> "فعال است و سرویس بلاک مجوز لازم را دارد"
            else -> "روشن است؛ دسترسی سرویس را در تنظیمات دسترسی‌پذیری فعال کن"
        }
    }

    private fun refreshUi() {
        val enabled = listOf(
            prefs.location,
            prefs.wifi,
            prefs.contacts,
            prefs.calls,
            prefs.sms,
            prefs.calendar,
            prefs.apps,
            prefs.appBlockingEnabled,
            prefs.callRecordingEnabled,
        ).count { it }

        val permissions = selectedPermissions().count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        val queued = db.count()
        val deadLetters = db.countDeadLetters()
        binding.activeModulesText.text = enabled.toString()
        binding.permissionsReadyText.text = "$permissions / ${selectedPermissions().size}"
        binding.queueMetricText.text = queued.toString()
        binding.queueCountBadge.text = "$queued در صف"
        binding.deadLetterCountBadge.text = "$deadLetters مورد متوقف‌شده"
        binding.deviceSummaryText.text = prefs.deviceName
        binding.deviceDetailsText.text =
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE ?: "نامشخص"} · SDK ${Build.VERSION.SDK_INT}"

        val ready = EndpointPolicy.isAllowed(prefs.endpoint) && prefs.token.isNotBlank()
        binding.connectionBadge.text = if (ready) "آماده" else "تنظیم نشده"

        binding.callRecordingStatusText.text =
            prefs.lastCallRecordingStatus.ifBlank {
                if (prefs.callRecordingEnabled) "ضبط تماس آمادهٔ استفاده است" else "ضبط خاموش است"
            }

        binding.locationTrackingStatusText.text = prefs.lastLocationStatus.ifBlank { if (prefs.locationTrackingEnabled) "ردیابی موقعیت آماده است" else "ردیابی موقعیت خاموش است" }
        binding.remoteControlStatusText.text = prefs.lastRemoteControlStatus.ifBlank { if (prefs.remoteControlEnabled) "ریموت کنترل آماده است" else "ریموت کنترل خاموش است" }
        refreshAppBlockingStatus()

        binding.lastSyncText.text = when {
            queued > 0 && deadLetters > 0 ->
                "${queued} بسته در صف هستند · ${deadLetters} مورد متوقف شده"
            queued > 0 ->
                "${queued} بسته منتظر ارسال هستند"
            deadLetters > 0 ->
                "${deadLetters} بسته به‌دلیل خطای تکراری متوقف شده‌اند"
            prefs.lastSuccessfulSyncAt > 0L ->
                "آخرین ارسال موفق: " +
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT,
                        java.text.DateFormat.SHORT,
                        java.util.Locale("fa", "IR")
                    ).format(java.util.Date(prefs.lastSuccessfulSyncAt))
            else ->
                "هنوز همگام‌سازی انجام نشده است"
        }

        refreshDeviceStats()
        binding.autoSyncSwitch.isChecked = prefs.autoSync
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        refreshAppBlockingStatus()
    }
}
