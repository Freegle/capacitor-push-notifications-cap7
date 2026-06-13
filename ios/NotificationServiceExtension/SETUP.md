# Notification Service Extension — Xcode wiring guide

This directory contains the source for the `NotificationServiceExtension` app
target.  The files here are the single source of truth; an identical copy lives
at `iznik-nuxt3/ios/App/NotificationServiceExtension/` for the PR that wires the
Xcode target.

A Notification Service Extension (NSE) is an **app target**, not a library.
It cannot be distributed inside a CocoaPods pod or a Swift Package — it must be
added to the host app's `.xcodeproj`.

**This is now automated on CircleCI** — no manual Xcode step. The app repo
(`iznik-nuxt3`) wires and signs the NSE entirely in CI:

- `fastlane/add_nse_target.rb` copies this NSE source out of the installed plugin
  package and adds/refreshes the `NotificationServiceExtension` target in
  `App.xcodeproj` (idempotent; runs every build, survives `npx cap sync ios`).
- The `ios beta` lane then `produce`s the App ID and `get_provisioning_profile`s an
  App Store profile for `org.ilovefreegle.iphone.NotificationServiceExtension` using
  the existing App Store Connect API key, signs the NSE target, and adds both targets
  to `gym`'s `provisioningProfiles` export map.

The only prerequisite is the App Store Connect API key (already configured for the
app). It self-gates: until the plugin version bundling this NSE is installed in the
app, the step no-ops and the app builds without the extension (iOS notifications then
fall back to plain text). The manual steps below remain as reference / fallback.

---

## What the NSE does

For `NEW_POSTS` push notifications:
- Sets `content.title` from FCM data field `title`
- Sets `content.subtitle` from FCM data field `summary`
- Builds `content.body` from FCM data field `lines` (JSON array joined with
  newlines) + a `+N more` line when `moreCount > 0`; falls back to `message`
- Downloads the URL in FCM data field `image` and attaches it as a
  `UNNotificationAttachment` (shown as a thumbnail on lock screen / notification
  banner, full image when expanded)

For all other categories the original APNs content passes through unchanged.

---

## Prerequisites

The server (Laravel `IncomingMailService` / Go push sender) MUST include in the
APNs envelope:
```json
{
  "aps": {
    "alert": { "title": "...", "body": "..." },
    "mutable-content": 1,
    "content-available": 1,
    "interruption-level": "passive"
  }
}
```
`mutable-content: 1` is the trigger that causes iOS to wake the NSE before
displaying the notification.  Without it the NSE is never called.

---

## Xcode target wiring (do this once on a Mac)

### 1 — Add the NSE target

Open `iznik-nuxt3/ios/App/App.xcworkspace` in Xcode (the workspace, not the
.xcodeproj).

**File → New → Target…**
- Choose **Notification Service Extension**
- Product Name: `NotificationServiceExtension`
- Team: `GMYU3K9D84`  (matches the rest of the app)
- Bundle Identifier: `org.ilovefreegle.iphone.NotificationServiceExtension`
  (must be a sub-identifier of the main app bundle ID)
- Language: **Swift**
- Click **Finish** — Xcode will ask "Activate scheme?"; choose **Cancel** so
  the main "Freegle" scheme stays selected.

### 2 — Replace the generated source files

Xcode creates stub files.  Delete them (move to Trash) and add the files from
this directory instead:

1. In the Project navigator, right-click the `NotificationServiceExtension`
   group and choose **Add Files to "App"…**
2. Navigate to `NotificationServiceExtension/` and select both files:
   - `NotificationService.swift`
   - `Info.plist`
3. Make sure "Add to target: NotificationServiceExtension" is ticked (NOT "App").
4. **Uncheck** "Copy items if needed" — they are already in the right place.

### 3 — Info.plist settings in the target build settings

Select the `NotificationServiceExtension` target → **Build Settings**:
- `MARKETING_VERSION` — must match the main app (set it to `$(inherited)` if
  you use a shared xcconfig)
- `CURRENT_PROJECT_VERSION` — same
- `PRODUCT_BUNDLE_IDENTIFIER` — `org.ilovefreegle.iphone.NotificationServiceExtension`
- `SWIFT_VERSION` — `5.0` (or the same as the main app)
- `IPHONEOS_DEPLOYMENT_TARGET` — `14.0` (matches the rest of the project)

### 4 — App Groups / Entitlements (optional but recommended)

If you want the NSE to share data with the main app (e.g., a shared cache),
add an App Group entitlement to both targets with the same group ID.  For
Freegle's current use this is not required — the NSE only downloads and attaches
images from the network.

### 5 — Embed the extension in the main app

Select the **Freegle** target (main app) → **General** → **Frameworks,
Libraries, and Embedded Content** → **+** → choose the
`NotificationServiceExtension.appex` product.  Set "Embed Without Signing"
(Xcode will sign it automatically as part of the build).

### 6 — Provisioning profile

Create a new **App ID** in the Apple Developer portal for the NSE bundle
identifier (`org.ilovefreegle.iphone.NotificationServiceExtension`), then create a
provisioning profile for it under the `GMYU3K9D84` team.  Download and add it
to Xcode under **Preferences → Accounts → Download Manual Profiles** or let
automatic signing handle it.

### 7 — Build and test

Run the app on a real device (NSEs do not fire in the iOS Simulator).  Send a
test push with `mutable-content: 1` and `category: "NEW_POSTS"`.  The attached
image should appear on the lock screen notification.

---

## Verification checklist

- [ ] NSE target appears in `App.xcodeproj` with correct bundle ID
- [ ] `NotificationService.swift` is in the NSE target (not the main app target)
- [ ] `Info.plist` has `NSExtensionPointIdentifier = com.apple.usernotifications.service`
- [ ] Main app target embeds `NotificationServiceExtension.appex`
- [ ] Server sends `mutable-content: 1` in APNs envelope for NEW_POSTS
- [ ] Server sends `category: "NEW_POSTS"` in FCM data payload
- [ ] Server sends `image` (https URL) in FCM data payload when a photo exists
- [ ] Server sends `lines` (JSON array string) in FCM data payload
- [ ] Push tested on physical device — image attachment visible on lock screen

---

## Troubleshooting

**NSE not called:** Check that the APNs envelope contains `mutable-content: 1`
(not just the FCM data field).  Use `curl` + APNs direct HTTP/2 or the Firebase
console to send a test push.

**Image not shown:** The NSE has a short time budget (~30 s).  If the image URL
is slow the extension will call the content handler without attachment.  Use a
small (<1 MB) JPEG/PNG.

**Build fails with "module not found":** The NSE is a separate Swift module; it
cannot import Capacitor or Firebase.  It only needs `UserNotifications` (already
imported).

**Extension crashes on launch:** Check the `PRODUCT_MODULE_NAME` in build
settings — `$(PRODUCT_MODULE_NAME).NotificationService` in Info.plist must
match the compiled module name (default: same as the product name without spaces).
