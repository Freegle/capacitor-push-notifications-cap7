/* FREEGLE 7.0.2 */

package com.capacitorjs.plugins.pushnotifications;

import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

// Freegle..
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import java.util.Map;
// ..Freegle

public class MessagingService extends FirebaseMessagingService {

    private static final String packageName = "org.ilovefreegle.direct"; // Freegle
    private static final String channelId = "PushDefaultForeground"; // Freegle

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        boolean fired = PushNotificationsPlugin.sendRemoteMessage(remoteMessage); // Freegle..
        if( !fired){
          sendServiceNotification(remoteMessage); // If app not alive 
        } // ..Freegle
    }

    @Override
    public void onNewToken(@NonNull String s) {
        super.onNewToken(s);
        PushNotificationsPlugin.onNewToken(s);
    }

    // Freegle..
    private void sendServiceNotification(@NonNull RemoteMessage remoteMessage) {
      Map<String, String> msgdata = remoteMessage.getData();
      if (msgdata == null) return;

      // FREEGLE: Only process notifications WITH channel_id (new app behavior)
      // Legacy notifications (no channel_id) are ignored to prevent duplicates
      String channelIdCheck = msgdata.get("channel_id");
      if (channelIdCheck == null || channelIdCheck.isEmpty()) {
        Log.d("PushNotifications", "Ignoring legacy notification without channel_id");
        return;
      }

      try{
        // Extract fields with null checking
        String titleStr = msgdata.get("title");
        String messageStr = msgdata.get("message");
        String countStr = msgdata.get("count");
        String notIdStr = msgdata.get("notId");

        if (titleStr == null || messageStr == null || countStr == null || notIdStr == null) {
          Log.e("PushNotifications", "Missing required fields in notification payload");
          return;
        }

        String title = titleStr;
        String message = messageStr;
        int count = Integer.parseInt(countStr);
        int notId = Integer.parseInt(notIdStr);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if( count==0){
          notificationManager.cancelAll();
          return;
        }
        Intent intent = new Intent(this, Class.forName(packageName+".MainActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notId, intent, PendingIntent.FLAG_IMMUTABLE);

        int pushIcon = android.R.drawable.ic_dialog_info;
        Bundle bundle = null;
        Resources r = null;
        ApplicationInfo applicationInfo = null;
        int appIconResId = 0;
        try {
            applicationInfo = getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            bundle = applicationInfo.metaData;
            r = getPackageManager().getResourcesForApplication(packageName);
            appIconResId = applicationInfo.icon;
        } catch (PackageManager.NameNotFoundException e) {
        }
        if (bundle != null && bundle.getInt("com.google.firebase.messaging.default_notification_icon") != 0) {
            pushIcon = bundle.getInt("com.google.firebase.messaging.default_notification_icon");
        }

        // Get channel_id, category, image, and timestamp from payload
        String notifChannelId = msgdata.get("channel_id");
        if (notifChannelId == null || notifChannelId.isEmpty()) {
            notifChannelId = channelId; // Use default
        }
        String category = msgdata.get("category");
        String imageUrl = msgdata.get("image");
        String timestampStr = msgdata.get("timestamp");

        Notification.Builder builder =
                new Notification.Builder(this, notifChannelId)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setSmallIcon(pushIcon)
                        .setPriority(Notification.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setColor(Color.GREEN)
                        .setContentIntent(pendingIntent);

        // Set timestamp if available - OS will display it automatically
        if (timestampStr != null && !timestampStr.isEmpty()) {
            try {
                long timestamp = Long.parseLong(timestampStr);
                builder.setWhen(timestamp * 1000);  // Convert to milliseconds
                builder.setShowWhen(true);
            } catch (NumberFormatException e) {
                Log.w("PushNotifications", "Invalid timestamp: " + timestampStr);
            }
        }

        PushNotificationsPlugin.setLargeIcon(builder,r,appIconResId,imageUrl);

        // Add action buttons based on category
        PushNotificationsPlugin.addNotificationActions(this, builder, category, msgdata, notId);

        notificationManager.notify(notId, builder.build());
      } catch (Exception e) {
        Log.e("PushNotifications", "sendServiceNotification exception "+e.getMessage());
      }
    }
    // ..Freegle
}
