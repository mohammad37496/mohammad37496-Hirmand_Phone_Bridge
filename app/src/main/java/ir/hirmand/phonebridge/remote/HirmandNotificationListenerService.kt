package ir.hirmand.phonebridge.remote

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class HirmandNotificationListenerService : NotificationListenerService() {
    companion object { val recent=CopyOnWriteArrayList<JSONObject>(); private const val MAX=100 }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras=sbn.notification.extras
        val row=JSONObject().put("packageName",sbn.packageName).put("title",extras.getString("android.title")?:"").put("text",extras.getCharSequence("android.text")?.toString()?:"").put("postedAt",sbn.postTime)
        recent.removeIf{it.optString("packageName")==sbn.packageName && it.optLong("postedAt")==sbn.postTime}
        recent.add(0,row); while(recent.size>MAX)recent.removeAt(recent.lastIndex)
    }
}