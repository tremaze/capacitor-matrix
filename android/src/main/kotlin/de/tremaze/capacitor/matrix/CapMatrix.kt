package de.tremaze.capacitor.matrix

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.OtherState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    private val roomTimelines = mutableMapOf<String, org.matrix.rustcomponents.sdk.Timeline>()
    // Keep strong references to listener handles so GC doesn't cancel the subscriptions
    private val timelineListenerHandles = mutableListOf<Any>()
    // Rooms currently being paginated by getRoomMessages — live listener suppresses events for these
    private val paginatingRooms = Collections.synchronizedSet(mutableSetOf<String>())
    // Per-room tracking of the oldest event ID returned to JS, used for pagination cursor
    private val oldestReturnedEventId = mutableMapOf<String, String>()
    private val roomCreatedAtCache = mutableMapOf<String, Long>()
    private var receiptSyncJob: Job? = null
    // Receipt cache: roomId → (eventId → set of userIds who sent a read receipt)
    // Populated by the parallel v2 receipt sync since sliding sync doesn't deliver
    // other users' read receipts via the Rust SDK's readReceipts property.
    private val receiptCache = Collections.synchronizedMap(
        mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
    )

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
        // Stop existing sync and clean up stale references before replacing the client
        syncService?.stop()
        syncService = null
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        timelineListenerHandles.clear()
        roomTimelines.clear()
        subscribedRoomIds.clear()

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
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        receiptCache.clear()
        timelineListenerHandles.clear()
        roomTimelines.clear()
        subscribedRoomIds.clear()
        client?.logout()
        client = null
        sessionStore.clear()
    }

    fun clearAllData() {
        syncService = null
        client = null
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        receiptCache.clear()
        timelineListenerHandles.clear()
        roomTimelines.clear()
        subscribedRoomIds.clear()
        sessionStore.clear()
        val sdkDir = context.filesDir.resolve("matrix_sdk")
        sdkDir.deleteRecursively()
    }

    fun getSession(): SessionInfo? {
        return sessionStore.load()
    }

    suspend fun updateAccessToken(accessToken: String) {
        requireClient()

        // Stop sync service and clean up references
        syncService?.stop()
        syncService = null
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        receiptCache.clear()
        timelineListenerHandles.clear()
        roomTimelines.clear()
        subscribedRoomIds.clear()

        val oldSession = sessionStore.load()
            ?: throw IllegalStateException("No persisted session to update")

        // Build a new client pointing to the same data directory (preserves crypto store).
        // The Rust SDK's restoreSession() can only be called once per Client instance.
        val safeUserId = oldSession.userId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val dataDir = context.filesDir.resolve("matrix_sdk/$safeUserId")
        dataDir.mkdirs()
        val dataDirPath = dataDir.absolutePath

        val newClient = ClientBuilder()
            .homeserverUrl(oldSession.homeserverUrl)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .autoEnableCrossSigning(true)
            .sqliteStore(SqliteStoreBuilder(dataDirPath, dataDirPath))
            .build()

        val newSession = Session(
            accessToken = accessToken,
            refreshToken = null,
            userId = oldSession.userId,
            deviceId = oldSession.deviceId,
            homeserverUrl = oldSession.homeserverUrl,
            oidcData = null,
            slidingSyncVersion = SlidingSyncVersion.NATIVE,
        )

        newClient.restoreSession(newSession)
        client = newClient

        val updatedInfo = oldSession.copy(accessToken = accessToken)
        sessionStore.save(updatedInfo)
    }

    // ── Sync ──────────────────────────────────────────────

    suspend fun startSync(
        onSyncState: (String) -> Unit,
        onMessage: (Map<String, Any?>) -> Unit,
        onRoomUpdate: (String, Map<String, Any?>) -> Unit,
        onReceipt: (roomId: String, eventId: String, userId: String) -> Unit,
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

        // Start a parallel v2 sync connection that only listens for m.receipt
        // ephemeral events. Sliding sync doesn't deliver other users'
        // read receipts, so this provides live receipt updates.
        android.util.Log.d("CapMatrix", "startSync: launching receiptSync before service.start()")
        startReceiptSync(onReceipt)

        // service.start() blocks until sync stops, so it must be last
        android.util.Log.d("CapMatrix", "startSync: calling service.start() (blocking)")
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
                val timeline = getOrCreateTimeline(room)
                val handle = timeline.addListener(object : TimelineListener {
                    private fun emitRoomUpdate() {
                        scope.launch {
                            val summary = try {
                                serializeRoom(room)
                            } catch (_: Exception) {
                                mapOf<String, Any?>("roomId" to roomId)
                            }
                            onRoomUpdate(roomId, summary)
                        }
                    }

                    override fun onUpdate(diff: List<TimelineDiff>) {
                        // Suppress live events while getRoomMessages is paginating this room
                        if (paginatingRooms.contains(roomId)) return
                        try {
                            for (d in diff) {
                                when (d) {
                                    is TimelineDiff.PushBack -> {
                                        // Skip local echoes — the Set diff will follow with the real EventId
                                        val isLocalEcho = d.value.asEvent()?.eventOrTransactionId is EventOrTransactionId.TransactionId
                                        if (!isLocalEcho) {
                                            serializeTimelineItem(d.value, roomId)?.let { onMessage(it) }
                                        }
                                        emitRoomUpdate()
                                    }
                                    is TimelineDiff.Append -> {
                                        d.values.forEach { item ->
                                            serializeTimelineItem(item, roomId)?.let { onMessage(it) }
                                        }
                                        emitRoomUpdate()
                                    }
                                    is TimelineDiff.Set -> {
                                        serializeTimelineItem(d.value, roomId)?.let { onMessage(it) }
                                    }
                                    is TimelineDiff.Reset -> {
                                        d.values.forEach { item ->
                                            serializeTimelineItem(item, roomId)?.let { onMessage(it) }
                                        }
                                        emitRoomUpdate()
                                    }
                                    is TimelineDiff.Insert -> {
                                        serializeTimelineItem(d.value, roomId)?.let { onMessage(it) }
                                        emitRoomUpdate()
                                    }
                                    is TimelineDiff.PushFront -> {
                                        serializeTimelineItem(d.value, roomId)?.let { onMessage(it) }
                                        emitRoomUpdate()
                                    }
                                    else -> { /* Remove, Clear, Truncate, PopBack, PopFront — no JS event needed */ }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CapMatrix", "Error in timeline listener for $roomId: ${e.message}", e)
                        }
                    }
                })
                timelineListenerHandles.add(handle)
            } catch (e: Exception) {
                android.util.Log.e("CapMatrix", "Failed to subscribe to room $roomId: ${e.message}")
            }
        }
    }

    suspend fun stopSync() {
        syncService?.stop()
        subscribedRoomIds.clear()
        timelineListenerHandles.clear()
        roomTimelines.clear()
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        receiptCache.clear()
    }

    fun getSyncState(): String {
        return "SYNCING" // Will reflect actual state once sync observers are fully wired
    }

    // ── Rooms ─────────────────────────────────────────────

    suspend fun getRooms(): List<Map<String, Any?>> {
        val c = requireClient()
        val result = mutableListOf<Map<String, Any?>>()
        for (room in c.rooms()) {
            val dict = serializeRoom(room).toMutableMap()
            if (dict["lastEventTs"] == null) {
                fetchRoomCreatedAt(room.id())?.let { dict["createdAt"] = it }
            }
            result.add(dict)
        }
        return result
    }

    private fun fetchRoomCreatedAt(roomId: String): Long? {
        roomCreatedAtCache[roomId]?.let { return it }
        val session = sessionStore.load() ?: return null
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
        val url = URL("$baseUrl/_matrix/client/v3/rooms/$encodedRoomId/state")
        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().readText()
            val events = org.json.JSONArray(body)
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                if (event.optString("type") == "m.room.create") {
                    val ts = event.optLong("origin_server_ts", -1)
                    if (ts > 0) {
                        roomCreatedAtCache[roomId] = ts
                        return ts
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
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
                    "avatarUrl" to member.avatarUrl,
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

    suspend fun forgetRoom(roomId: String) {
        val c = requireClient()
        // The Rust SDK doesn't have a dedicated forget method on the Room type.
        // After leaving, the room is removed from the room list on next sync.
        // This is a no-op placeholder for API compatibility.
    }

    suspend fun createRoom(
        name: String?,
        topic: String?,
        isEncrypted: Boolean,
        isDirect: Boolean = false,
        invite: List<String>?,
        preset: String? = null,
    ): String {
        val c = requireClient()
        val roomPreset = when (preset) {
            "trusted_private_chat" -> RoomPreset.TRUSTED_PRIVATE_CHAT
            "public_chat" -> RoomPreset.PUBLIC_CHAT
            else -> RoomPreset.PRIVATE_CHAT
        }
        val params = CreateRoomParameters(
            name = name,
            topic = topic,
            isEncrypted = isEncrypted,
            isDirect = isDirect,
            visibility = RoomVisibility.Private,
            preset = roomPreset,
            invite = invite,
        )
        return c.createRoom(params)
    }

    // ── Messaging ─────────────────────────────────────────

    suspend fun sendMessage(roomId: String, body: String, msgtype: String): String {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = getOrCreateTimeline(room)

        val content = messageEventContentFromMarkdown(body)
        timeline.send(content)

        // The Rust SDK's send() is fire-and-forget; the real eventId arrives via
        // timeline listener when the server acknowledges. Use the messageReceived
        // event listener to capture sent message IDs.
        return ""
    }

    suspend fun editMessage(roomId: String, eventId: String, newBody: String): String {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = getOrCreateTimeline(room)
        val editContent = org.matrix.rustcomponents.sdk.EditedContent.RoomMessage(messageEventContentFromMarkdown(newBody))
        timeline.edit(EventOrTransactionId.EventId(eventId), editContent)
        return ""
    }

    suspend fun sendReply(roomId: String, body: String, replyToEventId: String, msgtype: String): String {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = getOrCreateTimeline(room)
        val content = messageEventContentFromMarkdown(body)
        timeline.sendReply(content, replyToEventId)
        return ""
    }

    suspend fun getRoomMessages(roomId: String, limit: Int?, from: String?): Map<String, Any?> {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = getOrCreateTimeline(room)
        val requestedLimit = limit ?: 20

        // Suppress live listener while we paginate to avoid flooding JS with historical events
        paginatingRooms.add(roomId)
        val collector = TimelineItemCollector(roomId)
        val handle = timeline.addListener(collector)

        val isPagination = from != null
        var hitStart = false
        try {
            // Wait for the initial Reset snapshot before paginating
            collector.waitForUpdate(timeoutMs = 5000)
            val countBefore = collector.events.size

            // Reset cursor on initial load
            if (!isPagination) {
                oldestReturnedEventId.remove(roomId)
            }

            // Paginate when: first load with too few items, OR explicit pagination request
            if (isPagination || countBefore < requestedLimit) {
                hitStart = timeline.paginateBackwards(requestedLimit.toUShort())
                if (!hitStart) {
                    collector.waitForUpdate(timeoutMs = 5000)
                }
            }
        } finally {
            handle.cancel()
            paginatingRooms.remove(roomId)
        }

        val allEvents = collector.events
        val cursorId = oldestReturnedEventId[roomId]

        var events = if (isPagination && cursorId != null) {
            // Pagination: find the cursor event and return events before it
            val cursorIdx = allEvents.indexOfFirst { (it["eventId"] as? String) == cursorId }
            if (cursorIdx > 0) {
                allEvents.take(cursorIdx).takeLast(requestedLimit).map { it.toMutableMap() }
            } else {
                emptyList<MutableMap<String, Any?>>()
            }
        } else {
            // Initial load: return newest events
            allEvents.takeLast(requestedLimit).map { it.toMutableMap() }
        }

        // Update cursor to the oldest event we're returning
        events.firstOrNull()?.let { oldest ->
            (oldest["eventId"] as? String)?.let { eid ->
                oldestReturnedEventId[roomId] = eid
            }
        }

        // Apply receipt watermark: if any own event has readBy data,
        // all earlier own events in the timeline are also read.
        val myUserId = try { c.userId() } catch (_: Exception) { null }
        var watermarkReadBy: List<String>? = null
        var watermarkIndex = -1
        // Walk backwards (newest first) to find the newest own event with a read receipt
        for (i in events.indices.reversed()) {
            val sender = events[i]["senderId"] as? String
            if (sender == myUserId) {
                val rb = events[i]["readBy"] as? List<*>
                if (rb != null && rb.isNotEmpty()) {
                    watermarkReadBy = rb.filterIsInstance<String>()
                    watermarkIndex = i
                    break
                }
            }
        }
        // Apply watermark only to own events BEFORE the watermark (older),
        // not to events after it — a receipt on event N doesn't mean N+1 is read
        if (watermarkReadBy != null && watermarkIndex >= 0) {
            for (i in 0 until watermarkIndex) {
                val sender = events[i]["senderId"] as? String
                if (sender == myUserId) {
                    val existing = events[i]["readBy"] as? List<*>
                    if (existing == null || existing.isEmpty()) {
                        events[i]["status"] = "read"
                        events[i]["readBy"] = watermarkReadBy
                    }
                }
            }
        }

        // Return a pagination token so the JS layer knows more messages are available.
        // The Rust SDK timeline handles pagination state internally, so we use a
        // synthetic token ("more") to signal that further back-pagination is possible.
        // Also stop if pagination returned no new events (timeline fully loaded).
        val nextBatch: String? = if (hitStart || events.isEmpty()) null else "more"

        return mapOf(
            "events" to events,
            "nextBatch" to nextBatch,
        )
    }

    suspend fun markRoomAsRead(roomId: String, eventId: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        getOrCreateTimeline(room).markAsRead(receiptType = ReceiptType.READ)
    }

    suspend fun refreshEventStatuses(roomId: String, eventIds: List<String>): List<Map<String, Any?>> {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = getOrCreateTimeline(room)
        val myUserId = try { c.userId() } catch (_: Exception) { null }
        val results = mutableListOf<MutableMap<String, Any?>>()
        for (eid in eventIds) {
            try {
                val eventItem = timeline.getEventTimelineItemByEventId(eid)
                serializeEventTimelineItem(eventItem, roomId)?.let { results.add(it.toMutableMap()) }
            } catch (_: Exception) {
                // Event may no longer be in timeline; skip
            }
        }
        // Apply receipt watermark: a read receipt on event N means all prior own events are read,
        // but NOT events after N
        var watermarkReadBy: List<String>? = null
        var watermarkIndex = -1
        for (i in results.indices.reversed()) {
            val sender = results[i]["senderId"] as? String
            if (sender == myUserId) {
                val rb = results[i]["readBy"] as? List<*>
                if (rb != null && rb.isNotEmpty()) {
                    watermarkReadBy = rb.filterIsInstance<String>()
                    watermarkIndex = i
                    break
                }
            }
        }
        if (watermarkReadBy != null && watermarkIndex >= 0) {
            for (i in 0 until watermarkIndex) {
                val sender = results[i]["senderId"] as? String
                if (sender == myUserId) {
                    val existing = results[i]["readBy"] as? List<*>
                    if (existing == null || existing.isEmpty()) {
                        results[i]["status"] = "read"
                        results[i]["readBy"] = watermarkReadBy
                    }
                }
            }
        }
        return results
    }

    // ── Redactions & Reactions ─────────────────────────────

    suspend fun redactEvent(roomId: String, eventId: String, reason: String?) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = getOrCreateTimeline(room)
        timeline.redactEvent(EventOrTransactionId.EventId(eventId), reason)
    }

    fun getMediaUrl(mxcUrl: String): String {
        // Convert mxc://server/media_id to authenticated download URL
        val session = sessionStore.load() ?: throw IllegalStateException("Not logged in")
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val mxcPath = mxcUrl.removePrefix("mxc://")
        return "$baseUrl/_matrix/client/v1/media/download/$mxcPath?access_token=${session.accessToken}"
    }

    suspend fun sendReaction(roomId: String, eventId: String, key: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        val timeline = getOrCreateTimeline(room)
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

    suspend fun setRoomAvatar(roomId: String, mxcUrl: String) {
        val c = requireClient()
        val room = c.getRoom(roomId) ?: throw IllegalArgumentException("Room $roomId not found")
        room.uploadAvatar("image/png", ByteArray(0), null)
        // The Rust SDK doesn't have a direct setAvatar(mxcUrl) method.
        // For now this is a placeholder - the actual implementation would need
        // to use the raw state event API if available.
    }

    suspend fun uploadContent(fileUri: String, fileName: String, mimeType: String): String {
        val session = sessionStore.load() ?: throw IllegalStateException("Not logged in")
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val url = URL("$baseUrl/_matrix/media/v3/upload?filename=${URLEncoder.encode(fileName, "UTF-8")}")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.setRequestProperty("Content-Type", mimeType)
        connection.doOutput = true

        // Read file from URI
        val inputStream = if (fileUri.startsWith("content://") || fileUri.startsWith("file://")) {
            val uri = android.net.Uri.parse(fileUri)
            context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Cannot open file: $fileUri")
        } else {
            java.io.File(fileUri).inputStream()
        }

        inputStream.use { input ->
            connection.outputStream.use { output ->
                input.copyTo(output)
            }
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw Exception("Upload failed with status $responseCode")
        }

        val responseBody = connection.inputStream.bufferedReader().readText()
        val json = org.json.JSONObject(responseBody)
        return json.getString("content_uri")
    }

    fun getThumbnailUrl(mxcUrl: String, width: Int, height: Int, method: String): String {
        val session = sessionStore.load() ?: throw IllegalStateException("Not logged in")
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val mxcPath = mxcUrl.removePrefix("mxc://")
        return "$baseUrl/_matrix/client/v1/media/thumbnail/$mxcPath?width=$width&height=$height&method=$method&access_token=${session.accessToken}"
    }

    suspend fun getDevices(): List<Map<String, Any?>> {
        val session = sessionStore.load() ?: throw IllegalStateException("Not logged in")
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val url = URL("$baseUrl/_matrix/client/v3/devices")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw Exception("getDevices failed with status $responseCode")
        }

        val responseBody = connection.inputStream.bufferedReader().readText()
        val json = org.json.JSONObject(responseBody)
        val devicesArray = json.getJSONArray("devices")
        val devices = mutableListOf<Map<String, Any?>>()
        for (i in 0 until devicesArray.length()) {
            val device = devicesArray.getJSONObject(i)
            devices.add(mapOf(
                "deviceId" to device.getString("device_id"),
                "displayName" to device.optString("display_name", null),
                "lastSeenTs" to if (device.has("last_seen_ts")) device.getLong("last_seen_ts") else null,
                "lastSeenIp" to device.optString("last_seen_ip", null),
                "isCrossSigningVerified" to false, // TODO: wire up Rust SDK per-device verification
            ))
        }
        return devices
    }

    suspend fun deleteDevice(deviceId: String) {
        val session = sessionStore.load() ?: throw IllegalStateException("Not logged in")
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val url = URL("$baseUrl/_matrix/client/v3/devices/$deviceId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "DELETE"
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        val body = org.json.JSONObject()
        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        // 401 means UIA is required - for now we just throw
        if (responseCode !in 200..299) {
            throw Exception("deleteDevice failed with status $responseCode")
        }
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

    /**
     * Cross-signs the given device using the current cross-signing keys.
     * After recoverAndSetup (which calls recover + waitForE2eeInitializationTasks),
     * the SDK should already have cross-signed this device. This method ensures
     * the E2EE initialization is complete and then resolves.
     */
    suspend fun verifyDevice(deviceId: String) {
        val c = requireClient()
        val enc = c.encryption()
        // Ensure cross-signing keys are fully imported and the device is signed
        enc.waitForE2eeInitializationTasks()
        android.util.Log.d("CapMatrix", "verifyDevice($deviceId) — verificationState: ${enc.verificationState()}")
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

        // recoveryState reflects the LOCAL device's state (.DISABLED on a returning
        // device that hasn't recovered yet).  To decide whether encryption was
        // set up server-side we also check if a backup exists on the server.
        val ssReady = if (recoveryState == RecoveryState.ENABLED) {
            true
        } else {
            try { enc.backupExistsOnServer() } catch (_: Exception) { false }
        }

        return mapOf(
            "isCrossSigningReady" to isVerified,
            "crossSigningStatus" to mapOf(
                "hasMaster" to isVerified,
                "hasSelfSigning" to isVerified,
                "hasUserSigning" to isVerified,
                "isReady" to isVerified,
            ),
            "isKeyBackupEnabled" to isBackupEnabled,
            "isSecretStorageReady" to ssReady,
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

    suspend fun recoverAndSetup(recoveryKey: String?, passphrase: String?) {
        val c = requireClient()
        val key = recoveryKey ?: passphrase?.let { deriveRecoveryKeyFromPassphrase(c, it) }
            ?: throw IllegalArgumentException("recoveryKey or passphrase required")

        val enc = c.encryption()
        enc.recover(key)

        // Wait for the SDK to finish importing cross-signing keys and
        // verifying the current device after recovery.
        enc.waitForE2eeInitializationTasks()

        // Enable key backup if not already active
        if (enc.backupState() != BackupState.ENABLED) {
            try { enc.enableBackups() } catch (_: Exception) { }
        }
    }

    // region Passphrase → recovery key derivation

    /**
     * Derive a Matrix recovery key from a passphrase using PBKDF2 params
     * stored in the account's secret storage.
     */
    private suspend fun deriveRecoveryKeyFromPassphrase(client: Client, passphrase: String): String {
        // 1. Get the default key ID
        val defaultKeyJson = client.accountData("m.secret_storage.default_key")
            ?: throw IllegalStateException("No default secret storage key found")
        val defaultKeyMap = org.json.JSONObject(defaultKeyJson)
        val keyId = defaultKeyMap.getString("key")

        // 2. Get the key info with PBKDF2 params
        val keyInfoJson = client.accountData("m.secret_storage.key.$keyId")
            ?: throw IllegalStateException("Secret storage key info not found for $keyId")
        val keyInfoMap = org.json.JSONObject(keyInfoJson)
        val ppObj = keyInfoMap.optJSONObject("passphrase")
            ?: throw IllegalStateException("Secret storage key has no passphrase params — use recovery key instead")
        val salt = ppObj.getString("salt")
        val iterations = ppObj.getInt("iterations")
        val bits = ppObj.optInt("bits", 256)

        // 3. PBKDF2-SHA-512 derivation
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = javax.crypto.spec.PBEKeySpec(
            passphrase.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            iterations,
            bits,
        )
        val derivedBytes = factory.generateSecret(spec).encoded

        // 4. Encode as Matrix recovery key
        return encodeRecoveryKey(derivedBytes)
    }

    /**
     * Encode raw key bytes as a Matrix recovery key (base58 with 0x8b01 prefix + parity byte).
     */
    private fun encodeRecoveryKey(keyData: ByteArray): String {
        val prefix = byteArrayOf(0x8b.toByte(), 0x01)
        val buf = prefix + keyData
        var parity: Byte = 0
        for (b in buf) parity = (parity.toInt() xor b.toInt()).toByte()
        val full = buf + byteArrayOf(parity)
        val encoded = base58Encode(full)
        return encoded.chunked(4).joinToString(" ")
    }

    private val base58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray()

    private fun base58Encode(data: ByteArray): String {
        var bytes = data.toMutableList()
        val result = mutableListOf<Char>()

        while (bytes.isNotEmpty()) {
            var carry = 0
            val newBytes = mutableListOf<Byte>()
            for (b in bytes) {
                carry = carry * 256 + (b.toInt() and 0xFF)
                if (newBytes.isNotEmpty() || carry / 58 > 0) {
                    newBytes.add((carry / 58).toByte())
                }
                carry %= 58
            }
            result.add(base58Alphabet[carry])
            bytes = newBytes
        }

        // Preserve leading zeros
        for (b in data) {
            if (b.toInt() != 0) break
            result.add(base58Alphabet[0])
        }

        return result.reversed().joinToString("")
    }
    // endregion

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

    // ── Presence ─────────────────────────────────────────

    suspend fun setPresence(presence: String, statusMsg: String?) {
        val session = sessionStore.load() ?: throw IllegalStateException("Not logged in")
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val encodedUserId = URLEncoder.encode(session.userId, "UTF-8")
        val url = URL("$baseUrl/_matrix/client/v3/presence/$encodedUserId/status")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        val body = org.json.JSONObject()
        body.put("presence", presence)
        if (statusMsg != null) body.put("status_msg", statusMsg)
        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw Exception("setPresence failed with status $responseCode")
        }
    }

    suspend fun getPresence(userId: String): Map<String, Any?> {
        val session = sessionStore.load() ?: throw IllegalStateException("Not logged in")
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val encodedUserId = URLEncoder.encode(userId, "UTF-8")
        val url = URL("$baseUrl/_matrix/client/v3/presence/$encodedUserId/status")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw Exception("getPresence failed with status $responseCode")
        }

        val responseBody = connection.inputStream.bufferedReader().readText()
        val json = org.json.JSONObject(responseBody)
        return mapOf(
            "presence" to json.optString("presence", "offline"),
            "statusMsg" to if (json.has("status_msg")) json.getString("status_msg") else null,
            "lastActiveAgo" to if (json.has("last_active_ago")) json.getLong("last_active_ago") else null,
        )
    }

    // ── Pushers ───────────────────────────────────────────

    suspend fun setPusher(
        pushkey: String,
        kind: String?,
        appId: String,
        appDisplayName: String,
        deviceDisplayName: String,
        lang: String,
        dataUrl: String,
        dataFormat: String?,
    ) {
        val session = sessionStore.load() ?: throw IllegalStateException("Not logged in")
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val url = URL("$baseUrl/_matrix/client/v3/pushers/set")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        val dataObj = org.json.JSONObject()
        dataObj.put("url", dataUrl)
        if (dataFormat != null) dataObj.put("format", dataFormat)

        val body = org.json.JSONObject()
        body.put("pushkey", pushkey)
        body.put("kind", if (kind != null) kind else org.json.JSONObject.NULL)
        body.put("app_id", appId)
        body.put("app_display_name", appDisplayName)
        body.put("device_display_name", deviceDisplayName)
        body.put("lang", lang)
        body.put("data", dataObj)

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(body.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw Exception("setPusher failed with status $responseCode")
        }
    }

    // ── Helpers ───────────────────────────────────────────

    private fun requireClient(): Client {
        return client ?: throw IllegalStateException("Not logged in. Call login() or loginWithToken() first.")
    }

    private suspend fun getOrCreateTimeline(room: Room): org.matrix.rustcomponents.sdk.Timeline {
        return roomTimelines.getOrPut(room.id()) { room.timeline() }
    }

    private suspend fun serializeRoom(room: Room): Map<String, Any?> {
        val info = room.roomInfo()
        val membership = try {
            when (room.membership()) {
                org.matrix.rustcomponents.sdk.Membership.JOINED -> "join"
                org.matrix.rustcomponents.sdk.Membership.INVITED -> "invite"
                org.matrix.rustcomponents.sdk.Membership.LEFT -> "leave"
                org.matrix.rustcomponents.sdk.Membership.BANNED -> "ban"
                else -> "leave"
            }
        } catch (_: Exception) { "join" }
        // Check if room is a DM
        val isDirect = info.isDirect

        // Get avatar URL (mxc://)
        val avatarUrl = info.rawName?.let { null } // Rust SDK doesn't expose avatar URL via RoomInfo
        // TODO: Expose room avatar from Rust SDK when available

        val latestEvent = serializeLatestEvent(room.latestEvent(), room.id())

        val result = mutableMapOf<String, Any?>(
            "roomId" to room.id(),
            "name" to (info.displayName ?: ""),
            "topic" to info.topic,
            "memberCount" to info.joinedMembersCount.toInt(),
            "isEncrypted" to (info.encryptionState != EncryptionState.NOT_ENCRYPTED),
            "unreadCount" to (info.numUnreadMessages?.toInt() ?: 0),
            "lastEventTs" to latestEvent?.get("originServerTs"),
            "membership" to membership,
            "avatarUrl" to avatarUrl,
            "isDirect" to isDirect,
        )
        if (latestEvent != null) {
            result["latestEvent"] = latestEvent
        }
        return result
    }

    private fun serializeLatestEvent(value: LatestEventValue, roomId: String): Map<String, Any?>? {
        val timestamp: Long
        val sender: String
        val profile: ProfileDetails
        val content: TimelineItemContent

        when (value) {
            is LatestEventValue.None -> return null
            is LatestEventValue.Remote -> {
                timestamp = value.timestamp.toLong()
                sender = value.sender
                profile = value.profile
                content = value.content
            }
            is LatestEventValue.Local -> {
                timestamp = value.timestamp.toLong()
                sender = value.sender
                profile = value.profile
                content = value.content
            }
            else -> return null
        }

        val contentMap = mutableMapOf<String, Any?>()
        var eventType = "m.room.message"

        when (content) {
            is TimelineItemContent.MsgLike -> {
                when (val kind = content.content.kind) {
                    is MsgLikeKind.Message -> {
                        contentMap["body"] = kind.content.body
                        when (kind.content.msgType) {
                            is MessageType.Text -> contentMap["msgtype"] = "m.text"
                            is MessageType.Image -> contentMap["msgtype"] = "m.image"
                            is MessageType.File -> contentMap["msgtype"] = "m.file"
                            is MessageType.Audio -> contentMap["msgtype"] = "m.audio"
                            is MessageType.Video -> contentMap["msgtype"] = "m.video"
                            is MessageType.Emote -> contentMap["msgtype"] = "m.emote"
                            is MessageType.Notice -> contentMap["msgtype"] = "m.notice"
                            else -> contentMap["msgtype"] = "m.text"
                        }
                    }
                    is MsgLikeKind.UnableToDecrypt -> {
                        contentMap["body"] = "Unable to decrypt message"
                        contentMap["msgtype"] = "m.text"
                        contentMap["encrypted"] = true
                    }
                    is MsgLikeKind.Redacted -> {
                        eventType = "m.room.redaction"
                        contentMap["body"] = "Message deleted"
                    }
                    else -> eventType = "m.room.unknown"
                }
            }
            else -> eventType = "m.room.unknown"
        }

        var senderDisplayName: String? = null
        if (profile is ProfileDetails.Ready) {
            senderDisplayName = profile.displayName
        }

        val result = mutableMapOf<String, Any?>(
            "roomId" to roomId,
            "senderId" to sender,
            "type" to eventType,
            "content" to contentMap,
            "originServerTs" to timestamp,
        )
        if (senderDisplayName != null) {
            result["senderDisplayName"] = senderDisplayName
        }
        return result
    }

    /**
     * Collects timeline items from diffs, handling all diff types properly.
     * Used by getRoomMessages to gather a consistent snapshot.
     */
    /**
     * Mirrors the SDK's full timeline (including virtual/null items) so that
     * index-based diffs (Insert, Remove, Set) stay correct. The public `events`
     * property filters out nulls to return only real event items.
     */
    private inner class TimelineItemCollector(private val roomId: String) : TimelineListener {
        private val lock = Object()
        // Full mirror of the SDK timeline — null entries represent virtual items
        // (day separators, read markers, etc.) that serializeTimelineItem skips.
        private val _items = mutableListOf<MutableMap<String, Any?>?>()
        private var _updateCount = 0
        private var _lastWaitedCount = 0
        private var _pendingDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

        /** Returns only the non-null (real event) items, in timeline order. */
        val events: List<Map<String, Any?>>
            get() = synchronized(lock) { _items.filterNotNull().toList() }

        suspend fun waitForUpdate(timeoutMs: Long = 0): Boolean {
            val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>
            synchronized(lock) {
                if (_updateCount > _lastWaitedCount) {
                    _lastWaitedCount = _updateCount
                    return true
                }
                deferred = kotlinx.coroutines.CompletableDeferred()
                _pendingDeferred = deferred
            }
            return if (timeoutMs > 0) {
                try {
                    kotlinx.coroutines.withTimeout(timeoutMs) { deferred.await() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    synchronized(lock) {
                        _pendingDeferred = null
                        _lastWaitedCount = _updateCount
                    }
                    false
                }
            } else {
                deferred.await()
            }
        }

        override fun onUpdate(diff: List<TimelineDiff>) {
            synchronized(lock) {
                for (d in diff) {
                    when (d) {
                        is TimelineDiff.Reset -> {
                            _items.clear()
                            d.values.forEach { item ->
                                _items.add(serializeTimelineItem(item, roomId)?.toMutableMap())
                            }
                        }
                        is TimelineDiff.Append -> {
                            d.values.forEach { item ->
                                _items.add(serializeTimelineItem(item, roomId)?.toMutableMap())
                            }
                        }
                        is TimelineDiff.PushBack -> {
                            _items.add(serializeTimelineItem(d.value, roomId)?.toMutableMap())
                        }
                        is TimelineDiff.PushFront -> {
                            _items.add(0, serializeTimelineItem(d.value, roomId)?.toMutableMap())
                        }
                        is TimelineDiff.Set -> {
                            val idx = d.index.toInt()
                            if (idx in _items.indices) {
                                _items[idx] = serializeTimelineItem(d.value, roomId)?.toMutableMap()
                            }
                        }
                        is TimelineDiff.Insert -> {
                            val idx = minOf(d.index.toInt(), _items.size)
                            _items.add(idx, serializeTimelineItem(d.value, roomId)?.toMutableMap())
                        }
                        is TimelineDiff.Clear -> _items.clear()
                        is TimelineDiff.Remove -> {
                            val idx = d.index.toInt()
                            if (idx in _items.indices) _items.removeAt(idx)
                        }
                        is TimelineDiff.Truncate -> {
                            val len = d.length.toInt()
                            while (_items.size > len) _items.removeAt(_items.lastIndex)
                        }
                        is TimelineDiff.PopBack -> {
                            if (_items.isNotEmpty()) _items.removeAt(_items.lastIndex)
                        }
                        is TimelineDiff.PopFront -> {
                            if (_items.isNotEmpty()) _items.removeAt(0)
                        }
                        else -> {}
                    }
                }
                _updateCount++
                val pending = _pendingDeferred
                _pendingDeferred = null
                pending?.complete(true)
            }
        }
    }

    private fun serializeTimelineItem(item: TimelineItem, roomId: String): Map<String, Any?>? {
        val eventItem = item.asEvent() ?: return null
        return serializeEventTimelineItem(eventItem, roomId)
    }

    private fun serializeEventTimelineItem(eventItem: EventTimelineItem, roomId: String): Map<String, Any?>? {
        val eventId = when (val id = eventItem.eventOrTransactionId) {
            is EventOrTransactionId.EventId -> id.eventId
            is EventOrTransactionId.TransactionId -> id.transactionId
            else -> ""
        }

        val contentMap = mutableMapOf<String, Any?>()
        var eventType = "m.room.message"
        var stateKey: String? = null

        try {
            val content = eventItem.content
            when (content) {
                is TimelineItemContent.MsgLike -> {
                    val kind = content.content.kind
                    when (kind) {
                        is MsgLikeKind.Message -> {
                            contentMap["body"] = kind.content.body
                            when (val msgType = kind.content.msgType) {
                                is MessageType.Text -> {
                                    contentMap["msgtype"] = "m.text"
                                }
                                is MessageType.Image -> {
                                    contentMap["msgtype"] = "m.image"
                                    contentMap["filename"] = msgType.content.filename
                                    extractMediaUrl(msgType.content.source, contentMap)
                                }
                                is MessageType.File -> {
                                    contentMap["msgtype"] = "m.file"
                                    contentMap["filename"] = msgType.content.filename
                                    extractMediaUrl(msgType.content.source, contentMap)
                                }
                                is MessageType.Audio -> {
                                    contentMap["msgtype"] = "m.audio"
                                    contentMap["filename"] = msgType.content.filename
                                    extractMediaUrl(msgType.content.source, contentMap)
                                }
                                is MessageType.Video -> {
                                    contentMap["msgtype"] = "m.video"
                                    contentMap["filename"] = msgType.content.filename
                                    extractMediaUrl(msgType.content.source, contentMap)
                                }
                                is MessageType.Emote -> {
                                    contentMap["msgtype"] = "m.emote"
                                }
                                is MessageType.Notice -> {
                                    contentMap["msgtype"] = "m.notice"
                                }
                                else -> {
                                    contentMap["msgtype"] = "m.text"
                                }
                            }
                        }
                        is MsgLikeKind.UnableToDecrypt -> {
                            contentMap["body"] = "Unable to decrypt message"
                            contentMap["msgtype"] = "m.text"
                            contentMap["encrypted"] = true
                        }
                        is MsgLikeKind.Redacted -> {
                            eventType = "m.room.redaction"
                            contentMap["body"] = "Message deleted"
                        }
                        is MsgLikeKind.Other -> {
                            eventType = kind.eventType.toString()
                        }
                        else -> {}
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
                is TimelineItemContent.RoomMembership -> {
                    eventType = "m.room.member"
                    stateKey = content.userId
                    val membership = when (content.change) {
                        MembershipChange.JOINED, MembershipChange.INVITATION_ACCEPTED -> "join"
                        MembershipChange.LEFT -> "leave"
                        MembershipChange.BANNED, MembershipChange.KICKED_AND_BANNED -> "ban"
                        MembershipChange.INVITED -> "invite"
                        MembershipChange.KICKED -> "leave"
                        MembershipChange.UNBANNED -> "leave"
                        else -> "join"
                    }
                    contentMap["membership"] = membership
                    contentMap["displayname"] = content.userDisplayName ?: content.userId
                }
                is TimelineItemContent.State -> {
                    stateKey = content.stateKey
                    when (content.content) {
                        is OtherState.RoomCreate -> eventType = "m.room.create"
                        is OtherState.RoomName -> eventType = "m.room.name"
                        is OtherState.RoomTopic -> eventType = "m.room.topic"
                        else -> eventType = "m.room.unknown"
                    }
                }
                else -> {}
            }
        } catch (e: Exception) {
            android.util.Log.e("CapMatrix", "Error serializing timeline item: ${e.message}", e)
        }

        // Determine delivery/read status
        // Combine SDK readReceipts (usually empty with sliding sync) with our v2 receipt cache
        var status: String?
        var readBy: List<String>?
        try {
            val sendState = eventItem.localSendState
            if (sendState is org.matrix.rustcomponents.sdk.EventSendState.NotSentYet ||
                sendState is org.matrix.rustcomponents.sdk.EventSendState.SendingFailed) {
                status = "sending"
                readBy = null
            } else {
                // Merge SDK receipts + cache
                val allReaders = mutableSetOf<String>()
                // From SDK (may be empty with sliding sync)
                try {
                    val sdkReceipts = eventItem.readReceipts
                    allReaders.addAll(sdkReceipts.keys.filter { it != eventItem.sender })
                } catch (_: Exception) {}
                // From our v2 receipt cache
                val cachedReaders = receiptCache[roomId]?.get(eventId)
                if (cachedReaders != null) {
                    allReaders.addAll(cachedReaders.filter { it != eventItem.sender })
                }
                readBy = if (allReaders.isNotEmpty()) allReaders.toList() else null
                status = if (allReaders.isNotEmpty()) "read" else "sent"
            }
        } catch (e: Exception) {
            android.util.Log.e("CapMatrix", "Error reading status for $eventId: ${e.message}")
            readBy = null
            status = "sent"
        }

        // Build unsigned dict — include transaction_id when available
        val unsigned: Map<String, Any?>? = when (val id = eventItem.eventOrTransactionId) {
            is EventOrTransactionId.TransactionId -> mapOf("transaction_id" to id.transactionId)
            else -> null
        }

        val result = mutableMapOf<String, Any?>(
            "eventId" to eventId,
            "roomId" to roomId,
            "senderId" to eventItem.sender,
            "type" to eventType,
            "content" to contentMap,
            "originServerTs" to eventItem.timestamp.toLong(),
            "status" to status,
            "readBy" to readBy,
        )
        if (stateKey != null) {
            result["stateKey"] = stateKey
        }
        if (unsigned != null) {
            result["unsigned"] = unsigned
        }
        return result
    }

    // ── Receipt Sync (parallel v2 sync for read receipts) ──────

    private fun startReceiptSync(onReceipt: (roomId: String, eventId: String, userId: String) -> Unit) {
        val session = sessionStore.load()
        if (session == null) {
            android.util.Log.e("CapMatrix", "receiptSync: NO SESSION FOUND, cannot start receipt sync")
            return
        }
        android.util.Log.d("CapMatrix", "receiptSync: session loaded, userId=${session.userId}, homeserver=${session.homeserverUrl}")
        receiptSyncJob?.cancel()
        receiptSyncJob = scope.launch {
            val baseUrl = session.homeserverUrl.trimEnd('/')
            val token = session.accessToken
            val userId = session.userId

            android.util.Log.d("CapMatrix", "receiptSync: starting, uploading filter...")

            val filterId = uploadSyncFilter(baseUrl, token, userId)
            android.util.Log.d("CapMatrix", "receiptSync: filterId=$filterId")

            var since: String? = null
            val apiPaths = listOf("/_matrix/client/v3/sync", "/_matrix/client/r0/sync")
            var workingPath: String? = null

            for (apiPath in apiPaths) {
                if (!isActive) return@launch
                try {
                    val url = buildSyncUrl(baseUrl, apiPath, filterId, since = null, timeout = 0)
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 30_000
                        readTimeout = 30_000
                    }
                    try {
                        val code = conn.responseCode
                        if (code == 200) {
                            val body = conn.inputStream.bufferedReader().readText()
                            workingPath = apiPath
                            val json = org.json.JSONObject(body)
                            since = json.optString("next_batch", null)
                            processReceiptResponse(body, onReceipt)
                            android.util.Log.d("CapMatrix", "receiptSync: $apiPath works, since=$since")
                            break
                        } else {
                            val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "" }
                            android.util.Log.d("CapMatrix", "receiptSync: $apiPath returned HTTP $code: ${errBody?.take(500)}")
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    android.util.Log.d("CapMatrix", "receiptSync: $apiPath failed: ${e.message}")
                }
            }

            if (workingPath == null) {
                android.util.Log.d("CapMatrix", "receiptSync: no working sync endpoint found, giving up")
                return@launch
            }

            android.util.Log.d("CapMatrix", "receiptSync: entering long-poll loop on $workingPath")

            while (isActive) {
                try {
                    val url = buildSyncUrl(baseUrl, workingPath!!, filterId, since, timeout = 30000)
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 60_000
                        readTimeout = 60_000
                    }
                    try {
                        val code = conn.responseCode
                        if (code != 200) {
                            val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "" }
                            android.util.Log.d("CapMatrix", "receiptSync: HTTP $code: ${errBody?.take(300)}")
                            kotlinx.coroutines.delay(5000)
                            continue
                        }
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = org.json.JSONObject(body)
                        json.optString("next_batch", null)?.let { since = it }
                        processReceiptResponse(body, onReceipt)
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Exception) {
                    android.util.Log.d("CapMatrix", "receiptSync: error: ${e.message}")
                    if (isActive) kotlinx.coroutines.delay(5000)
                }
            }
            android.util.Log.d("CapMatrix", "receiptSync: loop ended")
        }
    }

    private fun uploadSyncFilter(baseUrl: String, accessToken: String, userId: String): String? {
        return try {
            val encodedUserId = URLEncoder.encode(userId, "UTF-8")
            val url = URL("$baseUrl/_matrix/client/v3/user/$encodedUserId/filter")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
            }
            val filterJson = """{"room":{"timeline":{"limit":0},"state":{"types":[]},"ephemeral":{"types":["m.receipt"]}},"presence":{"types":[]}}"""
            conn.outputStream.use { OutputStreamWriter(it).apply { write(filterJson); flush() } }
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(body)
                json.optString("filter_id", null)
            } else {
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "" }
                android.util.Log.d("CapMatrix", "receiptSync: filter upload HTTP $code: ${errBody?.take(300)}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.d("CapMatrix", "receiptSync: filter upload failed: ${e.message}")
            null
        }
    }

    private fun buildSyncUrl(baseUrl: String, apiPath: String, filterId: String?, since: String?, timeout: Int): String {
        val sb = StringBuilder("$baseUrl$apiPath?timeout=$timeout")
        if (filterId != null) {
            sb.append("&filter=").append(URLEncoder.encode(filterId, "UTF-8"))
        } else {
            val inlineFilter = """{"room":{"timeline":{"limit":0},"state":{"types":[]},"ephemeral":{"types":["m.receipt"]}},"presence":{"types":[]}}"""
            sb.append("&filter=").append(URLEncoder.encode(inlineFilter, "UTF-8"))
        }
        if (since != null) {
            sb.append("&since=").append(URLEncoder.encode(since, "UTF-8"))
        }
        return sb.toString()
    }

    private fun processReceiptResponse(body: String, onReceipt: (roomId: String, eventId: String, userId: String) -> Unit) {
        try {
            val json = org.json.JSONObject(body)
            val join = json.optJSONObject("rooms")?.optJSONObject("join") ?: return
            val myUserId = try { client?.userId() } catch (_: Exception) { null }
            for (roomId in join.keys()) {
                val roomData = join.optJSONObject(roomId) ?: continue
                val ephemeral = roomData.optJSONObject("ephemeral") ?: continue
                val events = ephemeral.optJSONArray("events") ?: continue
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    if (event.optString("type") != "m.receipt") continue
                    // Content format: { "$eventId": { "m.read": { "@user:server": { "ts": 123 } } } }
                    val content = event.optJSONObject("content") ?: continue
                    val roomReceipts = receiptCache.getOrPut(roomId) { mutableMapOf() }
                    for (eventId in content.keys()) {
                        val receiptTypes = content.optJSONObject(eventId) ?: continue
                        // Check both m.read and m.read.private
                        for (rType in listOf("m.read", "m.read.private")) {
                            val readers = receiptTypes.optJSONObject(rType) ?: continue
                            for (userId in readers.keys()) {
                                // Cache receipts from others for watermark logic
                                if (userId != myUserId) {
                                    roomReceipts.getOrPut(eventId) { mutableSetOf() }.add(userId)
                                }
                                android.util.Log.d("CapMatrix", "receiptSync: receipt roomId=$roomId eventId=$eventId userId=$userId")
                                onReceipt(roomId, eventId, userId)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CapMatrix", "receiptSync: processReceiptResponse error: ${e.message}")
        }
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

    private fun extractMediaUrl(source: org.matrix.rustcomponents.sdk.MediaSource, contentMap: MutableMap<String, Any?>) {
        try {
            val url = source.url()
            contentMap["url"] = url
        } catch (_: Exception) { }
        // Always try toJson as well — for encrypted media the url() may be empty or fail
        if (contentMap["url"] == null || (contentMap["url"] as? String).isNullOrEmpty()) {
            try {
                val json = source.toJson()
                val parsed = org.json.JSONObject(json)
                // Encrypted media has the URL nested in the JSON
                val url = parsed.optString("url", "")
                if (url.isNotEmpty()) {
                    contentMap["url"] = url
                }
            } catch (_: Exception) { }
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
