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

    // MARK: - Auth

    func login(homeserverUrl: String, userId: String, password: String) async throws -> [String: String] {
        let dataDir = Self.dataDirectory()

        let newClient = try await ClientBuilder()
            .homeserverUrl(homeserverUrl: homeserverUrl)
            .sqliteStore(sqliteStoreBuilder: SqliteStoreBuilder(dataPath: dataDir, cachePath: dataDir))
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
        let dataDir = Self.dataDirectory()

        let newClient = try await ClientBuilder()
            .homeserverUrl(homeserverUrl: homeserverUrl)
            .sqliteStore(sqliteStoreBuilder: SqliteStoreBuilder(dataPath: dataDir, cachePath: dataDir))
            .build()

        let session = Session(
            accessToken: accessToken,
            refreshToken: nil,
            userId: userId,
            deviceId: deviceId,
            homeserverUrl: homeserverUrl,
            slidingSyncVersion: nil
        )

        try newClient.restoreSession(session: session)
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
        syncService?.stop()
        syncService = nil
        try await client?.logout()
        client = nil
        sessionStore.clear()
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

        let service = try await c.syncService().finish()
        syncService = service

        let observer = SyncStateObserverProxy(onUpdate: { state in
            onSyncState(Self.mapSyncState(state))
        })
        service.state(listener: observer)

        try await service.start()
    }

    func stopSync() async throws {
        syncService?.stop()
    }

    func getSyncState() -> String {
        return "SYNCING"
    }

    // MARK: - Rooms

    func getRooms() throws -> [[String: Any]] {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        return c.rooms().map { Self.serializeRoom($0) }
    }

    func getRoomMembers(roomId: String) async throws -> [[String: Any]] {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        guard let room = c.getRoom(roomId: roomId) else {
            throw MatrixBridgeError.roomNotFound(roomId)
        }
        let members = try await room.members()
        return members.map { member in
            [
                "userId": member.userId,
                "displayName": member.displayName as Any,
                "membership": String(describing: member.membership).lowercased()
            ]
        }
    }

    func joinRoom(roomIdOrAlias: String) async throws -> String {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        let room = try await c.joinRoomByIdOrAlias(roomIdOrAlias: roomIdOrAlias, serverNames: [])
        return room.id()
    }

    func leaveRoom(roomId: String) async throws {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        guard let room = c.getRoom(roomId: roomId) else {
            throw MatrixBridgeError.roomNotFound(roomId)
        }
        try await room.leave()
    }

    // MARK: - Messaging

    func sendMessage(roomId: String, body: String, msgtype: String) async throws -> String {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        guard let room = c.getRoom(roomId: roomId) else {
            throw MatrixBridgeError.roomNotFound(roomId)
        }
        let timeline = try await room.timeline()
        let content = messageEventContentFromMarkdown(md: body)
        try await timeline.send(msg: content)

        return ""
    }

    func getRoomMessages(roomId: String, limit: Int, from: String?) async throws -> [String: Any] {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        guard let room = c.getRoom(roomId: roomId) else {
            throw MatrixBridgeError.roomNotFound(roomId)
        }
        let timeline = try await room.timeline()
        try await timeline.paginateBackwards(numEvents: UInt16(limit))

        return [
            "events": [] as [[String: Any]],
            "nextBatch": nil as String? as Any
        ]
    }

    func markRoomAsRead(roomId: String, eventId: String) async throws {
        guard let c = client else {
            throw MatrixBridgeError.notLoggedIn
        }
        guard let room = c.getRoom(roomId: roomId) else {
            throw MatrixBridgeError.roomNotFound(roomId)
        }
        let timeline = try await room.timeline()
        try await timeline.markAsRead(receiptType: .read)
    }

    // MARK: - Helpers

    private static func dataDirectory() -> String {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("matrix_sdk")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    private static func serializeRoom(_ room: Room) -> [String: Any] {
        let info = room.roomInfo()
        return [
            "roomId": room.id(),
            "name": info.displayName ?? "",
            "topic": info.topic as Any,
            "memberCount": info.joinedMembersCount ?? 0,
            "isEncrypted": info.isEncrypted,
            "unreadCount": info.numUnreadMessages ?? 0,
            "lastEventTs": nil as Int? as Any
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
        case .offline:
            return "ERROR"
        }
    }
}

// MARK: - Errors

enum MatrixBridgeError: LocalizedError {
    case notLoggedIn
    case roomNotFound(String)

    var errorDescription: String? {
        switch self {
        case .notLoggedIn:
            return "Not logged in. Call login() or loginWithToken() first."
        case .roomNotFound(let roomId):
            return "Room \(roomId) not found"
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
