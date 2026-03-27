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
    fun updateAccessToken(call: PluginCall) {
        val accessToken = call.getString("accessToken") ?: return call.reject("Missing accessToken")

        scope.launch {
            try {
                bridge.updateAccessToken(accessToken)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "updateAccessToken failed", e)
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
    fun clearAllData(call: PluginCall) {
        try {
            bridge.clearAllData()
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "clearAllData failed", e)
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
                        try {
                            val jsEvent = mapToJSObject(event)
                            notifyListeners("messageReceived", JSObject().put("event", jsEvent))
                        } catch (_: Exception) {
                            // ignore serialization errors
                        }
                    },
                    onRoomUpdate = { roomId, summary ->
                        notifyListeners(
                            "roomUpdated",
                            JSObject().put("roomId", roomId).put("summary", mapToJSObject(summary)),
                        )
                    },
                    onReceipt = { roomId, eventId, userId ->
                        notifyListeners(
                            "receiptReceived",
                            JSObject().put("roomId", roomId).put("eventId", eventId).put("userId", userId),
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
        scope.launch {
            try {
                val rooms = bridge.getRooms()
                val jsRooms = JSArray()
                rooms.forEach { room -> jsRooms.put(mapToJSObject(room)) }
                call.resolve(JSObject().put("rooms", jsRooms))
            } catch (e: Exception) {
                call.reject(e.message ?: "getRooms failed", e)
            }
        }
    }

    @PluginMethod
    fun getRoomMembers(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")

        scope.launch {
            try {
                val members = bridge.getRoomMembers(roomId)
                val jsMembers = JSArray()
                members.forEach { member -> jsMembers.put(mapToJSObject(member)) }
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
    fun forgetRoom(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")

        scope.launch {
            try {
                bridge.forgetRoom(roomId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "forgetRoom failed", e)
            }
        }
    }

    @PluginMethod
    fun sendMessage(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val body = call.getString("body") ?: return call.reject("Missing body")
        val msgtype = call.getString("msgtype") ?: "m.text"
        // Media info fields — consumed by the bridge when media sending is fully implemented
        @Suppress("UNUSED_VARIABLE") val duration = call.getInt("duration")
        @Suppress("UNUSED_VARIABLE") val width = call.getInt("width")
        @Suppress("UNUSED_VARIABLE") val height = call.getInt("height")

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
    fun editMessage(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId") ?: return call.reject("Missing eventId")
        val newBody = call.getString("newBody") ?: return call.reject("Missing newBody")
        // Media edit fields — not yet implemented in the native bridge
        @Suppress("UNUSED_VARIABLE") val msgtype = call.getString("msgtype")
        @Suppress("UNUSED_VARIABLE") val duration = call.getInt("duration")
        @Suppress("UNUSED_VARIABLE") val width = call.getInt("width")
        @Suppress("UNUSED_VARIABLE") val height = call.getInt("height")

        scope.launch {
            try {
                val resultEventId = bridge.editMessage(roomId, eventId, newBody)
                call.resolve(JSObject().put("eventId", resultEventId))
            } catch (e: Exception) {
                call.reject(e.message ?: "editMessage failed", e)
            }
        }
    }

    @PluginMethod
    fun sendReply(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val body = call.getString("body") ?: return call.reject("Missing body")
        val replyToEventId = call.getString("replyToEventId") ?: return call.reject("Missing replyToEventId")
        val msgtype = call.getString("msgtype") ?: "m.text"
        // Media info fields — consumed by the bridge when media sending is fully implemented
        @Suppress("UNUSED_VARIABLE") val duration = call.getInt("duration")
        @Suppress("UNUSED_VARIABLE") val width = call.getInt("width")
        @Suppress("UNUSED_VARIABLE") val height = call.getInt("height")

        scope.launch {
            try {
                val resultEventId = bridge.sendReply(roomId, body, replyToEventId, msgtype)
                call.resolve(JSObject().put("eventId", resultEventId))
            } catch (e: Exception) {
                call.reject(e.message ?: "sendReply failed", e)
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
                    jsEvents.put(mapToJSObject(event))
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

    @PluginMethod
    fun refreshEventStatuses(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val eventIdsArray = call.getArray("eventIds") ?: return call.reject("Missing eventIds")
        val eventIds = (0 until eventIdsArray.length()).map { eventIdsArray.getString(it) }

        scope.launch {
            try {
                val events = bridge.refreshEventStatuses(roomId, eventIds)
                val jsResult = JSObject()
                val jsEvents = JSArray()
                events.forEach { event -> jsEvents.put(mapToJSObject(event)) }
                jsResult.put("events", jsEvents)
                call.resolve(jsResult)
            } catch (e: Exception) {
                call.reject(e.message ?: "refreshEventStatuses failed", e)
            }
        }
    }

    @PluginMethod
    fun createRoom(call: PluginCall) {
        val name = call.getString("name")
        val topic = call.getString("topic")
        val isEncrypted = call.getBoolean("isEncrypted") ?: false
        val isDirect = call.getBoolean("isDirect") ?: false
        val preset = call.getString("preset")
        val inviteArray = call.getArray("invite")
        val invite = inviteArray?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }

        scope.launch {
            try {
                val roomId = bridge.createRoom(name, topic, isEncrypted, isDirect, invite, preset)
                call.resolve(JSObject().put("roomId", roomId))
            } catch (e: Exception) {
                call.reject(e.message ?: "createRoom failed", e)
            }
        }
    }

    @PluginMethod
    fun redactEvent(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId") ?: return call.reject("Missing eventId")
        val reason = call.getString("reason")

        scope.launch {
            try {
                bridge.redactEvent(roomId, eventId, reason)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "redactEvent failed", e)
            }
        }
    }

    @PluginMethod
    fun sendReaction(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId") ?: return call.reject("Missing eventId")
        val key = call.getString("key") ?: return call.reject("Missing key")

        scope.launch {
            try {
                bridge.sendReaction(roomId, eventId, key)
                call.resolve(JSObject().put("eventId", ""))
            } catch (e: Exception) {
                call.reject(e.message ?: "sendReaction failed", e)
            }
        }
    }

    @PluginMethod
    fun searchUsers(call: PluginCall) {
        val searchTerm = call.getString("searchTerm") ?: return call.reject("Missing searchTerm")
        val limit = call.getInt("limit", 10)!!.toLong()

        scope.launch {
            try {
                val result = bridge.searchUsers(searchTerm, limit)
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "searchUsers failed", e)
            }
        }
    }

    @PluginMethod
    fun setRoomName(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val name = call.getString("name") ?: return call.reject("Missing name")

        scope.launch {
            try {
                bridge.setRoomName(roomId, name)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "setRoomName failed", e)
            }
        }
    }

    @PluginMethod
    fun setRoomTopic(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val topic = call.getString("topic") ?: return call.reject("Missing topic")

        scope.launch {
            try {
                bridge.setRoomTopic(roomId, topic)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "setRoomTopic failed", e)
            }
        }
    }

    @PluginMethod
    fun setRoomAvatar(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val mxcUrl = call.getString("mxcUrl") ?: return call.reject("Missing mxcUrl")

        scope.launch {
            try {
                bridge.setRoomAvatar(roomId, mxcUrl)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "setRoomAvatar failed", e)
            }
        }
    }

    @PluginMethod
    fun uploadContent(call: PluginCall) {
        val fileUri = call.getString("fileUri") ?: return call.reject("Missing fileUri")
        val fileName = call.getString("fileName") ?: return call.reject("Missing fileName")
        val mimeType = call.getString("mimeType") ?: return call.reject("Missing mimeType")

        scope.launch {
            try {
                val contentUri = bridge.uploadContent(fileUri, fileName, mimeType)
                call.resolve(JSObject().put("contentUri", contentUri))
            } catch (e: Exception) {
                call.reject(e.message ?: "uploadContent failed", e)
            }
        }
    }

    @PluginMethod
    fun getThumbnailUrl(call: PluginCall) {
        val mxcUrl = call.getString("mxcUrl") ?: return call.reject("Missing mxcUrl")
        val width = call.getInt("width") ?: return call.reject("Missing width")
        val height = call.getInt("height") ?: return call.reject("Missing height")
        val method = call.getString("method") ?: "scale"

        try {
            val httpUrl = bridge.getThumbnailUrl(mxcUrl, width, height, method)
            call.resolve(JSObject().put("httpUrl", httpUrl))
        } catch (e: Exception) {
            call.reject(e.message ?: "getThumbnailUrl failed", e)
        }
    }

    @PluginMethod
    fun getDevices(call: PluginCall) {
        scope.launch {
            try {
                val devices = bridge.getDevices()
                val jsDevices = JSArray()
                devices.forEach { device -> jsDevices.put(mapToJSObject(device)) }
                call.resolve(JSObject().put("devices", jsDevices))
            } catch (e: Exception) {
                call.reject(e.message ?: "getDevices failed", e)
            }
        }
    }

    @PluginMethod
    fun deleteDevice(call: PluginCall) {
        val deviceId = call.getString("deviceId") ?: return call.reject("Missing deviceId")

        scope.launch {
            try {
                bridge.deleteDevice(deviceId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "deleteDevice failed", e)
            }
        }
    }

    @PluginMethod
    fun verifyDevice(call: PluginCall) {
        val deviceId = call.getString("deviceId") ?: return call.reject("Missing deviceId")

        scope.launch {
            try {
                bridge.verifyDevice(deviceId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "verifyDevice failed", e)
            }
        }
    }

    @PluginMethod
    fun setPusher(call: PluginCall) {
        call.reject("setPusher is not yet supported on this platform")
    }

    @PluginMethod
    fun inviteUser(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")

        scope.launch {
            try {
                bridge.inviteUser(roomId, userId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "inviteUser failed", e)
            }
        }
    }

    @PluginMethod
    fun kickUser(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val reason = call.getString("reason")

        scope.launch {
            try {
                bridge.kickUser(roomId, userId, reason)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "kickUser failed", e)
            }
        }
    }

    @PluginMethod
    fun banUser(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val reason = call.getString("reason")

        scope.launch {
            try {
                bridge.banUser(roomId, userId, reason)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "banUser failed", e)
            }
        }
    }

    @PluginMethod
    fun unbanUser(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")

        scope.launch {
            try {
                bridge.unbanUser(roomId, userId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "unbanUser failed", e)
            }
        }
    }

    @PluginMethod
    fun sendTyping(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val isTyping = call.getBoolean("isTyping") ?: return call.reject("Missing isTyping")

        scope.launch {
            try {
                bridge.sendTyping(roomId, isTyping)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "sendTyping failed", e)
            }
        }
    }

    @PluginMethod
    fun getMediaUrl(call: PluginCall) {
        val mxcUrl = call.getString("mxcUrl") ?: return call.reject("mxcUrl required")
        try {
            val httpUrl = bridge.getMediaUrl(mxcUrl)
            val ret = JSObject()
            ret.put("httpUrl", httpUrl)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject(e.message ?: "Failed to resolve media URL")
        }
    }

    @PluginMethod
    fun setPresence(call: PluginCall) {
        call.reject("setPresence is not supported on this platform")
    }

    @PluginMethod
    fun getPresence(call: PluginCall) {
        call.reject("getPresence is not supported on this platform")
    }

    @PluginMethod
    fun initializeCrypto(call: PluginCall) {
        scope.launch {
            try {
                bridge.initializeCrypto()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "initializeCrypto failed", e)
            }
        }
    }

    @PluginMethod
    fun bootstrapCrossSigning(call: PluginCall) {
        scope.launch {
            try {
                bridge.bootstrapCrossSigning()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "bootstrapCrossSigning failed", e)
            }
        }
    }

    @PluginMethod
    fun exportRoomKeys(call: PluginCall) {
        call.reject("exportRoomKeys is not supported on native — use recovery key instead")
    }

    @PluginMethod
    fun importRoomKeys(call: PluginCall) {
        call.reject("importRoomKeys is not supported on native — use recovery key instead")
    }

    @PluginMethod
    fun getEncryptionStatus(call: PluginCall) {
        scope.launch {
            try {
                call.resolve(mapToJSObject(bridge.getEncryptionStatus()))
            } catch (e: Exception) {
                call.reject(e.message ?: "getEncryptionStatus failed", e)
            }
        }
    }

    @PluginMethod
    fun setupKeyBackup(call: PluginCall) {
        scope.launch {
            try {
                val result = bridge.setupKeyBackup()
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "setupKeyBackup failed", e)
            }
        }
    }

    @PluginMethod
    fun getKeyBackupStatus(call: PluginCall) {
        scope.launch {
            try {
                val result = bridge.getKeyBackupStatus()
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "getKeyBackupStatus failed", e)
            }
        }
    }

    @PluginMethod
    fun restoreKeyBackup(call: PluginCall) {
        val recoveryKey = call.getString("recoveryKey")

        scope.launch {
            try {
                val result = bridge.restoreKeyBackup(recoveryKey)
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "restoreKeyBackup failed", e)
            }
        }
    }

    @PluginMethod
    fun setupRecovery(call: PluginCall) {
        val passphrase = call.getString("passphrase")
        // existingPassphrase is a web-only hint for SSSS migration; the Rust SDK
        // handles migration internally on Android. Read and ignore for now.
        @Suppress("UNUSED_VARIABLE") val existingPassphrase = call.getString("existingPassphrase")

        scope.launch {
            try {
                val result = bridge.setupRecovery(passphrase)
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "setupRecovery failed", e)
            }
        }
    }

    @PluginMethod
    fun isRecoveryEnabled(call: PluginCall) {
        scope.launch {
            try {
                val enabled = bridge.isRecoveryEnabled()
                call.resolve(JSObject().put("enabled", enabled))
            } catch (e: Exception) {
                call.reject(e.message ?: "isRecoveryEnabled failed", e)
            }
        }
    }

    @PluginMethod
    fun recoverAndSetup(call: PluginCall) {
        val recoveryKey = call.getString("recoveryKey")
        val passphrase = call.getString("passphrase")

        if (recoveryKey == null && passphrase == null) {
            return call.reject("Missing recoveryKey or passphrase")
        }

        scope.launch {
            try {
                bridge.recoverAndSetup(recoveryKey = recoveryKey, passphrase = passphrase)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "recoverAndSetup failed", e)
            }
        }
    }

    @PluginMethod
    fun resetRecoveryKey(call: PluginCall) {
        scope.launch {
            try {
                val result = bridge.resetRecoveryKey()
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "resetRecoveryKey failed", e)
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

    @Suppress("UNCHECKED_CAST")
    private fun mapToJSObject(map: Map<String, Any?>): JSObject {
        val obj = JSObject()
        map.forEach { (key, value) ->
            when (value) {
                is Map<*, *> -> obj.put(key, mapToJSObject(value as Map<String, Any?>))
                is List<*> -> {
                    val arr = JSArray()
                    value.forEach { item ->
                        when (item) {
                            is Map<*, *> -> arr.put(mapToJSObject(item as Map<String, Any?>))
                            else -> arr.put(item)
                        }
                    }
                    obj.put(key, arr)
                }
                else -> obj.put(key, value)
            }
        }
        return obj
    }
}
