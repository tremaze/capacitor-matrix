# @tremaze/capacitor-matrix

A [Capacitor](https://capacitorjs.com/) plugin that provides a unified API for the [Matrix](https://matrix.org/) communication protocol across Web, Android, and iOS.

- **Web** — powered by [matrix-js-sdk](https://github.com/matrix-org/matrix-js-sdk)
- **Android** — powered by [matrix-rust-sdk](https://github.com/nicegram/nicegram-android-nicegram-matrix-sdk) (Kotlin bindings)
- **iOS** — powered by [matrix-rust-components-swift](https://github.com/matrix-org/matrix-rust-components-swift)

## Features

- **Authentication** — password login, token-based login, session persistence
- **Real-time sync** — incremental sync with state change events
- **Rooms** — create, join, leave, list rooms with summaries
- **Messaging** — send/receive text, notices, emotes; paginated message history
- **Media** — upload and send images, audio, video, and files; resolve `mxc://` URLs
- **Reactions & Redactions** — react to messages, delete (redact) events
- **Room management** — rename rooms, set topics, invite/kick/ban/unban users
- **Typing indicators** — send and receive typing notifications
- **Presence** — set and query online/offline/unavailable status
- **End-to-end encryption** — cross-signing, key backup, secret storage, recovery, room key export/import

## Requirements

| Platform | Minimum Version |
| -------- | --------------- |
| Capacitor | 8.0.0 |
| iOS | 16.0 |
| Android | API 24 (Android 7.0) |

## Install

```bash
npm install @tremaze/capacitor-matrix
npx cap sync
```

### iOS

No additional setup required. The Swift Package Manager dependency on `matrix-rust-components-swift` is resolved automatically.

### Android

The plugin uses `matrix-rust-sdk` via Maven. Ensure your project's `build.gradle` includes `mavenCentral()` in the repositories block (this is the default for new Capacitor projects).

## Usage

### Import

```typescript
import { Matrix } from '@tremaze/capacitor-matrix';
```

### Authentication

```typescript
// Login with username and password
const session = await Matrix.login({
  homeserverUrl: 'https://matrix.example.com',
  userId: '@alice:example.com',
  password: 'secret',
});
console.log('Logged in as', session.userId);

// Or restore a session using a stored access token
const session = await Matrix.loginWithToken({
  homeserverUrl: 'https://matrix.example.com',
  accessToken: 'syt_...',
  userId: '@alice:example.com',
  deviceId: 'ABCDEF',
});

// Check for an existing session (persisted in localStorage on web)
const existing = await Matrix.getSession();
if (existing) {
  console.log('Already logged in as', existing.userId);
}

// Logout
await Matrix.logout();
```

### Sync

Start the background sync loop to receive real-time updates:

```typescript
// Listen for sync state changes
await Matrix.addListener('syncStateChange', ({ state, error }) => {
  console.log('Sync state:', state); // 'INITIAL' | 'SYNCING' | 'ERROR' | 'STOPPED'
  if (error) console.error('Sync error:', error);
});

await Matrix.startSync();

// Later, stop syncing
await Matrix.stopSync();
```

### Rooms

```typescript
// List joined rooms
const { rooms } = await Matrix.getRooms();
rooms.forEach((room) => {
  console.log(room.name, `(${room.memberCount} members)`);
});

// Create a room
const { roomId } = await Matrix.createRoom({
  name: 'Project Chat',
  topic: 'Discussion about the project',
  isEncrypted: true,
  invite: ['@bob:example.com'],
});

// Join a room by ID or alias
await Matrix.joinRoom({ roomIdOrAlias: '#general:example.com' });

// Leave a room
await Matrix.leaveRoom({ roomId: '!abc:example.com' });

// Get room members
const { members } = await Matrix.getRoomMembers({ roomId });
```

### Messaging

```typescript
// Send a text message
const { eventId } = await Matrix.sendMessage({
  roomId: '!abc:example.com',
  body: 'Hello, world!',
});

// Send a notice or emote
await Matrix.sendMessage({
  roomId,
  body: 'This is a notice',
  msgtype: 'm.notice',
});

// Load message history (paginated)
const { events, nextBatch } = await Matrix.getRoomMessages({
  roomId,
  limit: 50,
});

// Load older messages
const older = await Matrix.getRoomMessages({
  roomId,
  limit: 50,
  from: nextBatch,
});

// Listen for new messages in real time
await Matrix.addListener('messageReceived', ({ event }) => {
  console.log(`${event.senderId}: ${event.content.body}`);
});
```

### Media

```typescript
// Send an image (provide a file URI on native, or a blob URL on web)
await Matrix.sendMessage({
  roomId,
  body: 'photo.jpg',
  msgtype: 'm.image',
  fileUri: 'file:///path/to/photo.jpg',
  fileName: 'photo.jpg',
  mimeType: 'image/jpeg',
});

// Resolve an mxc:// URL to an HTTP URL for display
const { httpUrl } = await Matrix.getMediaUrl({
  mxcUrl: 'mxc://example.com/abc123',
});
```

### Reactions & Redactions

```typescript
// React to a message
await Matrix.sendReaction({
  roomId,
  eventId: '$someEvent',
  key: '👍',
});

// Redact (delete) a message
await Matrix.redactEvent({
  roomId,
  eventId: '$someEvent',
  reason: 'Sent by mistake',
});
```

### Room Management

```typescript
await Matrix.setRoomName({ roomId, name: 'New Room Name' });
await Matrix.setRoomTopic({ roomId, topic: 'Updated topic' });

await Matrix.inviteUser({ roomId, userId: '@carol:example.com' });
await Matrix.kickUser({ roomId, userId: '@dave:example.com', reason: 'Inactive' });
await Matrix.banUser({ roomId, userId: '@eve:example.com' });
await Matrix.unbanUser({ roomId, userId: '@eve:example.com' });
```

### Typing Indicators

```typescript
// Send a typing notification
await Matrix.sendTyping({ roomId, isTyping: true, timeout: 5000 });

// Stop typing
await Matrix.sendTyping({ roomId, isTyping: false });

// Listen for typing events
await Matrix.addListener('typingChanged', ({ roomId, userIds }) => {
  if (userIds.length > 0) {
    console.log(`${userIds.join(', ')} typing in ${roomId}`);
  }
});
```

### Presence

```typescript
// Set your presence
await Matrix.setPresence({ presence: 'online', statusMsg: 'Available' });

// Get another user's presence
const info = await Matrix.getPresence({ userId: '@bob:example.com' });
console.log(info.presence, info.statusMsg);
```

### End-to-End Encryption

```typescript
// Initialize the crypto module (call after login, before startSync)
await Matrix.initializeCrypto();

// Check encryption status
const status = await Matrix.getEncryptionStatus();
console.log('Cross-signing ready:', status.isCrossSigningReady);
console.log('Key backup enabled:', status.isKeyBackupEnabled);

// Bootstrap cross-signing (first-time device setup)
await Matrix.bootstrapCrossSigning();

// Set up server-side key backup
const backup = await Matrix.setupKeyBackup();

// Set up recovery (generates a recovery key)
const { recoveryKey } = await Matrix.setupRecovery();
console.log('Save this recovery key:', recoveryKey);

// Or use a passphrase-based recovery key
const recovery = await Matrix.setupRecovery({ passphrase: 'my secret phrase' });

// Recover on a new device
await Matrix.recoverAndSetup({ recoveryKey: 'EsXa ...' });
// or
await Matrix.recoverAndSetup({ passphrase: 'my secret phrase' });

// Export/import room keys (for manual backup)
const { data } = await Matrix.exportRoomKeys({ passphrase: 'backup-pass' });
await Matrix.importRoomKeys({ data, passphrase: 'backup-pass' });
```

### Event Listeners

```typescript
// Room updates (name change, new members, etc.)
await Matrix.addListener('roomUpdated', ({ roomId, summary }) => {
  console.log(`Room ${summary.name} updated`);
});

// Read receipt updates (fires when another user reads messages in a room)
await Matrix.addListener('receiptReceived', ({ roomId }) => {
  console.log(`New read receipt in ${roomId}`);
  // Refresh message statuses to update read indicators
});

// Clean up all listeners
await Matrix.removeAllListeners();
```

### Read Markers

```typescript
// Mark a room as read up to a specific event
await Matrix.markRoomAsRead({
  roomId: '!abc:example.com',
  eventId: '$latestEvent',
});

// Refresh read statuses for specific messages (useful after receiving a receiptReceived event)
const { events } = await Matrix.refreshEventStatuses({
  roomId: '!abc:example.com',
  eventIds: ['$event1', '$event2'],
});
events.forEach((evt) => {
  console.log(`${evt.eventId}: ${evt.status}`, evt.readBy);
});
```

## API Reference

The full API reference is auto-generated below from the TypeScript definitions.

<docgen-index>

* [`login(...)`](#login)
* [`loginWithToken(...)`](#loginwithtoken)
* [`logout()`](#logout)
* [`getSession()`](#getsession)
* [`startSync()`](#startsync)
* [`stopSync()`](#stopsync)
* [`getSyncState()`](#getsyncstate)
* [`createRoom(...)`](#createroom)
* [`getRooms()`](#getrooms)
* [`getRoomMembers(...)`](#getroommembers)
* [`joinRoom(...)`](#joinroom)
* [`leaveRoom(...)`](#leaveroom)
* [`forgetRoom(...)`](#forgetroom)
* [`sendMessage(...)`](#sendmessage)
* [`editMessage(...)`](#editmessage)
* [`sendReply(...)`](#sendreply)
* [`getRoomMessages(...)`](#getroommessages)
* [`markRoomAsRead(...)`](#markroomasread)
* [`refreshEventStatuses(...)`](#refresheventstatuses)
* [`redactEvent(...)`](#redactevent)
* [`sendReaction(...)`](#sendreaction)
* [`setRoomName(...)`](#setroomname)
* [`setRoomTopic(...)`](#setroomtopic)
* [`setRoomAvatar(...)`](#setroomavatar)
* [`inviteUser(...)`](#inviteuser)
* [`kickUser(...)`](#kickuser)
* [`banUser(...)`](#banuser)
* [`unbanUser(...)`](#unbanuser)
* [`sendTyping(...)`](#sendtyping)
* [`getMediaUrl(...)`](#getmediaurl)
* [`getThumbnailUrl(...)`](#getthumbnailurl)
* [`uploadContent(...)`](#uploadcontent)
* [`searchUsers(...)`](#searchusers)
* [`setPresence(...)`](#setpresence)
* [`getPresence(...)`](#getpresence)
* [`getDevices()`](#getdevices)
* [`deleteDevice(...)`](#deletedevice)
* [`verifyDevice(...)`](#verifydevice)
* [`setPusher(...)`](#setpusher)
* [`initializeCrypto()`](#initializecrypto)
* [`getEncryptionStatus()`](#getencryptionstatus)
* [`bootstrapCrossSigning()`](#bootstrapcrosssigning)
* [`setupKeyBackup()`](#setupkeybackup)
* [`getKeyBackupStatus()`](#getkeybackupstatus)
* [`restoreKeyBackup(...)`](#restorekeybackup)
* [`setupRecovery(...)`](#setuprecovery)
* [`clearAllData()`](#clearalldata)
* [`isRecoveryEnabled()`](#isrecoveryenabled)
* [`recoverAndSetup(...)`](#recoverandsetup)
* [`resetRecoveryKey(...)`](#resetrecoverykey)
* [`exportRoomKeys(...)`](#exportroomkeys)
* [`importRoomKeys(...)`](#importroomkeys)
* [`addListener('syncStateChange', ...)`](#addlistenersyncstatechange-)
* [`addListener('messageReceived', ...)`](#addlistenermessagereceived-)
* [`addListener('roomUpdated', ...)`](#addlistenerroomupdated-)
* [`addListener('typingChanged', ...)`](#addlistenertypingchanged-)
* [`addListener('receiptReceived', ...)`](#addlistenerreceiptreceived-)
* [`addListener('presenceChanged', ...)`](#addlistenerpresencechanged-)
* [`updateAccessToken(...)`](#updateaccesstoken)
* [`addListener('tokenRefreshRequired', ...)`](#addlistenertokenrefreshrequired-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### login(...)

```typescript
login(options: LoginOptions) => Promise<SessionInfo>
```

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#loginoptions">LoginOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#sessioninfo">SessionInfo</a>&gt;</code>

--------------------


### loginWithToken(...)

```typescript
loginWithToken(options: LoginWithTokenOptions) => Promise<SessionInfo>
```

| Param         | Type                                                                    |
| ------------- | ----------------------------------------------------------------------- |
| **`options`** | <code><a href="#loginwithtokenoptions">LoginWithTokenOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#sessioninfo">SessionInfo</a>&gt;</code>

--------------------


### logout()

```typescript
logout() => Promise<void>
```

--------------------


### getSession()

```typescript
getSession() => Promise<SessionInfo | null>
```

**Returns:** <code>Promise&lt;<a href="#sessioninfo">SessionInfo</a> | null&gt;</code>

--------------------


### startSync()

```typescript
startSync() => Promise<void>
```

--------------------


### stopSync()

```typescript
stopSync() => Promise<void>
```

--------------------


### getSyncState()

```typescript
getSyncState() => Promise<{ state: SyncState; }>
```

**Returns:** <code>Promise&lt;{ state: <a href="#syncstate">SyncState</a>; }&gt;</code>

--------------------


### createRoom(...)

```typescript
createRoom(options: { name?: string; topic?: string; isEncrypted?: boolean; isDirect?: boolean; invite?: string[]; preset?: 'private_chat' | 'trusted_private_chat' | 'public_chat'; historyVisibility?: 'invited' | 'joined' | 'shared' | 'world_readable'; }) => Promise<{ roomId: string; }>
```

| Param         | Type                                                                                                                                                                                                                                                         |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`options`** | <code>{ name?: string; topic?: string; isEncrypted?: boolean; isDirect?: boolean; invite?: string[]; preset?: 'private_chat' \| 'trusted_private_chat' \| 'public_chat'; historyVisibility?: 'invited' \| 'joined' \| 'shared' \| 'world_readable'; }</code> |

**Returns:** <code>Promise&lt;{ roomId: string; }&gt;</code>

--------------------


### getRooms()

```typescript
getRooms() => Promise<{ rooms: RoomSummary[]; }>
```

**Returns:** <code>Promise&lt;{ rooms: RoomSummary[]; }&gt;</code>

--------------------


### getRoomMembers(...)

```typescript
getRoomMembers(options: { roomId: string; }) => Promise<{ members: RoomMember[]; }>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ roomId: string; }</code> |

**Returns:** <code>Promise&lt;{ members: RoomMember[]; }&gt;</code>

--------------------


### joinRoom(...)

```typescript
joinRoom(options: { roomIdOrAlias: string; }) => Promise<{ roomId: string; }>
```

| Param         | Type                                    |
| ------------- | --------------------------------------- |
| **`options`** | <code>{ roomIdOrAlias: string; }</code> |

**Returns:** <code>Promise&lt;{ roomId: string; }&gt;</code>

--------------------


### leaveRoom(...)

```typescript
leaveRoom(options: { roomId: string; }) => Promise<void>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ roomId: string; }</code> |

--------------------


### forgetRoom(...)

```typescript
forgetRoom(options: { roomId: string; }) => Promise<void>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ roomId: string; }</code> |

--------------------


### sendMessage(...)

```typescript
sendMessage(options: SendMessageOptions) => Promise<{ eventId: string; }>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#sendmessageoptions">SendMessageOptions</a></code> |

**Returns:** <code>Promise&lt;{ eventId: string; }&gt;</code>

--------------------


### editMessage(...)

```typescript
editMessage(options: EditMessageOptions) => Promise<{ eventId: string; }>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#editmessageoptions">EditMessageOptions</a></code> |

**Returns:** <code>Promise&lt;{ eventId: string; }&gt;</code>

--------------------


### sendReply(...)

```typescript
sendReply(options: SendReplyOptions) => Promise<{ eventId: string; }>
```

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#sendreplyoptions">SendReplyOptions</a></code> |

**Returns:** <code>Promise&lt;{ eventId: string; }&gt;</code>

--------------------


### getRoomMessages(...)

```typescript
getRoomMessages(options: { roomId: string; limit?: number; from?: string; }) => Promise<{ events: MatrixEvent[]; nextBatch?: string; }>
```

| Param         | Type                                                            |
| ------------- | --------------------------------------------------------------- |
| **`options`** | <code>{ roomId: string; limit?: number; from?: string; }</code> |

**Returns:** <code>Promise&lt;{ events: MatrixEvent[]; nextBatch?: string; }&gt;</code>

--------------------


### markRoomAsRead(...)

```typescript
markRoomAsRead(options: { roomId: string; eventId: string; }) => Promise<void>
```

| Param         | Type                                              |
| ------------- | ------------------------------------------------- |
| **`options`** | <code>{ roomId: string; eventId: string; }</code> |

--------------------


### refreshEventStatuses(...)

```typescript
refreshEventStatuses(options: { roomId: string; eventIds: string[]; }) => Promise<{ events: MatrixEvent[]; }>
```

| Param         | Type                                                 |
| ------------- | ---------------------------------------------------- |
| **`options`** | <code>{ roomId: string; eventIds: string[]; }</code> |

**Returns:** <code>Promise&lt;{ events: MatrixEvent[]; }&gt;</code>

--------------------


### redactEvent(...)

```typescript
redactEvent(options: { roomId: string; eventId: string; reason?: string; }) => Promise<void>
```

| Param         | Type                                                               |
| ------------- | ------------------------------------------------------------------ |
| **`options`** | <code>{ roomId: string; eventId: string; reason?: string; }</code> |

--------------------


### sendReaction(...)

```typescript
sendReaction(options: { roomId: string; eventId: string; key: string; }) => Promise<{ eventId: string; }>
```

| Param         | Type                                                           |
| ------------- | -------------------------------------------------------------- |
| **`options`** | <code>{ roomId: string; eventId: string; key: string; }</code> |

**Returns:** <code>Promise&lt;{ eventId: string; }&gt;</code>

--------------------


### setRoomName(...)

```typescript
setRoomName(options: { roomId: string; name: string; }) => Promise<void>
```

| Param         | Type                                           |
| ------------- | ---------------------------------------------- |
| **`options`** | <code>{ roomId: string; name: string; }</code> |

--------------------


### setRoomTopic(...)

```typescript
setRoomTopic(options: { roomId: string; topic: string; }) => Promise<void>
```

| Param         | Type                                            |
| ------------- | ----------------------------------------------- |
| **`options`** | <code>{ roomId: string; topic: string; }</code> |

--------------------


### setRoomAvatar(...)

```typescript
setRoomAvatar(options: { roomId: string; mxcUrl: string; }) => Promise<void>
```

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ roomId: string; mxcUrl: string; }</code> |

--------------------


### inviteUser(...)

```typescript
inviteUser(options: { roomId: string; userId: string; }) => Promise<void>
```

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ roomId: string; userId: string; }</code> |

--------------------


### kickUser(...)

```typescript
kickUser(options: { roomId: string; userId: string; reason?: string; }) => Promise<void>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code>{ roomId: string; userId: string; reason?: string; }</code> |

--------------------


### banUser(...)

```typescript
banUser(options: { roomId: string; userId: string; reason?: string; }) => Promise<void>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code>{ roomId: string; userId: string; reason?: string; }</code> |

--------------------


### unbanUser(...)

```typescript
unbanUser(options: { roomId: string; userId: string; }) => Promise<void>
```

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ roomId: string; userId: string; }</code> |

--------------------


### sendTyping(...)

```typescript
sendTyping(options: { roomId: string; isTyping: boolean; timeout?: number; }) => Promise<void>
```

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code>{ roomId: string; isTyping: boolean; timeout?: number; }</code> |

--------------------


### getMediaUrl(...)

```typescript
getMediaUrl(options: { mxcUrl: string; }) => Promise<{ httpUrl: string; }>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ mxcUrl: string; }</code> |

**Returns:** <code>Promise&lt;{ httpUrl: string; }&gt;</code>

--------------------


### getThumbnailUrl(...)

```typescript
getThumbnailUrl(options: ThumbnailUrlOptions) => Promise<{ httpUrl: string; }>
```

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#thumbnailurloptions">ThumbnailUrlOptions</a></code> |

**Returns:** <code>Promise&lt;{ httpUrl: string; }&gt;</code>

--------------------


### uploadContent(...)

```typescript
uploadContent(options: UploadContentOptions) => Promise<UploadContentResult>
```

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#uploadcontentoptions">UploadContentOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#uploadcontentresult">UploadContentResult</a>&gt;</code>

--------------------


### searchUsers(...)

```typescript
searchUsers(options: { searchTerm: string; limit?: number; }) => Promise<{ results: UserProfile[]; limited: boolean; }>
```

| Param         | Type                                                 |
| ------------- | ---------------------------------------------------- |
| **`options`** | <code>{ searchTerm: string; limit?: number; }</code> |

**Returns:** <code>Promise&lt;{ results: UserProfile[]; limited: boolean; }&gt;</code>

--------------------


### setPresence(...)

```typescript
setPresence(options: { presence: 'online' | 'offline' | 'unavailable'; statusMsg?: string; }) => Promise<void>
```

| Param         | Type                                                                                   |
| ------------- | -------------------------------------------------------------------------------------- |
| **`options`** | <code>{ presence: 'online' \| 'offline' \| 'unavailable'; statusMsg?: string; }</code> |

--------------------


### getPresence(...)

```typescript
getPresence(options: { userId: string; }) => Promise<PresenceInfo>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ userId: string; }</code> |

**Returns:** <code>Promise&lt;<a href="#presenceinfo">PresenceInfo</a>&gt;</code>

--------------------


### getDevices()

```typescript
getDevices() => Promise<{ devices: DeviceInfo[]; }>
```

**Returns:** <code>Promise&lt;{ devices: DeviceInfo[]; }&gt;</code>

--------------------


### deleteDevice(...)

```typescript
deleteDevice(options: { deviceId: string; auth?: Record<string, unknown>; }) => Promise<void>
```

| Param         | Type                                                                                           |
| ------------- | ---------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ deviceId: string; auth?: <a href="#record">Record</a>&lt;string, unknown&gt;; }</code> |

--------------------


### verifyDevice(...)

```typescript
verifyDevice(options: { deviceId: string; }) => Promise<void>
```

| Param         | Type                               |
| ------------- | ---------------------------------- |
| **`options`** | <code>{ deviceId: string; }</code> |

--------------------


### setPusher(...)

```typescript
setPusher(options: PusherOptions) => Promise<void>
```

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code><a href="#pusheroptions">PusherOptions</a></code> |

--------------------


### initializeCrypto()

```typescript
initializeCrypto() => Promise<void>
```

--------------------


### getEncryptionStatus()

```typescript
getEncryptionStatus() => Promise<EncryptionStatus>
```

**Returns:** <code>Promise&lt;<a href="#encryptionstatus">EncryptionStatus</a>&gt;</code>

--------------------


### bootstrapCrossSigning()

```typescript
bootstrapCrossSigning() => Promise<void>
```

--------------------


### setupKeyBackup()

```typescript
setupKeyBackup() => Promise<KeyBackupStatus>
```

**Returns:** <code>Promise&lt;<a href="#keybackupstatus">KeyBackupStatus</a>&gt;</code>

--------------------


### getKeyBackupStatus()

```typescript
getKeyBackupStatus() => Promise<KeyBackupStatus>
```

**Returns:** <code>Promise&lt;<a href="#keybackupstatus">KeyBackupStatus</a>&gt;</code>

--------------------


### restoreKeyBackup(...)

```typescript
restoreKeyBackup(options?: { recoveryKey?: string | undefined; } | undefined) => Promise<{ importedKeys: number; }>
```

| Param         | Type                                   |
| ------------- | -------------------------------------- |
| **`options`** | <code>{ recoveryKey?: string; }</code> |

**Returns:** <code>Promise&lt;{ importedKeys: number; }&gt;</code>

--------------------


### setupRecovery(...)

```typescript
setupRecovery(options?: { passphrase?: string | undefined; existingPassphrase?: string | undefined; } | undefined) => Promise<RecoveryKeyInfo>
```

| Param         | Type                                                               |
| ------------- | ------------------------------------------------------------------ |
| **`options`** | <code>{ passphrase?: string; existingPassphrase?: string; }</code> |

**Returns:** <code>Promise&lt;<a href="#recoverykeyinfo">RecoveryKeyInfo</a>&gt;</code>

--------------------


### clearAllData()

```typescript
clearAllData() => Promise<void>
```

Wipe all local Matrix state (crypto DB, session, caches).

--------------------


### isRecoveryEnabled()

```typescript
isRecoveryEnabled() => Promise<{ enabled: boolean; }>
```

**Returns:** <code>Promise&lt;{ enabled: boolean; }&gt;</code>

--------------------


### recoverAndSetup(...)

```typescript
recoverAndSetup(options: { recoveryKey?: string; passphrase?: string; }) => Promise<void>
```

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code>{ recoveryKey?: string; passphrase?: string; }</code> |

--------------------


### resetRecoveryKey(...)

```typescript
resetRecoveryKey(options?: { passphrase?: string | undefined; } | undefined) => Promise<RecoveryKeyInfo>
```

| Param         | Type                                  |
| ------------- | ------------------------------------- |
| **`options`** | <code>{ passphrase?: string; }</code> |

**Returns:** <code>Promise&lt;<a href="#recoverykeyinfo">RecoveryKeyInfo</a>&gt;</code>

--------------------


### exportRoomKeys(...)

```typescript
exportRoomKeys(options: { passphrase: string; }) => Promise<{ data: string; }>
```

| Param         | Type                                 |
| ------------- | ------------------------------------ |
| **`options`** | <code>{ passphrase: string; }</code> |

**Returns:** <code>Promise&lt;{ data: string; }&gt;</code>

--------------------


### importRoomKeys(...)

```typescript
importRoomKeys(options: { data: string; passphrase: string; }) => Promise<{ importedKeys: number; }>
```

| Param         | Type                                               |
| ------------- | -------------------------------------------------- |
| **`options`** | <code>{ data: string; passphrase: string; }</code> |

**Returns:** <code>Promise&lt;{ importedKeys: number; }&gt;</code>

--------------------


### addListener('syncStateChange', ...)

```typescript
addListener(event: 'syncStateChange', listenerFunc: (data: SyncStateChangeEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------- |
| **`event`**        | <code>'syncStateChange'</code>                                                           |
| **`listenerFunc`** | <code>(data: <a href="#syncstatechangeevent">SyncStateChangeEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('messageReceived', ...)

```typescript
addListener(event: 'messageReceived', listenerFunc: (data: MessageReceivedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------- |
| **`event`**        | <code>'messageReceived'</code>                                                           |
| **`listenerFunc`** | <code>(data: <a href="#messagereceivedevent">MessageReceivedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('roomUpdated', ...)

```typescript
addListener(event: 'roomUpdated', listenerFunc: (data: RoomUpdatedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                             |
| ------------------ | -------------------------------------------------------------------------------- |
| **`event`**        | <code>'roomUpdated'</code>                                                       |
| **`listenerFunc`** | <code>(data: <a href="#roomupdatedevent">RoomUpdatedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('typingChanged', ...)

```typescript
addListener(event: 'typingChanged', listenerFunc: (data: TypingEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                   |
| ------------------ | ---------------------------------------------------------------------- |
| **`event`**        | <code>'typingChanged'</code>                                           |
| **`listenerFunc`** | <code>(data: <a href="#typingevent">TypingEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('receiptReceived', ...)

```typescript
addListener(event: 'receiptReceived', listenerFunc: (data: ReceiptReceivedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------- |
| **`event`**        | <code>'receiptReceived'</code>                                                           |
| **`listenerFunc`** | <code>(data: <a href="#receiptreceivedevent">ReceiptReceivedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('presenceChanged', ...)

```typescript
addListener(event: 'presenceChanged', listenerFunc: (data: PresenceChangedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------- |
| **`event`**        | <code>'presenceChanged'</code>                                                           |
| **`listenerFunc`** | <code>(data: <a href="#presencechangedevent">PresenceChangedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### updateAccessToken(...)

```typescript
updateAccessToken(options: { accessToken: string; }) => Promise<void>
```

| Param         | Type                                  |
| ------------- | ------------------------------------- |
| **`options`** | <code>{ accessToken: string; }</code> |

--------------------


### addListener('tokenRefreshRequired', ...)

```typescript
addListener(event: 'tokenRefreshRequired', listenerFunc: () => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                |
| ------------------ | ----------------------------------- |
| **`event`**        | <code>'tokenRefreshRequired'</code> |
| **`listenerFunc`** | <code>() =&gt; void</code>          |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### SessionInfo

| Prop                | Type                |
| ------------------- | ------------------- |
| **`accessToken`**   | <code>string</code> |
| **`userId`**        | <code>string</code> |
| **`deviceId`**      | <code>string</code> |
| **`homeserverUrl`** | <code>string</code> |


#### LoginOptions

| Prop                | Type                |
| ------------------- | ------------------- |
| **`homeserverUrl`** | <code>string</code> |
| **`userId`**        | <code>string</code> |
| **`password`**      | <code>string</code> |


#### LoginWithTokenOptions

| Prop                | Type                |
| ------------------- | ------------------- |
| **`homeserverUrl`** | <code>string</code> |
| **`accessToken`**   | <code>string</code> |
| **`userId`**        | <code>string</code> |
| **`deviceId`**      | <code>string</code> |


#### RoomSummary

| Prop              | Type                                                              |
| ----------------- | ----------------------------------------------------------------- |
| **`roomId`**      | <code>string</code>                                               |
| **`name`**        | <code>string</code>                                               |
| **`topic`**       | <code>string</code>                                               |
| **`memberCount`** | <code>number</code>                                               |
| **`isEncrypted`** | <code>boolean</code>                                              |
| **`unreadCount`** | <code>number</code>                                               |
| **`lastEventTs`** | <code>number</code>                                               |
| **`membership`**  | <code>'join' \| 'invite' \| 'leave' \| 'ban'</code>               |
| **`avatarUrl`**   | <code>string</code>                                               |
| **`isDirect`**    | <code>boolean</code>                                              |
| **`latestEvent`** | <code><a href="#latesteventpreview">LatestEventPreview</a></code> |


#### LatestEventPreview

| Prop                    | Type                                                             |
| ----------------------- | ---------------------------------------------------------------- |
| **`roomId`**            | <code>string</code>                                              |
| **`senderId`**          | <code>string</code>                                              |
| **`type`**              | <code>string</code>                                              |
| **`content`**           | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |
| **`originServerTs`**    | <code>number</code>                                              |
| **`senderDisplayName`** | <code>string</code>                                              |


#### RoomMember

| Prop              | Type                                                |
| ----------------- | --------------------------------------------------- |
| **`userId`**      | <code>string</code>                                 |
| **`displayName`** | <code>string</code>                                 |
| **`membership`**  | <code>'join' \| 'invite' \| 'leave' \| 'ban'</code> |
| **`avatarUrl`**   | <code>string</code>                                 |


#### SendMessageOptions

| Prop           | Type                                                                                                | Description                                                               |
| -------------- | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| **`roomId`**   | <code>string</code>                                                                                 |                                                                           |
| **`body`**     | <code>string</code>                                                                                 |                                                                           |
| **`msgtype`**  | <code>'m.text' \| 'm.notice' \| 'm.emote' \| 'm.image' \| 'm.audio' \| 'm.video' \| 'm.file'</code> |                                                                           |
| **`fileUri`**  | <code>string</code>                                                                                 |                                                                           |
| **`fileName`** | <code>string</code>                                                                                 |                                                                           |
| **`mimeType`** | <code>string</code>                                                                                 |                                                                           |
| **`fileSize`** | <code>number</code>                                                                                 |                                                                           |
| **`duration`** | <code>number</code>                                                                                 | Audio/video duration in milliseconds (sets info.duration per Matrix spec) |
| **`width`**    | <code>number</code>                                                                                 | Image/video width in pixels (sets info.w per Matrix spec)                 |
| **`height`**   | <code>number</code>                                                                                 | Image/video height in pixels (sets info.h per Matrix spec)                |


#### EditMessageOptions

| Prop           | Type                                                                                                | Description                                                             |
| -------------- | --------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| **`roomId`**   | <code>string</code>                                                                                 |                                                                         |
| **`eventId`**  | <code>string</code>                                                                                 |                                                                         |
| **`newBody`**  | <code>string</code>                                                                                 |                                                                         |
| **`msgtype`**  | <code>'m.text' \| 'm.notice' \| 'm.emote' \| 'm.image' \| 'm.audio' \| 'm.video' \| 'm.file'</code> | Required when editing a media message; must match the original msgtype  |
| **`fileUri`**  | <code>string</code>                                                                                 | New file to replace the media content (optional for caption-only edits) |
| **`fileName`** | <code>string</code>                                                                                 |                                                                         |
| **`mimeType`** | <code>string</code>                                                                                 |                                                                         |
| **`fileSize`** | <code>number</code>                                                                                 |                                                                         |
| **`duration`** | <code>number</code>                                                                                 | Audio/video duration in milliseconds                                    |
| **`width`**    | <code>number</code>                                                                                 | Image/video width in pixels                                             |
| **`height`**   | <code>number</code>                                                                                 | Image/video height in pixels                                            |


#### SendReplyOptions

| Prop                 | Type                                                                                                | Description                                                               |
| -------------------- | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| **`roomId`**         | <code>string</code>                                                                                 |                                                                           |
| **`body`**           | <code>string</code>                                                                                 |                                                                           |
| **`replyToEventId`** | <code>string</code>                                                                                 |                                                                           |
| **`msgtype`**        | <code>'m.text' \| 'm.notice' \| 'm.emote' \| 'm.image' \| 'm.audio' \| 'm.video' \| 'm.file'</code> |                                                                           |
| **`fileUri`**        | <code>string</code>                                                                                 |                                                                           |
| **`fileName`**       | <code>string</code>                                                                                 |                                                                           |
| **`mimeType`**       | <code>string</code>                                                                                 |                                                                           |
| **`fileSize`**       | <code>number</code>                                                                                 |                                                                           |
| **`duration`**       | <code>number</code>                                                                                 | Audio/video duration in milliseconds (sets info.duration per Matrix spec) |
| **`width`**          | <code>number</code>                                                                                 | Image/video width in pixels (sets info.w per Matrix spec)                 |
| **`height`**         | <code>number</code>                                                                                 | Image/video height in pixels (sets info.h per Matrix spec)                |


#### MatrixEvent

| Prop                 | Type                                                             | Description                                                                         |
| -------------------- | ---------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| **`eventId`**        | <code>string</code>                                              |                                                                                     |
| **`roomId`**         | <code>string</code>                                              |                                                                                     |
| **`senderId`**       | <code>string</code>                                              |                                                                                     |
| **`type`**           | <code>string</code>                                              |                                                                                     |
| **`stateKey`**       | <code>string</code>                                              | State key for state events (e.g. target user ID for m.room.member)                  |
| **`content`**        | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |                                                                                     |
| **`originServerTs`** | <code>number</code>                                              |                                                                                     |
| **`status`**         | <code>'sending' \| 'sent' \| 'delivered' \| 'read'</code>        | Delivery/read status for own messages: 'sending' \| 'sent' \| 'delivered' \| 'read' |
| **`readBy`**         | <code>string[]</code>                                            | User IDs that have read this event                                                  |
| **`unsigned`**       | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | Unsigned data (e.g. m.relations for edits, transaction_id for local echo)           |


#### ThumbnailUrlOptions

| Prop         | Type                           |
| ------------ | ------------------------------ |
| **`mxcUrl`** | <code>string</code>            |
| **`width`**  | <code>number</code>            |
| **`height`** | <code>number</code>            |
| **`method`** | <code>'scale' \| 'crop'</code> |


#### UploadContentResult

| Prop             | Type                |
| ---------------- | ------------------- |
| **`contentUri`** | <code>string</code> |


#### UploadContentOptions

| Prop           | Type                |
| -------------- | ------------------- |
| **`fileUri`**  | <code>string</code> |
| **`fileName`** | <code>string</code> |
| **`mimeType`** | <code>string</code> |


#### UserProfile

| Prop              | Type                |
| ----------------- | ------------------- |
| **`userId`**      | <code>string</code> |
| **`displayName`** | <code>string</code> |
| **`avatarUrl`**   | <code>string</code> |


#### PresenceInfo

| Prop                | Type                                                |
| ------------------- | --------------------------------------------------- |
| **`presence`**      | <code>'online' \| 'offline' \| 'unavailable'</code> |
| **`statusMsg`**     | <code>string</code>                                 |
| **`lastActiveAgo`** | <code>number</code>                                 |


#### DeviceInfo

| Prop                         | Type                 | Description                                       |
| ---------------------------- | -------------------- | ------------------------------------------------- |
| **`deviceId`**               | <code>string</code>  |                                                   |
| **`displayName`**            | <code>string</code>  |                                                   |
| **`lastSeenTs`**             | <code>number</code>  |                                                   |
| **`lastSeenIp`**             | <code>string</code>  |                                                   |
| **`isCrossSigningVerified`** | <code>boolean</code> | Whether this device is verified via cross-signing |


#### PusherOptions

| Prop                    | Type                                           |
| ----------------------- | ---------------------------------------------- |
| **`pushkey`**           | <code>string</code>                            |
| **`kind`**              | <code>string \| null</code>                    |
| **`appId`**             | <code>string</code>                            |
| **`appDisplayName`**    | <code>string</code>                            |
| **`deviceDisplayName`** | <code>string</code>                            |
| **`lang`**              | <code>string</code>                            |
| **`data`**              | <code>{ url: string; format?: string; }</code> |


#### EncryptionStatus

| Prop                       | Type                                                              |
| -------------------------- | ----------------------------------------------------------------- |
| **`isCrossSigningReady`**  | <code>boolean</code>                                              |
| **`crossSigningStatus`**   | <code><a href="#crosssigningstatus">CrossSigningStatus</a></code> |
| **`isKeyBackupEnabled`**   | <code>boolean</code>                                              |
| **`keyBackupVersion`**     | <code>string</code>                                               |
| **`isSecretStorageReady`** | <code>boolean</code>                                              |


#### CrossSigningStatus

| Prop                 | Type                 |
| -------------------- | -------------------- |
| **`hasMaster`**      | <code>boolean</code> |
| **`hasSelfSigning`** | <code>boolean</code> |
| **`hasUserSigning`** | <code>boolean</code> |
| **`isReady`**        | <code>boolean</code> |


#### KeyBackupStatus

| Prop          | Type                 |
| ------------- | -------------------- |
| **`exists`**  | <code>boolean</code> |
| **`version`** | <code>string</code>  |
| **`enabled`** | <code>boolean</code> |


#### RecoveryKeyInfo

| Prop              | Type                |
| ----------------- | ------------------- |
| **`recoveryKey`** | <code>string</code> |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### SyncStateChangeEvent

| Prop        | Type                                            |
| ----------- | ----------------------------------------------- |
| **`state`** | <code><a href="#syncstate">SyncState</a></code> |
| **`error`** | <code>string</code>                             |


#### MessageReceivedEvent

| Prop        | Type                                                |
| ----------- | --------------------------------------------------- |
| **`event`** | <code><a href="#matrixevent">MatrixEvent</a></code> |


#### RoomUpdatedEvent

| Prop          | Type                                                |
| ------------- | --------------------------------------------------- |
| **`roomId`**  | <code>string</code>                                 |
| **`summary`** | <code><a href="#roomsummary">RoomSummary</a></code> |


#### TypingEvent

| Prop          | Type                  |
| ------------- | --------------------- |
| **`roomId`**  | <code>string</code>   |
| **`userIds`** | <code>string[]</code> |


#### ReceiptReceivedEvent

| Prop          | Type                | Description                        |
| ------------- | ------------------- | ---------------------------------- |
| **`roomId`**  | <code>string</code> |                                    |
| **`eventId`** | <code>string</code> | The event that was read            |
| **`userId`**  | <code>string</code> | The user who sent the read receipt |


#### PresenceChangedEvent

| Prop           | Type                                                  |
| -------------- | ----------------------------------------------------- |
| **`userId`**   | <code>string</code>                                   |
| **`presence`** | <code><a href="#presenceinfo">PresenceInfo</a></code> |


### Type Aliases


#### SyncState

<code>'INITIAL' | 'SYNCING' | 'ERROR' | 'STOPPED'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>

</docgen-api>

## Development

### Setup

```bash
npm install
```

### Build

```bash
npm run build
```

### Test

```bash
npm test              # run once
npm run test:watch    # watch mode
```

The test suite covers the web layer (~98 tests) using Vitest with jsdom, mocking `matrix-js-sdk` at the module level.

### Verify All Platforms

```bash
npm run verify        # builds + tests web, builds Android, builds iOS
npm run verify:web    # web only
npm run verify:android
npm run verify:ios
```

### Lint & Format

```bash
npm run lint          # check
npm run fmt           # auto-fix
```

### Example App

An example app is included in `example-app/` for manual testing against a local homeserver:

```bash
cd example-app
npm install
npm run dev
```

## License

MIT
