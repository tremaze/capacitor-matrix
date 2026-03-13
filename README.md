# @tremaze/capacitor-matrix

A capacitor plugin wrapping native matrix SDKs

## Install

To use npm

```bash
npm install @tremaze/capacitor-matrix
````

To use yarn

```bash
yarn add @tremaze/capacitor-matrix
```

Sync native files

```bash
npx cap sync
```

## API

<docgen-index>

* [`login(...)`](#login)
* [`loginWithToken(...)`](#loginwithtoken)
* [`logout()`](#logout)
* [`getSession()`](#getsession)
* [`startSync()`](#startsync)
* [`stopSync()`](#stopsync)
* [`getSyncState()`](#getsyncstate)
* [`getRooms()`](#getrooms)
* [`getRoomMembers(...)`](#getroommembers)
* [`joinRoom(...)`](#joinroom)
* [`leaveRoom(...)`](#leaveroom)
* [`sendMessage(...)`](#sendmessage)
* [`getRoomMessages(...)`](#getroommessages)
* [`markRoomAsRead(...)`](#markroomasread)
* [`addListener('syncStateChange', ...)`](#addlistenersyncstatechange-)
* [`addListener('messageReceived', ...)`](#addlistenermessagereceived-)
* [`addListener('roomUpdated', ...)`](#addlistenerroomupdated-)
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


### sendMessage(...)

```typescript
sendMessage(options: SendMessageOptions) => Promise<{ eventId: string; }>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#sendmessageoptions">SendMessageOptions</a></code> |

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

| Prop              | Type                 |
| ----------------- | -------------------- |
| **`roomId`**      | <code>string</code>  |
| **`name`**        | <code>string</code>  |
| **`topic`**       | <code>string</code>  |
| **`memberCount`** | <code>number</code>  |
| **`isEncrypted`** | <code>boolean</code> |
| **`unreadCount`** | <code>number</code>  |
| **`lastEventTs`** | <code>number</code>  |


#### RoomMember

| Prop              | Type                                                |
| ----------------- | --------------------------------------------------- |
| **`userId`**      | <code>string</code>                                 |
| **`displayName`** | <code>string</code>                                 |
| **`membership`**  | <code>'join' \| 'invite' \| 'leave' \| 'ban'</code> |


#### SendMessageOptions

| Prop          | Type                                             |
| ------------- | ------------------------------------------------ |
| **`roomId`**  | <code>string</code>                              |
| **`body`**    | <code>string</code>                              |
| **`msgtype`** | <code>'m.text' \| 'm.notice' \| 'm.emote'</code> |


#### MatrixEvent

| Prop                 | Type                                                             |
| -------------------- | ---------------------------------------------------------------- |
| **`eventId`**        | <code>string</code>                                              |
| **`roomId`**         | <code>string</code>                                              |
| **`senderId`**       | <code>string</code>                                              |
| **`type`**           | <code>string</code>                                              |
| **`content`**        | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |
| **`originServerTs`** | <code>number</code>                                              |


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


### Type Aliases


#### SyncState

<code>'INITIAL' | 'SYNCING' | 'ERROR' | 'STOPPED'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>

</docgen-api>
