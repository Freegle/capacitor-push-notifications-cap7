/* FREEGLE: Boot receiver to ensure FCM connection after device restart */

package com.capacitorjs.plugins.pushnotifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * Receives BOOT_COMPLETED broadcast after device restart.
 *
 * This ensures that:
 * 1. Firebase Messaging is initialized
 * 2. The FCM token is refreshed if needed
 * 3. Any pending messages can be delivered
 *
 * Without this, users would not receive push notifications after a device
 * restart until they manually open the app.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Device boot completed - initializing FCM");

            // Log to SharedPreferences for debugging
            try {
                SharedPreferences prefs = context.getSharedPreferences("push_debug", Context.MODE_PRIVATE);
                String existing = prefs.getString("log", "");
                String bootLog = "BootReceiver triggered at " + System.currentTimeMillis() + "\n";
                prefs.edit().putString("log", existing + bootLog).apply();
            } catch (Exception e) {
                Log.e(TAG, "Failed to save boot log", e);
            }

            // Initialize Firebase Messaging
            // This ensures the FCM connection is established and the token is valid
            try {
                FirebaseMessaging.getInstance().setAutoInitEnabled(true);

                // Request token to ensure connection is active
                FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "FCM token retrieved after boot");
                        } else {
                            Log.w(TAG, "Failed to get FCM token after boot", task.getException());
                        }
                    });

                Log.d(TAG, "FCM initialized after boot");
            } catch (Exception e) {
                Log.e(TAG, "Error initializing FCM after boot", e);
            }
        }
    }
}
