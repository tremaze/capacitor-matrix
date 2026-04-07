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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(name = "Matrix")
class CapMatrixPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var matrixBridge: CapMatrix

    override fun load() {
        matrixBridge = CapMatrix(context)
    }

    // ── Auth ─────────────────────────────────────────────────────────────

    @PluginMethod
    fun login(call: PluginCall) {
        val homeserverUrl = call.getString("homeserverUrl")
            ?: return call.reject("Missing homeserverUrl")
        val userId = call.getString("userId")
            ?: return call.reject("Missing userId")
        val password = call.getString("password")
            ?: return call.reject("Missing password")

        scope.launch {
            try {
                val session = matrixBridge.login(homeserverUrl, userId, password)
                call.resolve(mapToJSObject(session))
            } catch (e: Exception) {
                call.reject(e.message ?: "login failed", e)
            }
        }
    }

    @PluginMethod
    fun jwtLogin(call: PluginCall) {
        val homeserverUrl = call.getString("homeserverUrl")
            ?: return call.reject("Missing homeserverUrl")
        val token = call.getString("token")
            ?: return call.reject("Missing token")

        android.util.Log.d("CapMatrixPlugin", "jwtLogin: received call, launching coroutine")
        scope.launch {
            try {
                val session = matrixBridge.jwtLogin(homeserverUrl, token)
                android.util.Log.d("CapMatrixPlugin", "jwtLogin: bridge returned, resolving to JS")
                call.resolve(mapToJSObject(session))
            } catch (e: Exception) {
                android.util.Log.w("CapMatrixPlugin", "jwtLogin: failed — ${e.message}")
                call.reject(e.message ?: "jwtLogin failed", e)
            }
        }
    }

    @PluginMethod
    fun logout(call: PluginCall) {
        scope.launch {
            try {
                matrixBridge.logout()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "logout failed", e)
            }
        }
    }

    @PluginMethod
    fun clearAllData(call: PluginCall) {
        scope.launch {
            try {
                matrixBridge.clearAllData()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "clearAllData failed", e)
            }
        }
    }

    override fun handleOnDestroy() {
        matrixBridge.destroy()
        scope.cancel()
    }

    @PluginMethod
    fun updateAccessToken(call: PluginCall) {
        val accessToken = call.getString("accessToken")
            ?: return call.reject("Missing accessToken")

        scope.launch {
            try {
                matrixBridge.updateAccessToken(accessToken)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "updateAccessToken failed", e)
            }
        }
    }

    @PluginMethod
    fun getSession(call: PluginCall) {
        val session = matrixBridge.getSession()
        if (session != null) {
            call.resolve(mapToJSObject(session))
        } else {
            call.resolve(JSObject())
        }
    }

    // ── Sync ─────────────────────────────────────────────────────────────

    @PluginMethod
    fun startSync(call: PluginCall) {
        android.util.Log.d("CapMatrixPlugin", "startSync: received call")
        scope.launch {
            try {
                matrixBridge.startSync(
                    onSyncState = { state ->
                        notifyListeners("syncStateChange", JSObject().put("state", state))
                    },
                    onMessage = { event ->
                        notifyListeners("messageReceived", JSObject().put("event", mapToJSObject(event)))
                    },
                    onRoomUpdate = { roomId, summary ->
                        notifyListeners(
                            "roomUpdated",
                            JSObject().put("roomId", roomId).put("summary", mapToJSObject(summary))
                        )
                    },
                    onReceipt = { roomId, eventId, userId ->
                        notifyListeners(
                            "receiptReceived",
                            JSObject().put("roomId", roomId).put("eventId", eventId).put("userId", userId)
                        )
                    },
                    onTyping = { roomId, userIds ->
                        val arr = JSONArray()
                        userIds.forEach { arr.put(it) }
                        notifyListeners(
                            "typingChanged",
                            JSObject().put("roomId", roomId).put("userIds", arr)
                        )
                    }
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
                matrixBridge.stopSync()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "stopSync failed", e)
            }
        }
    }

    @PluginMethod
    fun getSyncState(call: PluginCall) {
        val state = matrixBridge.getSyncState()
        call.resolve(JSObject().put("state", state))
    }

    // ── Rooms ────────────────────────────────────────────────────────────

    @PluginMethod
    fun getRooms(call: PluginCall) {
        android.util.Log.d("CapMatrixPlugin", "getRooms: received call")
        scope.launch {
            try {
                val rooms = matrixBridge.getRooms()
                android.util.Log.d("CapMatrixPlugin", "getRooms: returning ${rooms.size} rooms")
                val arr = JSArray()
                rooms.forEach { arr.put(mapToJSONObject(it)) }
                call.resolve(JSObject().put("rooms", arr))
            } catch (e: Exception) {
                android.util.Log.w("CapMatrixPlugin", "getRooms: failed — ${e.message}")
                call.reject(e.message ?: "getRooms failed", e)
            }
        }
    }

    @PluginMethod
    fun getRoomMembers(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")

        scope.launch {
            try {
                val members = matrixBridge.getRoomMembers(roomId)
                val arr = JSArray()
                members.forEach { arr.put(mapToJSONObject(it)) }
                call.resolve(JSObject().put("members", arr))
            } catch (e: Exception) {
                call.reject(e.message ?: "getRoomMembers failed", e)
            }
        }
    }

    @PluginMethod
    fun joinRoom(call: PluginCall) {
        val roomIdOrAlias = call.getString("roomIdOrAlias")
            ?: return call.reject("Missing roomIdOrAlias")

        scope.launch {
            try {
                val roomId = matrixBridge.joinRoom(roomIdOrAlias)
                call.resolve(JSObject().put("roomId", roomId))
            } catch (e: Exception) {
                call.reject(e.message ?: "joinRoom failed", e)
            }
        }
    }

    @PluginMethod
    fun leaveRoom(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")

        scope.launch {
            try {
                matrixBridge.leaveRoom(roomId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "leaveRoom failed", e)
            }
        }
    }

    @PluginMethod
    fun forgetRoom(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        scope.launch {
            try {
                matrixBridge.forgetRoom(roomId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "forgetRoom failed", e)
            }
        }
    }

    @PluginMethod
    fun createRoom(call: PluginCall) {
        val name = call.getString("name")
        val topic = call.getString("topic")
        val isEncrypted = call.getBoolean("isEncrypted", false) ?: false
        val isDirect = call.getBoolean("isDirect", false) ?: false
        val invite = call.getArray("invite")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it) }
        }
        val preset = call.getString("preset")

        scope.launch {
            try {
                val roomId = matrixBridge.createRoom(name, topic, isEncrypted, isDirect, invite, preset)
                call.resolve(JSObject().put("roomId", roomId))
            } catch (e: Exception) {
                call.reject(e.message ?: "createRoom failed", e)
            }
        }
    }

    // ── Messaging ────────────────────────────────────────────────────────

    @PluginMethod
    fun sendMessage(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val body = call.getString("body")
            ?: return call.reject("Missing body")
        val msgtype = call.getString("msgtype") ?: "m.text"
        val fileUri = call.getString("fileUri")
        val fileName = call.getString("fileName")
        val mimeType = call.getString("mimeType")
        val fileSize = call.getInt("fileSize")
        val duration = call.getInt("duration")
        val width = call.getInt("width")
        val height = call.getInt("height")

        scope.launch {
            try {
                val eventId = matrixBridge.sendMessage(
                    roomId, body, msgtype,
                    fileUri = fileUri, fileName = fileName, mimeType = mimeType,
                    fileSize = fileSize, duration = duration, width = width, height = height
                )
                call.resolve(JSObject().put("eventId", eventId))
            } catch (e: Exception) {
                call.reject(e.message ?: "sendMessage failed", e)
            }
        }
    }

    @PluginMethod
    fun editMessage(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId")
            ?: return call.reject("Missing eventId")
        val newBody = call.getString("newBody")
            ?: return call.reject("Missing newBody")
        // Media edit fields — not yet implemented in the native bridge
        @Suppress("UNUSED_VARIABLE") val msgtype = call.getString("msgtype")
        @Suppress("UNUSED_VARIABLE") val duration = call.getInt("duration")
        @Suppress("UNUSED_VARIABLE") val width = call.getInt("width")
        @Suppress("UNUSED_VARIABLE") val height = call.getInt("height")

        scope.launch {
            try {
                val resultEventId = matrixBridge.editMessage(roomId, eventId, newBody)
                call.resolve(JSObject().put("eventId", resultEventId))
            } catch (e: Exception) {
                call.reject(e.message ?: "editMessage failed", e)
            }
        }
    }

    @PluginMethod
    fun sendReply(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val body = call.getString("body")
            ?: return call.reject("Missing body")
        val replyToEventId = call.getString("replyToEventId")
            ?: return call.reject("Missing replyToEventId")
        val msgtype = call.getString("msgtype") ?: "m.text"
        // Media info fields — consumed by the bridge when media sending is fully implemented
        @Suppress("UNUSED_VARIABLE") val duration = call.getInt("duration")
        @Suppress("UNUSED_VARIABLE") val width = call.getInt("width")
        @Suppress("UNUSED_VARIABLE") val height = call.getInt("height")

        scope.launch {
            try {
                val resultEventId = matrixBridge.sendReply(roomId, body, replyToEventId, msgtype)
                call.resolve(JSObject().put("eventId", resultEventId))
            } catch (e: Exception) {
                call.reject(e.message ?: "sendReply failed", e)
            }
        }
    }

    @PluginMethod
    fun getRoomMessages(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val limit = call.getInt("limit") ?: 20
        val from = call.getString("from")

        scope.launch {
            try {
                val result = matrixBridge.getRoomMessages(roomId, limit, from)
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "getRoomMessages failed", e)
            }
        }
    }

    @PluginMethod
    fun markRoomAsRead(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId")
            ?: return call.reject("Missing eventId")

        scope.launch {
            try {
                matrixBridge.markRoomAsRead(roomId, eventId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "markRoomAsRead failed", e)
            }
        }
    }

    @PluginMethod
    fun refreshEventStatuses(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val eventIdsArr = call.getArray("eventIds")
            ?: return call.reject("Missing eventIds")
        val eventIds = (0 until eventIdsArr.length()).mapNotNull { eventIdsArr.optString(it) }

        scope.launch {
            try {
                val events = matrixBridge.refreshEventStatuses(roomId, eventIds)
                val arr = JSArray()
                events.forEach { arr.put(mapToJSONObject(it)) }
                call.resolve(JSObject().put("events", arr))
            } catch (e: Exception) {
                call.reject(e.message ?: "refreshEventStatuses failed", e)
            }
        }
    }

    @PluginMethod
    fun redactEvent(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId")
            ?: return call.reject("Missing eventId")
        val reason = call.getString("reason")

        scope.launch {
            try {
                matrixBridge.redactEvent(roomId, eventId, reason)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "redactEvent failed", e)
            }
        }
    }

    @PluginMethod
    fun sendReaction(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val eventId = call.getString("eventId")
            ?: return call.reject("Missing eventId")
        val key = call.getString("key")
            ?: return call.reject("Missing key")

        scope.launch {
            try {
                matrixBridge.sendReaction(roomId, eventId, key)
                call.resolve(JSObject().put("eventId", ""))
            } catch (e: Exception) {
                call.reject(e.message ?: "sendReaction failed", e)
            }
        }
    }

    // ── Encryption ───────────────────────────────────────────────────────

    @PluginMethod
    fun initializeCrypto(call: PluginCall) {
        android.util.Log.d("CapMatrixPlugin", "initializeCrypto: received call")
        scope.launch {
            try {
                matrixBridge.initializeCrypto()
                android.util.Log.d("CapMatrixPlugin", "initializeCrypto: resolved")
                call.resolve()
            } catch (e: Exception) {
                android.util.Log.w("CapMatrixPlugin", "initializeCrypto: failed — ${e.message}")
                call.reject(e.message ?: "initializeCrypto failed", e)
            }
        }
    }

    @PluginMethod
    fun getEncryptionStatus(call: PluginCall) {
        android.util.Log.d("CapMatrixPlugin", "getEncryptionStatus: received call")
        scope.launch {
            try {
                val status = matrixBridge.getEncryptionStatus()
                android.util.Log.d("CapMatrixPlugin", "getEncryptionStatus: resolved")
                call.resolve(mapToJSObject(status))
            } catch (e: Exception) {
                android.util.Log.w("CapMatrixPlugin", "getEncryptionStatus: failed — ${e.message}")
                call.reject(e.message ?: "getEncryptionStatus failed", e)
            }
        }
    }

    @PluginMethod
    fun bootstrapCrossSigning(call: PluginCall) {
        scope.launch {
            try {
                matrixBridge.bootstrapCrossSigning()
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "bootstrapCrossSigning failed", e)
            }
        }
    }

    @PluginMethod
    fun setupKeyBackup(call: PluginCall) {
        scope.launch {
            try {
                val result = matrixBridge.setupKeyBackup()
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
                val status = matrixBridge.getKeyBackupStatus()
                call.resolve(mapToJSObject(status))
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
                val result = matrixBridge.restoreKeyBackup(recoveryKey)
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
        // handles migration internally. Read and ignore for now.
        @Suppress("UNUSED_VARIABLE") val existingPassphrase = call.getString("existingPassphrase")

        scope.launch {
            try {
                val result = matrixBridge.setupRecovery(passphrase)
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
                val enabled = matrixBridge.isRecoveryEnabled()
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
                matrixBridge.recoverAndSetup(recoveryKey, passphrase)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "recoverAndSetup failed", e)
            }
        }
    }

    @PluginMethod
    fun resetRecoveryKey(call: PluginCall) {
        val passphrase = call.getString("passphrase")

        scope.launch {
            try {
                val result = matrixBridge.resetRecoveryKey(passphrase)
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "resetRecoveryKey failed", e)
            }
        }
    }

    @PluginMethod
    fun exportRoomKeys(call: PluginCall) {
        val passphrase = call.getString("passphrase")
            ?: return call.reject("Missing passphrase")

        scope.launch {
            try {
                val data = matrixBridge.exportRoomKeys(passphrase)
                call.resolve(JSObject().put("data", data))
            } catch (e: Exception) {
                call.reject(e.message ?: "exportRoomKeys failed", e)
            }
        }
    }

    @PluginMethod
    fun importRoomKeys(call: PluginCall) {
        val data = call.getString("data")
            ?: return call.reject("Missing data")
        val passphrase = call.getString("passphrase")
            ?: return call.reject("Missing passphrase")

        scope.launch {
            try {
                val count = matrixBridge.importRoomKeys(data, passphrase)
                call.resolve(JSObject().put("importedKeys", count))
            } catch (e: Exception) {
                call.reject(e.message ?: "importRoomKeys failed", e)
            }
        }
    }

    @PluginMethod
    fun verifyDevice(call: PluginCall) {
        val deviceId = call.getString("deviceId")
            ?: return call.reject("Missing deviceId")

        scope.launch {
            try {
                matrixBridge.verifyDevice(deviceId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "verifyDevice failed", e)
            }
        }
    }

    // ── Room Management ──────────────────────────────────────────────────

    @PluginMethod
    fun setRoomName(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val name = call.getString("name")
            ?: return call.reject("Missing name")

        scope.launch {
            try {
                matrixBridge.setRoomName(roomId, name)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "setRoomName failed", e)
            }
        }
    }

    @PluginMethod
    fun setRoomTopic(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val topic = call.getString("topic")
            ?: return call.reject("Missing topic")

        scope.launch {
            try {
                matrixBridge.setRoomTopic(roomId, topic)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "setRoomTopic failed", e)
            }
        }
    }

    @PluginMethod
    fun setRoomAvatar(call: PluginCall) {
        call.getString("roomId")
            ?: return call.reject("Missing roomId")
        call.getString("mxcUrl")
            ?: return call.reject("Missing mxcUrl")
        // No-op placeholder: Rust SDK doesn't have direct setAvatar
        call.resolve()
    }

    @PluginMethod
    fun inviteUser(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val userId = call.getString("userId")
            ?: return call.reject("Missing userId")

        scope.launch {
            try {
                matrixBridge.inviteUser(roomId, userId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "inviteUser failed", e)
            }
        }
    }

    @PluginMethod
    fun kickUser(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val userId = call.getString("userId")
            ?: return call.reject("Missing userId")
        val reason = call.getString("reason")

        scope.launch {
            try {
                matrixBridge.kickUser(roomId, userId, reason)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "kickUser failed", e)
            }
        }
    }

    @PluginMethod
    fun banUser(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val userId = call.getString("userId")
            ?: return call.reject("Missing userId")
        val reason = call.getString("reason")

        scope.launch {
            try {
                matrixBridge.banUser(roomId, userId, reason)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "banUser failed", e)
            }
        }
    }

    @PluginMethod
    fun unbanUser(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val userId = call.getString("userId")
            ?: return call.reject("Missing userId")

        scope.launch {
            try {
                matrixBridge.unbanUser(roomId, userId)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "unbanUser failed", e)
            }
        }
    }

    // ── User Discovery ───────────────────────────────────────────────────

    @PluginMethod
    fun searchUsers(call: PluginCall) {
        val searchTerm = call.getString("searchTerm")
            ?: return call.reject("Missing searchTerm")
        val limit = call.getInt("limit") ?: 10

        scope.launch {
            try {
                val result = matrixBridge.searchUsers(searchTerm, limit)
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "searchUsers failed", e)
            }
        }
    }

    // ── Typing ───────────────────────────────────────────────────────────

    @PluginMethod
    fun sendTyping(call: PluginCall) {
        val roomId = call.getString("roomId")
            ?: return call.reject("Missing roomId")
        val isTyping = call.getBoolean("isTyping")
            ?: return call.reject("Missing isTyping")

        scope.launch {
            try {
                matrixBridge.sendTyping(roomId, isTyping)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "sendTyping failed", e)
            }
        }
    }

    // ── Media ────────────────────────────────────────────────────────────

    @PluginMethod
    fun getMediaUrl(call: PluginCall) {
        val mxcUrl = call.getString("mxcUrl")
            ?: return call.reject("Missing mxcUrl")
        try {
            val httpUrl = matrixBridge.getMediaUrl(mxcUrl)
            call.resolve(JSObject().put("httpUrl", httpUrl))
        } catch (e: Exception) {
            call.reject(e.message ?: "getMediaUrl failed", e)
        }
    }

    @PluginMethod
    fun getThumbnailUrl(call: PluginCall) {
        val mxcUrl = call.getString("mxcUrl")
            ?: return call.reject("Missing mxcUrl")
        val width = call.getInt("width") ?: 320
        val height = call.getInt("height") ?: 240
        val method = call.getString("method") ?: "scale"

        try {
            val url = matrixBridge.getThumbnailUrl(mxcUrl, width, height, method)
            call.resolve(JSObject().put("httpUrl", url))
        } catch (e: Exception) {
            call.reject(e.message ?: "getThumbnailUrl failed", e)
        }
    }

    @PluginMethod
    fun uploadContent(call: PluginCall) {
        val fileUri = call.getString("fileUri")
            ?: return call.reject("Missing fileUri")
        val fileName = call.getString("fileName")
            ?: return call.reject("Missing fileName")
        val mimeType = call.getString("mimeType")
            ?: return call.reject("Missing mimeType")

        scope.launch {
            try {
                val contentUri = matrixBridge.uploadContent(fileUri, fileName, mimeType)
                call.resolve(JSObject().put("contentUri", contentUri))
            } catch (e: Exception) {
                call.reject(e.message ?: "uploadContent failed", e)
            }
        }
    }

    // ── Presence ─────────────────────────────────────────────────────────

    @PluginMethod
    fun setPresence(call: PluginCall) {
        val presence = call.getString("presence")
            ?: return call.reject("Missing presence")
        val statusMsg = call.getString("statusMsg")

        scope.launch {
            try {
                matrixBridge.setPresence(presence, statusMsg)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "setPresence failed", e)
            }
        }
    }

    @PluginMethod
    fun getPresence(call: PluginCall) {
        val userId = call.getString("userId")
            ?: return call.reject("Missing userId")

        scope.launch {
            try {
                val result = matrixBridge.getPresence(userId)
                call.resolve(mapToJSObject(result))
            } catch (e: Exception) {
                call.reject(e.message ?: "getPresence failed", e)
            }
        }
    }

    // ── Devices ──────────────────────────────────────────────────────────

    @PluginMethod
    fun getDevices(call: PluginCall) {
        scope.launch {
            try {
                val devices = matrixBridge.getDevices()
                val arr = JSArray()
                devices.forEach { arr.put(mapToJSONObject(it)) }
                call.resolve(JSObject().put("devices", arr))
            } catch (e: Exception) {
                call.reject(e.message ?: "getDevices failed", e)
            }
        }
    }

    @PluginMethod
    fun deleteDevice(call: PluginCall) {
        val deviceId = call.getString("deviceId")
            ?: return call.reject("Missing deviceId")
        val auth = call.getObject("auth", null)?.let { JSONObject(it.toString()) }

        scope.launch {
            try {
                matrixBridge.deleteDevice(deviceId, auth)
                call.resolve()
            } catch (e: UiaRequiredException) {
                call.reject("UIA required", "UIA_REQUIRED", null, JSObject(e.data.toString()))
            } catch (e: Exception) {
                call.reject(e.message ?: "deleteDevice failed", e)
            }
        }
    }

    // ── Push ─────────────────────────────────────────────────────────────

    @PluginMethod
    fun setPusher(call: PluginCall) {
        val pushkey = call.getString("pushkey")
            ?: return call.reject("Missing pushkey")
        val appId = call.getString("appId")
            ?: return call.reject("Missing appId")
        val appDisplayName = call.getString("appDisplayName")
            ?: return call.reject("Missing appDisplayName")
        val deviceDisplayName = call.getString("deviceDisplayName")
            ?: return call.reject("Missing deviceDisplayName")
        val lang = call.getString("lang")
            ?: return call.reject("Missing lang")
        val dataObj = call.getObject("data")
            ?: return call.reject("Missing data")
        val dataUrl = dataObj.optString("url").takeIf { it.isNotEmpty() }
            ?: return call.reject("Missing data.url")
        val kind = call.getString("kind")
        val dataFormat = dataObj.optString("format").takeIf { it.isNotEmpty() }

        scope.launch {
            try {
                matrixBridge.setPusher(pushkey, kind, appId, appDisplayName, deviceDisplayName, lang, dataUrl, dataFormat)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "setPusher failed", e)
            }
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────

    companion object {
        /** Convert a Map<String, Any?> to a JSObject for Capacitor call.resolve() */
        fun mapToJSObject(map: Map<String, Any?>): JSObject {
            val obj = JSObject()
            for ((key, value) in map) {
                when (value) {
                    null -> obj.put(key, JSONObject.NULL)
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        obj.put(key, mapToJSONObject(value as Map<String, Any?>))
                    }
                    is List<*> -> {
                        val arr = JSONArray()
                        for (item in value) {
                            when (item) {
                                is Map<*, *> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    arr.put(mapToJSONObject(item as Map<String, Any?>))
                                }
                                null -> arr.put(JSONObject.NULL)
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

        /** Convert a Map<String, Any?> to a JSONObject (for nesting inside arrays/objects) */
        fun mapToJSONObject(map: Map<String, Any?>): JSONObject {
            val obj = JSONObject()
            for ((key, value) in map) {
                when (value) {
                    null -> obj.put(key, JSONObject.NULL)
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        obj.put(key, mapToJSONObject(value as Map<String, Any?>))
                    }
                    is List<*> -> {
                        val arr = JSONArray()
                        for (item in value) {
                            when (item) {
                                is Map<*, *> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    arr.put(mapToJSONObject(item as Map<String, Any?>))
                                }
                                null -> arr.put(JSONObject.NULL)
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
}
