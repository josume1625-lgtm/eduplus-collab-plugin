# IDEA 师生实时协同教学插件：网页学生端 Monaco Editor 渲染与双光标可视化引擎方案

## 1. 网页学生端极简架构设计与零依赖离线秒开

### 1.1 零外部依赖部署策略 (Offline-First Zero External Dependency)
在高校计算机机房、封闭实验室内网环境中，机器通常被物理拔掉外网网线或配置严密的代理防火墙，若静态页面依赖 CDN（如 `cdnjs`, `jsdelivr`, `unpkg`），会导致 Monaco Editor 加载阻塞乃至崩溃白屏。

本方案采用 **“内置静态嵌入 + 本地回环自宿主 (127.0.0.1 Embedded Self-Hosting)”** 极简架构：
1. **静态资源精简打包进 IDEA 插件**：
   - 提取 Monaco Editor 核心包中 `min/vs` 目录，剔除不必要的语言支持（只保留 Java、Python、C/C++、JSON、HTML 等教学常见语言定义），体积压缩在 5MB 以内。
   - 目录存放在 IDEA 插件源码的 `src/main/resources/webapp/` 下：
     ```text
     src/main/resources/webapp/
     ├── index.html
     ├── style.css
     ├── app.js
     └── vs/
         ├── loader.js
         ├── editor/
         │   ├── editor.main.js
         │   ├── editor.main.css
         │   └── editor.main.nls.zh-cn.js
         ├── base/worker/workerMain.js
         └── basic-languages/ (常用语法高亮包)
     ```
2. **IDEA 插件轻量级内置 HTTP 服务**：
   - 利用 Netty 或 JDK 内置 `com.sun.net.httpserver.HttpServer`（零第三方依赖），在 IDEA 启动时绑定 `127.0.0.1:{dynamic_port}`。
   - 当学生在局域网内打开浏览器时，学生访问老师端 IDEA 暴露的 HTTP 端口（或学生本地由独立轻量客户端分发，通常在高校局域网教学场景下，老师端 IDEA 插件启动内嵌 HTTP/WebSocket Server，所有学生直接访问 `http://<TEACHER_IP>:<PORT>/` 即可秒开）。
   - 本地资源完全基于 HTTP 相对路径加载：`<script src="./vs/loader.js"></script>`。
3. **Monaco Web Worker 沙箱环境隔离与加载优化**：
   - 浏览器对 Web Worker 跨源及 `file://` 协议有严格同源安全策略。
   - 在 `app.js` 中重写 `window.MonacoEnvironment.getWorkerUrl`，使用内联 Blob 封装或者相对路径重定向，确保断网秒级初始化。

---

## 2. 双光标与选区可视化渲染机制 (核心教学亮点)

### 2.1 老师光标与选区的高保真渲染 (蓝色科技流)
教学场景下，学生的核心诉求是“时刻清楚老师正在看哪一段、改哪一行”。因此老师光标必须具备极高的辨识度与平滑度：
- **Monaco deltaDecorations API 差量注入**：
  Monaco 废弃了频繁修改 DOM 的传统做法，使用底层渲染管线的 `editor.deltaDecorations(oldDecorations, newDecorations)`。不仅拥有 60FPS 的渲染帧率，而且能完美贴合编辑器缩放、换行与字体度量。
- **呼吸灯光标条 (`teacher-cursor`)**：
  使用 CSS 伪类与动画实现 2.5px 宽的科技蓝垂直光标，附带 `0 0 8px rgba(33, 150, 243, 0.85)` 外发光微发亮呼吸效果（1.2s 周期）。
- **悬浮气泡徽章 (`teacher-cursor-badge`)**：
  在老师光标顶端紧贴悬浮一个 `👨‍🏫 老师` 胶囊徽章，下带倒三角指示针。使用 `transform: translateY(0)` 和 `z-index: 110`，在代码中移动时平滑浮动，绝不遮挡当前字符的视觉阅读。
- **半透明蓝色选区 (`teacher-selection`)**：
  当老师鼠标拖选代码块时，渲染背景色为 `rgba(33, 150, 243, 0.25)`，辅以上下边缘微线框，让被讲解的代码块具有醒目的投影聚焦感。

### 2.2 视口平滑跟随 (Follow Teacher Viewport) 与折叠代码安全展开
- **滚动边界处理**：
  当老师在行 200 进行代码书写，而学生页面正停留在行 1 时，学生端开启“跟随老师视口”开关。
  引擎调用 `editor.revealPositionInCenterIfOutsideViewport(position, monaco.editor.ScrollType.Smooth)`。当老师光标处于可见屏幕内时不产生突兀滚动；一旦老师跳出当前视口，平滑居中漫游，避免学生视觉眩晕。
- **折叠代码边界保护**：
  若老师光标落在被折叠的方法块中，系统通过判定当前行可见性，自动展开对应折叠区域，防止老师光标“神秘消失”。

### 2.3 学生自身光标与选区 (橙色个性化主题)
- 通过 `monaco.editor.defineTheme('student-orange-dark')` 定制学生专属主题：
  - 学生光标：明亮活力橙 `#FF9800`
  - 学生选区：半透明橙色 `rgba(255, 152, 0, 0.25)`
  - 学生行号高亮：橙色高亮
- 视觉心理学效果：冷暖色强烈对撞（老师蓝 vs 学生橙），多光标同屏无歧义。

---

## 3. 客户端事件捕获、节流与增量/全量同步

### 3.1 30ms 节流光标上报 (Student Cursor Throttle)
- 键盘移动和鼠标拖选会以高频触发 `onDidChangeCursorPosition` 和 `onDidChangeCursorSelection`。
- 采用高性能的时间戳差值节流算法 (`throttle(..., 30)`)，将光标广播控制在最大 33fps，既保证丝滑度，又防止局域网并发流量洪峰打垮服务端 WebSocket 吞吐。

### 3.2 避免远端同步死循环回环机制 (`isApplyingRemoteUpdate` 门闩互斥锁)
- **回环灾难场景**：
  老师输入字符 'A' -> WebSocket 下发到学生 -> 学生端 `model.setValue()` 或 `executeEdits()` -> 触发学生端 `onDidChangeModelContent` -> 学生端误以为自己输入了 'A' 并上报给服务器 -> 导致无限震荡死循环（Echo Loop）。
- **工程级解决方案：门闩互斥锁**：
  ```javascript
  let isApplyingRemoteUpdate = false;

  function applyRemoteChanges(changes) {
      isApplyingRemoteUpdate = true;
      try {
        editor.executeEdits('teacher-remote', changes);
      } finally {
        setTimeout(() => { isApplyingRemoteUpdate = false; }, 0);
      }
  }

  editor.onDidChangeModelContent((e) => {
      if (isApplyingRemoteUpdate) return; // 关键阻断！
      // 正常上报本地学生输入...
  });
  ```

---

## 4. 前端网络容错与重连状态机

### 4.1 指数退避重试 (Exponential Backoff State Machine)
- 状态转移图：
  ```text
  [DISCONNECTED] --(连接中)--> [CONNECTING] --(握手成功)--> [CONNECTED (协同中)]
                                    │                           │
                                (连接中断)                   (心跳超时/连接断开)
                                    ↓                           ↓
                      [RECONNECTING (网络波动)] <─────────────────┘
                                    │ (重试间隔: 1s, 2s, 4s, 8s, 10s)
                                    ├─[<=5次]──> 再次触发 connect()
                                    └─[>5次]───> [DISCONNECTED (已断线)] ──> 展示“手动重连”按钮
  ```
- 退避步进：`[1000, 2000, 4000, 8000, 10000]` ms。

### 4.2 离线消息缓冲队列 (Offline Queue) 与重连快照恢复
- 在网络闪断恢复期间，学生输入的非高频关键操作暂存于 `State.offlineQueue`。
- 重连成功（`onOpen`）触发：
  1. 发送 `client_hello` 重新注册会话。
  2. 服务端下发最新权威 `full_sync_snapshot`。
  3. 执行快照覆写后，排队冲刷（Flush）离线暂存变更。

### 4.3 状态指示条与“只读锁定”教学模式
- 顶部状态胶囊：
  - 🟢 绿色 `CONNECTED`：协同中，实时双向 Ping 延迟（例如 12ms）。
  - 🟡 黄色 `RECONNECTING`：网络波动重连中 (第 X/5 次)，带有呼吸灯预警。
  - 🔴 红色 `DISCONNECTED`：已断开连接，出现“手动重连”交互按钮。
- 老师控制权联动（Readonly Lock）：
  - 老师点击“全员演示模式”时，学生端编辑器立即切换 `readOnly: true`，顶部呈现 🔒 红色徽标，底部状态栏变更为演示深蓝。
  - 老师释放控制权后，切换为 🔓 自主编辑模式。
