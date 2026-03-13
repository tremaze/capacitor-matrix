import { WebPlugin } from '@capacitor/core';

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

export class MatrixWeb extends WebPlugin implements MatrixPlugin {
  async login(_options: LoginOptions): Promise<SessionInfo> {
    throw this.unimplemented('login not implemented');
  }

  async loginWithToken(_options: LoginWithTokenOptions): Promise<SessionInfo> {
    throw this.unimplemented('loginWithToken not implemented');
  }

  async logout(): Promise<void> {
    throw this.unimplemented('logout not implemented');
  }

  async getSession(): Promise<SessionInfo | null> {
    throw this.unimplemented('getSession not implemented');
  }

  async startSync(): Promise<void> {
    throw this.unimplemented('startSync not implemented');
  }

  async stopSync(): Promise<void> {
    throw this.unimplemented('stopSync not implemented');
  }

  async getSyncState(): Promise<{ state: SyncState }> {
    throw this.unimplemented('getSyncState not implemented');
  }

  async getRooms(): Promise<{ rooms: RoomSummary[] }> {
    throw this.unimplemented('getRooms not implemented');
  }

  async getRoomMembers(
    _options: { roomId: string },
  ): Promise<{ members: RoomMember[] }> {
    throw this.unimplemented('getRoomMembers not implemented');
  }

  async joinRoom(
    _options: { roomIdOrAlias: string },
  ): Promise<{ roomId: string }> {
    throw this.unimplemented('joinRoom not implemented');
  }

  async leaveRoom(_options: { roomId: string }): Promise<void> {
    throw this.unimplemented('leaveRoom not implemented');
  }

  async sendMessage(
    _options: SendMessageOptions,
  ): Promise<{ eventId: string }> {
    throw this.unimplemented('sendMessage not implemented');
  }

  async getRoomMessages(
    _options: { roomId: string; limit?: number; from?: string },
  ): Promise<{ events: MatrixEvent[]; nextBatch?: string }> {
    throw this.unimplemented('getRoomMessages not implemented');
  }

  async markRoomAsRead(
    _options: { roomId: string; eventId: string },
  ): Promise<void> {
    throw this.unimplemented('markRoomAsRead not implemented');
  }
}
