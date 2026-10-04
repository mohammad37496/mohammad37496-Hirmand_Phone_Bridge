package ir.hirmand.phonebridge.remote

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.sync.SignedRequest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RemoteAudioActivity: AppCompatActivity(){
 companion object{const val REQ=811;const val EXTRA_ID="command_id";const val EXTRA_FORMAT="audio_format";const val EXTRA_DURATION="duration_seconds"}
 private lateinit var prefs:AppPrefs; private lateinit var status:TextView; private lateinit var timer:TextView
 private var commandId=""; private var format="wav"; private var duration=60; private var recorder:MediaRecorder?=null; private var recording=false; private var startedAt=0L; private var output:File?=null
 private val handler=Handler(Looper.getMainLooper())
 private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build()
 override fun onCreate(b:Bundle?){super.onCreate(b);prefs=AppPrefs(this);commandId=intent.getStringExtra(EXTRA_ID).orEmpty();format=intent.getStringExtra(EXTRA_FORMAT).orEmpty().ifBlank{"wav"};duration=intent.getIntExtra(EXTRA_DURATION,60).coerceIn(60,3600);buildUi();if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),REQ)}
 private fun buildUi(){val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,30,24,24);setBackgroundColor(0xff080b10.toInt())};status=TextView(this).apply{text="درخواست ضبط صدا";textSize=20f;setTextColor(-1)};timer=TextView(this).apply{text="مدت: "+duration+" ثانیه";textSize=16f;setTextColor(0xffc7d0db.toInt());setPadding(0,16,0,24)};val info=TextView(this).apply{text="فرمت: "+format.uppercase()+"\nاین ضبط فقط پس از تأیید شما شروع می‌شود و هنگام ضبط وضعیت آن روی صفحه قابل مشاهده است.";setTextColor(0xffc7d0db.toInt());textSize=14f};val start=Button(this).apply{text="تأیید و شروع ضبط";isAllCaps=false;setOnClickListener{startRecording()}};val stop=Button(this).apply{text="توقف و ارسال";isAllCaps=false;setOnClickListener{stopRecording(true)};isEnabled=false};root.addView(status);root.addView(timer);root.addView(info);root.addView(start);root.addView(stop);setContentView(root)}
 private fun startRecording(){if(recording)return;if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){status.text="مجوز میکروفون لازم است";return};if(format=="mp3"){status.text="MP3 در این نسخه پشتیبانی نمی‌شود؛ WAV یا AMR را انتخاب کنید.";return};output=File(cacheDir,"remote-audio-"+System.currentTimeMillis()+"."+format);try{recorder=MediaRecorder(this).apply{setAudioSource(MediaRecorder.AudioSource.MIC);if(format=="amr"){setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)}else{setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);setAudioEncoder(MediaRecorder.AudioEncoder.AAC)};setOutputFile(output!!.absolutePath);prepare();start()};recording=true;startedAt=System.currentTimeMillis();status.text="در حال ضبط صدا…";handler.post(tick);handler.postDelayed({stopRecording(true)},duration*1000L)}catch(e:Exception){status.text="شروع ضبط ناموفق بود";recorder?.release();recorder=null}}
 private val tick=object:Runnable{override fun run(){if(!recording)return;val sec=((System.currentTimeMillis()-startedAt)/1000).toInt();timer.text="در حال ضبط · "+sec+" / "+duration+" ثانیه";handler.postDelayed(this,1000)}}
 private fun stopRecording(send:Boolean){if(!recording)return;recording=false;handler.removeCallbacks(tick);runCatching{recorder?.stop()};recorder?.release();recorder=null;status.text="ضبط پایان یافت؛ در حال ارسال…";if(send)Thread{upload()} .start()}
 private fun upload(){val file=output;if(file==null||!file.exists()){postResult(false,null,"فایل ضبط پیدا نشد");return};val bytes=file.readBytes();val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)};val mime=if(format=="amr")"audio/amr" else "audio/wav";val endpoint=prefs.endpoint.trim();val token=prefs.token.trim();val device=prefs.installId.trim();if(!EndpointPolicy.isAllowed(endpoint)||token.isBlank()||device.isBlank()){postResult(false,null,"تنظیمات اتصال کامل نیست");return};val nameB64=java.util.Base64.getEncoder().encodeToString(file.name.toByteArray());val req=Request.Builder().url(endpoint.trimEnd('/')+"/remote-control/audio").post(bytes.toRequestBody(mime.toMediaType())).header("Authorization","Bearer "+token).header("X-Hirmand-Device-Id",device).header("X-Hirmand-Command-Id",commandId).header("X-Hirmand-File-Sha256",sha).header("X-Hirmand-File-Size",bytes.size.toString()).header("X-Hirmand-File-Mime",mime).header("X-Hirmand-File-Name",nameB64);SignedRequest.addHeaders(req,token,device,bytes);runCatching{client.newCall(req.build()).execute().use{r->if(!r.isSuccessful){postResult(false,null,"آپلود ناموفق بود");return};val id=JSONObject(r.body?.string().orEmpty()).optString("fileId");postResult(true,JSONObject().put("fileId",id).put("audioFormat",format).put("durationSeconds",((System.currentTimeMillis()-startedAt)/1000).coerceAtMost(duration.toLong())).put("mimeType",mime).put("sizeBytes",bytes.size).put("sha256",sha),null)}}.onFailure{postResult(false,null,"ارتباط برای ارسال فایل برقرار نشد")}}
 private fun postResult(ok:Boolean,result:JSONObject?,error:String?){val endpoint=prefs.endpoint.trim();val token=prefs.token.trim();val device=prefs.installId.trim();if(!EndpointPolicy.isAllowed(endpoint)||token.isBlank()||device.isBlank())return;val body=JSONObject().put("deviceId",device).put("commandId",commandId).put("action","record_audio").put("success",ok).put("error",error?:JSONObject.NULL).put("result",result?:JSONObject());val bytes=body.toString().toByteArray();Thread{runCatching{val req=Request.Builder().url(endpoint.trimEnd('/')+"/remote-control/result").post(bytes.toRequestBody("application/json".toMediaType())).header("Authorization","Bearer "+token).header("X-Hirmand-Device-Id",device);SignedRequest.addHeaders(req,token,device,bytes);client.newCall(req.build()).execute().use{if(it.isSuccessful)runOnUiThread{status.text=if(ok)"فایل صدا ارسال شد." else error?:"ناموفق";handler.postDelayed({finish()},1200)}}}}.start()}
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==REQ&&g.firstOrNull()==PackageManager.PERMISSION_GRANTED)status.text="مجوز میکروفون داده شد؛ تأیید و شروع ضبط را بزنید"}
 override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(recording)runCatching{recorder?.stop()};recorder?.release();super.onDestroy()}
}