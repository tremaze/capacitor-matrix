import type { PluginListenerHandle } from '@capacitor/core';

// Auth & Session

export interface LoginOptions {
  homeserverUrl: string;
  userId: string;
  password: string;
}

export interface LoginWithTokenOptions {
  homeserverUrl: string;
  accessToken: string;
  userId: string;
  deviceId: string;
}

export interface SessionInfo {
  accessToken: string;
  userId: string;
  deviceId: string;
  homeserverUrl: string;
}

// Messaging

export interface SendMessageOptions {
  roomId: string;
  body: string;
  msgtype?: 'm.text' | 'm.notice' | 'm.emote' | 'm.image' | 'm.audio' | 'm.video' | 'm.file';
  fileUri?: string;
  fileName?: string;
  mimeType?: string;
  fileSize?: number;
}

// Presence

export interface PresenceInfo {
  presence: 'online' | 'offline' | 'unavailable';
  statusMsg?: string;
  lastActiveAgo?: number;
}

// Typing

export interface TypingEvent {
  roomId: string;
  userIds: string[];
}

export interface ReceiptReceivedEvent {
  roomId: string;
}

export interface PresenceChangedEvent {
  userId: string;
  presence: PresenceInfo;
}

// Edit & Reply

export interface EditMessageOptions {
  roomId: string;
  eventId: string;
  newBody: string;
}

export interface SendReplyOptions {
  roomId: string;
  body: string;
  replyToEventId: string;
  msgtype?: 'm.text' | 'm.notice' | 'm.emote' | 'm.image' | 'm.audio' | 'm.video' | 'm.file';
  fileUri?: string;
  fileName?: string;
  mimeType?: string;
  fileSize?: number;
}

// Upload

export interface UploadContentOptions {
  fileUri: string;
  fileName: string;
  mimeType: string;
}

export interface UploadContentResult {
  contentUri: string;
}

// Thumbnail

export interface ThumbnailUrlOptions {
  mxcUrl: string;
  width: number;
  height: number;
  method?: 'scale' | 'crop';
}

export interface MatrixEvent {
  eventId: string;
  roomId: string;
  senderId: string;
  type: string;
  content: Record<string, unknown>;
  originServerTs: number;
  /** Delivery/read status for own messages: 'sending' | 'sent' | 'delivered' | 'read' */
  status?: 'sending' | 'sent' | 'delivered' | 'read';
  /** User IDs that have read this event */
  readBy?: string[];
  /** Unsigned data (e.g. m.relations for edits, transaction_id for local echo) */
  unsigned?: Record<string, unknown>;
}

// Rooms

export interface RoomSummary {
  roomId: string;
  name: string;
  topic?: string;
  memberCount: number;
  isEncrypted: boolean;
  unreadCount: number;
  lastEventTs?: number;
  membership?: 'join' | 'invite' | 'leave' | 'ban';
  avatarUrl?: string;
  isDirect?: boolean;
}

export interface RoomMember {
  userId: string;
  displayName?: string;
  membership: 'join' | 'invite' | 'leave' | 'ban';
  avatarUrl?: string;
}

// Device Management

export interface DeviceInfo {
  deviceId: string;
  displayName?: string;
  lastSeenTs?: number;
  lastSeenIp?: string;
}

// Pusher

export interface PusherOptions {
  pushkey: string;
  kind: string | null;
  appId: string;
  appDisplayName: string;
  deviceDisplayName: string;
  lang: string;
  data: { url: string; format?: string };
}

// User Discovery

export interface UserProfile {
  userId: string;
  displayName?: string;
  avatarUrl?: string;
}

// Encryption

export interface CrossSigningStatus {
  hasMaster: boolean;
  hasSelfSigning: boolean;
  hasUserSigning: boolean;
  isReady: boolean;
}

export interface KeyBackupStatus {
  exists: boolean;
  version?: string;
  enabled: boolean;
}

export interface RecoveryKeyInfo {
  recoveryKey: string;
}

export interface EncryptionStatus {
  isCrossSigningReady: boolean;
  crossSigningStatus: CrossSigningStatus;
  isKeyBackupEnabled: boolean;
  keyBackupVersion?: string;
  isSecretStorageReady: boolean;
}

// Events & Sync

export type SyncState = 'INITIAL' | 'SYNCING' | 'ERROR' | 'STOPPED';

export interface SyncStateChangeEvent {
  state: SyncState;
  error?: string;
}

export interface MessageReceivedEvent {
  event: MatrixEvent;
}

export interface RoomUpdatedEvent {
  roomId: string;
  summary: RoomSummary;
}

// Plugin Interface

export interface MatrixPlugin {
  // Auth
  login(options: LoginOptions): Promise<SessionInfo>;
  loginWithToken(options: LoginWithTokenOptions): Promise<SessionInfo>;
  logout(): Promise<void>;
  getSession(): Promise<SessionInfo | null>;

  // Sync
  startSync(): Promise<void>;
  stopSync(): Promise<void>;
  getSyncState(): Promise<{ state: SyncState }>;

  // Rooms
  createRoom(options: {
    name?: string;
    topic?: string;
    isEncrypted?: boolean;
    isDirect?: boolean;
    invite?: string[];
    preset?: 'private_chat' | 'trusted_private_chat' | 'public_chat';
    historyVisibility?: 'invited' | 'joined' | 'shared' | 'world_readable';
  }): Promise<{ roomId: string }>;
  getRooms(): Promise<{ rooms: RoomSummary[] }>;
  getRoomMembers(options: { roomId: string }): Promise<{ members: RoomMember[] }>;
  joinRoom(options: { roomIdOrAlias: string }): Promise<{ roomId: string }>;
  leaveRoom(options: { roomId: string }): Promise<void>;
  forgetRoom(options: { roomId: string }): Promise<void>;

  // Messaging
  sendMessage(options: SendMessageOptions): Promise<{ eventId: string }>;
  editMessage(options: EditMessageOptions): Promise<{ eventId: string }>;
  sendReply(options: SendReplyOptions): Promise<{ eventId: string }>;
  getRoomMessages(options: {
    roomId: string;
    limit?: number;
    from?: string;
  }): Promise<{ events: MatrixEvent[]; nextBatch?: string }>;
  markRoomAsRead(options: {
    roomId: string;
    eventId: string;
  }): Promise<void>;
  refreshEventStatuses(options: {
    roomId: string;
    eventIds: string[];
  }): Promise<{ events: MatrixEvent[] }>;
  redactEvent(options: {
    roomId: string;
    eventId: string;
    reason?: string;
  }): Promise<void>;
  sendReaction(options: {
    roomId: string;
    eventId: string;
    key: string;
  }): Promise<{ eventId: string }>;

  // Room Management
  setRoomName(options: { roomId: string; name: string }): Promise<void>;
  setRoomTopic(options: { roomId: string; topic: string }): Promise<void>;
  setRoomAvatar(options: { roomId: string; mxcUrl: string }): Promise<void>;
  inviteUser(options: { roomId: string; userId: string }): Promise<void>;
  kickUser(options: { roomId: string; userId: string; reason?: string }): Promise<void>;
  banUser(options: { roomId: string; userId: string; reason?: string }): Promise<void>;
  unbanUser(options: { roomId: string; userId: string }): Promise<void>;

  // Typing
  sendTyping(options: {
    roomId: string;
    isTyping: boolean;
    timeout?: number;
  }): Promise<void>;

  // Media
  getMediaUrl(options: { mxcUrl: string }): Promise<{ httpUrl: string }>;
  getThumbnailUrl(options: ThumbnailUrlOptions): Promise<{ httpUrl: string }>;
  uploadContent(options: UploadContentOptions): Promise<UploadContentResult>;

  // User Discovery
  searchUsers(options: {
    searchTerm: string;
    limit?: number;
  }): Promise<{ results: UserProfile[]; limited: boolean }>;

  // Presence
  setPresence(options: {
    presence: 'online' | 'offline' | 'unavailable';
    statusMsg?: string;
  }): Promise<void>;
  getPresence(options: { userId: string }): Promise<PresenceInfo>;

  // Device Management
  getDevices(): Promise<{ devices: DeviceInfo[] }>;
  deleteDevice(options: { deviceId: string; auth?: Record<string, unknown> }): Promise<void>;

  // Push
  setPusher(options: PusherOptions): Promise<void>;

  // Encryption
  initializeCrypto(): Promise<void>;
  getEncryptionStatus(): Promise<EncryptionStatus>;
  bootstrapCrossSigning(): Promise<void>;
  setupKeyBackup(): Promise<KeyBackupStatus>;
  getKeyBackupStatus(): Promise<KeyBackupStatus>;
  restoreKeyBackup(options?: {
    recoveryKey?: string;
  }): Promise<{ importedKeys: number }>;
  setupRecovery(options?: {
    passphrase?: string;
  }): Promise<RecoveryKeyInfo>;
  isRecoveryEnabled(): Promise<{ enabled: boolean }>;
  recoverAndSetup(options: {
    recoveryKey?: string;
    passphrase?: string;
  }): Promise<void>;
  resetRecoveryKey(options?: {
    passphrase?: string;
  }): Promise<RecoveryKeyInfo>;
  exportRoomKeys(options: {
    passphrase: string;
  }): Promise<{ data: string }>;
  importRoomKeys(options: {
    data: string;
    passphrase: string;
  }): Promise<{ importedKeys: number }>;

  // Listeners
  addListener(
    event: 'syncStateChange',
    listenerFunc: (data: SyncStateChangeEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    event: 'messageReceived',
    listenerFunc: (data: MessageReceivedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    event: 'roomUpdated',
    listenerFunc: (data: RoomUpdatedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    event: 'typingChanged',
    listenerFunc: (data: TypingEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    event: 'receiptReceived',
    listenerFunc: (data: ReceiptReceivedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    event: 'presenceChanged',
    listenerFunc: (data: PresenceChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
