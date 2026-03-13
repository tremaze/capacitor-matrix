package de.tremaze.capacitor.matrix

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "Matrix")
class MatrixPlugin : Plugin() {

    private val bridge = MatrixSDKBridge()

    @PluginMethod
    fun login(call: PluginCall) {
        val homeserverUrl = call.getString("homeserverUrl") ?: return call.reject("Missing homeserverUrl")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val password = call.getString("password") ?: return call.reject("Missing password")

        try {
            val session = bridge.login(homeserverUrl, userId, password)
            call.resolve(session.toJSObject())
        } catch (e: Exception) {
            call.reject(e.message ?: "Login failed", e)
        }
    }

    @PluginMethod
    fun loginWithToken(call: PluginCall) {
        val homeserverUrl = call.getString("homeserverUrl") ?: return call.reject("Missing homeserverUrl")
        val accessToken = call.getString("accessToken") ?: return call.reject("Missing accessToken")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val deviceId = call.getString("deviceId") ?: return call.reject("Missing deviceId")

        try {
            val session = bridge.loginWithToken(homeserverUrl, accessToken, userId, deviceId)
            call.resolve(session.toJSObject())
        } catch (e: Exception) {
            call.reject(e.message ?: "Login with token failed", e)
        }
    }

    @PluginMethod
    fun logout(call: PluginCall) {
        try {
            bridge.logout()
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "Logout failed", e)
        }
    }

    @PluginMethod
    fun getSession(call: PluginCall) {
        try {
            val session = bridge.getSession()
            if (session != null) {
                call.resolve(session.toJSObject())
            } else {
                call.resolve(JSObject())
            }
        } catch (e: Exception) {
            call.reject(e.message ?: "getSession failed", e)
        }
    }

    @PluginMethod
    fun startSync(call: PluginCall) {
        try {
            bridge.startSync(
                onSyncState = { state ->
                    notifyListeners("syncStateChange", JSObject().put("state", state))
                },
                onMessage = { event ->
                    notifyListeners("messageReceived", JSObject().put("event", JSObject(event.toString())))
                },
                onRoomUpdate = { roomId, summary ->
                    notifyListeners(
                        "roomUpdated",
                        JSObject().put("roomId", roomId).put("summary", JSObject(summary.toString())),
                    )
                },
            )
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "startSync failed", e)
        }
    }

    @PluginMethod
    fun stopSync(call: PluginCall) {
        try {
            bridge.stopSync()
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "stopSync failed", e)
        }
    }

    @PluginMethod
    fun getSyncState(call: PluginCall) {
        try {
            val state = bridge.getSyncState()
            call.resolve(JSObject().put("state", state))
        } catch (e: Exception) {
            call.reject(e.message ?: "getSyncState failed", e)
        }
    }

    @PluginMethod
    fun getRooms(call: PluginCall) {
        try {
            bridge.getRooms()
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "getRooms failed", e)
        }
    }

    @PluginMethod
    fun getRoomMembers(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")

        try {
            bridge.getRoomMembers(roomId)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "getRoomMembers failed", e)
        }
    }

    @PluginMethod
    fun joinRoom(call: PluginCall) {
        val roomIdOrAlias = call.getString("roomIdOrAlias") ?: return call.reject("Missing roomIdOrAlias")

        try {
            val roomId = bridge.joinRoom(roomIdOrAlias)
            call.resolve(JSObject().put("roomId", roomId))
        } catch (e: Exception) {
            call.reject(e.message ?: "joinRoom failed", e)
        }
    }

    @PluginMethod
    fun leaveRoom(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")

        try {
            bridge.leaveRoom(roomId)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "leaveRoom failed", e)
        }
    }

    @PluginMethod
    fun sendMessage(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val body = call.getString("body") ?: return call.reject("Missing body")
        val msgtype = call.getString("msgtype") ?: "m.text"

        try {
            val eventId = bridge.sendMessage(roomId, body, msgtype)
            call.resolve(JSObject().put("eventId", eventId))
        } catch (e: Exception) {
            call.reject(e.message ?: "sendMessage failed", e)
        }
    }

    @PluginMethod
    fun getRoomMessages(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val limit = call.getInt("limit")
        val from = call.getString("from")

        try {
            bridge.getRoomMessages(roomId, limit, from)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "getRoomMessages failed", e)
        }
    }

    @PluginMethod
    fun markRoomAsRead(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId") ?: return call.reject("Missing eventId")

        try {
            bridge.markRoomAsRead(roomId, eventId)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "markRoomAsRead failed", e)
        }
    }

    private fun SessionInfo.toJSObject(): JSObject {
        return JSObject().apply {
            put("accessToken", accessToken)
            put("userId", userId)
            put("deviceId", deviceId)
            put("homeserverUrl", homeserverUrl)
        }
    }
}
