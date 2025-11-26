/* FREEGLE 7.0.2 */

package com.capacitorjs.plugins.pushnotifications;

import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginHandle;

/**
 * Handles notification action button presses (Reply, Mark Read, etc.)
 * Passes the action and any input text back to the JavaScript layer.
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_REPLY = "com.capacitorjs.plugins.pushnotifications.ACTION_REPLY";
    public static final String ACTION_MARK_READ = "com.capacitorjs.plugins.pushnotifications.ACTION_MARK_READ";
    public static final String KEY_TEXT_REPLY = "key_text_reply";
    public static final String EXTRA_NOTIFICATION_DATA = "notification_data";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d("NotificationAction", "Action received: " + action);

        if (action == null) return;

        // Get notification data from intent
        Bundle notificationData = intent.getBundleExtra(EXTRA_NOTIFICATION_DATA);
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);

        // Cancel the notification
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(notificationId);
        }

        // Build the action result
        JSObject actionJson = new JSObject();
        JSObject notificationJson = new JSObject();
        JSObject dataObject = new JSObject();

        // Copy notification data to JSON
        if (notificationData != null) {
            for (String key : notificationData.keySet()) {
                Object value = notificationData.get(key);
                if (value != null) {
                    dataObject.put(key, value.toString());
                }
            }
        }
        notificationJson.put("data", dataObject);

        if (ACTION_REPLY.equals(action)) {
            // Get the reply text from RemoteInput
            Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
            String replyText = "";
            if (remoteInput != null) {
                CharSequence replyCharSequence = remoteInput.getCharSequence(KEY_TEXT_REPLY);
                if (replyCharSequence != null) {
                    replyText = replyCharSequence.toString();
                }
            }

            actionJson.put("actionId", "reply");
            actionJson.put("inputValue", replyText);
            Log.d("NotificationAction", "Reply action with text: " + replyText);
        } else if (ACTION_MARK_READ.equals(action)) {
            actionJson.put("actionId", "mark_read");
            Log.d("NotificationAction", "Mark read action");
        } else {
            // Unknown action
            return;
        }

        actionJson.put("notification", notificationJson);

        // Send to JavaScript layer
        PushNotificationsPlugin pushPlugin = PushNotificationsPlugin.getPushNotificationsInstance();
        if (pushPlugin != null) {
            pushPlugin.sendActionPerformed(actionJson);
        } else {
            Log.w("NotificationAction", "PushNotificationsPlugin not available, storing action for later");
            // Store the action to be processed when app starts
            PushNotificationsPlugin.pendingAction = actionJson;
        }
    }
}
