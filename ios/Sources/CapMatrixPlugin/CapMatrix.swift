import Foundation
import CommonCrypto
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
    private var platformInitialized = false
    private var syncStateObserver: SyncStateObserverProxy?
    private let subscriptionLock = NSLock()
    private var receiptSyncTask: Task<Void, Never>?
    private var currentSyncState: String = "STOPPED"
    private var roomCreatedAtCache: [String: UInt64] = [:]
    private let createdAtLock = NSLock()
    // Rooms currently being paginated by getRoomMessages — live listener suppresses events for these
    private var paginatingRooms = Set<String>()
    private let paginatingLock = NSLock()
    // Per-room tracking of the oldest event ID returned to JS, used for pagination cursor
    private var oldestReturnedEventId: [String: String] = [:]
    // Set on session restore (jwtLogin) to filter out rooms that are cached as
    // joined locally but are no longer joined according to the server.  Tuwunel's
    // sliding sync does not push explicit leave events on resume, so without this
    // reconciliation those rooms would stay in the list indefinitely.
    private var cachedRoomIds: Set<String>? = nil      // rooms the SDK knew about before sync
    private var serverJoinedRoomIds: Set<String>? = nil // authoritative list from /joined_rooms

    // MARK: - Auth

    func login(homeserverUrl: String, userId: String, password: String) async throws -> [String: String] {
        do {
            return try await _login(homeserverUrl: homeserverUrl, userId: userId, password: password)
        } catch {
            if "\(error)".contains("account in the store") {
                print("[CapMatrix] Crypto store mismatch — clearing data and retrying login")
                await clearAllData()
                return try await _login(homeserverUrl: homeserverUrl, userId: userId, password: password)
            }
            throw error
        }
    }

    private func _login(homeserverUrl: String, userId: String, password: String) async throws -> [String: String] {
        let dataDir = Self.dataDirectory()
        let cacheDir = Self.cacheDirectory(clearFirst: true)

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

    func jwtLogin(homeserverUrl: String, token: String) async throws -> [String: String] {
        do {
            return try await _jwtLogin(homeserverUrl: homeserverUrl, token: token)
        } catch {
            // If crypto store has mismatched account, wipe and retry
            if "\(error)".contains("account in the store") {
                print("[CapMatrix] Crypto store mismatch — clearing data and retrying login")
                await clearAllData()
                return try await _jwtLogin(homeserverUrl: homeserverUrl, token: token)
            }
            throw error
        }
    }

    private struct MatrixCredentials {
        let accessToken: String
        let userId: String
        let deviceId: String
    }

    private func decodeJwtSub(_ token: String) -> String? {
        let parts = token.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var base64 = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64.append(contentsOf: String(repeating: "=", count: 4 - remainder)) }
        guard let data = Data(base64Encoded: base64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sub = json["sub"] as? String else { return nil }
        return sub
    }

    private func _jwtLogin(homeserverUrl: String, token: String) async throws -> [String: String] {
        let sub = decodeJwtSub(token)
        let stored = sessionStore.load()
        print("[CapMatrix] _jwtLogin: sub=\(sub ?? "nil"), stored userId=\(stored?.userId ?? "nil"), stored hs=\(stored?.homeserverUrl ?? "nil")")

        if let stored = stored, let sub = sub {
            let matchesUser = stored.userId.hasPrefix("@\(sub):")
            let matchesHomeserver = stored.homeserverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")) ==
                homeserverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))

            if matchesUser && matchesHomeserver {
                print("[CapMatrix] _jwtLogin: same user + homeserver — restoring from stored session")
                return try await _restoreWithCredentials(
                    homeserverUrl: homeserverUrl,
                    accessToken: stored.accessToken,
                    userId: stored.userId,
                    deviceId: stored.deviceId
                )
            } else {
                print("[CapMatrix] _jwtLogin: different user — clearing all data before fresh exchange")
                await clearAllData()
            }
        }

        print("[CapMatrix] _jwtLogin: performing fresh JWT exchange (no deviceId)")
        let creds = try await exchangeJwtForCredentials(homeserverUrl: homeserverUrl, token: token)
        print("[CapMatrix] _jwtLogin: got credentials userId=\(creds.userId) deviceId=\(creds.deviceId)")
        return try await _restoreWithCredentials(
            homeserverUrl: homeserverUrl,
            accessToken: creds.accessToken,
            userId: creds.userId,
            deviceId: creds.deviceId
        )
    }

    private func exchangeJwtForCredentials(homeserverUrl: String, token: String) async throws -> MatrixCredentials {
        let baseUrl = homeserverUrl.hasSuffix("/") ? String(homeserverUrl.dropLast()) : homeserverUrl
        guard let url = URL(string: "\(baseUrl)/_matrix/client/v3/login") else {
            throw MatrixBridgeError.custom("Invalid homeserver URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 15

        let payload: [String: Any] = [
            "type": "org.matrix.login.jwt",
            "token": token,
            "initial_device_display_name": "Capacitor Matrix Plugin"
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw MatrixBridgeError.custom("JWT login failed: invalid response")
        }
        guard httpResponse.statusCode == 200 else {
            let errorBody = String(data: data, encoding: .utf8) ?? "unknown error"
            throw MatrixBridgeError.custom("JWT login failed (HTTP \(httpResponse.statusCode)): \(errorBody)")
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let accessToken = json["access_token"] as? String,
              let userId = json["user_id"] as? String,
              let deviceId = json["device_id"] as? String else {
            throw MatrixBridgeError.custom("JWT login failed: missing fields in response")
        }

        return MatrixCredentials(accessToken: accessToken, userId: userId, deviceId: deviceId)
    }

    private func _restoreWithCredentials(homeserverUrl: String, accessToken: String, userId: String, deviceId: String) async throws -> [String: String] {
        // Stop existing sync and clean up stale references before replacing the client
        await syncService?.stop()
        syncService = nil
        syncStateHandle = nil
        receiptSyncTask?.cancel()
        receiptSyncTask = nil
        timelineListenerHandles.removeAll()
        roomTimelines.removeAll()
        subscribedRoomIds.removeAll()

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

        // Snapshot which rooms the SDK knows about from the cache, then fetch the
        // server's authoritative joined-rooms list concurrently.  The combination
        // lets isStaleRoom() filter out cached rooms the user has since left without
        // waiting for the server to push explicit leave events (which Tuwunel doesn't).
        async let serverRooms = fetchJoinedRoomIds(homeserverUrl: homeserverUrl, accessToken: accessToken)
        cachedRoomIds = Set(newClient.rooms().map { $0.id() })
        serverJoinedRoomIds = await serverRooms
        print("[CapMatrix] _restoreWithCredentials: cachedRooms=\(cachedRoomIds?.count ?? 0), serverJoined=\(serverJoinedRoomIds?.count ?? 0)")

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
        await syncService?.stop()
        syncService = nil
        syncStateHandle = nil
        currentSyncState = "STOPPED"
        timelineListenerHandles.removeAll()
        roomTimelines.removeAll()
        subscribedRoomIds.removeAll()
        cachedRoomIds = nil
        serverJoinedRoomIds = nil
        try await client?.logout()
        client = nil
        sessionStore.clear()
    }

    func clearAllData() async {
        receiptSyncTask?.cancel()
        receiptSyncTask = nil
        await syncService?.stop()
        syncService = nil
        syncStateHandle = nil
        currentSyncState = "STOPPED"
        client = nil
        timelineListenerHandles.removeAll()
        roomTimelines.removeAll()
        subscribedRoomIds.removeAll()
        cachedRoomIds = nil
        serverJoinedRoomIds = nil
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

    func updateAccessToken(accessToken: String) async throws {
        guard client != nil else {
            throw MatrixBridgeError.notLoggedIn
        }

        // Stop sync service and clean up references
        await syncService?.stop()
        syncService = nil
        syncStateHandle = nil
        receiptSyncTask?.cancel()
        receiptSyncTask = nil
        timelineListenerHandles.removeAll()
        roomTimelines.removeAll()
        subscribedRoomIds.removeAll()

        guard let oldSession = sessionStore.load() else {
            throw MatrixBridgeError.custom("No persisted session to update")
        }

        // Build a new client pointing to the same data directory (preserves crypto store).
        // The Rust SDK's restoreSession() can only be called once per Client instance.
        let dataDir = Self.dataDirectory()
        let cacheDir = Self.cacheDirectory()

        let newClient = try await ClientBuilder()
            .homeserverUrl(url: oldSession.homeserverUrl)
            .sessionPaths(dataPath: dataDir, cachePath: cacheDir)
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .autoEnableCrossSigning(autoEnableCrossSigning: true)
            .build()

        let newSession = Session(
            accessToken: accessToken,
            refreshToken: nil,
            userId: oldSession.userId,
            deviceId: oldSession.deviceId,
            homeserverUrl: oldSession.homeserverUrl,
            oidcData: nil,
            slidingSyncVersion: .native
        )

        try await newClient.restoreSession(session: newSession)
        client = newClient

        let updatedInfo = MatrixSessionInfo(
            accessToken: accessToken,
            userId: oldSession.userId,
            deviceId: oldSession.deviceId,
            homeserverUrl: oldSession.homeserverUrl
        )
        sessionStore.save(session: updatedInfo)
    }

    // MARK: - Sync

    func startSync(
        onSyncState: @escaping (String) -> Void,
        onMessage: @escaping ([String: Any]) -> Void,
        onRoomUpdate: @escaping (String, [String: Any]) -> Void,
        onReceipt: @escaping (_ roomId: String, _ eventId: String, _ userId: String) -> Void
    ) async throws {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }

        // Enable Rust SDK tracing (once — calling initPlatform twice panics)
        if !platformInitialized {
            let tracingConfig = TracingConfiguration(
                logLevel: .warn,
                traceLogPacks: [],
                extraTargets: ["matrix_sdk", "matrix_sdk_ui"],
                writeToStdoutOrSystem: true,
                writeToFiles: nil,
                sentryDsn: nil
            )
            try? initPlatform(config: tracingConfig, useLightweightTokioRuntime: false)
            platformInitialized = true
        }

        print("[CapMatrix] startSync: building sync service...")
        let service = try await c.syncService().finish()
        syncService = service
        print("[CapMatrix] startSync: sync service built")

        let observer = SyncStateObserverProxy(onUpdate: { [weak self] state in
            let mapped = Self.mapSyncState(state)
            self?.currentSyncState = mapped
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
    private func startReceiptSync(onReceipt: @escaping (_ roomId: String, _ eventId: String, _ userId: String) -> Void) {
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

    /// Parse receipt events from a v2 sync response and fire callbacks per (eventId, userId) pair.
    private static func processReceiptResponse(
        data: Data, onReceipt: @escaping (_ roomId: String, _ eventId: String, _ userId: String) -> Void
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
                guard (event["type"] as? String) == "m.receipt",
                      let content = event["content"] as? [String: Any] else { continue }
                // Content format: { "$eventId": { "m.read": { "@user:server": { "ts": 123 } } } }
                for (eventId, receiptTypes) in content {
                    guard let types = receiptTypes as? [String: Any] else { continue }
                    for receiptType in ["m.read", "m.read.private"] {
                        guard let readers = types[receiptType] as? [String: Any] else { continue }
                        for userId in readers.keys {
                            print("[CapMatrix] receiptSync: receipt roomId=\(roomId) eventId=\(eventId) userId=\(userId)")
                            onReceipt(roomId, eventId, userId)
                        }
                    }
                }
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
            // Only subscribe to rooms the SDK considers joined; left/invited rooms
            // from a cached session would produce M_FORBIDDEN on timeline access.
            guard room.membership() == .joined else { continue }
            // Skip rooms that were cached as joined but are no longer on the server.
            guard !isStaleRoom(roomId: roomId) else { continue }
            subscribedRoomIds.insert(roomId)
            roomsToSubscribe.append((room, roomId))
        }
        subscriptionLock.unlock()

        print("[CapMatrix] subscribeToRoomTimelines: \(alreadyCount) already subscribed, \(roomsToSubscribe.count) new")
        if roomsToSubscribe.isEmpty { return }

        for (room, roomId) in roomsToSubscribe {
            do {
                let timeline = try await getOrCreateTimeline(room: room)
                let listener = LiveTimelineListener(roomId: roomId, room: room, onMessage: onMessage, onRoomUpdate: onRoomUpdate, isPaginating: { [weak self] in
                    guard let self = self else { return false }
                    self.paginatingLock.lock()
                    defer { self.paginatingLock.unlock() }
                    return self.paginatingRooms.contains(roomId)
                })
                let handle = await timeline.addListener(listener: listener)
                subscriptionLock.lock()
                timelineListenerHandles.append(handle)
                subscriptionLock.unlock()
                print("[CapMatrix]   room \(roomId): listener added")
            } catch {
                // Remove from the subscribed set so a later sync cycle can retry
                // once the SDK has updated its state.
                subscriptionLock.lock()
                subscribedRoomIds.remove(roomId)
                subscriptionLock.unlock()
                print("[CapMatrix]   room \(roomId): FAILED: \(error)")
            }
        }

        // Preload messages for all rooms in the background so paginateBackwards
        // has already run by the time the user opens a room.
        Task {
            let t0 = CFAbsoluteTimeGetCurrent()
            await withTaskGroup(of: Void.self) { group in
                for (_, roomId) in roomsToSubscribe {
                    group.addTask { [weak self] in
                        guard let self = self, let timeline = self.roomTimelines[roomId] else { return }
                        let tRoom = CFAbsoluteTimeGetCurrent()
                        _ = try? await timeline.paginateBackwards(numEvents: 30)
                        print("[CapMatrix] [PERF] preload \(roomId.prefix(12))… paginateBackwards=\(self.ms(tRoom, CFAbsoluteTimeGetCurrent()))ms")
                    }
                }
            }
            print("[CapMatrix] [PERF] preload ALL rooms done in \(ms(t0, CFAbsoluteTimeGetCurrent()))ms")
        }
    }

    func stopSync() async throws {
        await syncService?.stop()
        syncStateHandle = nil
        currentSyncState = "STOPPED"
        subscribedRoomIds.removeAll()
        timelineListenerHandles.removeAll()
        roomTimelines.removeAll()
        receiptSyncTask?.cancel()
        receiptSyncTask = nil
    }

    func getSyncState() -> String {
        return currentSyncState
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
            guard room.membership() == .joined else { continue }
            guard !isStaleRoom(roomId: room.id()) else { continue }
            do {
                var dict = try await Self.serializeRoom(room)
                if dict["lastEventTs"] as? UInt64 == nil {
                    if let ts = await fetchRoomCreatedAt(roomId: room.id()) {
                        dict["createdAt"] = ts
                    }
                }
                result.append(dict)
            } catch {
                print("[CapMatrix] getRooms: skipping \(room.id()): \(error)")
            }
        }
        return result
    }

    private func fetchRoomCreatedAt(roomId: String) async -> UInt64? {
        createdAtLock.lock()
        let cached = roomCreatedAtCache[roomId]
        createdAtLock.unlock()
        if let cached { return cached }
        guard let session = sessionStore.load() else { return nil }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        guard let encodedRoomId = roomId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "\(baseUrl)/_matrix/client/v3/rooms/\(encodedRoomId)/state") else {
            return nil
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let statusCode = (response as? HTTPURLResponse)?.statusCode,
              statusCode >= 200, statusCode < 300,
              let events = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return nil
        }
        for event in events {
            if event["type"] as? String == "m.room.create",
               let ts = event["origin_server_ts"] as? UInt64 {
                createdAtLock.lock()
                roomCreatedAtCache[roomId] = ts
                createdAtLock.unlock()
                return ts
            }
        }
        return nil
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
                    "membership": String(describing: member.membership).lowercased(),
                    "avatarUrl": member.avatarUrl as Any,
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

    func forgetRoom(roomId: String) async throws {
        // The Rust SDK doesn't have a dedicated forget method on the Room type.
        // After leaving, the room is removed from the room list on next sync.
        // This is a no-op placeholder for API compatibility.
    }

    func createRoom(name: String?, topic: String?, isEncrypted: Bool, isDirect: Bool = false, invite: [String]?, preset: String? = nil) async throws -> String {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        let roomPreset: RoomPreset
        switch preset {
        case "trusted_private_chat":
            roomPreset = .trustedPrivateChat
        case "public_chat":
            roomPreset = .publicChat
        default:
            roomPreset = .privateChat
        }
        let params = CreateRoomParameters(
            name: name,
            topic: topic,
            isEncrypted: isEncrypted,
            isDirect: isDirect,
            visibility: .private,
            preset: roomPreset,
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

    func editMessage(roomId: String, eventId: String, newBody: String) async throws -> String {
        let room = try requireRoom(roomId: roomId)
        let content = messageEventContentFromMarkdown(md: newBody)
        let editContent = EditedContent.roomMessage(content: content)
        let timeline = try await getOrCreateTimeline(room: room)
        try await timeline.edit(eventOrTransactionId: .eventId(eventId: eventId), newContent: editContent)
        return ""
    }

    func sendReply(roomId: String, body: String, replyToEventId: String, msgtype: String) async throws -> String {
        let room = try requireRoom(roomId: roomId)
        let timeline = try await getOrCreateTimeline(room: room)
        let content = messageEventContentFromMarkdown(md: body)
        try await timeline.sendReply(msg: content, eventId: replyToEventId)
        return ""
    }

    func getRoomMessages(roomId: String, limit: Int, from: String?) async throws -> [String: Any] {
        let t0 = CFAbsoluteTimeGetCurrent()
        let room = try requireRoom(roomId: roomId)
        let t1 = CFAbsoluteTimeGetCurrent()
        let timeline = try await getOrCreateTimeline(room: room)
        let t2 = CFAbsoluteTimeGetCurrent()
        print("[CapMatrix] [PERF] getRoomMessages(\(roomId.prefix(12))…) requireRoom=\(ms(t0,t1))ms getOrCreateTimeline=\(ms(t1,t2))ms")

        // Suppress live listener while we paginate to avoid flooding JS with historical events
        paginatingLock.lock()
        paginatingRooms.insert(roomId)
        paginatingLock.unlock()

        let collector = TimelineItemCollector(roomId: roomId)
        let t3 = CFAbsoluteTimeGetCurrent()
        let handle = await timeline.addListener(listener: collector)
        let t4 = CFAbsoluteTimeGetCurrent()
        print("[CapMatrix] [PERF] addListener=\(ms(t3,t4))ms")

        var hitStart = false
        // Wait for the initial Reset snapshot before paginating
        let tWait1 = CFAbsoluteTimeGetCurrent()
        let gotInitial = await collector.waitForUpdate(timeoutNanos: 5_000_000_000)
        let tWait1Done = CFAbsoluteTimeGetCurrent()
        let countBefore = collector.events.count
        let isPagination = from != nil
        print("[CapMatrix] [PERF] waitForInitial=\(ms(tWait1,tWait1Done))ms gotInitial=\(gotInitial) items=\(countBefore) from=\(from ?? "nil")")

        // Reset cursor on initial load
        if !isPagination {
            paginatingLock.lock()
            oldestReturnedEventId.removeValue(forKey: roomId)
            paginatingLock.unlock()
        }

        // Paginate when: first load with too few items, OR explicit pagination request
        if isPagination || countBefore < limit {
            do {
                let tPag = CFAbsoluteTimeGetCurrent()
                hitStart = try await timeline.paginateBackwards(numEvents: UInt16(limit))
                let tPagDone = CFAbsoluteTimeGetCurrent()
                print("[CapMatrix] [PERF] paginateBackwards=\(ms(tPag,tPagDone))ms hitStart=\(hitStart)")

                // Always wait for diffs — even when hitStart=true, the final batch's
                // events arrive asynchronously via PushFront/Insert diffs
                let tWait2 = CFAbsoluteTimeGetCurrent()
                _ = await collector.waitForUpdate(timeoutNanos: 5_000_000_000)
                let tWait2Done = CFAbsoluteTimeGetCurrent()
                print("[CapMatrix] [PERF] waitForPagination=\(ms(tWait2,tWait2Done))ms items=\(collector.events.count) hitStart=\(hitStart)")
            } catch {
                // Pagination failed (e.g. expired token) — fall through and
                // return whatever events were already collected from cache.
                print("[CapMatrix] getRoomMessages: paginateBackwards failed, returning cached events: \(error)")
                hitStart = true
            }
        }

        handle.cancel()
        paginatingLock.lock()
        paginatingRooms.remove(roomId)
        paginatingLock.unlock()

        let tSlice = CFAbsoluteTimeGetCurrent()
        let allEvents = collector.events
        var events: [[String: Any]]

        paginatingLock.lock()
        let cursorId = oldestReturnedEventId[roomId]
        paginatingLock.unlock()

        if let cursorId = cursorId, from != nil {
            if let cursorIdx = allEvents.firstIndex(where: { ($0["eventId"] as? String) == cursorId }) {
                let available = Array(allEvents.prefix(cursorIdx))
                events = Array(available.suffix(limit))
            } else {
                print("[CapMatrix] getRoomMessages: cursor eventId \(cursorId) not found in timeline")
                events = []
            }
        } else {
            events = Array(allEvents.suffix(limit))
        }

        if let oldest = events.first, let eid = oldest["eventId"] as? String {
            paginatingLock.lock()
            oldestReturnedEventId[roomId] = eid
            paginatingLock.unlock()
        }

        // Apply receipt watermark
        let myUserId = client.flatMap({ try? $0.userId() })
        var watermarkReadBy: [String]? = nil
        var watermarkIndex = -1
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
        let tSliceDone = CFAbsoluteTimeGetCurrent()

        let nextBatch: String? = (hitStart || events.isEmpty) ? nil : "more"

        print("[CapMatrix] [PERF] getRoomMessages TOTAL=\(ms(t0,tSliceDone))ms slicing+watermark=\(ms(tSlice,tSliceDone))ms returning \(events.count) events")

        return [
            "events": events,
            "nextBatch": nextBatch as Any
        ]
    }

    private func ms(_ start: CFAbsoluteTime, _ end: CFAbsoluteTime) -> Int {
        return Int((end - start) * 1000)
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
        let timeline = try await getOrCreateTimeline(room: room)
        try await timeline.redactEvent(eventOrTransactionId: .eventId(eventId: eventId), reason: reason)
    }

    func sendReaction(roomId: String, eventId: String, key: String) async throws {
        let room = try requireRoom(roomId: roomId)
        let timeline = try await getOrCreateTimeline(room: room)
        _ = try await timeline.toggleReaction(itemId: .eventId(eventId: eventId), key: key)
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

    func getThumbnailUrl(mxcUrl: String, width: Int, height: Int, method: String) throws -> String {
        guard let session = sessionStore.load() else {
            throw MatrixBridgeError.notLoggedIn
        }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        let mxcPath = mxcUrl.replacingOccurrences(of: "mxc://", with: "")
        return "\(baseUrl)/_matrix/client/v1/media/thumbnail/\(mxcPath)?width=\(width)&height=\(height)&method=\(method)&access_token=\(session.accessToken)"
    }

    // MARK: - Content Upload

    func uploadContent(fileUri: String, fileName: String, mimeType: String) async throws -> String {
        guard let session = sessionStore.load() else {
            throw MatrixBridgeError.notLoggedIn
        }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        let encodedFileName = fileName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? fileName
        let urlString = "\(baseUrl)/_matrix/media/v3/upload?filename=\(encodedFileName)"
        guard let url = URL(string: urlString) else {
            throw MatrixBridgeError.notSupported("Invalid upload URL")
        }

        // Read file data from URI
        let fileData: Data
        if fileUri.hasPrefix("file://"), let fileUrl = URL(string: fileUri) {
            fileData = try Data(contentsOf: fileUrl)
        } else {
            let fileUrl = URL(fileURLWithPath: fileUri)
            fileData = try Data(contentsOf: fileUrl)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        request.httpBody = fileData

        let (data, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard statusCode >= 200 && statusCode < 300 else {
            throw MatrixBridgeError.notSupported("Upload failed with status \(statusCode)")
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let contentUri = json["content_uri"] as? String else {
            throw MatrixBridgeError.notSupported("Invalid upload response")
        }
        return contentUri
    }

    // MARK: - Devices

    func getDevices() async throws -> [[String: Any]] {
        guard let session = sessionStore.load() else {
            throw MatrixBridgeError.notLoggedIn
        }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        let urlString = "\(baseUrl)/_matrix/client/v3/devices"
        guard let url = URL(string: urlString) else {
            throw MatrixBridgeError.notSupported("Invalid devices URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard statusCode >= 200 && statusCode < 300 else {
            throw MatrixBridgeError.notSupported("getDevices failed with status \(statusCode)")
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let devicesArray = json["devices"] as? [[String: Any]] else {
            throw MatrixBridgeError.notSupported("Invalid devices response")
        }

        let crossSignedDeviceIds = await getCrossSignedDeviceIds(
            baseUrl: baseUrl,
            accessToken: session.accessToken,
            userId: session.userId
        )

        return devicesArray.map { device in
            let deviceId = device["device_id"] as? String ?? ""
            return [
                "deviceId": deviceId,
                "displayName": device["display_name"] as Any,
                "lastSeenTs": device["last_seen_ts"] as Any,
                "lastSeenIp": device["last_seen_ip"] as Any,
                "isCrossSigningVerified": crossSignedDeviceIds.contains(deviceId),
            ]
        }
    }

    private func getCrossSignedDeviceIds(
        baseUrl: String,
        accessToken: String,
        userId: String
    ) async -> Set<String> {
        guard let url = URL(string: "\(baseUrl)/_matrix/client/v3/keys/query") else {
            return []
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: Any] = ["device_keys": [userId: [] as [String]]]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let statusCode = (response as? HTTPURLResponse)?.statusCode,
              statusCode >= 200, statusCode < 300,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return []
        }

        guard let selfSigningKeys = json["self_signing_keys"] as? [String: Any],
              let userSSK = selfSigningKeys[userId] as? [String: Any],
              let sskKeys = userSSK["keys"] as? [String: String] else {
            return []
        }
        let selfSigningKeyIds = Set(sskKeys.keys)

        guard let deviceKeysMap = json["device_keys"] as? [String: Any],
              let userDevices = deviceKeysMap[userId] as? [String: Any] else {
            return []
        }

        var verifiedIds = Set<String>()
        for (deviceId, deviceData) in userDevices {
            guard let deviceDict = deviceData as? [String: Any],
                  let signatures = deviceDict["signatures"] as? [String: Any],
                  let userSignatures = signatures[userId] as? [String: String] else {
                continue
            }
            let signatureKeyIds = Set(userSignatures.keys)
            if !signatureKeyIds.isDisjoint(with: selfSigningKeyIds) {
                verifiedIds.insert(deviceId)
            }
        }
        return verifiedIds
    }

    func deleteDevice(deviceId: String) async throws {
        guard let session = sessionStore.load() else {
            throw MatrixBridgeError.notLoggedIn
        }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        let urlString = "\(baseUrl)/_matrix/client/v3/devices/\(deviceId)"
        guard let url = URL(string: urlString) else {
            throw MatrixBridgeError.notSupported("Invalid device URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = "{}".data(using: .utf8)

        let (_, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        // 401 means UIA is required - for now we just throw
        guard statusCode >= 200 && statusCode < 300 else {
            throw MatrixBridgeError.notSupported("deleteDevice failed with status \(statusCode)")
        }
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

        // recoveryState reflects the LOCAL device's state (.disabled on a returning
        // device that hasn't recovered yet).  To decide whether encryption was
        // set up server-side we also check if a backup exists on the server.
        let ssReady: Bool
        if recoveryState == .enabled {
            ssReady = true
        } else {
            ssReady = (try? await enc.backupExistsOnServer()) ?? false
        }

        return [
            "isCrossSigningReady": isVerified,
            "crossSigningStatus": [
                "hasMaster": isVerified,
                "hasSelfSigning": isVerified,
                "hasUserSigning": isVerified,
                "isReady": isVerified,
            ],
            "isKeyBackupEnabled": isBackupEnabled,
            "isSecretStorageReady": ssReady,
        ]
    }

    func bootstrapCrossSigning() async throws {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        await c.encryption().waitForE2eeInitializationTasks()
    }

    /// Cross-signs the given device using the current cross-signing keys.
    /// After `recoverAndSetup` (which calls `recover` + `waitForE2eeInitializationTasks`),
    /// the SDK should already have cross-signed this device. This method ensures
    /// the E2EE initialization is complete and then resolves.
    func verifyDevice(deviceId: String) async throws {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        let enc = c.encryption()
        // Ensure cross-signing keys are fully imported and the device is signed
        await enc.waitForE2eeInitializationTasks()
        print("[CapMatrix] verifyDevice(\(deviceId)) — verificationState: \(enc.verificationState())")
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
            passphrase: passphrase,
            progressListener: listener
        )
        return ["recoveryKey": key]
    }

    func isRecoveryEnabled() async throws -> Bool {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }
        return c.encryption().recoveryState() == .enabled
    }

    func recoverAndSetup(recoveryKey: String?, passphrase: String?) async throws {
        guard let c = client else { throw MatrixBridgeError.notLoggedIn }

        let key: String
        if let rk = recoveryKey {
            key = rk
        } else if let pp = passphrase {
            key = try await deriveRecoveryKeyFromPassphrase(client: c, passphrase: pp)
        } else {
            throw MatrixBridgeError.missingParameter("recoveryKey or passphrase")
        }

        let enc = c.encryption()
        try await enc.recover(recoveryKey: key)

        // Wait for the SDK to finish importing cross-signing keys and
        // verifying the current device after recovery.
        await enc.waitForE2eeInitializationTasks()

        // Enable key backup if not already active
        if enc.backupState() != .enabled {
            try? await enc.enableBackups()
        }
    }

    // MARK: - Passphrase → recovery key derivation

    /// Fetch the SSSS default key's PBKDF2 params from account data
    /// and derive the recovery key from a passphrase.
    private func deriveRecoveryKeyFromPassphrase(client c: Client, passphrase: String) async throws -> String {
        // 1. Get the default key ID
        guard let defaultKeyJson = try await c.accountData(eventType: "m.secret_storage.default_key"),
              let defaultKeyData = defaultKeyJson.data(using: .utf8),
              let defaultKeyDict = try JSONSerialization.jsonObject(with: defaultKeyData) as? [String: Any],
              let keyId = defaultKeyDict["key"] as? String else {
            throw MatrixBridgeError.custom("No default secret storage key found")
        }

        // 2. Get the key info (contains PBKDF2 params)
        guard let keyInfoJson = try await c.accountData(eventType: "m.secret_storage.key.\(keyId)"),
              let keyInfoData = keyInfoJson.data(using: .utf8),
              let keyInfoDict = try JSONSerialization.jsonObject(with: keyInfoData) as? [String: Any],
              let ppDict = keyInfoDict["passphrase"] as? [String: Any],
              let salt = ppDict["salt"] as? String,
              let iterations = ppDict["iterations"] as? Int else {
            throw MatrixBridgeError.custom("Secret storage key has no passphrase params — use recovery key instead")
        }
        let bits = (ppDict["bits"] as? Int) ?? 256

        // 3. PBKDF2-SHA-512 derivation
        let derivedBytes = try pbkdf2SHA512(
            passphrase: passphrase,
            salt: salt,
            iterations: iterations,
            keyLengthBytes: bits / 8
        )

        // 4. Encode as Matrix recovery key (base58 with 0x8b01 prefix + parity)
        return encodeRecoveryKey(derivedBytes)
    }

    private func pbkdf2SHA512(passphrase: String, salt: String, iterations: Int, keyLengthBytes: Int) throws -> Data {
        guard let passData = passphrase.data(using: .utf8),
              let saltData = salt.data(using: .utf8) else {
            throw MatrixBridgeError.custom("Failed to encode passphrase/salt as UTF-8")
        }

        var derivedKey = Data(count: keyLengthBytes)
        let status = derivedKey.withUnsafeMutableBytes { derivedKeyPtr in
            passData.withUnsafeBytes { passPtr in
                saltData.withUnsafeBytes { saltPtr in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passPtr.baseAddress?.assumingMemoryBound(to: Int8.self),
                        passData.count,
                        saltPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        saltData.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA512),
                        UInt32(iterations),
                        derivedKeyPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        keyLengthBytes
                    )
                }
            }
        }

        guard status == kCCSuccess else {
            throw MatrixBridgeError.custom("PBKDF2 derivation failed with status \(status)")
        }

        return derivedKey
    }

    /// Encode raw key bytes as a Matrix recovery key (base58 with 0x8b01 prefix + parity byte).
    private func encodeRecoveryKey(_ keyData: Data) -> String {
        let prefix: [UInt8] = [0x8b, 0x01]
        var buf = Data(prefix) + keyData
        // Calculate parity (XOR of all bytes)
        var parity: UInt8 = 0
        for byte in buf { parity ^= byte }
        buf.append(parity)
        // Base58 encode and insert spaces every 4 chars
        let encoded = base58Encode(buf)
        var spaced = ""
        for (i, ch) in encoded.enumerated() {
            if i > 0 && i % 4 == 0 { spaced.append(" ") }
            spaced.append(ch)
        }
        return spaced
    }

    private static let base58Alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

    private func base58Encode(_ data: Data) -> String {
        var bytes = Array(data)
        var result: [Character] = []

        while !bytes.isEmpty {
            var carry = 0
            var newBytes: [UInt8] = []
            for byte in bytes {
                carry = carry * 256 + Int(byte)
                if !newBytes.isEmpty || carry / 58 > 0 {
                    newBytes.append(UInt8(carry / 58))
                }
                carry %= 58
            }
            result.append(Self.base58Alphabet[carry])
            bytes = newBytes
        }

        // Preserve leading zeros
        for byte in data {
            if byte != 0 { break }
            result.append(Self.base58Alphabet[0])
        }

        return String(result.reversed())
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

    // MARK: - Presence

    func setPresence(presence: String, statusMsg: String?) async throws {
        guard let session = sessionStore.load() else {
            throw MatrixBridgeError.notLoggedIn
        }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        let encodedUserId = session.userId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? session.userId
        let urlString = "\(baseUrl)/_matrix/client/v3/presence/\(encodedUserId)/status"
        guard let url = URL(string: urlString) else {
            throw MatrixBridgeError.notSupported("Invalid presence URL")
        }

        var body: [String: Any] = ["presence": presence]
        if let msg = statusMsg { body["status_msg"] = msg }
        let bodyData = try JSONSerialization.data(withJSONObject: body)

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = bodyData

        let (_, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard statusCode >= 200 && statusCode < 300 else {
            throw MatrixBridgeError.notSupported("setPresence failed with status \(statusCode)")
        }
    }

    func getPresence(userId: String) async throws -> [String: Any] {
        guard let session = sessionStore.load() else {
            throw MatrixBridgeError.notLoggedIn
        }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        let encodedUserId = userId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? userId
        let urlString = "\(baseUrl)/_matrix/client/v3/presence/\(encodedUserId)/status"
        guard let url = URL(string: urlString) else {
            throw MatrixBridgeError.notSupported("Invalid presence URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard statusCode >= 200 && statusCode < 300 else {
            throw MatrixBridgeError.notSupported("getPresence failed with status \(statusCode)")
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MatrixBridgeError.notSupported("Invalid presence response")
        }

        var result: [String: Any] = ["presence": json["presence"] as? String ?? "offline"]
        if let msg = json["status_msg"] as? String { result["statusMsg"] = msg }
        if let ago = json["last_active_ago"] as? Int { result["lastActiveAgo"] = ago }
        return result
    }

    // MARK: - Pushers

    func setPusher(
        pushkey: String,
        kind: String?,
        appId: String,
        appDisplayName: String,
        deviceDisplayName: String,
        lang: String,
        dataUrl: String,
        dataFormat: String?
    ) async throws {
        guard let session = sessionStore.load() else {
            throw MatrixBridgeError.notLoggedIn
        }
        let baseUrl = session.homeserverUrl.hasSuffix("/")
            ? String(session.homeserverUrl.dropLast())
            : session.homeserverUrl
        let urlString = "\(baseUrl)/_matrix/client/v3/pushers/set"
        guard let url = URL(string: urlString) else {
            throw MatrixBridgeError.notSupported("Invalid pushers URL")
        }

        var dataObj: [String: Any] = ["url": dataUrl]
        if let format = dataFormat { dataObj["format"] = format }

        var body: [String: Any] = [
            "pushkey": pushkey,
            "kind": kind as Any,
            "app_id": appId,
            "app_display_name": appDisplayName,
            "device_display_name": deviceDisplayName,
            "lang": lang,
            "data": dataObj,
        ]
        if kind == nil { body["kind"] = NSNull() }

        let bodyData = try JSONSerialization.data(withJSONObject: body)

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = bodyData

        let (_, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard statusCode >= 200 && statusCode < 300 else {
            throw MatrixBridgeError.notSupported("setPusher failed with status \(statusCode)")
        }
    }

    // MARK: - Helpers

    /// Returns true if this room was in the local cache at login time but is absent
    /// from the server's /joined_rooms list — i.e. it is a stale left room.
    private func isStaleRoom(roomId: String) -> Bool {
        guard let serverIds = serverJoinedRoomIds,
              let cachedIds = cachedRoomIds else { return false }
        return cachedIds.contains(roomId) && !serverIds.contains(roomId)
    }

    /// Fetches the caller's current joined room IDs from the homeserver.
    private func fetchJoinedRoomIds(homeserverUrl: String, accessToken: String) async -> Set<String>? {
        let baseUrl = homeserverUrl.hasSuffix("/") ? String(homeserverUrl.dropLast()) : homeserverUrl
        guard let url = URL(string: "\(baseUrl)/_matrix/client/v3/joined_rooms") else { return nil }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rooms = json["joined_rooms"] as? [String] else { return nil }
        return Set(rooms)
    }

    private static func dataDirectory() -> String {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("matrix_sdk")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    /// Separate cache directory for sliding sync state.
    /// Pass `clearFirst: true` only for fresh logins to wipe any previous session's data.
    /// Session restores (jwtLogin, updateAccessToken) preserve the cache so the
    /// Rust SDK resumes incrementally; stale left rooms are filtered by membership check
    /// in getRooms() and subscribeToRoomTimelines() rather than by clearing the cache.
    private static func cacheDirectory(clearFirst: Bool = false) -> String {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("matrix_sdk_cache")
        if clearFirst {
            try? FileManager.default.removeItem(at: dir)
        }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    static func serializeRoom(_ room: Room) async throws -> [String: Any] {
        let info = try await room.roomInfo()
        let encrypted = await room.isEncrypted()
        let membership: String = {
            switch room.membership() {
            case .joined: return "join"
            case .invited: return "invite"
            case .left: return "leave"
            @unknown default: return "join"
            }
        }()
        let isDirect = info.isDirect
        let avatarUrl: String? = nil // Rust SDK doesn't expose avatar URL via RoomInfo yet

        let latestEvent = await room.latestEvent()
        let latestEventDict = serializeLatestEventValue(latestEvent, roomId: room.id())

        var dict: [String: Any] = [
            "roomId": room.id(),
            "name": info.displayName ?? "",
            "topic": info.topic as Any,
            "memberCount": info.joinedMembersCount ?? 0,
            "isEncrypted": encrypted,
            "unreadCount": info.numUnreadMessages ?? 0,
            "lastEventTs": latestEventDict?["originServerTs"] as Any,
            "membership": membership,
            "avatarUrl": avatarUrl as Any,
            "isDirect": isDirect,
        ]
        if let le = latestEventDict {
            dict["latestEvent"] = le
        }
        return dict
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

/// Serialize a LatestEventValue (from room.latestEvent()) into a lightweight dictionary
/// for last-message previews. Does NOT create a timeline subscription.
private func serializeLatestEventValue(_ value: LatestEventValue, roomId: String) -> [String: Any]? {
    let timestamp: UInt64
    let sender: String
    let profile: ProfileDetails
    let content: TimelineItemContent

    switch value {
    case .none:
        return nil
    case .remote(let ts, let s, _, let p, let c):
        timestamp = ts
        sender = s
        profile = p
        content = c
    case .local(let ts, let s, let p, let c, _):
        timestamp = ts
        sender = s
        profile = p
        content = c
    @unknown default:
        return nil
    }

    var contentDict: [String: Any] = [:]
    var eventType = "m.room.message"

    switch content {
    case .msgLike(let msgLikeContent):
        switch msgLikeContent.kind {
        case .message(let messageContent):
            contentDict["body"] = messageContent.body
            switch messageContent.msgType {
            case .text:
                contentDict["msgtype"] = "m.text"
            case .image:
                contentDict["msgtype"] = "m.image"
            case .file:
                contentDict["msgtype"] = "m.file"
            case .audio:
                contentDict["msgtype"] = "m.audio"
            case .video:
                contentDict["msgtype"] = "m.video"
            case .emote:
                contentDict["msgtype"] = "m.emote"
            case .notice:
                contentDict["msgtype"] = "m.notice"
            default:
                contentDict["msgtype"] = "m.text"
            }
        case .unableToDecrypt:
            contentDict["body"] = "Unable to decrypt message"
            contentDict["msgtype"] = "m.text"
            contentDict["encrypted"] = true
        case .redacted:
            eventType = "m.room.redaction"
            contentDict["body"] = "Message deleted"
        default:
            eventType = "m.room.unknown"
        }
    default:
        eventType = "m.room.unknown"
    }

    var senderDisplayName: String? = nil
    if case .ready(let displayName, _, _) = profile {
        senderDisplayName = displayName
    }

    var dict: [String: Any] = [
        "roomId": roomId,
        "senderId": sender,
        "type": eventType,
        "content": contentDict,
        "originServerTs": timestamp,
    ]
    if let name = senderDisplayName {
        dict["senderDisplayName"] = name
    }
    return dict
}

/// Extract the event ID string from an EventOrTransactionId enum.
private func extractEventId(_ eventOrTxnId: EventOrTransactionId) -> String? {
    switch eventOrTxnId {
    case .eventId(let eventId):
        return eventId
    case .transactionId(let transactionId):
        return transactionId
    @unknown default:
        return nil
    }
}

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
    guard let eventId = extractEventId(eventItem.eventOrTransactionId) else {
        return nil
    }

    var contentDict: [String: Any] = [:]
    var eventType = "m.room.message"
    var stateKey: String? = nil

    let content = eventItem.content
    switch content {
    case .msgLike(let msgLikeContent):
        switch msgLikeContent.kind {
        case .message(let messageContent):
            contentDict["body"] = messageContent.body
            switch messageContent.msgType {
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
        case .unableToDecrypt:
            contentDict["body"] = "Unable to decrypt message"
            contentDict["msgtype"] = "m.text"
            contentDict["encrypted"] = true
        case .redacted:
            eventType = "m.room.redaction"
            contentDict["body"] = "Message deleted"
        default:
            eventType = "m.room.unknown"
        }

        // Reactions from MsgLikeContent
        let reactions = msgLikeContent.reactions
        if !reactions.isEmpty {
            contentDict["reactions"] = reactions.map { r in
                [
                    "key": r.key,
                    "count": r.senders.count,
                    "senders": r.senders.map { $0.senderId },
                ] as [String: Any]
            }
        }
    case .roomMembership(let userId, let userDisplayName, let change, _):
        eventType = "m.room.member"
        stateKey = userId
        let membership: String
        switch change {
        case .joined, .invitationAccepted:
            membership = "join"
        case .left:
            membership = "leave"
        case .banned, .kickedAndBanned:
            membership = "ban"
        case .invited:
            membership = "invite"
        case .kicked:
            membership = "leave"
        case .unbanned:
            membership = "leave"
        default:
            membership = "join"
        }
        contentDict["membership"] = membership
        contentDict["displayname"] = userDisplayName ?? userId
    case .state(let sk, let stateContent):
        stateKey = sk
        switch stateContent {
        case .roomCreate:
            eventType = "m.room.create"
        default:
            eventType = "m.room.unknown"
        }
    default:
        eventType = "m.room.unknown"
    }

    // Delivery/read status
    var status: String = "sent"
    if let sendState = eventItem.localSendState {
        switch sendState {
        case .notSentYet:
            status = "sending"
        case .sendingFailed:
            status = "sending"
        case .sent:
            // Check read receipts below
            break
        default:
            break
        }
    }

    var readBy: [String]? = nil
    let receipts = eventItem.readReceipts
    if !receipts.isEmpty {
        print("[CapMatrix] readReceipts for \(eventId): \(receipts.keys) sender=\(eventItem.sender)")
    }
    if status == "sent" {
        let others = receipts.keys.filter { $0 != eventItem.sender }
        if !others.isEmpty {
            status = "read"
            readBy = Array(others)
        }
    }

    // Build unsigned dict — include transaction_id when available
    var unsignedDict: [String: Any]? = nil
    if case .transactionId(let txnId) = eventItem.eventOrTransactionId {
        unsignedDict = ["transaction_id": txnId]
    }

    var result: [String: Any] = [
        "eventId": eventId,
        "roomId": roomId,
        "senderId": eventItem.sender,
        "type": eventType,
        "content": contentDict,
        "originServerTs": eventItem.timestamp,
        "status": status,
        "readBy": readBy as Any,
    ]
    if let sk = stateKey {
        result["stateKey"] = sk
    }
    if let ud = unsignedDict {
        result["unsigned"] = ud
    }
    return result
}

// MARK: - Live Timeline Listener (for sync subscriptions)

class LiveTimelineListener: TimelineListener {
    private let roomId: String
    private let room: Room
    private let onMessage: ([String: Any]) -> Void
    private let onRoomUpdate: (String, [String: Any]) -> Void
    private let isPaginating: () -> Bool

    init(roomId: String, room: Room, onMessage: @escaping ([String: Any]) -> Void, onRoomUpdate: @escaping (String, [String: Any]) -> Void, isPaginating: @escaping () -> Bool) {
        self.roomId = roomId
        self.room = room
        self.onMessage = onMessage
        self.onRoomUpdate = onRoomUpdate
        self.isPaginating = isPaginating
    }

    /// Emit a room update with full room summary.
    private func emitRoomUpdate() {
        Task {
            let summary: [String: Any]
            if let s = try? await MatrixSDKBridge.serializeRoom(room) {
                summary = s
            } else {
                summary = ["roomId": roomId]
            }
            onRoomUpdate(roomId, summary)
        }
    }

    func onUpdate(diff: [TimelineDiff]) {
        // Suppress live events while getRoomMessages is paginating this room
        if isPaginating() { return }
        print("[CapMatrix] LiveTimelineListener onUpdate for \(roomId): \(diff.count) diffs")
        for d in diff {
            switch d {
            case .reset(let items):
                print("[CapMatrix]   Reset: \(items.count) items")
                items.forEach { item in
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        print("[CapMatrix]   Reset item: \(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil")")
                        onMessage(event)
                    }
                }
                emitRoomUpdate()
            case .append(let items):
                print("[CapMatrix]   Append: \(items.count) items")
                items.forEach { item in
                    if let event = serializeTimelineItem(item, roomId: roomId) {
                        print("[CapMatrix]   Append item: \(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil")")
                        onMessage(event)
                    }
                }
                emitRoomUpdate()
            case .pushBack(let item):
                let isLocalEcho = item.asEvent().map { extractEventId($0.eventOrTransactionId) } != nil
                    && item.asEvent()?.eventOrTransactionId is EventOrTransactionId
                // Check if this is a local echo (transaction ID, no event ID yet)
                let eventItem = item.asEvent()
                var skipLocalEcho = false
                if let ei = eventItem {
                    if case .transactionId = ei.eventOrTransactionId {
                        skipLocalEcho = true
                    }
                }
                print("[CapMatrix]   PushBack: localEcho=\(skipLocalEcho)")
                if !skipLocalEcho, let event = serializeTimelineItem(item, roomId: roomId) {
                    print("[CapMatrix]   PushBack item: \(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil")")
                    onMessage(event)
                }
                emitRoomUpdate()
            case .pushFront(let item):
                // PushFront = historical events from back-pagination — emit message but no room update
                if let event = serializeTimelineItem(item, roomId: roomId) {
                    print("[CapMatrix]   PushFront item: \(event["eventId"] ?? "nil")")
                    onMessage(event)
                }
            case .set(let index, let item):
                if let event = serializeTimelineItem(item, roomId: roomId) {
                    print("[CapMatrix]   Set item: \(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil") status=\(event["status"] ?? "nil") readBy=\(event["readBy"] ?? "nil")")
                    onMessage(event)
                    // If this event has readBy data, trigger roomUpdated
                    // so the app can refresh receipt status for all messages
                    if let rb = event["readBy"] as? [String], !rb.isEmpty {
                        emitRoomUpdate()
                    }
                }
            case .insert(let index, let item):
                if let event = serializeTimelineItem(item, roomId: roomId) {
                    print("[CapMatrix]   Insert item: \(event["eventId"] ?? "nil")")
                    onMessage(event)
                }
                emitRoomUpdate()
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
            switch d {
            case .reset(let items):
                _items.removeAll()
                _uniqueIdMap.removeAll()
                items.forEach { item in
                    trackUniqueId(item)
                    _items.append(serializeTimelineItem(item, roomId: roomId))
                }
            case .append(let items):
                items.forEach { item in
                    trackUniqueId(item)
                    _items.append(serializeTimelineItem(item, roomId: roomId))
                }
            case .pushBack(let item):
                trackUniqueId(item)
                _items.append(serializeTimelineItem(item, roomId: roomId))
            case .pushFront(let item):
                trackUniqueId(item)
                _items.insert(serializeTimelineItem(item, roomId: roomId), at: 0)
            case .set(let index, let item):
                trackUniqueId(item)
                let idx = Int(index)
                if idx >= 0 && idx < _items.count {
                    _items[idx] = serializeTimelineItem(item, roomId: roomId)
                }
            case .insert(let index, let item):
                trackUniqueId(item)
                let idx = min(Int(index), _items.count)
                _items.insert(serializeTimelineItem(item, roomId: roomId), at: idx)
            case .clear:
                _items.removeAll()
                _uniqueIdMap.removeAll()
            case .remove(let index):
                let idx = Int(index)
                if idx >= 0 && idx < _items.count {
                    _items.remove(at: idx)
                }
            case .truncate(let length):
                let len = Int(length)
                while _items.count > len { _items.removeLast() }
            case .popBack:
                if !_items.isEmpty { _items.removeLast() }
            case .popFront:
                if !_items.isEmpty { _items.removeFirst() }
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
        let uniqueId = item.uniqueId().id
        if let eid = extractEventId(eventItem.eventOrTransactionId) {
            _uniqueIdMap[eid] = uniqueId
        }
    }
}

// MARK: - Errors

enum MatrixBridgeError: LocalizedError {
    case notLoggedIn
    case roomNotFound(String)
    case notSupported(String)
    case missingParameter(String)
    case custom(String)

    var errorDescription: String? {
        switch self {
        case .notLoggedIn:
            return "Not logged in. Call login() or jwtLogin() first."
        case .roomNotFound(let roomId):
            return "Room \(roomId) not found"
        case .notSupported(let method):
            return "\(method) is not supported in this version of the Matrix SDK"
        case .missingParameter(let name):
            return "Missing required parameter: \(name)"
        case .custom(let message):
            return message
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
