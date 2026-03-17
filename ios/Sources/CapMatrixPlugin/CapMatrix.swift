import Foundation
import MatrixRustSDK

struct MatrixSessionInfo {
    let accessToken: String
    let userId: String
    let deviceId: String
    let homeserverUrl: String

    func toDictionary() -> [String: String] {
        return [
            "accessToken": accessToken,
            "userId": userId,
            "deviceId": deviceId,
            "homeserverUrl": homeserverUrl
        ]
    }
}

class MatrixSDKBridge {

    private var client: Client?
    private var syncService: SyncService?
    private let sessionStore = MatrixKeychainStore()
    private var subscribedRoomIds = Set<String>()
    private var roomTimelines: [String: Timeline] = [:]
    // Keep strong references so GC doesn't cancel subscriptions
    private var timelineListenerHandles: [Any] = []
    private var syncStateHandle: TaskHandle?
    private var syncStateObserver: SyncStateObserverProxy?
    private let subscriptionLock = NSLock()

    // MARK: - Auth

    func login(homeserverUrl: String, userId: String, password: String) async throws -> [String: String] {
        do {
            return try await _login(homeserverUrl: homeserverUrl, userId: userId, password: password)
        } catch {
            if "\(error)".contains("account in the store") {
                print("[CapMatrix] Crypto store mismatch — clearing data and retrying login")
                clearAllData()
                return try await _login(homeserverUrl: homeserverUrl, userId: userId, password: password)
            }
            throw error
        }
    }

    private func _login(homeserverUrl: String, userId: String, password: String) async throws -> [String: String] {
        let dataDir = Self.dataDirectory()

        let newClient = try await ClientBuilder()
            .homeserverUrl(url: homeserverUrl)
            .sessionPaths(dataPath: dataDir, cachePath: dataDir)
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .autoEnableCrossSigning(autoEnableCrossSigning: true)
            .build()

        try await newClient.login(
            username: userId,
            password: password,
            initialDeviceName: "Capacitor Matrix Plugin",
            deviceId: nil
        )

        client = newClient
        let session = try newClient.session()
        let info = MatrixSessionInfo(
            accessToken: session.accessToken,
            userId: session.userId,
            deviceId: session.deviceId,
            homeserverUrl: homeserverUrl
        )
        sessionStore.save(session: info)
        return info.toDictionary()
    }

    func loginWithToken(homeserverUrl: String, accessToken: String, userId: String, deviceId: String) async throws -> [String: String] {
        do {
            return try await _loginWithToken(homeserverUrl: homeserverUrl, accessToken: accessToken, userId: userId, deviceId: deviceId)
        } catch {
            // If crypto store has mismatched account, wipe and retry
            if "\(error)".contains("account in the store") {
                print("[CapMatrix] Crypto store mismatch — clearing data and retrying login")
                clearAllData()
                return try await _loginWithToken(homeserverUrl: homeserverUrl, accessToken: accessToken, userId: userId, deviceId: deviceId)
            }
            throw error
        }
    }

    private func _loginWithToken(homeserverUrl: String, accessToken: String, userId: String, deviceId: String) async throws -> [String: String] {
        let dataDir = Self.dataDirectory()

        let newClient = try await ClientBuilder()
            .homeserverUrl(url: homeserverUrl)
            .sessionPaths(dataPath: dataDir, cachePath: dataDir)
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .autoEnableCrossSigning(autoEnableCrossSigning: true)
            .build()

        let session = Session(
            accessToken: accessToken,
            refreshToken: nil,
            userId: userId,
            deviceId: deviceId,
            homeserverUrl: homeserverUrl,
            oidcData: nil,
            slidingSyncVersion: .native
        )

        try await newClient.restoreSession(session: session)
        client = newClient

        let info = MatrixSessionInfo(
            accessToken: accessToken,
            userId: userId,
            deviceId: deviceId,
            homeserverUrl: homeserverUrl
        )
        sessionStore.save(session: info)
        return info.toDictionary()
    }

    func logout() async throws {
        try await syncService?.stop()
        syncService = nil
        syncStateHandle = nil
        timelineListenerHandles.removeAll()
        roomTimelines.removeAll()
        subscribedRoomIds.removeAll()
        try await client?.logout()
        client = nil
        sessionStore.clear()
    }

    func clearAllData() {
        syncService = nil
        syncStateHandle = nil
        client = nil
        timelineListenerHandles.removeAll()
        roomTimelines.removeAll()
        subscribedRoomIds.removeAll()
        sessionStore.clear()
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("matrix_sdk")
        try? FileManager.default.removeItem(at: dir)
    }

    func getSession() -> [String: String]? {
        return sessionStore.load()?.toDictionary()
    }

    // MARK: - Sync

    func startSync(
        onSyncState: @escaping (String) -> Void,
        onMessage: @escaping ([String: Any]) -> Void,
        onRoomUpdate: @escaping (String, [String: Any]) -> Void
    ) async throws {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }

        // Enable Rust SDK tracing to diagnose sync errors
        let tracingConfig = TracingConfiguration(
            filter: "warn,matrix_sdk=debug,matrix_sdk_ui=debug",
            writeToStdoutOrSystem: true,
            writeToFiles: nil
        )
        setupTracing(config: tracingConfig)

        print("[CapMatrix] startSync: building sync service...")
        let service = try await c.syncService().finish()
        syncService = service
        print("[CapMatrix] startSync: sync service built")

        let observer = SyncStateObserverProxy(onUpdate: { [weak self] state in
            let mapped = Self.mapSyncState(state)
            print("[CapMatrix] SyncState changed: \(state) -> \(mapped)")
            onSyncState(mapped)
            if mapped == "SYNCING" {
                Task { [weak self] in
                    await self?.subscribeToRoomTimelines(onMessage: onMessage, onRoomUpdate: onRoomUpdate)
                }
            }
        })
        syncStateObserver = observer
        syncStateHandle = service.state(listener: observer)

        // Start sync in a detached task (matches Android's service.start() which blocks)
        Task.detached { [weak service] in
            print("[CapMatrix] startSync: calling service.start()...")
            await service?.start()
            print("[CapMatrix] startSync: service.start() returned")
        }
    }

    private func subscribeToRoomTimelines(
        onMessage: @escaping ([String: Any]) -> Void,
        onRoomUpdate: @escaping (String, [String: Any]) -> Void
    ) async {
        guard let c = client else { return }
        let rooms = c.rooms()

        var roomsToSubscribe: [(Room, String)] = []
        subscriptionLock.lock()
        let alreadyCount = subscribedRoomIds.count
        for room in rooms {
            let roomId = room.id()
            if subscribedRoomIds.contains(roomId) { continue }
            subscribedRoomIds.insert(roomId)
            roomsToSubscribe.append((room, roomId))
        }
        subscriptionLock.unlock()

        print("[CapMatrix] subscribeToRoomTimelines: \(alreadyCount) already subscribed, \(roomsToSubscribe.count) new")
        if roomsToSubscribe.isEmpty { return }

        for (room, roomId) in roomsToSubscribe {
            do {
                let timeline = try await getOrCreateTimeline(room: room)
                let listener = LiveTimelineListener(roomId: roomId, onMessage: onMessage, onRoomUpdate: onRoomUpdate)
                let handle = await timeline.addListener(listener: listener)
                subscriptionLock.lock()
                timelineListenerHandles.append(handle)
                subscriptionLock.unlock()
                print("[CapMatrix]   room \(roomId): listener added ✓")
            } catch {
                print("[CapMatrix]   room \(roomId): FAILED: \(error)")
            }
        }
    }

    func stopSync() async throws {
        try await syncService?.stop()
        syncStateHandle = nil
        subscribedRoomIds.removeAll()
        timelineListenerHandles.removeAll()
        roomTimelines.removeAll()
    }

    func getSyncState() -> String {
        return "SYNCING"
    }

    // MARK: - Room Lookup

    private func requireRoom(roomId: String) throws -> Room {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        guard let room = c.rooms().first(where: { $0.id() == roomId }) else {
            throw MatrixBridgeError.roomNotFound(roomId)
        }
        return room
    }

    private func getOrCreateTimeline(room: Room) async throws -> Timeline {
        let roomId = room.id()
        if let existing = roomTimelines[roomId] {
            return existing
        }
        let timeline = try await room.timeline()
        roomTimelines[roomId] = timeline
        return timeline
    }

    // MARK: - Rooms

    func getRooms() async throws -> [[String: Any]] {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        var result: [[String: Any]] = []
        for room in c.rooms() {
            result.append(try await Self.serializeRoom(room))
        }
        return result
    }

    func getRoomMembers(roomId: String) async throws -> [[String: Any]] {
        let room = try requireRoom(roomId: roomId)
        let iterator = try await room.members()
        var result: [[String: Any]] = []
        let total = iterator.len()
        while let chunk = iterator.nextChunk(chunkSize: min(total, 100)) {
            for member in chunk {
                result.append([
                    "userId": member.userId,
                    "displayName": member.displayName as Any,
                    "membership": String(describing: member.membership).lowercased()
                ])
            }
        }
        return result
    }

    func joinRoom(roomIdOrAlias: String) async throws -> String {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        let room = try await c.joinRoomByIdOrAlias(roomIdOrAlias: roomIdOrAlias, serverNames: [])
        return room.id()
    }

    func leaveRoom(roomId: String) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.leave()
    }

    func createRoom(name: String?, topic: String?, isEncrypted: Bool, invite: [String]?) async throws -> String {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        let params = CreateRoomParameters(
            name: name,
            topic: topic,
            isEncrypted: isEncrypted,
            isDirect: false,
            visibility: .private,
            preset: .privateChat,
            invite: invite
        )
        return try await c.createRoom(request: params)
    }

    // MARK: - Messaging

    func sendMessage(roomId: String, body: String, msgtype: String) async throws -> String {
        let room = try requireRoom(roomId: roomId)
        let timeline = try await getOrCreateTimeline(room: room)
        let content = messageEventContentFromMarkdown(md: body)
        try await timeline.send(msg: content)
        return ""
    }

    func getRoomMessages(roomId: String, limit: Int, from: String?) async throws -> [String: Any] {
        let room = try requireRoom(roomId: roomId)
        let timeline = try await getOrCreateTimeline(room: room)

        let collector = TimelineItemCollector(roomId: roomId)
        let handle = await timeline.addListener(listener: collector)

        // Wait for the initial Reset snapshot before paginating
        await collector.waitForUpdate()

        // Paginate backwards — returns true if we hit the start of the timeline
        let hitStart = try await timeline.paginateBackwards(numEvents: UInt16(limit))

        // If there were new events, wait for the diffs to arrive via the listener
        if !hitStart {
            await collector.waitForUpdate()
        }

        handle.cancel()

        var events = Array(collector.events.suffix(limit))

        // Apply receipt watermark: if any own event has readBy data,
        // all earlier own events in the timeline are also read.
        // The SDK only attaches receipts to the specific event they target,
        // but in Matrix a read receipt implies all prior events are read too.
        let myUserId = try? client?.userId()
        var watermarkReadBy: [String]? = nil
        // Walk backwards (newest first) to find the watermark
        for i in stride(from: events.count - 1, through: 0, by: -1) {
            let evt = events[i]
            let sender = evt["senderId"] as? String
            if sender == myUserId {
                if let rb = evt["readBy"] as? [String], !rb.isEmpty {
                    // Found the watermark — this and all earlier own events are "read"
                    watermarkReadBy = rb
                    break
                }
            }
        }
        // Apply watermark to all earlier own events that don't already have readBy
        if let watermark = watermarkReadBy {
            for i in 0..<events.count {
                let sender = events[i]["senderId"] as? String
                if sender == myUserId {
                    let existing = events[i]["readBy"] as? [String]
                    if existing == nil || existing!.isEmpty {
                        events[i]["status"] = "read"
                        events[i]["readBy"] = watermark
                    }
                }
            }
        }

        return [
            "events": events,
            "nextBatch": nil as String? as Any
        ]
    }

    func markRoomAsRead(roomId: String, eventId: String) async throws {
        let room = try requireRoom(roomId: roomId)
        let timeline = try await getOrCreateTimeline(room: room)
        print("[CapMatrix] markRoomAsRead: roomId=\(roomId) eventId=\(eventId)")
        try await timeline.markAsRead(receiptType: ReceiptType.read)
        print("[CapMatrix] markRoomAsRead: done")
    }

    /// Re-fetch timeline items by event ID and return them with updated receipt status.
    /// Uses the receipt watermark from the SDK's timeline data.
    func refreshEventStatuses(roomId: String, eventIds: [String]) async throws -> [[String: Any]] {
        let room = try requireRoom(roomId: roomId)
        let timeline = try await getOrCreateTimeline(room: room)
        let myUserId = try? client?.userId()

        // First pass: collect all events and find the receipt watermark
        var items: [(id: String, item: EventTimelineItem, serialized: [String: Any])] = []
        var watermarkReadBy: [String]? = nil

        for eid in eventIds {
            do {
                let eventItem = try await timeline.getEventTimelineItemByEventId(eventId: eid)
                if let serialized = serializeEventTimelineItem(eventItem, roomId: roomId) {
                    items.append((id: eid, item: eventItem, serialized: serialized))
                    // Check for receipt watermark on own messages
                    if eventItem.sender() == myUserId,
                       let rb = serialized["readBy"] as? [String], !rb.isEmpty {
                        watermarkReadBy = rb
                    }
                }
            } catch {
                // skip
            }
        }

        // Second pass: apply watermark to all own events
        var results: [[String: Any]] = []
        if let watermark = watermarkReadBy {
            for var entry in items {
                if entry.serialized["senderId"] as? String == myUserId {
                    let existing = entry.serialized["readBy"] as? [String]
                    if existing == nil || existing!.isEmpty {
                        entry.serialized["status"] = "read"
                        entry.serialized["readBy"] = watermark
                    }
                }
                results.append(entry.serialized)
            }
        } else {
            results = items.map { $0.serialized }
        }
        return results
    }

    // MARK: - Redactions & Reactions

    func redactEvent(roomId: String, eventId: String, reason: String?) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.redact(eventId: eventId, reason: reason)
    }

    func sendReaction(roomId: String, eventId: String, key: String) async throws {
        let room = try requireRoom(roomId: roomId)
        let timeline = try await getOrCreateTimeline(room: room)

        // toggleReaction needs the timeline item's uniqueId, not the eventId
        // addListener immediately fires a Reset diff with current items
        let collector = TimelineItemCollector(roomId: roomId)
        let handle = await timeline.addListener(listener: collector)
        await collector.waitForUpdate()
        handle.cancel()

        guard let uniqueId = collector.uniqueIdForEvent(eventId) else {
            throw MatrixBridgeError.notSupported("Could not find timeline item for event \(eventId)")
        }
        try await timeline.toggleReaction(uniqueId: uniqueId, key: key)
    }

    // MARK: - User Discovery

    func searchUsers(searchTerm: String, limit: Int) async throws -> [String: Any] {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        let result = try await c.searchUsers(searchTerm: searchTerm, limit: UInt64(limit))
        let users = result.results.map { u -> [String: Any?] in
            [
                "userId": u.userId,
                "displayName": u.displayName,
                "avatarUrl": u.avatarUrl,
            ]
        }
        return [
            "results": users,
            "limited": result.limited,
        ]
    }

    // MARK: - Room Management

    func setRoomName(roomId: String, name: String) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.setName(name: name)
    }

    func setRoomTopic(roomId: String, topic: String) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.setTopic(topic: topic)
    }

    func inviteUser(roomId: String, userId: String) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.inviteUserById(userId: userId)
    }

    func kickUser(roomId: String, userId: String, reason: String?) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.kickUser(userId: userId, reason: reason)
    }

    func banUser(roomId: String, userId: String, reason: String?) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.banUser(userId: userId, reason: reason)
    }

    func unbanUser(roomId: String, userId: String) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.unbanUser(userId: userId, reason: nil)
    }

    // MARK: - Media URL

    func getMediaUrl(mxcUrl: String) throws -> String {
        guard let session = sessionStore.load() else {
            throw MatrixBridgeError.notLoggedIn
        }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        let mxcPath = mxcUrl.replacingOccurrences(of: "mxc://", with: "")
        return "\(baseUrl)/_matrix/client/v1/media/download/\(mxcPath)?access_token=\(session.accessToken)"
    }

    // MARK: - Typing

    func sendTyping(roomId: String, isTyping: Bool) async throws {
        let room = try requireRoom(roomId: roomId)
        try await room.typingNotice(isTyping: isTyping)
    }

    // MARK: - Encryption

    func initializeCrypto() async throws {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        await c.encryption().waitForE2eeInitializationTasks()
    }

    func getEncryptionStatus() async throws -> [String: Any] {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        let enc = c.encryption()
        let vState = enc.verificationState()
        let backupState = enc.backupState()
        let recoveryState = enc.recoveryState()

        let isVerified = vState == .verified
        let isBackupEnabled = backupState == .enabled || backupState == .creating || backupState == .resuming

        return [
            "isCrossSigningReady": isVerified,
            "crossSigningStatus": [
                "hasMaster": isVerified,
                "hasSelfSigning": isVerified,
                "hasUserSigning": isVerified,
                "isReady": isVerified,
            ],
            "isKeyBackupEnabled": isBackupEnabled,
            "isSecretStorageReady": recoveryState == .enabled,
        ]
    }

    func bootstrapCrossSigning() async throws {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        await c.encryption().waitForE2eeInitializationTasks()
    }

    func setupKeyBackup() async throws -> [String: Any] {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        try await c.encryption().enableBackups()
        return ["exists": true, "enabled": true]
    }

    func getKeyBackupStatus() async throws -> [String: Any] {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        let existsOnServer = try await c.encryption().backupExistsOnServer()
        let state = c.encryption().backupState()
        let enabled = state == .enabled || state == .creating || state == .resuming
        return ["exists": existsOnServer, "enabled": enabled]
    }

    func restoreKeyBackup(recoveryKey: String?) async throws -> [String: Any] {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        if let key = recoveryKey {
            try await c.encryption().recover(recoveryKey: key)
        }
        return ["importedKeys": -1]
    }

    func setupRecovery(passphrase: String?) async throws -> [String: Any] {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        let listener = NoopEnableRecoveryProgressListener()
        let key = try await c.encryption().enableRecovery(
            waitForBackupsToUpload: false,
            progressListener: listener
        )
        return ["recoveryKey": key]
    }

    func isRecoveryEnabled() async throws -> Bool {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        return c.encryption().recoveryState() == .enabled
    }

    func recoverAndSetup(recoveryKey: String) async throws {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        try await c.encryption().recover(recoveryKey: recoveryKey)
    }

    func resetRecoveryKey(passphrase: String?) async throws -> [String: Any] {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        let key = try await c.encryption().resetRecoveryKey()
        return ["recoveryKey": key]
    }

    func exportRoomKeys(passphrase: String) async throws -> String {
        throw MatrixBridgeError.notSupported("exportRoomKeys")
    }

    func importRoomKeys(data: String, passphrase: String) async throws -> Int {
        throw MatrixBridgeError.notSupported("importRoomKeys")
    }

    // MARK: - Helpers

    private static func dataDirectory() -> String {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("matrix_sdk")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    private static func serializeRoom(_ room: Room) async throws -> [String: Any] {
        let info = try await room.roomInfo()
        let encrypted = (try? room.isEncrypted()) ?? false
        let membership: String = {
            switch room.membership() {
            case .joined: return "join"
            case .invited: return "invite"
            case .left: return "leave"
            @unknown default: return "join"
            }
        }()
        return [
            "roomId": room.id(),
            "name": info.displayName ?? "",
            "topic": info.topic as Any,
            "memberCount": info.joinedMembersCount ?? 0,
            "isEncrypted": encrypted,
            "unreadCount": info.numUnreadMessages ?? 0,
            "lastEventTs": nil as Int? as Any,
            "membership": membership,
        ]
    }

    private static func mapSyncState(_ state: SyncServiceState) -> String {
        switch state {
        case .idle:
            return "STOPPED"
        case .running:
            return "SYNCING"
        case .terminated:
            return "STOPPED"
        case .error:
            return "ERROR"
        @unknown default:
            return "ERROR"
        }
    }
}

// MARK: - Timeline Serialization Helpers

private func extractMediaUrl(source: MediaSource, into contentDict: inout [String: Any]) {
    let url = source.url()
    if !url.isEmpty {
        contentDict["url"] = url
    }
    // Fallback: for encrypted media, try toJson to extract the mxc URL
    if contentDict["url"] == nil || (contentDict["url"] as? String)?.isEmpty == true {
        let json = source.toJson()
        if let jsonData = json.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
           let mxcUrl = parsed["url"] as? String, !mxcUrl.isEmpty {
            contentDict["url"] = mxcUrl
        }
    }
}

private func serializeTimelineItem(_ item: TimelineItem, roomId: String) -> [String: Any]? {
    guard let eventItem = item.asEvent() else { return nil }
    return serializeEventTimelineItem(eventItem, roomId: roomId)
}

private func serializeEventTimelineItem(_ eventItem: EventTimelineItem, roomId: String) -> [String: Any]? {
    let eventId: String
    if let eid = eventItem.eventId() {
        eventId = eid
    } else if let tid = eventItem.transactionId() {
        eventId = tid
    } else {
        return nil
    }

    var contentDict: [String: Any] = [:]
    var eventType = "m.room.message"

    let content = eventItem.content()
    switch content.kind() {
    case .message:
        if let msg = content.asMessage() {
            contentDict["body"] = msg.body()
            switch msg.msgtype() {
            case .text:
                contentDict["msgtype"] = "m.text"
            case .image(let imgContent):
                contentDict["msgtype"] = "m.image"
                extractMediaUrl(source: imgContent.source, into: &contentDict)
            case .file(let fileContent):
                contentDict["msgtype"] = "m.file"
                contentDict["filename"] = fileContent.filename
                extractMediaUrl(source: fileContent.source, into: &contentDict)
            case .audio(let audioContent):
                contentDict["msgtype"] = "m.audio"
                contentDict["filename"] = audioContent.filename
                extractMediaUrl(source: audioContent.source, into: &contentDict)
            case .video(let videoContent):
                contentDict["msgtype"] = "m.video"
                contentDict["filename"] = videoContent.filename
                extractMediaUrl(source: videoContent.source, into: &contentDict)
            case .emote:
                contentDict["msgtype"] = "m.emote"
            case .notice:
                contentDict["msgtype"] = "m.notice"
            default:
                contentDict["msgtype"] = "m.text"
            }
        }
    case .unableToDecrypt:
        contentDict["body"] = "Unable to decrypt message"
        contentDict["msgtype"] = "m.text"
        contentDict["encrypted"] = true
    case .redactedMessage:
        eventType = "m.room.redaction"
        contentDict["body"] = "Message deleted"
    default:
        eventType = "m.room.unknown"
    }

    // Reactions
    let reactions = eventItem.reactions()
    if !reactions.isEmpty {
        contentDict["reactions"] = reactions.map { r in
            [
                "key": r.key,
                "count": r.senders.count,
                "senders": r.senders.map { $0.senderId },
            ] as [String: Any]
        }
    }

    // Delivery/read status
    var status: String = "sent"
    if let sendState = eventItem.localSendState() {
        switch sendState {
        case .notSentYet:
            status = "sending"
        case .sendingFailed(_, _):
            status = "sending"
        case .sent(_):
            // Check read receipts below
            break
        default:
            break
        }
    }

    var readBy: [String]? = nil
    let receipts = eventItem.readReceipts()
    if !receipts.isEmpty {
        print("[CapMatrix] readReceipts for \(eventId): \(receipts.keys) sender=\(eventItem.sender())")
    }
    if status == "sent" {
        let others = receipts.keys.filter { $0 != eventItem.sender() }
        if !others.isEmpty {
            status = "read"
            readBy = Array(others)
        }
    }

    return [
        "eventId": eventId,
        "roomId": roomId,
        "senderId": eventItem.sender(),
        "type": eventType,
        "content": contentDict,
        "originServerTs": eventItem.timestamp(),
        "status": status,
        "readBy": readBy as Any,
    ]
}

// MARK: - Live Timeline Listener (for sync subscriptions)

class LiveTimelineListener: TimelineListener {
    private let roomId: String
    private let onMessage: ([String: Any]) -> Void
    private let onRoomUpdate: (String, [String: Any]) -> Void

    init(roomId: String, onMessage: @escaping ([String: Any]) -> Void, onRoomUpdate: @escaping (String, [String: Any]) -> Void) {
        self.roomId = roomId
        self.onMessage = onMessage
        self.onRoomUpdate = onRoomUpdate
    }

    func onUpdate(diff: [TimelineDiff]) {
        print("[CapMatrix] LiveTimelineListener onUpdate for \(roomId): \(diff.count) diffs")
        for d in diff {
            let change = d.change()
            print("[CapMatrix]   diff type: \(change)")
            switch change {
            case .reset:
                let items = d.reset() ?? []
                print("[CapMatrix]   Reset: \(items.count) items")
                items.forEach { item in
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        print("[CapMatrix]   Reset item: \(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil")")
                        onMessage(event)
                    }
                }
                onRoomUpdate(roomId, ["roomId": roomId])
            case .append:
                let items = d.append() ?? []
                print("[CapMatrix]   Append: \(items.count) items")
                items.forEach { item in
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        print("[CapMatrix]   Append item: \(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil")")
                        onMessage(event)
                    }
                }
                onRoomUpdate(roomId, ["roomId": roomId])
            case .pushBack:
                if let item = d.pushBack() {
                    let isLocalEcho = item.asEvent()?.eventId() == nil && item.asEvent()?.transactionId() != nil
                    print("[CapMatrix]   PushBack: localEcho=\(isLocalEcho)")
                    if !isLocalEcho, let event = serializeTimelineItem(item, roomId: roomId) {
                        print("[CapMatrix]   PushBack item: \(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil")")
                        onMessage(event)
                    }
                    onRoomUpdate(roomId, ["roomId": roomId])
                }
            case .pushFront:
                if let item = d.pushFront() {
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        print("[CapMatrix]   PushFront item: \(event["eventId"] ?? "nil")")
                        onMessage(event)
                    }
                    onRoomUpdate(roomId, ["roomId": roomId])
                }
            case .set:
                if let data = d.set() {
                    if let event = serializeTimelineItem(data.item, roomId: roomId) {
                        print("[CapMatrix]   Set item: \(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil") status=\(event["status"] ?? "nil") readBy=\(event["readBy"] ?? "nil")")
                        onMessage(event)
                        // If this event has readBy data, trigger roomUpdated
                        // so the app can refresh receipt status for all messages
                        if let rb = event["readBy"] as? [String], !rb.isEmpty {
                            onRoomUpdate(roomId, ["roomId": roomId])
                        }
                    }
                }
            case .insert:
                if let data = d.insert() {
                    if let event = serializeTimelineItem(data.item, roomId: roomId) {
                        print("[CapMatrix]   Insert item: \(event["eventId"] ?? "nil")")
                        onMessage(event)
                    }
                    onRoomUpdate(roomId, ["roomId": roomId])
                }
            case .remove:
                break // Index-based removal, handled by JS layer
            default:
                break
            }
        }
    }
}

// MARK: - Timeline Item Collector (for pagination/one-shot reads)

class TimelineItemCollector: TimelineListener {
    private let lock = NSLock()
    private var _events: [[String: Any]] = []
    private var _uniqueIdMap: [String: String] = [:] // eventId -> uniqueId
    private let roomId: String
    private var _updateContinuation: CheckedContinuation<Void, Never>?
    private var _updateCount = 0
    private var _lastWaitedCount = 0

    init(roomId: String) {
        self.roomId = roomId
    }

    /// Waits for the listener to receive at least one update since the last call (or since creation).
    func waitForUpdate() async {
        lock.lock()
        let countBefore = _lastWaitedCount
        if _updateCount > countBefore {
            _lastWaitedCount = _updateCount
            lock.unlock()
            return
        }
        lock.unlock()
        await withCheckedContinuation { cont in
            lock.lock()
            if _updateCount > countBefore {
                _lastWaitedCount = _updateCount
                lock.unlock()
                cont.resume()
            } else {
                _updateContinuation = cont
                lock.unlock()
            }
        }
        lock.lock()
        _lastWaitedCount = _updateCount
        lock.unlock()
    }

    var events: [[String: Any]] {
        lock.lock()
        defer { lock.unlock() }
        return _events
    }

    func uniqueIdForEvent(_ eventId: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return _uniqueIdMap[eventId]
    }

    func onUpdate(diff: [TimelineDiff]) {
        var continuation: CheckedContinuation<Void, Never>?
        lock.lock()
        for d in diff {
            switch d.change() {
            case .reset:
                _events.removeAll()
                _uniqueIdMap.removeAll()
                d.reset()?.forEach { item in
                    trackUniqueId(item)
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        _events.append(event)
                    }
                }
            case .append:
                d.append()?.forEach { item in
                    trackUniqueId(item)
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        _events.append(event)
                    }
                }
            case .pushBack:
                if let item = d.pushBack() {
                    trackUniqueId(item)
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        _events.append(event)
                    }
                }
            case .pushFront:
                if let item = d.pushFront() {
                    trackUniqueId(item)
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        _events.insert(event, at: 0)
                    }
                }
            case .set:
                if let data = d.set() {
                    trackUniqueId(data.item)
                    if let event = serializeTimelineItem(data.item, roomId: roomId) {
                        _events.append(event)
                    }
                }
            case .insert:
                if let data = d.insert() {
                    trackUniqueId(data.item)
                    if let event = serializeTimelineItem(data.item, roomId: roomId) {
                        _events.append(event)
                    }
                }
            default:
                break
            }
        }
        _updateCount += 1
        continuation = _updateContinuation
        _updateContinuation = nil
        lock.unlock()
        continuation?.resume()
    }

    private func trackUniqueId(_ item: TimelineItem) {
        guard let eventItem = item.asEvent() else { return }
        let uniqueId = item.uniqueId()
        if let eid = eventItem.eventId() {
            _uniqueIdMap[eid] = uniqueId
        }
        if let tid = eventItem.transactionId() {
            _uniqueIdMap[tid] = uniqueId
        }
    }
}

// MARK: - Errors

enum MatrixBridgeError: LocalizedError {
    case notLoggedIn
    case roomNotFound(String)
    case notSupported(String)

    var errorDescription: String? {
        switch self {
        case .notLoggedIn:
            return "Not logged in. Call login() or loginWithToken() first."
        case .roomNotFound(let roomId):
            return "Room \(roomId) not found"
        case .notSupported(let method):
            return "\(method) is not supported in this version of the Matrix SDK"
        }
    }
}

// MARK: - Sync Observer Proxy

class SyncStateObserverProxy: SyncServiceStateObserver {
    private let onUpdateHandler: (SyncServiceState) -> Void

    init(onUpdate: @escaping (SyncServiceState) -> Void) {
        self.onUpdateHandler = onUpdate
    }

    func onUpdate(state: SyncServiceState) {
        onUpdateHandler(state)
    }
}

// MARK: - Enable Recovery Progress Listener (no-op)

class NoopEnableRecoveryProgressListener: EnableRecoveryProgressListener {
    func onUpdate(status: EnableRecoveryProgress) {
        // No-op
    }
}

// MARK: - Keychain Store

class MatrixKeychainStore {
    private let service = "de.tremaze.matrix"

    func save(session: MatrixSessionInfo) {
        let data: [String: String] = session.toDictionary()
        guard let jsonData = try? JSONEncoder().encode(data) else { return }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "session",
            kSecValueData as String: jsonData
        ]

        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }

    func load() -> MatrixSessionInfo? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "session",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        guard let dict = try? JSONDecoder().decode([String: String].self, from: data) else { return nil }

        guard let accessToken = dict["accessToken"],
              let userId = dict["userId"],
              let deviceId = dict["deviceId"],
              let homeserverUrl = dict["homeserverUrl"] else { return nil }

        return MatrixSessionInfo(
            accessToken: accessToken,
            userId: userId,
            deviceId: deviceId,
            homeserverUrl: homeserverUrl
        )
    }

    func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "session"
        ]
        SecItemDelete(query as CFDictionary)
    }
}
