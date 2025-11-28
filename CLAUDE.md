# Capacitor Push Notifications Plugin - Development Guide

## Building the Plugin

After making changes to TypeScript definitions or adding new methods, you MUST rebuild the dist folder:

```bash
npm install
npm run build
```

This compiles TypeScript and runs rollup to generate:
- `dist/plugin.js` - Browser bundle
- `dist/plugin.cjs.js` - CommonJS bundle
- `dist/esm/` - ES modules

**IMPORTANT**: If you add new methods to the Android/iOS native code, you must also:
1. Add the method signature to `dist/esm/definitions.d.ts`
2. Run `npm run build` to regenerate the JS bundles
3. Commit the updated `dist/` folder

## Native Code Locations

- **Android**: `android/src/main/java/com/capacitorjs/plugins/pushnotifications/`
- **iOS**: `ios/Sources/PushNotificationsPlugin/`

## Adding New Plugin Methods

1. Add the method to the native code with `@PluginMethod` (Android) or `@objc func` (iOS)
2. Add the TypeScript definition in `dist/esm/definitions.d.ts`
3. Run `npm run build`
4. Commit all changes including `dist/`

## Testing Changes

The plugin is installed from GitHub in iznik-nuxt3. To test changes:

1. Commit and push changes to this repo
2. In iznik-nuxt3, run `npm update @freegle/capacitor-push-notifications-cap7`
3. Or trigger a CircleCI build which does this automatically

## Key Files

- `MessagingService.java` - Handles FCM messages when app is killed/background
- `NotificationHelper.java` - Creates and displays Android notifications
- `PushNotificationsPlugin.java` - Main plugin class, bridges to JS
- `PushNotificationsHandler.swift` - iOS notification handling
