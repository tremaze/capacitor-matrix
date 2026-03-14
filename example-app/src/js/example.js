import { Matrix } from '@tremaze/capacitor-matrix';

// ── State ──────────────────────────────────────────────

let selectedRoomId = null;
let currentUserId = null;
let currentRooms = [];
let _refreshRoomTimer = null;

function refreshRoomListDebounced() {
  if (_refreshRoomTimer) clearTimeout(_refreshRoomTimer);
  _refreshRoomTimer = setTimeout(() => {
    _refreshRoomTimer = null;
    window.doGetRooms();
  }, 500);
}

// ── Logging ────────────────────────────────────────────

const logDesktop = document.getElementById('debugLog');
const logMobile = document.getElementById('debugLogMobile');

function log(msg, cls = '') {
  const line = document.createElement('div');
  if (cls) line.className = cls;
  line.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  // Write to both desktop and mobile log
  logDesktop.appendChild(line.cloneNode(true));
  logMobile.appendChild(line);
  logDesktop.scrollTop = logDesktop.scrollHeight;
  logMobile.scrollTop = logMobile.scrollHeight;
}

function logResult(label, data) {
  log(`${label}: ${JSON.stringify(data, null, 2)}`, 'success');
}

function logError(label, err) {
  log(`${label} ERROR: ${err.message || err}`, 'error');
}

window.clearLog = () => {
  logDesktop.innerHTML = '';
  logMobile.innerHTML = '';
};

window.copyLog = async () => {
  const text = logDesktop.innerText;
  if (!text) return log('Nothing to copy', 'error');
  try {
    await navigator.clipboard.writeText(text);
    log('Log copied to clipboard', 'success');
  } catch {
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
    const composeBar = document.querySelector('.compose-bar');
    if (composeBar && document.activeElement?.closest('.compose-bar')) {
      requestAnimationFrame(() => {
        composeBar.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      });
    }
  });
}

// ── Status ─────────────────────────────────────────────

let statusText = 'Not connected';

function setStatus(text, state) {
  statusText = text;
  const dot = document.getElementById('statusDot');
  dot.className = 'status-indicator';
  if (state) dot.classList.add(state);
}

// ── Auth ───────────────────────────────────────────────

let tokenMode = false;

window.toggleAuthMode = () => {
  tokenMode = !tokenMode;
  document.getElementById('passwordAuth').classList.toggle('hidden', tokenMode);
  document.getElementById('tokenAuth').classList.toggle('hidden', !tokenMode);
  document.getElementById('authToggleBtn').textContent = tokenMode
    ? 'Use password instead'
    : 'Use access token instead';
};

function showApp() {
  document.getElementById('loginScreen').classList.add('hidden');
  document.getElementById('appShell').classList.add('active');
  // Update user display
  const initials = currentUserId ? currentUserId.replace('@', '').substring(0, 2).toUpperCase() : '??';
  document.getElementById('userAvatar').textContent = initials;
  document.getElementById('userName').textContent = currentUserId || 'Unknown';
  // Hide mobile debug button on desktop, desktop on mobile
  updateDebugBtnVisibility();
}

function updateDebugBtnVisibility() {
  const isMobile = window.innerWidth <= 768;
  document.getElementById('debugMobileBtn').style.display = isMobile ? '' : 'none';
  document.getElementById('debugDesktopBtn').style.display = isMobile ? 'none' : '';
}
window.addEventListener('resize', updateDebugBtnVisibility);

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
    showApp();
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
    showApp();
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
    currentRooms = [];
    document.getElementById('roomList').innerHTML = '';
    document.getElementById('loginScreen').classList.remove('hidden');
    document.getElementById('appShell').classList.remove('active');
    closeSettingsDrawer();
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
    currentRooms = [];
    document.getElementById('roomList').innerHTML = '';
    document.getElementById('loginScreen').classList.remove('hidden');
    document.getElementById('appShell').classList.remove('active');
    localStorage.clear();
    closeSettingsDrawer();
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
        // Always refresh room list on sync to pick up new rooms/changes
        refreshRoomListDebounced();
        if (!roomsLoaded) {
          roomsLoaded = true;
        }
        if (!cryptoChecked) {
          cryptoChecked = true;
          setTimeout(() => checkAndPromptCrypto(), 500);
        }
        break;
      case 'ERROR':
        setStatus(`Sync error`, 'error');
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
      if (evt.type === 'm.room.redaction') {
        const redactedId = evt.content?.redacts || evt.redacts;
        if (redactedId) {
          const el = document.querySelector(`[data-event-id="${redactedId}"]`);
          if (el) {
            el.innerHTML = '<em style="color:var(--text-muted)">Message deleted</em>';
            el.className = 'msg-system';
          }
        }
        return;
      }
      if (evt.type === 'm.reaction') {
        const rel = evt.content?.['m.relates_to'];
        log(`Reaction rel: ${JSON.stringify(rel)}`, 'event');
        // Skip own reactions — already added optimistically by doReact
        if (evt.senderId === currentUserId) return;
        if (rel?.event_id && rel?.key) {
          addReactionChip(rel.event_id, rel.key);
        }
        return;
      }
      // If event already exists, update reactions (Android sends Set diffs for reaction changes)
      if (evt.eventId && document.querySelector(`[data-event-id="${evt.eventId}"]`)) {
        const aggReactions = evt.content?.reactions;
        if (Array.isArray(aggReactions)) {
          const container = document.getElementById(`reactions-${evt.eventId}`);
          if (container) {
            container.innerHTML = '';
            for (const r of aggReactions) {
              for (let i = 0; i < (r.count || 1); i++) {
                addReactionChip(evt.eventId, r.key);
              }
            }
          }
        }
        return;
      }
      renderMessage(evt);
      const msgList = document.getElementById('messageList');
      msgList.scrollTop = msgList.scrollHeight;
    }
    // Refresh room list to update previews/unread counts
    refreshRoomListDebounced();
  });

  Matrix.addListener('roomUpdated', (data) => {
    log(`Room updated: ${data.roomId}`, 'event');
    refreshRoomListDebounced();
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
      const backupStatus = await Matrix.getKeyBackupStatus().catch(() => ({ exists: false }));
      if (backupStatus.exists) {
        showRecoverModal();
      } else {
        showSetupRecoveryModal();
      }
    } else if (!status.isKeyBackupEnabled) {
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
      <p style="font-size:12px;color:var(--text-muted);margin:0">A passphrase makes it easier to remember.</p>
    </div>
    <div class="btn-row">
      <button class="modal-btn modal-btn-secondary" onclick="hideModal()">Skip</button>
      <button class="modal-btn modal-btn-primary" id="modalSetupBtn" onclick="doModalSetupRecovery()">Set Up Encryption</button>
    </div>
  `);
}

window.doModalSetupRecovery = async () => {
  const passphrase = document.getElementById('modalPassphrase')?.value.trim() || undefined;
  const btn = document.getElementById('modalSetupBtn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Setting up...';

  try {
    log('Setting up recovery...', 'event');
    const result = await Matrix.setupRecovery({ passphrase });
    log('Recovery setup complete', 'success');
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
      <button class="modal-btn modal-btn-secondary" onclick="doCopyRecoveryKey()">Copy</button>
      <button class="modal-btn modal-btn-secondary" onclick="doDownloadRecoveryKey()">Download</button>
      <button class="modal-btn modal-btn-primary" onclick="hideModal()">I've Saved It</button>
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
    const blob = new Blob([`Matrix Recovery Key\n\n${key}\n\nStore this key safely.\n`], { type: 'text/plain' });
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
      <input type="text" id="modalRecoveryInput" placeholder="Enter recovery key or passphrase..." />
    </div>
    <div class="btn-row">
      <button class="modal-btn modal-btn-secondary" onclick="hideModal()">Skip</button>
      <button class="modal-btn modal-btn-primary" id="modalRecoverBtn" onclick="doModalRecover()">Verify Device</button>
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

    const status = await Matrix.getEncryptionStatus();
    logResult('Encryption status after recovery', status);

    if (selectedRoomId) {
      try { await loadMessages(selectedRoomId); } catch (_) {}
    }
  } catch (e) {
    logError('recover', e);
    showRecoverErrorModal(e.message || String(e));
  }
};

function showRecoverErrorModal(errorMsg) {
  showModal(`
    <h2>Recovery Failed</h2>
    <p style="color:var(--red)">${esc(errorMsg)}</p>
    <p>You can try again or reset encryption to start fresh (you may lose access to old messages).</p>
    <div class="btn-row">
      <button class="modal-btn modal-btn-secondary" onclick="showRecoverModal()">Try Again</button>
      <button class="modal-btn modal-btn-danger" id="modalResetBtn" onclick="doModalReset()">Reset Encryption</button>
    </div>
  `);
}

window.doModalReset = async () => {
  const btn = document.getElementById('modalResetBtn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Resetting...';

  try {
    log('Resetting encryption...', 'event');
    const result = await Matrix.setupRecovery({});
    log('New recovery created', 'success');
    showRecoveryKeyModal(result.recoveryKey);
  } catch (e) {
    logError('reset', e);
    btn.disabled = false;
    btn.textContent = 'Reset Encryption';
  }
};

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
  const passphrase = (document.getElementById('passphraseInput')?.value || document.getElementById('passphraseInputM')?.value || '').trim() || undefined;
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
  const recoveryKey = (document.getElementById('recoveryKeyInput')?.value || document.getElementById('recoveryKeyInputM')?.value || '').trim();
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

let selectedInviteUsers = [];
let userSearchTimeout = null;

window.showCreateRoomModal = () => {
  selectedInviteUsers = [];
  renderSelectedUsers();
  document.getElementById('createRoomModal').classList.add('active');
};

window.hideCreateRoomModal = () => {
  document.getElementById('createRoomModal').classList.remove('active');
  document.getElementById('userSearchResults').style.display = 'none';
  document.getElementById('userSearchInput').value = '';
  selectedInviteUsers = [];
  renderSelectedUsers();
};

window.onUserSearchInput = () => {
  clearTimeout(userSearchTimeout);
  const term = document.getElementById('userSearchInput').value.trim();
  const resultsEl = document.getElementById('userSearchResults');
  if (term.length < 2) {
    resultsEl.style.display = 'none';
    return;
  }
  userSearchTimeout = setTimeout(async () => {
    try {
      const { results } = await Matrix.searchUsers({ searchTerm: term, limit: 8 });
      // Filter out already-selected users and current user
      const filtered = results.filter(
        (u) => u.userId !== currentUserId && !selectedInviteUsers.some((s) => s.userId === u.userId),
      );
      if (filtered.length === 0) {
        resultsEl.style.display = 'none';
        return;
      }
      resultsEl.innerHTML = '';
      filtered.forEach((u) => {
        const initials = (u.displayName || u.userId).replace('@', '').substring(0, 2).toUpperCase();
        const color = roomColor(u.userId);
        const item = document.createElement('div');
        item.className = 'user-search-item';
        item.innerHTML = `
          <div class="user-search-avatar" style="background:${color}">${initials}</div>
          <div class="user-search-info">
            <div class="user-search-name">${esc(u.displayName || u.userId)}</div>
            <div class="user-search-id">${esc(u.userId)}</div>
          </div>
        `;
        item.onclick = () => {
          selectedInviteUsers.push(u);
          renderSelectedUsers();
          document.getElementById('userSearchInput').value = '';
          resultsEl.style.display = 'none';
        };
        resultsEl.appendChild(item);
      });
      resultsEl.style.display = 'block';
    } catch (e) {
      // Silently fail — user can still type IDs manually
    }
  }, 300);
};

function renderSelectedUsers() {
  const container = document.getElementById('selectedUsers');
  container.innerHTML = '';
  selectedInviteUsers.forEach((u, i) => {
    const chip = document.createElement('span');
    chip.className = 'user-chip';
    chip.innerHTML = `${esc(u.displayName || u.userId)} <button onclick="removeInviteUser(${i})">&times;</button>`;
    container.appendChild(chip);
  });
}

window.removeInviteUser = (index) => {
  selectedInviteUsers.splice(index, 1);
  renderSelectedUsers();
};

window.doCreateRoomFromModal = async () => {
  const name = document.getElementById('createRoomNameModal').value.trim();
  const topic = document.getElementById('createRoomTopicModal').value.trim();
  const isEncrypted = document.getElementById('createRoomEncryptedModal').checked;

  if (!name) return log('Enter a room name', 'error');

  // Combine selected users + any manually typed user in the search field
  const invite = selectedInviteUsers.map((u) => u.userId);
  const manualInput = document.getElementById('userSearchInput').value.trim();
  if (manualInput && manualInput.startsWith('@')) {
    invite.push(manualInput);
  }

  log(`Creating room "${name}"${invite.length ? ` with ${invite.length} invites` : ''}...`);
  try {
    const result = await Matrix.createRoom({ name, topic: topic || undefined, isEncrypted, invite });
    logResult('Room created', result);
    hideCreateRoomModal();
    document.getElementById('createRoomNameModal').value = '';
    document.getElementById('createRoomTopicModal').value = '';
    document.getElementById('createRoomEncryptedModal').checked = false;
    await window.doGetRooms();
  } catch (e) {
    logError('createRoom', e);
  }
};

window.doCreateRoom = async () => {
  const name = (document.getElementById('createRoomName')?.value || document.getElementById('createRoomNameM')?.value || '').trim();
  const topic = (document.getElementById('createRoomTopic')?.value || document.getElementById('createRoomTopicM')?.value || '').trim();
  const inviteRaw = (document.getElementById('createRoomInvite')?.value || document.getElementById('createRoomInviteM')?.value || '').trim();
  const isEncrypted = document.getElementById('createRoomEncrypted')?.checked || document.getElementById('createRoomEncryptedM')?.checked || false;

  if (!name) return log('Enter a room name', 'error');
  const invite = inviteRaw ? inviteRaw.split(',').map((s) => s.trim()).filter(Boolean) : [];

  log(`Creating room "${name}"...`);
  try {
    const result = await Matrix.createRoom({ name, topic: topic || undefined, isEncrypted, invite });
    logResult('Room created', result);
    await window.doGetRooms();
  } catch (e) {
    logError('createRoom', e);
  }
};

// ── Rooms ──────────────────────────────────────────────

const ROOM_COLORS = [
  '#6366f1', '#ec4899', '#f59e0b', '#10b981', '#8b5cf6',
  '#ef4444', '#06b6d4', '#f97316', '#14b8a6', '#a855f7',
];

function roomColor(roomId) {
  let hash = 0;
  for (let i = 0; i < roomId.length; i++) hash = roomId.charCodeAt(i) + ((hash << 5) - hash);
  return ROOM_COLORS[Math.abs(hash) % ROOM_COLORS.length];
}

function roomInitials(name) {
  if (!name) return '?';
  const words = name.trim().split(/\s+/);
  if (words.length >= 2) return (words[0][0] + words[1][0]).toUpperCase();
  return name.substring(0, 2).toUpperCase();
}

window.doGetRooms = async () => {
  log('Fetching rooms...');
  try {
    const result = await Matrix.getRooms();
    const rooms = result.rooms || [];
    currentRooms = rooms;
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
    li.className = `room-item${room.roomId === selectedRoomId ? ' active' : ''}`;
    li.dataset.roomId = room.roomId;

    const color = roomColor(room.roomId);
    const initials = roomInitials(room.name);
    const encIcon = room.isEncrypted ? '<span class="room-encrypted-icon">&#128274;</span>' : '';

    li.innerHTML = `
      <div class="room-avatar" style="background:${color}">${initials}</div>
      <div class="room-item-info">
        <div class="room-item-name">${esc(room.name || '(unnamed)')}${encIcon}</div>
        <div class="room-item-preview">${room.memberCount} member${room.memberCount !== 1 ? 's' : ''}</div>
      </div>
      ${room.unreadCount > 0 ? `<div class="room-item-meta"><div class="room-unread-badge">${room.unreadCount}</div></div>` : ''}
    `;
    li.onclick = () => selectRoom(room);
    list.appendChild(li);
  });
}

function selectRoom(room) {
  selectedRoomId = room.roomId;

  // Update room list highlight
  document.querySelectorAll('.room-item').forEach((li) => {
    li.classList.toggle('active', li.dataset.roomId === room.roomId);
  });

  // Show chat view
  document.getElementById('chatEmpty').classList.add('hidden');
  const chatView = document.getElementById('chatView');
  chatView.classList.remove('hidden');

  // Update header
  document.getElementById('chatRoomName').textContent = room.name || '(unnamed)';
  const detail = [];
  if (room.memberCount) detail.push(`${room.memberCount} members`);
  if (room.isEncrypted) detail.push('Encrypted');
  document.getElementById('chatRoomDetail').textContent = detail.join(' · ');

  // Mobile: slide to chat
  document.getElementById('sidebar').classList.add('room-open');
  document.getElementById('chatArea').classList.add('room-open');

  log(`Selected room: ${room.name} (${room.roomId})`);
  loadConversation();
}

window.goBackToRooms = () => {
  document.getElementById('sidebar').classList.remove('room-open');
  document.getElementById('chatArea').classList.remove('room-open');
};

window.doJoinRoom = async () => {
  const roomIdOrAlias = (document.getElementById('joinRoomInput')?.value || document.getElementById('joinRoomInputM')?.value || '').trim();
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
    document.getElementById('chatView').classList.add('hidden');
    document.getElementById('chatEmpty').classList.remove('hidden');
    closeRoomDrawer();
    goBackToRooms();
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

// ── Room Drawer ───────────────────────────────────────

window.openRoomDrawer = () => {
  document.getElementById('roomDrawerOverlay').classList.add('active');
  document.getElementById('roomDrawer').classList.add('active');
};

window.closeRoomDrawer = () => {
  document.getElementById('roomDrawerOverlay').classList.remove('active');
  document.getElementById('roomDrawer').classList.remove('active');
};

window.showSettingsDrawer = () => {
  document.getElementById('settingsOverlay').classList.add('active');
  document.getElementById('settingsDrawer').classList.add('active');
};

window.closeSettingsDrawer = () => {
  document.getElementById('settingsOverlay').classList.remove('active');
  document.getElementById('settingsDrawer').classList.remove('active');
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
      msgList.innerHTML = '<div class="msg-system">No messages yet. Say hello!</div>';
      return;
    }
    // Separate reactions from other events
    const reactions = [];
    const displayEvents = [];
    for (const evt of events) {
      if (evt.type === 'm.reaction') {
        reactions.push(evt);
      } else {
        displayEvents.push(evt);
      }
    }
    displayEvents.forEach((evt) => renderMessage(evt));
    // Apply reactions from web (separate m.reaction events)
    for (const r of reactions) {
      const rel = r.content?.['m.relates_to'];
      if (rel?.event_id && rel?.key) {
        addReactionChip(rel.event_id, rel.key);
      }
    }
    // Apply reactions from Android (aggregated in content.reactions)
    for (const evt of displayEvents) {
      const aggReactions = evt.content?.reactions;
      if (Array.isArray(aggReactions) && evt.eventId) {
        for (const r of aggReactions) {
          for (let i = 0; i < (r.count || 1); i++) {
            addReactionChip(evt.eventId, r.key);
          }
        }
      }
    }
    msgList.scrollTop = msgList.scrollHeight;
  } catch (e) {
    msgList.innerHTML = '<div class="msg-system">Error loading messages</div>';
    logError('loadConversation', e);
  }
}

async function loadMessages(roomId) {
  if (roomId !== selectedRoomId) return;
  await loadConversation();
}

function renderMessage(evt) {
  const msgList = document.getElementById('messageList');
  const isMine = evt.senderId === currentUserId;

  // State events
  if (evt.type !== 'm.room.message') {
    const sys = document.createElement('div');
    sys.className = 'msg-system';
    const label = evt.type === 'm.room.member' ? `${evt.senderId} ${evt.content?.membership || 'updated'}` :
                  evt.type === 'm.room.encrypted' ? 'Encrypted event' :
                  `${evt.type}`;
    sys.textContent = label;
    msgList.appendChild(sys);
    return;
  }

  const group = document.createElement('div');
  group.className = `msg-group ${isMine ? 'mine' : 'other'}`;
  if (evt.eventId) group.dataset.eventId = evt.eventId;

  const body = evt.content?.body || '';
  const msgtype = evt.content?.msgtype || 'm.text';
  const time = new Date(evt.originServerTs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const senderInitials = (evt.senderId || '??').replace('@', '').substring(0, 2).toUpperCase();
  const color = roomColor(evt.senderId || '');

  let bubbleHtml = '';

  // Media rendering
  if (msgtype === 'm.image' && evt.content?.url) {
    bubbleHtml += `<div class="media-preview"><img src="" data-mxc="${esc(evt.content.url)}" alt="${esc(body)}" onclick="window.open(this.src)" /></div>`;
    if (body) bubbleHtml += `<div style="font-size:12px;opacity:0.7;margin-top:4px">${esc(body)}</div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.audio' && evt.content?.url) {
    bubbleHtml += `<div class="media-preview"><audio controls src="" data-mxc="${esc(evt.content.url)}"></audio></div>`;
    if (body) bubbleHtml += `<div style="font-size:12px;opacity:0.7;margin-top:4px">${esc(body)}</div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.video' && evt.content?.url) {
    bubbleHtml += `<div class="media-preview"><video controls src="" data-mxc="${esc(evt.content.url)}" style="max-width:280px"></video></div>`;
    if (body) bubbleHtml += `<div style="font-size:12px;opacity:0.7;margin-top:4px">${esc(body)}</div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.file' && evt.content?.url) {
    bubbleHtml += `<div class="media-preview"><a href="#" data-mxc="${esc(evt.content.url)}" onclick="downloadMedia(this,event)">&#128206; ${esc(body || 'Download file')}</a></div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.emote') {
    bubbleHtml += `<em>* ${esc(evt.senderId)} ${esc(body)}</em>`;
  } else if (msgtype === 'm.notice') {
    bubbleHtml += `<span style="opacity:0.7">${esc(body)}</span>`;
  } else {
    bubbleHtml += esc(body);
  }

  bubbleHtml += `<div class="msg-time">${time}</div>`;

  // Actions
  let actionsHtml = '';
  if (evt.eventId && !evt.eventId.startsWith('local-')) {
    actionsHtml += `<div class="msg-actions-bar">`;
    actionsHtml += `<button class="msg-action-btn" onclick="doReact('${evt.eventId}','\\u{1F44D}')">&#128077;</button>`;
    actionsHtml += `<button class="msg-action-btn" onclick="doReact('${evt.eventId}','\\u2764\\uFE0F')">&#10084;&#65039;</button>`;
    actionsHtml += `<button class="msg-action-btn" onclick="doReact('${evt.eventId}','\\u{1F602}')">&#128514;</button>`;
    if (isMine) {
      actionsHtml += `<button class="msg-action-btn" onclick="doRedact('${evt.eventId}')" title="Delete" style="color:var(--red)">&#10005;</button>`;
    }
    actionsHtml += `</div>`;
    actionsHtml += `<div class="msg-reactions-bar" id="reactions-${evt.eventId}"></div>`;
  }

  group.innerHTML = `
    <div class="msg-avatar" style="background:${color}">${senderInitials}</div>
    <div class="msg-content-wrap">
      <div class="msg-sender-name">${esc(evt.senderId)}</div>
      <div class="msg-bubble">${bubbleHtml}</div>
      ${actionsHtml}
    </div>
  `;
  msgList.appendChild(group);
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
    // silently fail
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

  try {
    const result = await Matrix.sendMessage({ roomId: selectedRoomId, body });
    log(`Sent: ${result.eventId}`, 'success');
  } catch (e) {
    logError('sendMessage', e);
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
      el.innerHTML = '<em style="color:var(--text-muted)">Message deleted</em>';
      el.className = 'msg-system';
    }
  } catch (e) {
    logError('redactEvent', e);
  }
};

window.doReact = async (eventId, key) => {
  if (!selectedRoomId) return;
  // Add chip optimistically before the network call
  addReactionChip(eventId, key);
  const msgList = document.getElementById('messageList');
  msgList.scrollTop = msgList.scrollHeight;
  try {
    await Matrix.sendReaction({ roomId: selectedRoomId, eventId, key });
    log(`Reacted ${key} to ${eventId}`, 'success');
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
    document.getElementById('chatRoomName').textContent = name;
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
    log('Room topic set', 'success');
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

// ── Debug Panel ───────────────────────────────────────

window.toggleDebug = () => {
  document.getElementById('debugPanel').classList.toggle('active');
};

window.toggleDebugMobile = () => {
  const sheet = document.getElementById('debugSheet');
  const overlay = document.getElementById('debugSheetOverlay');
  sheet.classList.toggle('active');
  overlay.classList.toggle('active');
};

window.switchDebugTab = (btn, tab) => {
  const container = btn.closest('.debug-panel, .debug-sheet');
  container.querySelectorAll('.debug-tab').forEach((t) => t.classList.remove('active'));
  container.querySelectorAll('.debug-tab-content').forEach((c) => c.classList.remove('active'));
  btn.classList.add('active');
  container.querySelector(`[data-tab-content="${tab}"]`).classList.add('active');
};

// ── Init ───────────────────────────────────────────────

// Close user search dropdown when clicking outside
document.addEventListener('click', (e) => {
  const results = document.getElementById('userSearchResults');
  const input = document.getElementById('userSearchInput');
  if (results && !results.contains(e.target) && e.target !== input) {
    results.style.display = 'none';
  }
});

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
      showApp();
      await startSyncAndLoadRooms();
    }
  } catch (e) {
    logError('Session restore', e);
  }
})();
