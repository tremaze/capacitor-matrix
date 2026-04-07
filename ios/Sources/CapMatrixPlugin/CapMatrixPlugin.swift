import Foundation
import Capacitor

@objc(MatrixPlugin)
public class MatrixPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MatrixPlugin"
    public let jsName = "Matrix"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "login", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "jwtLogin", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "logout", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSession", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startSync", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopSync", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSyncState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRooms", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRoomMembers", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "joinRoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "leaveRoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sendMessage", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRoomMessages", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "markRoomAsRead", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createRoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "initializeCrypto", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getEncryptionStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "bootstrapCrossSigning", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setupKeyBackup", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getKeyBackupStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "restoreKeyBackup", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setupRecovery", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isRecoveryEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "recoverAndSetup", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resetRecoveryKey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exportRoomKeys", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "importRoomKeys", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "redactEvent", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sendReaction", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setRoomName", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setRoomTopic", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "inviteUser", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "kickUser", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "banUser", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unbanUser", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "searchUsers", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sendTyping", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getMediaUrl", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPresence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPresence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "refreshEventStatuses", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "forgetRoom", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "editMessage", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sendReply", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setRoomAvatar", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "uploadContent", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getThumbnailUrl", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDevices", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteDevice", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPusher", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "verifyDevice", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearAllData", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateAccessToken", returnType: CAPPluginReturnPromise),
    ]

    private let matrixBridge = MatrixSDKBridge()

    @objc func login(_ call: CAPPluginCall) {
        guard let homeserverUrl = call.getString("homeserverUrl"),
              let userId = call.getString("userId"),
              let password = call.getString("password") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                let session = try await matrixBridge.login(homeserverUrl: homeserverUrl, userId: userId, password: password)
                call.resolve(session)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func jwtLogin(_ call: CAPPluginCall) {
        guard let homeserverUrl = call.getString("homeserverUrl"),
              let token = call.getString("token") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                let session = try await matrixBridge.jwtLogin(
                    homeserverUrl: homeserverUrl,
                    token: token
                )
                call.resolve(session)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func logout(_ call: CAPPluginCall) {
        Task {
            do {
                try await matrixBridge.logout()
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func clearAllData(_ call: CAPPluginCall) {
        Task {
            await matrixBridge.clearAllData()
            call.resolve()
        }
    }

    @objc func updateAccessToken(_ call: CAPPluginCall) {
        guard let accessToken = call.getString("accessToken") else {
            return call.reject("Missing accessToken")
        }

        Task {
            do {
                try await matrixBridge.updateAccessToken(accessToken: accessToken)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getSession(_ call: CAPPluginCall) {
        if let session = matrixBridge.getSession() {
            call.resolve(session)
        } else {
            call.resolve([:])
        }
    }

    @objc func startSync(_ call: CAPPluginCall) {
        Task {
            do {
                try await matrixBridge.startSync(
                    onSyncState: { [weak self] state in
                        DispatchQueue.main.async {
                            self?.notifyListeners("syncStateChange", data: ["state": state])
                        }
                    },
                    onMessage: { [weak self] event in
                        print("[CapMatrixPlugin] onMessage: eventId=\(event["eventId"] ?? "nil") type=\(event["type"] ?? "nil")")
                        DispatchQueue.main.async {
                            print("[CapMatrixPlugin] notifyListeners messageReceived on main thread")
                            self?.notifyListeners("messageReceived", data: ["event": event])
                        }
                    },
                    onRoomUpdate: { [weak self] roomId, summary in
                        DispatchQueue.main.async {
                            self?.notifyListeners("roomUpdated", data: ["roomId": roomId, "summary": summary])
                        }
                    },
                    onReceipt: { [weak self] roomId, eventId, userId in
                        DispatchQueue.main.async {
                            self?.notifyListeners("receiptReceived", data: ["roomId": roomId, "eventId": eventId, "userId": userId])
                        }
                    },
                    onTyping: { [weak self] roomId, userIds in
                        DispatchQueue.main.async {
                            self?.notifyListeners("typingChanged", data: ["roomId": roomId, "userIds": userIds])
                        }
                    }
                )
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func stopSync(_ call: CAPPluginCall) {
        Task {
            do {
                try await matrixBridge.stopSync()
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getSyncState(_ call: CAPPluginCall) {
        let state = matrixBridge.getSyncState()
        call.resolve(["state": state])
    }

    @objc func getRooms(_ call: CAPPluginCall) {
        Task {
            do {
                let rooms = try await matrixBridge.getRooms()
                call.resolve(["rooms": rooms])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getRoomMembers(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId") else {
            return call.reject("Missing roomId")
        }

        Task {
            do {
                let members = try await matrixBridge.getRoomMembers(roomId: roomId)
                call.resolve(["members": members])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func joinRoom(_ call: CAPPluginCall) {
        guard let roomIdOrAlias = call.getString("roomIdOrAlias") else {
            return call.reject("Missing roomIdOrAlias")
        }

        Task {
            do {
                let roomId = try await matrixBridge.joinRoom(roomIdOrAlias: roomIdOrAlias)
                call.resolve(["roomId": roomId])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func leaveRoom(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId") else {
            return call.reject("Missing roomId")
        }

        Task {
            do {
                try await matrixBridge.leaveRoom(roomId: roomId)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func sendMessage(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let body = call.getString("body") else {
            return call.reject("Missing required parameters")
        }
        let msgtype = call.getString("msgtype") ?? "m.text"
        let fileUri = call.getString("fileUri")
        let fileName = call.getString("fileName")
        let mimeType = call.getString("mimeType")
        let fileSize = call.getInt("fileSize")
        let duration = call.getInt("duration")
        let width = call.getInt("width")
        let height = call.getInt("height")

        Task {
            do {
                let eventId = try await matrixBridge.sendMessage(
                    roomId: roomId, body: body, msgtype: msgtype,
                    fileUri: fileUri, fileName: fileName, mimeType: mimeType,
                    fileSize: fileSize, duration: duration, width: width, height: height
                )
                call.resolve(["eventId": eventId])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getRoomMessages(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId") else {
            return call.reject("Missing roomId")
        }
        let limit = call.getInt("limit") ?? 20
        let from = call.getString("from")

        Task {
            do {
                let result = try await matrixBridge.getRoomMessages(roomId: roomId, limit: limit, from: from)
                call.resolve(result)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func markRoomAsRead(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let eventId = call.getString("eventId") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                try await matrixBridge.markRoomAsRead(roomId: roomId, eventId: eventId)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func refreshEventStatuses(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let eventIds = call.getArray("eventIds") as? [String] else {
            return call.reject("Missing roomId or eventIds")
        }

        Task {
            do {
                let events = try await matrixBridge.refreshEventStatuses(roomId: roomId, eventIds: eventIds)
                call.resolve(["events": events])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func createRoom(_ call: CAPPluginCall) {
        let name = call.getString("name")
        let topic = call.getString("topic")
        let isEncrypted = call.getBool("isEncrypted") ?? false
        let invite = call.getArray("invite") as? [String]
        let isDirect = call.getBool("isDirect") ?? false
        let preset = call.getString("preset")

        Task {
            do {
                let roomId = try await matrixBridge.createRoom(
                    name: name,
                    topic: topic,
                    isEncrypted: isEncrypted,
                    isDirect: isDirect,
                    invite: invite,
                    preset: preset
                )
                call.resolve(["roomId": roomId])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func initializeCrypto(_ call: CAPPluginCall) {
        Task {
            do {
                try await matrixBridge.initializeCrypto()
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getEncryptionStatus(_ call: CAPPluginCall) {
        Task {
            do {
                let status = try await matrixBridge.getEncryptionStatus()
                call.resolve(status)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func bootstrapCrossSigning(_ call: CAPPluginCall) {
        Task {
            do {
                try await matrixBridge.bootstrapCrossSigning()
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func setupKeyBackup(_ call: CAPPluginCall) {
        Task {
            do {
                let result = try await matrixBridge.setupKeyBackup()
                call.resolve(result)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getKeyBackupStatus(_ call: CAPPluginCall) {
        Task {
            do {
                let status = try await matrixBridge.getKeyBackupStatus()
                call.resolve(status)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func restoreKeyBackup(_ call: CAPPluginCall) {
        let recoveryKey = call.getString("recoveryKey")

        Task {
            do {
                let result = try await matrixBridge.restoreKeyBackup(recoveryKey: recoveryKey)
                call.resolve(result)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func setupRecovery(_ call: CAPPluginCall) {
        let passphrase = call.getString("passphrase")
        // existingPassphrase is a web-only hint for SSSS migration; the Rust SDK
        // handles migration internally on iOS. Read and ignore for now.
        _ = call.getString("existingPassphrase")

        Task {
            do {
                let result = try await matrixBridge.setupRecovery(passphrase: passphrase)
                call.resolve(result)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func isRecoveryEnabled(_ call: CAPPluginCall) {
        Task {
            do {
                let enabled = try await matrixBridge.isRecoveryEnabled()
                call.resolve(["enabled": enabled])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func recoverAndSetup(_ call: CAPPluginCall) {
        let recoveryKey = call.getString("recoveryKey")
        let passphrase = call.getString("passphrase")

        guard recoveryKey != nil || passphrase != nil else {
            return call.reject("Missing recoveryKey or passphrase")
        }

        Task {
            do {
                try await matrixBridge.recoverAndSetup(recoveryKey: recoveryKey, passphrase: passphrase)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func resetRecoveryKey(_ call: CAPPluginCall) {
        let passphrase = call.getString("passphrase")

        Task {
            do {
                let result = try await matrixBridge.resetRecoveryKey(passphrase: passphrase)
                call.resolve(result)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func exportRoomKeys(_ call: CAPPluginCall) {
        guard let passphrase = call.getString("passphrase") else {
            return call.reject("Missing passphrase")
        }

        Task {
            do {
                let data = try await matrixBridge.exportRoomKeys(passphrase: passphrase)
                call.resolve(["data": data])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func importRoomKeys(_ call: CAPPluginCall) {
        guard let data = call.getString("data"),
              let passphrase = call.getString("passphrase") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                let count = try await matrixBridge.importRoomKeys(data: data, passphrase: passphrase)
                call.resolve(["importedKeys": count])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func redactEvent(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let eventId = call.getString("eventId") else {
            return call.reject("Missing required parameters")
        }
        let reason = call.getString("reason")

        Task {
            do {
                try await matrixBridge.redactEvent(roomId: roomId, eventId: eventId, reason: reason)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func sendReaction(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let eventId = call.getString("eventId"),
              let key = call.getString("key") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                try await matrixBridge.sendReaction(roomId: roomId, eventId: eventId, key: key)
                call.resolve(["eventId": ""])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func searchUsers(_ call: CAPPluginCall) {
        guard let searchTerm = call.getString("searchTerm") else {
            return call.reject("Missing searchTerm")
        }
        let limit = call.getInt("limit") ?? 10

        Task {
            do {
                let result = try await matrixBridge.searchUsers(searchTerm: searchTerm, limit: limit)
                call.resolve(result)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func setRoomName(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let name = call.getString("name") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                try await matrixBridge.setRoomName(roomId: roomId, name: name)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func setRoomTopic(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let topic = call.getString("topic") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                try await matrixBridge.setRoomTopic(roomId: roomId, topic: topic)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func inviteUser(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let userId = call.getString("userId") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                try await matrixBridge.inviteUser(roomId: roomId, userId: userId)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func kickUser(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let userId = call.getString("userId") else {
            return call.reject("Missing required parameters")
        }
        let reason = call.getString("reason")

        Task {
            do {
                try await matrixBridge.kickUser(roomId: roomId, userId: userId, reason: reason)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func banUser(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let userId = call.getString("userId") else {
            return call.reject("Missing required parameters")
        }
        let reason = call.getString("reason")

        Task {
            do {
                try await matrixBridge.banUser(roomId: roomId, userId: userId, reason: reason)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func unbanUser(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let userId = call.getString("userId") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                try await matrixBridge.unbanUser(roomId: roomId, userId: userId)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func sendTyping(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let isTyping = call.getBool("isTyping") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                try await matrixBridge.sendTyping(roomId: roomId, isTyping: isTyping)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getMediaUrl(_ call: CAPPluginCall) {
        guard let mxcUrl = call.getString("mxcUrl") else {
            return call.reject("Missing mxcUrl")
        }
        do {
            let httpUrl = try matrixBridge.getMediaUrl(mxcUrl: mxcUrl)
            call.resolve(["httpUrl": httpUrl])
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    @objc func setPresence(_ call: CAPPluginCall) {
        guard let presence = call.getString("presence") else {
            return call.reject("Missing presence")
        }
        let statusMsg = call.getString("statusMsg")

        Task {
            do {
                try await matrixBridge.setPresence(presence: presence, statusMsg: statusMsg)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getPresence(_ call: CAPPluginCall) {
        guard let userId = call.getString("userId") else {
            return call.reject("Missing userId")
        }

        Task {
            do {
                let result = try await matrixBridge.getPresence(userId: userId)
                call.resolve(result)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func forgetRoom(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId") else {
            return call.reject("Missing roomId")
        }

        Task {
            do {
                try await matrixBridge.forgetRoom(roomId: roomId)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func editMessage(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let eventId = call.getString("eventId"),
              let newBody = call.getString("newBody") else {
            return call.reject("Missing required parameters")
        }
        // Media edit fields — not yet implemented in the native bridge
        _ = call.getString("msgtype")
        _ = call.getInt("duration")
        _ = call.getInt("width")
        _ = call.getInt("height")

        Task {
            do {
                let resultEventId = try await matrixBridge.editMessage(roomId: roomId, eventId: eventId, newBody: newBody)
                call.resolve(["eventId": resultEventId])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func sendReply(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId"),
              let body = call.getString("body"),
              let replyToEventId = call.getString("replyToEventId") else {
            return call.reject("Missing required parameters")
        }
        let msgtype = call.getString("msgtype") ?? "m.text"
        // Media info fields — consumed by the bridge when media sending is fully implemented
        _ = call.getInt("duration")
        _ = call.getInt("width")
        _ = call.getInt("height")

        Task {
            do {
                let resultEventId = try await matrixBridge.sendReply(roomId: roomId, body: body, replyToEventId: replyToEventId, msgtype: msgtype)
                call.resolve(["eventId": resultEventId])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func setRoomAvatar(_ call: CAPPluginCall) {
        guard let _ = call.getString("roomId"),
              let _ = call.getString("mxcUrl") else {
            return call.reject("Missing required parameters")
        }
        // No-op placeholder: Rust SDK doesn't have direct setAvatar
        call.resolve()
    }

    @objc func uploadContent(_ call: CAPPluginCall) {
        guard let fileUri = call.getString("fileUri"),
              let fileName = call.getString("fileName"),
              let mimeType = call.getString("mimeType") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                let contentUri = try await matrixBridge.uploadContent(fileUri: fileUri, fileName: fileName, mimeType: mimeType)
                call.resolve(["contentUri": contentUri])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getThumbnailUrl(_ call: CAPPluginCall) {
        guard let mxcUrl = call.getString("mxcUrl") else {
            return call.reject("Missing mxcUrl")
        }
        let width = call.getInt("width") ?? 320
        let height = call.getInt("height") ?? 240
        let method = call.getString("method") ?? "scale"

        do {
            let url = try matrixBridge.getThumbnailUrl(mxcUrl: mxcUrl, width: width, height: height, method: method)
            call.resolve(["httpUrl": url])
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    @objc func getDevices(_ call: CAPPluginCall) {
        Task {
            do {
                let devices = try await matrixBridge.getDevices()
                call.resolve(["devices": devices])
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func deleteDevice(_ call: CAPPluginCall) {
        guard let deviceId = call.getString("deviceId") else {
            return call.reject("Missing deviceId")
        }
        let auth = call.getObject("auth")

        Task {
            do {
                try await matrixBridge.deleteDevice(deviceId: deviceId, auth: auth)
                call.resolve()
            } catch let e as UiaRequiredError {
                call.reject("UIA required", "UIA_REQUIRED", nil, e.data)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func verifyDevice(_ call: CAPPluginCall) {
        guard let deviceId = call.getString("deviceId") else {
            return call.reject("Missing deviceId")
        }

        Task {
            do {
                try await matrixBridge.verifyDevice(deviceId: deviceId)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func setPusher(_ call: CAPPluginCall) {
        guard let pushkey = call.getString("pushkey"),
              let appId = call.getString("appId"),
              let appDisplayName = call.getString("appDisplayName"),
              let deviceDisplayName = call.getString("deviceDisplayName"),
              let lang = call.getString("lang"),
              let dataObj = call.getObject("data"),
              let dataUrl = dataObj["url"] as? String else {
            return call.reject("Missing required parameters")
        }
        let kind = call.getString("kind")
        let dataFormat = dataObj["format"] as? String

        Task {
            do {
                try await matrixBridge.setPusher(
                    pushkey: pushkey,
                    kind: kind,
                    appId: appId,
                    appDisplayName: appDisplayName,
                    deviceDisplayName: deviceDisplayName,
                    lang: lang,
                    dataUrl: dataUrl,
                    dataFormat: dataFormat
                )
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }
}
