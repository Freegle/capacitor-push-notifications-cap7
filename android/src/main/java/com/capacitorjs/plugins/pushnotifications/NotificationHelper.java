/* FREEGLE 7.0.3 */

package com.capacitorjs.plugins.pushnotifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;

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
                String packageName = context.getPackageName();
                ApplicationInfo applicationInfo = context.getPackageManager()
                    .getApplicationInfo(packageName, PackageManager.GET_META_DATA);
                bundle = applicationInfo.metaData;
                resources = context.getPackageManager().getResourcesForApplication(packageName);
                appIconResId = applicationInfo.icon;
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Package not found: " + context.getPackageName());
            }

            // Get notification icon
            int pushIcon = android.R.drawable.ic_dialog_info;
            if (bundle != null && bundle.getInt("com.google.firebase.messaging.default_notification_icon") != 0) {
                pushIcon = bundle.getInt("com.google.firebase.messaging.default_notification_icon");
            }

            // Create intent to open app
            Intent intent;
            try {
                intent = new Intent(context, Class.forName(context.getPackageName() + ".MainActivity"));
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

            // Set large icon (profile image / first photo) - shown in the
            // collapsed notification.
            setLargeIcon(builder, resources, appIconResId, imageUrl);

            // Rich expanded style. The server sends images[] (up to 4 photo
            // URLs) and lines[] (per-post text rows) for digest-style pushes;
            // the previous code only set a small large-icon, so a multi-photo
            // digest showed a single thumbnail. Build a collage / big picture /
            // inbox text list as appropriate. No-op on any error, leaving the
            // plain large-icon notification intact.
            applyRichStyle(builder, title, imageUrl, msgdata);

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
     * Apply a rich expanded notification style based on the payload:
     *   images[] with >=2 URLs    -> a tiled collage via BigPictureStyle
     *   a single photo URL        -> BigPictureStyle of that photo
     *   lines[] (no usable photo) -> InboxStyle text list
     * Leaves the plain large-icon notification untouched on any error or when
     * the payload carries none of these fields, so unknown/legacy payloads
     * still display.
     */
    private static void applyRichStyle(Notification.Builder builder, String title,
                                       String primaryImageUrl, Map<String, String> msgdata) {
        try {
            List<String> imageUrls = parseJsonStringArray(msgdata.get("images"));
            List<String> lines = parseJsonStringArray(msgdata.get("lines"));
            String summary = msgdata.get("summary");
            int moreCount = 0;
            try {
                moreCount = Integer.parseInt(msgdata.get("moreCount"));
            } catch (Exception ignored) {
            }

            // 1. Collage from >=2 photos.
            if (imageUrls.size() >= 2) {
                Bitmap collage = buildCollage(imageUrls);
                if (collage != null) {
                    Notification.BigPictureStyle bp = new Notification.BigPictureStyle()
                        .bigPicture(collage)
                        .setBigContentTitle(title);
                    if (summary != null && !summary.isEmpty()) {
                        bp.setSummaryText(summary);
                    }
                    builder.setStyle(bp);
                    return;
                }
            }

            // 2. Single photo -> BigPicture.
            String single = primaryImageUrl;
            if ((single == null || single.isEmpty()) && !imageUrls.isEmpty()) {
                single = imageUrls.get(0);
            }
            if (single != null && single.startsWith("http")) {
                Bitmap big = downloadImage(single);
                if (big != null) {
                    Notification.BigPictureStyle bp = new Notification.BigPictureStyle()
                        .bigPicture(big)
                        .setBigContentTitle(title);
                    if (summary != null && !summary.isEmpty()) {
                        bp.setSummaryText(summary);
                    }
                    builder.setStyle(bp);
                    return;
                }
            }

            // 3. No photo -> InboxStyle text list.
            if (!lines.isEmpty()) {
                Notification.InboxStyle inbox = new Notification.InboxStyle()
                    .setBigContentTitle(title);
                for (String line : lines) {
                    inbox.addLine(line);
                }
                if (moreCount > 0) {
                    inbox.setSummaryText("+" + moreCount + " more");
                } else if (summary != null && !summary.isEmpty()) {
                    inbox.setSummaryText(summary);
                }
                builder.setStyle(inbox);
            }
        } catch (Exception e) {
            Log.w(TAG, "applyRichStyle failed, leaving plain notification: " + e.getMessage());
        }
    }

    /** Parse a JSON array string of strings into a List; empty on null/blank/error. */
    private static List<String> parseJsonStringArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse JSON array: " + e.getMessage());
        }
        return out;
    }

    /**
     * Build a single landscape collage from up to 4 photo URLs, laid out to
     * fill a 1024x512 BigPicture canvas:
     *   2 photos -> side by side
     *   3 photos -> one tall left, two stacked right
     *   4 photos -> 2x2 grid
     * Returns null if fewer than 2 photos download successfully (caller then
     * falls back to a single big picture).
     */
    private static Bitmap buildCollage(List<String> urls) {
        List<Bitmap> bitmaps = new ArrayList<>();
        for (String u : urls) {
            if (bitmaps.size() >= 4) {
                break;
            }
            if (u == null || !u.startsWith("http")) {
                continue;
            }
            Bitmap b = downloadImage(u);
            if (b != null) {
                bitmaps.add(b);
            }
        }
        if (bitmaps.size() < 2) {
            return null;
        }

        final int w = 1024;
        final int h = 512;
        final int gap = 6;
        Bitmap canvasBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBmp);
        canvas.drawColor(Color.WHITE);

        int cw = (w - gap) / 2;
        int rh = (h - gap) / 2;
        int n = bitmaps.size();
        if (n == 2) {
            drawCenterCrop(canvas, bitmaps.get(0), new Rect(0, 0, cw, h));
            drawCenterCrop(canvas, bitmaps.get(1), new Rect(cw + gap, 0, w, h));
        } else if (n == 3) {
            drawCenterCrop(canvas, bitmaps.get(0), new Rect(0, 0, cw, h));
            drawCenterCrop(canvas, bitmaps.get(1), new Rect(cw + gap, 0, w, rh));
            drawCenterCrop(canvas, bitmaps.get(2), new Rect(cw + gap, rh + gap, w, h));
        } else {
            drawCenterCrop(canvas, bitmaps.get(0), new Rect(0, 0, cw, rh));
            drawCenterCrop(canvas, bitmaps.get(1), new Rect(cw + gap, 0, w, rh));
            drawCenterCrop(canvas, bitmaps.get(2), new Rect(0, rh + gap, cw, h));
            drawCenterCrop(canvas, bitmaps.get(3), new Rect(cw + gap, rh + gap, w, h));
        }
        return canvasBmp;
    }

    /** Draw src into dest scaled to fill with a center-crop (no distortion). */
    private static void drawCenterCrop(Canvas canvas, Bitmap src, Rect dest) {
        if (src == null) {
            return;
        }
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw = dest.width();
        int dh = dest.height();
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
            return;
        }
        float scale = Math.max((float) dw / sw, (float) dh / sh);
        int scaledW = Math.round(sw * scale);
        int scaledH = Math.round(sh * scale);
        int left = dest.left + (dw - scaledW) / 2;
        int top = dest.top + (dh - scaledH) / 2;
        Rect srcRect = new Rect(0, 0, sw, sh);
        Rect dstRect = new Rect(left, top, left + scaledW, top + scaledH);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.save();
        canvas.clipRect(dest);
        canvas.drawBitmap(src, srcRect, dstRect, paint);
        canvas.restore();
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
