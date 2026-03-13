import { vi } from 'vitest';

/**
 * Creates a mock MatrixClient with all methods used by MatrixWeb stubbed as vi.fn().
 * Each test can override return values as needed.
 */
export function createMockMatrixClient() {
  const eventHandlers: Record<string, Function[]> = {};

  const mockClient = {
    // Event system
    on: vi.fn((event: string, handler: Function) => {
      if (!eventHandlers[event]) eventHandlers[event] = [];
      eventHandlers[event].push(handler);
      return mockClient;
    }),
    off: vi.fn(),
    removeAllListeners: vi.fn(),

    // Auth
    loginWithPassword: vi.fn().mockResolvedValue({
      access_token: 'mock-token',
      user_id: '@test:localhost',
      device_id: 'MOCK_DEVICE',
    }),
    logout: vi.fn().mockResolvedValue(undefined),
    stopClient: vi.fn(),
    startClient: vi.fn().mockResolvedValue(undefined),

    // Sync
    getSyncState: vi.fn().mockReturnValue('SYNCING'),

    // Rooms
    createRoom: vi.fn().mockResolvedValue({ room_id: '!new-room:localhost' }),
    getRooms: vi.fn().mockReturnValue([]),
    getRoom: vi.fn().mockReturnValue(null),
    joinRoom: vi.fn().mockResolvedValue({ roomId: '!joined:localhost' }),
    leave: vi.fn().mockResolvedValue(undefined),

    // Messaging
    sendMessage: vi.fn().mockResolvedValue({ event_id: '$sent-event' }),
    sendEvent: vi.fn().mockResolvedValue({ event_id: '$sent-event' }),
    redactEvent: vi.fn().mockResolvedValue(undefined),
    createMessagesRequest: vi.fn().mockResolvedValue({ chunk: [], end: undefined }),
    setRoomReadMarkersHttpRequest: vi.fn().mockResolvedValue(undefined),
    uploadContent: vi.fn().mockResolvedValue({ content_uri: 'mxc://localhost/uploaded' }),

    // Room management
    setRoomName: vi.fn().mockResolvedValue(undefined),
    setRoomTopic: vi.fn().mockResolvedValue(undefined),
    invite: vi.fn().mockResolvedValue(undefined),
    kick: vi.fn().mockResolvedValue(undefined),
    ban: vi.fn().mockResolvedValue(undefined),
    unban: vi.fn().mockResolvedValue(undefined),

    // Typing
    sendTyping: vi.fn().mockResolvedValue(undefined),

    // Media
    mxcUrlToHttp: vi.fn().mockReturnValue('https://localhost/_matrix/media/v3/download/localhost/abc'),

    // Presence
    setPresence: vi.fn().mockResolvedValue(undefined),
    getUser: vi.fn().mockReturnValue(null),

    // Crypto
    initRustCrypto: vi.fn().mockResolvedValue(undefined),
    getCrypto: vi.fn().mockReturnValue(null),
  };

  return {
    mockClient,
    eventHandlers,
    /** Emit a mock event as if the SDK fired it */
    emitEvent: (event: string, ...args: unknown[]) => {
      (eventHandlers[event] ?? []).forEach((handler) => handler(...args));
    },
  };
}

export function createMockCrypto() {
  return {
    isCrossSigningReady: vi.fn().mockResolvedValue(false),
    getCrossSigningStatus: vi.fn().mockResolvedValue({
      publicKeysOnDevice: false,
      privateKeysCachedLocally: {
        selfSigningKey: false,
        userSigningKey: false,
      },
    }),
    getActiveSessionBackupVersion: vi.fn().mockResolvedValue(null),
    getSecretStorageStatus: vi.fn().mockResolvedValue({ defaultKeyId: null }),
    isSecretStorageReady: vi.fn().mockResolvedValue(false),
    bootstrapCrossSigning: vi.fn().mockResolvedValue(undefined),
    resetKeyBackup: vi.fn().mockResolvedValue(undefined),
    restoreKeyBackup: vi.fn().mockResolvedValue({ imported: 5 }),
    createRecoveryKeyFromPassphrase: vi.fn().mockResolvedValue({
      privateKey: new Uint8Array(32),
      encodedPrivateKey: 'EsXa mock recovery key',
    }),
    bootstrapSecretStorage: vi.fn().mockResolvedValue(undefined),
    loadSessionBackupPrivateKeyFromSecretStorage: vi.fn().mockResolvedValue(undefined),
    checkKeyBackupAndEnable: vi.fn().mockResolvedValue(undefined),
    exportRoomKeysAsJson: vi.fn().mockResolvedValue('[]'),
    importRoomKeysAsJson: vi.fn().mockResolvedValue(undefined),
  };
}

export function createMockRoom(overrides: Partial<{
  roomId: string;
  name: string;
  topic: string;
  memberCount: number;
  isEncrypted: boolean;
  unreadCount: number;
  lastActiveTs: number;
  members: Array<{ userId: string; name: string; membership: string }>;
}> = {}) {
  const roomId = overrides.roomId ?? '!room:localhost';
  const members = overrides.members ?? [];

  return {
    roomId,
    name: overrides.name ?? 'Test Room',
    getJoinedMemberCount: vi.fn().mockReturnValue(overrides.memberCount ?? 2),
    hasEncryptionStateEvent: vi.fn().mockReturnValue(overrides.isEncrypted ?? false),
    getUnreadNotificationCount: vi.fn().mockReturnValue(overrides.unreadCount ?? 0),
    getLastActiveTimestamp: vi.fn().mockReturnValue(overrides.lastActiveTs ?? 1700000000000),
    currentState: {
      getStateEvents: vi.fn().mockImplementation((type: string) => {
        if (type === 'm.room.topic') {
          return overrides.topic
            ? { getContent: () => ({ topic: overrides.topic }) }
            : null;
        }
        if (type === 'm.typing') {
          return { getContent: () => ({ user_ids: [] }) };
        }
        return null;
      }),
    },
    loadMembersIfNeeded: vi.fn().mockResolvedValue(undefined),
    getMembers: vi.fn().mockReturnValue(
      members.map((m) => ({
        userId: m.userId,
        name: m.name,
        membership: m.membership,
      })),
    ),
    getLiveTimeline: vi.fn().mockReturnValue({
      getEvents: vi.fn().mockReturnValue([]),
      getPaginationToken: vi.fn().mockReturnValue(null),
    }),
  };
}

export function createMockSdkEvent(overrides: Partial<{
  eventId: string;
  roomId: string;
  sender: string;
  type: string;
  content: Record<string, unknown>;
  ts: number;
}> = {}) {
  return {
    getId: vi.fn().mockReturnValue(overrides.eventId ?? '$evt1'),
    getRoomId: vi.fn().mockReturnValue(overrides.roomId ?? '!room:localhost'),
    getSender: vi.fn().mockReturnValue(overrides.sender ?? '@user:localhost'),
    getType: vi.fn().mockReturnValue(overrides.type ?? 'm.room.message'),
    getContent: vi.fn().mockReturnValue(overrides.content ?? { body: 'hello', msgtype: 'm.text' }),
    getTs: vi.fn().mockReturnValue(overrides.ts ?? 1700000000000),
  };
}
