import { WebPlugin } from '@capacitor/core';
import {
  createClient,
  ClientEvent,
  RoomEvent,
  Direction,
  MsgType,
} from 'matrix-js-sdk';
import type { MatrixClient, Room, MatrixEvent as SdkMatrixEvent } from 'matrix-js-sdk';

import type {
  MatrixPlugin,
  LoginOptions,
  LoginWithTokenOptions,
  SessionInfo,
  SendMessageOptions,
  MatrixEvent,
  RoomSummary,
  RoomMember,
  SyncState,
} from './definitions';

const SESSION_KEY = 'matrix_session';

export class MatrixWeb extends WebPlugin implements MatrixPlugin {
  private client?: MatrixClient;

  // ── Auth ──────────────────────────────────────────────

  async login(options: LoginOptions): Promise<SessionInfo> {
    const tmpClient = createClient({ baseUrl: options.homeserverUrl });
    const res = await tmpClient.loginWithPassword(options.userId, options.password);

    this.client = createClient({
      baseUrl: options.homeserverUrl,
      accessToken: res.access_token,
      userId: res.user_id,
      deviceId: res.device_id,
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
      if (event.getType() === 'm.room.message') {
        this.notifyListeners('messageReceived', {
          event: this.serializeEvent(event, room?.roomId),
        });
      }
    });

    this.client!.on(RoomEvent.Name, (room: Room) => {
      this.notifyListeners('roomUpdated', {
        roomId: room.roomId,
        summary: this.serializeRoom(room),
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

  // ── Messaging ─────────────────────────────────────────

  async sendMessage(options: SendMessageOptions): Promise<{ eventId: string }> {
    this.requireClient();
    const msgtypeMap = {
      'm.text': MsgType.Text,
      'm.notice': MsgType.Notice,
      'm.emote': MsgType.Emote,
    } as const;
    const msgtype = msgtypeMap[options.msgtype ?? 'm.text'];
    const res = await this.client!.sendMessage(options.roomId, {
      msgtype,
      body: options.body,
    });
    return { eventId: res.event_id };
  }

  async getRoomMessages(
    options: { roomId: string; limit?: number; from?: string },
  ): Promise<{ events: MatrixEvent[]; nextBatch?: string }> {
    this.requireClient();

    const room = this.client!.getRoom(options.roomId);
    const fromToken = options.from ?? room?.getLiveTimeline().getPaginationToken(Direction.Backward) ?? null;

    const res = await this.client!.createMessagesRequest(
      options.roomId,
      fromToken,
      options.limit ?? 20,
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
    await this.client!.setRoomReadMarkersHttpRequest(
      options.roomId,
      options.eventId,
      options.eventId,
    );
  }

  // ── Helpers ───────────────────────────────────────────

  private requireClient(): void {
    if (!this.client) {
      throw new Error('Not logged in. Call login() or loginWithToken() first.');
    }
  }

  private persistSession(session: SessionInfo): void {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  }

  private serializeEvent(event: SdkMatrixEvent, fallbackRoomId?: string): MatrixEvent {
    return {
      eventId: event.getId() ?? '',
      roomId: event.getRoomId() ?? fallbackRoomId ?? '',
      senderId: event.getSender() ?? '',
      type: event.getType(),
      content: (event.getContent() ?? {}) as Record<string, unknown>,
      originServerTs: event.getTs(),
    };
  }

  private serializeRoom(room: Room): RoomSummary {
    return {
      roomId: room.roomId,
      name: room.name,
      topic: room.currentState.getStateEvents('m.room.topic', '')?.getContent()?.topic ?? undefined,
      memberCount: room.getJoinedMemberCount(),
      isEncrypted: room.hasEncryptionStateEvent(),
      unreadCount: room.getUnreadNotificationCount() ?? 0,
      lastEventTs: room.getLastActiveTimestamp() || undefined,
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
