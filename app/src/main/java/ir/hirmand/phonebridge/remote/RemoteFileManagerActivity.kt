package ir.hirmand.phonebridge.remote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.sync.SignedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RemoteFileManagerActivity : Activity() {
    companion object { private const val PICK_TREE=4101; private const val MAX_ENTRIES=2500; private const val MAX_UPLOAD_BYTES=8L*1024*1024 }
    private lateinit var prefs:AppPrefs; private lateinit var status:TextView; private lateinit var commandId:String; private lateinit var operation:String; private lateinit var requestedUri:String
    private val client by lazy { OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).callTimeout(90,TimeUnit.SECONDS).build() }

    override fun onCreate(state:Bundle?){
        super.onCreate(state); prefs=AppPrefs(this); commandId=intent.getStringExtra("command_id").orEmpty(); operation=intent.getStringExtra("operation").orEmpty(); requestedUri=intent.getStringExtra("uri").orEmpty()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,50,40,40);gravity=Gravity.CENTER_HORIZONTAL}
        root.addView(TextView(this).apply{text="مدیریت فایل‌های Phone Bridge";textSize=22f;gravity=Gravity.CENTER})
        status=TextView(this).apply{text=if(operation=="download")"فایل برای ارسال به پنل آماده است؛ برای ادامه تأیید کنید." else "برای نمایش فایل‌ها باید یک پوشه را خودتان انتخاب و اجازه دسترسی بدهید.";textSize=17f;setPadding(0,30,0,30);gravity=Gravity.CENTER};root.addView(status)
        if(operation=="download"){
            root.addView(Button(this).apply{text="تأیید و ارسال فایل به پنل";setOnClickListener{thread{uploadRequestedFile()}}})
            root.addView(Button(this).apply{text="لغو";setOnClickListener{postResult(false,JSONObject(),"ارسال فایل لغو شد");finish()}})
        }else{
            root.addView(Button(this).apply{text="انتخاب پوشه";setOnClickListener{openTreePicker()}})
            root.addView(Button(this).apply{text="بستن";setOnClickListener{postResult(true,JSONObject().put("entries",0),null);finish()}})
        };setContentView(root)
    }
    private fun openTreePicker(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),PICK_TREE)}
    @Deprecated("legacy result API")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data);if(requestCode!=PICK_TREE)return
        if(resultCode!=RESULT_OK||data?.data==null){postResult(false,JSONObject(),"انتخاب پوشه لغو شد");finish();return}
        val tree=data.data!!;runCatching{val takeFlags=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);if(takeFlags!=0)contentResolver.takePersistableUriPermission(tree,takeFlags)};status.text="پوشه انتخاب شد؛ در حال خواندن فهرست فایل‌ها…"
        thread{val entries=enumerateTree(tree);val ok=postIndex(tree,entries);runOnUiThread{status.text=if(ok)"فهرست "+entries.size+" مورد به پنل ارسال شد." else "ارسال فهرست ناموفق بود.";postResult(ok,JSONObject().put("rootUri",tree.toString()).put("entries",entries.size),if(ok)null else "ارسال فهرست ناموفق بود");window.decorView.postDelayed({finish()},1200)}}
    }
    private fun enumerateTree(root:Uri):List<JSONObject>{
        val out=ArrayList<JSONObject>()
        fun walk(parent:Uri,relative:String,depth:Int){
            if(depth>30||out.size>=MAX_ENTRIES)return
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getTreeDocumentId(parent))
            contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,DocumentsContract.Document.COLUMN_DISPLAY_NAME+" ASC")?.use{c->
                val id=c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val name=c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);val mime=c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);val size=c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);val modified=c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while(c.moveToNext()&&out.size<MAX_ENTRIES){
                    val docId=c.getString(id);val display=if(name>=0)c.getString(name).orEmpty() else "";val type=c.getString(mime)?:"application/octet-stream";val dir=type==DocumentsContract.Document.MIME_TYPE_DIR;val child=DocumentsContract.buildDocumentUriUsingTree(parent,docId);val rel=if(relative.isBlank())display else relative+"/"+display
                    out.add(JSONObject().put("uri",child.toString()).put("parentUri",parent.toString()).put("name",display).put("relativePath",rel).put("mimeType",type).put("sizeBytes",if(size>=0&&!c.isNull(size))c.getLong(size) else 0L).put("modifiedAt",if(modified>=0&&!c.isNull(modified))c.getLong(modified) else 0L).put("isDirectory",dir));if(dir)walk(child,rel,depth+1)
                }
            }
        };walk(root,"",0);return out
    }
    private fun postIndex(root:Uri,entries:List<JSONObject>):Boolean{
        val endpoint=prefs.endpoint.trim();val token=prefs.token.trim();val device=prefs.installId.trim();if(!EndpointPolicy.isAllowed(endpoint)||token.isBlank()||device.isBlank())return false
        val limited=JSONArray();entries.take(1800).forEach{limited.put(it)};val body=JSONObject().put("deviceId",device).put("rootUri",root.toString()).put("entries",limited).toString().toByteArray()
        return runCatching{val req=Request.Builder().url(endpoint.trimEnd('/')+"/remote-control/files/index").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).header("Authorization","Bearer "+token).header("X-Hirmand-Device-Id",device);SignedRequest.addHeaders(req,token,device,body);client.newCall(req.build()).execute().use{it.isSuccessful}}.getOrDefault(false)
    }
    private fun uploadRequestedFile(){
        val endpoint=prefs.endpoint.trim();val token=prefs.token.trim();val device=prefs.installId.trim();if(!EndpointPolicy.isAllowed(endpoint)||token.isBlank()||device.isBlank()){runOnUiThread{status.text="تنظیمات ارتباطی معتبر نیست."};return}
        val uri=Uri.parse(requestedUri);val name=queryName(uri);val bytes=contentResolver.openInputStream(uri)?.use{it.readBytes()};if(bytes==null||bytes.size.toLong()>MAX_UPLOAD_BYTES){runOnUiThread{status.text="فایل وجود ندارد یا بیش از ۸ مگابایت است."};return}
        val sha=sha256(bytes);val mime=contentResolver.getType(uri)?:"application/octet-stream";val nameB64=android.util.Base64.encodeToString(name.toByteArray(),android.util.Base64.NO_WRAP)
        val req=Request.Builder().url(endpoint.trimEnd('/')+"/files").post(bytes.toRequestBody(mime.toMediaType())).header("Authorization","Bearer "+token).header("X-Hirmand-Device-Id",device).header("X-Hirmand-File-Sha256",sha).header("X-Hirmand-File-Size",bytes.size.toString()).header("X-Hirmand-File-Mime",mime).header("X-Hirmand-File-Name",nameB64);SignedRequest.addHeaders(req,token,device,bytes)
        runCatching{client.newCall(req.build()).execute().use{response->if(!response.isSuccessful){runOnUiThread{status.text="آپلود فایل ناموفق بود."};return};val id=JSONObject(response.body?.string().orEmpty()).optString("fileId");runOnUiThread{status.text="فایل ارسال شد: "+name};postResult(true,JSONObject().put("fileId",id).put("fileName",name).put("mimeType",mime).put("sizeBytes",bytes.size).put("sha256",sha),null);runOnUiThread{window.decorView.postDelayed({finish()},1200)}}}.onFailure{runOnUiThread{status.text="ارتباط برای ارسال فایل برقرار نشد."}}
    }
    private fun queryName(uri:Uri):String{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())return it.getString(0).orEmpty()};return uri.lastPathSegment?.substringAfterLast('/')?:"file"}
    private fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 0xff)}
    private fun postResult(success:Boolean,result:JSONObject,error:String?){
        val endpoint=prefs.endpoint.trim();val token=prefs.token.trim();val device=prefs.installId.trim();if(!EndpointPolicy.isAllowed(endpoint)||token.isBlank()||device.isBlank()||commandId.isBlank())return
        val body=JSONObject().put("deviceId",device).put("commandId",commandId).put("action","manage_files").put("success",success).put("error",error?:JSONObject.NULL).put("result",result).toString().toByteArray()
        thread{runCatching{val req=Request.Builder().url(endpoint.trimEnd('/')+"/remote-control/result").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).header("Authorization","Bearer "+token).header("X-Hirmand-Device-Id",device);SignedRequest.addHeaders(req,token,device,body);client.newCall(req.build()).execute().close()}}
    }
}
