/* FREEGLE 7.0.2 */

package com.capacitorjs.plugins.pushnotifications;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.firebase.messaging.CommonNotificationBuilder;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.NotificationParams;
import com.google.firebase.messaging.RemoteMessage;
import java.util.ArrayList; // Freegle
import java.util.Arrays;
import java.util.List; // Freegle
import org.json.JSONException;
import org.json.JSONObject;

import android.app.PendingIntent;  // Freegle..
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.Log;
import androidx.core.content.res.ResourcesCompat;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit; // ..Freegle

@CapacitorPlugin(
    name = "PushNotifications",
    permissions = @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = PushNotificationsPlugin.PUSH_NOTIFICATIONS)
)
public class PushNotificationsPlugin extends Plugin {

    static final String PUSH_NOTIFICATIONS = "receive";

    public static Bridge staticBridge = null;
    public static RemoteMessage lastMessage = null;
    public static JSObject pendingAction = null; // Freegle: Store action when app was not running
    public NotificationManager notificationManager;
    public MessagingService firebaseMessagingService;
    private NotificationChannelManager notificationChannelManager;

    private static final String EVENT_TOKEN_CHANGE = "registration";
    private static final String EVENT_TOKEN_ERROR = "registrationError";

    // Freegle: Category constants for notification actions
    public static final String CATEGORY_CHAT_MESSAGE = "CHAT_MESSAGE";

    public void load() {
        notificationManager = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        firebaseMessagingService = new MessagingService();

        staticBridge = this.bridge;
        if (lastMessage != null) {
            fireNotification(lastMessage, false); // Freegle
            lastMessage = null;
        }

        // Freegle: Process any pending action from when app was not running
        if (pendingAction != null) {
            notifyListeners("pushNotificationActionPerformed", pendingAction, true);
            pendingAction = null;
        }

        notificationChannelManager = new NotificationChannelManager(getActivity(), notificationManager, getConfig());
    }

    @Override
    protected void handleOnNewIntent(Intent data) {
        super.handleOnNewIntent(data);
        Bundle bundle = data.getExtras();
        if (bundle != null && bundle.containsKey("google.message_id")) {
            JSObject notificationJson = new JSObject();
            JSObject dataObject = new JSObject();
            for (String key : bundle.keySet()) {
                if (key.equals("google.message_id")) {
                    notificationJson.put("id", bundle.getString(key));
                } else {
                    String valueStr = bundle.getString(key);
                    dataObject.put(key, valueStr);
                }
            }
            notificationJson.put("data", dataObject);
            JSObject actionJson = new JSObject();
            actionJson.put("actionId", "tap");
            actionJson.put("notification", notificationJson);
            notifyListeners("pushNotificationActionPerformed", actionJson, true);
        }
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            JSObject permissionsResultJSON = new JSObject();
            permissionsResultJSON.put("receive", "granted");
            call.resolve(permissionsResultJSON);
        } else {
            super.checkPermissions(call);
        }
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || getPermissionState(PUSH_NOTIFICATIONS) == PermissionState.GRANTED) {
            JSObject permissionsResultJSON = new JSObject();
            permissionsResultJSON.put("receive", "granted");
            call.resolve(permissionsResultJSON);
        } else {
            requestPermissionForAlias(PUSH_NOTIFICATIONS, call, "permissionsCallback");
        }
    }

    @PluginMethod
    public void register(PluginCall call) {
        FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        FirebaseMessaging
            .getInstance()
            .getToken()
            .addOnCompleteListener(
                task -> {
                    if (!task.isSuccessful()) {
                        sendError(task.getException().getLocalizedMessage());
                        return;
                    }
                    sendToken(task.getResult());
                }
            );
        call.resolve();
    }

    @PluginMethod
    public void unregister(PluginCall call) {
        FirebaseMessaging.getInstance().setAutoInitEnabled(false);
        FirebaseMessaging.getInstance().deleteToken();
        call.resolve();
    }

    @PluginMethod
    public void getDeliveredNotifications(PluginCall call) {
        JSArray notifications = new JSArray();
        StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();

        for (StatusBarNotification notif : activeNotifications) {
            JSObject jsNotif = new JSObject();

            jsNotif.put("id", notif.getId());
            jsNotif.put("tag", notif.getTag());

            Notification notification = notif.getNotification();
            if (notification != null) {
                jsNotif.put("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE));
                jsNotif.put("body", notification.extras.getCharSequence(Notification.EXTRA_TEXT));
                jsNotif.put("group", notification.getGroup());
                jsNotif.put("groupSummary", 0 != (notification.flags & Notification.FLAG_GROUP_SUMMARY));

                JSObject extras = new JSObject();

                for (String key : notification.extras.keySet()) {
                    extras.put(key, notification.extras.getString(key));
                }

                jsNotif.put("data", extras);
            }

            notifications.put(jsNotif);
        }

        JSObject result = new JSObject();
        result.put("notifications", notifications);
        call.resolve(result);
    }

    @PluginMethod
    public void removeDeliveredNotifications(PluginCall call) {
        JSArray notifications = call.getArray("notifications");

        try {
            for (Object o : notifications.toList()) {
                if (o instanceof JSONObject) {
                    JSObject notif = JSObject.fromJSONObject((JSONObject) o);
                    String tag = notif.getString("tag");
                    Integer id = notif.getInteger("id");

                    if (tag == null) {
                        notificationManager.cancel(id);
                    } else {
                        notificationManager.cancel(tag, id);
                    }
                } else {
                    call.reject("Expected notifications to be a list of notification objects");
                }
            }
        } catch (JSONException e) {
            call.reject(e.getMessage());
        }

        call.resolve();
    }

    @PluginMethod
    public void removeAllDeliveredNotifications(PluginCall call) {
        notificationManager.cancelAll();
        call.resolve();
    }

    @PluginMethod
    public void createChannel(PluginCall call) {
        notificationChannelManager.createChannel(call);
    }

    @PluginMethod
    public void deleteChannel(PluginCall call) {
        notificationChannelManager.deleteChannel(call);
    }

    @PluginMethod
    public void listChannels(PluginCall call) {
        notificationChannelManager.listChannels(call);
    }

    public void sendToken(String token) {
        JSObject data = new JSObject();
        data.put("value", token);
        notifyListeners(EVENT_TOKEN_CHANGE, data, true);
    }

    public void sendError(String error) {
        JSObject data = new JSObject();
        data.put("error", error);
        notifyListeners(EVENT_TOKEN_ERROR, data, true);
    }

    // Freegle: Public method to send action performed events from NotificationActionReceiver
    public void sendActionPerformed(JSObject actionJson) {
        notifyListeners("pushNotificationActionPerformed", actionJson, true);
    }

    public static void onNewToken(String newToken) {
        PushNotificationsPlugin pushPlugin = PushNotificationsPlugin.getPushNotificationsInstance();
        if (pushPlugin != null) {
            pushPlugin.sendToken(newToken);
        }
    }

    public static boolean sendRemoteMessage(RemoteMessage remoteMessage) { // Freegle
        PushNotificationsPlugin pushPlugin = PushNotificationsPlugin.getPushNotificationsInstance();
        if (pushPlugin != null) {
            pushPlugin.fireNotification(remoteMessage, true); // Freegle
            return true;  // Foreground or Background // Freegle
        } else {
            lastMessage = remoteMessage;
            return false; // Not running // Freegle
        }
    }

    public void fireNotification(RemoteMessage remoteMessage, Boolean foreground) { // Freegle
        JSObject remoteMessageData = new JSObject();

        JSObject data = new JSObject();
        remoteMessageData.put("id", remoteMessage.getMessageId());
        for (String key : remoteMessage.getData().keySet()) {
            Object value = remoteMessage.getData().get(key);
            data.put(key, value);
        }
        data.put("foreground", foreground); // Freegle
        remoteMessageData.put("data", data);

        // Freegle..
        // Handle data notification
        Map<String, String> msgdata = remoteMessage.getData();
        if (msgdata != null) {
          try{
            String title = msgdata.get("title").toString();
            String message = msgdata.get("message").toString();
            int count = Integer.parseInt(msgdata.get("count").toString());
            int res = Integer.parseInt(msgdata.get("notId").toString());
            if( count==0){
              notificationManager.cancelAll();
            } else {
              Bundle bundle = null;
              Resources r = null;
              String className = getContext().getPackageName();
              int appIconResId = 0;
              try {
                  ApplicationInfo applicationInfo = getContext()
                      .getPackageManager()
                      .getApplicationInfo(className, PackageManager.GET_META_DATA);
                  bundle = applicationInfo.metaData;
                  r = getContext().getPackageManager().getResourcesForApplication(className);
                  appIconResId = applicationInfo.icon;
              } catch (PackageManager.NameNotFoundException e) {
              }
              int pushIcon = android.R.drawable.ic_dialog_info;
              if (bundle != null && bundle.getInt("com.google.firebase.messaging.default_notification_icon") != 0) {
                  pushIcon = bundle.getInt("com.google.firebase.messaging.default_notification_icon");
              }

              Intent intent = new Intent(getContext(), Class.forName(className+".MainActivity"));
              intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
              PendingIntent pendingIntent = PendingIntent.getActivity(getContext(), res, intent, PendingIntent.FLAG_IMMUTABLE);

              // Get channel_id, category, image, and timestamp from payload
              String channelId = msgdata.get("channel_id");
              if (channelId == null || channelId.isEmpty()) {
                  channelId = NotificationChannelManager.FOREGROUND_NOTIFICATION_CHANNEL_ID;
              }
              String category = msgdata.get("category");
              String imageUrl = msgdata.get("image");
              String timestampStr = msgdata.get("timestamp");

              Notification.Builder builder = new Notification.Builder(
                  getContext(),
                  channelId
              )
                  .setSmallIcon(pushIcon)
                  .setContentTitle(title)
                  .setContentText(message)
                  .setPriority(Notification.PRIORITY_DEFAULT)
                  .setColor(Color.GREEN)
                  .setContentIntent(pendingIntent);

              // Set timestamp if available
              if (timestampStr != null && !timestampStr.isEmpty()) {
                  try {
                      long timestamp = Long.parseLong(timestampStr);
                      builder.setWhen(timestamp * 1000);  // Convert to milliseconds
                      builder.setShowWhen(true);
                      // Add relative time as subtext
                      String relativeTime = formatRelativeTime(timestamp);
                      if (!relativeTime.isEmpty()) {
                          builder.setSubText(relativeTime);
                      }
                  } catch (NumberFormatException e) {
                      Log.w("PushNotifications", "Invalid timestamp: " + timestampStr);
                  }
              }

              setLargeIcon(builder,r,appIconResId,imageUrl);

              // Add action buttons based on category
              addNotificationActions(getContext(), builder, category, msgdata, res);

              notificationManager.notify(res, builder.build());
            }
          }
          catch(Exception e) {
            Log.e("PushNotifications", "fireNotification exception "+e.getMessage());
          }
        }
        // ..Freegle

        RemoteMessage.Notification notification = remoteMessage.getNotification();
        if (notification != null) {
            String title = notification.getTitle();
            String body = notification.getBody();
            String[] presentation = getConfig().getArray("presentationOptions");
            if (presentation != null) {
                if (Arrays.asList(presentation).contains("alert")) {
                    Bundle bundle = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            ApplicationInfo applicationInfo = getContext()
                                .getPackageManager()
                                .getApplicationInfo(
                                    getContext().getPackageName(),
                                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA)
                                );
                            bundle = applicationInfo.metaData;
                        } catch (PackageManager.NameNotFoundException e) {
                            e.printStackTrace();
                        }
                    } else {
                        bundle = getBundleLegacy();
                    }

                    if (bundle != null) {
                        NotificationParams params = new NotificationParams(remoteMessage.toIntent().getExtras());

                        String channelId = CommonNotificationBuilder.getOrCreateChannel(
                            getContext(),
                            params.getNotificationChannelId(),
                            bundle
                        );

                        CommonNotificationBuilder.DisplayNotificationInfo notificationInfo = CommonNotificationBuilder.createNotificationInfo(
                            getContext(),
                            getContext(),
                            params,
                            channelId,
                            bundle
                        );

                        notificationManager.notify(notificationInfo.tag, notificationInfo.id, notificationInfo.notificationBuilder.build());
                    }
                }
            }
            remoteMessageData.put("title", title);
            remoteMessageData.put("body", body);
            remoteMessageData.put("click_action", notification.getClickAction());

            Uri link = notification.getLink();
            if (link != null) {
                remoteMessageData.put("link", link.toString());
            }
        }

        notifyListeners("pushNotificationReceived", remoteMessageData, true);
    }

    public static PushNotificationsPlugin getPushNotificationsInstance() {
        if (staticBridge != null && staticBridge.getWebView() != null) {
            PluginHandle handle = staticBridge.getPlugin("PushNotifications");
            if (handle == null) {
                return null;
            }
            return (PushNotificationsPlugin) handle.getInstance();
        }
        return null;
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        this.checkPermissions(call);
    }

    @SuppressWarnings("deprecation")
    private Bundle getBundleLegacy() {
        try {
            ApplicationInfo applicationInfo = getContext()
                .getPackageManager()
                .getApplicationInfo(getContext().getPackageName(), PackageManager.GET_META_DATA);
            return applicationInfo.metaData;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    // Freegle..
    /**
     * Format timestamp as relative time ("3 minutes ago").
     */
    public static String formatRelativeTime(long timestamp) {
        try {
            long now = System.currentTimeMillis() / 1000;  // Current time in seconds
            long diff = now - timestamp;  // Difference in seconds

            if (diff < 60) {
                return "just now";
            } else if (diff < 3600) {
                long minutes = diff / 60;
                return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
            } else if (diff < 86400) {
                long hours = diff / 3600;
                return hours + (hours == 1 ? " hour ago" : " hours ago");
            } else if (diff < 604800) {
                long days = diff / 86400;
                return days + (days == 1 ? " day ago" : " days ago");
            } else {
                long weeks = diff / 604800;
                return weeks + (weeks == 1 ? " week ago" : " weeks ago");
            }
        } catch (Exception e) {
            Log.e("PushNotifications", "Error formatting timestamp: " + e.getMessage());
            return "";
        }
    }

    /**
     * Download a bitmap from a URL synchronously.
     * This should be called from a background thread.
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
            Log.e("PushNotifications", "Error downloading image: " + e.getMessage());
            return null;
        }
    }

    /**
     * Set large icon for notification, trying image URL first, then falling back to app icon.
     */
    public static void setLargeIcon(Notification.Builder builder, Resources r, int appIconResId, String imageUrl) {
        Bitmap iconBitmap = null;

        // Try to download image from URL if provided
        if (imageUrl != null && !imageUrl.isEmpty() && imageUrl.startsWith("http")) {
            try {
                iconBitmap = downloadImage(imageUrl);
                if (iconBitmap != null) {
                    builder.setLargeIcon(iconBitmap);
                    return; // Success, no need to try app icon
                }
            } catch (Exception e) {
                Log.w("PushNotifications", "Failed to load image from URL, falling back to app icon: " + e.getMessage());
            }
        }

        // Fall back to app icon
        if (r != null && appIconResId != 0){
          Drawable d = ResourcesCompat.getDrawable(r, appIconResId, null);
          if( d != null) {
            final Bitmap bmp = Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            d.draw(canvas);
            builder.setLargeIcon(bmp);
          }
        }
    }

    /**
     * Add action buttons to a notification based on the category.
     * For CHAT_MESSAGE category, adds Reply (with text input) and Mark Read buttons.
     */
    public static void addNotificationActions(Context context, Notification.Builder builder,
            String category, Map<String, String> msgdata, int notificationId) {
        if (category == null || !CATEGORY_CHAT_MESSAGE.equals(category)) {
            return; // Only add actions for chat messages
        }

        try {
            // Create bundle with notification data for the actions
            Bundle notificationDataBundle = new Bundle();
            if (msgdata != null) {
                for (Map.Entry<String, String> entry : msgdata.entrySet()) {
                    notificationDataBundle.putString(entry.getKey(), entry.getValue());
                }
            }

            // Reply action with RemoteInput for text
            RemoteInput remoteInput = new RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
                    .setLabel("Reply")
                    .build();

            Intent replyIntent = new Intent(context, NotificationActionReceiver.class);
            replyIntent.setAction(NotificationActionReceiver.ACTION_REPLY);
            replyIntent.putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_DATA, notificationDataBundle);
            replyIntent.putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId);

            PendingIntent replyPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notificationId * 10 + 1, // Unique request code
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );

            Notification.Action replyAction = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    "Reply",
                    replyPendingIntent
            )
                    .addRemoteInput(remoteInput)
                    .build();

            builder.addAction(replyAction);

            // Mark Read action
            Intent markReadIntent = new Intent(context, NotificationActionReceiver.class);
            markReadIntent.setAction(NotificationActionReceiver.ACTION_MARK_READ);
            markReadIntent.putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_DATA, notificationDataBundle);
            markReadIntent.putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId);

            PendingIntent markReadPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notificationId * 10 + 2, // Unique request code
                    markReadIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification.Action markReadAction = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_view,
                    "Mark Read",
                    markReadPendingIntent
            )
                    .build();

            builder.addAction(markReadAction);

            Log.d("PushNotifications", "Added Reply and Mark Read actions for category: " + category);
        } catch (Exception e) {
            Log.e("PushNotifications", "Error adding notification actions: " + e.getMessage());
        }
    }
    // ..Freegle
}
