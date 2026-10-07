/**
 * ============================================================================
 * EduPlus 教学协同编辑器 - 学生端核心引擎 (app.js)
 * 模块职责：
 * 1. 离线 Monaco Editor 环境初始化与个性化橙色主题定义
 * 2. 老师双光标/选区渲染引擎 (deltaDecorations API + 视口平滑跟随)
 * 3. 学生光标 30ms 节流上报与增量编辑监听 (带 isApplyingRemoteUpdate 门闩锁)
 * 4. WebSocket 指数退避重连状态机与离线暂存队列
 * ============================================================================
 */

(function () {
  'use strict';

  // ==========================================
  // 1. 全局配置与状态常量
  // ==========================================
  const CONFIG = {
    // 默认连接本地 IDEA 插件内置的 WebSocket 端口（可通过 URL query 参数重载）
    wsUrl: getWsUrl(),
    cursorThrottleMs: 30,         // 光标 30ms 节流
    reconnectIntervals: [1000, 2000, 4000, 8000, 10000], // 5次指数退避
    heartbeatIntervalMs: 5000,    // 5秒心跳 Ping/Pong
    initialLanguage: 'java',
    initialCode: `// 欢迎进入 EduPlus 实时协同课堂
public class Main {
    public static void main(String[] args) {
        // 等待老师连接与分发代码...
        System.out.println("EduPlus Collaborative Classroom Ready.");
    }
}
`
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
    
    // 挂载到 window 供页面 UI 读取
    window.CLIENT_META = { roomId, studentId, studentName };
    
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${hostname}:${wsPort}/ws/collab?roomId=${roomId}&userId=${studentId}&role=student`;
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
    teacherPresenceBadge: document.getElementById('teacherPresenceBadge'),
    bottomStatusbar: document.getElementById('bottom-statusbar')
  };

  // ==========================================
  // 3. 核心协同运行时状态
  // ==========================================
  const State = {
    editor: null,                       // Monaco Editor 实例
    isApplyingRemoteUpdate: false,      // 防循环同步的互斥门闩标志
    teacherDecorations: [],             // 老师 deltaDecorations 句柄缓存
    isReadOnlyLocked: false,            // 是否被老师强行全局只读锁定
    followTeacherView: true,            // 是否跟随老师视口滚动
    
    // WebSocket 状态机
    ws: null,
    connectionState: 'DISCONNECTED',    // 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED'
    reconnectAttempt: 0,
    reconnectTimer: null,
    heartbeatTimer: null,
    lastPingTimestamp: 0,
    offlineQueue: []                    // 离线消息缓冲队列
  };

  // ==========================================
  // 4. Monaco Editor 本地化集成与橙色主题配置
  // ==========================================
  function initMonacoEditor() {
    // 配置 Monaco 本地化 Worker 加载策略，断网秒开，禁止任何外网 CDN 探测
    window.MonacoEnvironment = {
      getWorkerUrl: function (moduleId, label) {
        // 利用内联 Blob Worker 转发，杜绝同源策略和本地 file:// 协议 Worker 加载限制
        const workerScript = `
          self.MonacoEnvironment = { baseUrl: '${window.location.origin}/vs' };
          importScripts('${window.location.origin}/vs/base/worker/workerMain.js');
        `;
        return `data:text/javascript;charset=utf-8,${encodeURIComponent(workerScript)}`;
      }
    };

    // 配置 AMD loader 基础路径
    const vsPath = window.__monaco_base || './vs';
    if (window.require) {
      window.require.config({
        paths: { vs: vsPath }
      });

      window.require(['vs/editor/editor.main'], function () {
        // 1. 定义学生个性化橙色主题（与老师高保真科技蓝形成鲜明反差）
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
            // 学生自身光标：明亮活力橙
            'editorCursor.foreground': '#ff9800',
            // 学生自身选区：半透明橙
            'editor.selectionBackground': '#ff980040',
            'editor.inactiveSelectionBackground': '#ff980026',
            'editor.selectionHighlightBackground': '#ff98002a',
            'editorLineNumber.activeForeground': '#ff9800',
            'editorLineNumber.foreground': '#5a5a5a'
          }
        });

        // 2. 创建编辑器实例
        State.editor = monaco.editor.create(document.getElementById('monaco-host'), {
          value: CONFIG.initialCode,
          language: CONFIG.initialLanguage,
          theme: 'student-orange-dark',
          automaticLayout: true,         // 响应式自适应容器宽度与高度
          fontSize: 14,
          fontFamily: "'Fira Code', 'JetBrains Mono', Consolas, 'Courier New', monospace",
          fontLigatures: true,
          tabSize: 4,
          renderWhitespace: 'selection',
          minimap: { enabled: true, side: 'right' },
          scrollBeyondLastLine: false,
          smoothScrolling: true,
          cursorBlinking: 'smooth',
          cursorSmoothCaretAnimation: 'on'
        });

        // 3. 绑定 Monaco 客户端事件
        bindMonacoEvents();

        // 4. 编辑器准备就绪后，启动 WebSocket 协同通道
        WSClient.connect();
      });
    } else {
      console.warn('Monaco loader not found, falling back to mock mode for offline testing');
      WSClient.connect();
    }
  }

  // ==========================================
  // 5. 双光标与选区渲染引擎 (老师端可视化)
  // ==========================================
  const RemoteCursorEngine = {
    /**
     * 渲染或更新老师光标与选区
     * @param {Object} data - { lineNumber, column, selection: { startLine, startCol, endLine, endCol }, isTyping }
     */
    updateTeacherCursor: function (data) {
      if (!State.editor) return;

      const newDecorations = [];
      const { lineNumber, column, selection } = data;

      // 1. 如果存在多字符选区 (Selection)
      const hasSelection = selection &&
        (selection.startLine !== selection.endLine || selection.startCol !== selection.endCol);

      if (hasSelection) {
        newDecorations.push({
          range: new monaco.Range(
            selection.startLine,
            selection.startCol,
            selection.endLine,
            selection.endCol
          ),
          options: {
            className: 'teacher-selection',
            stickiness: monaco.editor.TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
            zIndex: 50
          }
        });
      }

      // 2. 渲染老师单点光标条 (带 "👨‍🏫 老师" 悬浮气泡徽章)
      newDecorations.push({
        range: new monaco.Range(lineNumber, column, lineNumber, column),
        options: {
          className: 'teacher-cursor',
          beforeContentClassName: 'teacher-cursor-badge',
          stickiness: monaco.editor.TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
          zIndex: 100
        }
      });

      // 3. 执行 Monaco 高性能差量渲染 (deltaDecorations)
      State.teacherDecorations = State.editor.deltaDecorations(
        State.teacherDecorations,
        newDecorations
      );

      // 4. 视口平滑跟随 (Follow Viewport) 与折叠代码安全展开
      if (State.followTeacherView) {
        const targetPos = new monaco.Position(lineNumber, column);

        // 如果该行处于代码折叠块内部，安全检查并平滑滚动到可见区域
        try {
          State.editor.revealPositionInCenterIfOutsideViewport(
            targetPos,
            monaco.editor.ScrollType.Smooth
          );
        } catch (err) {
          console.warn('Failed to reveal position:', err);
        }
      }

      // 更新底栏老师在线状态
      if (DOM.teacherPresenceBadge) {
        DOM.teacherPresenceBadge.innerHTML = `<span class="teacher-dot"></span> 老师活跃 (L:${lineNumber}, C:${column})`;
      }
    },

    /** 清理老师的光标渲染（如老师离线时） */
    clearTeacherCursor: function () {
      if (!State.editor || State.teacherDecorations.length === 0) return;
      State.teacherDecorations = State.editor.deltaDecorations(State.teacherDecorations, []);
      if (DOM.teacherPresenceBadge) {
        DOM.teacherPresenceBadge.innerHTML = `<span class="teacher-dot" style="background:#858585;box-shadow:none;"></span> 老师离线`;
      }
    }
  };

  // ==========================================
  // 6. 编辑器事件捕获、节流与远端防回环门闩
  // ==========================================
  function bindMonacoEvents() {
    const editor = State.editor;

    // (1) 30ms 节流上报 student_cursor
    const throttledCursorReport = throttle(function (cursorPos, selection) {
      if (State.connectionState !== 'CONNECTED') return;

      const payload = {
        type: 'student_cursor',
        roomId: window.CLIENT_META.roomId,
        userId: window.CLIENT_META.studentId,
        userName: window.CLIENT_META.studentName,
        timestamp: Date.now(),
        cursor: {
          lineNumber: cursorPos.lineNumber,
          column: cursorPos.column
        },
        selection: selection ? {
          startLine: selection.startLineNumber,
          startCol: selection.startColumn,
          endLine: selection.endLineNumber,
          endCol: selection.endColumn
        } : null
      };

      WSClient.send(payload);
    }, CONFIG.cursorThrottleMs);

    // 监听光标位置变动
    editor.onDidChangeCursorPosition(function (e) {
      const pos = e.position;
      DOM.cursorInfo.innerText = `行 ${pos.lineNumber}, 列 ${pos.column}`;
      throttledCursorReport(pos, editor.getSelection());
    });

    // 监听选区变动
    editor.onDidChangeCursorSelection(function (e) {
      const sel = e.selection;
      if (sel.isEmpty()) {
        DOM.selectionInfo.innerText = '未选择';
      } else {
        const count = Math.abs(sel.endColumn - sel.startColumn);
        DOM.selectionInfo.innerText = `已选 ${count} 字符`;
      }
      throttledCursorReport(editor.getPosition(), sel);
    });

    // (2) 键盘输入事件监听与增量/全量同步 (带 isApplyingRemoteUpdate 防死循环门闩)
    editor.onDidChangeModelContent(function (e) {
      // 关键拦截：若是远端更新导致的内容变更，直接跳过，切断死循环回环！
      if (State.isApplyingRemoteUpdate) {
        return;
      }

      // 如果当前处于只读锁定状态，则不产生发送
      if (State.isReadOnlyLocked) {
        return;
      }

      // 提取增量变更包 (Incremental Changes)
      const changes = e.changes.map(ch => ({
        range: {
          startLine: ch.range.startLineNumber,
          startCol: ch.range.startColumn,
          endLine: ch.range.endLineNumber,
          endCol: ch.range.endColumn
        },
        rangeLength: ch.rangeLength,
        rangeOffset: ch.rangeOffset,
        text: ch.text
      }));

      const payload = {
        type: 'student_edit_delta',
        roomId: window.CLIENT_META.roomId,
        userId: window.CLIENT_META.studentId,
        versionId: e.versionId,
        timestamp: Date.now(),
        changes: changes
      };

      WSClient.send(payload);
    });
  }

  // ==========================================
  // 7. 远端数据应用 (带门闩锁安全机制)
  // ==========================================
  const RemoteApplyEngine = {
    /** 接收并应用老师/服务器的全量代码同步 */
    applyFullSync: function (content, language) {
      if (!State.editor) return;

      State.isApplyingRemoteUpdate = true;
      try {
        const model = State.editor.getModel();
        if (language && model.getLanguageId() !== language) {
          monaco.editor.setModelLanguage(model, language);
          document.getElementById('syntaxInfo').innerText = language;
        }
        model.setValue(content);
      } finally {
        // 下一微任务帧安全释放门闩
        setTimeout(() => {
          State.isApplyingRemoteUpdate = false;
        }, 0);
      }
    },

    /** 接收并应用老师端的增量编辑操作 */
    applyTeacherEdits: function (changes) {
      if (!State.editor || !changes || changes.length === 0) return;

      State.isApplyingRemoteUpdate = true;
      try {
        const monacoEdits = changes.map(ch => ({
          range: new monaco.Range(
            ch.range.startLine,
            ch.range.startCol,
            ch.range.endLine,
            ch.range.endCol
          ),
          text: ch.text,
          forceMoveMarkers: true
        }));

        State.editor.executeEdits('teacher-remote-source', monacoEdits);
      } finally {
        setTimeout(() => {
          State.isApplyingRemoteUpdate = false;
        }, 0);
      }
    },

    /** 设置只读模式状态（老师演示模式锁定） */
    setReadOnlyLock: function (isLocked, reason) {
      State.isReadOnlyLocked = isLocked;
      if (State.editor) {
        State.editor.updateOptions({ readOnly: isLocked });
      }

      if (isLocked) {
        DOM.lockIndicator.className = 'lock-indicator is-locked';
        DOM.lockIcon.innerText = '🔒';
        DOM.lockText.innerText = reason || '老师正在演示 (只读锁定)';
        DOM.bottomStatusbar.classList.add('is-teacher-active');
      } else {
        DOM.lockIndicator.className = 'lock-indicator is-unlocked';
        DOM.lockIcon.innerText = '🔓';
        DOM.lockText.innerText = '自主编辑模式';
        DOM.bottomStatusbar.classList.remove('is-teacher-active');
      }
    }
  };

  // ==========================================
  // 8. WebSocket 容错与指数退避重连状态机
  // ==========================================
  const WSClient = {
    connect: function () {
      if (State.ws && (State.ws.readyState === WebSocket.OPEN || State.ws.readyState === WebSocket.CONNECTING)) {
        return;
      }

      this.updateState('CONNECTING');

      try {
        State.ws = new WebSocket(CONFIG.wsUrl);
      } catch (err) {
        console.error('WebSocket 初始化异常:', err);
        this.handleDisconnect();
        return;
      }

      State.ws.onopen = this.onOpen.bind(this);
      State.ws.onmessage = this.onMessage.bind(this);
      State.ws.onerror = this.onError.bind(this);
      State.ws.onclose = this.onClose.bind(this);
    },

    onOpen: function () {
      console.log('[WS] 连接建立成功');
      State.reconnectAttempt = 0;
      this.updateState('CONNECTED');

      // 握手包：注册学生客户端身份并请求全量快照同步
      this.send({
        type: 'client_hello',
        roomId: window.CLIENT_META.roomId,
        userId: window.CLIENT_META.studentId,
        userName: window.CLIENT_META.studentName,
        role: 'student',
        timestamp: Date.now()
      });

      // 启动心跳 Ping
      this.startHeartbeat();

      // 冲刷断线期间积攒的离线队列
      this.flushOfflineQueue();
    },

    onMessage: function (event) {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch (e) {
        console.error('无效 JSON 消息:', event.data);
        return;
      }

      switch (msg.type) {
        case 'heartbeat_pong':
          this.handlePong(msg);
          break;

        case 'full_sync_snapshot':
          // 权威全量快照同步
          RemoteApplyEngine.applyFullSync(msg.content, msg.language);
          if (msg.readOnly !== undefined) {
            RemoteApplyEngine.setReadOnlyLock(msg.readOnly, msg.lockReason);
          }
          break;

        case 'teacher_cursor_broadcast':
          // 老师光标与选区广播
          RemoteCursorEngine.updateTeacherCursor(msg.data);
          break;

        case 'teacher_edit_broadcast':
          // 老师增量代码编辑
          RemoteApplyEngine.applyTeacherEdits(msg.changes);
          break;

        case 'teacher_lock_state':
          // 老师全局加锁/解锁广播
          RemoteApplyEngine.setReadOnlyLock(msg.isLocked, msg.reason);
          break;

        case 'teacher_offline':
          // 老师离开/离线
          RemoteCursorEngine.clearTeacherCursor();
          break;

        default:
          console.log('[WS] 收到未处理消息类型:', msg.type);
      }
    },

    onError: function (err) {
      console.warn('[WS] 通道发生异常:', err);
    },

    onClose: function () {
      console.warn('[WS] 连接关闭');
      this.stopHeartbeat();
      RemoteCursorEngine.clearTeacherCursor();
      this.handleDisconnect();
    },

    handleDisconnect: function () {
      if (State.reconnectAttempt < CONFIG.reconnectIntervals.length) {
        this.updateState('RECONNECTING');
        const delay = CONFIG.reconnectIntervals[State.reconnectAttempt];
        State.reconnectAttempt++;
        console.log(`[WS] 准备第 ${State.reconnectAttempt} 次重连，延迟: ${delay}ms`);

        clearTimeout(State.reconnectTimer);
        State.reconnectTimer = setTimeout(() => {
          this.connect();
        }, delay);
      } else {
        // 耗尽 5 次重试后停机并提示手动重连
        this.updateState('DISCONNECTED');
        console.error('[WS] 重试次数已耗尽，等待用户手动重连');
      }
    },

    manualReconnect: function () {
      State.reconnectAttempt = 0;
      clearTimeout(State.reconnectTimer);
      this.connect();
    },

    send: function (payload) {
      const jsonStr = JSON.stringify(payload);
      if (State.ws && State.ws.readyState === WebSocket.OPEN) {
        State.ws.send(jsonStr);
      } else {
        // 处于离线状态时，非高频光标的变更入暂存队列
        if (payload.type !== 'student_cursor') {
          State.offlineQueue.push(jsonStr);
          if (State.offlineQueue.length > 100) State.offlineQueue.shift(); // 防内存溢出
        }
      }
    },

    flushOfflineQueue: function () {
      if (State.offlineQueue.length > 0) {
        console.log(`[WS] 正在冲刷离线队列 (${State.offlineQueue.length} 条)...`);
        while (State.offlineQueue.length > 0 && State.ws && State.ws.readyState === WebSocket.OPEN) {
          const item = State.offlineQueue.shift();
          State.ws.send(item);
        }
      }
    },

    startHeartbeat: function () {
      this.stopHeartbeat();
      State.heartbeatTimer = setInterval(() => {
        if (State.ws && State.ws.readyState === WebSocket.OPEN) {
          State.lastPingTimestamp = performance.now();
          this.send({ type: 'heartbeat_ping', timestamp: Date.now() });
        }
      }, CONFIG.heartbeatIntervalMs);
    },

    stopHeartbeat: function () {
      if (State.heartbeatTimer) {
        clearInterval(State.heartbeatTimer);
        State.heartbeatTimer = null;
      }
    },

    handlePong: function () {
      if (State.lastPingTimestamp > 0) {
        const rtt = Math.round(performance.now() - State.lastPingTimestamp);
        DOM.latencyVal.innerText = `${rtt} ms`;
        // 根据延迟调整指示灯颜色
        const dot = DOM.networkLatency.querySelector('.latency-dot');
        if (dot) {
          dot.style.backgroundColor = rtt < 60 ? '#4caf50' : (rtt < 180 ? '#ffb300' : '#f44336');
        }
      }
    },

    updateState: function (newState) {
      State.connectionState = newState;
      DOM.connStatusCapsule.className = 'status-capsule';
      DOM.btnReconnect.style.display = 'none';

      switch (newState) {
        case 'CONNECTED':
          DOM.connStatusCapsule.classList.add('status-connected');
          DOM.statusText.innerText = '协同中';
          break;

        case 'RECONNECTING':
          DOM.connStatusCapsule.classList.add('status-reconnecting');
          DOM.statusText.innerText = `网络波动重连中 (${State.reconnectAttempt}/${CONFIG.reconnectIntervals.length})`;
          break;

        case 'DISCONNECTED':
          DOM.connStatusCapsule.classList.add('status-disconnected');
          DOM.statusText.innerText = '已断开连接';
          DOM.btnReconnect.style.display = 'inline-block';
          DOM.latencyVal.innerText = '-- ms';
          break;

        case 'CONNECTING':
        default:
          DOM.connStatusCapsule.classList.add('status-reconnecting');
          DOM.statusText.innerText = '正在连接...';
          break;
      }
    }
  };

  // ==========================================
  // 9. 辅助工具函数 (Throttle 防抖节流)
  // ==========================================
  function throttle(func, wait) {
    let timeout = null;
    let previous = 0;
    return function (...args) {
      const now = Date.now();
      const remaining = wait - (now - previous);
      if (remaining <= 0 || remaining > wait) {
        if (timeout) {
          clearTimeout(timeout);
          timeout = null;
        }
        previous = now;
        func.apply(this, args);
      } else if (!timeout) {
        timeout = setTimeout(() => {
          previous = Date.now();
          timeout = null;
          func.apply(this, args);
        }, remaining);
      }
    };
  }

  // ==========================================
  // 10. 界面交互事件绑定与生命周期初始化
  // ==========================================
  function setupUIBindings() {
    // 渲染房间与学生标识
    DOM.roomBadge.innerText = `课堂: ${window.CLIENT_META.roomId}`;
    DOM.userBadge.innerText = `学生: ${window.CLIENT_META.studentName}`;

    // 跟随老师视口开关
    DOM.followTeacherCheckbox.addEventListener('change', function (e) {
      State.followTeacherView = e.target.checked;
    });

    // 手动重连按钮
    DOM.btnReconnect.addEventListener('click', function () {
      WSClient.manualReconnect();
    });

    // 窗口尺寸变化自适应
    window.addEventListener('resize', function () {
      if (State.editor) {
        State.editor.layout();
      }
    });
  }

  // 入口启动
  window.addEventListener('DOMContentLoaded', function () {
    setupUIBindings();
    initMonacoEditor();
  });

})();
