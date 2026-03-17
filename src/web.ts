import { WebPlugin } from '@capacitor/core';
import {
  createClient,
  ClientEvent,
  RoomEvent,
  RoomMemberEvent,
  Direction,
  MsgType,
  EventType,
  RelationType,
  UserEvent,
} from 'matrix-js-sdk';
import { decodeRecoveryKey } from 'matrix-js-sdk/lib/crypto-api/recovery-key';
import { deriveRecoveryKeyFromPassphrase } from 'matrix-js-sdk/lib/crypto-api/key-passphrase';
import type { MatrixClient, Room, MatrixEvent as SdkMatrixEvent, User } from 'matrix-js-sdk';

import type {
  MatrixPlugin,
  LoginOptions,
  LoginWithTokenOptions,
  SessionInfo,
  SendMessageOptions,
  EditMessageOptions,
  SendReplyOptions,
  UploadContentOptions,
  UploadContentResult,
  ThumbnailUrlOptions,
  PusherOptions,
  DeviceInfo,
  MatrixEvent,
  RoomSummary,
  RoomMember,
  SyncState,
  EncryptionStatus,
  KeyBackupStatus,
  RecoveryKeyInfo,
  PresenceInfo,
  UserProfile,
} from './definitions';

const SESSION_KEY = 'matrix_session';

export class MatrixWeb extends WebPlugin implements MatrixPlugin {
  private client?: MatrixClient;
  private secretStorageKey?: Uint8Array<ArrayBuffer>;
  private recoveryPassphrase?: string;

  private readonly _cryptoCallbacks = {
    getSecretStorageKey: async (
      opts: { keys: Record<string, unknown> },
    ): Promise<[string, Uint8Array<ArrayBuffer>] | null> => {
      const keyId = Object.keys(opts.keys)[0];
      if (!keyId) return null;

      // If we have the raw key cached, use it directly
      if (this.secretStorageKey) {
        return [keyId, this.secretStorageKey];
      }

      // If we have a passphrase, derive the key using the server's stored parameters
      if (this.recoveryPassphrase) {
        const keyInfo = opts.keys[keyId] as {
          passphrase?: { salt: string; iterations: number; bits?: number };
        };
        if (keyInfo?.passphrase) {
          const derived = await deriveRecoveryKeyFromPassphrase(
            this.recoveryPassphrase,
            keyInfo.passphrase.salt,
            keyInfo.passphrase.iterations,
            keyInfo.passphrase.bits ?? 256,
          );
          this.secretStorageKey = derived;
          return [keyId, derived];
        }
      }

      return null;
    },
    cacheSecretStorageKey: (
      _keyId: string,
      _keyInfo: unknown,
      key: Uint8Array<ArrayBuffer>,
    ): void => {
      this.secretStorageKey = key;
    },
  };

  // ── Auth ──────────────────────────────────────────────

  async login(options: LoginOptions): Promise<SessionInfo> {
    const tmpClient = createClient({ baseUrl: options.homeserverUrl });
    const res = await tmpClient.loginWithPassword(options.userId, options.password);

    this.client = createClient({
      baseUrl: options.homeserverUrl,
      accessToken: res.access_token,
      userId: res.user_id,
      deviceId: res.device_id,
      cryptoCallbacks: this._cryptoCallbacks,
    });

    const session: SessionInfo = {
      accessToken: res.access_token,
      userId: res.user_id,
      deviceId: res.device_id,
      homeserverUrl: options.homeserverUrl,
    };

    this.persistSession(session);
    return session;
  }

  async loginWithToken(options: LoginWithTokenOptions): Promise<SessionInfo> {
    this.client = createClient({
      baseUrl: options.homeserverUrl,
      accessToken: options.accessToken,
      userId: options.userId,
      deviceId: options.deviceId,
      cryptoCallbacks: this._cryptoCallbacks,
    });

    const session: SessionInfo = {
      accessToken: options.accessToken,
      userId: options.userId,
      deviceId: options.deviceId,
      homeserverUrl: options.homeserverUrl,
    };

    this.persistSession(session);
    return session;
  }

  async logout(): Promise<void> {
    if (this.client) {
      this.client.stopClient();
      try {
        await this.client.logout(true);
      } catch {
        // ignore logout errors (e.g. token already invalidated)
      }
      this.client = undefined;
    }
    localStorage.removeItem(SESSION_KEY);
  }

  async getSession(): Promise<SessionInfo | null> {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as SessionInfo;
    } catch {
      return null;
    }
  }

  // ── Sync ──────────────────────────────────────────────

  async startSync(): Promise<void> {
    this.requireClient();

    this.client!.on(ClientEvent.Sync, (state, _prev, data) => {
      const mapped = this.mapSyncState(state);
      this.notifyListeners('syncStateChange', {
        state: mapped,
        error: data?.error?.message,
      });
    });

    this.client!.on(RoomEvent.Timeline, (event: SdkMatrixEvent, room: Room | undefined) => {
      this.notifyListeners('messageReceived', {
        event: this.serializeEvent(event, room?.roomId),
      });
      // When an encrypted event arrives, listen for decryption and re-notify
      if (event.isBeingDecrypted() || event.getType() === 'm.room.encrypted') {
        event.once('Event.decrypted' as any, () => {
          this.notifyListeners('messageReceived', {
            event: this.serializeEvent(event, room?.roomId),
          });
        });
      }
      // When a reaction or redaction arrives, re-emit the parent event with updated aggregated reactions
      if (event.getType() === EventType.Reaction || event.getType() === EventType.RoomRedaction) {
        const rel = event.getContent()?.['m.relates_to'];
        const targetId = rel?.event_id || event.getAssociatedId();
        if (targetId && room) {
          const targetEvent = room.findEventById(targetId);
          if (targetEvent) {
            // Small delay to let the SDK finish aggregation
            setTimeout(() => {
              this.notifyListeners('messageReceived', {
                event: this.serializeEvent(targetEvent, room.roomId),
              });
            }, 100);
          }
        }
      }
    });

    this.client!.on(RoomEvent.Receipt, (_event: SdkMatrixEvent, room: Room) => {
      this.notifyListeners('receiptReceived', {
        roomId: room.roomId,
      });
      // Re-emit own sent messages with updated read status
      const myUserId = this.client?.getUserId();
      if (myUserId) {
        const timeline = room.getLiveTimeline().getEvents();
        // Walk backwards through recent events; stop after checking a reasonable batch
        const limit = Math.min(timeline.length, 50);
        for (let i = timeline.length - 1; i >= timeline.length - limit; i--) {
          const evt = timeline[i];
          if (evt.getSender() !== myUserId) continue;
          const serialized = this.serializeEvent(evt, room.roomId);
          if (serialized.status === 'read') {
            this.notifyListeners('messageReceived', { event: serialized });
          }
        }
      }
    });

    this.client!.on(RoomEvent.Name, (room: Room) => {
      this.notifyListeners('roomUpdated', {
        roomId: room.roomId,
        summary: this.serializeRoom(room),
      });
    });

    this.client!.on(RoomMemberEvent.Typing, (_event: SdkMatrixEvent, member: any) => {
      const roomId = member?.roomId;
      if (roomId) {
        const room = this.client!.getRoom(roomId);
        if (room) {
          const typingEvent = room.currentState.getStateEvents('m.typing', '');
          const userIds: string[] = typingEvent?.getContent()?.user_ids ?? [];
          this.notifyListeners('typingChanged', { roomId, userIds });
        }
      }
    });

    this.client!.on(UserEvent.Presence, (_event: SdkMatrixEvent | undefined, user: User) => {
      this.notifyListeners('presenceChanged', {
        userId: user.userId,
        presence: {
          presence: (user.presence as PresenceInfo['presence']) ?? 'offline',
          statusMsg: user.presenceStatusMsg ?? undefined,
          lastActiveAgo: user.lastActiveAgo ?? undefined,
        },
      });
    });

    await this.client!.startClient({ initialSyncLimit: 20 });
  }

  async stopSync(): Promise<void> {
    this.requireClient();
    this.client!.stopClient();
  }

  async getSyncState(): Promise<{ state: SyncState }> {
    this.requireClient();
    const raw = this.client!.getSyncState();
    return { state: this.mapSyncState(raw) };
  }

  // ── Rooms ─────────────────────────────────────────────

  async createRoom(options: {
    name?: string;
    topic?: string;
    isEncrypted?: boolean;
    isDirect?: boolean;
    invite?: string[];
    preset?: 'private_chat' | 'trusted_private_chat' | 'public_chat';
    historyVisibility?: 'invited' | 'joined' | 'shared' | 'world_readable';
  }): Promise<{ roomId: string }> {
    this.requireClient();

    const createOpts: Record<string, unknown> = {
      visibility: 'private' as const,
    };
    if (options.name) createOpts.name = options.name;
    if (options.topic) createOpts.topic = options.topic;
    if (options.invite?.length) createOpts.invite = options.invite;
    if (options.preset) createOpts.preset = options.preset;
    if (options.isDirect) createOpts.is_direct = true;

    const initialState: Record<string, unknown>[] = [];
    if (options.isEncrypted) {
      initialState.push({
        type: 'm.room.encryption',
        state_key: '',
        content: { algorithm: 'm.megolm.v1.aes-sha2' },
      });
    }
    if (options.historyVisibility) {
      initialState.push({
        type: 'm.room.history_visibility',
        state_key: '',
        content: { history_visibility: options.historyVisibility },
      });
    }
    if (initialState.length > 0) {
      createOpts.initial_state = initialState;
    }

    const res = await this.client!.createRoom(createOpts);
    return { roomId: res.room_id };
  }

  async getRooms(): Promise<{ rooms: RoomSummary[] }> {
    this.requireClient();
    const rooms = this.client!.getRooms().map((r) => this.serializeRoom(r));
    return { rooms };
  }

  async getRoomMembers(options: { roomId: string }): Promise<{ members: RoomMember[] }> {
    this.requireClient();
    const room = this.client!.getRoom(options.roomId);
    if (!room) throw new Error(`Room ${options.roomId} not found`);

    await room.loadMembersIfNeeded();
    const members: RoomMember[] = room.getMembers().map((m) => ({
      userId: m.userId,
      displayName: m.name ?? undefined,
      membership: m.membership as RoomMember['membership'],
      avatarUrl: m.getMxcAvatarUrl() ?? undefined,
    }));

    return { members };
  }

  async joinRoom(options: { roomIdOrAlias: string }): Promise<{ roomId: string }> {
    this.requireClient();
    const room = await this.client!.joinRoom(options.roomIdOrAlias);
    return { roomId: room.roomId };
  }

  async leaveRoom(options: { roomId: string }): Promise<void> {
    this.requireClient();
    await this.client!.leave(options.roomId);
  }

  async forgetRoom(options: { roomId: string }): Promise<void> {
    this.requireClient();
    await this.client!.forget(options.roomId);
  }

  // ── Messaging ─────────────────────────────────────────

  async sendMessage(options: SendMessageOptions): Promise<{ eventId: string }> {
    this.requireClient();

    const msgtype = options.msgtype ?? 'm.text';
    const mediaTypes = ['m.image', 'm.audio', 'm.video', 'm.file'];

    if (mediaTypes.includes(msgtype) && options.fileUri) {
      // Media message: upload file then send
      const response = await fetch(options.fileUri);
      const blob = await response.blob();
      const uploadRes = await this.client!.uploadContent(blob, {
        name: options.fileName,
        type: options.mimeType,
      });
      const mxcUrl = uploadRes.content_uri;
      const content: Record<string, unknown> = {
        msgtype,
        body: options.body || options.fileName || 'file',
        url: mxcUrl,
        info: {
          mimetype: options.mimeType,
          size: options.fileSize ?? blob.size,
        },
      };
      const res = await this.client!.sendMessage(options.roomId, content as any);
      return { eventId: res.event_id };
    }

    // Text message
    const msgtypeMap = {
      'm.text': MsgType.Text,
      'm.notice': MsgType.Notice,
      'm.emote': MsgType.Emote,
    } as const;
    const mappedType = msgtypeMap[msgtype as keyof typeof msgtypeMap] ?? MsgType.Text;
    const res = await this.client!.sendMessage(options.roomId, {
      msgtype: mappedType,
      body: options.body,
    });
    return { eventId: res.event_id };
  }

  async editMessage(options: EditMessageOptions): Promise<{ eventId: string }> {
    this.requireClient();
    const content: Record<string, unknown> = {
      msgtype: MsgType.Text,
      body: `* ${options.newBody}`,
      'm.new_content': {
        msgtype: MsgType.Text,
        body: options.newBody,
      },
      'm.relates_to': {
        rel_type: 'm.replace',
        event_id: options.eventId,
      },
    };
    const res = await this.client!.sendMessage(options.roomId, content as any);
    return { eventId: res.event_id };
  }

  async sendReply(options: SendReplyOptions): Promise<{ eventId: string }> {
    this.requireClient();

    const msgtype = options.msgtype ?? 'm.text';
    const mediaTypes = ['m.image', 'm.audio', 'm.video', 'm.file'];

    let content: Record<string, unknown>;

    if (mediaTypes.includes(msgtype) && options.fileUri) {
      // Media reply: upload file then send with reply relation
      const response = await fetch(options.fileUri);
      const blob = await response.blob();
      const uploadRes = await this.client!.uploadContent(blob, {
        name: options.fileName,
        type: options.mimeType,
      });
      content = {
        msgtype,
        body: options.body || options.fileName || 'file',
        url: uploadRes.content_uri,
        info: {
          mimetype: options.mimeType,
          size: options.fileSize ?? blob.size,
        },
        'm.relates_to': {
          'm.in_reply_to': {
            event_id: options.replyToEventId,
          },
        },
      };
    } else {
      // Text reply
      content = {
        msgtype: MsgType.Text,
        body: options.body,
        'm.relates_to': {
          'm.in_reply_to': {
            event_id: options.replyToEventId,
          },
        },
      };
    }

    const res = await this.client!.sendMessage(options.roomId, content as any);
    return { eventId: res.event_id };
  }

  async getRoomMessages(
    options: { roomId: string; limit?: number; from?: string },
  ): Promise<{ events: MatrixEvent[]; nextBatch?: string }> {
    this.requireClient();

    const limit = options.limit ?? 20;
    const room = this.client!.getRoom(options.roomId);

    // If no explicit pagination token, return events from the synced timeline
    if (!options.from && room) {
      // Paginate backwards so we have enough events (initial sync may be small)
      try {
        await this.client!.scrollback(room, limit);
      } catch {
        // scrollback may fail if there's no more history
      }

      const timeline = room.getLiveTimeline();
      const timelineEvents = timeline.getEvents();
      // Filter out reactions and redactions before slicing — they're aggregated into parent events
      const displayableEvents = timelineEvents.filter((e) => {
        const t = e.getType();
        return t !== EventType.Reaction && t !== EventType.RoomRedaction;
      });
      const events: MatrixEvent[] = displayableEvents
        .slice(-limit)
        .map((e) => this.serializeEvent(e, options.roomId))
        .sort((a, b) => a.originServerTs - b.originServerTs);
      const backToken = timeline.getPaginationToken(Direction.Backward) ?? undefined;
      return { events, nextBatch: backToken };
    }

    // Paginate further back using the token
    const fromToken = options.from ?? null;
    const res = await this.client!.createMessagesRequest(
      options.roomId,
      fromToken,
      limit,
      Direction.Backward,
    );

    const events: MatrixEvent[] = (res.chunk ?? []).map((e) => ({
      eventId: e.event_id,
      roomId: options.roomId,
      senderId: e.sender,
      type: e.type,
      content: (e.content ?? {}) as Record<string, unknown>,
      originServerTs: e.origin_server_ts,
    }));

    return { events, nextBatch: res.end ?? undefined };
  }

  async markRoomAsRead(options: { roomId: string; eventId: string }): Promise<void> {
    this.requireClient();
    const room = this.client!.getRoom(options.roomId);
    if (room) {
      const event = room.findEventById(options.eventId);
      if (event) {
        await this.client!.sendReadReceipt(event);
        return;
      }
    }
    // Fallback to HTTP request if event not found locally
    await this.client!.setRoomReadMarkersHttpRequest(
      options.roomId,
      options.eventId,
      options.eventId,
    );
  }

  async refreshEventStatuses(options: { roomId: string; eventIds: string[] }): Promise<{ events: import('./definitions').MatrixEvent[] }> {
    this.requireClient();
    const room = this.client!.getRoom(options.roomId);
    if (!room) return { events: [] };
    const events: import('./definitions').MatrixEvent[] = [];
    for (const eid of options.eventIds) {
      const event = room.findEventById(eid);
      if (event) {
        events.push(this.serializeEvent(event, options.roomId));
      }
    }
    return { events };
  }

  // ── Redactions & Reactions ───────────────────────────────

  async redactEvent(options: { roomId: string; eventId: string; reason?: string }): Promise<void> {
    this.requireClient();
    await this.client!.redactEvent(options.roomId, options.eventId, undefined, {
      reason: options.reason,
    } as any);
  }

  async sendReaction(options: { roomId: string; eventId: string; key: string }): Promise<{ eventId: string }> {
    this.requireClient();
    const myUserId = this.client!.getUserId();

    // Check if the user already reacted with this key — if so, toggle off (redact)
    const room = this.client!.getRoom(options.roomId);
    if (room && myUserId) {
      try {
        const relations = room.relations.getChildEventsForEvent(
          options.eventId,
          RelationType.Annotation,
          EventType.Reaction,
        );
        if (relations) {
          const existing = relations.getRelations().find(
            (e) => e.getSender() === myUserId && e.getContent()?.['m.relates_to']?.key === options.key,
          );
          if (existing) {
            const existingId = existing.getId();
            if (existingId) {
              await this.client!.redactEvent(options.roomId, existingId);
              return { eventId: existingId };
            }
          }
        }
      } catch {
        // fall through to send
      }
    }

    const res = await this.client!.sendEvent(options.roomId, EventType.Reaction, {
      'm.relates_to': {
        rel_type: RelationType.Annotation,
        event_id: options.eventId,
        key: options.key,
      },
    });
    return { eventId: res.event_id };
  }

  // ── Room Management ────────────────────────────────────

  async setRoomName(options: { roomId: string; name: string }): Promise<void> {
    this.requireClient();
    await this.client!.setRoomName(options.roomId, options.name);
  }

  async setRoomTopic(options: { roomId: string; topic: string }): Promise<void> {
    this.requireClient();
    await this.client!.setRoomTopic(options.roomId, options.topic);
  }

  async setRoomAvatar(options: { roomId: string; mxcUrl: string }): Promise<void> {
    this.requireClient();
    await this.client!.sendStateEvent(options.roomId, 'm.room.avatar' as any, { url: options.mxcUrl });
  }

  async inviteUser(options: { roomId: string; userId: string }): Promise<void> {
    this.requireClient();
    await this.client!.invite(options.roomId, options.userId);
  }

  async kickUser(options: { roomId: string; userId: string; reason?: string }): Promise<void> {
    this.requireClient();
    await this.client!.kick(options.roomId, options.userId, options.reason);
  }

  async banUser(options: { roomId: string; userId: string; reason?: string }): Promise<void> {
    this.requireClient();
    await this.client!.ban(options.roomId, options.userId, options.reason);
  }

  async unbanUser(options: { roomId: string; userId: string }): Promise<void> {
    this.requireClient();
    await this.client!.unban(options.roomId, options.userId);
  }

  // ── Typing ─────────────────────────────────────────────

  async sendTyping(options: { roomId: string; isTyping: boolean; timeout?: number }): Promise<void> {
    this.requireClient();
    await this.client!.sendTyping(options.roomId, options.isTyping, options.timeout ?? 30000);
  }

  // ── Media ──────────────────────────────────────────────

  async getMediaUrl(options: { mxcUrl: string }): Promise<{ httpUrl: string }> {
    this.requireClient();
    // Use the authenticated media endpoint (Matrix v1.11+)
    const mxcPath = options.mxcUrl.replace('mxc://', '');
    const baseUrl = this.client!.getHomeserverUrl().replace(/\/$/, '');
    const accessToken = this.client!.getAccessToken();
    const httpUrl = `${baseUrl}/_matrix/client/v1/media/download/${mxcPath}?access_token=${accessToken}`;
    return { httpUrl };
  }

  async getThumbnailUrl(options: ThumbnailUrlOptions): Promise<{ httpUrl: string }> {
    this.requireClient();
    const mxcPath = options.mxcUrl.replace('mxc://', '');
    const baseUrl = this.client!.getHomeserverUrl().replace(/\/$/, '');
    const accessToken = this.client!.getAccessToken();
    const method = options.method ?? 'scale';
    const httpUrl = `${baseUrl}/_matrix/client/v1/media/thumbnail/${mxcPath}?width=${options.width}&height=${options.height}&method=${method}&access_token=${accessToken}`;
    return { httpUrl };
  }

  async uploadContent(options: UploadContentOptions): Promise<UploadContentResult> {
    this.requireClient();
    const response = await fetch(options.fileUri);
    const blob = await response.blob();
    const res = await this.client!.uploadContent(blob, {
      name: options.fileName,
      type: options.mimeType,
    });
    return { contentUri: res.content_uri };
  }

  // ── Presence ───────────────────────────────────────────

  async setPresence(options: { presence: 'online' | 'offline' | 'unavailable'; statusMsg?: string }): Promise<void> {
    this.requireClient();
    await this.client!.setPresence({
      presence: options.presence,
      status_msg: options.statusMsg,
    });
  }

  async getPresence(options: { userId: string }): Promise<PresenceInfo> {
    this.requireClient();
    const user = this.client!.getUser(options.userId);
    return {
      presence: (user?.presence as PresenceInfo['presence']) ?? 'offline',
      statusMsg: user?.presenceStatusMsg ?? undefined,
      lastActiveAgo: user?.lastActiveAgo ?? undefined,
    };
  }

  // ── Device Management ──────────────────────────────────

  async getDevices(): Promise<{ devices: DeviceInfo[] }> {
    this.requireClient();
    const res = await this.client!.getDevices();
    const devices: DeviceInfo[] = (res.devices ?? []).map((d: any) => ({
      deviceId: d.device_id,
      displayName: d.display_name ?? undefined,
      lastSeenTs: d.last_seen_ts ?? undefined,
      lastSeenIp: d.last_seen_ip ?? undefined,
    }));
    return { devices };
  }

  async deleteDevice(options: { deviceId: string; auth?: Record<string, unknown> }): Promise<void> {
    this.requireClient();
    await this.client!.deleteDevice(options.deviceId, options.auth as any);
  }

  // ── Push ──────────────────────────────────────────────

  async setPusher(options: PusherOptions): Promise<void> {
    this.requireClient();
    await this.client!.setPusher({
      pushkey: options.pushkey,
      kind: options.kind ?? undefined,
      app_id: options.appId,
      app_display_name: options.appDisplayName,
      device_display_name: options.deviceDisplayName,
      lang: options.lang,
      data: options.data,
    } as any);
  }

  // ── Encryption ──────────────────────────────────────────

  async initializeCrypto(): Promise<void> {
    this.requireClient();
    const userId = this.client!.getUserId();
    const deviceId = this.client!.getDeviceId();
    await this.client!.initRustCrypto({
      cryptoDatabasePrefix: `matrix-js-sdk/${userId}/${deviceId}`,
    });
  }

  async getEncryptionStatus(): Promise<EncryptionStatus> {
    this.requireClient();
    const crypto = this.client!.getCrypto();
    if (!crypto) {
      return {
        isCrossSigningReady: false,
        crossSigningStatus: { hasMaster: false, hasSelfSigning: false, hasUserSigning: false, isReady: false },
        isKeyBackupEnabled: false,
        isSecretStorageReady: false,
      };
    }

    const csReady = await crypto.isCrossSigningReady();
    const csStatus = await crypto.getCrossSigningStatus();
    const backupVersion = await crypto.getActiveSessionBackupVersion();

    // Use getSecretStorageStatus().defaultKeyId to check if secret storage was
    // set up at all, rather than isSecretStorageReady() which also checks that
    // cross-signing keys are stored (too strict for Phase 1).
    const ssStatus = await crypto.getSecretStorageStatus();
    const ssHasKey = ssStatus.defaultKeyId !== null;

    return {
      isCrossSigningReady: csReady,
      crossSigningStatus: {
        hasMaster: csStatus.publicKeysOnDevice,
        hasSelfSigning: csStatus.privateKeysCachedLocally.selfSigningKey,
        hasUserSigning: csStatus.privateKeysCachedLocally.userSigningKey,
        isReady: csReady,
      },
      isKeyBackupEnabled: backupVersion !== null,
      keyBackupVersion: backupVersion ?? undefined,
      isSecretStorageReady: ssHasKey,
    };
  }

  async bootstrapCrossSigning(): Promise<void> {
    const crypto = await this.ensureCrypto();
    await crypto.bootstrapCrossSigning({
      setupNewCrossSigning: true,
      authUploadDeviceSigningKeys: async (makeRequest) => {
        // UIA flow: attempt with dummy auth, fall back to session-based retry
        try {
          await makeRequest({ type: 'm.login.dummy' });
        } catch (e: any) {
          const session = e?.data?.session;
          if (session) {
            await makeRequest({ type: 'm.login.dummy', session });
          } else {
            throw e;
          }
        }
      },
    });
  }

  async setupKeyBackup(): Promise<KeyBackupStatus> {
    const crypto = await this.ensureCrypto();
    await crypto.resetKeyBackup();
    const version = await crypto.getActiveSessionBackupVersion();
    return { exists: true, version: version ?? undefined, enabled: true };
  }

  async getKeyBackupStatus(): Promise<KeyBackupStatus> {
    this.requireClient();
    const crypto = this.requireCrypto();
    const version = await crypto.getActiveSessionBackupVersion();
    return {
      exists: version !== null,
      version: version ?? undefined,
      enabled: version !== null,
    };
  }

  async restoreKeyBackup(_options?: { recoveryKey?: string }): Promise<{ importedKeys: number }> {
    this.requireClient();
    const crypto = this.requireCrypto();

    const result = await crypto.restoreKeyBackup();
    return { importedKeys: result?.imported ?? 0 };
  }

  async setupRecovery(options?: { passphrase?: string }): Promise<RecoveryKeyInfo> {
    const crypto = await this.ensureCrypto();

    const keyInfo = await crypto.createRecoveryKeyFromPassphrase(options?.passphrase);
    this.secretStorageKey = keyInfo.privateKey;

    await crypto.bootstrapSecretStorage({
      createSecretStorageKey: async () => keyInfo,
      setupNewSecretStorage: true,
      setupNewKeyBackup: true,
    });

    return { recoveryKey: keyInfo.encodedPrivateKey ?? '' };
  }

  async isRecoveryEnabled(): Promise<{ enabled: boolean }> {
    const crypto = await this.ensureCrypto();
    const ready = await crypto.isSecretStorageReady();
    return { enabled: ready };
  }

  async recoverAndSetup(options: { recoveryKey?: string; passphrase?: string }): Promise<void> {
    const crypto = await this.ensureCrypto();

    // Derive/decode the secret storage key
    if (options.recoveryKey) {
      this.secretStorageKey = decodeRecoveryKey(options.recoveryKey);
    } else if (options.passphrase) {
      // Store passphrase — the getSecretStorageKey callback will derive
      // the key using the server's stored PBKDF2 params (salt, iterations)
      this.recoveryPassphrase = options.passphrase;
      this.secretStorageKey = undefined; // Clear any stale raw key
    } else {
      throw new Error('Either recoveryKey or passphrase must be provided');
    }

    // Load the backup decryption key from secret storage into the Rust crypto store.
    // This triggers the getSecretStorageKey callback.
    try {
      await crypto.loadSessionBackupPrivateKeyFromSecretStorage();
    } catch (e) {
      // Clear stale key material so the next attempt starts fresh
      this.secretStorageKey = undefined;
      this.recoveryPassphrase = undefined;
      throw e;
    }

    // Now that the key is stored locally, activate backup in the running client
    await crypto.checkKeyBackupAndEnable();
  }

  async resetRecoveryKey(options?: { passphrase?: string }): Promise<RecoveryKeyInfo> {
    return this.setupRecovery(options);
  }

  async exportRoomKeys(options: { passphrase: string }): Promise<{ data: string }> {
    this.requireClient();
    const crypto = this.requireCrypto();
    const keys = await crypto.exportRoomKeysAsJson();
    // The exported JSON is not encrypted by default; for passphrase encryption
    // the caller should handle it, or we return the raw JSON
    void options.passphrase; // passphrase encryption is handled natively; on web we return raw
    return { data: keys };
  }

  async importRoomKeys(options: { data: string; passphrase: string }): Promise<{ importedKeys: number }> {
    this.requireClient();
    const crypto = this.requireCrypto();
    void options.passphrase; // passphrase decryption handled natively; on web we import raw
    await crypto.importRoomKeysAsJson(options.data);
    return { importedKeys: -1 }; // count not available from importRoomKeysAsJson
  }

  // ── Helpers ───────────────────────────────────────────

  private requireClient(): void {
    if (!this.client) {
      throw new Error('Not logged in. Call login() or loginWithToken() first.');
    }
  }

  private requireCrypto() {
    const crypto = this.client!.getCrypto();
    if (!crypto) {
      throw new Error('Crypto not initialized. Call initializeCrypto() first.');
    }
    return crypto;
  }

  private async ensureCrypto() {
    this.requireClient();
    if (!this.client!.getCrypto()) {
      await this.initializeCrypto();
    }
    return this.requireCrypto();
  }

  private persistSession(session: SessionInfo): void {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  }

  private serializeEvent(event: SdkMatrixEvent, fallbackRoomId?: string): MatrixEvent {
    const roomId = event.getRoomId() ?? fallbackRoomId ?? '';

    // Redacted events should be marked clearly
    if (event.isRedacted()) {
      return {
        eventId: event.getId() ?? '',
        roomId,
        senderId: event.getSender() ?? '',
        type: 'm.room.redaction',
        content: { body: 'Message deleted' },
        originServerTs: event.getTs(),
      };
    }

    const content = { ...(event.getContent() ?? {}) } as Record<string, unknown>;

    // Include aggregated reactions from the room's relations container
    const eventId = event.getId();
    if (eventId && roomId) {
      const room = this.client?.getRoom(roomId);
      if (room) {
        try {
          const relations = room.relations.getChildEventsForEvent(
            eventId,
            RelationType.Annotation,
            EventType.Reaction,
          );
          if (relations) {
            const sorted = relations.getSortedAnnotationsByKey();
            if (sorted && sorted.length > 0) {
              content.reactions = sorted.map(([key, events]) => ({
                key,
                count: events.size,
                senders: Array.from(events).map((e) => e.getSender()),
              }));
            }
          }
        } catch {
          // relations may not be available
        }
      }
    }

    // Determine delivery/read status
    let status: MatrixEvent['status'];
    const readBy: string[] = [];
    const myUserId = this.client?.getUserId();
    const sender = event.getSender();
    const room = eventId && roomId ? this.client?.getRoom(roomId) : undefined;

    if (sender === myUserId && eventId) {
      // Own message — check delivery status
      const evtStatus = event.status; // null = sent & echoed, 'sending', 'sent', etc.
      if (evtStatus === 'sending' || evtStatus === 'encrypting' || evtStatus === 'queued') {
        status = 'sending';
      } else {
        // Event is at least sent; check if anyone has read it
        if (room) {
          try {
            const members = room.getJoinedMembers();
            for (const member of members) {
              if (member.userId === myUserId) continue;
              if (room.hasUserReadEvent(member.userId, eventId)) {
                readBy.push(member.userId);
              }
            }
          } catch {
            // ignore errors
          }
        }
        status = readBy.length > 0 ? 'read' : 'sent';
      }
    } else if (eventId && room) {
      // Other's message — collect who has read it
      try {
        const members = room.getJoinedMembers();
        for (const member of members) {
          if (member.userId === sender) continue;
          if (room.hasUserReadEvent(member.userId, eventId)) {
            readBy.push(member.userId);
          }
        }
      } catch {
        // ignore
      }
    }

    // Include unsigned data (e.g. m.relations for edits, transaction_id for local echo)
    const unsignedData = event.getUnsigned?.();
    const unsigned = unsignedData && Object.keys(unsignedData).length > 0
      ? (unsignedData as Record<string, unknown>)
      : undefined;

    return {
      eventId: eventId ?? '',
      roomId,
      senderId: sender ?? '',
      type: event.getType(),
      content,
      originServerTs: event.getTs(),
      status,
      readBy: readBy.length > 0 ? readBy : undefined,
      unsigned,
    };
  }

  private serializeRoom(room: Room): RoomSummary {
    // Detect DM: check m.direct account data or guess from room state
    let isDirect = false;
    try {
      const directEvent = this.client?.getAccountData('m.direct' as any);
      if (directEvent) {
        const directContent = directEvent.getContent() as Record<string, string[]>;
        for (const roomIds of Object.values(directContent)) {
          if (roomIds.includes(room.roomId)) {
            isDirect = true;
            break;
          }
        }
      }
    } catch {
      // ignore
    }

    // Get avatar URL
    let avatarUrl: string | undefined;
    const avatarEvent = room.currentState.getStateEvents('m.room.avatar', '');
    if (avatarEvent) {
      const mxcUrl = avatarEvent.getContent()?.url as string | undefined;
      if (mxcUrl) {
        avatarUrl = mxcUrl;
      }
    }

    return {
      roomId: room.roomId,
      name: room.name,
      topic: room.currentState.getStateEvents('m.room.topic', '')?.getContent()?.topic ?? undefined,
      memberCount: room.getJoinedMemberCount(),
      isEncrypted: room.hasEncryptionStateEvent(),
      unreadCount: room.getUnreadNotificationCount() ?? 0,
      lastEventTs: room.getLastActiveTimestamp() || undefined,
      membership: room.getMyMembership() as RoomSummary['membership'],
      avatarUrl,
      isDirect,
    };
  }

  async searchUsers(options: {
    searchTerm: string;
    limit?: number;
  }): Promise<{ results: UserProfile[]; limited: boolean }> {
    this.requireClient();
    const resp = await this.client!.searchUserDirectory({
      term: options.searchTerm,
      limit: options.limit ?? 10,
    });
    return {
      results: resp.results.map((u: { user_id: string; display_name?: string; avatar_url?: string }) => ({
        userId: u.user_id,
        displayName: u.display_name,
        avatarUrl: u.avatar_url,
      })),
      limited: resp.limited,
    };
  }

  private mapSyncState(state: string | null): SyncState {
    switch (state) {
      case 'PREPARED':
      case 'SYNCING':
      case 'CATCHUP':
      case 'RECONNECTING':
        return 'SYNCING';
      case 'ERROR':
        return 'ERROR';
      case 'STOPPED':
        return 'STOPPED';
      default:
        return 'INITIAL';
    }
  }
}
