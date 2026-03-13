package de.tremaze.capacitor.matrix

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown

data class SessionInfo(
    val accessToken: String,
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
)

class MatrixSDKBridge(private val context: Context) {

    private var client: Client? = null
    private var syncService: SyncService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionStore by lazy { MatrixSessionStore(context) }

    // ── Auth ──────────────────────────────────────────────

    suspend fun login(homeserverUrl: String, userId: String, password: String): SessionInfo {
        val dataDir = context.filesDir.resolve("matrix_sdk").absolutePath

        val newClient = ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .sqliteStore(SqliteStoreBuilder(dataDir, dataDir))
            .build()

        newClient.login(userId, password, "Capacitor Matrix Plugin", null)

        client = newClient
        val session = newClient.session()
        val info = session.toSessionInfo(homeserverUrl)
        sessionStore.save(info)
        return info
    }

    suspend fun loginWithToken(
        homeserverUrl: String,
        accessToken: String,
        userId: String,
        deviceId: String,
    ): SessionInfo {
        val dataDir = context.filesDir.resolve("matrix_sdk").absolutePath

        val newClient = ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .sqliteStore(SqliteStoreBuilder(dataDir, dataDir))
            .build()

        val session = Session(
            accessToken = accessToken,
            refreshToken = null,
            userId = userId,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
            slidingSyncVersion = null,
        )

        newClient.restoreSession(session)
        client = newClient

        val info = SessionInfo(
            accessToken = accessToken,
            userId = userId,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
        )
        sessionStore.save(info)
        return info
    }

    suspend fun logout() {
        syncService?.stop()
        syncService = null
        client?.logout()
        client = null
        sessionStore.clear()
    }

    fun getSession(): SessionInfo? {
        return sessionStore.load()
    }

    // ── Sync ──────────────────────────────────────────────

    suspend fun startSync(
        onSyncState: (String) -> Unit,
        onMessage: (Map<String, Any?>) -> Unit,
        onRoomUpdate: (String, Map<String, Any?>) -> Unit,
    ) {
        val c = requireClient()
        val service = c.syncService().finish()
        syncService = service

        service.state(object : SyncServiceStateObserver {
            override fun onUpdate(state: SyncServiceState) {
                onSyncState(mapSyncState(state))
            }
        })

        service.start()
    }

    suspend fun stopSync() {
        syncService?.stop()
    }

    fun getSyncState(): String {
        return "SYNCING" // Will reflect actual state once sync observers are fully wired
    }

    // ── Rooms ─────────────────────────────────────────────

    fun getRooms(): List<Map<String, Any?>> {
        val c = requireClient()
        return c.rooms().map { serializeRoom(it) }
    }

    suspend fun getRoomMembers(roomId: String): List<Map<String, Any?>> {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val members = room.members()
        return members.map { member ->
            mapOf(
                "userId" to member.userId,
                "displayName" to member.displayName,
                "membership" to member.membership.name.lowercase(),
            )
        }
    }

    suspend fun joinRoom(roomIdOrAlias: String): String {
        val c = requireClient()
        val room = c.joinRoomByIdOrAlias(roomIdOrAlias, emptyList())
        return room.id()
    }

    suspend fun leaveRoom(roomId: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.leave()
    }

    // ── Messaging ─────────────────────────────────────────

    suspend fun sendMessage(roomId: String, body: String, msgtype: String): String {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = room.timeline()

        val content = messageEventContentFromMarkdown(body)
        timeline.send(content)

        // The Rust SDK sends asynchronously; eventId may not be immediately available
        return ""
    }

    suspend fun getRoomMessages(roomId: String, limit: Int?, from: String?): Map<String, Any?> {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = room.timeline()

        timeline.paginateBackwards(limit?.toUInt() ?: 20u)

        // Return empty for now - full timeline item serialization requires
        // subscribing to timeline updates
        return mapOf(
            "events" to emptyList<Map<String, Any?>>(),
            "nextBatch" to null,
        )
    }

    suspend fun markRoomAsRead(roomId: String, eventId: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.timeline().markAsRead(receiptType = ReceiptType.READ)
    }

    // ── Helpers ───────────────────────────────────────────

    private fun requireClient(): Client {
        return client ?: throw IllegalStateException("Not logged in. Call login() or loginWithToken() first.")
    }

    private fun serializeRoom(room: Room): Map<String, Any?> {
        val info = room.roomInfo()
        return mapOf(
            "roomId" to room.id(),
            "name" to (info.displayName ?: ""),
            "topic" to info.topic,
            "memberCount" to (info.joinedMembersCount?.toInt() ?: 0),
            "isEncrypted" to info.isEncrypted,
            "unreadCount" to (info.numUnreadMessages?.toInt() ?: 0),
            "lastEventTs" to null,
        )
    }

    private fun mapSyncState(state: SyncServiceState): String {
        return when (state) {
            SyncServiceState.Idle -> "STOPPED"
            SyncServiceState.Running -> "SYNCING"
            SyncServiceState.Terminated -> "STOPPED"
            SyncServiceState.Error -> "ERROR"
            SyncServiceState.Offline -> "ERROR"
        }
    }

    private fun Session.toSessionInfo(homeserverUrl: String): SessionInfo {
        return SessionInfo(
            accessToken = this.accessToken,
            userId = this.userId,
            deviceId = this.deviceId,
            homeserverUrl = homeserverUrl,
        )
    }
}

class MatrixSessionStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "matrix_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(session: SessionInfo) {
        prefs.edit()
            .putString("accessToken", session.accessToken)
            .putString("userId", session.userId)
            .putString("deviceId", session.deviceId)
            .putString("homeserverUrl", session.homeserverUrl)
            .apply()
    }

    fun load(): SessionInfo? {
        val accessToken = prefs.getString("accessToken", null) ?: return null
        val userId = prefs.getString("userId", null) ?: return null
        val deviceId = prefs.getString("deviceId", null) ?: return null
        val homeserverUrl = prefs.getString("homeserverUrl", null) ?: return null
        return SessionInfo(accessToken, userId, deviceId, homeserverUrl)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
