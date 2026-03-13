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
  msgtype?: 'm.text' | 'm.notice' | 'm.emote';
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
  removeAllListeners(): Promise<void>;
}
