/* FREEGLE 7.0.2 */

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
import org.json.JSONException;

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

            // Set large icon (profile image / first post photo)
            setLargeIcon(builder, resources, appIconResId, imageUrl);

            // FREEGLE: Apply rich style for NEW_POSTS category (InboxStyle or BigPictureStyle)
            // Falls back to base single-line notification if fields are missing or parsing fails.
            if (PushNotificationsPlugin.CATEGORY_NEW_POSTS.equals(category)) {
                applyNewPostsStyle(builder, msgdata, count, imageUrl);
            }

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
     * Apply rich Android notification style for NEW_POSTS category.
     *
     * count >= 2 -> InboxStyle: one line per entry in "lines", optional "+N more" line,
     *               summary text from "summary", big content title from payload "title".
     *               First post photo (already set as largeIcon) is reused — no second download.
     * count == 1 -> BigPictureStyle: "image" URL downloaded as bigPicture bitmap,
     *               bigLargeIcon(null) so the large icon collapses when expanded.
     *
     * All field reads are null/empty-safe; any parse failure logs a warning and
     * leaves the builder untouched so the collapsed single-line view still works.
     *
     * FUTURE-PROOFING: If new fields are added to NEW_POSTS payloads in future,
     * add them here as optional with null checks following the same defensive pattern.
     */
    static void applyNewPostsStyle(Notification.Builder builder, Map<String, String> msgdata,
                                   int count, String imageUrl) {
        try {
            String title   = msgdata.get("title");
            String summary = msgdata.get("summary");
            String linesJson = msgdata.get("lines");

            int moreCount = 0;
            String moreCountStr = msgdata.get("moreCount");
            if (moreCountStr != null && !moreCountStr.isEmpty()) {
                try {
                    moreCount = Integer.parseInt(moreCountStr);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "NEW_POSTS: invalid moreCount value: " + moreCountStr);
                }
            }

            if (count >= 2) {
                // Prefer a photo collage of the top posts (photo-first design). Falls back to
                // the InboxStyle text list when fewer than two posts have a usable photo.
                Bitmap collage = buildCollage(msgdata.get("images"));
                if (collage != null) {
                    Notification.BigPictureStyle pictureStyle = new Notification.BigPictureStyle()
                        .bigPicture(collage)
                        .bigLargeIcon((Bitmap) null);
                    if (title != null && !title.isEmpty()) {
                        pictureStyle.setBigContentTitle(title);
                    }
                    // Item names go in the expanded summary line beneath the collage.
                    String namesSummary = msgdata.get("message");
                    if (namesSummary == null || namesSummary.isEmpty()) {
                        namesSummary = summary;
                    }
                    if (namesSummary != null && !namesSummary.isEmpty()) {
                        pictureStyle.setSummaryText(namesSummary);
                    }
                    builder.setStyle(pictureStyle);
                    Log.d(TAG, "NEW_POSTS: applied BigPictureStyle collage (count=" + count + ")");
                    return;
                }

                // InboxStyle: each "lines" entry on its own row
                Notification.InboxStyle inboxStyle = new Notification.InboxStyle();

                if (title != null && !title.isEmpty()) {
                    inboxStyle.setBigContentTitle(title);
                }
                if (summary != null && !summary.isEmpty()) {
                    inboxStyle.setSummaryText(summary);
                }

                if (linesJson != null && !linesJson.isEmpty()) {
                    try {
                        JSONArray linesArray = new JSONArray(linesJson);
                        for (int i = 0; i < linesArray.length(); i++) {
                            String line = linesArray.optString(i, null);
                            if (line != null && !line.isEmpty()) {
                                inboxStyle.addLine(line);
                            }
                        }
                    } catch (JSONException e) {
                        Log.w(TAG, "NEW_POSTS: failed to parse lines JSON: " + e.getMessage());
                        // Fall through — style still applied with title/summary only
                    }
                }

                // "+N more" trailing line when there are posts beyond the listed ones
                if (moreCount > 0) {
                    inboxStyle.addLine("+" + moreCount + " more");
                }

                builder.setStyle(inboxStyle);
                Log.d(TAG, "NEW_POSTS: applied InboxStyle (count=" + count + ", moreCount=" + moreCount + ")");

            } else if (count == 1) {
                // BigPictureStyle: show the single item's photo expanded
                if (imageUrl != null && !imageUrl.isEmpty() && imageUrl.startsWith("http")) {
                    Bitmap bigPicture = downloadImage(imageUrl);
                    if (bigPicture != null) {
                        Notification.BigPictureStyle bigPictureStyle = new Notification.BigPictureStyle()
                            .bigPicture(bigPicture)
                            .bigLargeIcon((Bitmap) null); // collapse largeIcon when expanded

                        if (title != null && !title.isEmpty()) {
                            bigPictureStyle.setBigContentTitle(title);
                        }

                        builder.setStyle(bigPictureStyle);
                        Log.d(TAG, "NEW_POSTS: applied BigPictureStyle (count=1)");
                    } else {
                        Log.w(TAG, "NEW_POSTS: BigPicture download returned null, keeping single-line style");
                    }
                } else {
                    Log.d(TAG, "NEW_POSTS: count=1 but no image URL, keeping single-line style");
                }
            }
        } catch (Exception e) {
            // Never crash — base single-line notification is already built
            Log.e(TAG, "NEW_POSTS: unexpected error applying rich style, falling back: " + e.getMessage(), e);
        }
    }

    /**
     * Build a photo collage from the "images" payload field (JSON array of up to ~4 URLs)
     * for the multi-post NEW_POSTS notification, so the expanded view shows several item
     * photos rather than a text-only list.
     *
     * Returns a single tiled bitmap (mosaic laid out in a 2:1 frame), or null when fewer
     * than two photos are available — in which case the caller falls back to InboxStyle text.
     * Up to 4 photos are used; extras are ignored (the "+N more" count already conveys the rest).
     */
    private static Bitmap buildCollage(String imagesJson) {
        if (imagesJson == null || imagesJson.isEmpty()) {
            return null;
        }

        List<Bitmap> bitmaps = new ArrayList<>();
        try {
            JSONArray urls = new JSONArray(imagesJson);
            for (int i = 0; i < urls.length() && bitmaps.size() < 4; i++) {
                String url = urls.optString(i, null);
                if (url != null && url.startsWith("http")) {
                    Bitmap b = downloadImage(url);
                    if (b != null) {
                        bitmaps.add(b);
                    }
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "NEW_POSTS: failed to parse images JSON: " + e.getMessage());
            return null;
        }

        int n = bitmaps.size();
        if (n < 2) {
            return null; // not enough photos for a collage — caller falls back to text list
        }

        final int W = 1024, H = 512, gap = 6;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        if (n == 2) {
            int half = (W - gap) / 2;
            drawCropped(canvas, bitmaps.get(0), new Rect(0, 0, half, H), paint);
            drawCropped(canvas, bitmaps.get(1), new Rect(half + gap, 0, W, H), paint);
        } else if (n == 3) {
            int half = (W - gap) / 2;
            int rowH = (H - gap) / 2;
            drawCropped(canvas, bitmaps.get(0), new Rect(0, 0, half, H), paint);
            drawCropped(canvas, bitmaps.get(1), new Rect(half + gap, 0, W, rowH), paint);
            drawCropped(canvas, bitmaps.get(2), new Rect(half + gap, rowH + gap, W, H), paint);
        } else { // 4
            int colW = (W - gap) / 2;
            int rowH = (H - gap) / 2;
            drawCropped(canvas, bitmaps.get(0), new Rect(0, 0, colW, rowH), paint);
            drawCropped(canvas, bitmaps.get(1), new Rect(colW + gap, 0, W, rowH), paint);
            drawCropped(canvas, bitmaps.get(2), new Rect(0, rowH + gap, colW, H), paint);
            drawCropped(canvas, bitmaps.get(3), new Rect(colW + gap, rowH + gap, W, H), paint);
        }

        return out;
    }

    /**
     * Draw a bitmap into dst, centre-cropped to dst's aspect ratio (fills the cell, no distortion).
     */
    private static void drawCropped(Canvas canvas, Bitmap src, Rect dst, Paint paint) {
        int bw = src.getWidth(), bh = src.getHeight();
        if (bw <= 0 || bh <= 0) {
            return;
        }
        float dstAspect = (float) dst.width() / dst.height();
        float srcAspect = (float) bw / bh;

        int sw, sh;
        if (srcAspect > dstAspect) {
            // source is relatively wider → crop its sides
            sh = bh;
            sw = Math.round(bh * dstAspect);
        } else {
            // source is relatively taller → crop top/bottom
            sw = bw;
            sh = Math.round(bw / dstAspect);
        }
        int sx = (bw - sw) / 2;
        int sy = (bh - sh) / 2;
        Rect srcRect = new Rect(sx, sy, sx + sw, sy + sh);
        canvas.drawBitmap(src, srcRect, dst, paint);
    }

    /**
     * Download image from URL for notification icon.
     * Package-private so it can be reused by helpers in this package (e.g. applyNewPostsStyle).
     */
    static Bitmap downloadImage(String imageUrl) {
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
