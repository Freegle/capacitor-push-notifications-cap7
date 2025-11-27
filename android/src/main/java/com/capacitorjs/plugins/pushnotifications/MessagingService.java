/* FREEGLE 7.0.2 */

package com.capacitorjs.plugins.pushnotifications;

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

        // FREEGLE: Try to notify plugin first (handles JS layer + notification display)
        boolean pluginHandled = PushNotificationsPlugin.sendRemoteMessage(remoteMessage);

        // If plugin not available (app not running), show notification directly
        // This ensures notifications appear even when app is killed
        if (!pluginHandled) {
            Map<String, String> msgdata = remoteMessage.getData();
            if (msgdata != null) {
                NotificationHelper.createAndShowNotification(this, msgdata);
            }
        }
    }

    @Override
    public void onNewToken(@NonNull String s) {
        super.onNewToken(s);
        PushNotificationsPlugin.onNewToken(s);
    }
}
