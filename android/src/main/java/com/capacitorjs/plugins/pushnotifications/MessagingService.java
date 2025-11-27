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

        // FREEGLE: Always create notification via centralized helper
        // This ensures consistent display regardless of app state
        Map<String, String> msgdata = remoteMessage.getData();
        NotificationHelper.createAndShowNotification(this, msgdata);

        // Also notify plugin for JS layer handling (badge, routing, etc.)
        PushNotificationsPlugin.sendRemoteMessage(remoteMessage);
    }

    @Override
    public void onNewToken(@NonNull String s) {
        super.onNewToken(s);
        PushNotificationsPlugin.onNewToken(s);
    }
}
