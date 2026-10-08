/**
 * ============================================================================
 * EduPlus Teaching Studio - Student Core Engine (app.js)
 * Module Responsibilities:
 * 1. Local Monaco Editor environment initialization with personalized orange theme
 * 2. Project workspace directory tree rendering & file switching (Directory Tree)
 * 3. Multi-tab bar rendering and synchronization (Multi-tabs)
 * 4. Real-time console output capture and streaming (Execution Output Console)
 * 5. High-fidelity teacher blue cursor & selection rendering (CSS overlay + smooth viewport follow)
 * 6. Student cursor 30ms throttled reporting & incremental delta editing (with RemoteApplyGuard)
 * 7. Local/LAN WebSocket exponential backoff reconnection state machine
 * ============================================================================
 */

(function () {
  'use strict';

  // ==========================================
  // 1. Global Configurations & Constants
  // ==========================================
  const CONFIG = {
    wsUrl: getWsUrl(),
    cursorThrottleMs: 30,
    reconnectIntervals: [1000, 2000, 4000, 8000, 10000],
    heartbeatIntervalMs: 5000,
    initialLanguage: 'java',
    initialCode: `// Welcome to EduPlus Real-time Teaching Studio\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println("EduPlus studio connected. Waiting for teacher code broadcast...");\n    }\n}\n`
  };

  /** Extract room & student info from URL parameters, auto-calculate WebSocket port */
  function getWsUrl() {
    const params = new URLSearchParams(window.location.search);
    const hostname = window.location.hostname || '127.0.0.1';
    const httpPort = parseInt(window.location.port || '8765', 10);
    const wsPort = params.get('wsPort') || (httpPort + 1);
    const roomId = params.get('roomId') || 'ROOM_101';
    const studentId = params.get('studentId') || ('stu_' + Math.floor(Math.random() * 9000 + 1000));
    const studentName = params.get('name') || ('Student_' + studentId.slice(-4));

    window.CLIENT_META = { roomId, studentId, studentName };

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${hostname}:${wsPort}/ws/collab?roomId=${roomId}&userId=${studentId}&name=${encodeURIComponent(studentName)}&role=student`;
  }

  // ==========================================
  // 2. DOM Elements Cache
  // ==========================================
  const DOM = {
    roomBadge: document.getElementById('roomBadge'),
    userBadge: document.getElementById('userBadge'),
    lockIndicator: document.getElementById('lockIndicator'),
    lockIcon: document.getElementById('lockIcon'),
    lockText: document.getElementById('lockText'),
    followTeacherCheckbox: document.getElementById('followTeacherCheckbox'),
    networkLatency: document.getElementById('networkLatency'),
    latencyVal: document.getElementById('latencyVal'),
    connStatusCapsule: document.getElementById('connStatusCapsule'),
    statusDot: document.getElementById('statusDot'),
    statusText: document.getElementById('statusText'),
    btnReconnect: document.getElementById('btnReconnect'),
    cursorInfo: document.getElementById('cursorInfo'),
    selectionInfo: document.getElementById('selectionInfo'),
    fileInfo: document.getElementById('fileInfo'),
    syntaxInfo: document.getElementById('syntaxInfo'),
    teacherPresenceBadge: document.getElementById('teacherPresenceBadge'),

    // Tree & Tabs DOM
    toggleSidebarBtn: document.getElementById('toggleSidebarBtn'),
    fileTreeSidebar: document.getElementById('file-tree-sidebar'),
    fileTreeContainer: document.getElementById('file-tree-container'),
    refreshTreeBtn: document.getElementById('refreshTreeBtn'),
    editorTabs: document.getElementById('editor-tabs'),

    // Terminal & Run DOM
    btnStudentRun: document.getElementById('btnStudentRun'),
    toggleTerminalBtn: document.getElementById('toggleTerminalBtn'),
    terminalPanel: document.getElementById('terminal-panel'),
    runStatusBadge: document.getElementById('runStatusBadge'),
    clearTerminalBtn: document.getElementById('clearTerminalBtn'),
    collapseTerminalBtn: document.getElementById('collapseTerminalBtn'),
    terminalContent: document.getElementById('terminal-content')
  };

  // ==========================================
  // 3. Core Collaborative State
  // ==========================================
  const State = {
    editor: null,                       // Monaco Editor instance
    isApplyingRemoteUpdate: false,      // Anti-loop recursion latch
    teacherDecorations: [],             // Teacher cursor decoration IDs
    isReadOnlyLocked: false,            // Read-only lock
    followTeacherView: true,            // Auto-follow teacher viewport
    currentFilePath: '',               // Active file relative path
    tabs: [],                           // Open tabs list
    projectTree: [],                    // Project directory tree

    // WebSocket state machine
    ws: null,
    connectionState: 'DISCONNECTED',
    reconnectAttempt: 0,
    reconnectTimer: null,
    heartbeatTimer: null,
    lastPingTimestamp: 0
  };

  // ==========================================
  // 4. Monaco Editor Localization Integration
  // ==========================================
  function initMonacoEditor() {
    window.MonacoEnvironment = {
      getWorkerUrl: function (moduleId, label) {
        const workerScript = `
          self.MonacoEnvironment = { baseUrl: '${window.location.origin}/vs' };
          importScripts('${window.location.origin}/vs/base/worker/workerMain.js');
        `;
        return `data:text/javascript;charset=utf-8,${encodeURIComponent(workerScript)}`;
      }
    };

    // 1. Define student orange dark theme
    monaco.editor.defineTheme('student-orange-dark', {
      base: 'vs-dark',
      inherit: true,
      rules: [
        { token: 'comment', foreground: '6a9955', fontStyle: 'italic' },
        { token: 'keyword', foreground: 'c586c0' },
        { token: 'string', foreground: 'ce9178' },
        { token: 'number', foreground: 'b5cea8' }
      ],
      colors: {
        'editor.background': '#1e1e1e',
        'editorCursor.foreground': '#ff9800',
        'editor.selectionBackground': '#ff980040',
        'editor.inactiveSelectionBackground': '#ff980026',
        'editor.selectionHighlightBackground': '#ff98002a',
        'editorLineNumber.activeForeground': '#ff9800',
        'editorLineNumber.foreground': '#5a5a5a'
      }
    });

    // 2. Create Monaco editor instance
    State.editor = monaco.editor.create(document.getElementById('monaco-host'), {
      value: CONFIG.initialCode,
      language: CONFIG.initialLanguage,
      theme: 'student-orange-dark',
      automaticLayout: true,
      fontSize: 14,
      fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, 'Courier New', monospace",
      fontLigatures: true,
      tabSize: 4,
      renderWhitespace: 'selection',
      minimap: { enabled: true, side: 'right' },
      scrollBeyondLastLine: false,
      smoothScrolling: true,
      cursorBlinking: 'smooth',
      cursorSmoothCaretAnimation: 'on'
    });

    // 3. Bind editor listeners
    bindMonacoEvents();

    // 4. Connect WebSocket pipeline
    WSClient.connect();
  }

  // ==========================================
  // 5. Editor Events & Student Throttle Reporting
  // ==========================================
  function bindMonacoEvents() {
    const editor = State.editor;

    let cursorThrottleTimer = null;
    let pendingCursorEvent = null;

    // Listen to cursor position & selection changes
    editor.onDidChangeCursorPosition(function (e) {
      pendingCursorEvent = e;
      if (!cursorThrottleTimer) {
        cursorThrottleTimer = setTimeout(function () {
          sendStudentCursor();
          cursorThrottleTimer = null;
        }, CONFIG.cursorThrottleMs);
      }
      updateCursorStatusUI(e.position);
    });

    editor.onDidChangeCursorSelection(function (e) {
      updateSelectionStatusUI(e.selection);
    });

    function sendStudentCursor() {
      if (!State.ws || State.ws.readyState !== WebSocket.OPEN) return;
      const pos = editor.getPosition();
      const sel = editor.getSelection();
      if (!pos) return;

      const payload = {
        studentId: window.CLIENT_META.studentId,
        studentName: window.CLIENT_META.studentName,
        line: pos.lineNumber,
        ch: pos.column
      };

      if (sel && !sel.isEmpty()) {
        payload.selectionStart = { line: sel.startLineNumber, ch: sel.startColumn };
        payload.selectionEnd = { line: sel.endLineNumber, ch: sel.endColumn };
      }

      WSClient.send({
        type: 'cursor_student',
        payload: payload
      });
    }

    // Listen to student keyboard edits
    editor.onDidChangeModelContent(function (e) {
      if (State.isApplyingRemoteUpdate) return;
      if (State.isReadOnlyLocked) return;

      e.changes.forEach(function (change) {
        WSClient.send({
          type: 'code_delta',
          payload: {
            filePath: State.currentFilePath,
            rangeOffset: change.rangeOffset,
            oldLength: change.rangeLength,
            text: change.text
          }
        });
      });
    });
  }

  // ==========================================
  // 6. UI Interaction Component Bindings
  // ==========================================
  function bindUIEvents() {
    // Classroom info
    if (window.CLIENT_META) {
      DOM.roomBadge.textContent = 'Room: ' + window.CLIENT_META.roomId;
      DOM.userBadge.textContent = 'Student: ' + window.CLIENT_META.studentName;
    }

    // Follow teacher viewport toggle
    DOM.followTeacherCheckbox.addEventListener('change', function (e) {
      State.followTeacherView = e.target.checked;
    });

    // Reconnect button
    DOM.btnReconnect.addEventListener('click', function () {
      WSClient.connect();
    });

    // Sidebar collapse toggle
    DOM.toggleSidebarBtn.addEventListener('click', function () {
      DOM.fileTreeSidebar.classList.toggle('collapsed');
      setTimeout(() => State.editor && State.editor.layout(), 220);
    });

    // Refresh tree button
    DOM.refreshTreeBtn.addEventListener('click', function () {
      DOM.fileTreeContainer.innerHTML = '<div class="tree-loading">Refreshing project tree...</div>';
      WSClient.send({ type: 'refresh_tree_request' });
    });

    // Toggle terminal button
    DOM.toggleTerminalBtn.addEventListener('click', function () {
      DOM.terminalPanel.classList.toggle('hidden');
      setTimeout(() => State.editor && State.editor.layout(), 220);
    });

    // Collapse terminal button
    DOM.collapseTerminalBtn.addEventListener('click', function () {
      const isCol = DOM.terminalPanel.classList.toggle('collapsed');
      DOM.collapseTerminalBtn.textContent = isCol ? 'Expand' : 'Collapse';
      setTimeout(() => State.editor && State.editor.layout(), 220);
    });

    // Clear terminal output
    DOM.clearTerminalBtn.addEventListener('click', function () {
      DOM.terminalContent.innerHTML = '<div class="terminal-line terminal-system">Console output cleared.</div>';
    });

    // Student request to run active file
    if (DOM.btnStudentRun) {
      DOM.btnStudentRun.addEventListener('click', function () {
        if (!State.ws || State.ws.readyState !== WebSocket.OPEN) {
          alert('Collaboration not connected, cannot request run.');
          return;
        }

        DOM.btnStudentRun.disabled = true;
        DOM.btnStudentRun.textContent = '⏳ Waiting for Approval...';

        const reqPath = State.currentFilePath || 'Active File';
        WSClient.send({
          type: 'student_request_run',
          payload: {
            studentId: window.CLIENT_META.studentId,
            studentName: window.CLIENT_META.studentName,
            filePath: reqPath
          }
        });

        const timeStr = new Date().toLocaleTimeString();
        const line = document.createElement('div');
        line.className = 'terminal-line terminal-system';
        line.textContent = `[${timeStr}] === ⏳ Requested to run [${reqPath}], waiting for teacher approval... ===`;
        DOM.terminalContent.appendChild(line);
        DOM.terminalContent.scrollTop = DOM.terminalContent.scrollHeight;

        // Auto-reset after 15 seconds
        setTimeout(function () {
          if (DOM.btnStudentRun.disabled) {
            DOM.btnStudentRun.disabled = false;
            DOM.btnStudentRun.textContent = '▶ Run Code';
          }
        }, 15000);
      });
    }
  }

  function handleStudentRunResponse(payload) {
    if (!payload) return;
    if (DOM.btnStudentRun) {
      DOM.btnStudentRun.disabled = false;
      DOM.btnStudentRun.textContent = '▶ Run Code';
    }

    const timeStr = new Date().toLocaleTimeString();
    const line = document.createElement('div');
    if (payload.approved) {
      line.className = 'terminal-line terminal-system';
      line.textContent = `[${timeStr}] === ✅ Teacher approved run request. Launching execution... ===`;
    } else {
      line.className = 'terminal-line terminal-stderr';
      line.textContent = `[${timeStr}] === ❌ Teacher rejected the run request. ===`;
    }
    DOM.terminalContent.appendChild(line);
    DOM.terminalContent.scrollTop = DOM.terminalContent.scrollHeight;
  }

  // ==========================================
  // 7. Directory Tree Engine (Directory Tree)
  // ==========================================
  function renderDirectoryTree(treeData, projectName) {
    State.projectTree = treeData;
    const container = DOM.fileTreeContainer;
    container.innerHTML = '';

    if (!treeData || treeData.length === 0) {
      container.innerHTML = '<div class="tree-loading">Workspace is empty or has no files</div>';
      return;
    }

    const rootList = document.createElement('div');
    rootList.className = 'tree-root';

    function buildNodes(items, parentEl) {
      items.forEach(item => {
        const node = document.createElement('div');
        node.className = 'tree-node ' + (item.isDirectory ? 'tree-folder' : 'tree-file');
        node.dataset.path = item.path;

        if (item.path === State.currentFilePath) {
          node.classList.add('active');
        }

        const icon = document.createElement('span');
        icon.className = 'tree-icon';
        icon.textContent = item.isDirectory ? '📁 ' : getFileIcon(item.name);

        const label = document.createElement('span');
        label.className = 'tree-label';
        label.textContent = item.name;

        node.appendChild(icon);
        node.appendChild(label);
        parentEl.appendChild(node);

        if (item.isDirectory) {
          const childrenBox = document.createElement('div');
          childrenBox.className = 'tree-children';
          if (item.children && item.children.length > 0) {
            buildNodes(item.children, childrenBox);
          }
          parentEl.appendChild(childrenBox);

          node.addEventListener('click', function (e) {
            e.stopPropagation();
            const isHidden = childrenBox.style.display === 'none';
            childrenBox.style.display = isHidden ? 'block' : 'none';
            icon.textContent = isHidden ? '📂 ' : '📁 ';
          });
        } else {
          node.addEventListener('click', function (e) {
            e.stopPropagation();
            openFile(item.path);
          });
        }
      });
    }

    buildNodes(treeData, rootList);
    container.appendChild(rootList);
  }

  function getFileIcon(fileName) {
    if (!fileName) return '📄 ';
    const ext = fileName.split('.').pop().toLowerCase();
    switch (ext) {
      case 'java': return '☕ ';
      case 'kt': case 'kts': return '🟣 ';
      case 'py': return '🐍 ';
      case 'js': case 'ts': return '📜 ';
      case 'html': return '🌐 ';
      case 'css': return '🎨 ';
      case 'json': return '📦 ';
      case 'xml': return '📑 ';
      case 'md': return '📝 ';
      default: return '📄 ';
    }
  }

  function openFile(filePath) {
    if (!filePath) return;
    WSClient.send({
      type: 'open_file_request',
      payload: { path: filePath }
    });
  }

  // ==========================================
  // 8. Multi-tab Bar Rendering (Multi Tabs)
  // ==========================================
  function renderTabs(tabs, activePath) {
    State.tabs = tabs || [];
    if (activePath) State.currentFilePath = activePath;

    const container = DOM.editorTabs;
    container.innerHTML = '';

    if (!tabs || tabs.length === 0) {
      const single = document.createElement('div');
      single.className = 'tab-item active';
      const fName = State.currentFilePath || 'Active.java';
      single.innerHTML = `<span class="tab-icon">📄</span><span class="tab-name">${fName}</span>`;
      container.appendChild(single);
      return;
    }

    tabs.forEach(tab => {
      const tabEl = document.createElement('div');
      const tabName = tab.name || (tab.path ? tab.path.split('/').pop() : 'file');
      const isAct = (tab.active || tab.path === State.currentFilePath);
      tabEl.className = 'tab-item ' + (isAct ? 'active' : '');
      tabEl.dataset.path = tab.path || '';
      tabEl.innerHTML = `<span class="tab-icon">${getFileIcon(tabName)}</span><span class="tab-name">${tabName}</span>`;

      tabEl.addEventListener('click', function () {
        if (tab.path) openFile(tab.path);
      });

      container.appendChild(tabEl);
    });

    updateTreeActiveFile(State.currentFilePath);
  }

  function updateTreeActiveFile(path) {
    const nodes = DOM.fileTreeContainer.querySelectorAll('.tree-node');
    nodes.forEach(n => {
      if (n.dataset.path === path) {
        n.classList.add('active');
      } else {
        n.classList.remove('active');
      }
    });
  }

  // ==========================================
  // 9. Execution Output Synchronization
  // ==========================================
  function handleExecutionStatus(payload) {
    if (!payload) return;
    const status = payload.status;
    const terminal = DOM.terminalContent;

    if (status === 'started') {
      DOM.runStatusBadge.textContent = '🚀 Running: ' + (payload.title || 'App');
      DOM.runStatusBadge.className = 'badge-run-status running';

      const line = document.createElement('div');
      line.className = 'terminal-line terminal-system';
      const timeStr = new Date().toLocaleTimeString();
      line.textContent = `[${timeStr}] === 🚀 Started: ${payload.title || 'Program'} ===`;
      terminal.appendChild(line);

      DOM.terminalPanel.classList.remove('collapsed', 'hidden');
      setTimeout(() => State.editor && State.editor.layout(), 100);
    } else if (status === 'terminated') {
      const exitCode = payload.exitCode !== undefined ? payload.exitCode : 0;
      DOM.runStatusBadge.textContent = `✅ Exit (Code: ${exitCode})`;
      DOM.runStatusBadge.className = 'badge-run-status ' + (exitCode === 0 ? '' : 'error');

      const line = document.createElement('div');
      line.className = 'terminal-line terminal-system';
      const timeStr = new Date().toLocaleTimeString();
      line.textContent = `[${timeStr}] === Process finished with exit code ${exitCode} ===\n`;
      terminal.appendChild(line);
    }
    terminal.scrollTop = terminal.scrollHeight;
  }

  function handleExecutionOutput(payload) {
    if (!payload || !payload.text) return;
    const terminal = DOM.terminalContent;
    const line = document.createElement('div');

    if (payload.isStderr) {
      line.className = 'terminal-line terminal-stderr';
    } else if (payload.isSystem) {
      line.className = 'terminal-line terminal-system';
    } else {
      line.className = 'terminal-line terminal-stdout';
    }

    line.textContent = payload.text;
    terminal.appendChild(line);
    terminal.scrollTop = terminal.scrollHeight;
  }

  // ==========================================
  // 10. Teacher Dual-Cursor Overlay Rendering
  //     (Pure CSS overlay, zero buffer pollution, student cursor protected)
  // ==========================================
  function renderTeacherCursor(payload) {
    if (!State.editor || !payload) return;
    const model = State.editor.getModel();
    if (!model) return;

    // Ignore cursor if associated with a different file
    if (payload.filePath && State.currentFilePath && payload.filePath !== State.currentFilePath) {
      return;
    }

    const maxLine = model.getLineCount();
    const safeLine = Math.max(1, Math.min(payload.line || 1, maxLine));
    const maxCol = model.getLineMaxColumn(safeLine);
    const safeCh = Math.max(1, Math.min(payload.ch || 1, maxCol));
    const teacherName = payload.teacherName || 'Teacher';

    // Save student active position before updating decorations
    const hasFocus = State.editor.hasTextFocus();
    const currentPos = State.editor.getPosition();
    const currentSel = State.editor.getSelection();

    const newDecorations = [];

    // 1. Blue highlight vertical cursor bar (Floating badge styled via CSS ::before)
    newDecorations.push({
      range: new monaco.Range(safeLine, safeCh, safeLine, safeCh),
      options: {
        className: 'teacher-cursor-line',
        hoverMessage: { value: `**Teacher (${teacherName})**` }
      }
    });

    // 2. Teacher selection highlight
    if (payload.selectionStart && payload.selectionEnd) {
      const sLine = Math.max(1, Math.min(payload.selectionStart.line, maxLine));
      const sCol = Math.max(1, Math.min(payload.selectionStart.ch, model.getLineMaxColumn(sLine)));
      const eLine = Math.max(1, Math.min(payload.selectionEnd.line, maxLine));
      const eCol = Math.max(1, Math.min(payload.selectionEnd.ch, model.getLineMaxColumn(eLine)));
      newDecorations.push({
        range: new monaco.Range(sLine, sCol, eLine, eCol),
        options: {
          className: 'teacher-selection-highlight',
          isWholeLine: false
        }
      });
    }

    // Atomic update of decorations
    State.teacherDecorations = State.editor.deltaDecorations(
      State.teacherDecorations,
      newDecorations
    );

    // Ensure student cursor is never snapped back to column 1
    if (hasFocus && currentPos) {
      State.editor.setPosition(currentPos);
      if (currentSel && !currentSel.isEmpty()) {
        State.editor.setSelection(currentSel);
      }
    }

    // Viewport smooth follow (only if student is not currently focused/typing)
    if (State.followTeacherView && !hasFocus) {
      State.editor.revealPositionInCenterIfOutsideViewport(
        { lineNumber: safeLine, column: safeCh },
        monaco.editor.ScrollType.Smooth
      );
    }
  }

  // ==========================================
  // 11. Code Full & Delta Synchronization
  // ==========================================
  function handleCodeFull(payload) {
    if (!State.editor || !payload) return;
    State.isApplyingRemoteUpdate = true;
    try {
      const currentModel = State.editor.getModel();
      const newLang = payload.language || 'plaintext';
      const content = payload.content !== undefined && payload.content !== null ? payload.content : '';

      if (currentModel) {
        currentModel.setValue(content);
        monaco.editor.setModelLanguage(currentModel, newLang);
      } else {
        const newModel = monaco.editor.createModel(content, newLang);
        State.editor.setModel(newModel);
      }

      const filePath = payload.filePath || 'Active.java';
      State.currentFilePath = filePath;
      DOM.fileInfo.textContent = '📄 ' + (payload.fileName || filePath);
      DOM.syntaxInfo.textContent = newLang.toUpperCase();

      // Clear stale decorations from previous file to prevent invalid decoration handle errors
      State.teacherDecorations = State.editor.deltaDecorations(State.teacherDecorations, []);

      // Reset scroll position to top to eliminate blank/white screen bug when file sizes differ
      State.editor.setScrollPosition({ scrollTop: 0, scrollLeft: 0 });
      State.editor.setPosition({ lineNumber: 1, column: 1 });

      // Immediate layout refresh
      State.editor.layout();

      updateTreeActiveFile(filePath);
    } catch (err) {
      console.error('[EduPlus] handleCodeFull error:', err);
    } finally {
      State.isApplyingRemoteUpdate = false;
    }
  }

  function handleCodeDelta(payload) {
    if (!State.editor || !payload) return;
    // Ignore delta updates not matching currently active file
    if (payload.filePath && State.currentFilePath && payload.filePath !== State.currentFilePath) {
      return;
    }
    State.isApplyingRemoteUpdate = true;
    try {
      const model = State.editor.getModel();
      if (!model) return;

      const docLen = model.getValueLength();
      const safeOffset = Math.max(0, Math.min(payload.rangeOffset, docLen));
      const safeOldLen = Math.max(0, Math.min(payload.oldLength || 0, docLen - safeOffset));

      const startPos = model.getPositionAt(safeOffset);
      const endPos = model.getPositionAt(safeOffset + safeOldLen);

      model.applyEdits([{
        range: new monaco.Range(startPos.lineNumber, startPos.column, endPos.lineNumber, endPos.column),
        text: payload.text || '',
        forceMoveMarkers: true
      }]);
    } catch (err) {
      console.warn('[EduPlus] handleCodeDelta error:', err);
    } finally {
      State.isApplyingRemoteUpdate = false;
    }
  }

  function handleTeacherLockState(payload) {
    if (!State.editor || !payload) return;
    State.isReadOnlyLocked = !!payload.isLocked;
    State.editor.updateOptions({ readOnly: State.isReadOnlyLocked });

    if (State.isReadOnlyLocked) {
      DOM.lockIndicator.classList.add('locked');
      DOM.lockIcon.textContent = '🔒';
      DOM.lockText.textContent = 'Teacher Exclusive (Read-only)';
    } else {
      DOM.lockIndicator.classList.remove('locked');
      DOM.lockIcon.textContent = '🔓';
      DOM.lockText.textContent = 'Interactive Mode';
    }
  }

  // ==========================================
  // 12. WebSocket Client & Fault Tolerance Reconnection
  // ==========================================
  const WSClient = {
    connect: function () {
      if (State.ws && (State.ws.readyState === WebSocket.OPEN || State.ws.readyState === WebSocket.CONNECTING)) {
        return;
      }

      setConnectionStatus('CONNECTING', 'Connecting to collaboration server...');

      try {
        State.ws = new WebSocket(CONFIG.wsUrl);
      } catch (err) {
        console.error('[WS] Connection creation failed:', err);
        WSClient.scheduleReconnect();
        return;
      }

      State.ws.onopen = function () {
        setConnectionStatus('CONNECTED', 'Collab Active');
        State.reconnectAttempt = 0;
        if (DOM.btnReconnect) DOM.btnReconnect.style.display = 'none';
        WSClient.startHeartbeat();
      };

      State.ws.onmessage = function (event) {
        try {
          const msg = JSON.parse(event.data);
          WSClient.dispatchMessage(msg);
        } catch (e) {
          console.warn('[WS] Parse message error:', e);
        }
      };

      State.ws.onclose = function () {
        setConnectionStatus('DISCONNECTED', 'Connection disconnected');
        WSClient.stopHeartbeat();
        WSClient.scheduleReconnect();
      };

      State.ws.onerror = function () {
        setConnectionStatus('DISCONNECTED', 'Network jitter / connection failed');
      };
    },

    send: function (obj) {
      if (State.ws && State.ws.readyState === WebSocket.OPEN) {
        State.ws.send(JSON.stringify(obj));
      }
    },

    dispatchMessage: function (msg) {
      switch (msg.type) {
        case 'code_full':
          handleCodeFull(msg.payload);
          break;
        case 'code_delta':
          handleCodeDelta(msg.payload);
          break;
        case 'cursor_teacher':
          renderTeacherCursor(msg.payload);
          break;
        case 'teacher_lock_state':
          handleTeacherLockState(msg.payload);
          break;
        case 'directory_tree':
          renderDirectoryTree(msg.payload.tree, msg.payload.projectName);
          break;
        case 'tab_list':
          renderTabs(msg.payload.tabs, msg.payload.activePath);
          break;
        case 'execution_status':
          handleExecutionStatus(msg.payload);
          break;
        case 'execution_output':
          handleExecutionOutput(msg.payload);
          break;
        case 'student_run_response':
          handleStudentRunResponse(msg.payload);
          break;
        case 'heartbeat':
          if (State.lastPingTimestamp > 0) {
            const rtt = Date.now() - State.lastPingTimestamp;
            DOM.latencyVal.textContent = rtt + ' ms';
          }
          break;
      }
    },

    startHeartbeat: function () {
      WSClient.stopHeartbeat();
      State.heartbeatTimer = setInterval(function () {
        if (State.ws && State.ws.readyState === WebSocket.OPEN) {
          State.lastPingTimestamp = Date.now();
          WSClient.send({
            type: 'heartbeat',
            payload: { action: 'ping', clientTime: State.lastPingTimestamp }
          });
        }
      }, CONFIG.heartbeatIntervalMs);
    },

    stopHeartbeat: function () {
      if (State.heartbeatTimer) {
        clearInterval(State.heartbeatTimer);
        State.heartbeatTimer = null;
      }
    },

    scheduleReconnect: function () {
      if (State.reconnectTimer) return;
      if (State.reconnectAttempt >= CONFIG.reconnectIntervals.length) {
        if (DOM.btnReconnect) DOM.btnReconnect.style.display = 'inline-block';
        setConnectionStatus('DISCONNECTED', 'Connection lost. Click Reconnect to retry');
        return;
      }

      const delay = CONFIG.reconnectIntervals[State.reconnectAttempt];
      State.reconnectAttempt++;
      setConnectionStatus('RECONNECTING', `Reconnecting (${State.reconnectAttempt}/5)...`);

      State.reconnectTimer = setTimeout(function () {
        State.reconnectTimer = null;
        WSClient.connect();
      }, delay);
    }
  };

  function setConnectionStatus(state, text) {
    State.connectionState = state;
    DOM.statusText.textContent = text;
    DOM.connStatusCapsule.className = 'status-capsule status-' + state.toLowerCase();
  }

  function updateCursorStatusUI(pos) {
    if (pos) {
      DOM.cursorInfo.textContent = `Ln ${pos.lineNumber}, Col ${pos.column}`;
    }
  }

  function updateSelectionStatusUI(sel) {
    if (sel && !sel.isEmpty()) {
      DOM.selectionInfo.textContent = `${Math.abs(sel.endLineNumber - sel.startLineNumber) + 1} lines selected`;
    } else {
      DOM.selectionInfo.textContent = 'No selection';
    }
  }

  // ==========================================
  // 13. Application Entrypoint
  // ==========================================
  bindUIEvents();
  initMonacoEditor();
})();
