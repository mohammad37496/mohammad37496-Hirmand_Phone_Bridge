package ir.hirmand.phonebridge.remote

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.sync.SignedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RemoteAppManagerActivity : Activity() {
    private lateinit var prefs: AppPrefs; private lateinit var status: TextView; private lateinit var commandId: String
    private val client by lazy { OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).build() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); prefs=AppPrefs(this); commandId=intent.getStringExtra("command_id").orEmpty()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,50,40,40);gravity=Gravity.CENTER_HORIZONTAL}
        root.addView(TextView(this).apply{text="مدیریت برنامه‌ها";textSize=22f;gravity=Gravity.CENTER})
        status=TextView(this).apply{text="برای ادامه، فهرست برنامه‌های نصب‌شده فقط پس از تأیید شما به پنل ارسال می‌شود.";textSize=17f;setPadding(0,30,0,30);gravity=Gravity.CENTER};root.addView(status)
        root.addView(Button(this).apply{text="تأیید و ارسال فهرست برنامه‌ها";setOnClickListener{thread{sendApps()}}})
        root.addView(Button(this).apply{text="باز کردن تنظیمات یک برنامه";setOnClickListener{chooseAppSettings()}})
        root.addView(Button(this).apply{text="لغو";setOnClickListener{post(false,JSONObject(), "درخواست لغو شد");finish()}})
        setContentView(root)
    }
    private fun sendApps() {
        val pm=packageManager; val arr=JSONArray()
        pm.getInstalledApplications(0).asSequence()
            .filter{it.packageName!=packageName}
            .take(300)
            .forEach{info->
                val label=pm.getApplicationLabel(info).toString()
                val ai=runCatching{pm.getApplicationInfo(info.packageName,0)}.getOrNull()
                val pi=runCatching{pm.getPackageInfo(info.packageName,0)}.getOrNull()
                arr.put(JSONObject().put("name",label).put("packageName",info.packageName).put("versionName",pi?.versionName?:"—").put("versionCode",if(android.os.Build.VERSION.SDK_INT>=28)pi?.longVersionCode?:0 else 0).put("system",(info.flags and ApplicationInfo.FLAG_SYSTEM)!=0))
            }
        post(true,JSONObject().put("count",arr.length()).put("apps",arr),null)
        runOnUiThread{status.text="فهرست "+arr.length()+" برنامه ارسال شد.";window.decorView.postDelayed({finish()},1200)}
    }
    private fun chooseAppSettings(){
        val intent=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:"+packageName))
        startActivity(intent)
    }
    private fun post(ok:Boolean,result:JSONObject,error:String?) {
        if(commandId.isBlank())return; val endpoint=prefs.endpoint.trim();val token=prefs.token.trim();val device=prefs.installId.trim()
        if(endpoint.isBlank()||token.isBlank()||device.isBlank())return
        val body=JSONObject().put("deviceId",device).put("commandId",commandId).put("action","list_apps").put("success",ok).put("error",error?:JSONObject.NULL).put("result",result).toString().toByteArray()
        thread{runCatching{val req=Request.Builder().url(endpoint.trimEnd('/')+"/remote-control/result").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).header("Authorization","Bearer "+token).header("X-Hirmand-Device-Id",device);SignedRequest.addHeaders(req,token,device,body);client.newCall(req.build()).execute().close()}}
    }
}