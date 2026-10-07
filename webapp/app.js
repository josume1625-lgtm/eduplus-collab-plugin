/**
 * ============================================================================
 * EduPlus 教学协同编辑器 - 学生端核心引擎 (app.js)
 * 模块职责：
 * 1. 本地 Monaco Editor 环境初始化与个性化橙色主题
 * 2. 项目工作区目录树渲染与点击切换文件 (Directory Tree)
 * 3. 多页签栏渲染与实时联动切换 (Multi-tabs)
 * 4. 老师端程序运行输出实时捕获展示控制台 (Execution Output Console)
 * 5. 老师高保真蓝色双光标/选区渲染引擎 (deltaDecorations API + 视口平滑跟随)
 * 6. 学生光标 30ms 节流上报与增量编辑监听 (带 isApplyingRemoteUpdate 门闩锁)
 * 7. 本地局域网自适应 WebSocket 指数退避重连状态机
 * ============================================================================
 */

(function () {
  'use strict';

  // ==========================================
  // 1. 全局配置与状态常量
  // ==========================================
  const CONFIG = {
    wsUrl: getWsUrl(),
    cursorThrottleMs: 30,
    reconnectIntervals: [1000, 2000, 4000, 8000, 10000],
    heartbeatIntervalMs: 5000,
    initialLanguage: 'java',
    initialCode: `// 欢迎进入 EduPlus 实时教学协同工作台\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println("EduPlus 协同课堂已连接，等待老师代码同步...");\n    }\n}\n`
  };

  /** 从 URL 参数提取房间与用户信息，自动计算 WebSocket 端口 (HTTP 端口 + 1) */
  function getWsUrl() {
    const params = new URLSearchParams(window.location.search);
    const hostname = window.location.hostname || '127.0.0.1';
    const httpPort = parseInt(window.location.port || '8765', 10);
    const wsPort = params.get('wsPort') || (httpPort + 1);
    const roomId = params.get('roomId') || 'ROOM_101';
    const studentId = params.get('studentId') || ('stu_' + Math.floor(Math.random() * 9000 + 1000));
    const studentName = params.get('name') || ('学生_' + studentId.slice(-4));

    window.CLIENT_META = { roomId, studentId, studentName };

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${hostname}:${wsPort}/ws/collab?roomId=${roomId}&userId=${studentId}&name=${encodeURIComponent(studentName)}&role=student`;
  }

  // ==========================================
  // 2. DOM 元素缓存
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

    // 目录树与页签 DOM
    toggleSidebarBtn: document.getElementById('toggleSidebarBtn'),
    fileTreeSidebar: document.getElementById('file-tree-sidebar'),
    fileTreeContainer: document.getElementById('file-tree-container'),
    refreshTreeBtn: document.getElementById('refreshTreeBtn'),
    editorTabs: document.getElementById('editor-tabs'),

    // 运行控制台与申请运行 DOM
    btnStudentRun: document.getElementById('btnStudentRun'),
    toggleTerminalBtn: document.getElementById('toggleTerminalBtn'),
    terminalPanel: document.getElementById('terminal-panel'),
    runStatusBadge: document.getElementById('runStatusBadge'),
    clearTerminalBtn: document.getElementById('clearTerminalBtn'),
    collapseTerminalBtn: document.getElementById('collapseTerminalBtn'),
    terminalContent: document.getElementById('terminal-content')
  };

  // ==========================================
  // 3. 核心协同运行时状态
  // ==========================================
  const State = {
    editor: null,                       // Monaco Editor 实例
    isApplyingRemoteUpdate: false,      // 防循环同步门闩标志
    teacherDecorations: [],             // 老师光标高亮句柄
    isReadOnlyLocked: false,            // 老师只读锁
    followTeacherView: true,            // 跟随老师视口滚动
    currentFilePath: '',               // 当前活动文件路径
    tabs: [],                           // 当前打开的页签列表
    projectTree: [],                    // 项目目录树数据

    // WebSocket 状态机
    ws: null,
    connectionState: 'DISCONNECTED',
    reconnectAttempt: 0,
    reconnectTimer: null,
    heartbeatTimer: null,
    lastPingTimestamp: 0
  };

  // ==========================================
  // 4. Monaco Editor 本地化集成
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

    // 1. 定义学生个性化橙色主题
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

    // 2. 创建 Monaco 实例
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

    // 3. 绑定编辑器事件监听
    bindMonacoEvents();

    // 4. 连接协同 WebSocket 管道
    WSClient.connect();
  }

  // ==========================================
  // 5. 编辑器事件绑定与学生操作节流上报
  // ==========================================
  function bindMonacoEvents() {
    const editor = State.editor;

    let cursorThrottleTimer = null;
    let pendingCursorEvent = null;

    // 监听光标移动与选区变动
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

    // 监听学生键盘编辑
    editor.onDidChangeModelContent(function (e) {
      if (State.isApplyingRemoteUpdate) return;
      if (State.isReadOnlyLocked) return;

      e.changes.forEach(function (change) {
        WSClient.send({
          type: 'code_delta',
          payload: {
            rangeOffset: change.rangeOffset,
            oldLength: change.rangeLength,
            text: change.text
          }
        });
      });
    });
  }

  // ==========================================
  // 6. UI 交互组件事件绑定 (目录树、页签、运行控制台)
  // ==========================================
  function bindUIEvents() {
    // 课堂信息展示
    if (window.CLIENT_META) {
      DOM.roomBadge.textContent = '课堂: ' + window.CLIENT_META.roomId;
      DOM.userBadge.textContent = '学生: ' + window.CLIENT_META.studentName;
    }

    // 跟随老师视口开关
    DOM.followTeacherCheckbox.addEventListener('change', function (e) {
      State.followTeacherView = e.target.checked;
    });

    // 手动重连按钮
    DOM.btnReconnect.addEventListener('click', function () {
      WSClient.connect();
    });

    // 侧边栏折叠切换
    DOM.toggleSidebarBtn.addEventListener('click', function () {
      DOM.fileTreeSidebar.classList.toggle('collapsed');
      setTimeout(() => State.editor && State.editor.layout(), 220);
    });

    // 刷新目录树按钮
    DOM.refreshTreeBtn.addEventListener('click', function () {
      DOM.fileTreeContainer.innerHTML = '<div class="tree-loading">正在刷新目录...</div>';
      WSClient.send({ type: 'refresh_tree_request' });
    });

    // 运行结果面板切换按钮
    DOM.toggleTerminalBtn.addEventListener('click', function () {
      DOM.terminalPanel.classList.toggle('hidden');
      setTimeout(() => State.editor && State.editor.layout(), 220);
    });

    // 折叠运行控制台
    DOM.collapseTerminalBtn.addEventListener('click', function () {
      const isCol = DOM.terminalPanel.classList.toggle('collapsed');
      DOM.collapseTerminalBtn.textContent = isCol ? '展开' : '折叠';
      setTimeout(() => State.editor && State.editor.layout(), 220);
    });

    // 清屏运行输出
    DOM.clearTerminalBtn.addEventListener('click', function () {
      DOM.terminalContent.innerHTML = '<div class="terminal-line terminal-system">控制台输出已清空。</div>';
    });

    // 学生端申请运行当前代码
    if (DOM.btnStudentRun) {
      DOM.btnStudentRun.addEventListener('click', function () {
        if (!State.ws || State.ws.readyState !== WebSocket.OPEN) {
          alert('协同未连接，无法发起运行申请');
          return;
        }

        DOM.btnStudentRun.disabled = true;
        DOM.btnStudentRun.textContent = '⏳ 等待老师确认...';

        const reqPath = State.currentFilePath || '当前文件';
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
        line.textContent = `[${timeStr}] === ⏳ 已向老师申请运行代码【${reqPath}】，等待老师确认中... ===`;
        DOM.terminalContent.appendChild(line);
        DOM.terminalContent.scrollTop = DOM.terminalContent.scrollHeight;

        // 15 秒超时自动复位
        setTimeout(function () {
          if (DOM.btnStudentRun.disabled) {
            DOM.btnStudentRun.disabled = false;
            DOM.btnStudentRun.textContent = '▶ 申请运行代码';
          }
        }, 15000);
      });
    }
  }

  function handleStudentRunResponse(payload) {
    if (!payload) return;
    if (DOM.btnStudentRun) {
      DOM.btnStudentRun.disabled = false;
      DOM.btnStudentRun.textContent = '▶ 申请运行代码';
    }

    const timeStr = new Date().toLocaleTimeString();
    const line = document.createElement('div');
    if (payload.approved) {
      line.className = 'terminal-line terminal-system';
      line.textContent = `[${timeStr}] === ✅ 老师已批准运行申请，程序正在启动执行... ===`;
    } else {
      line.className = 'terminal-line terminal-stderr';
      line.textContent = `[${timeStr}] === ❌ 老师拒绝了此次运行申请。 ===`;
    }
    DOM.terminalContent.appendChild(line);
    DOM.terminalContent.scrollTop = DOM.terminalContent.scrollHeight;
  }

  // ==========================================
  // 7. 目录树渲染引擎 (Directory Tree)
  // ==========================================
  function renderDirectoryTree(treeData, projectName) {
    State.projectTree = treeData;
    const container = DOM.fileTreeContainer;
    container.innerHTML = '';

    if (!treeData || treeData.length === 0) {
      container.innerHTML = '<div class="tree-loading">工作区为空或无文件</div>';
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
    // 请求服务端读取对应文件内容
    WSClient.send({
      type: 'open_file_request',
      payload: { path: filePath }
    });
  }

  // ==========================================
  // 8. 多页签栏渲染与联动 (Multi Tabs)
  // ==========================================
  function renderTabs(tabs, activePath) {
    State.tabs = tabs || [];
    if (activePath) State.currentFilePath = activePath;

    const container = DOM.editorTabs;
    container.innerHTML = '';

    if (!tabs || tabs.length === 0) {
      const single = document.createElement('div');
      single.className = 'tab-item active';
      single.innerHTML = `<span class="tab-icon">📄</span><span class="tab-name">${State.currentFilePath || 'Active.java'}</span>`;
      container.appendChild(single);
      return;
    }

    tabs.forEach(tab => {
      const tabEl = document.createElement('div');
      tabEl.className = 'tab-item ' + (tab.active || tab.path === State.currentFilePath ? 'active' : '');
      tabEl.dataset.path = tab.path;
      tabEl.innerHTML = `<span class="tab-icon">${getFileIcon(tab.name)}</span><span class="tab-name">${tab.name}</span>`;

      tabEl.addEventListener('click', function () {
        openFile(tab.path);
      });

      container.appendChild(tabEl);
    });

    // 同步更新左侧树的高亮
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
  // 9. 运行结果控制台输出同步 (Execution Output)
  // ==========================================
  function handleExecutionStatus(payload) {
    if (!payload) return;
    const status = payload.status;
    const terminal = DOM.terminalContent;

    if (status === 'started') {
      DOM.runStatusBadge.textContent = '🚀 运行中: ' + (payload.title || 'App');
      DOM.runStatusBadge.className = 'badge-run-status running';

      const line = document.createElement('div');
      line.className = 'terminal-line terminal-system';
      const timeStr = new Date().toLocaleTimeString();
      line.textContent = `[${timeStr}] === 🚀 开始运行: ${payload.title || '程序'} ===`;
      terminal.appendChild(line);

      // 展开终端面板
      DOM.terminalPanel.classList.remove('collapsed', 'hidden');
      setTimeout(() => State.editor && State.editor.layout(), 100);
    } else if (status === 'terminated') {
      const exitCode = payload.exitCode !== undefined ? payload.exitCode : 0;
      DOM.runStatusBadge.textContent = `✅ 退出 (代码: ${exitCode})`;
      DOM.runStatusBadge.className = 'badge-run-status ' + (exitCode === 0 ? '' : 'error');

      const line = document.createElement('div');
      line.className = 'terminal-line terminal-system';
      const timeStr = new Date().toLocaleTimeString();
      line.textContent = `[${timeStr}] === 进程结束 (exit code: ${exitCode}) ===\n`;
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
  // 10. 老师双光标/选区叠加渲染 (deltaDecorations)
  // ==========================================
  function renderTeacherCursor(payload) {
    if (!State.editor || !payload) return;
    const line = payload.line;
    const ch = payload.ch;
    const teacherName = payload.teacherName || '老师';

    const newDecorations = [];

    // 1. 蓝色高亮光标垂直条 + 姓名气泡标签
    newDecorations.push({
      range: new monaco.Range(line, ch, line, ch),
      options: {
        className: 'teacher-cursor-line',
        hoverMessage: { value: `**${teacherName}** 当前光标位置` },
        after: {
          content: `👨‍🏫 ${teacherName}`,
          inlineClassName: 'teacher-cursor-badge'
        }
      }
    });

    // 2. 老师选区半透明蓝色高亮
    if (payload.selectionStart && payload.selectionEnd) {
      newDecorations.push({
        range: new monaco.Range(
          payload.selectionStart.line,
          payload.selectionStart.ch,
          payload.selectionEnd.line,
          payload.selectionEnd.ch
        ),
        options: {
          className: 'teacher-selection-highlight',
          isWholeLine: false
        }
      });
    }

    // 原子更新装饰句柄
    State.teacherDecorations = State.editor.deltaDecorations(
      State.teacherDecorations,
      newDecorations
    );

    // 视口平滑跟随
    if (State.followTeacherView) {
      State.editor.revealPositionInCenterIfOutsideViewport(
        { lineNumber: line, column: ch },
        monaco.editor.ScrollType.Smooth
      );
    }
  }

  // ==========================================
  // 11. 代码全量与增量同步处理
  // ==========================================
  function handleCodeFull(payload) {
    if (!State.editor || !payload) return;
    State.isApplyingRemoteUpdate = true;
    try {
      const currentModel = State.editor.getModel();
      const newLang = payload.language || 'plaintext';
      if (currentModel) {
        currentModel.setValue(payload.content || '');
        monaco.editor.setModelLanguage(currentModel, newLang);
      } else {
        const newModel = monaco.editor.createModel(payload.content || '', newLang);
        State.editor.setModel(newModel);
      }

      const filePath = payload.filePath || 'Active.java';
      State.currentFilePath = filePath;
      DOM.fileInfo.textContent = '📄 ' + (payload.fileName || filePath);
      DOM.syntaxInfo.textContent = newLang.toUpperCase();

      updateTreeActiveFile(filePath);
    } finally {
      State.isApplyingRemoteUpdate = false;
    }
  }

  function handleCodeDelta(payload) {
    if (!State.editor || !payload) return;
    // 忽略与当前活动查看文件不匹配的差量更新
    if (payload.filePath && State.currentFilePath && payload.filePath !== State.currentFilePath) {
      return;
    }
    State.isApplyingRemoteUpdate = true;
    try {
      const model = State.editor.getModel();
      if (!model) return;

      const startPos = model.getPositionAt(payload.rangeOffset);
      const endPos = model.getPositionAt(payload.rangeOffset + (payload.oldLength || 0));

      model.applyEdits([{
        range: new monaco.Range(startPos.lineNumber, startPos.column, endPos.lineNumber, endPos.column),
        text: payload.text || '',
        forceMoveMarkers: true
      }]);
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
      DOM.lockText.textContent = '老师独占演示 (只读)';
    } else {
      DOM.lockIndicator.classList.remove('locked');
      DOM.lockIcon.textContent = '🔓';
      DOM.lockText.textContent = '自主编辑模式';
    }
  }

  // ==========================================
  // 12. WebSocket 通信客户端与容错重连状态机
  // ==========================================
  const WSClient = {
    connect: function () {
      if (State.ws && (State.ws.readyState === WebSocket.OPEN || State.ws.readyState === WebSocket.CONNECTING)) {
        return;
      }

      setConnectionStatus('CONNECTING', '正在连接协同服务...');

      try {
        State.ws = new WebSocket(CONFIG.wsUrl);
      } catch (err) {
        console.error('[WS] 创建连接失败:', err);
        WSClient.scheduleReconnect();
        return;
      }

      State.ws.onopen = function () {
        setConnectionStatus('CONNECTED', '教学协同进行中');
        State.reconnectAttempt = 0;
        if (DOM.btnReconnect) DOM.btnReconnect.style.display = 'none';
        WSClient.startHeartbeat();
      };

      State.ws.onmessage = function (event) {
        try {
          const msg = JSON.parse(event.data);
          WSClient.dispatchMessage(msg);
        } catch (e) {
          console.warn('[WS] 解析消息失败:', e);
        }
      };

      State.ws.onclose = function () {
        setConnectionStatus('DISCONNECTED', '连接已断开');
        WSClient.stopHeartbeat();
        WSClient.scheduleReconnect();
      };

      State.ws.onerror = function () {
        setConnectionStatus('DISCONNECTED', '网络波动/连接失败');
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
        setConnectionStatus('DISCONNECTED', '连接中断，请手动重试');
        return;
      }

      const delay = CONFIG.reconnectIntervals[State.reconnectAttempt];
      State.reconnectAttempt++;
      setConnectionStatus('RECONNECTING', `重连中 (${State.reconnectAttempt}/5)...`);

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
      DOM.cursorInfo.textContent = `行 ${pos.lineNumber}, 列 ${pos.column}`;
    }
  }

  function updateSelectionStatusUI(sel) {
    if (sel && !sel.isEmpty()) {
      DOM.selectionInfo.textContent = `已选 ${Math.abs(sel.endLineNumber - sel.startLineNumber) + 1} 行`;
    } else {
      DOM.selectionInfo.textContent = '未选择';
    }
  }

  // ==========================================
  // 13. 应用入口启动
  // ==========================================
  bindUIEvents();
  initMonacoEditor();
})();
