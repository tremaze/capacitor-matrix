import { Matrix } from '@tremaze/capacitor-matrix';

// ── State ──────────────────────────────────────────────

let selectedRoomId = null;
let currentUserId = null;

// ── Logging ────────────────────────────────────────────

const logEl = document.getElementById('log');

function log(msg, cls = '') {
  const line = document.createElement('div');
  if (cls) line.className = cls;
  line.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  logEl.appendChild(line);
  logEl.scrollTop = logEl.scrollHeight;
}

function logResult(label, data) {
  log(`${label}: ${JSON.stringify(data, null, 2)}`, 'success');
}

function logError(label, err) {
  log(`${label} ERROR: ${err.message || err}`, 'error');
}

window.clearLog = () => {
  logEl.innerHTML = '';
};

window.copyLog = async () => {
  const text = logEl.innerText;
  if (!text) return log('Nothing to copy', 'error');
  try {
    await navigator.clipboard.writeText(text);
    log('Log copied to clipboard', 'success');
  } catch {
    // Fallback for environments without clipboard API
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    log('Log copied to clipboard', 'success');
  }
};

// ── Mobile keyboard handling ──────────────────────────

if ('visualViewport' in window) {
  window.visualViewport.addEventListener('resize', () => {
    const composeBar = document.getElementById('composeBar');
    if (composeBar && document.activeElement?.closest('#composeBar')) {
      requestAnimationFrame(() => {
        composeBar.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      });
    }
  });
}

// ── Status ─────────────────────────────────────────────

function setStatus(text, state) {
  document.getElementById('statusText').textContent = text;
  const dot = document.getElementById('statusDot');
  dot.className = 'status-dot';
  if (state) dot.classList.add(state);
}

// ── Auth ───────────────────────────────────────────────

let tokenMode = false;

window.toggleAuthMode = () => {
  tokenMode = !tokenMode;
  document.getElementById('passwordAuth').classList.toggle('hidden', tokenMode);
  document.getElementById('tokenAuth').classList.toggle('hidden', !tokenMode);
};

window.doLogin = async () => {
  const homeserverUrl = document.getElementById('homeserverUrl').value.trim();
  const userId = document.getElementById('userId').value.trim();
  const password = document.getElementById('password').value;

  if (!homeserverUrl || !userId || !password) {
    return log('Fill in all login fields', 'error');
  }

  log('Logging in...');
  try {
    const session = await Matrix.login({ homeserverUrl, userId, password });
    currentUserId = session.userId;
    logResult('Login success', session);
    setStatus(`Logged in as ${session.userId}`, 'connected');
    await startSyncAndLoadRooms();
  } catch (e) {
    logError('Login', e);
  }
};

window.doLoginWithToken = async () => {
  const homeserverUrl = document.getElementById('homeserverUrl').value.trim();
  const userId = document.getElementById('userId').value.trim();
  const accessToken = document.getElementById('accessToken').value.trim();
  const deviceId = document.getElementById('deviceId').value.trim();

  if (!homeserverUrl || !userId || !accessToken || !deviceId) {
    return log('Fill in all token login fields', 'error');
  }

  log('Logging in with token...');
  try {
    const session = await Matrix.loginWithToken({
      homeserverUrl,
      accessToken,
      userId,
      deviceId,
    });
    currentUserId = session.userId;
    logResult('Token login success', session);
    setStatus(`Logged in as ${session.userId}`, 'connected');
    await startSyncAndLoadRooms();
  } catch (e) {
    logError('Token login', e);
  }
};

window.doLogout = async () => {
  log('Logging out...');
  try {
    await Matrix.logout();
    log('Logged out', 'success');
    setStatus('Not connected');
    selectedRoomId = null;
    currentUserId = null;
    document.getElementById('roomList').innerHTML = '';
    document.getElementById('roomDetail').classList.add('hidden');
  } catch (e) {
    logError('Logout', e);
  }
};

window.doClearAllData = async () => {
  if (!confirm('This will delete ALL Matrix data (session, crypto keys, messages). Continue?')) return;
  log('Wiping all data...');
  try {
    await Matrix.clearAllData();
    log('All data wiped', 'success');
    setStatus('Not connected');
    selectedRoomId = null;
    currentUserId = null;
    document.getElementById('roomList').innerHTML = '';
    document.getElementById('roomDetail').classList.add('hidden');
    localStorage.clear();
  } catch (e) {
    logError('clearAllData', e);
  }
};

window.doGetSession = async () => {
  try {
    const session = await Matrix.getSession();
    if (session && session.userId) {
      logResult('Session found', session);
      setStatus(`Session: ${session.userId}`, 'connected');
    } else {
      log('No stored session', 'event');
    }
  } catch (e) {
    logError('getSession', e);
  }
};

// ── Sync ───────────────────────────────────────────────

window.doStartSync = async () => {
  log('Starting sync...');
  try {
    await Matrix.startSync();
    log('Sync started', 'success');
  } catch (e) {
    logError('startSync', e);
  }
};

window.doStopSync = async () => {
  log('Stopping sync...');
  try {
    await Matrix.stopSync();
    log('Sync stopped', 'success');
    setStatus('Sync stopped');
  } catch (e) {
    logError('stopSync', e);
  }
};

window.doGetSyncState = async () => {
  try {
    const result = await Matrix.getSyncState();
    logResult('Sync state', result);
  } catch (e) {
    logError('getSyncState', e);
  }
};

// ── Auto Sync + Room Load ─────────────────────────────

let roomsLoaded = false;
let listenersRegistered = false;
let cryptoChecked = false;

function registerListeners() {
  if (listenersRegistered) return;
  listenersRegistered = true;

  Matrix.addListener('syncStateChange', async (data) => {
    log(`Sync state: ${data.state}`, 'event');
    switch (data.state) {
      case 'SYNCING':
        setStatus('Syncing', 'syncing');
        if (!roomsLoaded) {
          roomsLoaded = true;
          await window.doGetRooms();
        }
        // Check crypto status after first sync completes
        if (!cryptoChecked) {
          cryptoChecked = true;
          setTimeout(() => checkAndPromptCrypto(), 500);
        }
        break;
      case 'ERROR':
        setStatus(`Sync error: ${data.error || 'unknown'}`, 'error');
        log(`Sync error: ${data.error || 'unknown'}`, 'error');
        break;
      case 'STOPPED':
        setStatus('Sync stopped');
        break;
    }
  });

  Matrix.addListener('messageReceived', (data) => {
    const evt = data.event;
    log(`Event ${evt?.type} from ${evt?.senderId}: ${JSON.stringify(evt?.content)}`, 'event');
    if (evt && evt.roomId === selectedRoomId) {
      // Handle redactions
      if (evt.type === 'm.room.redaction') {
        const redactedId = evt.content?.redacts || evt.redacts;
        if (redactedId) {
          const el = document.querySelector(`[data-event-id="${redactedId}"]`);
          if (el) {
            el.innerHTML = '<em style="color:#999">Message deleted</em>';
            el.className = 'msg-system';
          }
        }
        return;
      }
      // Handle reactions
      if (evt.type === 'm.reaction') {
        const rel = evt.content?.['m.relates_to'];
        if (rel?.event_id && rel?.key) {
          addReactionChip(rel.event_id, rel.key);
        }
        return;
      }
      // Normal messages from others
      if (evt.senderId !== currentUserId) {
        renderMessage(evt);
        const msgList = document.getElementById('messageList');
        msgList.scrollTop = msgList.scrollHeight;
      }
    }
  });

  Matrix.addListener('roomUpdated', (data) => {
    log(`Room updated: ${data.roomId}`, 'event');
  });

  Matrix.addListener('typingChanged', (data) => {
    const indicator = document.getElementById('typingIndicator');
    if (data.roomId === selectedRoomId) {
      const others = (data.userIds || []).filter((id) => id !== currentUserId);
      indicator.textContent = others.length > 0 ? `${others.join(', ')} typing...` : '';
    }
  });
}

async function startSyncAndLoadRooms() {
  roomsLoaded = false;
  cryptoChecked = false;
  registerListeners();

  // Initialize crypto before starting sync
  try {
    await Matrix.initializeCrypto();
    log('Crypto initialized', 'success');
  } catch (e) {
    logError('initCrypto', e);
  }

  log('Starting sync...');
  try {
    await Matrix.startSync();
    log('Sync started', 'success');
  } catch (e) {
    logError('startSync', e);
  }
}

// ── Crypto Setup / Recovery Modal ─────────────────────

const cryptoModal = document.getElementById('cryptoModal');
const cryptoModalContent = document.getElementById('cryptoModalContent');

function showModal(html) {
  cryptoModalContent.innerHTML = html;
  cryptoModal.classList.add('active');
}

function hideModal() {
  cryptoModal.classList.remove('active');
  cryptoModalContent.innerHTML = '';
}

async function checkAndPromptCrypto() {
  try {
    const status = await Matrix.getEncryptionStatus();
    log(`Crypto check: secretStorage=${status.isSecretStorageReady}, crossSigning=${status.isCrossSigningReady}, keyBackup=${status.isKeyBackupEnabled}`, 'event');

    if (!status.isSecretStorageReady) {
      // Check if a backup already exists on the server
      const backupStatus = await Matrix.getKeyBackupStatus().catch(() => ({ exists: false }));
      if (backupStatus.exists) {
        // Backup exists server-side but this device isn't set up — need to recover
        showRecoverModal();
      } else {
        // No backup at all — prompt to create one
        showSetupRecoveryModal();
      }
    } else if (!status.isKeyBackupEnabled) {
      // Secret storage exists but key backup isn't enabled on this device —
      // need recovery key/passphrase to unlock
      showRecoverModal();
    } else {
      log('Encryption set up for this device', 'success');
    }
  } catch (e) {
    logError('cryptoCheck', e);
  }
}

function showSetupRecoveryModal() {
  showModal(`
    <h2>Set Up Encryption</h2>
    <p>Secure your messages with end-to-end encryption. You'll get a recovery key to restore access on other devices.</p>
    <div class="step">
      <label><span class="step-num">1</span> Passphrase (optional)</label>
      <input type="text" id="modalPassphrase" placeholder="Enter a passphrase or leave empty..." />
      <p style="font-size:11px;color:#999;margin:0">A passphrase makes it easier to remember. If left empty, only the recovery key can be used.</p>
    </div>
    <div class="btn-row">
      <button class="btn-secondary" onclick="hideModal()">Skip</button>
      <button class="btn-primary" id="modalSetupBtn" onclick="doModalSetupRecovery()">Set Up Encryption</button>
    </div>
  `);
}

window.doModalSetupRecovery = async () => {
  const passphrase = document.getElementById('modalPassphrase')?.value.trim() || undefined;
  const btn = document.getElementById('modalSetupBtn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Setting up...';

  try {
    // Setup recovery (creates secret storage + key backup)
    log('Setting up recovery...', 'event');
    const result = await Matrix.setupRecovery({ passphrase });
    log('Recovery setup complete', 'success');

    // Show recovery key to the user
    showRecoveryKeyModal(result.recoveryKey);
  } catch (e) {
    if (e.message?.includes('BACKUP_EXISTS')) {
      log('Existing backup found — prompting for recovery key', 'event');
      showRecoverModal();
    } else {
      logError('setupRecovery', e);
      btn.disabled = false;
      btn.textContent = 'Set Up Encryption';
    }
  }
};

function showRecoveryKeyModal(recoveryKey) {
  showModal(`
    <h2>Save Your Recovery Key</h2>
    <p>This key is the <strong>only way</strong> to restore your encrypted messages if you lose access. Save it somewhere safe!</p>
    <div class="recovery-key-display" id="recoveryKeyDisplay">${esc(recoveryKey)}</div>
    <div class="btn-row">
      <button class="btn-secondary" onclick="doCopyRecoveryKey()">Copy</button>
      <button class="btn-primary" onclick="doDownloadRecoveryKey()">Download</button>
      <button class="btn-primary" onclick="hideModal()">I've Saved It</button>
    </div>
  `);
}

window.doCopyRecoveryKey = () => {
  const key = document.getElementById('recoveryKeyDisplay')?.textContent;
  if (key) {
    navigator.clipboard.writeText(key).then(() => {
      log('Recovery key copied to clipboard', 'success');
    });
  }
};

window.doDownloadRecoveryKey = () => {
  const key = document.getElementById('recoveryKeyDisplay')?.textContent;
  if (key) {
    const blob = new Blob([`Matrix Recovery Key\n\n${key}\n\nStore this key safely. You'll need it to verify new devices.\n`], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'matrix-recovery-key.txt';
    a.click();
    URL.revokeObjectURL(a.href);
    log('Recovery key downloaded', 'success');
  }
};

function showRecoverModal() {
  showModal(`
    <h2>Verify This Device</h2>
    <p>This device isn't verified yet. Enter your recovery key or passphrase to unlock your encrypted messages.</p>
    <div class="step">
      <label><span class="step-num">1</span> Recovery Key or Passphrase</label>
      <input type="text" id="modalRecoveryInput" placeholder="Enter recovery key (EsXa K2g1...)  or passphrase..." />
    </div>
    <div class="btn-row">
      <button class="btn-secondary" onclick="hideModal()">Skip</button>
      <button class="btn-primary" id="modalRecoverBtn" onclick="doModalRecover()">Verify Device</button>
    </div>
  `);
}

window.doModalRecover = async () => {
  const input = document.getElementById('modalRecoveryInput')?.value.trim();
  if (!input) return;
  const btn = document.getElementById('modalRecoverBtn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Verifying...';

  try {
    // Detect if it's a recovery key (starts with "Es" and has spaces/groups) or a passphrase
    const isRecoveryKey = /^[A-Za-z0-9]{4}(\s+[A-Za-z0-9]{4})+$/.test(input);

    if (isRecoveryKey) {
      log('Recovering with recovery key...', 'event');
      await Matrix.recoverAndSetup({ recoveryKey: input });
    } else {
      log('Recovering with passphrase...', 'event');
      await Matrix.recoverAndSetup({ passphrase: input });
    }

    log('Device verified successfully', 'success');
    hideModal();

    // Re-check status
    const status = await Matrix.getEncryptionStatus();
    logResult('Encryption status after recovery', status);

    // Reload messages in the current room to retry decryption
    if (selectedRoomId) {
      try {
        await loadMessages(selectedRoomId);
      } catch (_) {}
    }
  } catch (e) {
    logError('recover', e);
    // Show error with reset option
    showRecoverErrorModal(e.message || String(e));
  }
};

function showRecoverErrorModal(errorMsg) {
  showModal(`
    <h2>Recovery Failed</h2>
    <p style="color:#d32f2f">${esc(errorMsg)}</p>
    <p>This can happen if the encryption keys were corrupted or changed. You can reset encryption to start fresh — this will create new keys but you may lose access to old encrypted messages.</p>
    <div class="btn-row">
      <button class="btn-secondary" onclick="showRecoverModal()">Try Again</button>
      <button class="btn-danger" id="modalResetBtn" onclick="doModalReset()">Reset Encryption</button>
    </div>
  `);
}

window.doModalReset = async () => {
  const btn = document.getElementById('modalResetBtn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Resetting...';

  try {
    // Setup fresh recovery (new secret storage + key backup)
    log('Resetting encryption — creating new secret storage...', 'event');
    const result = await Matrix.setupRecovery({});
    log('New recovery created', 'success');

    // Show the new recovery key
    showRecoveryKeyModal(result.recoveryKey);
  } catch (e) {
    logError('reset', e);
    btn.disabled = false;
    btn.textContent = 'Reset Encryption';
  }
};

// Make modal functions available globally
window.hideModal = hideModal;
window.showRecoverModal = showRecoverModal;

// ── Encryption ────────────────────────────────────────

window.doInitCrypto = async () => {
  log('Initializing crypto...');
  try {
    await Matrix.initializeCrypto();
    log('Crypto initialized', 'success');
  } catch (e) {
    logError('initCrypto', e);
  }
};

window.doGetEncryptionStatus = async () => {
  try {
    const status = await Matrix.getEncryptionStatus();
    logResult('Encryption status', status);
  } catch (e) {
    logError('getEncryptionStatus', e);
  }
};

window.doBootstrapCrossSigning = async () => {
  log('Bootstrapping cross-signing...');
  try {
    await Matrix.bootstrapCrossSigning();
    log('Cross-signing bootstrapped', 'success');
  } catch (e) {
    logError('bootstrapCrossSigning', e);
  }
};

window.doSetupKeyBackup = async () => {
  log('Setting up key backup...');
  try {
    const result = await Matrix.setupKeyBackup();
    logResult('Key backup setup', result);
  } catch (e) {
    logError('setupKeyBackup', e);
  }
};

window.doGetKeyBackupStatus = async () => {
  try {
    const result = await Matrix.getKeyBackupStatus();
    logResult('Key backup status', result);
  } catch (e) {
    logError('getKeyBackupStatus', e);
  }
};

window.doSetupRecovery = async () => {
  const passphrase = document.getElementById('passphraseInput').value.trim() || undefined;
  log(`Setting up recovery${passphrase ? ' with passphrase' : ''}...`);
  try {
    const result = await Matrix.setupRecovery({ passphrase });
    logResult('Recovery setup', result);
    log(`Recovery key: ${result.recoveryKey}`, 'success');
  } catch (e) {
    if (e.message?.includes('BACKUP_EXISTS')) {
      log('A backup already exists. Enter your recovery key below and use "Recover".', 'error');
    } else {
      logError('setupRecovery', e);
    }
  }
};

window.doIsRecoveryEnabled = async () => {
  try {
    const result = await Matrix.isRecoveryEnabled();
    logResult('Recovery enabled', result);
  } catch (e) {
    logError('isRecoveryEnabled', e);
  }
};

window.doRecoverAndSetup = async () => {
  const recoveryKey = document.getElementById('recoveryKeyInput').value.trim();
  if (!recoveryKey) return log('Enter a recovery key', 'error');

  log('Recovering...');
  try {
    await Matrix.recoverAndSetup({ recoveryKey });
    log('Recovery complete', 'success');
  } catch (e) {
    logError('recoverAndSetup', e);
  }
};

window.doResetRecoveryKey = async () => {
  log('Resetting recovery key...');
  try {
    const result = await Matrix.resetRecoveryKey();
    logResult('Recovery key reset', result);
    log(`New recovery key: ${result.recoveryKey}`, 'success');
  } catch (e) {
    logError('resetRecoveryKey', e);
  }
};

// ── Create Room ────────────────────────────────────────

window.doCreateRoom = async () => {
  const name = document.getElementById('createRoomName').value.trim();
  const topic = document.getElementById('createRoomTopic').value.trim();
  const inviteRaw = document.getElementById('createRoomInvite').value.trim();
  const isEncrypted = document.getElementById('createRoomEncrypted').checked;

  if (!name) return log('Enter a room name', 'error');

  const invite = inviteRaw ? inviteRaw.split(',').map((s) => s.trim()).filter(Boolean) : [];

  log(`Creating room "${name}"${isEncrypted ? ' (encrypted)' : ''}...`);
  try {
    const result = await Matrix.createRoom({ name, topic: topic || undefined, isEncrypted, invite });
    logResult('Room created', result);
    document.getElementById('createRoomName').value = '';
    document.getElementById('createRoomTopic').value = '';
    document.getElementById('createRoomInvite').value = '';
    document.getElementById('createRoomEncrypted').checked = false;
    await window.doGetRooms();
  } catch (e) {
    logError('createRoom', e);
  }
};

// ── Rooms ──────────────────────────────────────────────

window.doGetRooms = async () => {
  log('Fetching rooms...');
  try {
    const result = await Matrix.getRooms();
    const rooms = result.rooms || [];
    logResult(`Got ${rooms.length} rooms`, rooms.map((r) => r.name || r.roomId));
    renderRoomList(rooms);
  } catch (e) {
    logError('getRooms', e);
  }
};

function renderRoomList(rooms) {
  const list = document.getElementById('roomList');
  list.innerHTML = '';
  rooms.forEach((room) => {
    const li = document.createElement('li');
    li.dataset.roomId = room.roomId;
    if (room.roomId === selectedRoomId) li.classList.add('selected');
    li.innerHTML = `
      <div class="room-name">${room.name || '(unnamed)'}</div>
      <div class="room-meta">${room.roomId} | ${room.memberCount} members | ${room.unreadCount} unread${room.isEncrypted ? ' | \u{1F512}' : ''}</div>
    `;
    li.onclick = () => selectRoom(room);
    list.appendChild(li);
  });
}

function selectRoom(room) {
  selectedRoomId = room.roomId;
  document.getElementById('roomDetail').classList.remove('hidden');
  document.getElementById('roomDetailName').textContent = room.name || '(unnamed)';
  document.getElementById('roomDetailId').textContent = room.roomId;

  // Highlight selected room in list
  document.querySelectorAll('.room-list li').forEach((li, i) => {
    li.classList.toggle('selected', li.dataset.roomId === room.roomId);
  });

  // Show encryption badge
  const badge = document.getElementById('roomEncBadge');
  badge.classList.toggle('hidden', !room.isEncrypted);
  if (room.isEncrypted) badge.classList.add('encrypted');

  log(`Selected room: ${room.name} (${room.roomId})`);
  loadConversation();
}

window.doJoinRoom = async () => {
  const roomIdOrAlias = document.getElementById('joinRoomInput').value.trim();
  if (!roomIdOrAlias) return log('Enter a room ID or alias', 'error');

  log(`Joining ${roomIdOrAlias}...`);
  try {
    const result = await Matrix.joinRoom({ roomIdOrAlias });
    logResult('Joined room', result);
    window.doGetRooms();
  } catch (e) {
    logError('joinRoom', e);
  }
};

window.doLeaveRoom = async () => {
  if (!selectedRoomId) return;
  log(`Leaving ${selectedRoomId}...`);
  try {
    await Matrix.leaveRoom({ roomId: selectedRoomId });
    log('Left room', 'success');
    selectedRoomId = null;
    document.getElementById('roomDetail').classList.add('hidden');
    window.doGetRooms();
  } catch (e) {
    logError('leaveRoom', e);
  }
};

// ── Room Details ───────────────────────────────────────

window.doGetRoomMembers = async () => {
  if (!selectedRoomId) return;
  log(`Fetching members for ${selectedRoomId}...`);
  try {
    const result = await Matrix.getRoomMembers({ roomId: selectedRoomId });
    const members = result.members || [];
    logResult(`${members.length} members`, members);
  } catch (e) {
    logError('getRoomMembers', e);
  }
};

window.doGetRoomMessages = async () => {
  if (!selectedRoomId) return;
  log(`Fetching messages for ${selectedRoomId}...`);
  try {
    const result = await Matrix.getRoomMessages({
      roomId: selectedRoomId,
      limit: 50,
    });
    const events = result.events || [];
    logResult(`${events.length} messages`, events.map((e) => `${e.senderId}: ${e.content?.body || e.type}`));
  } catch (e) {
    logError('getRoomMessages', e);
  }
};

// ── Conversation View ─────────────────────────────────

async function loadConversation() {
  const msgList = document.getElementById('messageList');
  msgList.innerHTML = '<div class="msg-system">Loading messages...</div>';

  try {
    const result = await Matrix.getRoomMessages({
      roomId: selectedRoomId,
      limit: 50,
    });
    const events = result.events || [];
    msgList.innerHTML = '';
    if (events.length === 0) {
      msgList.innerHTML = '<div class="msg-system">No messages yet</div>';
      return;
    }
    events.forEach((evt) => renderMessage(evt));
    msgList.scrollTop = msgList.scrollHeight;
  } catch (e) {
    msgList.innerHTML = `<div class="msg-system">Error loading messages</div>`;
    logError('loadConversation', e);
  }
}

function renderMessage(evt) {
  const msgList = document.getElementById('messageList');
  const isMine = evt.senderId === currentUserId;

  // State events (member joins, room creation, etc.)
  if (evt.type !== 'm.room.message') {
    const sys = document.createElement('div');
    sys.className = 'msg-system';
    const label = evt.type === 'm.room.member' ? `${evt.senderId} ${evt.content?.membership || 'updated'}` :
                  evt.type === 'm.room.encrypted' ? `\u{1F512} Encrypted event` :
                  `${evt.type}`;
    sys.textContent = label;
    msgList.appendChild(sys);
    return;
  }

  const bubble = document.createElement('div');
  bubble.className = `msg-bubble ${isMine ? 'mine' : 'other'}`;
  if (evt.eventId) bubble.dataset.eventId = evt.eventId;

  const body = evt.content?.body || '';
  const msgtype = evt.content?.msgtype || 'm.text';
  const time = new Date(evt.originServerTs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  let html = '';
  if (!isMine) {
    html += `<div class="msg-sender">${esc(evt.senderId)}</div>`;
  }

  // Media rendering
  if (msgtype === 'm.image' && evt.content?.url) {
    html += `<div class="media-preview"><img src="" data-mxc="${esc(evt.content.url)}" alt="${esc(body)}" onclick="window.open(this.src)" /></div>`;
    if (body) html += `<div style="font-size:11px;color:#777">${esc(body)}</div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.audio' && evt.content?.url) {
    html += `<div class="media-preview"><audio controls src="" data-mxc="${esc(evt.content.url)}"></audio></div>`;
    if (body) html += `<div style="font-size:11px;color:#777">${esc(body)}</div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.video' && evt.content?.url) {
    html += `<div class="media-preview"><video controls src="" data-mxc="${esc(evt.content.url)}" style="max-width:250px"></video></div>`;
    if (body) html += `<div style="font-size:11px;color:#777">${esc(body)}</div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.file' && evt.content?.url) {
    html += `<div class="media-preview"><a href="#" data-mxc="${esc(evt.content.url)}" onclick="downloadMedia(this,event)">\u{1F4CE} ${esc(body || 'Download file')}</a></div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.emote') {
    html += `<em>* ${esc(evt.senderId)} ${esc(body)}</em>`;
  } else if (msgtype === 'm.notice') {
    html += `<span style="color:#888">${esc(body)}</span>`;
  } else {
    html += esc(body);
  }

  html += `<div class="msg-time">${time}</div>`;

  // Action buttons (react + delete)
  if (evt.eventId && !evt.eventId.startsWith('local-')) {
    html += `<div class="msg-actions">`;
    html += `<button onclick="doReact('${evt.eventId}','\u{1F44D}')">&#128077;</button>`;
    html += `<button onclick="doReact('${evt.eventId}','\u2764\uFE0F')">&#10084;&#65039;</button>`;
    html += `<button onclick="doReact('${evt.eventId}','\u{1F602}')">&#128514;</button>`;
    if (isMine) {
      html += `<button onclick="doRedact('${evt.eventId}')" title="Delete" style="color:#f44336">&#10005;</button>`;
    }
    html += `</div>`;
    html += `<div class="msg-reactions" id="reactions-${evt.eventId}"></div>`;
  }

  bubble.innerHTML = html;
  msgList.appendChild(bubble);
}

async function resolveMediaUrl(mxcUrl) {
  try {
    const { httpUrl } = await Matrix.getMediaUrl({ mxcUrl });
    document.querySelectorAll(`[data-mxc="${mxcUrl}"]`).forEach((el) => {
      if (el.tagName === 'IMG' || el.tagName === 'AUDIO' || el.tagName === 'VIDEO') {
        el.src = httpUrl;
      } else if (el.tagName === 'A') {
        el.href = httpUrl;
      }
    });
  } catch (e) {
    // silently fail for media URL resolution
  }
}

window.downloadMedia = (el, event) => {
  event.preventDefault();
  if (el.href && el.href !== '#') window.open(el.href);
};

function addReactionChip(eventId, key) {
  const container = document.getElementById(`reactions-${eventId}`);
  if (!container) return;
  const chip = document.createElement('span');
  chip.className = 'reaction-chip';
  chip.textContent = key;
  container.appendChild(chip);
}

function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

// ── Messaging ──────────────────────────────────────────

window.doSendMessage = async () => {
  if (!selectedRoomId) return;
  const input = document.getElementById('messageInput');
  const body = input.value.trim();
  if (!body) return;

  input.value = '';
  // Optimistically render
  renderMessage({
    eventId: 'local-' + Date.now(),
    roomId: selectedRoomId,
    senderId: currentUserId,
    type: 'm.room.message',
    content: { body, msgtype: 'm.text' },
    originServerTs: Date.now(),
  });
  const msgList = document.getElementById('messageList');
  msgList.scrollTop = msgList.scrollHeight;

  try {
    const result = await Matrix.sendMessage({
      roomId: selectedRoomId,
      body,
    });
    log(`Sent: ${result.eventId}`, 'success');
  } catch (e) {
    logError('sendMessage', e);
    // Show error inline
    const err = document.createElement('div');
    err.className = 'msg-system';
    err.textContent = `Failed to send: ${e.message || e}`;
    err.style.color = '#f44336';
    msgList.appendChild(err);
  }
};

// ── Redactions & Reactions ─────────────────────────────

window.doRedact = async (eventId) => {
  if (!selectedRoomId) return;
  try {
    await Matrix.redactEvent({ roomId: selectedRoomId, eventId });
    log(`Redacted ${eventId}`, 'success');
    const el = document.querySelector(`[data-event-id="${eventId}"]`);
    if (el) {
      el.innerHTML = '<em style="color:#999">Message deleted</em>';
      el.className = 'msg-system';
    }
  } catch (e) {
    logError('redactEvent', e);
  }
};

window.doReact = async (eventId, key) => {
  if (!selectedRoomId) return;
  try {
    await Matrix.sendReaction({ roomId: selectedRoomId, eventId, key });
    log(`Reacted ${key} to ${eventId}`, 'success');
    addReactionChip(eventId, key);
  } catch (e) {
    logError('sendReaction', e);
  }
};

// ── Room Management ───────────────────────────────────

window.doSetRoomName = async () => {
  if (!selectedRoomId) return;
  const name = document.getElementById('roomNameInput').value.trim();
  if (!name) return;
  try {
    await Matrix.setRoomName({ roomId: selectedRoomId, name });
    log(`Room name set to "${name}"`, 'success');
    document.getElementById('roomDetailName').textContent = name;
  } catch (e) {
    logError('setRoomName', e);
  }
};

window.doSetRoomTopic = async () => {
  if (!selectedRoomId) return;
  const topic = document.getElementById('roomTopicInput').value.trim();
  if (!topic) return;
  try {
    await Matrix.setRoomTopic({ roomId: selectedRoomId, topic });
    log(`Room topic set`, 'success');
  } catch (e) {
    logError('setRoomTopic', e);
  }
};

window.doInviteUser = async () => {
  if (!selectedRoomId) return;
  const userId = document.getElementById('userActionInput').value.trim();
  if (!userId) return;
  try {
    await Matrix.inviteUser({ roomId: selectedRoomId, userId });
    log(`Invited ${userId}`, 'success');
  } catch (e) {
    logError('inviteUser', e);
  }
};

window.doKickUser = async () => {
  if (!selectedRoomId) return;
  const userId = document.getElementById('userActionInput').value.trim();
  if (!userId) return;
  try {
    await Matrix.kickUser({ roomId: selectedRoomId, userId });
    log(`Kicked ${userId}`, 'success');
  } catch (e) {
    logError('kickUser', e);
  }
};

window.doBanUser = async () => {
  if (!selectedRoomId) return;
  const userId = document.getElementById('userActionInput').value.trim();
  if (!userId) return;
  try {
    await Matrix.banUser({ roomId: selectedRoomId, userId });
    log(`Banned ${userId}`, 'success');
  } catch (e) {
    logError('banUser', e);
  }
};

window.doUnbanUser = async () => {
  if (!selectedRoomId) return;
  const userId = document.getElementById('userActionInput').value.trim();
  if (!userId) return;
  try {
    await Matrix.unbanUser({ roomId: selectedRoomId, userId });
    log(`Unbanned ${userId}`, 'success');
  } catch (e) {
    logError('unbanUser', e);
  }
};

// ── Typing Indicator ──────────────────────────────────

let typingTimeout = null;

window.onComposeInput = () => {
  if (!selectedRoomId) return;
  if (!typingTimeout) {
    Matrix.sendTyping({ roomId: selectedRoomId, isTyping: true, timeout: 30000 }).catch(() => {});
  }
  clearTimeout(typingTimeout);
  typingTimeout = setTimeout(() => {
    typingTimeout = null;
    Matrix.sendTyping({ roomId: selectedRoomId, isTyping: false }).catch(() => {});
  }, 3000);
};

// ── File Upload ───────────────────────────────────────

window.doSendFile = async () => {
  if (!selectedRoomId) return;
  const fileInput = document.getElementById('fileInput');
  const file = fileInput.files?.[0];
  if (!file) return;

  const mime = file.type || 'application/octet-stream';
  let msgtype = 'm.file';
  if (mime.startsWith('image/')) msgtype = 'm.image';
  else if (mime.startsWith('audio/')) msgtype = 'm.audio';
  else if (mime.startsWith('video/')) msgtype = 'm.video';

  log(`Uploading ${file.name} (${msgtype})...`);
  try {
    const fileUri = URL.createObjectURL(file);
    const result = await Matrix.sendMessage({
      roomId: selectedRoomId,
      body: file.name,
      msgtype,
      fileUri,
      fileName: file.name,
      mimeType: mime,
      fileSize: file.size,
    });
    URL.revokeObjectURL(fileUri);
    log(`File sent: ${result.eventId}`, 'success');

    // Optimistically render
    renderMessage({
      eventId: result.eventId,
      roomId: selectedRoomId,
      senderId: currentUserId,
      type: 'm.room.message',
      content: { body: file.name, msgtype },
      originServerTs: Date.now(),
    });
    const msgList = document.getElementById('messageList');
    msgList.scrollTop = msgList.scrollHeight;
  } catch (e) {
    logError('sendFile', e);
  }
  fileInput.value = '';
};

// ── Init ───────────────────────────────────────────────

log('Matrix plugin test app loaded');

// Restore session on startup
(async () => {
  try {
    const session = await Matrix.getSession();
    if (session && session.userId && session.accessToken) {
      currentUserId = session.userId;
      log(`Restoring session: ${session.userId}`, 'success');
      document.getElementById('homeserverUrl').value = session.homeserverUrl || '';
      document.getElementById('userId').value = session.userId || '';
      await Matrix.loginWithToken({
        homeserverUrl: session.homeserverUrl,
        accessToken: session.accessToken,
        userId: session.userId,
        deviceId: session.deviceId,
      });
      setStatus(`Logged in as ${session.userId}`, 'connected');
      await startSyncAndLoadRooms();
    }
  } catch (e) {
    logError('Session restore', e);
  }
})();
