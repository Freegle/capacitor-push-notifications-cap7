import UserNotifications
import UIKit

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
///   images      - JSON array of up to 4 post photo URLs; tiled into a collage when >=2
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
        // Intentionally NOT set from "summary". "summary" is "Freegle • N new
        // posts", which duplicates the count already in the title ("N new
        // freegles near you") AND the "FREEGLE" app name iOS prints in the
        // notification header — so a subtitle just repeats it. Leaving it empty
        // gives title + item lines (body) with no redundancy. ("summary" is
        // still used by Android's InboxStyle subtext, where it isn't redundant.)

        // --- Body ---
        // Prefer the multiline "lines" array joined by newlines; fall back to "message".
        let body = buildBody(userInfo: userInfo)
        content.body = body

        // --- Category identifier ---
        // Register NEW_POSTS as a passive category (no action buttons) so iOS
        // knows the interruption level and correct lock-screen behaviour.
        content.categoryIdentifier = "NEW_POSTS"

        // --- Image attachment ---
        // Prefer a collage of the top posts' photos ("images" JSON array); when fewer than
        // two photos are available, fall back to the single "image" thumbnail.
        let collageURLs = parseImageURLs(userInfo["images"])
        if collageURLs.count >= 2 {
            downloadAndAttachCollage(urls: Array(collageURLs.prefix(4)), to: content, completion: completion)
            return
        }

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

    // MARK: - Multi-photo collage

    /// Parse the "images" FCM field (JSON array of http(s) URL strings) into URLs.
    private func parseImageURLs(_ raw: Any?) -> [URL] {
        guard let s = raw as? String,
              let data = s.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [String] else {
            return []
        }
        return arr.compactMap { str -> URL? in
            guard str.hasPrefix("http://") || str.hasPrefix("https://") else { return nil }
            return URL(string: str)
        }
    }

    /// Download up to 4 photos, tile them into one collage image, and attach it.
    /// Needs at least two successful downloads; otherwise delivers the (already enriched)
    /// text content with no attachment.
    private func downloadAndAttachCollage(
        urls: [URL],
        to content: UNMutableNotificationContent,
        completion: @escaping () -> Void
    ) {
        let group = DispatchGroup()
        var images = [Int: UIImage]()
        let lock = NSLock()

        for (idx, url) in urls.enumerated() {
            group.enter()
            URLSession.shared.dataTask(with: url) { data, _, _ in
                defer { group.leave() }
                if let data = data, let img = UIImage(data: data) {
                    lock.lock(); images[idx] = img; lock.unlock()
                }
            }.resume()
        }

        group.notify(queue: .main) {
            let ordered = urls.indices.compactMap { images[$0] }
            guard ordered.count >= 2 else { completion(); return }

            let collage = self.composeCollage(ordered, size: CGSize(width: 1024, height: 512))
            guard let jpeg = collage.jpegData(compressionQuality: 0.85) else { completion(); return }

            let tmp = FileManager.default.temporaryDirectory
                .appendingPathComponent("new_posts_collage_\(UUID().uuidString).jpg")
            do {
                try jpeg.write(to: tmp)
                let attachment = try UNNotificationAttachment(identifier: "new_posts_collage", url: tmp, options: nil)
                content.attachments = [attachment]
            } catch {
                print("FREEGLE NSE: collage attach error: \(error)")
            }
            completion()
        }
    }

    /// Tile 2–4 images into a 2:1 mosaic (mirrors the Android collage layout):
    /// 2 → side by side, 3 → one large + two stacked, 4 → 2×2. Each cell is aspect-fill cropped.
    private func composeCollage(_ images: [UIImage], size: CGSize) -> UIImage {
        let gap: CGFloat = 6
        let W = size.width, H = size.height
        let half = (W - gap) / 2
        let rowH = (H - gap) / 2
        let n = min(images.count, 4)

        var rects: [CGRect] = []
        switch n {
        case 2:
            rects = [CGRect(x: 0, y: 0, width: half, height: H),
                     CGRect(x: half + gap, y: 0, width: W - half - gap, height: H)]
        case 3:
            rects = [CGRect(x: 0, y: 0, width: half, height: H),
                     CGRect(x: half + gap, y: 0, width: W - half - gap, height: rowH),
                     CGRect(x: half + gap, y: rowH + gap, width: W - half - gap, height: H - rowH - gap)]
        default:
            rects = [CGRect(x: 0, y: 0, width: half, height: rowH),
                     CGRect(x: half + gap, y: 0, width: W - half - gap, height: rowH),
                     CGRect(x: 0, y: rowH + gap, width: half, height: H - rowH - gap),
                     CGRect(x: half + gap, y: rowH + gap, width: W - half - gap, height: H - rowH - gap)]
        }

        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { rendererContext in
            let ctx = rendererContext.cgContext
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            for (i, rect) in rects.enumerated() {
                self.drawAspectFill(images[i], in: rect, context: ctx)
            }
        }
    }

    /// Draw an image filling `rect` (centre-cropped, no distortion), clipped to the cell.
    private func drawAspectFill(_ image: UIImage, in rect: CGRect, context: CGContext) {
        let iw = image.size.width, ih = image.size.height
        guard iw > 0, ih > 0 else { return }
        context.saveGState()
        context.addRect(rect)
        context.clip()
        let scale = max(rect.width / iw, rect.height / ih)
        let dw = iw * scale, dh = ih * scale
        let drawRect = CGRect(x: rect.midX - dw / 2, y: rect.midY - dh / 2, width: dw, height: dh)
        image.draw(in: drawRect)
        context.restoreGState()
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
