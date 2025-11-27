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

        // FREEGLE: Notify plugin which handles both notification display and JS layer
        // The plugin uses NotificationHelper for consistent notification creation
        PushNotificationsPlugin.sendRemoteMessage(remoteMessage);
    }

    @Override
    public void onNewToken(@NonNull String s) {
        super.onNewToken(s);
        PushNotificationsPlugin.onNewToken(s);
    }
}
