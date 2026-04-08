package de.tremaze.capacitor.matrix

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.ReceiptType
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
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.ThumbnailInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.VideoInfo
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageMessageContent
import org.matrix.rustcomponents.sdk.VideoMessageContent
import org.matrix.rustcomponents.sdk.AudioMessageContent
import org.matrix.rustcomponents.sdk.FileMessageContent
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import java.time.Duration
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val TAG = "CapMatrix"
private const val TIMING_TAG = "CapMatrixTiming"

class CapMatrix(private val context: Context) {

    @Volatile private var client: Client? = null
    @Volatile private var syncService: SyncService? = null
    private val sessionStore = MatrixSessionStore(context)
    private val subscribedRoomIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val roomTimelines = Collections.synchronizedMap(mutableMapOf<String, Timeline>())
    private val timelineListenerHandles = Collections.synchronizedList(mutableListOf<Any>())
    private val paginatingRooms = Collections.synchronizedSet(mutableSetOf<String>())
    private val oldestReturnedEventId = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val roomCreatedAtCache = Collections.synchronizedMap(mutableMapOf<String, Long>())
    @Volatile private var receiptSyncJob: Job? = null
    private val timelineListeners = Collections.synchronizedList(mutableListOf<LiveTimelineListener>())
    private val timelineListenersByRoom = Collections.synchronizedMap(mutableMapOf<String, LiveTimelineListener>())
    // Cache the latest non-self receipt per room from receiptSync, so getRoomMessages
    // can use it even when the SDK's readReceipts are empty on timeline items.
    private val latestReceiptByRoom = Collections.synchronizedMap(mutableMapOf<String, Pair<String, String>>()) // roomId -> (eventId, userId)
    private val emittedInviteRoomIds = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var currentSyncState: String = "STOPPED"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loginMutex = Mutex()
    private val getRoomsMutex = Mutex()
    @Volatile private var cachedGetRoomsResult: List<Map<String, Any?>>? = null
    @Volatile private var cachedGetRoomsTimestamp: Long = 0

    @Volatile
    private var cachedRoomIds: Set<String>? = null
    @Volatile
    private var serverJoinedRoomIds: Set<String>? = null

    // ── Auth ─────────────────────────────────────────────────────────────

    suspend fun login(homeserverUrl: String, userId: String, password: String): Map<String, String> {
        return loginMutex.withLock {
            try {
                _login(homeserverUrl, userId, password)
            } catch (e: Exception) {
                if (e.message?.contains("account in the store") == true) {
                    Log.w(TAG, "Crypto store mismatch — clearing data and retrying login")
                    clearAllData()
                    _login(homeserverUrl, userId, password)
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun _login(homeserverUrl: String, userId: String, password: String): Map<String, String> {
        val dataDir = dataDirectory()
        val cacheDir = cacheDirectory(clearFirst = true)

        val newClient = ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .sqliteStore(SqliteStoreBuilder(dataDir, dataDir))
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .autoEnableCrossSigning(true)
            .build()

        newClient.login(userId, password, "Capacitor Matrix Plugin", null)

        client = newClient
        val session = newClient.session()
        val info = SessionInfo(session.accessToken, session.userId, session.deviceId, homeserverUrl)
        sessionStore.save(info)
        return info.toMap()
    }

    suspend fun jwtLogin(
        homeserverUrl: String,
        token: String
    ): Map<String, String> {
        Log.d(TAG, "jwtLogin: acquiring loginMutex… client=${client != null}")
        return loginMutex.withLock {
            Log.d(TAG, "jwtLogin: mutex acquired")
            try {
                _jwtLogin(homeserverUrl, token)
            } catch (e: Exception) {
                if (e.message?.contains("account in the store") == true) {
                    Log.w(TAG, "jwtLogin: crypto store mismatch — clearing data and retrying")
                    clearAllData()
                    _jwtLogin(homeserverUrl, token)
                } else {
                    Log.w(TAG, "jwtLogin: failed with ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            }
        }.also { Log.d(TAG, "jwtLogin: complete, returning session for ${it["userId"]}") }
    }

    private fun decodeJwtSub(token: String): String? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
            JSONObject(String(payload, Charsets.UTF_8)).optString("sub", null)
        } catch (_: Exception) { null }
    }

    private suspend fun _jwtLogin(
        homeserverUrl: String,
        token: String
    ): Map<String, String> {
        val sub = decodeJwtSub(token)
        val stored = sessionStore.load()
        Log.d(TAG, "_jwtLogin: sub=$sub, stored userId=${stored?.userId}, stored hs=${stored?.homeserverUrl}")

        if (stored != null && sub != null) {
            val storedUser = stored.userId  // e.g. @sub:server
            val matchesUser = storedUser.startsWith("@$sub:")
            val matchesHomeserver = stored.homeserverUrl.trimEnd('/') == homeserverUrl.trimEnd('/')

            if (matchesUser && matchesHomeserver) {
                Log.d(TAG, "_jwtLogin: same user + homeserver — restoring from stored session")
                return _restoreWithCredentials(homeserverUrl, stored.accessToken, stored.userId, stored.deviceId)
            } else {
                Log.w(TAG, "_jwtLogin: different user — clearing all data before fresh exchange")
                clearAllData()
            }
        }

        Log.d(TAG, "_jwtLogin: performing fresh JWT exchange (no deviceId)")
        val creds = exchangeJwtForCredentials(homeserverUrl, token)
        Log.d(TAG, "_jwtLogin: got credentials userId=${creds.userId} deviceId=${creds.deviceId}")
        return _restoreWithCredentials(homeserverUrl, creds.accessToken, creds.userId, creds.deviceId)
    }

    private data class MatrixCredentials(
        val accessToken: String,
        val userId: String,
        val deviceId: String
    )

    private suspend fun exchangeJwtForCredentials(
        homeserverUrl: String,
        token: String,
        existingDeviceId: String? = null
    ): MatrixCredentials {
        val baseUrl = homeserverUrl.trimEnd('/')
        val url = "$baseUrl/_matrix/client/v3/login"
        Log.d(TAG, "exchangeJwt: POST $url (token length=${token.length}, deviceId=${existingDeviceId ?: "none"})")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
        }
        try {
            val payload = JSONObject().apply {
                put("type", "org.matrix.login.jwt")
                put("token", token)
                put("initial_device_display_name", "Capacitor Matrix Plugin")
                if (existingDeviceId != null) {
                    put("device_id", existingDeviceId)
                }
            }
            conn.outputStream.bufferedWriter().use { it.write(payload.toString()) }

            val statusCode = conn.responseCode
            Log.d(TAG, "exchangeJwt: response statusCode=$statusCode")
            if (statusCode != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                Log.w(TAG, "exchangeJwt: FAILED — $errorBody")
                throw MatrixBridgeError.Custom("JWT login failed (HTTP $statusCode): ${errorBody ?: "unknown error"}")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val creds = MatrixCredentials(
                accessToken = json.getString("access_token"),
                userId = json.getString("user_id"),
                deviceId = json.getString("device_id")
            )
            Log.d(TAG, "exchangeJwt: success userId=${creds.userId} deviceId=${creds.deviceId}")
            return creds
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun _restoreWithCredentials(
        homeserverUrl: String,
        accessToken: String,
        userId: String,
        deviceId: String
    ): Map<String, String> {
        Log.d(TAG, "_restoreWithCredentials: userId=$userId deviceId=$deviceId, existing client=${client != null}")
        // Stop existing sync and clean up stale references before replacing the client
        syncService?.stop()
        syncService = null
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        timelineListenerHandles.clear()
        roomTimelines.clear()
        subscribedRoomIds.clear()
        Log.d(TAG, "_restoreWithCredentials: cleaned up old state, building new client…")

        val dataDir = dataDirectory()
        val cacheDir = cacheDirectory()

        val newClient = ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .sqliteStore(SqliteStoreBuilder(dataDir, dataDir))
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .autoEnableCrossSigning(true)
            .build()
        Log.d(TAG, "_restoreWithCredentials: client built, restoring session…")

        val session = Session(
            accessToken = accessToken,
            refreshToken = null,
            userId = userId,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
            oidcData = null,
            slidingSyncVersion = SlidingSyncVersion.NATIVE
        )

        newClient.restoreSession(session)
        client = newClient
        Log.d(TAG, "_restoreWithCredentials: session restored, client set. rooms=${newClient.rooms().size}")

        // Snapshot cached rooms and fetch server's authoritative joined-rooms list
        cachedRoomIds = newClient.rooms().map { it.id() }.toSet()
        serverJoinedRoomIds = fetchJoinedRoomIds(homeserverUrl, accessToken)
        Log.d(TAG, "_restoreWithCredentials: cachedRooms=${cachedRoomIds?.size}, serverJoined=${serverJoinedRoomIds?.size}")

        val info = SessionInfo(accessToken, userId, deviceId, homeserverUrl)
        sessionStore.save(info)
        Log.d(TAG, "_restoreWithCredentials: session saved, done")
        return info.toMap()
    }

    suspend fun logout() {
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        syncService?.stop()
        syncService = null
        currentSyncState = "STOPPED"
        cancelTimelineListeners()
        timelineListenerHandles.clear()
        roomTimelines.clear()
        subscribedRoomIds.clear()
        cachedRoomIds = null
        serverJoinedRoomIds = null
        client?.logout()
        client = null
        sessionStore.clear()
    }

    suspend fun clearAllData() {
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        syncService?.stop()
        syncService = null
        currentSyncState = "STOPPED"
        cancelTimelineListeners()
        timelineListenerHandles.clear()
        roomTimelines.clear()
        subscribedRoomIds.clear()
        cachedRoomIds = null
        serverJoinedRoomIds = null
        client = null
        sessionStore.clear()
        val dataDir = context.filesDir.resolve("matrix_sdk")
        dataDir.deleteRecursively()
        val cacheDir = context.cacheDir.resolve("matrix_sdk_cache")
        cacheDir.deleteRecursively()
    }

    fun getSession(): Map<String, String>? {
        val session = sessionStore.load()
        Log.d(TAG, "getSession: stored=${session != null}, client=${client != null}")
        return session?.toMap()
    }

    suspend fun updateAccessToken(accessToken: String) {
        if (client == null) throw MatrixBridgeError.NotLoggedIn()

        // Stop sync service and clean up references
        syncService?.stop()
        syncService = null
        receiptSyncJob?.cancel()
        receiptSyncJob = null
        timelineListenerHandles.clear()
        roomTimelines.clear()
        subscribedRoomIds.clear()

        val oldSession = sessionStore.load()
            ?: throw MatrixBridgeError.Custom("No persisted session to update")

        // Build a new client pointing to the same data directory (preserves crypto store)
        val dataDir = dataDirectory()
        val cacheDir = cacheDirectory()

        val newClient = ClientBuilder()
            .homeserverUrl(oldSession.homeserverUrl)
            .sqliteStore(SqliteStoreBuilder(dataDir, dataDir))
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .autoEnableCrossSigning(true)
            .build()

        val newSession = Session(
            accessToken = accessToken,
            refreshToken = null,
            userId = oldSession.userId,
            deviceId = oldSession.deviceId,
            homeserverUrl = oldSession.homeserverUrl,
            oidcData = null,
            slidingSyncVersion = SlidingSyncVersion.NATIVE
        )

        newClient.restoreSession(newSession)
        client = newClient

        val updatedInfo = SessionInfo(accessToken, oldSession.userId, oldSession.deviceId, oldSession.homeserverUrl)
        sessionStore.save(updatedInfo)
    }

    // ── Sync ─────────────────────────────────────────────────────────────

    suspend fun startSync(
        onSyncState: (String) -> Unit,
        onMessage: (Map<String, Any?>) -> Unit,
        onRoomUpdate: (String, Map<String, Any?>) -> Unit,
        onReceipt: (roomId: String, eventId: String, userId: String) -> Unit,
        onTyping: (roomId: String, userIds: List<String>) -> Unit
    ) {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()

        Log.d(TAG, "startSync: building sync service...")
        val service = c.syncService().finish()
        syncService = service
        Log.d(TAG, "startSync: sync service built")

        val observer = object : SyncServiceStateObserver {
            override fun onUpdate(state: SyncServiceState) {
                val mapped = mapSyncState(state)
                currentSyncState = mapped
                Log.d(TAG, "SyncState changed: $state -> $mapped")
                onSyncState(mapped)
                if (mapped == "SYNCING") {
                    scope.launch {
                        subscribeToRoomTimelines(onMessage, onRoomUpdate)
                    }
                }
            }
        }
        service.state(observer)

        // Start sync in a separate coroutine (service.start() blocks)
        scope.launch {
            Log.d(TAG, "startSync: calling service.start()...")
            service.start()
            Log.d(TAG, "startSync: service.start() returned")
        }

        // Start a parallel v2 sync connection for m.receipt and m.typing ephemeral events.
        // When a receipt arrives, also notify the room's LiveTimelineListener so it can
        // update its watermark — the SDK's readReceipts on timeline items may lag behind.
        val myUserId = try { c.userId() } catch (_: Exception) { null }
        startReceiptSync(
            onReceipt = { roomId, eventId, userId ->
                onReceipt(roomId, eventId, userId)
                if (userId != myUserId) {
                    latestReceiptByRoom[roomId] = eventId to userId
                    timelineListenersByRoom[roomId]?.onExternalReceipt(eventId, userId)
                }
            },
            onTyping = onTyping,
            onRoomUpdate = onRoomUpdate
        )
    }

    private fun startReceiptSync(
        onReceipt: (roomId: String, eventId: String, userId: String) -> Unit,
        onTyping: (roomId: String, userIds: List<String>) -> Unit,
        onRoomUpdate: (String, Map<String, Any?>) -> Unit
    ) {
        val session = sessionStore.load() ?: return

        receiptSyncJob?.cancel()
        receiptSyncJob = scope.launch {
            val baseUrl = session.homeserverUrl.trimEnd('/')
            val token = session.accessToken
            val userId = session.userId

            Log.d(TAG, "receiptSync: starting, uploading filter...")

            val filterId = uploadSyncFilter(baseUrl, token, userId)
            Log.d(TAG, "receiptSync: filterId=$filterId")

            var since: String? = null
            val apiPaths = listOf("/_matrix/client/v3/sync", "/_matrix/client/r0/sync")
            var workingPath: String? = null

            for (apiPath in apiPaths) {
                if (!isActive) return@launch

                val testUrl = buildSyncUrl(baseUrl, apiPath, filterId, null, 0) ?: continue

                try {
                    val conn = (URL(testUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 30_000
                        readTimeout = 30_000
                    }

                    try {
                        val statusCode = conn.responseCode
                        if (statusCode == 200) {
                            workingPath = apiPath
                            val body = conn.inputStream.bufferedReader().readText()
                            val json = JSONObject(body)
                            since = json.optString("next_batch").takeIf { it.isNotEmpty() }
                            processReceiptResponse(json, onReceipt)
                            processTypingResponse(json, onTyping)
                            Log.d(TAG, "receiptSync: $apiPath works, since=$since")
                            break
                        } else {
                            val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                            Log.d(TAG, "receiptSync: $apiPath returned HTTP $statusCode: ${errBody?.take(500)}")
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "receiptSync: $apiPath failed: ${e.message}")
                }
            }

            val apiPath = workingPath
            if (apiPath == null) {
                Log.d(TAG, "receiptSync: no working sync endpoint found, giving up")
                return@launch
            }

            Log.d(TAG, "receiptSync: entering long-poll loop on $apiPath")

            while (isActive) {
                val syncUrl = buildSyncUrl(baseUrl, apiPath, filterId, since, 30000)
                if (syncUrl == null) {
                    Log.d(TAG, "receiptSync: invalid URL")
                    return@launch
                }

                try {
                    val conn = (URL(syncUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 60_000
                        readTimeout = 60_000
                    }

                    try {
                        val statusCode = conn.responseCode
                        if (statusCode != 200) {
                            val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                            Log.d(TAG, "receiptSync: HTTP $statusCode: ${errBody?.take(300)}")
                            kotlinx.coroutines.delay(5000)
                            continue
                        }

                        val body = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(body)
                        json.optString("next_batch").takeIf { it.isNotEmpty() }?.let { since = it }

                        processReceiptResponse(json, onReceipt)
                        processTypingResponse(json, onTyping)

                        // Check for new rooms (invites, joins from other devices)
                        checkForNewRooms(onRoomUpdate)
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.d(TAG, "receiptSync: error: ${e.message}")
                    if (isActive) {
                        kotlinx.coroutines.delay(5000)
                    }
                }
            }
            Log.d(TAG, "receiptSync: loop ended")
        }
    }

    private fun uploadSyncFilter(baseUrl: String, accessToken: String, userId: String): String? {
        val encodedUserId = URLEncoder.encode(userId, "UTF-8")
        val urlStr = "$baseUrl/_matrix/client/v3/user/$encodedUserId/filter"

        val filterJson = JSONObject().apply {
            put("room", JSONObject().apply {
                put("timeline", JSONObject().put("limit", 0))
                put("state", JSONObject().put("types", JSONArray()))
                put("ephemeral", JSONObject().put("types", JSONArray().put("m.receipt").put("m.typing")))
            })
            put("presence", JSONObject().put("types", JSONArray()))
        }

        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
            }
            try {
                conn.outputStream.use { it.write(filterJson.toString().toByteArray()) }

                val statusCode = conn.responseCode
                if (statusCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    json.optString("filter_id").takeIf { it.isNotEmpty() }
                } else {
                    val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    Log.d(TAG, "receiptSync: filter upload HTTP $statusCode: ${errBody?.take(300)}")
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "receiptSync: filter upload failed: ${e.message}")
            null
        }
    }

    private fun buildSyncUrl(
        baseUrl: String,
        apiPath: String,
        filterId: String?,
        since: String?,
        timeout: Int
    ): String? {
        val sb = StringBuilder("$baseUrl$apiPath?timeout=$timeout")
        if (filterId != null) {
            sb.append("&filter=${URLEncoder.encode(filterId, "UTF-8")}")
        } else {
            val inlineFilter = """{"room":{"timeline":{"limit":0},"state":{"types":[]},"ephemeral":{"types":["m.receipt","m.typing"]}},"presence":{"types":[]}}"""
            sb.append("&filter=${URLEncoder.encode(inlineFilter, "UTF-8")}")
        }
        if (since != null) {
            sb.append("&since=${URLEncoder.encode(since, "UTF-8")}")
        }
        return sb.toString()
    }

    private fun processReceiptResponse(
        json: JSONObject,
        onReceipt: (roomId: String, eventId: String, userId: String) -> Unit
    ) {
        try {
            val rooms = json.optJSONObject("rooms")?.optJSONObject("join") ?: return
            for (roomId in rooms.keys()) {
                val roomData = rooms.optJSONObject(roomId) ?: continue
                val ephemeral = roomData.optJSONObject("ephemeral") ?: continue
                val events = ephemeral.optJSONArray("events") ?: continue
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    if (event.optString("type") != "m.receipt") continue
                    val content = event.optJSONObject("content") ?: continue
                    for (eventId in content.keys()) {
                        val receiptTypes = content.optJSONObject(eventId) ?: continue
                        for (receiptType in listOf("m.read", "m.read.private")) {
                            val readers = receiptTypes.optJSONObject(receiptType) ?: continue
                            for (userId in readers.keys()) {
                                Log.d(TAG, "receiptSync: receipt roomId=$roomId eventId=$eventId userId=$userId")
                                onReceipt(roomId, eventId, userId)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "processReceiptResponse error: ${e.message}")
        }
    }

    private fun processTypingResponse(
        json: JSONObject,
        onTyping: (roomId: String, userIds: List<String>) -> Unit
    ) {
        try {
            val rooms = json.optJSONObject("rooms")?.optJSONObject("join") ?: return
            for (roomId in rooms.keys()) {
                val roomData = rooms.optJSONObject(roomId) ?: continue
                val ephemeral = roomData.optJSONObject("ephemeral") ?: continue
                val events = ephemeral.optJSONArray("events") ?: continue
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    if (event.optString("type") != "m.typing") continue
                    val content = event.optJSONObject("content") ?: continue
                    val userIdsArr = content.optJSONArray("user_ids") ?: continue
                    val userIds = (0 until userIdsArr.length()).mapNotNull { userIdsArr.optString(it) }
                    onTyping(roomId, userIds)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "processTypingResponse error: ${e.message}")
        }
    }

    private suspend fun subscribeToRoomTimelines(
        onMessage: (Map<String, Any?>) -> Unit,
        onRoomUpdate: (String, Map<String, Any?>) -> Unit
    ) {
        val tStart = SystemClock.elapsedRealtime()
        val c = client ?: return
        val rooms = c.rooms()

        val roomsToSubscribe = mutableListOf<Pair<Room, String>>()
        synchronized(subscribedRoomIds) {
            val alreadyCount = subscribedRoomIds.size
            for (room in rooms) {
                val roomId = room.id()
                if (subscribedRoomIds.contains(roomId)) continue
                if (room.membership() != Membership.JOINED) continue
                if (isStaleRoom(roomId)) continue
                subscribedRoomIds.add(roomId)
                roomsToSubscribe.add(room to roomId)
            }
            Log.d(TAG, "subscribeToRoomTimelines: $alreadyCount already subscribed, ${roomsToSubscribe.size} new")
        }

        if (roomsToSubscribe.isEmpty()) return

        val myUserId = try { c.userId() } catch (_: Exception) { null }
        for ((room, roomId) in roomsToSubscribe) {
            try {
                val timeline = getOrCreateTimeline(room)
                val listener = LiveTimelineListener(roomId, room, myUserId, onMessage, onRoomUpdate) {
                    paginatingRooms.contains(roomId)
                }
                val handle = timeline.addListener(listener)
                timelineListenerHandles.add(handle)
                timelineListeners.add(listener)
                timelineListenersByRoom[roomId] = listener
                Log.d(TAG, "  room $roomId: listener added")
            } catch (e: Exception) {
                subscribedRoomIds.remove(roomId)
                Log.d(TAG, "  room $roomId: FAILED: ${e.message}")
            }
        }
        val subscribeElapsed = SystemClock.elapsedRealtime() - tStart
        Log.d(TIMING_TAG, "subscribeToRoomTimelines: ${roomsToSubscribe.size} rooms subscribed in ${subscribeElapsed}ms")

        // Preload messages for all rooms in the background
        val preloadStart = SystemClock.elapsedRealtime()
        for ((_, roomId) in roomsToSubscribe) {
            scope.launch {
                val timeline = roomTimelines[roomId] ?: return@launch
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    timeline.paginateBackwards(30u)
                    Log.d(TIMING_TAG, "subscribeToRoomTimelines: preload $roomId took ${SystemClock.elapsedRealtime() - t0}ms")
                } catch (e: Exception) {
                    Log.d(TAG, "preload $roomId failed: ${e.message}")
                }
            }
        }
        Log.d(TIMING_TAG, "subscribeToRoomTimelines: total setup ${SystemClock.elapsedRealtime() - tStart}ms (preload launched async)")
    }

    /** Check for new rooms (invites, joins from other devices) and emit roomUpdated. */
    private suspend fun checkForNewRooms(onRoomUpdate: (String, Map<String, Any?>) -> Unit) {
        val c = client ?: return
        for (room in c.rooms()) {
            val roomId = room.id()
            val mem = room.membership()
            if (mem == Membership.INVITED && roomId !in emittedInviteRoomIds) {
                emittedInviteRoomIds.add(roomId)
                try {
                    val summary = serializeRoom(room)
                    Log.d(TAG, "checkForNewRooms: new invite $roomId")
                    onRoomUpdate(roomId, summary)
                } catch (_: Exception) { }
            } else if (mem == Membership.JOINED && roomId !in subscribedRoomIds) {
                try {
                    val summary = serializeRoom(room)
                    Log.d(TAG, "checkForNewRooms: new joined room $roomId")
                    onRoomUpdate(roomId, summary)
                } catch (_: Exception) { }
            }
        }
    }

    suspend fun stopSync() {
        syncService?.stop()
        syncService = null
        currentSyncState = "STOPPED"
        cancelTimelineListeners()
        subscribedRoomIds.clear()
        timelineListenerHandles.clear()
        roomTimelines.clear()
        emittedInviteRoomIds.clear()
        receiptSyncJob?.cancel()
        receiptSyncJob = null
    }

    fun getSyncState(): String = currentSyncState

    // ── Room Lookup ──────────────────────────────────────────────────────

    private fun requireRoom(roomId: String): Room {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        return c.rooms().firstOrNull { it.id() == roomId }
            ?: throw MatrixBridgeError.RoomNotFound(roomId)
    }

    private suspend fun getOrCreateTimeline(room: Room): Timeline {
        val roomId = room.id()
        synchronized(roomTimelines) {
            roomTimelines[roomId]?.let {
                Log.d(TIMING_TAG, "getOrCreateTimeline[$roomId] cache HIT")
                return it
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        val timeline = room.timeline()
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.d(TIMING_TAG, "getOrCreateTimeline[$roomId] cache MISS — room.timeline() took ${elapsed}ms")
        synchronized(roomTimelines) {
            // Double-check: another coroutine may have created it while we awaited
            roomTimelines[roomId]?.let { return it }
            roomTimelines[roomId] = timeline
        }
        return timeline
    }

    // ── Rooms ────────────────────────────────────────────────────────────

    suspend fun getRooms(): List<Map<String, Any?>> {
        Log.d(TAG, "getRooms: client=${client != null}")
        // Deduplicate rapid-fire getRooms calls: return cached result if <500ms old
        val now = System.currentTimeMillis()
        cachedGetRoomsResult?.let { cached ->
            if (now - cachedGetRoomsTimestamp < 500) {
                Log.d(TAG, "getRooms: returning cached result (${cached.size} rooms)")
                return cached
            }
        }
        return getRoomsMutex.withLock {
            // Re-check after acquiring lock — another caller may have just refreshed
            cachedGetRoomsResult?.let { cached ->
                if (System.currentTimeMillis() - cachedGetRoomsTimestamp < 500) return cached
            }
            val c = client ?: throw MatrixBridgeError.NotLoggedIn()
            val rooms = c.rooms()
            Log.d(TAG, "getRooms: SDK returned ${rooms.size} rooms total")
            // Pass 1: serialize all rooms, collect IDs needing createdAt
            val serialized = mutableListOf<MutableMap<String, Any?>>()
            val needCreatedAt = mutableListOf<String>()
            for (room in rooms) {
                val mem = room.membership()
                if (mem != Membership.JOINED && mem != Membership.INVITED) continue
                // Only apply stale-room check to joined rooms — invited rooms
                // won't appear in /joined_rooms and must not be filtered out.
                if (mem == Membership.JOINED && isStaleRoom(room.id())) continue
                try {
                    val dict = serializeRoom(room).toMutableMap()
                    if (dict["lastEventTs"] == null) {
                        needCreatedAt.add(room.id())
                    }
                    serialized.add(dict)
                } catch (e: Exception) {
                    Log.d(TAG, "getRooms: skipping ${room.id()}: ${e.message}")
                }
            }

            // Pass 2: batch-fetch createdAt concurrently for rooms without lastEventTs
            val createdAts = fetchRoomCreatedAts(needCreatedAt)

            // Pass 3: compute roomOrderTs
            val result = serialized.map { dict ->
                val roomId = dict["roomId"] as? String ?: ""
                val lastEventTs = dict["lastEventTs"] as? Long
                val createdAt = createdAts[roomId] ?: dict["createdAt"] as? Long
                if (createdAt != null) dict["createdAt"] = createdAt
                dict["roomOrderTs"] = lastEventTs ?: createdAt ?: 0L
                dict
            }

            Log.d(TAG, "getRooms: returning ${result.size} joined rooms (filtered from ${rooms.size})")
            cachedGetRoomsResult = result
            cachedGetRoomsTimestamp = System.currentTimeMillis()
            result
        }
    }

    private suspend fun fetchSingleRoomCreatedAt(roomId: String, baseUrl: String, accessToken: String): Pair<String, Long>? {
        roomCreatedAtCache[roomId]?.let { return roomId to it }
        val encodedRoomId = URLEncoder.encode(roomId, "UTF-8")
        val urlStr = "$baseUrl/_matrix/client/v3/rooms/$encodedRoomId/state"

        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                val statusCode = conn.responseCode
                if (statusCode !in 200..299) {
                    return null
                }
                val body = conn.inputStream.bufferedReader().readText()
                val events = JSONArray(body)
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    if (event.optString("type") == "m.room.create") {
                        val ts = event.optLong("origin_server_ts", 0L)
                        if (ts > 0) {
                            roomCreatedAtCache[roomId] = ts
                            return roomId to ts
                        }
                    }
                }
                null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchRoomCreatedAts(roomIds: List<String>): Map<String, Long> {
        if (roomIds.isEmpty()) return emptyMap()
        val uncached = roomIds.filter { roomCreatedAtCache[it] == null }
        if (uncached.isEmpty()) return roomIds.mapNotNull { id ->
            roomCreatedAtCache[id]?.let { id to it }
        }.toMap()

        val session = sessionStore.load() ?: return roomIds.mapNotNull { id ->
            roomCreatedAtCache[id]?.let { id to it }
        }.toMap()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val semaphore = Semaphore(5)

        uncached.map { roomId ->
            scope.async<Pair<String, Long>?> {
                semaphore.withPermit { fetchSingleRoomCreatedAt(roomId, baseUrl, session.accessToken) }
            }
        }.mapNotNull { it.await() }.forEach { (roomId, ts) ->
            roomCreatedAtCache[roomId] = ts
        }

        return roomIds.mapNotNull { id -> roomCreatedAtCache[id]?.let { id to it } }.toMap()
    }

    suspend fun getRoomMembers(roomId: String): List<Map<String, Any?>> {
        val room = requireRoom(roomId)
        val iterator = room.members()
        val result = mutableListOf<Map<String, Any?>>()
        val total = iterator.len()
        if (total == 0u) return emptyList()
        var chunk = iterator.nextChunk(minOf(total, 100u))
        while (chunk != null) {
            for (member in chunk) {
                result.add(
                    mapOf(
                        "userId" to member.userId,
                        "displayName" to member.displayName,
                        "membership" to when (member.membership) {
                            is org.matrix.rustcomponents.sdk.MembershipState.Join -> "join"
                            is org.matrix.rustcomponents.sdk.MembershipState.Invite -> "invite"
                            is org.matrix.rustcomponents.sdk.MembershipState.Leave -> "leave"
                            is org.matrix.rustcomponents.sdk.MembershipState.Ban -> "ban"
                            else -> "join"
                        },
                        "avatarUrl" to member.avatarUrl,
                    )
                )
            }
            chunk = iterator.nextChunk(minOf(total, 100u))
        }
        return result
    }

    suspend fun joinRoom(roomIdOrAlias: String): String {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val room = c.joinRoomByIdOrAlias(roomIdOrAlias, emptyList())
        val roomId = room.id()
        // Update stale-room bookkeeping so the freshly joined room isn't filtered out
        serverJoinedRoomIds = serverJoinedRoomIds?.plus(roomId)
        return roomId
    }

    suspend fun leaveRoom(roomId: String) {
        val room = requireRoom(roomId)
        room.leave()
    }

    fun forgetRoom(roomId: String) {
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
        preset: String? = null
    ): String {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val roomPreset = when (preset) {
            "trusted_private_chat" -> RoomPreset.TRUSTED_PRIVATE_CHAT
            "public_chat" -> RoomPreset.PUBLIC_CHAT
            else -> RoomPreset.PRIVATE_CHAT
        }
        val params = CreateRoomParameters(
            name = name ?: "",
            topic = topic ?: "",
            isEncrypted = isEncrypted,
            isDirect = isDirect,
            visibility = RoomVisibility.Private,
            preset = roomPreset,
            invite = invite ?: emptyList()
        )
        return c.createRoom(params)
    }

    // ── Messaging ────────────────────────────────────────────────────────

    suspend fun sendMessage(
        roomId: String, body: String, msgtype: String,
        fileUri: String? = null, fileName: String? = null, mimeType: String? = null,
        fileSize: Int? = null, duration: Int? = null, width: Int? = null, height: Int? = null,
        thumbnailUri: String? = null, thumbnailMimeType: String? = null,
        thumbnailWidth: Int? = null, thumbnailHeight: Int? = null,
        onProgress: (Double) -> Unit = {}
    ): String {
        val mediaTypes = listOf("m.image", "m.audio", "m.video", "m.file")
        if (msgtype in mediaTypes && fileUri != null && fileName != null && mimeType != null) {
            sendMedia(
                roomId, msgtype, fileUri, fileName, mimeType,
                fileSize, duration, width, height,
                caption = body, inReplyTo = null,
                thumbnailUri = thumbnailUri, thumbnailMimeType = thumbnailMimeType,
                thumbnailWidth = thumbnailWidth, thumbnailHeight = thumbnailHeight,
                onProgress = onProgress
            )
            return ""
        }
        // Text message
        val room = requireRoom(roomId)
        val timeline = getOrCreateTimeline(room)
        val content = messageEventContentFromMarkdown(body)
        timeline.send(content)
        return ""
    }

    private suspend fun sendMedia(
        roomId: String, msgtype: String,
        fileUri: String, fileName: String, mimeType: String,
        fileSize: Int?, duration: Int?, width: Int?, height: Int?,
        caption: String?, inReplyTo: String?,
        thumbnailUri: String? = null, thumbnailMimeType: String? = null,
        thumbnailWidth: Int? = null, thumbnailHeight: Int? = null,
        onProgress: (Double) -> Unit = {}
    ) {
        Log.d(TAG, "sendMedia: msgtype=$msgtype mimeType=$mimeType fileName=$fileName fileUri=${fileUri.take(80)}")

        // Read the file bytes from whatever source was provided
        val fileBytes: ByteArray = when {
            fileUri.startsWith("data:") -> {
                val commaIdx = fileUri.indexOf(',')
                if (commaIdx < 0) throw MatrixBridgeError.Custom("Invalid data URI")
                android.util.Base64.decode(fileUri.substring(commaIdx + 1), android.util.Base64.DEFAULT)
            }
            fileUri.startsWith("content://") -> {
                val uri = Uri.parse(fileUri)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw MatrixBridgeError.Custom("Cannot read content URI: $fileUri")
            }
            else -> {
                val path = if (fileUri.startsWith("file://")) fileUri.removePrefix("file://") else fileUri
                java.io.File(path).readBytes()
            }
        }
        Log.d(TAG, "sendMedia: read ${fileBytes.size} bytes")

        // Upload raw bytes to the media server with progress tracking — bypasses the Rust
        // SDK's image-decoding validation in sendImage/sendVideo/sendAudio which throws
        // InvalidAttachmentData for image formats the Rust `image` crate cannot decode
        // (e.g. HEIC, some JPEGs).
        onProgress(0.0)
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val uploadBaseUrl = session.homeserverUrl.trimEnd('/')
        val encodedUploadFileName = URLEncoder.encode(fileName, "UTF-8")
        val uploadUrl = URL("$uploadBaseUrl/_matrix/media/v3/upload?filename=$encodedUploadFileName")
        val uploadConn = uploadUrl.openConnection() as HttpURLConnection
        uploadConn.requestMethod = "POST"
        uploadConn.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        uploadConn.setRequestProperty("Content-Type", mimeType)
        uploadConn.doOutput = true
        uploadConn.setFixedLengthStreamingMode(fileBytes.size)
        uploadConn.outputStream.use { os ->
            val chunkSize = 65536
            var bytesSent = 0
            while (bytesSent < fileBytes.size) {
                val end = minOf(bytesSent + chunkSize, fileBytes.size)
                os.write(fileBytes, bytesSent, end - bytesSent)
                bytesSent = end
                onProgress(bytesSent.toDouble() / fileBytes.size.toDouble())
            }
        }
        val uploadResponseCode = uploadConn.responseCode
        if (uploadResponseCode < 200 || uploadResponseCode >= 300) {
            val errorBody = uploadConn.errorStream?.bufferedReader()?.readText() ?: ""
            throw MatrixBridgeError.Custom("Upload failed with status $uploadResponseCode: $errorBody")
        }
        val uploadResponseBody = uploadConn.inputStream.bufferedReader().readText()
        val mxcUrl = JSONObject(uploadResponseBody).getString("content_uri")
        Log.d(TAG, "sendMedia: uploaded, mxcUrl=$mxcUrl")

        // Upload thumbnail if provided (no progress tracking — thumbnails are small)
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        var thumbnailMxcUrl: String? = null
        var thumbnailSizeBytes: Int? = null
        if (thumbnailUri != null && thumbnailMimeType != null) {
            val thumbBytes: ByteArray = when {
                thumbnailUri.startsWith("data:") -> {
                    val commaIdx = thumbnailUri.indexOf(',')
                    if (commaIdx < 0) throw MatrixBridgeError.Custom("Invalid thumbnail data URI")
                    android.util.Base64.decode(thumbnailUri.substring(commaIdx + 1), android.util.Base64.DEFAULT)
                }
                thumbnailUri.startsWith("content://") -> {
                    val uri = Uri.parse(thumbnailUri)
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw MatrixBridgeError.Custom("Cannot read thumbnail content URI: $thumbnailUri")
                }
                else -> {
                    val path = if (thumbnailUri.startsWith("file://")) thumbnailUri.removePrefix("file://") else thumbnailUri
                    java.io.File(path).readBytes()
                }
            }
            val thumbMxcUrl = c.uploadMedia(thumbnailMimeType, thumbBytes, null)
            Log.d(TAG, "sendMedia: thumbnail uploaded, mxcUrl=$thumbMxcUrl")
            thumbnailMxcUrl = thumbMxcUrl
            thumbnailSizeBytes = thumbBytes.size
        }

        // Build Matrix event content as JSON (bypasses Rust SDK serialization
        // which may not include thumbnail fields in the final event)
        val baseUrl = uploadBaseUrl

        val info = JSONObject()
        info.put("mimetype", mimeType)
        info.put("size", fileSize ?: fileBytes.size)
        when (msgtype) {
            "m.image" -> {
                if (width != null) info.put("w", width)
                if (height != null) info.put("h", height)
            }
            "m.video" -> {
                if (duration != null) info.put("duration", duration)
                if (width != null) info.put("w", width)
                if (height != null) info.put("h", height)
            }
            "m.audio" -> {
                if (duration != null) info.put("duration", duration)
            }
        }
        if (thumbnailMxcUrl != null) {
            info.put("thumbnail_url", thumbnailMxcUrl)
            val thumbInfo = JSONObject()
            if (thumbnailMimeType != null) thumbInfo.put("mimetype", thumbnailMimeType)
            if (thumbnailSizeBytes != null) thumbInfo.put("size", thumbnailSizeBytes)
            if (thumbnailWidth != null) thumbInfo.put("w", thumbnailWidth)
            if (thumbnailHeight != null) thumbInfo.put("h", thumbnailHeight)
            info.put("thumbnail_info", thumbInfo)
        }

        val content = JSONObject()
        content.put("msgtype", msgtype)
        content.put("body", caption ?: fileName)
        content.put("url", mxcUrl)
        content.put("info", info)

        if (inReplyTo != null) {
            content.put("m.relates_to", JSONObject().put("m.in_reply_to", JSONObject().put("event_id", inReplyTo)))
        }

        // Send event via Matrix Client-Server API
        val txnId = java.util.UUID.randomUUID().toString()
        val encodedRoomId = URLEncoder.encode(roomId, "UTF-8")
        val url = URL("$baseUrl/_matrix/client/v3/rooms/$encodedRoomId/send/m.room.message/$txnId")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { os ->
            os.write(content.toString().toByteArray(Charsets.UTF_8))
        }
        val responseCode = conn.responseCode
        if (responseCode < 200 || responseCode >= 300) {
            val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw MatrixBridgeError.Custom("Send failed with status $responseCode: $errorBody")
        }
        Log.d(TAG, "sendMedia: sent successfully")
    }

    suspend fun editMessage(roomId: String, eventId: String, newBody: String): String {
        val room = requireRoom(roomId)
        val content = messageEventContentFromMarkdown(newBody)
        val editContent = EditedContent.RoomMessage(content)
        val timeline = getOrCreateTimeline(room)
        timeline.edit(EventOrTransactionId.EventId(eventId), editContent)
        return ""
    }

    suspend fun sendReply(
        roomId: String, body: String, replyToEventId: String, msgtype: String,
        fileUri: String? = null, fileName: String? = null, mimeType: String? = null,
        fileSize: Int? = null, duration: Int? = null, width: Int? = null, height: Int? = null,
        thumbnailUri: String? = null, thumbnailMimeType: String? = null,
        thumbnailWidth: Int? = null, thumbnailHeight: Int? = null,
        onProgress: (Double) -> Unit = {}
    ): String {
        val mediaTypes = listOf("m.image", "m.audio", "m.video", "m.file")
        if (msgtype in mediaTypes && fileUri != null && fileName != null && mimeType != null) {
            sendMedia(
                roomId, msgtype, fileUri, fileName, mimeType,
                fileSize, duration, width, height,
                caption = body, inReplyTo = replyToEventId,
                thumbnailUri = thumbnailUri, thumbnailMimeType = thumbnailMimeType,
                thumbnailWidth = thumbnailWidth, thumbnailHeight = thumbnailHeight,
                onProgress = onProgress
            )
            return ""
        }
        // Text reply
        val room = requireRoom(roomId)
        val timeline = getOrCreateTimeline(room)
        val content = messageEventContentFromMarkdown(body)
        timeline.sendReply(content, replyToEventId)
        return ""
    }

    suspend fun getRoomMessages(roomId: String, limit: Int, from: String?): Map<String, Any?> {
        val tStart = SystemClock.elapsedRealtime()
        var tPrev = tStart
        fun logTiming(checkpoint: String, count: Int? = null) {
            val now = SystemClock.elapsedRealtime()
            val delta = now - tPrev
            val total = now - tStart
            val countStr = if (count != null) " [count=$count]" else ""
            Log.d(TIMING_TAG, "getRoomMessages[$roomId] $checkpoint +${delta}ms (total ${total}ms)$countStr")
            tPrev = now
        }
        logTiming("start")

        val room = requireRoom(roomId)
        logTiming("requireRoom")

        val timeline = getOrCreateTimeline(room)
        logTiming("getOrCreateTimeline")

        // Suppress live listener while we paginate
        synchronized(paginatingRooms) { paginatingRooms.add(roomId) }

        val collector = TimelineItemCollector(roomId)
        val handle = timeline.addListener(collector)
        logTiming("addListener")

        var hitStart = false
        // Wait for the initial Reset snapshot before paginating
        val gotInitial = collector.waitForUpdate(5000)
        val countBefore = collector.events.size
        logTiming("waitInitial", countBefore)
        val isPagination = from != null

        // Reset cursor on initial load
        if (!isPagination) {
            synchronized(oldestReturnedEventId) { oldestReturnedEventId.remove(roomId) }
        }

        // Paginate when: first load with too few items, OR explicit pagination request
        if (isPagination || countBefore < limit) {
            try {
                hitStart = timeline.paginateBackwards(limit.toUShort())
                logTiming("paginateBackwards")

                if (!hitStart) {
                    // More history exists — wait for the SDK to deliver diffs
                    collector.waitForUpdate(5000)
                    logTiming("waitPagination", collector.events.size)
                }
            } catch (e: Exception) {
                // Pagination failed (e.g. expired token) — fall through and
                // return whatever events were already collected from cache.
                Log.w(TAG, "getRoomMessages: paginateBackwards failed, returning cached events: ${e.message}")
                logTiming("paginateBackwards(FAILED)")
                hitStart = true
            }
        }

        handle.cancel()
        synchronized(paginatingRooms) { paginatingRooms.remove(roomId) }

        val allEvents = collector.events
        var events: List<MutableMap<String, Any?>>

        val cursorId = synchronized(oldestReturnedEventId) { oldestReturnedEventId[roomId] }

        if (cursorId != null && from != null) {
            val cursorIdx = allEvents.indexOfFirst { (it["eventId"] as? String) == cursorId }
            if (cursorIdx >= 0) {
                val available = allEvents.subList(0, cursorIdx)
                val start = maxOf(0, available.size - limit)
                events = available.subList(start, available.size).map { it.toMutableMap() }
            } else {
                Log.d(TAG, "getRoomMessages: cursor eventId $cursorId not found in timeline")
                events = emptyList()
            }
        } else {
            val start = maxOf(0, allEvents.size - limit)
            events = allEvents.subList(start, allEvents.size).map { it.toMutableMap() }
        }

        if (events.isNotEmpty()) {
            val oldest = events.first()
            (oldest["eventId"] as? String)?.let { eid ->
                synchronized(oldestReturnedEventId) { oldestReturnedEventId[roomId] = eid }
            }
        }

        // Apply receipt watermark
        val myUserId = try { client?.userId() } catch (_: Exception) { null }
        var watermarkReadBy: List<String>? = null
        var watermarkIndex = -1
        for (i in events.indices.reversed()) {
            val evt = events[i]
            if (evt["senderId"] as? String == myUserId) {
                val rb = evt["readBy"] as? List<*>
                if (rb != null && rb.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    watermarkReadBy = rb as List<String>
                    watermarkIndex = i
                    break
                }
            }
        }
        if (watermarkReadBy != null && watermarkIndex >= 0) {
            for (i in 0 until watermarkIndex) {
                if (events[i]["senderId"] as? String == myUserId) {
                    val existing = events[i]["readBy"] as? List<*>
                    if (existing == null || existing.isEmpty()) {
                        events[i]["status"] = "read"
                        events[i]["readBy"] = watermarkReadBy
                    }
                }
            }
        }

        // Apply cached receiptSync receipt as a second watermark pass.
        // The SDK's readReceipts may be empty for the latest event, but
        // receiptSync already received the receipt in a previous sync cycle.
        latestReceiptByRoom[roomId]?.let { (receiptEventId, receiptUserId) ->
            val receiptEvt = events.firstOrNull { it["eventId"] == receiptEventId }
            val receiptTs = receiptEvt?.get("originServerTs") as? Long
            if (receiptTs != null) {
                for (evt in events) {
                    val ts = evt["originServerTs"] as? Long ?: continue
                    if (ts <= receiptTs && evt["senderId"] as? String == myUserId) {
                        val existing = evt["readBy"] as? List<*>
                        if (existing == null || existing.isEmpty()) {
                            evt["status"] = "read"
                            evt["readBy"] = listOf(receiptUserId)
                        }
                    }
                }
            }
        }

        // Feed own "sent" events into the listener's tracking so that
        // receiptSync can re-emit them as "read" when a receipt arrives.
        timelineListenersByRoom[roomId]?.trackEvents(events)

        logTiming("buildResponse", events.size)

        val nextBatch: String? = if (hitStart || events.isEmpty()) null else "more"

        val result = mapOf(
            "events" to events,
            "nextBatch" to nextBatch
        )
        logTiming("done", events.size)
        return result
    }

    suspend fun markRoomAsRead(roomId: String, eventId: String) {
        val room = requireRoom(roomId)
        val timeline = getOrCreateTimeline(room)
        Log.d(TAG, "markRoomAsRead: roomId=$roomId eventId=$eventId")
        timeline.markAsRead(ReceiptType.READ)
        Log.d(TAG, "markRoomAsRead: done")
    }

    suspend fun refreshEventStatuses(roomId: String, eventIds: List<String>): List<Map<String, Any?>> {
        val room = requireRoom(roomId)
        val timeline = getOrCreateTimeline(room)
        val myUserId = try { client?.userId() } catch (_: Exception) { null }

        val items = mutableListOf<Triple<String, EventTimelineItem, Map<String, Any?>>>()
        for (eid in eventIds) {
            try {
                val eventItem = timeline.getEventTimelineItemByEventId(eid)
                val serialized = serializeEventTimelineItem(eventItem, roomId)
                if (serialized != null) {
                    items.add(Triple(eid, eventItem, serialized))
                }
            } catch (_: Exception) {
                // skip
            }
        }

        // Find the newest own event with a read receipt (watermark)
        var watermarkReadBy: List<String>? = null
        var watermarkIndex = -1
        for (i in items.indices.reversed()) {
            if (items[i].third["senderId"] as? String == myUserId) {
                val rb = items[i].third["readBy"] as? List<*>
                if (rb != null && rb.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    watermarkReadBy = rb as List<String>
                    watermarkIndex = i
                    break
                }
            }
        }

        val results = mutableListOf<Map<String, Any?>>()
        if (watermarkReadBy != null && watermarkIndex >= 0) {
            for (i in items.indices) {
                val serialized = items[i].third.toMutableMap()
                if (i < watermarkIndex && serialized["senderId"] as? String == myUserId) {
                    val existing = serialized["readBy"] as? List<*>
                    if (existing == null || existing.isEmpty()) {
                        serialized["status"] = "read"
                        serialized["readBy"] = watermarkReadBy
                    }
                }
                results.add(serialized)
            }
        } else {
            results.addAll(items.map { it.third })
        }
        return results
    }

    // ── Redactions & Reactions ────────────────────────────────────────────

    suspend fun redactEvent(roomId: String, eventId: String, reason: String?) {
        val room = requireRoom(roomId)
        val timeline = getOrCreateTimeline(room)
        timeline.redactEvent(EventOrTransactionId.EventId(eventId), reason)
    }

    suspend fun sendReaction(roomId: String, eventId: String, key: String) {
        val room = requireRoom(roomId)
        val timeline = getOrCreateTimeline(room)
        timeline.toggleReaction(EventOrTransactionId.EventId(eventId), key)
    }

    // ── User Discovery ───────────────────────────────────────────────────

    suspend fun searchUsers(searchTerm: String, limit: Int): Map<String, Any?> {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val result = c.searchUsers(searchTerm, limit.toULong())
        val users = result.results.map { u ->
            mapOf<String, Any?>(
                "userId" to u.userId,
                "displayName" to u.displayName,
                "avatarUrl" to u.avatarUrl,
            )
        }
        return mapOf(
            "results" to users,
            "limited" to result.limited,
        )
    }

    // ── Room Management ──────────────────────────────────────────────────

    suspend fun setRoomName(roomId: String, name: String) {
        val room = requireRoom(roomId)
        room.setName(name)
    }

    suspend fun setRoomTopic(roomId: String, topic: String) {
        val room = requireRoom(roomId)
        room.setTopic(topic)
    }

    suspend fun inviteUser(roomId: String, userId: String) {
        val room = requireRoom(roomId)
        room.inviteUserById(userId)
    }

    suspend fun kickUser(roomId: String, userId: String, reason: String?) {
        val room = requireRoom(roomId)
        room.kickUser(userId, reason)
    }

    suspend fun banUser(roomId: String, userId: String, reason: String?) {
        val room = requireRoom(roomId)
        room.banUser(userId, reason)
    }

    suspend fun unbanUser(roomId: String, userId: String) {
        val room = requireRoom(roomId)
        room.unbanUser(userId, null)
    }

    // ── Media URL ────────────────────────────────────────────────────────

    fun getMediaUrl(mxcUrl: String): String {
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val mxcPath = mxcUrl.removePrefix("mxc://")
        return "$baseUrl/_matrix/client/v1/media/download/$mxcPath?access_token=${session.accessToken}"
    }

    fun getThumbnailUrl(mxcUrl: String, width: Int, height: Int, method: String): String {
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val mxcPath = mxcUrl.removePrefix("mxc://")
        return "$baseUrl/_matrix/client/v1/media/thumbnail/$mxcPath?width=$width&height=$height&method=$method&access_token=${session.accessToken}"
    }

    // ── Content Upload ───────────────────────────────────────────────────

    suspend fun uploadContent(fileUri: String, fileName: String, mimeType: String): String {
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val encodedFileName = URLEncoder.encode(fileName, "UTF-8")
        val urlString = "$baseUrl/_matrix/media/v3/upload?filename=$encodedFileName"

        // Read file data from URI
        val fileData: ByteArray = if (fileUri.startsWith("data:")) {
            // data:[<mediatype>][;base64],<data>
            val commaIdx = fileUri.indexOf(',')
            if (commaIdx < 0) throw MatrixBridgeError.Custom("Invalid data URI")
            android.util.Base64.decode(fileUri.substring(commaIdx + 1), android.util.Base64.DEFAULT)
        } else if (fileUri.startsWith("content://")) {
            val uri = Uri.parse(fileUri)
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw MatrixBridgeError.Custom("Cannot read content URI: $fileUri")
        } else {
            val path = if (fileUri.startsWith("file://")) fileUri.removePrefix("file://") else fileUri
            java.io.File(path).readBytes()
        }

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", mimeType)
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(fileData) }

            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                throw MatrixBridgeError.Custom("Upload failed with status $statusCode")
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            return json.optString("content_uri").takeIf { it.isNotEmpty() }
                ?: throw MatrixBridgeError.Custom("Invalid upload response")
        } finally {
            conn.disconnect()
        }
    }

    // ── Devices ──────────────────────────────────────────────────────────

    suspend fun getDevices(): List<Map<String, Any?>> {
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val urlString = "$baseUrl/_matrix/client/v3/devices"

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        val devicesArray: JSONArray
        try {
            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                throw MatrixBridgeError.Custom("getDevices failed with status $statusCode")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            devicesArray = json.optJSONArray("devices")
                ?: throw MatrixBridgeError.Custom("Invalid devices response")
        } finally {
            conn.disconnect()
        }

        val crossSignedDeviceIds = getCrossSignedDeviceIds(baseUrl, session.accessToken, session.userId)

        return (0 until devicesArray.length()).map { i ->
            val device = devicesArray.getJSONObject(i)
            val deviceId = device.optString("device_id", "")
            mapOf<String, Any?>(
                "deviceId" to deviceId,
                "displayName" to device.opt("display_name"),
                "lastSeenTs" to device.opt("last_seen_ts"),
                "lastSeenIp" to device.opt("last_seen_ip"),
                "isCrossSigningVerified" to crossSignedDeviceIds.contains(deviceId),
            )
        }
    }

    private fun getCrossSignedDeviceIds(baseUrl: String, accessToken: String, userId: String): Set<String> {
        return try {
            val conn = (URL("$baseUrl/_matrix/client/v3/keys/query").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            try {
                val bodyJson = JSONObject().put("device_keys", JSONObject().put(userId, JSONArray()))
                conn.outputStream.use { it.write(bodyJson.toString().toByteArray()) }

                val statusCode = conn.responseCode
                if (statusCode !in 200..299) {
                    return emptySet()
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)

                val selfSigningKeys = json.optJSONObject("self_signing_keys") ?: return emptySet()
                val userSSK = selfSigningKeys.optJSONObject(userId) ?: return emptySet()
                val sskKeys = userSSK.optJSONObject("keys") ?: return emptySet()
                val selfSigningKeyIds = sskKeys.keys().asSequence().toSet()

                val deviceKeysMap = json.optJSONObject("device_keys") ?: return emptySet()
                val userDevices = deviceKeysMap.optJSONObject(userId) ?: return emptySet()

                val verifiedIds = mutableSetOf<String>()
                for (deviceId in userDevices.keys()) {
                    val deviceDict = userDevices.optJSONObject(deviceId) ?: continue
                    val signatures = deviceDict.optJSONObject("signatures") ?: continue
                    val userSignatures = signatures.optJSONObject(userId) ?: continue
                    val signatureKeyIds = userSignatures.keys().asSequence().toSet()
                    if (signatureKeyIds.intersect(selfSigningKeyIds).isNotEmpty()) {
                        verifiedIds.add(deviceId)
                    }
                }
                verifiedIds
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun deleteDevice(deviceId: String, auth: JSONObject? = null) {
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val urlString = "$baseUrl/_matrix/client/v3/devices/$deviceId"

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        try {
            val body = JSONObject()
            if (auth != null) body.put("auth", auth)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val statusCode = conn.responseCode
            if (statusCode == 401) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                val uiaData = if (errorBody != null) JSONObject(errorBody) else JSONObject()
                throw UiaRequiredException(uiaData)
            }
            if (statusCode !in 200..299) {
                throw MatrixBridgeError.Custom("deleteDevice failed with status $statusCode")
            }
        } finally {
            conn.disconnect()
        }
    }

    // ── Typing ───────────────────────────────────────────────────────────

    suspend fun sendTyping(roomId: String, isTyping: Boolean) {
        val room = requireRoom(roomId)
        room.typingNotice(isTyping)
    }

    // ── Encryption ───────────────────────────────────────────────────────

    suspend fun initializeCrypto() {
        Log.d(TAG, "initializeCrypto: client=${client != null}")
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val enc = c.encryption()
        Log.d(TAG, "initializeCrypto: verificationState=${enc.verificationState()}, backupState=${enc.backupState()}, recoveryState=${enc.recoveryState()}")
        enc.waitForE2eeInitializationTasks()
        Log.d(TAG, "initializeCrypto: done — verificationState=${enc.verificationState()}, backupState=${enc.backupState()}, recoveryState=${enc.recoveryState()}")
    }

    suspend fun getEncryptionStatus(): Map<String, Any?> {
        Log.d(TAG, "getEncryptionStatus: client=${client != null}")
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val enc = c.encryption()
        val vState = enc.verificationState()
        val backupState = enc.backupState()
        val recoveryState = enc.recoveryState()
        Log.d(TAG, "getEncryptionStatus: verification=$vState, backup=$backupState, recovery=$recoveryState")

        val isVerified = vState == org.matrix.rustcomponents.sdk.VerificationState.VERIFIED
        val isBackupEnabled = backupState == org.matrix.rustcomponents.sdk.BackupState.ENABLED ||
                backupState == org.matrix.rustcomponents.sdk.BackupState.CREATING ||
                backupState == org.matrix.rustcomponents.sdk.BackupState.RESUMING

        val ssReady: Boolean = if (recoveryState == org.matrix.rustcomponents.sdk.RecoveryState.ENABLED) {
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

    suspend fun bootstrapCrossSigning() {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        c.encryption().waitForE2eeInitializationTasks()
    }

    suspend fun verifyDevice(deviceId: String) {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val enc = c.encryption()
        enc.waitForE2eeInitializationTasks()
        Log.d(TAG, "verifyDevice($deviceId) — verificationState: ${enc.verificationState()}")
    }

    suspend fun setupKeyBackup(): Map<String, Any?> {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        c.encryption().enableBackups()
        return mapOf("exists" to true, "enabled" to true)
    }

    suspend fun getKeyBackupStatus(): Map<String, Any?> {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val existsOnServer = c.encryption().backupExistsOnServer()
        val state = c.encryption().backupState()
        val enabled = state == org.matrix.rustcomponents.sdk.BackupState.ENABLED ||
                state == org.matrix.rustcomponents.sdk.BackupState.CREATING ||
                state == org.matrix.rustcomponents.sdk.BackupState.RESUMING
        return mapOf("exists" to existsOnServer, "enabled" to enabled)
    }

    suspend fun restoreKeyBackup(recoveryKey: String?): Map<String, Any?> {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        if (recoveryKey != null) {
            c.encryption().recover(recoveryKey)
        }
        return mapOf("importedKeys" to -1)
    }

    suspend fun setupRecovery(passphrase: String?): Map<String, Any?> {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val listener = object : EnableRecoveryProgressListener {
            override fun onUpdate(status: EnableRecoveryProgress) { /* no-op */ }
        }
        val key = c.encryption().enableRecovery(false, passphrase, listener)
        return mapOf("recoveryKey" to key)
    }

    suspend fun isRecoveryEnabled(): Boolean {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val state = c.encryption().recoveryState()
        Log.d(TAG, "isRecoveryEnabled: recoveryState=$state")
        return state == org.matrix.rustcomponents.sdk.RecoveryState.ENABLED
    }

    suspend fun recoverAndSetup(recoveryKey: String?, passphrase: String?) {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        Log.d(TAG, "recoverAndSetup: hasRecoveryKey=${recoveryKey != null}, hasPassphrase=${passphrase != null}")

        val enc = c.encryption()
        Log.d(TAG, "recoverAndSetup: BEFORE — verification=${enc.verificationState()}, backup=${enc.backupState()}, recovery=${enc.recoveryState()}")

        val key: String = when {
            recoveryKey != null -> recoveryKey
            passphrase != null -> deriveRecoveryKeyFromPassphrase(c, passphrase)
            else -> throw MatrixBridgeError.MissingParameter("recoveryKey or passphrase")
        }

        Log.d(TAG, "recoverAndSetup: calling enc.recover()…")
        enc.recover(key)
        Log.d(TAG, "recoverAndSetup: recover() done, waiting for E2EE tasks…")
        enc.waitForE2eeInitializationTasks()
        Log.d(TAG, "recoverAndSetup: AFTER — verification=${enc.verificationState()}, backup=${enc.backupState()}, recovery=${enc.recoveryState()}")

        if (enc.backupState() != org.matrix.rustcomponents.sdk.BackupState.ENABLED) {
            Log.d(TAG, "recoverAndSetup: backup not enabled, calling enableBackups()…")
            try { enc.enableBackups() } catch (_: Exception) { }
        }
        Log.d(TAG, "recoverAndSetup: complete")
    }

    // ── Passphrase -> recovery key derivation ────────────────────────────

    private suspend fun deriveRecoveryKeyFromPassphrase(client: Client, passphrase: String): String {
        // 1. Get the default key ID
        val defaultKeyJson = client.accountData("m.secret_storage.default_key")
            ?: throw MatrixBridgeError.Custom("No default secret storage key found")
        val defaultKeyDict = JSONObject(defaultKeyJson)
        val keyId = defaultKeyDict.optString("key").takeIf { it.isNotEmpty() }
            ?: throw MatrixBridgeError.Custom("No default secret storage key found")

        // 2. Get the key info (contains PBKDF2 params)
        val keyInfoJson = client.accountData("m.secret_storage.key.$keyId")
            ?: throw MatrixBridgeError.Custom("Secret storage key info not found")
        val keyInfoDict = JSONObject(keyInfoJson)
        val ppDict = keyInfoDict.optJSONObject("passphrase")
            ?: throw MatrixBridgeError.Custom("Secret storage key has no passphrase params — use recovery key instead")
        val salt = ppDict.optString("salt").takeIf { it.isNotEmpty() }
            ?: throw MatrixBridgeError.Custom("Secret storage key has no passphrase params — use recovery key instead")
        val iterations = ppDict.optInt("iterations", 0)
        if (iterations == 0) throw MatrixBridgeError.Custom("Secret storage key has no passphrase params — use recovery key instead")
        val bits = ppDict.optInt("bits", 256)

        // 3. PBKDF2-SHA-512 derivation
        val derivedBytes = pbkdf2SHA512(passphrase, salt, iterations, bits / 8)

        // 4. Encode as Matrix recovery key
        return encodeRecoveryKey(derivedBytes)
    }

    private fun pbkdf2SHA512(passphrase: String, salt: String, iterations: Int, keyLengthBytes: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, keyLengthBytes * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    private fun encodeRecoveryKey(keyData: ByteArray): String {
        val prefix = byteArrayOf(0x8b.toByte(), 0x01.toByte())
        val buf = prefix + keyData
        // Calculate parity (XOR of all bytes)
        var parity: Byte = 0
        for (b in buf) parity = (parity.toInt() xor b.toInt()).toByte()
        val full = buf + parity
        // Base58 encode and insert spaces every 4 chars
        val encoded = base58Encode(full)
        val spaced = StringBuilder()
        for ((i, ch) in encoded.withIndex()) {
            if (i > 0 && i % 4 == 0) spaced.append(' ')
            spaced.append(ch)
        }
        return spaced.toString()
    }

    private fun base58Encode(data: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
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
            result.add(alphabet[carry])
            bytes = newBytes
        }

        // Preserve leading zeros
        for (b in data) {
            if (b.toInt() != 0) break
            result.add(alphabet[0])
        }

        return result.reversed().joinToString("")
    }

    suspend fun resetRecoveryKey(passphrase: String?): Map<String, Any?> {
        val c = client ?: throw MatrixBridgeError.NotLoggedIn()
        val key = c.encryption().resetRecoveryKey()
        return mapOf("recoveryKey" to key)
    }

    fun exportRoomKeys(passphrase: String): String {
        throw MatrixBridgeError.NotSupported("exportRoomKeys")
    }

    fun importRoomKeys(data: String, passphrase: String): Int {
        throw MatrixBridgeError.NotSupported("importRoomKeys")
    }

    // ── Presence ─────────────────────────────────────────────────────────

    suspend fun setPresence(presence: String, statusMsg: String?) {
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val encodedUserId = URLEncoder.encode(session.userId, "UTF-8")
        val urlString = "$baseUrl/_matrix/client/v3/presence/$encodedUserId/status"

        val bodyJson = JSONObject().apply {
            put("presence", presence)
            if (statusMsg != null) put("status_msg", statusMsg)
        }

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(bodyJson.toString().toByteArray()) }
            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                throw MatrixBridgeError.Custom("setPresence failed with status $statusCode")
            }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getPresence(userId: String): Map<String, Any?> {
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val encodedUserId = URLEncoder.encode(userId, "UTF-8")
        val urlString = "$baseUrl/_matrix/client/v3/presence/$encodedUserId/status"

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                throw MatrixBridgeError.Custom("getPresence failed with status $statusCode")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)

            val result = mutableMapOf<String, Any?>(
                "presence" to (json.optString("presence", "offline"))
            )
            json.optString("status_msg").takeIf { it.isNotEmpty() }?.let { result["statusMsg"] = it }
            if (json.has("last_active_ago")) result["lastActiveAgo"] = json.optInt("last_active_ago")
            return result
        } finally {
            conn.disconnect()
        }
    }

    // ── Pushers ──────────────────────────────────────────────────────────

    suspend fun setPusher(
        pushkey: String,
        kind: String?,
        appId: String,
        appDisplayName: String,
        deviceDisplayName: String,
        lang: String,
        dataUrl: String,
        dataFormat: String?
    ) {
        val session = sessionStore.load() ?: throw MatrixBridgeError.NotLoggedIn()
        val baseUrl = session.homeserverUrl.trimEnd('/')
        val urlString = "$baseUrl/_matrix/client/v3/pushers/set"

        val dataObj = JSONObject().apply {
            put("url", dataUrl)
            if (dataFormat != null) put("format", dataFormat)
        }

        val bodyJson = JSONObject().apply {
            put("pushkey", pushkey)
            put("kind", kind ?: JSONObject.NULL)
            put("app_id", appId)
            put("app_display_name", appDisplayName)
            put("device_display_name", deviceDisplayName)
            put("lang", lang)
            put("data", dataObj)
        }

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(bodyJson.toString().toByteArray()) }
            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                throw MatrixBridgeError.Custom("setPusher failed with status $statusCode")
            }
        } finally {
            conn.disconnect()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun isStaleRoom(roomId: String): Boolean {
        val serverIds = serverJoinedRoomIds ?: return false
        val cachedIds = cachedRoomIds ?: return false
        return cachedIds.contains(roomId) && !serverIds.contains(roomId)
    }

    private suspend fun fetchJoinedRoomIds(homeserverUrl: String, accessToken: String): Set<String>? {
        val baseUrl = homeserverUrl.trimEnd('/')
        return try {
            val conn = (URL("$baseUrl/_matrix/client/v3/joined_rooms").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                val statusCode = conn.responseCode
                if (statusCode != 200) {
                    return null
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val rooms = json.optJSONArray("joined_rooms") ?: return null
                (0 until rooms.length()).mapNotNull { rooms.optString(it) }.toSet()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun dataDirectory(): String {
        val dir = context.filesDir.resolve("matrix_sdk")
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun cacheDirectory(clearFirst: Boolean = false): String {
        val dir = context.cacheDir.resolve("matrix_sdk_cache")
        if (clearFirst) {
            dir.deleteRecursively()
        }
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun cancelTimelineListeners() {
        synchronized(timelineListeners) {
            for (listener in timelineListeners) {
                listener.cancel()
            }
            timelineListeners.clear()
        }
        timelineListenersByRoom.clear()
    }

    fun destroy() {
        scope.cancel()
    }

    companion object {
        suspend fun serializeRoom(room: Room): Map<String, Any?> {
            val info = room.roomInfo()
            val membership = when (room.membership()) {
                Membership.JOINED -> "join"
                Membership.INVITED -> "invite"
                Membership.LEFT -> "leave"
                else -> "join"
            }
            val isDirect = info.isDirect

            val latestEvent = room.latestEvent()
            val latestEventDict = serializeLatestEventValue(latestEvent, room.id())

            val lastEventTs = latestEventDict?.get("originServerTs") as? Long

            val dict = mutableMapOf<String, Any?>(
                "roomId" to room.id(),
                "name" to (info.displayName ?: ""),
                "topic" to info.topic,
                "memberCount" to (info.joinedMembersCount?.toInt() ?: 0),
                "isEncrypted" to room.isEncrypted(),
                "unreadCount" to (info.numUnreadMessages?.toInt() ?: 0),
                "lastEventTs" to lastEventTs,
                "membership" to membership,
                "avatarUrl" to null,
                "isDirect" to isDirect,
                "roomOrderTs" to lastEventTs,
            )
            if (latestEventDict != null) {
                dict["latestEvent"] = latestEventDict
            }
            return dict
        }

        private fun mapSyncState(state: SyncServiceState): String {
            return when (state) {
                SyncServiceState.IDLE -> "STOPPED"
                SyncServiceState.RUNNING -> "SYNCING"
                SyncServiceState.TERMINATED -> "STOPPED"
                SyncServiceState.ERROR -> "ERROR"
                else -> "ERROR"
            }
        }
    }
}

// ── Timeline Serialization Helpers ───────────────────────────────────────

private fun serializeLatestEventValue(value: LatestEventValue, roomId: String): Map<String, Any?>? {
    val timestamp: ULong
    val sender: String
    val profile: ProfileDetails
    val content: TimelineItemContent

    when (value) {
        is LatestEventValue.None -> return null
        is LatestEventValue.Remote -> {
            timestamp = value.timestamp
            sender = value.sender
            profile = value.profile
            content = value.content
        }
        is LatestEventValue.Local -> {
            timestamp = value.timestamp
            sender = value.sender
            profile = value.profile
            content = value.content
        }
        else -> return null
    }

    var contentDict = mutableMapOf<String, Any?>()
    var eventType = "m.room.message"

    when (content) {
        is TimelineItemContent.MsgLike -> {
            when (val kind = content.content.kind) {
                is MsgLikeKind.Message -> {
                    contentDict["body"] = kind.content.body
                    when (val msgType = kind.content.msgType) {
                        is MessageType.Text -> contentDict["msgtype"] = "m.text"
                        is MessageType.Image -> {
                            contentDict["msgtype"] = "m.image"
                            extractMediaUrl(msgType.content.source, contentDict)
                            extractImageInfo(msgType.content, contentDict)
                        }
                        is MessageType.File -> {
                            contentDict["msgtype"] = "m.file"
                            contentDict["filename"] = msgType.content.filename
                            extractMediaUrl(msgType.content.source, contentDict)
                            extractFileInfo(msgType.content, contentDict)
                        }
                        is MessageType.Audio -> {
                            contentDict["msgtype"] = "m.audio"
                            contentDict["filename"] = msgType.content.filename
                            extractMediaUrl(msgType.content.source, contentDict)
                            extractAudioInfo(msgType.content, contentDict)
                        }
                        is MessageType.Video -> {
                            contentDict["msgtype"] = "m.video"
                            contentDict["filename"] = msgType.content.filename
                            extractMediaUrl(msgType.content.source, contentDict)
                            extractVideoInfo(msgType.content, contentDict)
                        }
                        is MessageType.Emote -> contentDict["msgtype"] = "m.emote"
                        is MessageType.Notice -> contentDict["msgtype"] = "m.notice"
                        else -> contentDict["msgtype"] = "m.text"
                    }
                }
                is MsgLikeKind.UnableToDecrypt -> {
                    contentDict["body"] = "Unable to decrypt message"
                    contentDict["msgtype"] = "m.text"
                    contentDict["encrypted"] = true
                }
                is MsgLikeKind.Redacted -> {
                    eventType = "m.room.redaction"
                    contentDict["body"] = "Message deleted"
                }
                else -> {
                    eventType = "m.room.unknown"
                }
            }
        }
        else -> {
            eventType = "m.room.unknown"
        }
    }

    var senderDisplayName: String? = null
    if (profile is ProfileDetails.Ready) {
        senderDisplayName = profile.displayName
    }

    val dict = mutableMapOf<String, Any?>(
        "roomId" to roomId,
        "senderId" to sender,
        "type" to eventType,
        "content" to contentDict,
        "originServerTs" to timestamp.toLong(),
    )
    if (senderDisplayName != null) {
        dict["senderDisplayName"] = senderDisplayName
    }
    return dict
}

private fun extractEventId(eventOrTxnId: EventOrTransactionId): String? {
    return when (eventOrTxnId) {
        is EventOrTransactionId.EventId -> eventOrTxnId.eventId
        is EventOrTransactionId.TransactionId -> eventOrTxnId.transactionId
    }
}

private fun extractMediaUrl(source: MediaSource, contentDict: MutableMap<String, Any?>) {
    val url = source.url()
    if (url.isNotEmpty()) {
        contentDict["url"] = url
    }
    // Fallback: for encrypted media, try toJson to extract the mxc URL
    if (contentDict["url"] == null || (contentDict["url"] as? String)?.isEmpty() == true) {
        try {
            val json = source.toJson()
            val parsed = JSONObject(json)
            val mxcUrl = parsed.optString("url", "")
            if (mxcUrl.isNotEmpty()) {
                contentDict["url"] = mxcUrl
            }
        } catch (_: Exception) { }
    }
}

private fun extractThumbnailFields(source: MediaSource?, info: ThumbnailInfo?): Map<String, Any?>? {
    val src = source ?: return null
    val thumbUrl = src.url()
    if (thumbUrl.isEmpty()) return null
    val result = mutableMapOf<String, Any?>("thumbnail_url" to thumbUrl)
    val thumbInfo = mutableMapOf<String, Any?>()
    if (info != null) {
        info.mimetype?.let { thumbInfo["mimetype"] = it }
        info.size?.let { thumbInfo["size"] = it.toLong() }
        info.width?.let { thumbInfo["w"] = it.toLong() }
        info.height?.let { thumbInfo["h"] = it.toLong() }
    }
    if (thumbInfo.isNotEmpty()) {
        result["thumbnail_info"] = thumbInfo
    }
    return result
}

private fun extractImageInfo(content: ImageMessageContent, contentDict: MutableMap<String, Any?>) {
    val info = content.info ?: return
    val infoDict = mutableMapOf<String, Any?>()
    info.mimetype?.let { infoDict["mimetype"] = it }
    info.size?.let { infoDict["size"] = it.toLong() }
    info.width?.let { infoDict["w"] = it.toLong() }
    info.height?.let { infoDict["h"] = it.toLong() }
    extractThumbnailFields(info.thumbnailSource, info.thumbnailInfo)?.forEach { (k, v) -> infoDict[k] = v }
    contentDict["info"] = infoDict
}

private fun extractVideoInfo(content: VideoMessageContent, contentDict: MutableMap<String, Any?>) {
    val info = content.info ?: return
    val infoDict = mutableMapOf<String, Any?>()
    info.mimetype?.let { infoDict["mimetype"] = it }
    info.size?.let { infoDict["size"] = it.toLong() }
    info.width?.let { infoDict["w"] = it.toLong() }
    info.height?.let { infoDict["h"] = it.toLong() }
    info.duration?.let { infoDict["duration"] = it.toMillis() }
    extractThumbnailFields(info.thumbnailSource, info.thumbnailInfo)?.forEach { (k, v) -> infoDict[k] = v }
    contentDict["info"] = infoDict
}

private fun extractAudioInfo(content: AudioMessageContent, contentDict: MutableMap<String, Any?>) {
    val info = content.info ?: return
    val infoDict = mutableMapOf<String, Any?>()
    info.mimetype?.let { infoDict["mimetype"] = it }
    info.size?.let { infoDict["size"] = it.toLong() }
    info.duration?.let { infoDict["duration"] = it.toMillis() }
    contentDict["info"] = infoDict
}

private fun extractFileInfo(content: FileMessageContent, contentDict: MutableMap<String, Any?>) {
    val info = content.info ?: return
    val infoDict = mutableMapOf<String, Any?>()
    info.mimetype?.let { infoDict["mimetype"] = it }
    info.size?.let { infoDict["size"] = it.toLong() }
    extractThumbnailFields(info.thumbnailSource, info.thumbnailInfo)?.forEach { (k, v) -> infoDict[k] = v }
    contentDict["info"] = infoDict
}

private fun serializeTimelineItem(item: TimelineItem, roomId: String): Map<String, Any?>? {
    val eventItem = item.asEvent() ?: return null
    return serializeEventTimelineItem(eventItem, roomId)
}

private fun serializeEventTimelineItem(eventItem: EventTimelineItem, roomId: String): Map<String, Any?>? {
    val eventId = extractEventId(eventItem.eventOrTransactionId) ?: return null

    val contentDict = mutableMapOf<String, Any?>()
    var eventType = "m.room.message"
    var stateKey: String? = null

    when (val content = eventItem.content) {
        is TimelineItemContent.MsgLike -> {
            when (val kind = content.content.kind) {
                is MsgLikeKind.Message -> {
                    contentDict["body"] = kind.content.body
                    when (val msgType = kind.content.msgType) {
                        is MessageType.Text -> contentDict["msgtype"] = "m.text"
                        is MessageType.Image -> {
                            contentDict["msgtype"] = "m.image"
                            extractMediaUrl(msgType.content.source, contentDict)
                            extractImageInfo(msgType.content, contentDict)
                        }
                        is MessageType.File -> {
                            contentDict["msgtype"] = "m.file"
                            contentDict["filename"] = msgType.content.filename
                            extractMediaUrl(msgType.content.source, contentDict)
                            extractFileInfo(msgType.content, contentDict)
                        }
                        is MessageType.Audio -> {
                            contentDict["msgtype"] = "m.audio"
                            contentDict["filename"] = msgType.content.filename
                            extractMediaUrl(msgType.content.source, contentDict)
                            extractAudioInfo(msgType.content, contentDict)
                        }
                        is MessageType.Video -> {
                            contentDict["msgtype"] = "m.video"
                            contentDict["filename"] = msgType.content.filename
                            extractMediaUrl(msgType.content.source, contentDict)
                            extractVideoInfo(msgType.content, contentDict)
                        }
                        is MessageType.Emote -> contentDict["msgtype"] = "m.emote"
                        is MessageType.Notice -> contentDict["msgtype"] = "m.notice"
                        else -> contentDict["msgtype"] = "m.text"
                    }
                }
                is MsgLikeKind.UnableToDecrypt -> {
                    contentDict["body"] = "Unable to decrypt message"
                    contentDict["msgtype"] = "m.text"
                    contentDict["encrypted"] = true
                }
                is MsgLikeKind.Redacted -> {
                    eventType = "m.room.redaction"
                    contentDict["body"] = "Message deleted"
                }
                else -> {
                    eventType = "m.room.unknown"
                }
            }

            // Reactions from MsgLikeContent
            val reactions = content.content.reactions
            if (reactions.isNotEmpty()) {
                contentDict["reactions"] = reactions.map { r ->
                    mapOf<String, Any?>(
                        "key" to r.key,
                        "count" to r.senders.size,
                        "senders" to r.senders.map { it.senderId },
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
            contentDict["membership"] = membership
            contentDict["displayname"] = content.userDisplayName ?: content.userId
        }
        is TimelineItemContent.State -> {
            stateKey = content.stateKey
            // TODO: distinguish m.room.create and other state events when SDK exposes state content enum
            eventType = "m.room.unknown"
        }
        else -> {
            eventType = "m.room.unknown"
        }
    }

    // Delivery/read status
    var status = "sent"
    val sendState = eventItem.localSendState
    if (sendState != null) {
        when (sendState) {
            is org.matrix.rustcomponents.sdk.EventSendState.NotSentYet -> status = "sending"
            is org.matrix.rustcomponents.sdk.EventSendState.SendingFailed -> status = "sending"
            is org.matrix.rustcomponents.sdk.EventSendState.Sent -> { /* check receipts below */ }
        }
    }

    var readBy: List<String>? = null
    val receipts = eventItem.readReceipts
    if (status == "sent") {
        val others = receipts.keys.filter { it != eventItem.sender }
        if (others.isNotEmpty()) {
            status = "read"
            readBy = others
        }
    }
    // Build unsigned dict — include transaction_id when available
    var unsignedDict: Map<String, Any?>? = null
    if (eventItem.eventOrTransactionId is EventOrTransactionId.TransactionId) {
        unsignedDict = mapOf("transaction_id" to (eventItem.eventOrTransactionId as EventOrTransactionId.TransactionId).transactionId)
    }

    val result = mutableMapOf<String, Any?>(
        "eventId" to eventId,
        "roomId" to roomId,
        "senderId" to eventItem.sender,
        "type" to eventType,
        "content" to contentDict,
        "originServerTs" to eventItem.timestamp.toLong(),
        "status" to status,
        "readBy" to readBy,
    )
    if (stateKey != null) {
        result["stateKey"] = stateKey
    }
    if (unsignedDict != null) {
        result["unsigned"] = unsignedDict
    }
    return result
}

// ── Live Timeline Listener (for sync subscriptions) ──────────────────────

private class LiveTimelineListener(
    private val roomId: String,
    private val room: Room,
    private val myUserId: String?,
    private val onMessage: (Map<String, Any?>) -> Unit,
    private val onRoomUpdate: (String, Map<String, Any?>) -> Unit,
    private val isPaginating: () -> Boolean
) : TimelineListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Timestamp-based read watermark.  "Someone read up to this timestamp."
    // Persists across diff batches so we never regress.
    private var watermarkTs: Long = -1
    private var watermarkReadBy: List<String>? = null
    // EventIds confirmed read via receiptSync.  Survives SDK re-emissions
    // (Remove+PushBack) that would otherwise overwrite the "read" status.
    private val confirmedReadEventIds = Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= 200) iterator().let { it.next(); it.remove() }
                return super.add(element)
            }
        }
    )
    // Track own "sent" events so we can re-emit them as "read" when a receipt
    // arrives via receiptSync (which runs ahead of the SDK's timeline updates).
    private val emittedOwnSent = Collections.synchronizedMap(
        object : LinkedHashMap<String, MutableMap<String, Any?>>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<String, Any?>>?) = size > 50
        }
    )
    // Coalesce rapid room updates: only one in-flight serialization at a time,
    // and at most one pending re-serialization queued behind it.
    @Volatile private var roomUpdatePending = false
    @Volatile private var roomUpdateRunning = false

    fun cancel() {
        scope.cancel()
    }

    /** Track events from getRoomMessages so onExternalReceipt can find them. */
    fun trackEvents(events: List<MutableMap<String, Any?>>) {
        for (evt in events) {
            trackOwnSent(evt)
        }
    }

    /**
     * Called from receiptSync when a receipt arrives from another user.
     * The SDK's timeline readReceipts may not reflect this yet, so we
     * update the watermark and re-emit the event as "read" directly.
     */
    fun onExternalReceipt(eventId: String, userId: String) {
        confirmedReadEventIds.add(eventId)
        val receiptEvt = emittedOwnSent[eventId]
        if (receiptEvt != null) {
            val ts = receiptEvt["originServerTs"] as? Long ?: return
            if (ts > watermarkTs) {
                watermarkTs = ts
                watermarkReadBy = listOf(userId)
            }
        }
        // Re-emit ALL tracked own events at or below the watermark as read
        val rb = watermarkReadBy ?: return
        if (watermarkTs < 0) return
        val iter = emittedOwnSent.entries.iterator()
        while (iter.hasNext()) {
            val (id, evt) = iter.next()
            val evtTs = evt["originServerTs"] as? Long ?: continue
            if (evtTs <= watermarkTs) {
                confirmedReadEventIds.add(id)
                evt["status"] = "read"
                evt["readBy"] = rb
                onMessage(evt)
                iter.remove()
            }
        }
    }

    private fun emitRoomUpdate() {
        synchronized(this) {
            if (roomUpdateRunning) {
                roomUpdatePending = true
                return
            }
            roomUpdateRunning = true
        }
        scope.launch {
            try {
                do {
                    synchronized(this@LiveTimelineListener) { roomUpdatePending = false }
                    val summary = try {
                        CapMatrix.serializeRoom(room)
                    } catch (_: Exception) {
                        mapOf<String, Any?>("roomId" to roomId)
                    }
                    onRoomUpdate(roomId, summary)
                } while (synchronized(this@LiveTimelineListener) {
                    if (roomUpdatePending) true else { roomUpdateRunning = false; false }
                })
            } catch (_: Exception) {
                synchronized(this@LiveTimelineListener) { roomUpdateRunning = false }
            }
        }
    }

    /** Check raw readReceipts for non-self users; advance the watermark if newer. */
    private fun updateWatermarkFromItem(item: TimelineItem) {
        val eventItem = item.asEvent() ?: return
        if (myUserId == null) return
        val others = eventItem.readReceipts.keys.filter { it != myUserId }
        if (others.isNotEmpty()) {
            val ts = eventItem.timestamp.toLong()
            if (ts > watermarkTs) {
                watermarkTs = ts
                watermarkReadBy = others
            }
        }
    }

    /** If this own event was sent at or before the watermark, or was already
     *  confirmed read by receiptSync, mark it read. */
    private fun applyWatermarkToEvent(evt: MutableMap<String, Any?>) {
        if (evt["senderId"] as? String != myUserId) return
        val rb = watermarkReadBy
        // Check if this specific eventId was confirmed read by receiptSync
        val eventId = evt["eventId"] as? String
        if (eventId != null && eventId in confirmedReadEventIds && rb != null) {
            evt["status"] = "read"
            evt["readBy"] = rb
            return
        }
        // Timestamp-based watermark
        if (rb == null || watermarkTs < 0) return
        val ts = evt["originServerTs"] as? Long ?: return
        if (ts <= watermarkTs) {
            val existing = evt["readBy"] as? List<*>
            if (existing == null || existing.isEmpty()) {
                evt["status"] = "read"
                evt["readBy"] = rb
            }
        }
    }

    /** Serialize, apply watermark, track, emit. */
    private fun serializeAndEmit(item: TimelineItem) {
        serializeTimelineItem(item, roomId)?.toMutableMap()?.let { evt ->
            applyWatermarkToEvent(evt)
            trackOwnSent(evt)
            onMessage(evt)
        }
    }

    private fun trackOwnSent(evt: MutableMap<String, Any?>) {
        val eventId = evt["eventId"] as? String ?: return
        if (evt["senderId"] as? String == myUserId) {
            if (evt["status"] == "sent") {
                emittedOwnSent[eventId] = evt
            } else {
                emittedOwnSent.remove(eventId)
            }
        }
    }

    override fun onUpdate(diff: List<TimelineDiff>) {
        if (isPaginating()) return
        var needsRoomUpdate = false
        val setItems = mutableListOf<TimelineItem>()
        for (d in diff) {
            when (d) {
                is TimelineDiff.Reset -> {
                    watermarkTs = -1
                    watermarkReadBy = null
                    d.values.forEach { updateWatermarkFromItem(it) }
                    d.values.forEach { serializeAndEmit(it) }
                    needsRoomUpdate = true
                }
                is TimelineDiff.Append -> {
                    d.values.forEach { updateWatermarkFromItem(it) }
                    d.values.forEach { serializeAndEmit(it) }
                    needsRoomUpdate = true
                }
                is TimelineDiff.PushBack -> {
                    val eventItem = d.value.asEvent()
                    var skipLocalEcho = false
                    if (eventItem != null && eventItem.eventOrTransactionId is EventOrTransactionId.TransactionId) {
                        skipLocalEcho = true
                    }
                    if (!skipLocalEcho) {
                        updateWatermarkFromItem(d.value)
                        serializeAndEmit(d.value)
                    }
                    needsRoomUpdate = true
                }
                is TimelineDiff.PushFront -> {
                    updateWatermarkFromItem(d.value)
                    serializeAndEmit(d.value)
                }
                is TimelineDiff.Set -> {
                    setItems.add(d.value)
                }
                is TimelineDiff.Insert -> {
                    updateWatermarkFromItem(d.value)
                    serializeAndEmit(d.value)
                    needsRoomUpdate = true
                }
                is TimelineDiff.Remove -> { }
                else -> { }
            }
        }
        if (setItems.isNotEmpty()) {
            setItems.forEach { updateWatermarkFromItem(it) }
            for (item in setItems) {
                serializeTimelineItem(item, roomId)?.toMutableMap()?.let { evt ->
                    applyWatermarkToEvent(evt)
                    trackOwnSent(evt)
                    onMessage(evt)
                    val rb = evt["readBy"] as? List<*>
                    if (rb != null && rb.isNotEmpty()) needsRoomUpdate = true
                }
            }
        }
        if (needsRoomUpdate) {
            emitRoomUpdate()
        }
    }
}

// ── Timeline Item Collector (for pagination/one-shot reads) ──────────────

private class TimelineItemCollector(private val roomId: String) : TimelineListener {
    private val lock = Any()
    private var _items = mutableListOf<Map<String, Any?>?>()
    private var _updateDeferred: CompletableDeferred<Boolean>? = null
    private var _updateCount = 0
    private var _lastWaitedCount = 0

    val events: List<Map<String, Any?>>
        get() = synchronized(lock) {
            _items.filterNotNull()
        }

    suspend fun waitForUpdate(timeoutMs: Long = 0): Boolean {
        synchronized(lock) {
            if (_updateCount > _lastWaitedCount) {
                _lastWaitedCount = _updateCount
                return true
            }
            _updateDeferred = CompletableDeferred()
        }

        val deferred = synchronized(lock) { _updateDeferred!! }

        val result = if (timeoutMs > 0) {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } else {
            deferred.await()
        }

        if (!result) {
            // Timeout — clear and complete the pending deferred
            synchronized(lock) {
                _updateDeferred?.complete(false)
                _updateDeferred = null
            }
        }
        synchronized(lock) {
            _lastWaitedCount = _updateCount
        }
        return result
    }

    override fun onUpdate(diff: List<TimelineDiff>) {
        var deferred: CompletableDeferred<Boolean>?
        synchronized(lock) {
            for (d in diff) {
                when (d) {
                    is TimelineDiff.Reset -> {
                        _items.clear()
                        d.values.forEach { item ->
                            _items.add(serializeTimelineItem(item, roomId))
                        }
                    }
                    is TimelineDiff.Append -> {
                        d.values.forEach { item ->
                            _items.add(serializeTimelineItem(item, roomId))
                        }
                    }
                    is TimelineDiff.PushBack -> {
                        _items.add(serializeTimelineItem(d.value, roomId))
                    }
                    is TimelineDiff.PushFront -> {
                        _items.add(0, serializeTimelineItem(d.value, roomId))
                    }
                    is TimelineDiff.Set -> {
                        val idx = d.index.toInt()
                        if (idx in _items.indices) {
                            _items[idx] = serializeTimelineItem(d.value, roomId)
                        }
                    }
                    is TimelineDiff.Insert -> {
                        val idx = minOf(d.index.toInt(), _items.size)
                        _items.add(idx, serializeTimelineItem(d.value, roomId))
                    }
                    is TimelineDiff.Clear -> {
                        _items.clear()
                    }
                    is TimelineDiff.Remove -> {
                        val idx = d.index.toInt()
                        if (idx in _items.indices) {
                            _items.removeAt(idx)
                        }
                    }
                    is TimelineDiff.Truncate -> {
                        val len = d.length.toInt()
                        while (_items.size > len) _items.removeAt(_items.size - 1)
                    }
                    is TimelineDiff.PopBack -> {
                        if (_items.isNotEmpty()) _items.removeAt(_items.size - 1)
                    }
                    is TimelineDiff.PopFront -> {
                        if (_items.isNotEmpty()) _items.removeAt(0)
                    }
                }
            }
            _updateCount++
            deferred = _updateDeferred
            _updateDeferred = null
        }
        deferred?.complete(true)
    }
}

// ── Errors ───────────────────────────────────────────────────────────────

sealed class MatrixBridgeError(message: String) : Exception(message) {
    class NotLoggedIn : MatrixBridgeError("Not logged in. Call login() or jwtLogin() first.")
    class RoomNotFound(roomId: String) : MatrixBridgeError("Room $roomId not found")
    class NotSupported(method: String) : MatrixBridgeError("$method is not supported in this version of the Matrix SDK")
    class MissingParameter(name: String) : MatrixBridgeError("Missing required parameter: $name")
    class Custom(message: String) : MatrixBridgeError(message)
}

class UiaRequiredException(val data: JSONObject) : Exception("UIA required")

// ── Session Store ────────────────────────────────────────────────────────

data class SessionInfo(
    val accessToken: String,
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String
) {
    fun toMap(): Map<String, String> = mapOf(
        "accessToken" to accessToken,
        "userId" to userId,
        "deviceId" to deviceId,
        "homeserverUrl" to homeserverUrl
    )
}

class MatrixSessionStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "de.tremaze.matrix.session",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
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
