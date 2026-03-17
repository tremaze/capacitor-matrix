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
    private var receiptSyncTask: Task<Void, Never>?

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
        let cacheDir = Self.cacheDirectory()

        let newClient = try await ClientBuilder()
            .homeserverUrl(url: homeserverUrl)
            .sessionPaths(dataPath: dataDir, cachePath: cacheDir)
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
        let cacheDir = Self.cacheDirectory()

        let newClient = try await ClientBuilder()
            .homeserverUrl(url: homeserverUrl)
            .sessionPaths(dataPath: dataDir, cachePath: cacheDir)
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
        receiptSyncTask?.cancel()
        receiptSyncTask = nil
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
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("matrix_sdk_cache")
        try? FileManager.default.removeItem(at: cacheDir)
    }

    func getSession() -> [String: String]? {
        return sessionStore.load()?.toDictionary()
    }

    // MARK: - Sync

    func startSync(
        onSyncState: @escaping (String) -> Void,
        onMessage: @escaping ([String: Any]) -> Void,
        onRoomUpdate: @escaping (String, [String: Any]) -> Void,
        onReceipt: @escaping (String) -> Void
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

        // Start a parallel v2 sync connection that only listens for m.receipt
        // ephemeral events. Tuwunel's sliding sync doesn't deliver other users'
        // read receipts, so this provides live receipt updates.
        startReceiptSync(onReceipt: onReceipt)
    }

    /// Runs a lightweight v2 sync loop that only subscribes to m.receipt
    /// ephemeral events. The Rust SDK receives receipts via sliding sync
    /// but doesn't expose them through readReceipts() on timeline items,
    /// so this parallel connection provides live receipt updates.
    private func startReceiptSync(onReceipt: @escaping (String) -> Void) {
        guard let session = sessionStore.load() else { return }

        receiptSyncTask?.cancel()
        receiptSyncTask = Task.detached {
            let baseUrl = session.homeserverUrl.hasSuffix("/")
                ? String(session.homeserverUrl.dropLast())
                : session.homeserverUrl
            let token = session.accessToken
            let userId = session.userId

            print("[CapMatrix] receiptSync: starting, uploading filter...")

            // Upload filter first — some servers reject inline JSON filters
            let filterId = await Self.uploadSyncFilter(
                baseUrl: baseUrl, accessToken: token, userId: userId
            )
            print("[CapMatrix] receiptSync: filterId=\(filterId ?? "nil")")

            var since: String? = nil

            // Try both v3 and r0 API versions
            let apiPaths = ["/_matrix/client/v3/sync", "/_matrix/client/r0/sync"]
            var workingPath: String? = nil

            for apiPath in apiPaths {
                if Task.isCancelled { return }

                let testUrl = Self.buildSyncUrl(
                    baseUrl: baseUrl, apiPath: apiPath,
                    filterId: filterId, since: nil, timeout: 0
                )
                guard let url = testUrl else { continue }

                var request = URLRequest(url: url)
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                request.setValue("application/json", forHTTPHeaderField: "Accept")
                request.timeoutInterval = 30

                do {
                    let (data, response) = try await URLSession.shared.data(for: request)
                    let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1

                    if statusCode == 200 {
                        workingPath = apiPath
                        // Extract since token from first response
                        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                           let nextBatch = json["next_batch"] as? String {
                            since = nextBatch
                        }
                        // Process any receipts in the initial response
                        Self.processReceiptResponse(data: data, onReceipt: onReceipt)
                        print("[CapMatrix] receiptSync: \(apiPath) works, since=\(since ?? "nil")")
                        break
                    } else {
                        let body = String(data: data, encoding: .utf8) ?? "(no body)"
                        print("[CapMatrix] receiptSync: \(apiPath) returned HTTP \(statusCode): \(body.prefix(500))")
                    }
                } catch {
                    print("[CapMatrix] receiptSync: \(apiPath) failed: \(error)")
                }
            }

            guard let apiPath = workingPath else {
                print("[CapMatrix] receiptSync: no working sync endpoint found, giving up")
                return
            }

            print("[CapMatrix] receiptSync: entering long-poll loop on \(apiPath)")

            while !Task.isCancelled {
                let syncUrl = Self.buildSyncUrl(
                    baseUrl: baseUrl, apiPath: apiPath,
                    filterId: filterId, since: since, timeout: 30000
                )
                guard let url = syncUrl else {
                    print("[CapMatrix] receiptSync: invalid URL")
                    return
                }

                var request = URLRequest(url: url)
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                request.setValue("application/json", forHTTPHeaderField: "Accept")
                request.timeoutInterval = 60

                do {
                    let (data, response) = try await URLSession.shared.data(for: request)
                    let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1

                    guard statusCode == 200 else {
                        let body = String(data: data, encoding: .utf8) ?? ""
                        print("[CapMatrix] receiptSync: HTTP \(statusCode): \(body.prefix(300))")
                        // Back off on error, but not too long
                        try await Task.sleep(nanoseconds: 5_000_000_000)
                        continue
                    }

                    if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                       let nextBatch = json["next_batch"] as? String {
                        since = nextBatch
                    }

                    Self.processReceiptResponse(data: data, onReceipt: onReceipt)
                } catch is CancellationError {
                    break
                } catch {
                    print("[CapMatrix] receiptSync: error: \(error)")
                    if !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 5_000_000_000)
                    }
                }
            }
            print("[CapMatrix] receiptSync: loop ended")
        }
    }

    /// Upload a sync filter that only subscribes to m.receipt ephemeral events.
    /// Returns the filter ID, or nil if upload fails.
    private static func uploadSyncFilter(
        baseUrl: String, accessToken: String, userId: String
    ) async -> String? {
        let encodedUserId = userId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? userId
        let urlStr = "\(baseUrl)/_matrix/client/v3/user/\(encodedUserId)/filter"
        guard let url = URL(string: urlStr) else { return nil }

        let filterJson: [String: Any] = [
            "room": [
                "timeline": ["limit": 0],
                "state": ["types": [] as [String]],
                "ephemeral": ["types": ["m.receipt"]]
            ],
            "presence": ["types": [] as [String]]
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: filterJson) else { return nil }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        request.timeoutInterval = 15

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
            if statusCode == 200,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let filterId = json["filter_id"] as? String {
                return filterId
            }
            let respBody = String(data: data, encoding: .utf8) ?? ""
            print("[CapMatrix] receiptSync: filter upload HTTP \(statusCode): \(respBody.prefix(300))")
            return nil
        } catch {
            print("[CapMatrix] receiptSync: filter upload failed: \(error)")
            return nil
        }
    }

    /// Build a sync URL using URLComponents for correct encoding.
    private static func buildSyncUrl(
        baseUrl: String, apiPath: String,
        filterId: String?, since: String?, timeout: Int
    ) -> URL? {
        var components = URLComponents(string: "\(baseUrl)\(apiPath)")
        var queryItems: [URLQueryItem] = [
            URLQueryItem(name: "timeout", value: "\(timeout)")
        ]
        if let fid = filterId {
            queryItems.append(URLQueryItem(name: "filter", value: fid))
        } else {
            // Inline filter as fallback
            let inlineFilter = #"{"room":{"timeline":{"limit":0},"state":{"types":[]},"ephemeral":{"types":["m.receipt"]}},"presence":{"types":[]}}"#
            queryItems.append(URLQueryItem(name: "filter", value: inlineFilter))
        }
        if let s = since {
            queryItems.append(URLQueryItem(name: "since", value: s))
        }
        components?.queryItems = queryItems
        return components?.url
    }

    /// Parse receipt events from a v2 sync response and fire callbacks.
    private static func processReceiptResponse(
        data: Data, onReceipt: @escaping (String) -> Void
    ) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rooms = (json["rooms"] as? [String: Any])?["join"] as? [String: Any] else {
            return
        }
        for (roomId, roomData) in rooms {
            guard let roomDict = roomData as? [String: Any],
                  let ephemeral = roomDict["ephemeral"] as? [String: Any],
                  let events = ephemeral["events"] as? [[String: Any]] else {
                continue
            }
            for event in events {
                guard (event["type"] as? String) == "m.receipt" else { continue }
                print("[CapMatrix] receiptSync: receipt in \(roomId)")
                onReceipt(roomId)
            }
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
        receiptSyncTask?.cancel()
        receiptSyncTask = nil
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
        let gotInitial = await collector.waitForUpdate(timeoutNanos: 5_000_000_000)
        print("[CapMatrix] getRoomMessages: initial snapshot: \(collector.events.count) items, gotInitial=\(gotInitial)")

        // Only paginate if we don't have enough items yet
        if collector.events.count < limit {
            let hitStart = try await timeline.paginateBackwards(numEvents: UInt16(limit))
            print("[CapMatrix] getRoomMessages: paginated, hitStart=\(hitStart)")

            // If there were new events, wait for the diffs to arrive via the listener
            if !hitStart {
                _ = await collector.waitForUpdate(timeoutNanos: 5_000_000_000)
            }
            print("[CapMatrix] getRoomMessages: after pagination: \(collector.events.count) items")
        }

        handle.cancel()

        var events = Array(collector.events.suffix(limit))

        // Apply receipt watermark: if any own event has readBy data,
        // all earlier own events in the timeline are also read.
        // The SDK only attaches receipts to the specific event they target,
        // but in Matrix a read receipt implies all prior events are read too.
        // Only events BEFORE the watermark are marked — events after it are unread.
        let myUserId = client.flatMap({ try? $0.userId() })
        var watermarkReadBy: [String]? = nil
        var watermarkIndex = -1
        // Walk backwards (newest first) to find the newest own event with a receipt
        for i in stride(from: events.count - 1, through: 0, by: -1) {
            let evt = events[i]
            let sender = evt["senderId"] as? String
            if sender == myUserId {
                if let rb = evt["readBy"] as? [String], !rb.isEmpty {
                    watermarkReadBy = rb
                    watermarkIndex = i
                    break
                }
            }
        }
        // Apply watermark only to own events BEFORE the watermark (older)
        if let watermark = watermarkReadBy, watermarkIndex >= 0 {
            for i in 0..<watermarkIndex {
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
        let myUserId = client.flatMap({ try? $0.userId() })

        // Collect all events
        var items: [(id: String, item: EventTimelineItem, serialized: [String: Any])] = []

        for eid in eventIds {
            do {
                let eventItem = try await timeline.getEventTimelineItemByEventId(eventId: eid)
                if let serialized = serializeEventTimelineItem(eventItem, roomId: roomId) {
                    items.append((id: eid, item: eventItem, serialized: serialized))
                }
            } catch {
                // skip
            }
        }

        // Find the newest own event with a read receipt (watermark)
        var watermarkReadBy: [String]? = nil
        var watermarkIndex = -1
        for i in stride(from: items.count - 1, through: 0, by: -1) {
            if items[i].serialized["senderId"] as? String == myUserId,
               let rb = items[i].serialized["readBy"] as? [String], !rb.isEmpty {
                watermarkReadBy = rb
                watermarkIndex = i
                break
            }
        }

        // Apply watermark only to own events BEFORE the watermark (older)
        var results: [[String: Any]] = []
        if let watermark = watermarkReadBy, watermarkIndex >= 0 {
            for i in 0..<items.count {
                var serialized = items[i].serialized
                if i < watermarkIndex,
                   serialized["senderId"] as? String == myUserId {
                    let existing = serialized["readBy"] as? [String]
                    if existing == nil || existing!.isEmpty {
                        serialized["status"] = "read"
                        serialized["readBy"] = watermark
                    }
                }
                results.append(serialized)
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

    /// Separate cache directory for sliding sync state.
    /// Cleared on each login/restore to force a fresh sync, working around
    /// Tuwunel returning stale events when resuming from a cached sync position.
    private static func cacheDirectory() -> String {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("matrix_sdk_cache")
        // Clear stale sync cache on each startup
        try? FileManager.default.removeItem(at: dir)
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

/// Mirrors the SDK's full timeline (including virtual/nil items) so that
/// index-based diffs (Insert, Remove, Set) stay correct. The public `events`
/// property filters out nils to return only real event items.
class TimelineItemCollector: TimelineListener {
    private let lock = NSLock()
    // Full mirror of the SDK timeline — nil entries represent virtual items
    // (day separators, read markers, etc.) that serializeTimelineItem skips.
    private var _items: [[String: Any]?] = []
    private var _uniqueIdMap: [String: String] = [:] // eventId -> uniqueId
    private let roomId: String
    private var _updateContinuation: CheckedContinuation<Bool, Never>?
    private var _updateCount = 0
    private var _lastWaitedCount = 0

    init(roomId: String) {
        self.roomId = roomId
    }

    /// Waits for the listener to receive at least one update since the last call (or since creation).
    /// Returns true if an update was received, false if the timeout was hit.
    @discardableResult
    func waitForUpdate(timeoutNanos: UInt64 = 0) async -> Bool {
        lock.lock()
        let countBefore = _lastWaitedCount
        if _updateCount > countBefore {
            _lastWaitedCount = _updateCount
            lock.unlock()
            return true
        }
        lock.unlock()

        // Race between the update arriving and an optional timeout
        return await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
                    self.lock.lock()
                    if self._updateCount > countBefore {
                        self._lastWaitedCount = self._updateCount
                        self.lock.unlock()
                        cont.resume(returning: true)
                    } else {
                        self._updateContinuation = cont
                        self.lock.unlock()
                    }
                }
            }
            if timeoutNanos > 0 {
                group.addTask {
                    try? await Task.sleep(nanoseconds: timeoutNanos)
                    return false
                }
            }
            let result = await group.next() ?? false
            if !result {
                // Timeout won — clear and resume the pending continuation
                self.lock.lock()
                let pending = self._updateContinuation
                self._updateContinuation = nil
                self.lock.unlock()
                pending?.resume(returning: false)
            }
            group.cancelAll()
            self.lock.lock()
            self._lastWaitedCount = self._updateCount
            self.lock.unlock()
            return result
        }
    }

    /// Returns only the non-nil (real event) items, in timeline order.
    var events: [[String: Any]] {
        lock.lock()
        defer { lock.unlock() }
        return _items.compactMap { $0 }
    }

    func uniqueIdForEvent(_ eventId: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return _uniqueIdMap[eventId]
    }

    func onUpdate(diff: [TimelineDiff]) {
        var continuation: CheckedContinuation<Bool, Never>?
        lock.lock()
        for d in diff {
            switch d.change() {
            case .reset:
                _items.removeAll()
                _uniqueIdMap.removeAll()
                d.reset()?.forEach { item in
                    trackUniqueId(item)
                    _items.append(serializeTimelineItem(item, roomId: roomId))
                }
            case .append:
                d.append()?.forEach { item in
                    trackUniqueId(item)
                    _items.append(serializeTimelineItem(item, roomId: roomId))
                }
            case .pushBack:
                if let item = d.pushBack() {
                    trackUniqueId(item)
                    _items.append(serializeTimelineItem(item, roomId: roomId))
                }
            case .pushFront:
                if let item = d.pushFront() {
                    trackUniqueId(item)
                    _items.insert(serializeTimelineItem(item, roomId: roomId), at: 0)
                }
            case .set:
                if let data = d.set() {
                    trackUniqueId(data.item)
                    let idx = Int(data.index)
                    if idx >= 0 && idx < _items.count {
                        _items[idx] = serializeTimelineItem(data.item, roomId: roomId)
                    }
                }
            case .insert:
                if let data = d.insert() {
                    trackUniqueId(data.item)
                    let idx = min(Int(data.index), _items.count)
                    _items.insert(serializeTimelineItem(data.item, roomId: roomId), at: idx)
                }
            case .clear:
                _items.removeAll()
                _uniqueIdMap.removeAll()
            default:
                break
            }
        }
        _updateCount += 1
        continuation = _updateContinuation
        _updateContinuation = nil
        lock.unlock()
        continuation?.resume(returning: true)
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
