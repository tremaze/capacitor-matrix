import { Matrix } from '@tremaze/capacitor-matrix';

// ── State ──────────────────────────────────────────────

let selectedRoomId = null;
let currentUserId = null;
let currentRooms = [];
let _refreshRoomTimer = null;
// userId → 'online' | 'offline' | 'unavailable'
const presenceMap = new Map();

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

window.doRegister = async () => {
  const homeserverUrl = document.getElementById('homeserverUrl').value.trim();
  const userId = document.getElementById('userId').value.trim();
  const password = document.getElementById('password').value;

  if (!homeserverUrl || !userId || !password) {
    return log('Fill in all fields to register', 'error');
  }

  const localpart = userId.startsWith('@') ? userId.split(':')[0].substring(1) : userId;
  log(`Registering ${localpart}...`);
  try {
    const res = await fetch(`${homeserverUrl}/_matrix/client/v3/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: localpart,
        password,
        auth: { type: 'm.login.dummy' },
      }),
    });
    const data = await res.json();
    if (!res.ok) {
      return log(`Register failed: ${data.error || JSON.stringify(data)}`, 'error');
    }
    logResult('Registration success', data);
    log('Now sign in with the same credentials.', 'success');
  } catch (e) {
    logError('Register', e);
  }
};

window.doLogin = async () => {
  const homeserverUrl = document.getElementById('homeserverUrl').value.trim();
  const userId = document.getElementById('userId').value.trim();
  const password = document.getElementById('password').value;

  if (!homeserverUrl || !userId || !password) {
    return log('Fill in all login fields', 'error');
  }

  await clearStaleDataIfUserChanged(userId);

  log('Logging in...');
  try {
    const session = await Matrix.login({ homeserverUrl, userId, password });
    currentUserId = session.userId;
    localStorage.setItem('lastMatrixUserId', session.userId);
    logResult('Login success', session);
    setStatus(`Logged in as ${session.userId}`, 'connected');
    showApp();
    await startSyncAndLoadRooms();
  } catch (e) {
    logError('Login', e);
  }
};

window.doJwtLogin = async () => {
  const homeserverUrl = document.getElementById('homeserverUrl').value.trim();
  const token = document.getElementById('jwtToken').value.trim();

  if (!homeserverUrl || !token) {
    return log('Fill in homeserver URL and JWT token', 'error');
  }

  log('Logging in with JWT...');
  try {
    const session = await Matrix.jwtLogin({
      homeserverUrl,
      token,
    });
    currentUserId = session.userId;
    localStorage.setItem('lastMatrixUserId', session.userId);
    logResult('JWT login success', session);
    setStatus(`Logged in as ${session.userId}`, 'connected');
    showApp();
    await startSyncAndLoadRooms();
  } catch (e) {
    logError('JWT login', e);
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
    localStorage.removeItem('lastMatrixUserId');
    document.getElementById('roomList').innerHTML = '';
    document.getElementById('loginScreen').classList.remove('hidden');
    document.getElementById('appShell').classList.remove('active');
    closeSettingsDrawer();
  } catch (e) {
    logError('Logout', e);
  }
};

/**
 * If the incoming userId differs from the last logged-in user, wipe all local
 * Matrix state (crypto store, session, caches) before logging in. This prevents
 * stale secret-storage / backup-key data from one user causing recovery failures
 * for another.
 */
async function clearStaleDataIfUserChanged(incomingUserId) {
  const lastUserId = localStorage.getItem('lastMatrixUserId');
  if (lastUserId && lastUserId !== incomingUserId) {
    log(`Different user detected (was ${lastUserId}, now ${incomingUserId}) — clearing local state...`, 'event');
    try {
      await Matrix.clearAllData();
      localStorage.removeItem('lastMatrixUserId');
      log('Local state cleared', 'success');
    } catch (e) {
      logError('clearStaleData', e);
    }
  }
}

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
        break;
      case 'ERROR': {
        const err = data.error || 'unknown';
        if (err.includes('M_UNKNOWN_TOKEN') || err.includes('Unknown access token')) {
          log('Token invalidated by server — logging out', 'error');
          setStatus('Not connected');
          await Matrix.logout().catch(() => {});
          currentUserId = null;
          currentRooms = [];
          selectedRoomId = null;
          document.getElementById('roomList').innerHTML = '';
          document.getElementById('loginScreen').classList.remove('hidden');
          document.getElementById('appShell').classList.remove('active');
        } else {
          setStatus('Sync error', 'error');
          log(`Sync error: ${err}`, 'error');
        }
        break;
      }
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
        // Web sends redaction event with content.redacts pointing to target
        const redactedId = evt.content?.redacts || evt.redacts;
        if (redactedId) {
          const el = document.querySelector(`[data-event-id="${redactedId}"]`);
          if (el) {
            el.innerHTML = '<em style="color:var(--text-muted)">Message deleted</em>';
            el.className = 'msg-system';
          }
        }
        // Android sends the redacted event itself (Set diff) with its own eventId
        if (evt.eventId) {
          const el = document.querySelector(`[data-event-id="${evt.eventId}"]`);
          if (el) {
            el.innerHTML = '<em style="color:var(--text-muted)">Message deleted</em>';
            el.className = 'msg-system';
          }
        }
        return;
      }
      if (evt.type === 'm.reaction') {
        // Reactions are handled via re-emitted parent event with aggregated reactions
        return;
      }
      // Skip state events (member joins, room name changes, etc.) — they are
      // rendered during initial room load and should not be re-appended on sync.
      if (evt.type.startsWith('m.room.') && evt.type !== 'm.room.message' && evt.type !== 'm.room.encrypted' && evt.type !== 'm.room.redaction') {
        return;
      }
      // If event already exists, update it (decryption, reactions, status)
      const existingEl = evt.eventId && document.querySelector(`[data-event-id="${evt.eventId}"]`);
      if (existingEl) {
        // If the message was a decryption placeholder, replace it in-place (preserve order)
        const bubble = existingEl.querySelector('.msg-bubble');
        if (bubble && evt.type === 'm.room.message' && bubble.textContent?.includes('Decrypting')) {
          const parent = existingEl.parentNode;
          const nextSibling = existingEl.nextSibling;
          existingEl.remove();
          renderMessage(evt, nextSibling);
          return;
        }
        const aggReactions = evt.content?.reactions;
        if (Array.isArray(aggReactions)) {
          const container = document.getElementById(`reactions-${evt.eventId}`);
          if (container) {
            container.innerHTML = '';
            for (const r of aggReactions) {
              const senders = r.senders || [];
              for (let i = 0; i < (r.count || 1); i++) {
                const isMine = senders[i] === currentUserId;
                addReactionChip(evt.eventId, r.key, isMine);
              }
            }
          }
        }
        // Update delivery/read status live
        if (evt.status) {
          updateMsgStatus(evt.eventId, evt.status);
        }
        return;
      }
      renderMessage(evt);
      const msgList = document.getElementById('messageList');
      msgList.scrollTop = msgList.scrollHeight;
      // Send read receipt for incoming messages from others
      if (evt.senderId !== currentUserId && evt.eventId && !evt.eventId.startsWith('~!')) {
        log(`Sending read receipt for ${evt.eventId}`, 'info');
        Matrix.markRoomAsRead({ roomId: evt.roomId, eventId: evt.eventId })
          .then(() => log(`Read receipt sent for ${evt.eventId}`, 'info'))
          .catch((e) => log(`Failed to send read receipt: ${e?.message || e}`, 'error'));
      }
    }
    // Refresh room list to update previews/unread counts
    refreshRoomListDebounced();
  });

  Matrix.addListener('roomUpdated', (data) => {
    log(`Room updated: ${data.roomId}`, 'event');
    refreshRoomListDebounced();
    // On native platforms, receipt changes come as room updates — refresh own message statuses
    if (data.roomId === selectedRoomId) {
      refreshOwnMessageStatuses();
    }
  });

  Matrix.addListener('receiptReceived', (data) => {
    if (data.roomId === selectedRoomId) {
      // Update all own messages in view — check if any are now "read"
      document.querySelectorAll('.msg-group.mine').forEach((el) => {
        const eid = el.dataset.eventId;
        if (!eid || eid.startsWith('~!')) return;
        const statusEl = el.querySelector('.msg-status');
        if (statusEl && !statusEl.classList.contains('read')) {
          updateMsgStatus(eid, 'read');
        }
      });
    }
  });

  Matrix.addListener('typingChanged', (data) => {
    const indicator = document.getElementById('typingIndicator');
    if (data.roomId === selectedRoomId) {
      const others = (data.userIds || []).filter((id) => id !== currentUserId);
      indicator.textContent = others.length > 0 ? `${others.join(', ')} typing...` : '';
    }
  });

  Matrix.addListener('presenceChanged', (data) => {
    const status = data.presence?.presence || 'offline';
    presenceMap.set(data.userId, status);
    // Update own presence dot in sidebar footer
    if (data.userId === currentUserId) {
      updateOwnPresenceDot(status);
    }
    // Update all visible presence dots for this user in the message list
    document.querySelectorAll(`.presence-dot[data-user-id="${data.userId}"]`).forEach((dot) => {
      dot.className = `presence-dot presence-${status}`;
    });
  });
}

// Resolved by crypto modal when recovery/setup completes or is skipped
let cryptoModalResolve = null;

async function startSyncAndLoadRooms() {
  roomsLoaded = false;
  registerListeners();

  try {
    await Matrix.initializeCrypto();
    log('Crypto initialized', 'success');
  } catch (e) {
    logError('initCrypto', e);
  }

  // Check crypto and wait for recovery/setup before starting sync
  // so that decryption keys are available when timelines are created
  const needsSync = await checkAndPromptCrypto();
  if (needsSync) {
    // User was prompted — wait for them to complete or skip
    await new Promise((resolve) => { cryptoModalResolve = resolve; });
  }

  log('Starting sync...');
  try {
    await Matrix.startSync();
    log('Sync started', 'success');
    // Set own presence to online
    Matrix.setPresence({ presence: 'online' }).catch(() => {});
    presenceMap.set(currentUserId, 'online');
    updateOwnPresenceDot('online');
  } catch (e) {
    logError('startSync', e);
  }
}

function updateOwnPresenceDot(status) {
  let dot = document.getElementById('ownPresenceDot');
  if (!dot) {
    const avatar = document.getElementById('userAvatar');
    if (!avatar) return;
    avatar.style.position = 'relative';
    dot = document.createElement('span');
    dot.id = 'ownPresenceDot';
    avatar.appendChild(dot);
  }
  dot.className = `presence-dot presence-${status}`;
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
  // Resolve the waiting promise so sync can start
  if (cryptoModalResolve) {
    cryptoModalResolve();
    cryptoModalResolve = null;
  }
}

/** Returns true if user needs to interact with crypto modal before sync can start */
async function checkAndPromptCrypto() {
  try {
    const status = await Matrix.getEncryptionStatus();
    log(`Crypto check: secretStorage=${status.isSecretStorageReady}, crossSigning=${status.isCrossSigningReady}, keyBackup=${status.isKeyBackupEnabled}`, 'event');

    if (!status.isSecretStorageReady) {
      const backupStatus = await Matrix.getKeyBackupStatus().catch(() => ({ exists: false }));
      if (backupStatus.exists) {
        showRecoverModal();
        return true;
      } else {
        showSetupRecoveryModal();
        return true;
      }
    } else if (!status.isKeyBackupEnabled) {
      showRecoverModal();
      return true;
    } else {
      log('Encryption set up for this device', 'success');
      return false;
    }
  } catch (e) {
    logError('cryptoCheck', e);
    return false;
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
      // Show error in modal so it's visible even when debug log is behind the overlay
      const errEl = document.getElementById('modalError') || (() => {
        const el = document.createElement('p');
        el.id = 'modalError';
        el.style.cssText = 'color:var(--error);font-size:13px;margin:8px 0 0';
        btn.parentElement.after(el);
        return el;
      })();
      errEl.textContent = e.message || String(e);
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
      <input type="text" id="modalRecoveryInput" placeholder="Enter recovery key or passphrase..." autocapitalize="off" autocorrect="off" spellcheck="false" />
    </div>
    <div class="btn-row">
      <button class="modal-btn modal-btn-secondary" onclick="hideModal()">Skip</button>
      <button class="modal-btn modal-btn-primary" id="modalRecoverBtn" onclick="doModalRecover()">Verify Device</button>
    </div>
  `);
}

let _recoveryClearAttempted = false;

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
    _recoveryClearAttempted = false;

    const status = await Matrix.getEncryptionStatus();
    logResult('Encryption status after recovery', status);

    hideModal();  // This resolves the promise, allowing sync to start
  } catch (e) {
    const msg = e.message || String(e);
    const isMismatch = msg.includes('decryption key does not match');

    if (isMismatch && !_recoveryClearAttempted) {
      // Stale local crypto state — wipe it, re-login fresh, and retry
      _recoveryClearAttempted = true;
      log('Backup key mismatch — clearing stale local state and retrying...', 'event');
      try {
        await Matrix.clearAllData();
        localStorage.removeItem('lastMatrixUserId');
        log('Local state cleared — please log in again with a fresh JWT', 'error');
        showRecoverErrorModal('Local state cleared. Please log in again.');
      } catch (retryErr) {
        logError('recover-clear-retry', retryErr);
        showRecoverErrorModal(msg);
      }
    } else {
      logError('recover', e);
      showRecoverErrorModal(msg);
    }
  }
};

function showRecoverErrorModal(errorMsg) {
  showModal(`
    <h2>Recovery Failed</h2>
    <p style="color:var(--red)">${esc(errorMsg)}</p>
    <p>The passphrase or recovery key you entered is incorrect. You can try again or reset everything from scratch.</p>
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

  // Separate invitations from joined rooms
  const invites = rooms.filter((r) => r.membership === 'invite');
  const joined = rooms.filter((r) => r.membership !== 'invite');

  // Render invitations at the top
  if (invites.length > 0) {
    const header = document.createElement('li');
    header.className = 'room-list-header';
    header.textContent = `Invitations (${invites.length})`;
    list.appendChild(header);

    invites.forEach((room) => {
      const li = document.createElement('li');
      li.className = 'room-item room-item-invite';
      li.dataset.roomId = room.roomId;

      const color = roomColor(room.roomId);
      const initials = roomInitials(room.name);
      const encIcon = room.isEncrypted ? '<span class="room-encrypted-icon">&#128274;</span>' : '';

      li.innerHTML = `
        <div class="room-avatar" style="background:${color}">${initials}</div>
        <div class="room-item-info">
          <div class="room-item-name">${esc(room.name || '(unnamed)')}${encIcon}</div>
          <div class="room-item-preview">Invited</div>
        </div>
        <div class="room-item-meta" style="display:flex;gap:6px">
          <button class="invite-btn invite-accept" title="Accept">&#10003;</button>
          <button class="invite-btn invite-decline" title="Decline">&#10005;</button>
        </div>
      `;
      li.querySelector('.invite-accept').onclick = (e) => { e.stopPropagation(); doAcceptInvite(room.roomId); };
      li.querySelector('.invite-decline').onclick = (e) => { e.stopPropagation(); doDeclineInvite(room.roomId); };
      list.appendChild(li);
    });

    const divider = document.createElement('li');
    divider.className = 'room-list-divider';
    list.appendChild(divider);
  }

  // Render joined rooms
  joined.forEach((room) => {
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

async function doAcceptInvite(roomId) {
  try {
    log(`Accepting invite for ${roomId}...`);
    await Matrix.joinRoom({ roomIdOrAlias: roomId });
    log('Invite accepted', 'success');
    window.doGetRooms();
  } catch (e) {
    logError('acceptInvite', e);
  }
}

async function doDeclineInvite(roomId) {
  try {
    log(`Declining invite for ${roomId}...`);
    await Matrix.leaveRoom({ roomId });
    log('Invite declined', 'success');
    window.doGetRooms();
  } catch (e) {
    logError('declineInvite', e);
  }
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
  selectedRoomId = null;
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

window.openRoomDrawer = async () => {
  document.getElementById('roomDrawerOverlay').classList.add('active');
  document.getElementById('roomDrawer').classList.add('active');
  // Load encryption details
  const el = document.getElementById('encryptionDetails');
  el.textContent = 'Loading...';
  try {
    const rooms = (await Matrix.getRooms()).rooms || [];
    const room = rooms.find((r) => r.roomId === selectedRoomId);
    const isEncrypted = room?.isEncrypted ?? false;

    const status = await Matrix.getEncryptionStatus();
    const backup = await Matrix.getKeyBackupStatus();
    let recoveryEnabled = false;
    try {
      recoveryEnabled = (await Matrix.isRecoveryEnabled()).enabled;
    } catch {
      // may not be supported
    }

    const cs = status.crossSigningStatus;
    const badge = (val) => val
      ? '<span style="color:var(--green)">✓</span>'
      : '<span style="color:var(--red)">✗</span>';

    el.innerHTML = `
      <div style="display:grid;grid-template-columns:1fr auto;gap:6px 12px;align-items:center">
        <span>Room encrypted</span>${badge(isEncrypted)}
        <span>Cross-signing ready</span>${badge(status.isCrossSigningReady)}
        <span style="padding-left:12px">Master key</span>${badge(cs.hasMaster)}
        <span style="padding-left:12px">Self-signing key</span>${badge(cs.hasSelfSigning)}
        <span style="padding-left:12px">User-signing key</span>${badge(cs.hasUserSigning)}
        <span>Key backup</span>${badge(backup.enabled)}
        <span>Secret storage</span>${badge(status.isSecretStorageReady)}
        <span>Recovery</span>${badge(recoveryEnabled)}
      </div>
    `;
  } catch (e) {
    el.textContent = 'Error loading encryption details: ' + (e.message || e);
  }
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
    // Filter out non-displayable events
    const hiddenTypes = ['m.reaction'];
    const displayEvents = events.filter((evt) => !hiddenTypes.includes(evt.type));
    displayEvents.forEach((evt) => renderMessage(evt));
    // Apply aggregated reactions (works for both web and Android)
    for (const evt of displayEvents) {
      const aggReactions = evt.content?.reactions;
      if (Array.isArray(aggReactions) && evt.eventId) {
        for (const r of aggReactions) {
          const senders = r.senders || [];
          for (let i = 0; i < (r.count || 1); i++) {
            const isMine = senders[i] === currentUserId;
            addReactionChip(evt.eventId, r.key, isMine);
          }
        }
      }
    }
    msgList.scrollTop = msgList.scrollHeight;
    // Send read receipt for the latest message
    const lastEvent = events[events.length - 1];
    if (lastEvent?.eventId && !lastEvent.eventId.startsWith('~!')) {
      log(`Sending read receipt for latest: ${lastEvent.eventId}`, 'info');
      Matrix.markRoomAsRead({ roomId: selectedRoomId, eventId: lastEvent.eventId })
        .then(() => log(`Read receipt sent for ${lastEvent.eventId}`, 'info'))
        .catch((e) => log(`Failed to send read receipt: ${e?.message || e}`, 'error'));
    }
    // Refresh read receipt statuses for own messages (needed on iOS where sliding sync doesn't deliver receipts)
    refreshOwnMessageStatuses();
  } catch (e) {
    msgList.innerHTML = '<div class="msg-system">Error loading messages</div>';
    logError('loadConversation', e);
  }
}

async function loadMessages(roomId) {
  if (roomId !== selectedRoomId) return;
  await loadConversation();
}

function renderMessage(evt, insertBefore = null) {
  const msgList = document.getElementById('messageList');
  const isMine = evt.senderId === currentUserId;

  // Skip non-displayable event types
  const hiddenTypes = ['m.reaction'];
  if (hiddenTypes.includes(evt.type)) return;

  // Redacted messages — show as "Message deleted"
  if (evt.type === 'm.room.redaction') {
    const sys = document.createElement('div');
    sys.className = 'msg-system';
    if (evt.eventId) sys.dataset.eventId = evt.eventId;
    sys.innerHTML = '<em style="color:var(--text-muted)">Message deleted</em>';
    if (insertBefore) {
      msgList.insertBefore(sys, insertBefore);
    } else {
      msgList.appendChild(sys);
    }
    return;
  }

  // State events (but not encrypted messages — those will be decrypted)
  if (evt.type !== 'm.room.message' && evt.type !== 'm.room.encrypted') {
    const sys = document.createElement('div');
    sys.className = 'msg-system';
    const label = evt.type === 'm.room.member' ? `${evt.senderId} ${evt.content?.membership || 'updated'}` :
                  `${evt.type}`;
    sys.textContent = label;
    if (insertBefore) {
      msgList.insertBefore(sys, insertBefore);
    } else {
      msgList.appendChild(sys);
    }
    return;
  }

  // For encrypted events still being decrypted, show a placeholder
  if (evt.type === 'm.room.encrypted') {
    evt.content = { ...evt.content, body: '🔒 Decrypting...', msgtype: 'm.text' };
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
    const info = evt.content?.info;
    const aspectStyle = mediaAspectStyle(info, 280);
    bubbleHtml += `<div class="media-preview" ${aspectStyle ? `style="${aspectStyle}"` : ''}><img src="" data-mxc="${esc(evt.content.url)}" alt="${esc(body)}" onclick="window.open(this.src)" style="width:100%;height:100%;object-fit:cover;border-radius:12px;cursor:pointer" /></div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.audio' && evt.content?.url) {
    bubbleHtml += `<div class="media-preview"><audio controls src="" data-mxc="${esc(evt.content.url)}" style="width:100%"></audio></div>`;
    if (body) bubbleHtml += `<div style="font-size:12px;opacity:0.7;margin-top:4px">${esc(body)}</div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.video' && evt.content?.url) {
    const info = evt.content?.info;
    const aspectStyle = mediaAspectStyle(info, 280);
    bubbleHtml += `<div class="media-preview" ${aspectStyle ? `style="${aspectStyle}"` : ''}><video controls src="" data-mxc="${esc(evt.content.url)}" style="width:100%;height:100%;border-radius:12px"></video></div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.file' && evt.content?.url) {
    bubbleHtml += `<div class="media-preview"><a href="#" data-mxc="${esc(evt.content.url)}" onclick="downloadMedia(this,event)" style="display:flex;align-items:center;gap:8px;text-decoration:none;color:inherit">&#128206; <span>${esc(body || 'Download file')}</span></a></div>`;
    resolveMediaUrl(evt.content.url);
  } else if (msgtype === 'm.emote') {
    bubbleHtml += `<em>* ${esc(evt.senderId)} ${esc(body)}</em>`;
  } else if (msgtype === 'm.notice') {
    bubbleHtml += `<span style="opacity:0.7">${esc(body)}</span>`;
  } else {
    bubbleHtml += esc(body);
  }

  // Status indicator for own messages
  let statusHtml = '';
  if (isMine && evt.status) {
    if (evt.status === 'sending') {
      statusHtml = '<span class="msg-status sending" title="Sending" style="letter-spacing:0">&#128336;</span>'; // 🕐
    } else if (evt.status === 'sent') {
      statusHtml = '<span class="msg-status sent" title="Sent">&#10003;</span>'; // ✓
    } else if (evt.status === 'delivered') {
      statusHtml = '<span class="msg-status delivered" title="Delivered">&#10003;&#10003;</span>'; // ✓✓
    } else if (evt.status === 'read') {
      statusHtml = '<span class="msg-status read" title="Read">&#10003;&#10003;</span>'; // ✓✓ blue
    }
  }

  bubbleHtml += `<div class="msg-time">${time}${statusHtml}</div>`;

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

  // Read receipt avatars
  let readByHtml = '';
  if (evt.readBy && evt.readBy.length > 0) {
    const avatars = evt.readBy
      .filter((uid) => uid !== evt.senderId)
      .map((uid) => {
        const initials = uid.replace('@', '').substring(0, 2).toUpperCase();
        const c = roomColor(uid);
        return `<span class="read-receipt-avatar" style="background:${c}" title="${esc(uid)}">${initials}</span>`;
      })
      .join('');
    if (avatars) {
      readByHtml = `<div class="msg-read-receipts">${avatars}</div>`;
    }
  }

  const senderPresence = presenceMap.get(evt.senderId) || 'offline';
  group.innerHTML = `
    <div class="msg-avatar" style="background:${color}">
      ${senderInitials}
      <span class="presence-dot presence-${senderPresence}" data-user-id="${esc(evt.senderId)}"></span>
    </div>
    <div class="msg-content-wrap">
      <div class="msg-sender-name">${esc(evt.senderId)}</div>
      <div class="msg-bubble">${bubbleHtml}</div>
      ${actionsHtml}
      ${readByHtml}
    </div>
  `;
  if (insertBefore) {
    msgList.insertBefore(group, insertBefore);
  } else {
    msgList.appendChild(group);
  }
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

async function refreshOwnMessageStatuses() {
  if (!selectedRoomId) return;
  const ownMsgs = document.querySelectorAll('.msg-group.mine');
  const eventIds = [];
  ownMsgs.forEach((el) => {
    const eid = el.dataset.eventId;
    if (eid && !eid.startsWith('~!')) {
      const statusEl = el.querySelector('.msg-status');
      if (statusEl && !statusEl.classList.contains('read')) {
        eventIds.push(eid);
      }
    }
  });
  if (eventIds.length === 0) return;
  try {
    const result = await Matrix.refreshEventStatuses({ roomId: selectedRoomId, eventIds });
    for (const evt of (result.events || [])) {
      if (evt.status) {
        updateMsgStatus(evt.eventId, evt.status);
      }
    }
  } catch (e) {
    // refreshEventStatuses may not be available on all platforms yet
  }
}

function updateMsgStatus(eventId, status) {
  const el = document.querySelector(`[data-event-id="${eventId}"]`);
  if (!el) return;
  const statusEl = el.querySelector('.msg-status');
  if (status === 'sent') {
    if (statusEl) {
      statusEl.className = 'msg-status sent';
      statusEl.title = 'Sent';
      statusEl.innerHTML = '&#10003;';
      statusEl.style.letterSpacing = '-3px';
    }
  } else if (status === 'read') {
    if (statusEl) {
      statusEl.className = 'msg-status read';
      statusEl.title = 'Read';
      statusEl.innerHTML = '&#10003;&#10003;';
      statusEl.style.letterSpacing = '-3px';
    }
  }
}

function mediaAspectStyle(info, maxWidth) {
  if (!info) return '';
  const w = info.w || info.width;
  const h = info.h || info.height;
  if (!w || !h) return '';
  const aspect = w / h;
  const displayWidth = Math.min(w, maxWidth);
  const displayHeight = Math.round(displayWidth / aspect);
  return `width:${displayWidth}px;height:${displayHeight}px`;
}

function addReactionChip(eventId, key, isMine = false) {
  const container = document.getElementById(`reactions-${eventId}`);
  if (!container) return;
  const chip = document.createElement('span');
  chip.className = `reaction-chip${isMine ? ' mine' : ''}`;
  chip.dataset.key = key;
  chip.textContent = key;
  container.appendChild(chip);
}

function rebuildReactionChips(eventId, reactions) {
  const container = document.getElementById(`reactions-${eventId}`);
  if (!container) return;
  container.innerHTML = '';
  if (!Array.isArray(reactions)) return;
  for (const r of reactions) {
    const senders = r.senders || [];
    for (let i = 0; i < (r.count || 1); i++) {
      const isMine = senders[i] === currentUserId;
      addReactionChip(eventId, r.key, isMine);
    }
  }
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
    // Upgrade the SDK's local echo element (with ~! prefix) to the real server event ID
    if (result.eventId) {
      const localEchoEl = document.querySelector('[data-event-id^="~!"]');
      if (localEchoEl) {
        const oldId = localEchoEl.dataset.eventId;
        localEchoEl.dataset.eventId = result.eventId;
        // Update reaction buttons and container to use the real event ID
        const wrap = localEchoEl.querySelector('.msg-content-wrap');
        if (wrap) {
          const actionsBar = wrap.querySelector('.msg-actions-bar');
          if (actionsBar) {
            actionsBar.innerHTML = `
              <button class="msg-action-btn" onclick="doReact('${result.eventId}','\\u{1F44D}')">&#128077;</button>
              <button class="msg-action-btn" onclick="doReact('${result.eventId}','\\u2764\\uFE0F')">&#10084;&#65039;</button>
              <button class="msg-action-btn" onclick="doReact('${result.eventId}','\\u{1F602}')">&#128514;</button>
              <button class="msg-action-btn" onclick="doRedact('${result.eventId}')" title="Delete" style="color:var(--red)">&#10005;</button>
            `;
          }
          const reactionsBar = wrap.querySelector('.msg-reactions-bar');
          if (reactionsBar) {
            reactionsBar.id = `reactions-${result.eventId}`;
          }
        }
        log(`Upgraded ${oldId} -> ${result.eventId}`, 'success');
      }
      // Update status from sending → sent
      updateMsgStatus(result.eventId, 'sent');
    }
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
  const container = document.getElementById(`reactions-${eventId}`);
  // Check if we already have this reaction from ourselves — toggle off
  const existingChip = container && container.querySelector(`.reaction-chip.mine[data-key="${key}"]`);
  if (existingChip) {
    existingChip.remove();
  } else {
    addReactionChip(eventId, key, true);
  }
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

    // Upgrade the SDK's local echo element (with ~! prefix) to the real server event ID
    if (result.eventId) {
      const localEchoEl = document.querySelector('[data-event-id^="~!"]');
      if (localEchoEl) {
        const oldId = localEchoEl.dataset.eventId;
        localEchoEl.dataset.eventId = result.eventId;
        const wrap = localEchoEl.querySelector('.msg-content-wrap');
        if (wrap) {
          const actionsBar = wrap.querySelector('.msg-actions-bar');
          if (actionsBar) {
            actionsBar.innerHTML = `
              <button class="msg-action-btn" onclick="doReact('${result.eventId}','\\u{1F44D}')">&#128077;</button>
              <button class="msg-action-btn" onclick="doReact('${result.eventId}','\\u2764\\uFE0F')">&#10084;&#65039;</button>
              <button class="msg-action-btn" onclick="doReact('${result.eventId}','\\u{1F602}')">&#128514;</button>
              <button class="msg-action-btn" onclick="doRedact('${result.eventId}')" title="Delete" style="color:var(--red)">&#10005;</button>
            `;
          }
          const reactionsBar = wrap.querySelector('.msg-reactions-bar');
          if (reactionsBar) {
            reactionsBar.id = `reactions-${result.eventId}`;
          }
        }
        log(`Upgraded ${oldId} -> ${result.eventId}`, 'success');
      }
      updateMsgStatus(result.eventId, 'sent');
    }
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
      await clearStaleDataIfUserChanged(session.userId);
      currentUserId = session.userId;
      localStorage.setItem('lastMatrixUserId', session.userId);
      log(`Restoring session: ${session.userId}`, 'success');
      document.getElementById('homeserverUrl').value = session.homeserverUrl || '';
      document.getElementById('userId').value = session.userId || '';
      log('Session found — please log in with a fresh JWT to restore', 'event');
      setStatus(`Session found for ${session.userId} — re-login required`, 'disconnected');
    }
  } catch (e) {
    const msg = e?.message || String(e);
    if (msg.includes('M_UNKNOWN_TOKEN') || msg.includes('Unknown access token') || msg.includes('401')) {
      log('Session expired or token invalid — please log in again', 'error');
      await Matrix.logout().catch(() => {});
      currentUserId = null;
      currentRooms = [];
      selectedRoomId = null;
      setStatus('Not connected');
    } else {
      logError('Session restore', e);
    }
  }
})();
