/* FREEGLE 7.0.2 */

package com.capacitorjs.plugins.pushnotifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Centralized notification creation for Freegle.
 * Used by both MessagingService (background) and PushNotificationsPlugin (foreground)
 * to ensure consistent notification display regardless of app state.
 *
 * FUTURE-PROOFING FOR NEW NOTIFICATION TYPES:
 * This implementation is designed to handle future notification types gracefully:
 *
 * 1. Required fields (title, message, count, notId, channel_id) are validated with null checks.
 *    If any are missing, the notification is rejected without crashing (lines 58-61).
 *
 * 2. Optional fields (category, image, timestamp) are checked before use.
 *    Missing optional fields don't break notifications.
 *
 * 3. Unknown categories are handled gracefully - notifications display with basic UI
 *    (no action buttons) but don't crash. Only recognized categories get action buttons.
 *
 * 4. When introducing new notification types in future:
 *    - Use same core required fields (title, message, count, notId, channel_id)
 *    - Add type-specific fields as OPTIONAL with null checks
 *    - Update addNotificationActions() to recognize new category
 *    - Older app versions will display new types as basic notifications without crashing
 */
public class NotificationHelper {

    private static final String TAG = "NotificationHelper";
    private static final String PACKAGE_NAME = "org.ilovefreegle.direct";

    /**
     * Create and display a notification from FCM data payload.
     * Returns true if notification was created successfully, false otherwise.
     */
    public static boolean createAndShowNotification(Context context, Map<String, String> msgdata) {
        if (msgdata == null) {
            Log.w(TAG, "Notification data is null");
            return false;
        }

        // FREEGLE: Only process notifications WITH channel_id (new app behavior)
        // Legacy notifications (no channel_id) are ignored to prevent duplicates
        String channelIdCheck = msgdata.get("channel_id");
        if (channelIdCheck == null || channelIdCheck.isEmpty()) {
            Log.d(TAG, "Ignoring legacy notification without channel_id");
            return false;
        }

        try {
            // Extract required fields with null checking
            String titleStr = msgdata.get("title");
            String messageStr = msgdata.get("message");
            String countStr = msgdata.get("count");
            String notIdStr = msgdata.get("notId");

            if (titleStr == null || messageStr == null || countStr == null || notIdStr == null) {
                Log.e(TAG, "Missing required fields in notification payload");
                return false;
            }

            String title = titleStr;
            String message = messageStr;
            int count = Integer.parseInt(countStr);
            int notId = Integer.parseInt(notIdStr);

            NotificationManager notificationManager = 
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            if (notificationManager == null) {
                Log.e(TAG, "NotificationManager is null");
                return false;
            }

            // Handle clear all notifications
            if (count == 0) {
                notificationManager.cancelAll();
                return true;
            }

            // Get app metadata and resources
            Bundle bundle = null;
            Resources resources = null;
            int appIconResId = 0;
            
            try {
                ApplicationInfo applicationInfo = context.getPackageManager()
                    .getApplicationInfo(PACKAGE_NAME, PackageManager.GET_META_DATA);
                bundle = applicationInfo.metaData;
                resources = context.getPackageManager().getResourcesForApplication(PACKAGE_NAME);
                appIconResId = applicationInfo.icon;
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Package not found: " + PACKAGE_NAME);
            }

            // Get notification icon
            int pushIcon = android.R.drawable.ic_dialog_info;
            if (bundle != null && bundle.getInt("com.google.firebase.messaging.default_notification_icon") != 0) {
                pushIcon = bundle.getInt("com.google.firebase.messaging.default_notification_icon");
            }

            // Create intent to open app
            Intent intent;
            try {
                intent = new Intent(context, Class.forName(PACKAGE_NAME + ".MainActivity"));
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "MainActivity class not found", e);
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                context, notId, intent, PendingIntent.FLAG_IMMUTABLE
            );

            // Get channel_id, category, image, and timestamp from payload
            String notifChannelId = msgdata.get("channel_id");
            if (notifChannelId == null || notifChannelId.isEmpty()) {
                notifChannelId = "PushDefaultForeground"; // Fallback
            }
            String category = msgdata.get("category");
            String imageUrl = msgdata.get("image");
            String timestampStr = msgdata.get("timestamp");

            // Build notification
            Notification.Builder builder = new Notification.Builder(context, notifChannelId)
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
                    Log.w(TAG, "Invalid timestamp: " + timestampStr);
                }
            }

            // Set large icon (profile image)
            setLargeIcon(builder, resources, appIconResId, imageUrl);

            // Add action buttons based on category
            PushNotificationsPlugin.addNotificationActions(context, builder, category, msgdata, notId);

            // Show notification
            notificationManager.notify(notId, builder.build());
            Log.d(TAG, "Notification displayed successfully: " + notId);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error creating notification: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Set large icon from URL or fallback to app icon.
     */
    private static void setLargeIcon(Notification.Builder builder, Resources resources, 
                                     int appIconResId, String imageUrl) {
        // Try to download image from URL if provided
        if (imageUrl != null && !imageUrl.isEmpty() && imageUrl.startsWith("http")) {
            try {
                Bitmap iconBitmap = downloadImage(imageUrl);
                if (iconBitmap != null) {
                    builder.setLargeIcon(iconBitmap);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load image from URL, falling back to app icon");
            }
        }

        // Fall back to app icon
        if (resources != null && appIconResId != 0) {
            try {
                Bitmap iconBitmap = BitmapFactory.decodeResource(resources, appIconResId);
                if (iconBitmap != null) {
                    builder.setLargeIcon(iconBitmap);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load app icon", e);
            }
        }
    }

    /**
     * Download image from URL for notification icon.
     */
    private static Bitmap downloadImage(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            input.close();
            connection.disconnect();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error downloading image: " + e.getMessage());
            return null;
        }
    }
}
