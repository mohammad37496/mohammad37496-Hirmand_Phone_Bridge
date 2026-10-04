package ir.hirmand.phonebridge.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ConsentGateActivity : AppCompatActivity() {
    private lateinit var consentStore: ConsentStore
    private val checks = mutableMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentStore = ConsentStore(this)

        if (consentStore.isAccepted()) {
            openMain()
            return
        }

        setContentView(buildView())
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            text = "فعال‌سازی Hirmand Phone Bridge"
            textSize = 24f
            gravity = Gravity.RIGHT
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title, matchWrap(0, 16))

        val intro = TextView(this).apply {
            text = "این گوشی برای استفاده کاری بنگاه مدیریت می‌شود. قبل از ورود، حوزه‌هایی را که با رضایت خودت اجازه استفاده از آن‌ها را می‌دهی انتخاب کن. این تأیید فقط رضایت داخل برنامه است؛ هر قابلیت حساس هنوز باید مجوز رسمی Android را جداگانه دریافت کند."
            textSize = 16f
            gravity = Gravity.RIGHT
            setTextIsSelectable(true)
        }
        root.addView(intro, matchWrap(0, 24))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(list)

        addScope(list, ConsentStore.DEVICE_STATUS, "وضعیت و سلامت دستگاه", "باتری، حافظه، نسخه Android و وضعیت اتصال کاری.")
        addScope(list, ConsentStore.LOCATION, "موقعیت کاری", "موقعیت فقط برای قابلیت‌های کاری که در خود برنامه فعال می‌کنی.")
        addScope(list, ConsentStore.CONTACTS_CALLS_SMS, "مخاطبین، تماس و پیامک", "فقط در صورت فعال‌سازی ماژول مربوط و دریافت Permission رسمی Android.")
        addScope(list, ConsentStore.CALENDAR, "تقویم", "داده‌های تقویم فقط با Permission رسمی Android.")
        addScope(list, ConsentStore.APPS, "برنامه‌های دستگاه", "اطلاعات محدود برنامه‌ها برای مدیریت دستگاه کاری.")
        addScope(list, ConsentStore.SELECTED_FILES, "فایل‌های انتخابی", "فقط فایل‌هایی که خودت از انتخاب‌گر Android انتخاب می‌کنی.")
        addScope(list, ConsentStore.NOTIFICATIONS, "اعلان‌های کاری", "دسترسی اعلان فقط در صورت فعال‌سازی قابلیت مربوط و تأیید Android.")
        addScope(list, ConsentStore.CAMERA, "دوربین", "استفاده از دوربین فقط با قابلیت فعال، رضایت ثبت‌شده و Permission رسمی Android.")
        addScope(list, ConsentStore.MICROPHONE, "میکروفون", "استفاده از میکروفون فقط با قابلیت فعال، رضایت ثبت‌شده و Permission رسمی Android.")

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0).apply {
            weight = 1f
        })

        val agreement = TextView(this).apply {
            text = "با فعال‌کردن هر مورد، تأیید می‌کنی که این حوزه برای استفاده کاری و طبق سیاست بنگاه مجاز است."
            textSize = 14f
            gravity = Gravity.RIGHT
            setPadding(0, 18, 0, 12)
        }
        root.addView(agreement, matchWrap(0, 4))

        val acceptButton = MaterialButton(this).apply {
            text = "ثبت رضایت و ورود به برنامه"
            isEnabled = true
            setOnClickListener {
                val selected = checks.filterValues { it.isChecked }.keys.toSet()
                if (ConsentStore.DEVICE_STATUS !in selected) {
                    MaterialAlertDialogBuilder(this@ConsentGateActivity)
                        .setTitle("وضعیت دستگاه لازم است")
                        .setMessage("برای اجرای برنامه، وضعیت پایهٔ دستگاه باید برای مدیریت کاری تأیید شود.")
                        .setPositiveButton("باشه", null)
                        .show()
                    return@setOnClickListener
                }
                if (selected.isEmpty()) {
                    MaterialAlertDialogBuilder(this@ConsentGateActivity)
                        .setTitle("انتخاب حوزه لازم است")
                        .setMessage("حداقل یک حوزه را مشخص کن تا رضایت ثبت شود.")
                        .setPositiveButton("باشه", null)
                        .show()
                    return@setOnClickListener
                }
                consentStore.accept(selected)
                openMain()
            }
        }
        root.addView(acceptButton, matchWrap(0, 0))

        checks[ConsentStore.DEVICE_STATUS]?.apply {
            isChecked = true
            isEnabled = false
        }

        checks.values.forEach { checkbox ->
            checkbox.setOnCheckedChangeListener { _, _ ->
                acceptButton.isEnabled = ConsentStore.DEVICE_STATUS in checks
                    .filterValues { it.isChecked }
                    .keys
            }
        }

        return root
    }

    private fun addScope(parent: LinearLayout, key: String, title: String, summary: String) {
        val check = CheckBox(this).apply {
            text = title
            textSize = 17f
            gravity = Gravity.RIGHT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val description = TextView(this).apply {
            text = summary
            textSize = 13f
            gravity = Gravity.RIGHT
            setPadding(8, 0, 8, 18)
        }
        parent.addView(check, matchWrap(0, 0))
        parent.addView(description, matchWrap(0, 0))
        checks[key] = check
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun matchWrap(top: Int, bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, top, 0, bottom)
        }
}
