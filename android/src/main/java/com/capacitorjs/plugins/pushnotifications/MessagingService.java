/* FREEGLE 7.0.2 */

package com.capacitorjs.plugins.pushnotifications;

import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/**
 * Handles FCM messages.
 * For data-only messages (what Freegle uses), onMessageReceived is ALWAYS called
 * regardless of app state (foreground/background/killed).
 *
 * Notification display is now centralized in NotificationHelper to ensure
 * consistent behavior regardless of app state.
 */
public class MessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        // Store debug info in SharedPreferences so it can be read when app starts
        StringBuilder debugLog = new StringBuilder();
        debugLog.append("onMessageReceived at ").append(System.currentTimeMillis()).append("\n");
        debugLog.append("Data: ").append(remoteMessage.getData()).append("\n");

        Log.d("MessagingService", "onMessageReceived called");
        Log.d("MessagingService", "Message data: " + remoteMessage.getData());

        // FREEGLE: Try to notify plugin first (handles JS layer + notification display)
        boolean pluginHandled = PushNotificationsPlugin.sendRemoteMessage(remoteMessage);
        debugLog.append("Plugin handled: ").append(pluginHandled).append("\n");
        Log.d("MessagingService", "Plugin handled: " + pluginHandled);

        // If plugin not available (app not running), show notification directly
        // This ensures notifications appear even when app is killed
        if (!pluginHandled) {
            Map<String, String> msgdata = remoteMessage.getData();
            if (msgdata != null) {
                debugLog.append("Calling NotificationHelper directly\n");
                Log.d("MessagingService", "Calling NotificationHelper directly");
                boolean shown = NotificationHelper.createAndShowNotification(this, msgdata);
                debugLog.append("Notification shown: ").append(shown).append("\n");
                Log.d("MessagingService", "Notification shown: " + shown);
            } else {
                debugLog.append("Message data is null\n");
                Log.w("MessagingService", "Message data is null");
            }
        }

        // Save debug log to SharedPreferences
        try {
            SharedPreferences prefs = getSharedPreferences("push_debug", MODE_PRIVATE);
            String existing = prefs.getString("log", "");
            prefs.edit().putString("log", existing + debugLog.toString() + "---\n").apply();
        } catch (Exception e) {
            Log.e("MessagingService", "Failed to save debug log", e);
        }
    }

    @Override
    public void onNewToken(@NonNull String s) {
        super.onNewToken(s);
        PushNotificationsPlugin.onNewToken(s);
    }
}
