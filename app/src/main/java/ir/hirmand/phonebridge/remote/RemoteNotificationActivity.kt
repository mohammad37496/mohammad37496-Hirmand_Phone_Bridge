package ir.hirmand.phonebridge.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RemoteNotificationActivity : Activity() {
    private lateinit var prefs:AppPrefs; private lateinit var status:TextView; private lateinit var commandId:String
    private val client by lazy{OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).build()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);prefs=AppPrefs(this);commandId=intent.getStringExtra("command_id").orEmpty()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,50,40,40);gravity=Gravity.CENTER_HORIZONTAL}
        root.addView(TextView(this).apply{text="مدیریت اعلان‌ها";textSize=22f;gravity=Gravity.CENTER})
        status=TextView(this).apply{text="برای دریافت اعلان‌های اخیر باید دسترسی Notification Access را خودتان فعال کنید.";textSize=17f;setPadding(0,30,0,30);gravity=Gravity.CENTER};root.addView(status)
        root.addView(Button(this).apply{text="باز کردن تنظیمات دسترسی اعلان‌ها";setOnClickListener{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}})
        root.addView(Button(this).apply{text="تأیید و ارسال اعلان‌های اخیر";setOnClickListener{send()}})
        root.addView(Button(this).apply{text="لغو";setOnClickListener{post(false,JSONObject(),"درخواست لغو شد");finish()}})
        setContentView(root)
    }
    private fun enabled():Boolean=android.provider.Settings.Secure.getString(contentResolver,"enabled_notification_listeners")?.split(":")?.any{it.contains(packageName)}==true
    private fun send(){
        if(!enabled()){status.text="ابتدا دسترسی اعلان‌ها را در تنظیمات Android فعال کنید.";return}
        val arr=org.json.JSONArray();HirmandNotificationListenerService.recent.take(50).forEach{arr.put(it)}
        post(true,JSONObject().put("count",arr.length()).put("notifications",arr),null);status.text=""+arr.length()+" اعلان ارسال شد.";window.decorView.postDelayed({finish()},1200)
    }
    private fun post(ok:Boolean,result:JSONObject,error:String?){
        if(commandId.isBlank())return;val endpoint=prefs.endpoint.trim();val token=prefs.token.trim();val device=prefs.installId.trim();if(endpoint.isBlank()||token.isBlank()||device.isBlank())return
        val body=JSONObject().put("deviceId",device).put("commandId",commandId).put("action","list_notifications").put("success",ok).put("error",error?:JSONObject.NULL).put("result",result).toString().toByteArray()
        thread{runCatching{val req=Request.Builder().url(endpoint.trimEnd('/')+"/remote-control/result").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).header("Authorization","Bearer "+token).header("X-Hirmand-Device-Id",device);SignedRequest.addHeaders(req,token,device,body);client.newCall(req.build()).execute().close()}}
    }
}