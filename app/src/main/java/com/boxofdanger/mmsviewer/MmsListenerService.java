package com.boxofdanger.mmsviewer;

import android.app.Notification;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MmsListenerService extends NotificationListenerService {

    private static final String TAG = "MmsListener";

    private static final Set<String> MESSAGING_PACKAGES = new HashSet<>(Arrays.asList(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.sonyericsson.conversations",
        "com.motorola.messaging",
        "org.thoughtcrime.securesms",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.facebook.orca"
    ));

    private PebbleBridge pebbleBridge;
    private long lastMmsTimestamp = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        pebbleBridge = new PebbleBridge(this);
        Log.i(TAG, "MMS Listener service created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (pebbleBridge != null) {
            pebbleBridge.destroy();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (!MESSAGING_PACKAGES.contains(pkg)) return;
        Log.d(TAG, "Messaging notification from: " + pkg);
        new Thread(() -> handleNotification(sbn)).start();
    }

    private void handleNotification(StatusBarNotification sbn) {
        long now = System.currentTimeMillis();
        if (now - lastMmsTimestamp < 5000) {
            Log.d(TAG, "Skipping duplicate notification");
            return;
        }
        lastMmsTimestamp = now;

        Bitmap image = null;
        String sender = "";

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;

        if (extras != null) {
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title != null) sender = title.toString();
            image = extractBigPicture(extras);
        }

        if (image == null) {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            image = fetchLatestMmsImage();
            if (image != null && sender.isEmpty()) {
                sender = fetchLatestMmsSender();
            }
        }

        if (image == null) {
            Log.d(TAG, "No image in notification");
            return;
        }

        Log.i(TAG, "Got image " + image.getWidth() + "x" + image.getHeight()
                + " from " + sender);

        try {
            ImageProcessor.Result processed = ImageProcessor.process(image);
            pebbleBridge.sendImage(processed, sender);
        } catch (Exception e) {
            Log.e(TAG, "Processing failed", e);
        } finally {
            image.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private Bitmap extractBigPicture(Bundle extras) {
        Object picObj = extras.get(Notification.EXTRA_PICTURE);
        if (picObj instanceof Bitmap) {
            Bitmap bmp = (Bitmap) picObj;
            if (bmp.getWidth() > 1 && bmp.getHeight() > 1) return bmp;
        }
        Object iconObj = extras.get(Notification.EXTRA_LARGE_ICON_BIG);
        if (iconObj instanceof Bitmap) {
            Bitmap bmp = (Bitmap) iconObj;
            if (bmp.getWidth() > 64 && bmp.getHeight() > 64) return bmp;
        }
        return null;
    }

    private Bitmap fetchLatestMmsImage() {
        try {
            ContentResolver cr = getContentResolver();
            String mmsId = null;
            try (Cursor cursor = cr.query(Uri.parse("content://mms"),
                    new String[]{"_id"}, null, null, "date DESC LIMIT 1")) {
                if (cursor != null && cursor.moveToFirst()) mmsId = cursor.getString(0);
            }
            if (mmsId == null) return null;
            try (Cursor cursor = cr.query(Uri.parse("content://mms/" + mmsId + "/part"),
                    new String[]{"_id", "ct", "_data"}, null, null, null)) {
                if (cursor == null) return null;
                while (cursor.moveToNext()) {
                    String partId = cursor.getString(0);
                    String mimeType = cursor.getString(1);
                    if (mimeType != null && mimeType.startsWith("image/")) {
                        return loadMmsPartImage(cr, partId);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MMS query failed", e);
        }
        return null;
    }

    private Bitmap loadMmsPartImage(ContentResolver cr, String partId) {
        try (InputStream is = cr.openInputStream(Uri.parse("content://mms/part/" + partId))) {
            if (is != null) return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load MMS part image", e);
        }
        return null;
    }

    private String fetchLatestMmsSender() {
        try {
            ContentResolver cr = getContentResolver();
            String mmsId = null;
            try (Cursor cursor = cr.query(Uri.parse("content://mms"),
                    new String[]{"_id"}, null, null, "date DESC LIMIT 1")) {
                if (cursor != null && cursor.moveToFirst()) mmsId = cursor.getString(0);
            }
            if (mmsId == null) return "";
            try (Cursor cursor = cr.query(Uri.parse("content://mms/" + mmsId + "/addr"),
                    new String[]{"address"}, "type = 137", null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String address = cursor.getString(0);
                    String name = resolveContactName(address);
                    return name != null ? name : address;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get MMS sender", e);
        }
        return "";
    }

    private String resolveContactName(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return null;
        try {
            Uri lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
            try (Cursor cursor = getContentResolver().query(lookupUri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.d(TAG, "Contact lookup failed for " + phoneNumber);
        }
        return null;
    }
}
