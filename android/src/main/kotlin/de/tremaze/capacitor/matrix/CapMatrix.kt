package de.tremaze.capacitor.matrix

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.RecoveryException
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.VerificationState
import uniffi.matrix_sdk_base.EncryptionState
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import java.util.Collections

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
    private val subscribedRoomIds = mutableSetOf<String>()

    private val sessionStore by lazy { MatrixSessionStore(context) }

    // ── Auth ──────────────────────────────────────────────

    suspend fun login(homeserverUrl: String, userId: String, password: String): SessionInfo {
        // Use a per-user data directory to avoid crypto store conflicts
        val safeUserId = userId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val dataDir = context.filesDir.resolve("matrix_sdk/$safeUserId")
        // Clear previous session data to avoid device ID mismatches
        dataDir.deleteRecursively()
        dataDir.mkdirs()
        val dataDirPath = dataDir.absolutePath

        val newClient = ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .autoEnableCrossSigning(true)
            .sqliteStore(SqliteStoreBuilder(dataDirPath, dataDirPath))
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
        val safeUserId = userId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val dataDir = context.filesDir.resolve("matrix_sdk/$safeUserId")
        dataDir.mkdirs()
        val dataDirPath = dataDir.absolutePath

        val newClient = ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .autoEnableCrossSigning(true)
            .sqliteStore(SqliteStoreBuilder(dataDirPath, dataDirPath))
            .build()

        val session = Session(
            accessToken = accessToken,
            refreshToken = null,
            userId = userId,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
            oidcData = null,
            slidingSyncVersion = SlidingSyncVersion.NATIVE,
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

    fun clearAllData() {
        syncService = null
        client = null
        sessionStore.clear()
        val sdkDir = context.filesDir.resolve("matrix_sdk")
        sdkDir.deleteRecursively()
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
                val mapped = mapSyncState(state)
                onSyncState(mapped)
                // When sync reaches SYNCING, subscribe to room timelines
                if (mapped == "SYNCING") {
                    scope.launch {
                        subscribeToRoomTimelines(c, onMessage, onRoomUpdate)
                    }
                }
            }
        })

        service.start()
    }

    private suspend fun subscribeToRoomTimelines(
        c: Client,
        onMessage: (Map<String, Any?>) -> Unit,
        onRoomUpdate: (String, Map<String, Any?>) -> Unit,
    ) {
        for (room in c.rooms()) {
            val roomId = room.id()
            if (subscribedRoomIds.contains(roomId)) continue
            subscribedRoomIds.add(roomId)
            try {
                val timeline = room.timeline()
                timeline.addListener(object : TimelineListener {
                    override fun onUpdate(diff: List<TimelineDiff>) {
                        for (d in diff) {
                            // Only fire for new events (PushBack/Append), not initial loads
                            when (d) {
                                is TimelineDiff.PushBack -> {
                                    serializeTimelineItem(d.value, roomId)?.let { event ->
                                        onMessage(event)
                                    }
                                    onRoomUpdate(roomId, mapOf("roomId" to roomId))
                                }
                                is TimelineDiff.Append -> {
                                    d.values.forEach { item ->
                                        serializeTimelineItem(item, roomId)?.let { event ->
                                            onMessage(event)
                                        }
                                    }
                                    onRoomUpdate(roomId, mapOf("roomId" to roomId))
                                }
                                is TimelineDiff.Set -> {
                                    // Set means an existing item was updated (e.g. reactions changed)
                                    serializeTimelineItem(d.value, roomId)?.let { event ->
                                        onMessage(event)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                })
                android.util.Log.d("CapMatrix", "Subscribed to timeline for room $roomId")
            } catch (e: Exception) {
                android.util.Log.e("CapMatrix", "Failed to subscribe to room $roomId: ${e.message}")
            }
        }
    }

    suspend fun stopSync() {
        syncService?.stop()
        subscribedRoomIds.clear()
    }

    fun getSyncState(): String {
        return "SYNCING" // Will reflect actual state once sync observers are fully wired
    }

    // ── Rooms ─────────────────────────────────────────────

    suspend fun getRooms(): List<Map<String, Any?>> {
        val c = requireClient()
        val result = mutableListOf<Map<String, Any?>>()
        for (room in c.rooms()) {
            result.add(serializeRoom(room))
        }
        return result
    }

    suspend fun getRoomMembers(roomId: String): List<Map<String, Any?>> {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val iterator = room.members()
        val result = mutableListOf<Map<String, Any?>>()
        while (true) {
            val chunk = iterator.nextChunk(100u) ?: break
            for (member in chunk) {
                result.add(mapOf(
                    "userId" to member.userId,
                    "displayName" to member.displayName,
                    "membership" to when (member.membership) {
                        is MembershipState.Ban -> "ban"
                        is MembershipState.Invite -> "invite"
                        is MembershipState.Join -> "join"
                        is MembershipState.Knock -> "knock"
                        is MembershipState.Leave -> "leave"
                        else -> "unknown"
                    },
                ))
            }
        }
        return result
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

    suspend fun createRoom(
        name: String?,
        topic: String?,
        isEncrypted: Boolean,
        invite: List<String>?,
    ): String {
        val c = requireClient()
        val params = CreateRoomParameters(
            name = name,
            topic = topic,
            isEncrypted = isEncrypted,
            isDirect = false,
            visibility = RoomVisibility.Private,
            preset = RoomPreset.PRIVATE_CHAT,
            invite = invite,
        )
        return c.createRoom(params)
    }

    // ── Messaging ─────────────────────────────────────────

    suspend fun sendMessage(roomId: String, body: String, msgtype: String): String {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = room.timeline()

        val content = messageEventContentFromMarkdown(body)
        timeline.send(content)

        // The Rust SDK's send() is fire-and-forget; the real eventId arrives via
        // timeline listener when the server acknowledges. Use the messageReceived
        // event listener to capture sent message IDs.
        return ""
    }

    suspend fun getRoomMessages(roomId: String, limit: Int?, from: String?): Map<String, Any?> {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = room.timeline()
        val requestedLimit = limit ?: 20

        val events = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())

        val handle = timeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                for (d in diff) {
                    collectEventsFromDiff(d, roomId, events)
                }
            }
        })

        timeline.paginateBackwards(requestedLimit.toUShort())
        delay(500) // Allow listener to process pagination diffs

        handle.cancel()

        return mapOf(
            "events" to events.takeLast(requestedLimit),
            "nextBatch" to null,
        )
    }

    suspend fun markRoomAsRead(roomId: String, eventId: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.timeline().markAsRead(receiptType = ReceiptType.READ)
    }

    // ── Redactions & Reactions ─────────────────────────────

    suspend fun redactEvent(roomId: String, eventId: String, reason: String?) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = room.timeline()
        timeline.redactEvent(EventOrTransactionId.EventId(eventId), reason)
    }

    suspend fun sendReaction(roomId: String, eventId: String, key: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = room.timeline()
        timeline.toggleReaction(EventOrTransactionId.EventId(eventId), key)
    }

    // ── Room Management ──────────────────────────────────

    suspend fun setRoomName(roomId: String, name: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.setName(name)
    }

    suspend fun setRoomTopic(roomId: String, topic: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.setTopic(topic)
    }

    suspend fun inviteUser(roomId: String, userId: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.inviteUserById(userId)
    }

    suspend fun kickUser(roomId: String, userId: String, reason: String?) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.kickUser(userId, reason)
    }

    suspend fun banUser(roomId: String, userId: String, reason: String?) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.banUser(userId, reason)
    }

    suspend fun unbanUser(roomId: String, userId: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.unbanUser(userId, null)
    }

    // ── Typing ───────────────────────────────────────────

    suspend fun sendTyping(roomId: String, isTyping: Boolean) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.typingNotice(isTyping)
    }

    // ── Encryption ────────────────────────────────────────

    suspend fun initializeCrypto() {
        // No-op on native — Rust SDK handles crypto automatically
        requireClient()
    }

    suspend fun bootstrapCrossSigning() {
        val c = requireClient()
        // Cross-signing is auto-enabled via ClientBuilder.autoEnableCrossSigning(true)
        // Wait for E2EE initialization tasks to complete
        c.encryption().waitForE2eeInitializationTasks()
    }

    suspend fun getEncryptionStatus(): Map<String, Any?> {
        val c = requireClient()
        val enc = c.encryption()
        val backupState = enc.backupState()
        val isBackupEnabled = backupState == BackupState.ENABLED ||
            backupState == BackupState.CREATING ||
            backupState == BackupState.RESUMING
        val recoveryState = enc.recoveryState()
        val verificationState = enc.verificationState()
        val isVerified = verificationState == VerificationState.VERIFIED

        return mapOf(
            "isCrossSigningReady" to isVerified,
            "crossSigningStatus" to mapOf(
                "hasMaster" to isVerified,
                "hasSelfSigning" to isVerified,
                "hasUserSigning" to isVerified,
                "isReady" to isVerified,
            ),
            "isKeyBackupEnabled" to isBackupEnabled,
            "isSecretStorageReady" to (recoveryState == RecoveryState.ENABLED),
        )
    }

    suspend fun setupKeyBackup(): Map<String, Any?> {
        val c = requireClient()
        try {
            c.encryption().enableBackups()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to enable key backup. You may need to set up recovery first: ${e.message}",
                e,
            )
        }
        return mapOf(
            "exists" to true,
            "enabled" to true,
        )
    }

    suspend fun getKeyBackupStatus(): Map<String, Any?> {
        val c = requireClient()
        val existsOnServer = c.encryption().backupExistsOnServer()
        val state = c.encryption().backupState()
        val enabled = state == BackupState.ENABLED ||
            state == BackupState.CREATING ||
            state == BackupState.RESUMING
        return mapOf(
            "exists" to existsOnServer,
            "enabled" to enabled,
        )
    }

    suspend fun restoreKeyBackup(recoveryKey: String?): Map<String, Any?> {
        val c = requireClient()
        if (recoveryKey != null) {
            c.encryption().recover(recoveryKey)
        }
        return mapOf("importedKeys" to -1)
    }

    suspend fun setupRecovery(passphrase: String?): Map<String, Any?> {
        val c = requireClient()
        try {
            val key = c.encryption().enableRecovery(
                waitForBackupsToUpload = false,
                passphrase = passphrase,
                progressListener = object : EnableRecoveryProgressListener {
                    override fun onUpdate(status: EnableRecoveryProgress) {
                        // no-op — callers get the key from the return value
                    }
                },
            )
            return mapOf("recoveryKey" to key)
        } catch (e: RecoveryException.BackupExistsOnServer) {
            throw IllegalStateException(
                "BACKUP_EXISTS",
                e,
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to set up recovery: ${e::class.simpleName}: ${e.message}", e)
        }
    }

    suspend fun isRecoveryEnabled(): Boolean {
        val c = requireClient()
        return c.encryption().recoveryState() == RecoveryState.ENABLED
    }

    suspend fun recoverAndSetup(recoveryKey: String) {
        val c = requireClient()
        c.encryption().recover(recoveryKey)
    }

    suspend fun resetRecoveryKey(): Map<String, Any?> {
        val c = requireClient()
        val key = c.encryption().resetRecoveryKey()
        return mapOf("recoveryKey" to key)
    }

    // ── User Discovery ─────────────────────────────────────

    suspend fun searchUsers(searchTerm: String, limit: Long): Map<String, Any?> {
        val c = requireClient()
        val result = c.searchUsers(searchTerm, limit.toULong())
        return mapOf(
            "results" to result.results.map { u ->
                mapOf(
                    "userId" to u.userId,
                    "displayName" to u.displayName,
                    "avatarUrl" to u.avatarUrl,
                )
            },
            "limited" to result.limited,
        )
    }

    // ── Helpers ───────────────────────────────────────────

    private fun requireClient(): Client {
        return client ?: throw IllegalStateException("Not logged in. Call login() or loginWithToken() first.")
    }

    private suspend fun serializeRoom(room: Room): Map<String, Any?> {
        val info = room.roomInfo()
        return mapOf(
            "roomId" to room.id(),
            "name" to (info.displayName ?: ""),
            "topic" to info.topic,
            "memberCount" to info.joinedMembersCount.toInt(),
            "isEncrypted" to (info.encryptionState != EncryptionState.NOT_ENCRYPTED),
            "unreadCount" to (info.numUnreadMessages?.toInt() ?: 0),
            "lastEventTs" to null,
        )
    }

    private fun collectEventsFromDiff(
        diff: TimelineDiff,
        roomId: String,
        events: MutableList<Map<String, Any?>>,
    ) {
        when (diff) {
            is TimelineDiff.Clear -> {
                events.clear()
            }
            is TimelineDiff.Append -> {
                diff.values.forEach { item ->
                    serializeTimelineItem(item, roomId)?.let { events.add(it) }
                }
            }
            is TimelineDiff.PushBack -> {
                serializeTimelineItem(diff.value, roomId)?.let { events.add(it) }
            }
            is TimelineDiff.PushFront -> {
                serializeTimelineItem(diff.value, roomId)?.let { events.add(0, it) }
            }
            is TimelineDiff.Reset -> {
                events.clear()
                diff.values.forEach { item ->
                    serializeTimelineItem(item, roomId)?.let { events.add(it) }
                }
            }
            else -> {}
        }
    }

    private fun serializeTimelineItem(item: TimelineItem, roomId: String): Map<String, Any?>? {
        val eventItem = item.asEvent() ?: return null

        val eventId = when (val id = eventItem.eventOrTransactionId) {
            is EventOrTransactionId.EventId -> id.eventId
            is EventOrTransactionId.TransactionId -> id.transactionId
            else -> ""
        }

        val contentMap = mutableMapOf<String, Any?>()
        var eventType = "m.room.message"

        try {
            val content = eventItem.content
            android.util.Log.d("CapMatrix", "Item content type: ${content::class.simpleName}, sender=${eventItem.sender}, id=$eventId")
            when (content) {
                is TimelineItemContent.MsgLike -> {
                    val kind = content.content.kind
                    android.util.Log.d("CapMatrix", "  MsgLike kind: ${kind::class.simpleName}")
                    when (kind) {
                        is MsgLikeKind.Message -> {
                            contentMap["body"] = kind.content.body
                            contentMap["msgtype"] = "m.text"
                        }
                        is MsgLikeKind.UnableToDecrypt -> {
                            contentMap["body"] = "Unable to decrypt message"
                            contentMap["msgtype"] = "m.text"
                            contentMap["encrypted"] = true
                            android.util.Log.d("CapMatrix", "  UTD msg: ${kind.msg}")
                        }
                        is MsgLikeKind.Redacted -> {
                            eventType = "m.room.redaction"
                            contentMap["body"] = "Message deleted"
                        }
                        is MsgLikeKind.Other -> {
                            eventType = kind.eventType.toString()
                            android.util.Log.d("CapMatrix", "  Other event type: ${kind.eventType}")
                        }
                        else -> {
                            android.util.Log.d("CapMatrix", "  Unhandled MsgLikeKind: ${kind::class.simpleName}")
                        }
                    }
                    // Aggregate reactions from the Rust SDK
                    val reactions = content.content.reactions
                    if (reactions.isNotEmpty()) {
                        contentMap["reactions"] = reactions.map { r ->
                            mapOf(
                                "key" to r.key,
                                "count" to r.senders.size,
                                "senders" to r.senders.map { s -> s.senderId },
                            )
                        }
                    }
                }
                is TimelineItemContent.RoomMembership -> eventType = "m.room.member"
                else -> {
                    android.util.Log.d("CapMatrix", "  Unhandled content type: ${content::class.simpleName}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CapMatrix", "Error serializing timeline item: ${e.message}", e)
        }

        return mapOf(
            "eventId" to eventId,
            "roomId" to roomId,
            "senderId" to eventItem.sender,
            "type" to eventType,
            "content" to contentMap,
            "originServerTs" to eventItem.timestamp.toLong(),
        )
    }

    private fun mapSyncState(state: SyncServiceState): String {
        return when (state) {
            SyncServiceState.IDLE -> "STOPPED"
            SyncServiceState.RUNNING -> "SYNCING"
            SyncServiceState.TERMINATED -> "STOPPED"
            SyncServiceState.ERROR -> "ERROR"
            SyncServiceState.OFFLINE -> "ERROR"
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
