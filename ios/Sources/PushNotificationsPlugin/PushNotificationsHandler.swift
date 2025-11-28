import Capacitor
import UserNotifications

public class PushNotificationsHandler: NSObject, NotificationHandlerProtocol {
    public weak var plugin: CAPPlugin?
    var notificationRequestLookup = [String: JSObject]()

    public func requestPermissions(with completion: ((Bool, Error?) -> Void)? = nil) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            completion?(granted, error)
        }
    }

    public func checkPermissions(with completion: ((UNAuthorizationStatus) -> Void)? = nil) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            completion?(settings.authorizationStatus)
        }
    }

    public func willPresent(notification: UNNotification) -> UNNotificationPresentationOptions {
        print("FREEGLE: willPresent called for notification: \(notification.request.identifier)")
        let notificationData = makeNotificationRequestJSObject(notification.request)
        self.plugin?.notifyListeners("pushNotificationReceived", data: notificationData)

        if let options = notificationRequestLookup[notification.request.identifier] {
            let silent = options["silent"] as? Bool ?? false

            if silent {
                print("FREEGLE: Notification is silent, returning empty options")
                return UNNotificationPresentationOptions.init(rawValue: 0)
            }
        }

        if let optionsArray = self.plugin?.getConfig().getArray("presentationOptions") as? [String] {
            print("FREEGLE: Found presentationOptions: \(optionsArray)")
            var presentationOptions = UNNotificationPresentationOptions.init()

            optionsArray.forEach { option in
                switch option {
                case "alert":
                    // iOS 14+ uses .banner and .list instead of .alert
                    if #available(iOS 14.0, *) {
                        presentationOptions.insert(.banner)
                        presentationOptions.insert(.list)
                    } else {
                        presentationOptions.insert(.alert)
                    }
                case "badge":
                    presentationOptions.insert(.badge)

                case "sound":
                    presentationOptions.insert(.sound)
                default:
                    print("Unrecogizned presentation option: \(option)")
                }
            }

            print("FREEGLE: Returning presentationOptions: \(presentationOptions)")
            return presentationOptions
        }

        // Default: show notification with banner, list, badge, and sound if no config
        print("FREEGLE: No presentationOptions config found, using default (banner, list, badge, sound)")
        if #available(iOS 14.0, *) {
            return [.banner, .list, .badge, .sound]
        } else {
            return [.alert, .badge, .sound]
        }
    }

    public func didReceive(response: UNNotificationResponse) {
        var data = JSObject()

        let originalNotificationRequest = response.notification.request
        let actionId = response.actionIdentifier

        if actionId == UNNotificationDefaultActionIdentifier {
            data["actionId"] = "tap"
        } else if actionId == UNNotificationDismissActionIdentifier {
            data["actionId"] = "dismiss"
        } else {
            data["actionId"] = actionId
        }

        if let inputType = response as? UNTextInputNotificationResponse {
            data["inputValue"] = inputType.userText
        }

        data["notification"] = makeNotificationRequestJSObject(originalNotificationRequest)

        self.plugin?.notifyListeners("pushNotificationActionPerformed", data: data, retainUntilConsumed: true)

    }

    func makeNotificationRequestJSObject(_ request: UNNotificationRequest) -> JSObject {
        return [
            "id": request.identifier,
            "title": request.content.title,
            "subtitle": request.content.subtitle,
            "badge": request.content.badge ?? 1,
            "body": request.content.body,
            "data": JSTypes.coerceDictionaryToJSObject(request.content.userInfo) ?? [:]
        ]
    }
}
