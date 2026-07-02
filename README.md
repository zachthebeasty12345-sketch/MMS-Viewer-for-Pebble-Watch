# MMS Viewer for Pebble Time 2 — See Your Text Message Images on Your Watch

Hey everyone! I built a proof-of-concept app that displays MMS images (picture messages) directly on the Pebble Time 2. When someone texts you a photo, it automatically launches on your watch and shows you a dithered version of the image.

## How It Works

Two parts working together:

- **Android companion app** — runs in the background listening for incoming MMS notifications. When one arrives with an image, it grabs the photo, scales it down to fit the Emery's 200×228 screen, applies Floyd-Steinberg dithering to convert it to Pebble's 64-color palette, and streams it to the watch in chunks.

- **Pebble watchapp** — receives the image data, assembles it, and displays it fullscreen with the sender's name at the bottom. The watch vibrates and lights up the backlight when a new image comes through.

The companion app uses a hybrid of PebbleKit2 (for launching the watch app) and old PebbleKit (for data transfer), since neither one handles both tasks on the Core Devices Pebble app.

## Requirements

- Pebble Time 2 (Emery platform)
- Android phone with the Core Devices Pebble app
- Willingness to sideload an APK

## Setup

1. Install the PBW on your watch
2. Sideload the APK on your phone
3. Open MMS Viewer on your phone and tap **Grant Permissions** (allow SMS and Contacts)
4. Tap **Enable Notification Listener** and toggle on MMS Viewer
5. That's it — next time someone texts you a photo, it should pop up on your watch

## ⚠️ Fair Warning

**This is very much a beta / proof of concept.** It works on my setup but I wouldn't call it polished. Some things to know:

- There's a delay between receiving the text and seeing the image (the normal text notification needs to clear on the watch first before the image can come through)
- Image quality is limited by the 64-color palette — photos are recognizable but lo-fi
- It may not work with every messaging app (tested with Google Messages)
- The Android app needs to stay running in the background, which some phones aggressively kill
- The hybrid PebbleKit approach is a bit of a hack and may break with future Core app updates

I built this because I wanted it to exist, not because I'm an expert Android developer. If someone with more experience wants to take this and make it rock-solid, please do! The full source and a detailed project spec are included.

Built by BoxofDanger 🤙
