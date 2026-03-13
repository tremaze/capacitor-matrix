import Foundation

@objc public class MatrixSDKBridge: NSObject {

    @objc public func login(homeserverUrl: String, userId: String, password: String) async throws -> [String: String] {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "login not implemented"])
    }

    @objc public func loginWithToken(homeserverUrl: String, accessToken: String, userId: String, deviceId: String) async throws -> [String: String] {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "loginWithToken not implemented"])
    }

    @objc public func logout() async throws {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "logout not implemented"])
    }

    @objc public func getSession() -> [String: String]? {
        return nil
    }

    public func startSync(
        onSyncState: @escaping (String) -> Void,
        onMessage: @escaping ([String: Any]) -> Void,
        onRoomUpdate: @escaping (String, [String: Any]) -> Void
    ) async throws {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "startSync not implemented"])
    }

    @objc public func stopSync() async throws {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "stopSync not implemented"])
    }

    @objc public func getSyncState() -> String {
        return "STOPPED"
    }

    @objc public func getRooms() throws -> [[String: Any]] {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "getRooms not implemented"])
    }

    @objc public func getRoomMembers(roomId: String) throws -> [[String: Any]] {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "getRoomMembers not implemented"])
    }

    @objc public func joinRoom(roomIdOrAlias: String) async throws -> String {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "joinRoom not implemented"])
    }

    @objc public func leaveRoom(roomId: String) async throws {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "leaveRoom not implemented"])
    }

    @objc public func sendMessage(roomId: String, body: String, msgtype: String) async throws -> String {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "sendMessage not implemented"])
    }

    @objc public func getRoomMessages(roomId: String, limit: Int, from: String?) async throws -> [String: Any] {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "getRoomMessages not implemented"])
    }

    @objc public func markRoomAsRead(roomId: String, eventId: String) async throws {
        throw NSError(domain: "MatrixPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "markRoomAsRead not implemented"])
    }
}
