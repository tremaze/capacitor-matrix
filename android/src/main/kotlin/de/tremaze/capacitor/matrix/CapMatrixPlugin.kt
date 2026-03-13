package de.tremaze.capacitor.matrix

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CapacitorPlugin(name = "Matrix")
class MatrixPlugin : Plugin() {

    private lateinit var bridge: MatrixSDKBridge
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load() {
        bridge = MatrixSDKBridge(context)
    }

    @PluginMethod
    fun login(call: PluginCall) {
        val homeserverUrl = call.getString("homeserverUrl") ?: return call.reject("Missing homeserverUrl")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val password = call.getString("password") ?: return call.reject("Missing password")

        scope.launch {
            try {
                val session = bridge.login(homeserverUrl, userId, password)
                call.resolve(session.toJSObject())
            } catch (e: Exception) {
                call.reject(e.message ?: "Login failed", e)
            }
        }
    }

    @PluginMethod
    fun loginWithToken(call: PluginCall) {
        val homeserverUrl = call.getString("homeserverUrl") ?: return call.reject("Missing homeserverUrl")
        val accessToken = call.getString("accessToken") ?: return call.reject("Missing accessToken")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val deviceId = call.getString("deviceId") ?: return call.reject("Missing deviceId")

        scope.launch {
            try {
                val session = bridge.loginWithToken(homeserverUrl, accessToken, userId, deviceId)
                call.resolve(session.toJSObject())
            } catch (e: Exception) {
                call.reject(e.message ?: "Login with token failed", e)
            }
        }
    }

    @PluginMethod
    fun logout(call: PluginCall) {
        scope.launch {
            try {
                bridge.logout()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "Logout failed", e)
            }
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
        scope.launch {
            try {
                bridge.startSync(
                    onSyncState = { state ->
                        notifyListeners("syncStateChange", JSObject().put("state", state))
                    },
                    onMessage = { event ->
                        val jsEvent = JSObject()
                        event.forEach { (key, value) -> jsEvent.put(key, value) }
                        notifyListeners("messageReceived", JSObject().put("event", jsEvent))
                    },
                    onRoomUpdate = { roomId, summary ->
                        val jsSummary = JSObject()
                        summary.forEach { (key, value) -> jsSummary.put(key, value) }
                        notifyListeners(
                            "roomUpdated",
                            JSObject().put("roomId", roomId).put("summary", jsSummary),
                        )
                    },
                )
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "startSync failed", e)
            }
        }
    }

    @PluginMethod
    fun stopSync(call: PluginCall) {
        scope.launch {
            try {
                bridge.stopSync()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "stopSync failed", e)
            }
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
            val rooms = bridge.getRooms()
            val jsRooms = JSArray()
            rooms.forEach { room ->
                val jsRoom = JSObject()
                room.forEach { (key, value) -> jsRoom.put(key, value) }
                jsRooms.put(jsRoom)
            }
            call.resolve(JSObject().put("rooms", jsRooms))
        } catch (e: Exception) {
            call.reject(e.message ?: "getRooms failed", e)
        }
    }

    @PluginMethod
    fun getRoomMembers(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")

        scope.launch {
            try {
                val members = bridge.getRoomMembers(roomId)
                val jsMembers = JSArray()
                members.forEach { member ->
                    val jsMember = JSObject()
                    member.forEach { (key, value) -> jsMember.put(key, value) }
                    jsMembers.put(jsMember)
                }
                call.resolve(JSObject().put("members", jsMembers))
            } catch (e: Exception) {
                call.reject(e.message ?: "getRoomMembers failed", e)
            }
        }
    }

    @PluginMethod
    fun joinRoom(call: PluginCall) {
        val roomIdOrAlias = call.getString("roomIdOrAlias") ?: return call.reject("Missing roomIdOrAlias")

        scope.launch {
            try {
                val roomId = bridge.joinRoom(roomIdOrAlias)
                call.resolve(JSObject().put("roomId", roomId))
            } catch (e: Exception) {
                call.reject(e.message ?: "joinRoom failed", e)
            }
        }
    }

    @PluginMethod
    fun leaveRoom(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")

        scope.launch {
            try {
                bridge.leaveRoom(roomId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "leaveRoom failed", e)
            }
        }
    }

    @PluginMethod
    fun sendMessage(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val body = call.getString("body") ?: return call.reject("Missing body")
        val msgtype = call.getString("msgtype") ?: "m.text"

        scope.launch {
            try {
                val eventId = bridge.sendMessage(roomId, body, msgtype)
                call.resolve(JSObject().put("eventId", eventId))
            } catch (e: Exception) {
                call.reject(e.message ?: "sendMessage failed", e)
            }
        }
    }

    @PluginMethod
    fun getRoomMessages(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val limit = call.getInt("limit")
        val from = call.getString("from")

        scope.launch {
            try {
                val result = bridge.getRoomMessages(roomId, limit, from)
                val jsResult = JSObject()
                val jsEvents = JSArray()
                @Suppress("UNCHECKED_CAST")
                (result["events"] as? List<Map<String, Any?>>)?.forEach { event ->
                    val jsEvent = JSObject()
                    event.forEach { (key, value) -> jsEvent.put(key, value) }
                    jsEvents.put(jsEvent)
                }
                jsResult.put("events", jsEvents)
                jsResult.put("nextBatch", result["nextBatch"])
                call.resolve(jsResult)
            } catch (e: Exception) {
                call.reject(e.message ?: "getRoomMessages failed", e)
            }
        }
    }

    @PluginMethod
    fun markRoomAsRead(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId") ?: return call.reject("Missing eventId")

        scope.launch {
            try {
                bridge.markRoomAsRead(roomId, eventId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "markRoomAsRead failed", e)
            }
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
