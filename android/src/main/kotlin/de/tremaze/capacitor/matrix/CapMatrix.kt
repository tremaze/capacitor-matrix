package de.tremaze.capacitor.matrix

class MatrixSDKBridge {

    fun login(homeserverUrl: String, userId: String, password: String): SessionInfo {
        throw NotImplementedError("login not implemented")
    }

    fun loginWithToken(homeserverUrl: String, accessToken: String, userId: String, deviceId: String): SessionInfo {
        throw NotImplementedError("loginWithToken not implemented")
    }

    fun logout() {
        throw NotImplementedError("logout not implemented")
    }

    fun getSession(): SessionInfo? {
        throw NotImplementedError("getSession not implemented")
    }

    fun startSync(
        onSyncState: (String) -> Unit,
        onMessage: (Map<String, Any?>) -> Unit,
        onRoomUpdate: (String, Map<String, Any?>) -> Unit,
    ) {
        throw NotImplementedError("startSync not implemented")
    }

    fun stopSync() {
        throw NotImplementedError("stopSync not implemented")
    }

    fun getSyncState(): String {
        throw NotImplementedError("getSyncState not implemented")
    }

    fun getRooms(): List<Map<String, Any?>> {
        throw NotImplementedError("getRooms not implemented")
    }

    fun getRoomMembers(roomId: String): List<Map<String, Any?>> {
        throw NotImplementedError("getRoomMembers not implemented")
    }

    fun joinRoom(roomIdOrAlias: String): String {
        throw NotImplementedError("joinRoom not implemented")
    }

    fun leaveRoom(roomId: String) {
        throw NotImplementedError("leaveRoom not implemented")
    }

    fun sendMessage(roomId: String, body: String, msgtype: String): String {
        throw NotImplementedError("sendMessage not implemented")
    }

    fun getRoomMessages(roomId: String, limit: Int?, from: String?): Map<String, Any?> {
        throw NotImplementedError("getRoomMessages not implemented")
    }

    fun markRoomAsRead(roomId: String, eventId: String) {
        throw NotImplementedError("markRoomAsRead not implemented")
    }
}

data class SessionInfo(
    val accessToken: String,
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
)
