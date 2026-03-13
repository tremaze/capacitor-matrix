import Foundation
import Capacitor

@objc(MatrixPlugin)
public class MatrixPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MatrixPlugin"
    public let jsName = "Matrix"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "login", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "loginWithToken", returnType: CAPPluginReturnPromise),
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
    ]

    private let bridge = MatrixSDKBridge()

    @objc func login(_ call: CAPPluginCall) {
        guard let homeserverUrl = call.getString("homeserverUrl"),
              let userId = call.getString("userId"),
              let password = call.getString("password") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                let session = try await bridge.login(homeserverUrl: homeserverUrl, userId: userId, password: password)
                call.resolve(session)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func loginWithToken(_ call: CAPPluginCall) {
        guard let homeserverUrl = call.getString("homeserverUrl"),
              let accessToken = call.getString("accessToken"),
              let userId = call.getString("userId"),
              let deviceId = call.getString("deviceId") else {
            return call.reject("Missing required parameters")
        }

        Task {
            do {
                let session = try await bridge.loginWithToken(
                    homeserverUrl: homeserverUrl,
                    accessToken: accessToken,
                    userId: userId,
                    deviceId: deviceId
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
                try await bridge.logout()
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getSession(_ call: CAPPluginCall) {
        if let session = bridge.getSession() {
            call.resolve(session)
        } else {
            call.resolve([:])
        }
    }

    @objc func startSync(_ call: CAPPluginCall) {
        Task {
            do {
                try await bridge.startSync(
                    onSyncState: { [weak self] state in
                        self?.notifyListeners("syncStateChange", data: ["state": state])
                    },
                    onMessage: { [weak self] event in
                        self?.notifyListeners("messageReceived", data: ["event": event])
                    },
                    onRoomUpdate: { [weak self] roomId, summary in
                        self?.notifyListeners("roomUpdated", data: ["roomId": roomId, "summary": summary])
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
                try await bridge.stopSync()
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getSyncState(_ call: CAPPluginCall) {
        let state = bridge.getSyncState()
        call.resolve(["state": state])
    }

    @objc func getRooms(_ call: CAPPluginCall) {
        do {
            let rooms = try bridge.getRooms()
            call.resolve(["rooms": rooms])
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    @objc func getRoomMembers(_ call: CAPPluginCall) {
        guard let roomId = call.getString("roomId") else {
            return call.reject("Missing roomId")
        }

        Task {
            do {
                let members = try await bridge.getRoomMembers(roomId: roomId)
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
                let roomId = try await bridge.joinRoom(roomIdOrAlias: roomIdOrAlias)
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
                try await bridge.leaveRoom(roomId: roomId)
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

        Task {
            do {
                let eventId = try await bridge.sendMessage(roomId: roomId, body: body, msgtype: msgtype)
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
                let result = try await bridge.getRoomMessages(roomId: roomId, limit: limit, from: from)
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
                try await bridge.markRoomAsRead(roomId: roomId, eventId: eventId)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }
}
