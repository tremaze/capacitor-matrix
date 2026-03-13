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

    @PluginMethod
    fun createRoom(call: PluginCall) {
        val name = call.getString("name")
        val topic = call.getString("topic")
        val isEncrypted = call.getBoolean("isEncrypted") ?: false
        val inviteArray = call.getArray("invite")
        val invite = inviteArray?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }

        scope.launch {
            try {
                val roomId = bridge.createRoom(name, topic, isEncrypted, invite)
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
        call.reject("getMediaUrl is only available on web")
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
    fun getEncryptionStatus(call: PluginCall) {
        scope.launch {
            try {
                val status = bridge.getEncryptionStatus()
                val jsResult = JSObject()
                jsResult.put("isCrossSigningReady", status["isCrossSigningReady"])
                @Suppress("UNCHECKED_CAST")
                val crossSigning = status["crossSigningStatus"] as? Map<String, Any?>
                if (crossSigning != null) {
                    val jsCrossSigning = JSObject()
                    crossSigning.forEach { (key, value) -> jsCrossSigning.put(key, value) }
                    jsResult.put("crossSigningStatus", jsCrossSigning)
                }
                jsResult.put("isKeyBackupEnabled", status["isKeyBackupEnabled"])
                jsResult.put("isSecretStorageReady", status["isSecretStorageReady"])
                call.resolve(jsResult)
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
                val jsResult = JSObject()
                result.forEach { (key, value) -> jsResult.put(key, value) }
                call.resolve(jsResult)
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
                val jsResult = JSObject()
                result.forEach { (key, value) -> jsResult.put(key, value) }
                call.resolve(jsResult)
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
                val jsResult = JSObject()
                result.forEach { (key, value) -> jsResult.put(key, value) }
                call.resolve(jsResult)
            } catch (e: Exception) {
                call.reject(e.message ?: "restoreKeyBackup failed", e)
            }
        }
    }

    @PluginMethod
    fun setupRecovery(call: PluginCall) {
        val passphrase = call.getString("passphrase")

        scope.launch {
            try {
                val result = bridge.setupRecovery(passphrase)
                val jsResult = JSObject()
                result.forEach { (key, value) -> jsResult.put(key, value) }
                call.resolve(jsResult)
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
        val recoveryKey = call.getString("recoveryKey") ?: return call.reject("Missing recoveryKey")

        scope.launch {
            try {
                bridge.recoverAndSetup(recoveryKey)
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
                val jsResult = JSObject()
                result.forEach { (key, value) -> jsResult.put(key, value) }
                call.resolve(jsResult)
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
}
