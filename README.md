# EduPlus - IntelliJ IDEA 师生实时协同教学插件（正式落地版）

本项目是一款专注于编程教学、代码演示与师生互动的 IntelliJ IDEA 教学协同插件。
- **角色架构**：老师端 = 本地 IntelliJ IDEA 编辑器 ｜ 学生端 = 浏览器网页 (Monaco Editor)
- **本地服务**：内置嵌入式 Jetty 服务，严格仅暴露本地回环端口（`127.0.0.1:9876`），无需任何外部服务器或公网穿透
- **核心特色**：双光标可视化（老师蓝、学生橙）、弱网震荡容错（3s心跳/30ms节流/重连回放）、老师优先教学写锁、文件切换自动跟随。

---

## 一、详细技术规格书与设计文档
所有架构白皮书与技术规范已归档在 [`docs/`](./docs/)：
1. **[IDE 端事件监听与学生远程双光标叠加渲染引擎](./docs/IDE_COLLABORATION_ENGINE_DESIGN.md)**
   - `MarkupModel` + `CustomHighlighterRenderer` 纯 Overlay 无侵入双光标渲染
   - `RemoteApplyGuard` (`ThreadLocal` + `UserData`) 三层防循环回环屏障
   - `DocumentListener` / `CaretListener` 协程防抖 / `FileEditorManagerListener` 切文件广播
2. **[本地嵌入式 Jetty 服务、WebSocket 双向通信协议与弱网容错引擎](./docs/EMBEDDED_JETTY_AND_WEBSOCKET_ARCHITECTURE.md)**
   - 127.0.0.1 严格回环绑定与自适应端口探测 (`[9876, 9896]`)
   - 6 大统一 JSON 信令规范（`code_full`, `code_delta`, `cursor_teacher`, `cursor_student`, `heartbeat`, `reconnect_sync`）
   - 网络容错引擎（单调自增 `seqId`、30ms 节流、50ms 防抖、3s 双向心跳、500 容量 Replay Buffer、全量快照兜底）
   - 老师优先写锁机制（Teacher Priority Lock, 500ms 滑动窗口独占）
3. **[学生端 Monaco Editor 渲染与双光标可视化引擎](./docs/MONACO_COLLAB_ENGINE_SPEC.md)**
   - 零外部依赖离线架构（Monaco 资源内嵌于插件，无需外网 CDN）
   - 老师端蓝色呼吸灯光标条 + 👨‍🏫 老师浮动胶囊徽章 + 浅蓝半透明选区
   - 学生自身橙色高亮与 30ms 节流上报
   - 指数退避自动重连与离线操作队列
4. **[工程交付方案、5 阶段 WBS 表与测试矩阵](./docs/PROJECT_DELIVERY_PLAN.md)**
   - 16 天 5 阶段 WBS 任务分解表与阶段验收标准
   - 弱网混沌注入压测与 1500 行大文件性能矩阵

---

## 二、工程目录结构
```
e:\eduplu\
├── build.gradle.kts                                    # 插件构建配置
├── settings.gradle.kts                                 # 工程定义
├── gradle.properties                                   # JVM 参数与平台版本
├── docs/                                               # 详细架构设计规范
│   ├── IDE_COLLABORATION_ENGINE_DESIGN.md
│   ├── EMBEDDED_JETTY_AND_WEBSOCKET_ARCHITECTURE.md
│   ├── MONACO_COLLAB_ENGINE_SPEC.md
│   └── PROJECT_DELIVERY_PLAN.md
├── webapp/                                             # 学生端 Web 离线静态资源
│   ├── index.html                                      # 学生端单页骨架
│   ├── style.css                                       # 双光标视觉样式与呼吸灯动效
│   └── app.js                                          # Monaco deltaDecorations / 重连状态机
├── src/main/resources/META-INF/
│   └── plugin.xml                                      # 插件扩展点与服务声明
└── src/main/kotlin/com/eduplus/collab/
    ├── arbitration/TeacherPriorityLockManager.kt       # 老师优先写锁管理器
    ├── faulttolerance/NetworkFaultToleranceEngine.kt   # 容错引擎 (时序/节流/防抖/心跳)
    ├── faulttolerance/ReplayBufferManager.kt           # 离线重放缓冲与全量兜底
    ├── protocol/ProtocolModels.kt                      # 6 大信令统一 JSON 模型
    ├── server/EmbeddedJettyServer.kt                   # 嵌入式本地 Jetty 服务
    ├── server/CollabWebSocketEndpoint.kt               # WebSocket 会话调度中心
    ├── server/CollabProjectService.kt                  # IntelliJ ProjectService 生命周期
    ├── render/RemoteCursorRenderer.kt                  # 橙色光标与姓名胶囊绘制
    ├── render/RemoteCursorManager.kt                   # MarkupModel 高亮生命周期
    ├── editor/RemoteApplyGuard.kt                      # 防循环回环隔离门闩
    ├── editor/CollabEditorStartupActivity.kt           # 编辑区挂载
    ├── service/TeachingSessionService.kt               # 协同业务管理单例
    ├── model/TeachingModels.kt                         # 领域模型定义
    └── ui/
        ├── TeachingControlPanel.kt                     # ToolWindow 控制面板
        ├── TeachingToolWindowFactory.kt                # ToolWindow 注册工厂
        └── component/
            ├── StatusIndicatorComponent.kt             # 四态动效指示灯
            └── CursorLegendCard.kt                     # 双光标图例对照卡片
```

---

## 三、快速开始
1. 编译并启动插件沙箱：
   ```bash
   ./gradlew runIde
   ```
2. 在启动的 IDEA 实例中：
   - 打开任意项目；
   - 点击右侧工具栏的 **「EduPlus 协同教学」** 图标展开面板；
   - 点击 **「启动协同服务」** 按钮；
   - 复制生成的本地访问链接（如 `http://127.0.0.1:9876/?token=xxxx`）并在浏览器中打开；
3. 即可开始师生实时双向协同教学！
