import UserNotifications

/// Freegle Notification Service Extension
///
/// Intercepts every FCM push that arrives with `mutable-content = 1` in the
/// APNs envelope.  For NEW_POSTS digests this means:
///   - Attaches the first-post image (data["image"]) as a UNNotificationAttachment
///   - Rebuilds the visible title / subtitle / body from the rich FCM data fields
///     so iOS shows a multiline digest instead of the raw single-line fallback
///
/// For any other notification category the content is passed through unchanged,
/// so CHAT_MESSAGE and other types are unaffected.
///
/// Required server-side APNs envelope keys:
///   aps.alert          (must be present so iOS delivers the notification)
///   aps.mutable-content = 1   (triggers this extension)
///   aps.content-available = 1 (wakes extension in background; NEW_POSTS uses this)
///
/// FCM data fields read here (all strings per FCM contract):
///   category    - "NEW_POSTS" selects the enriched path; others fall through
///   title       - "7 new things near you"
///   summary     - "Freegle • 7 new posts"  (used as subtitle)
///   message     - single-line fallback body
///   lines       - JSON array of ≤5 item strings, e.g. ["Offer: Sofa (Kingston)","Wanted: Bike"]
///   moreCount   - additional items beyond lines, e.g. "2"
///   image       - https:// URL of the first post photo; "" when absent
///
public class NotificationService: UNNotificationServiceExtension {

    // Stored so we can call it from serviceExtensionTimeWillExpire when iOS
    // kills us before the async image download finishes.
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttemptContent: UNMutableNotificationContent?

    // MARK: - UNNotificationServiceExtension

    override public func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler

        guard let mutableContent = (request.content.mutableCopy() as? UNMutableNotificationContent) else {
            // Fallback: deliver whatever APNs gave us
            contentHandler(request.content)
            return
        }

        self.bestAttemptContent = mutableContent

        let userInfo = request.content.userInfo
        let category = userInfo["category"] as? String ?? ""

        if category == "NEW_POSTS" {
            enrichNewPosts(content: mutableContent, userInfo: userInfo) {
                contentHandler(mutableContent)
            }
        } else {
            // Pass through for CHAT_MESSAGE and any future categories
            contentHandler(mutableContent)
        }
    }

    /// Called by iOS when our time budget is nearly exhausted.
    /// We must call contentHandler immediately with whatever we have so far.
    override public func serviceExtensionTimeWillExpire() {
        if let handler = contentHandler, let content = bestAttemptContent {
            handler(content)
        }
    }

    // MARK: - NEW_POSTS enrichment

    private func enrichNewPosts(
        content: UNMutableNotificationContent,
        userInfo: [AnyHashable: Any],
        completion: @escaping () -> Void
    ) {
        // --- Title ---
        // Use the FCM "title" field; fall back to whatever APNs aps.alert.title already set.
        if let title = userInfo["title"] as? String, !title.isEmpty {
            content.title = title
        }

        // --- Subtitle ---
        // "summary" = "Freegle • 7 new posts" — gives the user context under the title.
        if let summary = userInfo["summary"] as? String, !summary.isEmpty {
            content.subtitle = summary
        }

        // --- Body ---
        // Prefer the multiline "lines" array joined by newlines; fall back to "message".
        let body = buildBody(userInfo: userInfo)
        content.body = body

        // --- Category identifier ---
        // Register NEW_POSTS as a passive category (no action buttons) so iOS
        // knows the interruption level and correct lock-screen behaviour.
        content.categoryIdentifier = "NEW_POSTS"

        // --- Image attachment ---
        let imageURLString = userInfo["image"] as? String ?? ""
        if imageURLString.hasPrefix("http://") || imageURLString.hasPrefix("https://"),
           let imageURL = URL(string: imageURLString) {
            downloadAndAttach(imageURL: imageURL, to: content) {
                completion()
            }
        } else {
            completion()
        }
    }

    /// Builds the notification body text.
    /// - If "lines" parses as a JSON array and is non-empty, joins them with newlines.
    ///   When moreCount > 0 appends a "+N more" line.
    /// - Falls back to the "message" field (single-line text).
    private func buildBody(userInfo: [AnyHashable: Any]) -> String {
        if let linesJSON = userInfo["lines"] as? String,
           let data = linesJSON.data(using: .utf8),
           let lines = try? JSONSerialization.jsonObject(with: data) as? [String],
           !lines.isEmpty {

            var bodyLines = lines

            if let moreCountStr = userInfo["moreCount"] as? String,
               let moreCount = Int(moreCountStr),
               moreCount > 0 {
                bodyLines.append("+\(moreCount) more")
            }

            return bodyLines.joined(separator: "\n")
        }

        // Fallback to single-line message
        return (userInfo["message"] as? String) ?? ""
    }

    // MARK: - Image download + attachment

    private func downloadAndAttach(
        imageURL: URL,
        to content: UNMutableNotificationContent,
        completion: @escaping () -> Void
    ) {
        let task = URLSession.shared.downloadTask(with: imageURL) { tempURL, response, error in
            defer { completion() }

            guard error == nil, let tempURL = tempURL else {
                // Image fetch failed — deliver enriched text content without attachment
                return
            }

            do {
                // UNNotificationAttachment requires a file extension it can recognise.
                // Derive it from the Content-Type header when available; fall back to
                // the URL path extension; fall back to .jpg.
                let ext = self.fileExtension(for: response, url: imageURL)
                let destURL = tempURL.deletingPathExtension().appendingPathExtension(ext)

                // Move to a name with the correct extension so UNNotificationAttachment accepts it
                try FileManager.default.moveItem(at: tempURL, to: destURL)

                let attachment = try UNNotificationAttachment(
                    identifier: "new_posts_image",
                    url: destURL,
                    options: nil
                )
                content.attachments = [attachment]
            } catch {
                // Attachment creation failed — deliver without image
                print("FREEGLE NSE: attachment error: \(error)")
            }
        }
        task.resume()
    }

    /// Returns a lowercase file extension (without dot) suitable for UNNotificationAttachment.
    /// Supported types: jpeg/jpg, png, gif, webp.
    private func fileExtension(for response: URLResponse?, url: URL) -> String {
        // 1. Content-Type header
        if let httpResponse = response as? HTTPURLResponse,
           let contentType = httpResponse.value(forHTTPHeaderField: "Content-Type") {
            let lower = contentType.lowercased()
            if lower.contains("jpeg") || lower.contains("jpg") { return "jpg" }
            if lower.contains("png")  { return "png" }
            if lower.contains("gif")  { return "gif" }
            if lower.contains("webp") { return "jpg" } // iOS NSE doesn't support webp; convert by extension trick
        }

        // 2. URL path extension
        let ext = url.pathExtension.lowercased()
        if ["jpg", "jpeg", "png", "gif"].contains(ext) { return ext == "jpeg" ? "jpg" : ext }

        // 3. Safe default
        return "jpg"
    }
}
