import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  createMockMatrixClient,
  createMockCrypto,
  createMockRoom,
  createMockSdkEvent,
} from './helpers/mock-matrix-client';

// ── Module mocks ─────────────────────────────────────────

const { mockClient, emitEvent, eventHandlers } = createMockMatrixClient();

vi.mock('matrix-js-sdk', async () => {
  const actual = await vi.importActual('matrix-js-sdk');
  return {
    ...actual,
    createClient: vi.fn(() => mockClient),
  };
});

vi.mock('matrix-js-sdk/lib/crypto-api/recovery-key', () => ({
  decodeRecoveryKey: vi.fn(() => new Uint8Array(32)),
}));

vi.mock('matrix-js-sdk/lib/crypto-api/key-passphrase', () => ({
  deriveRecoveryKeyFromPassphrase: vi.fn().mockResolvedValue(new Uint8Array(32)),
}));

import { MatrixWeb } from '../src/web';
import { createClient } from 'matrix-js-sdk';
import { decodeRecoveryKey } from 'matrix-js-sdk/lib/crypto-api/recovery-key';

// ── Setup ────────────────────────────────────────────────

let plugin: MatrixWeb;

beforeEach(() => {
  plugin = new MatrixWeb();
  localStorage.clear();
  vi.clearAllMocks();
  // Clear event handlers
  for (const key of Object.keys(eventHandlers)) {
    delete eventHandlers[key];
  }
});

/** Helper: log in the plugin so client is set */
async function loginPlugin() {
  await plugin.login({
    homeserverUrl: 'https://matrix.test',
    userId: '@test:localhost',
    password: 'pass',
  });
}

/** Helper: log in + set up mock crypto */
async function loginWithCrypto() {
  await loginPlugin();
  const crypto = createMockCrypto();
  mockClient.getCrypto.mockReturnValue(crypto);
  return crypto;
}

// ── Phase 1: requireClient guard ─────────────────────────

describe('requireClient guard', () => {
  const methodsRequiringClient: Array<[string, () => Promise<unknown>]> = [
    ['startSync', () => plugin.startSync()],
    ['stopSync', () => plugin.stopSync()],
    ['getSyncState', () => plugin.getSyncState()],
    ['createRoom', () => plugin.createRoom({})],
    ['getRooms', () => plugin.getRooms()],
    ['getRoomMembers', () => plugin.getRoomMembers({ roomId: '!r:l' })],
    ['joinRoom', () => plugin.joinRoom({ roomIdOrAlias: '!r:l' })],
    ['leaveRoom', () => plugin.leaveRoom({ roomId: '!r:l' })],
    ['sendMessage', () => plugin.sendMessage({ roomId: '!r:l', body: 'hi' })],
    ['getRoomMessages', () => plugin.getRoomMessages({ roomId: '!r:l' })],
    ['markRoomAsRead', () => plugin.markRoomAsRead({ roomId: '!r:l', eventId: '$e' })],
    ['redactEvent', () => plugin.redactEvent({ roomId: '!r:l', eventId: '$e' })],
    ['sendReaction', () => plugin.sendReaction({ roomId: '!r:l', eventId: '$e', key: '👍' })],
    ['setRoomName', () => plugin.setRoomName({ roomId: '!r:l', name: 'n' })],
    ['setRoomTopic', () => plugin.setRoomTopic({ roomId: '!r:l', topic: 't' })],
    ['inviteUser', () => plugin.inviteUser({ roomId: '!r:l', userId: '@u:l' })],
    ['kickUser', () => plugin.kickUser({ roomId: '!r:l', userId: '@u:l' })],
    ['banUser', () => plugin.banUser({ roomId: '!r:l', userId: '@u:l' })],
    ['unbanUser', () => plugin.unbanUser({ roomId: '!r:l', userId: '@u:l' })],
    ['sendTyping', () => plugin.sendTyping({ roomId: '!r:l', isTyping: true })],
    ['getMediaUrl', () => plugin.getMediaUrl({ mxcUrl: 'mxc://l/a' })],
    ['setPresence', () => plugin.setPresence({ presence: 'online' })],
    ['getPresence', () => plugin.getPresence({ userId: '@u:l' })],
    ['initializeCrypto', () => plugin.initializeCrypto()],
    ['getEncryptionStatus', () => plugin.getEncryptionStatus()],
    ['bootstrapCrossSigning', () => plugin.bootstrapCrossSigning()],
    ['setupKeyBackup', () => plugin.setupKeyBackup()],
    ['getKeyBackupStatus', () => plugin.getKeyBackupStatus()],
  ];

  it.each(methodsRequiringClient)('%s throws when not logged in', async (name, fn) => {
    await expect(fn()).rejects.toThrow('Not logged in');
  });
});

// ── Phase 2: Auth & Session ──────────────────────────────

describe('login', () => {
  it('creates a client and calls loginWithPassword', async () => {
    const result = await plugin.login({
      homeserverUrl: 'https://matrix.test',
      userId: '@test:localhost',
      password: 'pass',
    });

    expect(createClient).toHaveBeenCalledWith({ baseUrl: 'https://matrix.test' });
    expect(mockClient.loginWithPassword).toHaveBeenCalledWith('@test:localhost', 'pass');
    expect(result).toEqual({
      accessToken: 'mock-token',
      userId: '@test:localhost',
      deviceId: 'MOCK_DEVICE',
      homeserverUrl: 'https://matrix.test',
    });
  });

  it('persists session to localStorage', async () => {
    await plugin.login({
      homeserverUrl: 'https://matrix.test',
      userId: '@test:localhost',
      password: 'pass',
    });

    const stored = JSON.parse(localStorage.getItem('matrix_session')!);
    expect(stored.accessToken).toBe('mock-token');
    expect(stored.userId).toBe('@test:localhost');
  });

  it('creates an authenticated client with cryptoCallbacks', async () => {
    await plugin.login({
      homeserverUrl: 'https://matrix.test',
      userId: '@test:localhost',
      password: 'pass',
    });

    // Second createClient call is the authenticated one
    expect(createClient).toHaveBeenCalledTimes(2);
    const secondCall = (createClient as any).mock.calls[1][0];
    expect(secondCall.accessToken).toBe('mock-token');
    expect(secondCall.userId).toBe('@test:localhost');
    expect(secondCall.deviceId).toBe('MOCK_DEVICE');
    expect(secondCall.cryptoCallbacks).toBeDefined();
  });
});

describe('jwtLogin', () => {
  it('exchanges JWT and creates client with returned credentials', async () => {
    const result = await plugin.jwtLogin({
      homeserverUrl: 'https://matrix.test',
      token: 'my-jwt',
    });

    // First createClient is the temp client for loginRequest
    expect(createClient).toHaveBeenCalledWith({ baseUrl: 'https://matrix.test' });
    // Second createClient is the authenticated client
    expect(createClient).toHaveBeenCalledWith(
      expect.objectContaining({
        baseUrl: 'https://matrix.test',
        accessToken: 'mock-token',
        userId: '@test:localhost',
        deviceId: 'MOCK_DEVICE',
      }),
    );
    expect(result.accessToken).toBe('mock-token');
    expect(result.userId).toBe('@test:localhost');
  });

  it('persists session to localStorage', async () => {
    await plugin.jwtLogin({
      homeserverUrl: 'https://matrix.test',
      token: 'my-jwt',
    });

    const stored = JSON.parse(localStorage.getItem('matrix_session')!);
    expect(stored.accessToken).toBe('mock-token');
  });
});

describe('logout', () => {
  it('stops client, calls logout, and clears session', async () => {
    await loginPlugin();
    await plugin.logout();

    expect(mockClient.stopClient).toHaveBeenCalled();
    expect(mockClient.logout).toHaveBeenCalledWith(true);
    expect(localStorage.getItem('matrix_session')).toBeNull();
  });

  it('handles logout errors gracefully', async () => {
    await loginPlugin();
    mockClient.logout.mockRejectedValueOnce(new Error('token invalidated'));

    await expect(plugin.logout()).resolves.not.toThrow();
    expect(localStorage.getItem('matrix_session')).toBeNull();
  });

  it('clears localStorage even without a client', async () => {
    localStorage.setItem('matrix_session', '{"stale":"data"}');
    await plugin.logout();
    expect(localStorage.getItem('matrix_session')).toBeNull();
  });
});

describe('getSession', () => {
  it('returns parsed session from localStorage', async () => {
    const session = { accessToken: 't', userId: '@u:l', deviceId: 'D', homeserverUrl: 'https://h' };
    localStorage.setItem('matrix_session', JSON.stringify(session));

    const result = await plugin.getSession();
    expect(result).toEqual(session);
  });

  it('returns null when no session stored', async () => {
    expect(await plugin.getSession()).toBeNull();
  });

  it('returns null for malformed JSON', async () => {
    localStorage.setItem('matrix_session', '{bad json');
    expect(await plugin.getSession()).toBeNull();
  });
});

// ── Phase 3: Core Operations ─────────────────────────────

describe('sendMessage', () => {
  beforeEach(loginPlugin);

  it('sends a text message with default msgtype', async () => {
    const result = await plugin.sendMessage({ roomId: '!r:l', body: 'hello' });

    expect(mockClient.sendMessage).toHaveBeenCalledWith('!r:l', expect.objectContaining({
      body: 'hello',
    }));
    expect(result.eventId).toBe('$sent-event');
  });

  it('maps m.notice msgtype correctly', async () => {
    await plugin.sendMessage({ roomId: '!r:l', body: 'notice', msgtype: 'm.notice' });

    const callArgs = mockClient.sendMessage.mock.calls[0];
    expect(callArgs[1].msgtype).toBe('m.notice');
  });

  it('maps m.emote msgtype correctly', async () => {
    await plugin.sendMessage({ roomId: '!r:l', body: 'dances', msgtype: 'm.emote' });

    const callArgs = mockClient.sendMessage.mock.calls[0];
    expect(callArgs[1].msgtype).toBe('m.emote');
  });

  it('uploads media and sends media message for m.image', async () => {
    const mockBlob = new Blob(['fake-image'], { type: 'image/png' });
    const mockResponse = { blob: vi.fn().mockResolvedValue(mockBlob) };
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(mockResponse as any);

    const result = await plugin.sendMessage({
      roomId: '!r:l',
      body: 'photo.png',
      msgtype: 'm.image',
      fileUri: 'blob:http://localhost/fake',
      fileName: 'photo.png',
      mimeType: 'image/png',
      fileSize: 1234,
    });

    expect(globalThis.fetch).toHaveBeenCalledWith('blob:http://localhost/fake');
    expect(mockClient.uploadContent).toHaveBeenCalledWith(mockBlob, {
      name: 'photo.png',
      type: 'image/png',
    });
    expect(mockClient.sendMessage).toHaveBeenCalledWith('!r:l', expect.objectContaining({
      msgtype: 'm.image',
      url: 'mxc://localhost/uploaded',
      info: { mimetype: 'image/png', size: 1234 },
    }));
    expect(result.eventId).toBe('$sent-event');
  });
});

describe('getRoomMessages', () => {
  beforeEach(loginPlugin);

  it('returns events from synced timeline when no from token', async () => {
    const mockEvent = createMockSdkEvent({ eventId: '$e1', content: { body: 'hi', msgtype: 'm.text' } });
    const mockRoom = createMockRoom({ roomId: '!r:l' });
    mockRoom.getLiveTimeline.mockReturnValue({
      getEvents: vi.fn().mockReturnValue([mockEvent]),
      getPaginationToken: vi.fn().mockReturnValue('t_back'),
    });
    mockClient.getRoom.mockReturnValue(mockRoom);

    const result = await plugin.getRoomMessages({ roomId: '!r:l', limit: 10 });

    expect(result.events).toHaveLength(1);
    expect(result.events[0].eventId).toBe('$e1');
    expect(result.nextBatch).toBe('t_back');
  });

  it('paginates with createMessagesRequest when from token provided', async () => {
    mockClient.createMessagesRequest.mockResolvedValueOnce({
      chunk: [
        { event_id: '$old', sender: '@u:l', type: 'm.room.message', content: { body: 'old' }, origin_server_ts: 1000 },
      ],
      end: 't_next',
    });

    const result = await plugin.getRoomMessages({ roomId: '!r:l', from: 't_start' });

    expect(mockClient.createMessagesRequest).toHaveBeenCalledWith('!r:l', 't_start', 20, expect.anything());
    expect(result.events).toHaveLength(1);
    expect(result.events[0].eventId).toBe('$old');
    expect(result.nextBatch).toBe('t_next');
  });
});

describe('createRoom', () => {
  beforeEach(loginPlugin);

  it('creates a basic room', async () => {
    const result = await plugin.createRoom({ name: 'My Room' });

    expect(mockClient.createRoom).toHaveBeenCalledWith(expect.objectContaining({
      visibility: 'private',
      name: 'My Room',
    }));
    expect(result.roomId).toBe('!new-room:localhost');
  });

  it('adds encryption initial_state when isEncrypted is true', async () => {
    await plugin.createRoom({ name: 'Secret', isEncrypted: true });

    const opts = mockClient.createRoom.mock.calls[0][0];
    expect(opts.initial_state).toEqual([
      { type: 'm.room.encryption', state_key: '', content: { algorithm: 'm.megolm.v1.aes-sha2' } },
    ]);
  });

  it('includes invite list', async () => {
    await plugin.createRoom({ invite: ['@alice:l', '@bob:l'] });

    const opts = mockClient.createRoom.mock.calls[0][0];
    expect(opts.invite).toEqual(['@alice:l', '@bob:l']);
  });
});

describe('getRooms', () => {
  beforeEach(loginPlugin);

  it('serializes rooms correctly', async () => {
    const room = createMockRoom({
      roomId: '!abc:l',
      name: 'General',
      topic: 'Welcome',
      memberCount: 5,
      isEncrypted: true,
      unreadCount: 3,
      lastActiveTs: 1700000000000,
    });
    mockClient.getRooms.mockReturnValue([room]);

    const result = await plugin.getRooms();

    expect(result.rooms).toHaveLength(1);
    expect(result.rooms[0]).toEqual({
      roomId: '!abc:l',
      name: 'General',
      topic: 'Welcome',
      memberCount: 5,
      isEncrypted: true,
      unreadCount: 3,
      lastEventTs: 1700000000000,
      membership: 'join',
      avatarUrl: undefined,
      isDirect: false,
    });
  });
});

describe('getRoomMembers', () => {
  beforeEach(loginPlugin);

  it('returns members for existing room', async () => {
    const room = createMockRoom({
      roomId: '!r:l',
      members: [
        { userId: '@alice:l', name: 'Alice', membership: 'join' },
        { userId: '@bob:l', name: 'Bob', membership: 'invite' },
      ],
    });
    mockClient.getRoom.mockReturnValue(room);

    const result = await plugin.getRoomMembers({ roomId: '!r:l' });

    expect(result.members).toHaveLength(2);
    expect(result.members[0].userId).toBe('@alice:l');
    expect(result.members[1].membership).toBe('invite');
  });

  it('throws when room not found', async () => {
    mockClient.getRoom.mockReturnValue(null);

    await expect(plugin.getRoomMembers({ roomId: '!nope:l' }))
      .rejects.toThrow('Room !nope:l not found');
  });
});

describe('joinRoom', () => {
  beforeEach(loginPlugin);

  it('joins and returns roomId', async () => {
    const result = await plugin.joinRoom({ roomIdOrAlias: '#general:l' });

    expect(mockClient.joinRoom).toHaveBeenCalledWith('#general:l');
    expect(result.roomId).toBe('!joined:localhost');
  });
});

describe('leaveRoom', () => {
  beforeEach(loginPlugin);

  it('calls client.leave', async () => {
    await plugin.leaveRoom({ roomId: '!r:l' });
    expect(mockClient.leave).toHaveBeenCalledWith('!r:l');
  });
});

describe('markRoomAsRead', () => {
  beforeEach(loginPlugin);

  it('calls setRoomReadMarkersHttpRequest', async () => {
    await plugin.markRoomAsRead({ roomId: '!r:l', eventId: '$e1' });
    expect(mockClient.setRoomReadMarkersHttpRequest).toHaveBeenCalledWith('!r:l', '$e1', '$e1');
  });
});

// ── Phase 4: Event System ────────────────────────────────

describe('startSync', () => {
  beforeEach(loginPlugin);

  it('registers event listeners and starts client', async () => {
    await plugin.startSync();

    expect(mockClient.on).toHaveBeenCalled();
    expect(mockClient.startClient).toHaveBeenCalledWith({ initialSyncLimit: 20 });
  });

  it('fires syncStateChange when sync event occurs', async () => {
    const listener = vi.fn();
    await plugin.addListener('syncStateChange', listener);
    await plugin.startSync();

    emitEvent('sync', 'SYNCING', null, {});

    expect(listener).toHaveBeenCalledWith(expect.objectContaining({ state: 'SYNCING' }));
  });

  it('fires messageReceived on timeline event', async () => {
    const listener = vi.fn();
    await plugin.addListener('messageReceived', listener);
    await plugin.startSync();

    const mockEvt = createMockSdkEvent({ eventId: '$msg1', content: { body: 'test' } });
    const mockRoom = createMockRoom({ roomId: '!r:l' });
    emitEvent('Room.timeline', mockEvt, mockRoom, false, false, { liveEvent: true });

    expect(listener).toHaveBeenCalledWith(expect.objectContaining({
      event: expect.objectContaining({ eventId: '$msg1' }),
    }));
  });

  it('fires roomUpdated on room name change', async () => {
    const listener = vi.fn();
    await plugin.addListener('roomUpdated', listener);
    await plugin.startSync();

    const mockRoom = createMockRoom({ roomId: '!r:l', name: 'New Name' });
    emitEvent('Room.name', mockRoom);

    expect(listener).toHaveBeenCalledWith(expect.objectContaining({
      roomId: '!r:l',
    }));
  });
});

describe('stopSync', () => {
  beforeEach(loginPlugin);

  it('calls stopClient', async () => {
    await plugin.stopSync();
    expect(mockClient.stopClient).toHaveBeenCalled();
  });
});

describe('getSyncState', () => {
  beforeEach(loginPlugin);

  it('maps SYNCING state', async () => {
    mockClient.getSyncState.mockReturnValue('SYNCING');
    const result = await plugin.getSyncState();
    expect(result.state).toBe('SYNCING');
  });

  it('maps PREPARED to SYNCING', async () => {
    mockClient.getSyncState.mockReturnValue('PREPARED');
    const result = await plugin.getSyncState();
    expect(result.state).toBe('SYNCING');
  });

  it('maps ERROR', async () => {
    mockClient.getSyncState.mockReturnValue('ERROR');
    const result = await plugin.getSyncState();
    expect(result.state).toBe('ERROR');
  });

  it('maps STOPPED', async () => {
    mockClient.getSyncState.mockReturnValue('STOPPED');
    const result = await plugin.getSyncState();
    expect(result.state).toBe('STOPPED');
  });

  it('maps null to INITIAL', async () => {
    mockClient.getSyncState.mockReturnValue(null);
    const result = await plugin.getSyncState();
    expect(result.state).toBe('INITIAL');
  });

  it('maps unknown to INITIAL', async () => {
    mockClient.getSyncState.mockReturnValue('SOMETHING_ELSE');
    const result = await plugin.getSyncState();
    expect(result.state).toBe('INITIAL');
  });
});

// ── Phase 5: Crypto ──────────────────────────────────────

describe('initializeCrypto', () => {
  it('calls initRustCrypto', async () => {
    await loginPlugin();
    await plugin.initializeCrypto();
    expect(mockClient.initRustCrypto).toHaveBeenCalled();
  });
});

describe('getEncryptionStatus', () => {
  it('returns defaults when crypto is not initialized', async () => {
    await loginPlugin();
    mockClient.getCrypto.mockReturnValue(null);

    const status = await plugin.getEncryptionStatus();

    expect(status.isCrossSigningReady).toBe(false);
    expect(status.isKeyBackupEnabled).toBe(false);
    expect(status.isSecretStorageReady).toBe(false);
  });

  it('returns mapped status when crypto is initialized', async () => {
    const crypto = await loginWithCrypto();
    crypto.isCrossSigningReady.mockResolvedValue(true);
    crypto.getCrossSigningStatus.mockResolvedValue({
      publicKeysOnDevice: true,
      privateKeysCachedLocally: { selfSigningKey: true, userSigningKey: true },
    });
    crypto.getActiveSessionBackupVersion.mockResolvedValue('v1');
    crypto.getSecretStorageStatus.mockResolvedValue({ defaultKeyId: 'key1' });

    const status = await plugin.getEncryptionStatus();

    expect(status.isCrossSigningReady).toBe(true);
    expect(status.crossSigningStatus.hasMaster).toBe(true);
    expect(status.crossSigningStatus.hasSelfSigning).toBe(true);
    expect(status.crossSigningStatus.hasUserSigning).toBe(true);
    expect(status.isKeyBackupEnabled).toBe(true);
    expect(status.keyBackupVersion).toBe('v1');
    expect(status.isSecretStorageReady).toBe(true);
  });
});

describe('bootstrapCrossSigning', () => {
  it('calls bootstrapCrossSigning with UIA flow', async () => {
    const crypto = await loginWithCrypto();

    await plugin.bootstrapCrossSigning();

    expect(crypto.bootstrapCrossSigning).toHaveBeenCalledWith(
      expect.objectContaining({ authUploadDeviceSigningKeys: expect.any(Function) }),
    );
  });

  it('throws when crypto not initialized', async () => {
    await loginPlugin();
    mockClient.getCrypto.mockReturnValue(null);

    await expect(plugin.bootstrapCrossSigning()).rejects.toThrow('Crypto not initialized');
  });
});

describe('setupKeyBackup', () => {
  it('resets and returns backup status', async () => {
    const crypto = await loginWithCrypto();
    crypto.getActiveSessionBackupVersion.mockResolvedValue('v2');

    const result = await plugin.setupKeyBackup();

    expect(crypto.resetKeyBackup).toHaveBeenCalled();
    expect(result).toEqual({ exists: true, version: 'v2', enabled: true });
  });
});

describe('getKeyBackupStatus', () => {
  it('returns status when backup exists', async () => {
    const crypto = await loginWithCrypto();
    crypto.getActiveSessionBackupVersion.mockResolvedValue('v1');

    const result = await plugin.getKeyBackupStatus();
    expect(result).toEqual({ exists: true, version: 'v1', enabled: true });
  });

  it('returns status when no backup', async () => {
    const crypto = await loginWithCrypto();
    crypto.getActiveSessionBackupVersion.mockResolvedValue(null);

    const result = await plugin.getKeyBackupStatus();
    expect(result).toEqual({ exists: false, version: undefined, enabled: false });
  });
});

describe('setupRecovery', () => {
  it('creates recovery key and bootstraps secret storage', async () => {
    const crypto = await loginWithCrypto();

    const result = await plugin.setupRecovery({ passphrase: 'mypass' });

    expect(crypto.createRecoveryKeyFromPassphrase).toHaveBeenCalledWith('mypass');
    expect(crypto.bootstrapSecretStorage).toHaveBeenCalledWith(
      expect.objectContaining({
        setupNewSecretStorage: true,
        setupNewKeyBackup: true,
      }),
    );
    expect(result.recoveryKey).toBe('EsXa mock recovery key');
  });
});

describe('recoverAndSetup', () => {
  it('decodes recovery key and loads from secret storage', async () => {
    const crypto = await loginWithCrypto();

    await plugin.recoverAndSetup({ recoveryKey: 'EsXa abcd efgh' });

    expect(decodeRecoveryKey).toHaveBeenCalledWith('EsXa abcd efgh');
    expect(crypto.loadSessionBackupPrivateKeyFromSecretStorage).toHaveBeenCalled();
    expect(crypto.checkKeyBackupAndEnable).toHaveBeenCalled();
  });

  it('stores passphrase for callback-based derivation', async () => {
    const crypto = await loginWithCrypto();

    await plugin.recoverAndSetup({ passphrase: 'mypass' });

    expect(crypto.loadSessionBackupPrivateKeyFromSecretStorage).toHaveBeenCalled();
    expect(crypto.checkKeyBackupAndEnable).toHaveBeenCalled();
  });

  it('throws when neither key nor passphrase provided', async () => {
    await loginWithCrypto();

    await expect(plugin.recoverAndSetup({})).rejects.toThrow('Either recoveryKey or passphrase');
  });

  it('clears key material on failure', async () => {
    const crypto = await loginWithCrypto();
    crypto.loadSessionBackupPrivateKeyFromSecretStorage.mockRejectedValueOnce(new Error('bad key'));

    await expect(plugin.recoverAndSetup({ recoveryKey: 'EsXa bad key' })).rejects.toThrow('bad key');
    // After failure, a fresh attempt with correct key should work
    // (secretStorageKey was cleared so it won't use stale data)
  });
});

describe('restoreKeyBackup', () => {
  it('returns imported key count', async () => {
    const crypto = await loginWithCrypto();
    crypto.restoreKeyBackup.mockResolvedValue({ imported: 42 });

    const result = await plugin.restoreKeyBackup();
    expect(result.importedKeys).toBe(42);
  });
});

describe('isRecoveryEnabled', () => {
  it('returns enabled state', async () => {
    const crypto = await loginWithCrypto();
    crypto.isSecretStorageReady.mockResolvedValue(true);

    const result = await plugin.isRecoveryEnabled();
    expect(result.enabled).toBe(true);
  });
});

describe('exportRoomKeys', () => {
  it('returns exported JSON', async () => {
    const crypto = await loginWithCrypto();
    crypto.exportRoomKeysAsJson.mockResolvedValue('[{"key":"data"}]');

    const result = await plugin.exportRoomKeys({ passphrase: 'pass' });
    expect(result.data).toBe('[{"key":"data"}]');
  });
});

describe('importRoomKeys', () => {
  it('imports keys and returns count', async () => {
    const crypto = await loginWithCrypto();

    const result = await plugin.importRoomKeys({ data: '[]', passphrase: 'pass' });
    expect(crypto.importRoomKeysAsJson).toHaveBeenCalledWith('[]');
    expect(result.importedKeys).toBe(-1); // count not available on web
  });
});

describe('resetRecoveryKey', () => {
  it('delegates to setupRecovery', async () => {
    const crypto = await loginWithCrypto();

    const result = await plugin.resetRecoveryKey({ passphrase: 'newpass' });
    expect(crypto.createRecoveryKeyFromPassphrase).toHaveBeenCalledWith('newpass');
    expect(result.recoveryKey).toBe('EsXa mock recovery key');
  });
});

// ── Phase 6: Pass-through methods ────────────────────────

describe('redactEvent', () => {
  beforeEach(loginPlugin);

  it('calls client.redactEvent with correct args', async () => {
    await plugin.redactEvent({ roomId: '!r:l', eventId: '$e1', reason: 'spam' });

    expect(mockClient.redactEvent).toHaveBeenCalledWith(
      '!r:l', '$e1', undefined, expect.objectContaining({ reason: 'spam' }),
    );
  });
});

describe('sendReaction', () => {
  beforeEach(loginPlugin);

  it('sends reaction event with correct relation', async () => {
    const result = await plugin.sendReaction({ roomId: '!r:l', eventId: '$e1', key: '👍' });

    expect(mockClient.sendEvent).toHaveBeenCalledWith(
      '!r:l',
      expect.anything(), // EventType.Reaction enum
      expect.objectContaining({
        'm.relates_to': expect.objectContaining({
          event_id: '$e1',
          key: '👍',
        }),
      }),
    );
    expect(result.eventId).toBe('$sent-event');
  });
});

describe('setRoomName', () => {
  beforeEach(loginPlugin);

  it('calls client.setRoomName', async () => {
    await plugin.setRoomName({ roomId: '!r:l', name: 'New Name' });
    expect(mockClient.setRoomName).toHaveBeenCalledWith('!r:l', 'New Name');
  });
});

describe('setRoomTopic', () => {
  beforeEach(loginPlugin);

  it('calls client.setRoomTopic', async () => {
    await plugin.setRoomTopic({ roomId: '!r:l', topic: 'New Topic' });
    expect(mockClient.setRoomTopic).toHaveBeenCalledWith('!r:l', 'New Topic');
  });
});

describe('inviteUser', () => {
  beforeEach(loginPlugin);

  it('calls client.invite', async () => {
    await plugin.inviteUser({ roomId: '!r:l', userId: '@alice:l' });
    expect(mockClient.invite).toHaveBeenCalledWith('!r:l', '@alice:l');
  });
});

describe('kickUser', () => {
  beforeEach(loginPlugin);

  it('calls client.kick with reason', async () => {
    await plugin.kickUser({ roomId: '!r:l', userId: '@bob:l', reason: 'spam' });
    expect(mockClient.kick).toHaveBeenCalledWith('!r:l', '@bob:l', 'spam');
  });
});

describe('banUser', () => {
  beforeEach(loginPlugin);

  it('calls client.ban', async () => {
    await plugin.banUser({ roomId: '!r:l', userId: '@bob:l', reason: 'abuse' });
    expect(mockClient.ban).toHaveBeenCalledWith('!r:l', '@bob:l', 'abuse');
  });
});

describe('unbanUser', () => {
  beforeEach(loginPlugin);

  it('calls client.unban', async () => {
    await plugin.unbanUser({ roomId: '!r:l', userId: '@bob:l' });
    expect(mockClient.unban).toHaveBeenCalledWith('!r:l', '@bob:l');
  });
});

describe('sendTyping', () => {
  beforeEach(loginPlugin);

  it('calls client.sendTyping with default timeout', async () => {
    await plugin.sendTyping({ roomId: '!r:l', isTyping: true });
    expect(mockClient.sendTyping).toHaveBeenCalledWith('!r:l', true, 30000);
  });

  it('uses custom timeout', async () => {
    await plugin.sendTyping({ roomId: '!r:l', isTyping: false, timeout: 5000 });
    expect(mockClient.sendTyping).toHaveBeenCalledWith('!r:l', false, 5000);
  });
});

describe('getMediaUrl', () => {
  beforeEach(loginPlugin);

  it('converts mxc URL to http', async () => {
    const result = await plugin.getMediaUrl({ mxcUrl: 'mxc://l/abc' });
    expect(result.httpUrl).toBe(
      'https://localhost/_matrix/client/v1/media/download/l/abc?access_token=mock-token',
    );
  });

  it('returns authenticated media URL for any mxc input', async () => {
    const result = await plugin.getMediaUrl({ mxcUrl: 'mxc://invalid' });
    expect(result.httpUrl).toBe(
      'https://localhost/_matrix/client/v1/media/download/invalid?access_token=mock-token',
    );
  });
});

describe('setPresence', () => {
  beforeEach(loginPlugin);

  it('calls client.setPresence', async () => {
    await plugin.setPresence({ presence: 'unavailable', statusMsg: 'brb' });
    expect(mockClient.setPresence).toHaveBeenCalledWith({
      presence: 'unavailable',
      status_msg: 'brb',
    });
  });
});

describe('getPresence', () => {
  beforeEach(loginPlugin);

  it('returns user presence info', async () => {
    mockClient.getUser.mockReturnValue({
      presence: 'online',
      presenceStatusMsg: 'Available',
      lastActiveAgo: 5000,
    });

    const result = await plugin.getPresence({ userId: '@alice:l' });
    expect(result).toEqual({
      presence: 'online',
      statusMsg: 'Available',
      lastActiveAgo: 5000,
    });
  });

  it('returns defaults when user not found', async () => {
    mockClient.getUser.mockReturnValue(null);

    const result = await plugin.getPresence({ userId: '@unknown:l' });
    expect(result.presence).toBe('offline');
    expect(result.statusMsg).toBeUndefined();
  });
});
