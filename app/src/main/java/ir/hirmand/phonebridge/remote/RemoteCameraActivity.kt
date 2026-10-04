package ir.hirmand.phonebridge.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.sync.SignedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.max

class RemoteCameraActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_CAMERA = 701
        private const val EXTRA_COMMAND_ID = "command_id"
        private const val EXTRA_CAMERA = "camera"
        private const val EXTRA_FLASH = "flash"
        private const val CAMERA_BACK = "back"
        private const val CAMERA_FRONT = "front"
    }

    private lateinit var prefs: AppPrefs
    private lateinit var cameraManager: CameraManager
    private lateinit var preview: TextureView
    private lateinit var info: TextView
    private lateinit var flashCheck: CheckBox
    private lateinit var backButton: Button
    private lateinit var frontButton: Button
    private lateinit var captureButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private var commandId = ""
    private var selectedCamera = CAMERA_BACK
    private var flashRequested = false
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var outputSize = Size(1280, 720)
    private var capturing = false
    private var finished = false
    private var currentFlashAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        commandId = intent.getStringExtra(EXTRA_COMMAND_ID).orEmpty().trim()
        selectedCamera = intent.getStringExtra(EXTRA_CAMERA).orEmpty().ifBlank { CAMERA_BACK }
        if (selectedCamera !in setOf(CAMERA_BACK, CAMERA_FRONT)) selectedCamera = CAMERA_BACK
        flashRequested = intent.getBooleanExtra(EXTRA_FLASH, false)

        if (commandId.isBlank()) {
            finish()
            return
        }

        buildUi()
        onBackPressedDispatcher.addCallback(this) {
            cancelAndFinish("درخواست گرفتن عکس لغو شد")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            openSelectedCamera()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF05070A.toInt())
            setPadding(14, 14, 14, 14)
        }

        info = TextView(this).apply {
            text = "درخواست گرفتن عکس ریموت"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            setPadding(4, 4, 4, 12)
        }
        root.addView(info, LinearLayout.LayoutParams(-1, -2))

        val previewFrame = FrameLayout(this)
        preview = TextureView(this)
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }

        val cameraRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        backButton = Button(this).apply {
            text = "دوربین عقب"
            isAllCaps = false
            setOnClickListener {
                if (!capturing) {
                    selectedCamera = CAMERA_BACK
                    openSelectedCamera()
                }
            }
        }
        frontButton = Button(this).apply {
            text = "دوربین جلو"
            isAllCaps = false
            setOnClickListener {
                if (!capturing) {
                    selectedCamera = CAMERA_FRONT
                    openSelectedCamera()
                }
            }
        }
        cameraRow.addView(backButton, LinearLayout.LayoutParams(0, -2, 1f))
        cameraRow.addView(frontButton, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(cameraRow)

        flashCheck = CheckBox(this).apply {
            text = "فلش"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = flashRequested
            setOnCheckedChangeListener { _, checked ->
                flashRequested = checked
                updateFlashState()
            }
        }
        controls.addView(flashCheck)

        captureButton = Button(this).apply {
            text = "گرفتن عکس و ارسال به پنل"
            isAllCaps = false
            setOnClickListener { capturePhoto() }
        }
        controls.addView(captureButton)

        val privacy = TextView(this).apply {
            text = "برای حفظ حریم خصوصی، عکس فقط بعد از باز شدن همین صفحه و فشردن دکمهٔ گرفتن عکس ثبت و به پنل ارسال می‌شود."
            setTextColor(0xFFB7C0CC.toInt())
            textSize = 12f
            setPadding(4, 10, 4, 4)
        }
        controls.addView(privacy)

        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized && preview.isAvailable &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            cameraDevice == null
        ) {
            openSelectedCamera()
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openSelectedCamera()
        } else {
            info.text = "مجوز دوربین داده نشد؛ عکس گرفته نمی‌شود."
            captureButton.isEnabled = false
        }
    }

    private fun openSelectedCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        closeCamera()
        val facing = if (selectedCamera == CAMERA_FRONT) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

        try {
            cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
            }

            val id = cameraId
            if (id == null) {
                info.text = "دوربین انتخاب‌شده روی این گوشی پیدا نشد."
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            outputSize = chooseOutputSize(jpegSizes)

            preview.surfaceTexture?.setDefaultBufferSize(outputSize.width, outputSize.height)
            applyPreviewTransform(characteristics)
            currentFlashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            updateFlashState()

            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    info.text = "باز کردن دوربین ناموفق بود."
                }
            }, mainHandler)
        } catch (error: SecurityException) {
            info.text = "مجوز دوربین در دسترس نیست."
        } catch (error: CameraAccessException) {
            info.text = "دسترسی به دوربین ممکن نشد."
        } catch (error: Exception) {
            info.text = "خطای دوربین: " + (error.message ?: "نامشخص")
        }
    }

    private fun createPreviewSession() {
        val camera = cameraDevice ?: return
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(outputSize.width, outputSize.height)
        val previewSurface = Surface(texture)

        imageReader?.close()
        imageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val buffer = image.planes.firstOrNull()?.buffer
            val bytes = buffer?.let {
                val data = ByteArray(it.remaining())
                it.get(data)
                data
            }
            image.close()
            if (bytes != null && bytes.isNotEmpty() && !finished) {
                handleCapturedJpeg(bytes)
            }
        }, mainHandler)

        camera.createCaptureSession(
            listOf(previewSurface, imageReader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    startPreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    info.text = "ساخت نمای دوربین ناموفق بود."
                }
            },
            mainHandler,
        )
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = preview.surface ?: return

        runCatching {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            session.setRepeatingRequest(request.build(), null, mainHandler)
            updateText()
        }.onFailure {
            info.text = "نمایش دوربین ناموفق بود."
        }
    }

    private fun capturePhoto() {
        if (capturing || finished) return
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val id = cameraId
        if (id.isNullOrBlank()) {
            cancelAndFinish("شناسهٔ دوربین معتبر نیست")
            return
        }

        capturing = true
        captureButton.isEnabled = false
        backButton.isEnabled = false
        frontButton.isEnabled = false
        flashCheck.isEnabled = false
        info.text = "در حال گرفتن عکس…"

        val characteristics = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
        if (characteristics == null) {
            cancelAndFinish("اطلاعات دوربین در دسترس نیست")
            return
        }

        runCatching {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(characteristics))
                if (flashRequested && currentFlashAvailable) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
            }
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CameraCaptureSession.CaptureFailure,
                ) {
                    mainHandler.post { cancelAndFinish("گرفتن عکس ناموفق بود") }
                }
            }, mainHandler)
        }.onFailure {
            cancelAndFinish("آماده‌سازی عکس ناموفق بود")
        }
    }

    private fun handleCapturedJpeg(bytes: ByteArray) {
        Thread {
            uploadPhoto(bytes)
        }.start()
    }

    private fun uploadPhoto(bytes: ByteArray) {
        val endpoint = prefs.endpoint.trim()
        val token = prefs.token.trim()
        val deviceId = prefs.installId.trim()
        if (!EndpointPolicy.isAllowed(endpoint) || token.isBlank() || deviceId.isBlank()) {
            postRemoteResult(false, null, "تنظیمات اتصال Phone Bridge کامل نیست")
            return
        }

        if (bytes.size > 8 * 1024 * 1024) {
            postRemoteResult(false, null, "حجم عکس بیش از حد مجاز است")
            return
        }

        val sha = sha256(bytes)
        val fileName = "remote-photo-" + System.currentTimeMillis() + ".jpg"
        val fileNameB64 = Base64.getEncoder().encodeToString(fileName.toByteArray(Charsets.UTF_8))

        val requestBuilder = Request.Builder()
            .url(endpoint.trimEnd('/') + "/remote-control/photo")
            .post(bytes.toRequestBody("image/jpeg".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("X-Hirmand-Device-Id", deviceId)
            .header("X-Hirmand-Command-Id", commandId)
            .header("X-Hirmand-File-Sha256", sha)
            .header("X-Hirmand-File-Size", bytes.size.toString())
            .header("X-Hirmand-File-Mime", "image/jpeg")
            .header("X-Hirmand-File-Name", fileNameB64)
        SignedRequest.addHeaders(requestBuilder, token, deviceId, bytes)

        runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    postRemoteResult(false, null, "آپلود عکس ناموفق بود (${response.code})")
                    return
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val fileId = json.optString("fileId").trim()
                if (fileId.isBlank()) {
                    postRemoteResult(false, null, "سرور شناسهٔ عکس را برنگرداند")
                    return
                }
                postRemoteResult(
                    true,
                    JSONObject()
                        .put("fileId", fileId)
                        .put("camera", selectedCamera)
                        .put("flash", flashRequested && currentFlashAvailable)
                        .put("fileName", fileName)
                        .put("mimeType", "image/jpeg")
                        .put("sizeBytes", bytes.size)
                        .put("sha256", sha)
                        .put("recordedAt", System.currentTimeMillis()),
                    null,
                )
            }
        }.onFailure {
            postRemoteResult(false, null, "ارتباط برای ارسال عکس برقرار نشد")
        }
    }

    private fun postRemoteResult(success: Boolean, result: JSONObject?, error: String?) {
        if (finished) return
        val endpoint = prefs.endpoint.trim()
        val token = prefs.token.trim()
        val deviceId = prefs.installId.trim()
        if (!EndpointPolicy.isAllowed(endpoint) || token.isBlank() || deviceId.isBlank()) return

        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("commandId", commandId)
            .put("action", "take_photo")
            .put("success", success)
            .put("error", error ?: JSONObject.NULL)
            .put("result", result ?: JSONObject())

        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        Thread {
            runCatching {
                val builder = Request.Builder()
                    .url(endpoint.trimEnd('/') + "/remote-control/result")
                    .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .header("X-Hirmand-Device-Id", deviceId)
                SignedRequest.addHeaders(builder, token, deviceId, bytes)
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        finished = true
                        mainHandler.post {
                            info.text = if (success) {
                                "عکس با موفقیت ثبت و به پنل ارسال شد."
                            } else {
                                error ?: "گرفتن عکس ناموفق بود."
                            }
                            captureButton.isEnabled = false
                            flashCheck.isEnabled = false
                        }
                    }
                }
            }
        }.start()
    }

    private fun cancelAndFinish(reason: String) {
        if (finished) {
            finish()
            return
        }
        finished = true
        val endpoint = prefs.endpoint.trim()
        val token = prefs.token.trim()
        val deviceId = prefs.installId.trim()
        if (EndpointPolicy.isAllowed(endpoint) && token.isNotBlank() && deviceId.isNotBlank()) {
            val body = JSONObject()
                .put("deviceId", deviceId)
                .put("commandId", commandId)
                .put("action", "take_photo")
                .put("success", false)
                .put("error", reason)
                .put("result", JSONObject())
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            Thread {
                runCatching {
                    val requestBuilder = Request.Builder()
                        .url(endpoint.trimEnd('/') + "/remote-control/result")
                        .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .header("Authorization", "Bearer $token")
                        .header("X-Hirmand-Device-Id", deviceId)
                    SignedRequest.addHeaders(requestBuilder, token, deviceId, bytes)
                    client.newCall(requestBuilder.build()).execute().close()
                }
            }.start()
        }
        mainHandler.post {
            info.text = reason
            finish()
        }
    }

    private fun updateText() {
        val cameraLabel = if (selectedCamera == CAMERA_FRONT) "جلو" else "عقب"
        val flashLabel = if (flashRequested && currentFlashAvailable) "فعال" else "خاموش"
        info.text = "دوربین $cameraLabel · فلش $flashLabel · آمادهٔ گرفتن عکس"
        preview.scaleX = if (selectedCamera == CAMERA_FRONT) -1f else 1f
    }

    private fun updateFlashState() {
        flashCheck.isEnabled = currentFlashAvailable && selectedCamera == CAMERA_BACK && !capturing
        if (!flashCheck.isEnabled) {
            flashRequested = false
            flashCheck.isChecked = false
        }
        updateText()
    }

    private fun applyPreviewTransform(characteristics: CameraCharacteristics) {
        val rotation = windowManager.defaultDisplay.rotation
        val viewWidth = max(1, preview.width)
        val viewHeight = max(1, preview.height)
        val matrix = Matrix()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            matrix.setRotate(
                90f * if (rotation == Surface.ROTATION_90) 1f else -1f,
                viewWidth / 2f,
                viewHeight / 2f,
            )
        }
        preview.setTransform(matrix)
    }

    private fun jpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceRotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (selectedCamera == CAMERA_FRONT) {
            (sensorOrientation + deviceRotation) % 360
        } else {
            (sensorOrientation - deviceRotation + 360) % 360
        }
    }

    private fun chooseOutputSize(sizes: List<Size>): Size {
        val preferred = sizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        return preferred ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(1280, 720)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        cameraId = null
    }
}
