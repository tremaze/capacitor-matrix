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

export interface MatrixEvent {
  eventId: string;
  roomId: string;
  senderId: string;
  type: string;
  content: Record<string, unknown>;
  originServerTs: number;
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
}

export interface RoomMember {
  userId: string;
  displayName?: string;
  membership: 'join' | 'invite' | 'leave' | 'ban';
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
    invite?: string[];
  }): Promise<{ roomId: string }>;
  getRooms(): Promise<{ rooms: RoomSummary[] }>;
  getRoomMembers(options: { roomId: string }): Promise<{ members: RoomMember[] }>;
  joinRoom(options: { roomIdOrAlias: string }): Promise<{ roomId: string }>;
  leaveRoom(options: { roomId: string }): Promise<void>;

  // Messaging
  sendMessage(options: SendMessageOptions): Promise<{ eventId: string }>;
  getRoomMessages(options: {
    roomId: string;
    limit?: number;
    from?: string;
  }): Promise<{ events: MatrixEvent[]; nextBatch?: string }>;
  markRoomAsRead(options: {
    roomId: string;
    eventId: string;
  }): Promise<void>;
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
  removeAllListeners(): Promise<void>;
}
