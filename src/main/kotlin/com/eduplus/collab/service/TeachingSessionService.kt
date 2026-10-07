package com.eduplus.collab.service

import com.eduplus.collab.editor.RemoteApplyGuard
import com.eduplus.collab.model.ConnectionStatus
import com.eduplus.collab.model.RemotePeer
import com.eduplus.collab.model.TeachingMode
import com.eduplus.collab.model.UserRole
import com.eduplus.collab.render.RemoteCursorManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 教学协同会话服务 - Project 级单例
 * 负责本地/局域网 HTTP 静态资源服务、WebSocket 全双工通信、文件树同步、多页签同步、
 * 编译/构建错误与控制台全量转发、默认自由协同模式、学生申请运行及老师二次确认。
 */
@Service(Service.Level.PROJECT)
class TeachingSessionService(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(TeachingSessionService::class.java)
    private val gson = Gson()
    private val seqCounter = AtomicLong(1000L)

    interface SessionEventListener {
        fun onStatusChanged(newStatus: ConnectionStatus) {}
        fun onModeChanged(newMode: TeachingMode) {}
        fun onPeerUpdated(peers: List<RemotePeer>) {}
    }

    private val listeners = CopyOnWriteArrayList<SessionEventListener>()
    private val currentStatus = AtomicReference(ConnectionStatus.IDLE)
    // 默认双向输入的自由互动协同模式
    private val currentMode = AtomicReference(TeachingMode.FREE_COLLABORATION)
    private val activePeers = CopyOnWriteArrayList<RemotePeer>()
    private val clientSockets = ConcurrentHashMap<String, WebSocket>()
    private val attachedHandlers = Collections.newSetFromMap(ConcurrentHashMap<ProcessHandler, Boolean>())
    // 缓存最近 200 条运行/编译日志，防止学生刚连接或刷新时遗漏首批运行结果
    private val executionLogCache = CopyOnWriteArrayList<JsonObject>()

    var port: Int = 8765
        private set
    var wsPort: Int = 8766
        private set

    val status: ConnectionStatus get() = currentStatus.get()
    val mode: TeachingMode get() = currentMode.get()
    val peers: List<RemotePeer> get() = activePeers.toList()
    val accessUrl: String get() = "http://127.0.0.1:$port/?role=student"
    val lanAccessUrl: String get() = "http://${getLanIp()}:$port/?role=student"

    private var httpServer: HttpServer? = null
    private var wsServer: WebSocketServer? = null
    private var remoteCursorManager: RemoteCursorManager? = null

    fun addListener(listener: SessionEventListener) { listeners.add(listener) }
    fun removeListener(listener: SessionEventListener) { listeners.remove(listener) }

    /**
     * 获取本机局域网 IPv4 地址 (Wi-Fi/以太网)
     */
    fun getLanIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    /**
     * 启动教学协同服务 (HTTP 托管 + WebSocket 长连接)
     */
    fun startSession(customPort: Int = 8765): Boolean {
        if (currentStatus.get() != ConnectionStatus.IDLE) return false

        try {
            this.port = findAvailablePort(customPort)
            this.wsPort = findAvailablePort(this.port + 1)
            this.remoteCursorManager = RemoteCursorManager(project)

            startHttpServer()
            startWebSocketServer()
            registerEditorListeners()
            registerExecutionListener()
            registerBuildListener()

            updateStatus(ConnectionStatus.WAITING)
            LOG.info("[EduPlus] 协同服务启动成功 -> 本机: $accessUrl, 局域网: $lanAccessUrl, WS端口: $wsPort")
            return true
        } catch (e: Exception) {
            LOG.error("[EduPlus] 启动协同服务失败", e)
            stopSession()
            return false
        }
    }

    /**
     * 停止教学协同服务
     */
    fun stopSession() {
        try {
            httpServer?.stop(0)
            wsServer?.stop()
        } catch (e: Exception) {
            LOG.warn("[EduPlus] 停止服务时发生异常", e)
        } finally {
            httpServer = null
            wsServer = null
            clientSockets.clear()
            activePeers.clear()
            attachedHandlers.clear()
            remoteCursorManager?.dispose()
            remoteCursorManager = null
            updateStatus(ConnectionStatus.IDLE)
            listeners.forEach { it.onPeerUpdated(emptyList()) }
        }
    }

    /**
     * 切换教学模式 (老师独占 / 自由协同)
     */
    fun setTeachingMode(newMode: TeachingMode) {
        if (currentMode.getAndSet(newMode) != newMode) {
            listeners.forEach { it.onModeChanged(newMode) }
            val lockMsg = JsonObject().apply {
                addProperty("type", "teacher_lock_state")
                val payload = JsonObject().apply {
                    addProperty("isLocked", newMode == TeachingMode.TEACHER_EXCLUSIVE)
                    addProperty("reason", if (newMode == TeachingMode.TEACHER_EXCLUSIVE) "老师独占讲解演示中" else "自由协同提问模式")
                }
                add("payload", payload)
            }
            broadcast(gson.toJson(lockMsg))
        }
    }

    fun updateStatus(status: ConnectionStatus) {
        if (currentStatus.getAndSet(status) != status) {
            listeners.forEach { it.onStatusChanged(status) }
        }
    }

    private fun addOrUpdatePeer(peer: RemotePeer) {
        activePeers.removeIf { it.id == peer.id }
        activePeers.add(peer)
        if (activePeers.isNotEmpty()) {
            updateStatus(ConnectionStatus.CONNECTED)
        }
        listeners.forEach { it.onPeerUpdated(activePeers.toList()) }
    }

    private fun removePeer(peerId: String) {
        activePeers.removeIf { it.id == peerId }
        if (activePeers.isEmpty()) {
            updateStatus(ConnectionStatus.WAITING)
        }
        listeners.forEach { it.onPeerUpdated(activePeers.toList()) }
    }

    /**
     * 启动本地/局域网 HTTP 静态资源服务 (托管 Monaco WebApp)
     */
    private fun startHttpServer() {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/") { exchange ->
            try {
                var path = exchange.requestURI.path ?: "/index.html"
                if (path == "/" || path.isBlank()) path = "/index.html"
                if (path.startsWith("/")) path = path.substring(1)

                val bytes = loadResourceBytes(path)
                if (bytes != null) {
                    val contentType = when {
                        path.endsWith(".html") -> "text/html; charset=UTF-8"
                        path.endsWith(".css") -> "text/css; charset=UTF-8"
                        path.endsWith(".js") -> "application/javascript; charset=UTF-8"
                        path.endsWith(".json") -> "application/json; charset=UTF-8"
                        path.endsWith(".png") -> "image/png"
                        path.endsWith(".svg") -> "image/svg+xml"
                        path.endsWith(".ttf") -> "font/ttf"
                        path.endsWith(".woff") -> "font/woff"
                        path.endsWith(".woff2") -> "font/woff2"
                        else -> "application/octet-stream"
                    }
                    exchange.responseHeaders.set("Content-Type", contentType)
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } else {
                    val notFound = "404 Not Found: $path".toByteArray()
                    exchange.sendResponseHeaders(404, notFound.size.toLong())
                    exchange.responseBody.use { it.write(notFound) }
                }
            } catch (e: Exception) {
                try {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.responseBody.close()
                } catch (_: Exception) {}
            }
        }
        server.start()
        this.httpServer = server
    }

    private fun loadResourceBytes(path: String): ByteArray? {
        val stream = javaClass.getResourceAsStream("/webapp/$path")
            ?: javaClass.getResourceAsStream("/$path")
        if (stream != null) {
            return stream.use { it.readBytes() }
        }
        val localFile = File("e:/eduplu/webapp/$path")
        if (localFile.exists() && localFile.isFile) {
            return localFile.readBytes()
        }
        return null
    }

    /**
     * 启动 WebSocket 全双工服务端
     */
    private fun startWebSocketServer() {
        val server = object : WebSocketServer(InetSocketAddress(wsPort)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                val uri = handshake.resourceDescriptor ?: ""
                val studentId = extractParam(uri, "userId") ?: ("stu_" + (1000..9999).random())
                val studentName = extractParam(uri, "name") ?: ("学生·" + studentId.takeLast(4))

                clientSockets[studentId] = conn
                addOrUpdatePeer(RemotePeer(
                    id = studentId,
                    name = studentName,
                    role = UserRole.STUDENT,
                    latencyMs = 12,
                    activeFile = "Active.java"
                ))
                LOG.info("[EduPlus] 学生已连接: $studentName ($studentId)")

                // 1. 发送工作区目录树
                broadcastProjectTree(conn)

                // 2. 发送当前所有已打开的页签
                broadcastTabs(conn)

                // 3. 发送当前活动编辑器全量代码
                ApplicationManager.getApplication().invokeLater {
                    sendCurrentEditorSnapshot(conn)
                }

                // 4. 重放最近运行输出历史，杜绝第一次运行或刷新时错过结果
                executionLogCache.forEach {
                    try { if (conn.isOpen) conn.send(gson.toJson(it)) } catch (_: Exception) {}
                }
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                val entry = clientSockets.entries.firstOrNull { it.value == conn }
                if (entry != null) {
                    clientSockets.remove(entry.key)
                    removePeer(entry.key)
                    remoteCursorManager?.removeStudentCursor(entry.key)
                    LOG.info("[EduPlus] 学生断开连接: ${entry.key}")
                }
            }

            override fun onMessage(conn: WebSocket, message: String) {
                handleClientMessage(conn, message)
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                LOG.warn("[EduPlus] WebSocket 发生错误", ex)
            }

            override fun onStart() {
                LOG.info("[EduPlus] WebSocketServer 已就绪，监听端口: $wsPort")
            }
        }
        server.isReuseAddr = true
        server.start()
        this.wsServer = server
    }

    /**
     * 处理客户端上行消息
     */
    private fun handleClientMessage(conn: WebSocket, message: String) {
        try {
            val json = JsonParser.parseString(message).asJsonObject
            val type = json.get("type")?.asString ?: return

            when (type) {
                "cursor_student" -> {
                    val payload = json.getAsJsonObject("payload") ?: return
                    val line = payload.get("line")?.asInt ?: 1
                    val ch = payload.get("ch")?.asInt ?: 1
                    val studentId = payload.get("studentId")?.asString ?: "stu_anon"
                    val studentName = payload.get("studentName")?.asString ?: "学生"
                    val selStart = payload.getAsJsonObject("selectionStart")
                    val selEnd = payload.getAsJsonObject("selectionEnd")

                    ApplicationManager.getApplication().invokeLater {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
                        if (editor.isDisposed) return@invokeLater
                        val doc = editor.document
                        val lineIndex = (line - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
                        val lineStart = doc.getLineStartOffset(lineIndex)
                        val lineEnd = doc.getLineEndOffset(lineIndex)
                        val offset = (lineStart + (ch - 1)).coerceIn(lineStart, lineEnd)

                        var startOffset: Int? = null
                        var endOffset: Int? = null
                        if (selStart != null && selEnd != null) {
                            val sLine = (selStart.get("line").asInt - 1).coerceIn(0, doc.lineCount - 1)
                            val eLine = (selEnd.get("line").asInt - 1).coerceIn(0, doc.lineCount - 1)
                            startOffset = (doc.getLineStartOffset(sLine) + (selStart.get("ch").asInt - 1)).coerceIn(0, doc.textLength)
                            endOffset = (doc.getLineStartOffset(eLine) + (selEnd.get("ch").asInt - 1)).coerceIn(0, doc.textLength)
                        }

                        remoteCursorManager?.updateStudentCursor(
                            editor = editor,
                            studentId = studentId,
                            studentName = studentName,
                            cursorOffset = offset,
                            selectionStart = startOffset,
                            selectionEnd = endOffset
                        )
                    }
                }
                "code_delta" -> {
                    if (currentMode.get() == TeachingMode.FREE_COLLABORATION) {
                        val payload = json.getAsJsonObject("payload") ?: return
                        val rangeOffset = payload.get("rangeOffset")?.asInt ?: 0
                        val oldLength = payload.get("oldLength")?.asInt ?: 0
                        val text = payload.get("text")?.asString ?: ""

                        ApplicationManager.getApplication().invokeLater {
                            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
                            val doc = editor.document
                            if (!doc.isWritable) return@invokeLater

                            WriteCommandAction.runWriteCommandAction(project, "EduPlus_Remote_Apply", "EduPlusGroup", {
                                RemoteApplyGuard.runGuarded {
                                    doc.putUserData(RemoteApplyGuard.IS_REMOTE_EDIT_KEY, true)
                                    try {
                                        val safeOffset = rangeOffset.coerceIn(0, doc.textLength)
                                        val safeOldLen = oldLength.coerceIn(0, doc.textLength - safeOffset)
                                        if (safeOldLen > 0 && text.isNotEmpty()) {
                                            doc.replaceString(safeOffset, safeOffset + safeOldLen, text)
                                        } else if (safeOldLen > 0) {
                                            doc.deleteString(safeOffset, safeOffset + safeOldLen)
                                        } else if (text.isNotEmpty()) {
                                            doc.insertString(safeOffset, text)
                                        }
                                    } finally {
                                        doc.putUserData(RemoteApplyGuard.IS_REMOTE_EDIT_KEY, null)
                                    }
                                }
                            })
                        }
                    }
                }
                "open_file_request" -> {
                    val payload = json.getAsJsonObject("payload") ?: return
                    val relPath = payload.get("path")?.asString ?: return
                    val base = project.basePath ?: return
                    val target = File(base, relPath)
                    if (target.exists() && target.isFile) {
                        try {
                            val content = target.readText(Charsets.UTF_8)
                            val ext = target.extension
                            val full = JsonObject().apply {
                                addProperty("type", "code_full")
                                val p = JsonObject().apply {
                                    addProperty("filePath", relPath)
                                    addProperty("fileName", target.name)
                                    addProperty("language", mapLanguage(ext))
                                    addProperty("content", content)
                                    addProperty("version", seqCounter.incrementAndGet())
                                }
                                add("payload", p)
                            }
                            conn.send(gson.toJson(full))
                        } catch (e: Exception) {
                            LOG.warn("[EduPlus] 读取文件失败: $relPath", e)
                        }
                    }
                }
                "refresh_tree_request" -> {
                    broadcastProjectTree(conn)
                }
                "student_request_run" -> {
                    // 学生申请运行当前代码文件，弹出老师端二次确认对话框
                    val payload = json.getAsJsonObject("payload") ?: return
                    val studentId = payload.get("studentId")?.asString ?: "stu"
                    val studentName = payload.get("studentName")?.asString ?: "学生"
                    val filePath = payload.get("filePath")?.asString ?: "当前文件"

                    ApplicationManager.getApplication().invokeLater {
                        val answer = Messages.showYesNoDialog(
                            project,
                            "👨‍🎓 学生【$studentName】申请在您的本地 IDEA 中运行代码文件：\n\n📄 $filePath\n\n是否允许并立即执行该程序？",
                            "EduPlus 协同课堂 - 代码运行申请",
                            "允许运行 (Run)",
                            "拒绝 (Reject)",
                            Messages.getQuestionIcon()
                        )

                        val approved = (answer == Messages.YES)
                        val respMsg = JsonObject().apply {
                            addProperty("type", "student_run_response")
                            val p = JsonObject().apply {
                                addProperty("approved", approved)
                                addProperty("studentId", studentId)
                                addProperty("message", if (approved) "老师已批准运行申请，程序正在启动..." else "老师拒绝了此次运行申请。")
                            }
                            add("payload", p)
                        }
                        broadcastAndCache(respMsg)

                        if (approved) {
                            triggerRunActiveFile()
                        }
                    }
                }
                "heartbeat" -> {
                    val pong = JsonObject().apply {
                        addProperty("type", "heartbeat")
                        val p = JsonObject().apply {
                            addProperty("action", "pong")
                            addProperty("serverTime", System.currentTimeMillis())
                        }
                        add("payload", p)
                    }
                    conn.send(gson.toJson(pong))
                }
            }
        } catch (e: Exception) {
            LOG.warn("[EduPlus] 解析客户端消息失败", e)
        }
    }

    /**
     * 触发本地 IDEA 运行当前活动配置或文件
     */
    fun triggerRunActiveFile() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val runManager = RunManager.getInstance(project)
                val selectedConfig = runManager.selectedConfiguration
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                if (selectedConfig != null && executor != null) {
                    ProgramRunnerUtil.executeConfiguration(selectedConfig, executor)
                    return@invokeLater
                }

                // Fallback: 如果没有选中的配置，通过 ActionManager 触发 Run
                val actionManager = ActionManager.getInstance()
                val runAction = actionManager.getAction("Run") ?: actionManager.getAction("RunClass")
                if (runAction != null) {
                    val dataContext = SimpleDataContext.getProjectContext(project)
                    val actionEvent = AnActionEvent.createFromAnAction(
                        runAction, null, ActionPlaces.UNKNOWN, dataContext
                    )
                    runAction.actionPerformed(actionEvent)
                }
            } catch (e: Throwable) {
                LOG.warn("[EduPlus] 触发本地运行失败", e)
            }
        }
    }

    /**
     * 注册本地 IDEA 编辑器监听器 (老师端上行广播)
     */
    private fun registerEditorListeners() {
        val eventMulticaster = EditorFactory.getInstance().eventMulticaster

        // 1. 文档内容变动监听 (Delta 广播) - 严格仅限当前活动源码文件，杜绝控制台/构建日志干扰
        eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (status == ConnectionStatus.IDLE) return
                if (RemoteApplyGuard.isRemoteUpdating) return
                if (event.document.getUserData(RemoteApplyGuard.IS_REMOTE_EDIT_KEY) == true) return

                val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
                if (currentEditor.document != event.document) return

                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (!file.isValid || file.isDirectory) return

                val basePath = project.basePath ?: return
                if (!file.path.startsWith(basePath)) return

                val delta = JsonObject().apply {
                    addProperty("type", "code_delta")
                    val payload = JsonObject().apply {
                        addProperty("filePath", getRelativePath(file))
                        addProperty("rangeOffset", event.offset)
                        addProperty("oldLength", event.oldLength)
                        addProperty("text", event.newFragment.toString())
                        addProperty("seqId", seqCounter.incrementAndGet())
                    }
                    add("payload", payload)
                }
                broadcast(gson.toJson(delta))
            }
        }, this)

        // 2. 老师光标与选区变动监听 (Cursor 广播) - 严格仅限当前活动代码编辑器
        eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (status == ConnectionStatus.IDLE) return
                val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
                if (event.editor != currentEditor) return

                val file = FileDocumentManager.getInstance().getFile(currentEditor.document) ?: return
                val basePath = project.basePath ?: return
                if (!file.path.startsWith(basePath)) return

                val caret = event.caret ?: return
                val pos = caret.logicalPosition

                val msg = JsonObject().apply {
                    addProperty("type", "cursor_teacher")
                    val payload = JsonObject().apply {
                        addProperty("line", pos.line + 1)
                        addProperty("ch", pos.column + 1)
                        addProperty("teacherName", "老师")
                        if (caret.hasSelection()) {
                            val selStart = JsonObject().apply {
                                val sp = caret.editor.offsetToLogicalPosition(caret.selectionStart)
                                addProperty("line", sp.line + 1)
                                addProperty("ch", sp.column + 1)
                            }
                            val selEnd = JsonObject().apply {
                                val ep = caret.editor.offsetToLogicalPosition(caret.selectionEnd)
                                addProperty("line", ep.line + 1)
                                addProperty("ch", ep.column + 1)
                            }
                            add("selectionStart", selStart)
                            add("selectionEnd", selEnd)
                        }
                    }
                    add("payload", payload)
                }
                broadcast(gson.toJson(msg))
            }
        }, this)

        // 3. 文件/多页签切换监听
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (status == ConnectionStatus.IDLE) return
                broadcastTabs()
                ApplicationManager.getApplication().invokeLater {
                    val editor = event.manager.selectedTextEditor ?: return@invokeLater
                    val file = event.newFile ?: return@invokeLater
                    if (!file.isValid || file.isDirectory) return@invokeLater
                    val basePath = project.basePath ?: return@invokeLater
                    if (!file.path.startsWith(basePath)) return@invokeLater

                    val relPath = getRelativePath(file)
                    val full = JsonObject().apply {
                        addProperty("type", "code_full")
                        val payload = JsonObject().apply {
                            addProperty("filePath", relPath)
                            addProperty("fileName", file.name)
                            addProperty("language", mapLanguage(file.extension))
                            addProperty("content", editor.document.text)
                            addProperty("version", seqCounter.incrementAndGet())
                        }
                        add("payload", payload)
                    }
                    broadcast(gson.toJson(full))
                }
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (status == ConnectionStatus.IDLE) return
                broadcastTabs()
                broadcastProjectTree()
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (status == ConnectionStatus.IDLE) return
                broadcastTabs()
            }
        })
    }

    /**
     * 注册程序运行输出监听器 (同步显示老师端运行输出，尽早挂载避免遗漏首批打印)
     */
    private fun registerExecutionListener() {
        try {
            project.messageBus.connect(this).subscribe(
                ExecutionManager.EXECUTION_TOPIC,
                object : ExecutionListener {
                    // 1. processStarting 尽早挂载监听器，在程序输出第一行之前完成捕获
                    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                        attachProcessHandler(handler, env.runProfile.name)
                    }

                    // 2. processStarted 兜底挂载
                    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                        attachProcessHandler(handler, env.runProfile.name)
                    }

                    // 3. processNotStarted 处理编译失败或无法启动的情形
                    override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
                        val errMsg = JsonObject().apply {
                            addProperty("type", "execution_output")
                            val p = JsonObject().apply {
                                addProperty("text", "\n=== ❌ 进程未能启动: ${cause?.message ?: "编译失败或运行配置未就绪"} ===\n")
                                addProperty("isStderr", true)
                                addProperty("isSystem", true)
                            }
                            add("payload", p)
                        }
                        broadcastAndCache(errMsg)

                        val endMsg = JsonObject().apply {
                            addProperty("type", "execution_status")
                            val p = JsonObject().apply {
                                addProperty("status", "terminated")
                                addProperty("exitCode", -1)
                            }
                            add("payload", p)
                        }
                        broadcastAndCache(endMsg)
                    }
                }
            )
        } catch (e: Throwable) {
            LOG.warn("[EduPlus] 注册运行监听器失败", e)
        }
    }

    /**
     * 注册项目编译/构建事件监听器 (完整转发编译过程输出与编译错误信息)
     */
    private fun registerBuildListener() {
        try {
            val buildViewManager = project.getService(BuildViewManager::class.java)
            buildViewManager?.addListener(object : BuildProgressListener {
                override fun onEvent(buildId: Any, event: BuildEvent) {
                    when (event) {
                        is StartBuildEvent -> {
                            val startMsg = JsonObject().apply {
                                addProperty("type", "execution_status")
                                val p = JsonObject().apply {
                                    addProperty("status", "started")
                                    addProperty("title", "项目编译构建")
                                    addProperty("time", System.currentTimeMillis())
                                }
                                add("payload", p)
                            }
                            broadcastAndCache(startMsg)
                        }
                        is OutputBuildEvent -> {
                            val text = event.message
                            if (!text.isNullOrBlank()) {
                                val isStderr = !event.isStdOut
                                val outMsg = JsonObject().apply {
                                    addProperty("type", "execution_output")
                                    val p = JsonObject().apply {
                                        addProperty("text", text)
                                        addProperty("isStderr", isStderr)
                                        addProperty("isSystem", false)
                                    }
                                    add("payload", p)
                                }
                                broadcastAndCache(outMsg)
                            }
                        }
                        is FinishBuildEvent -> {
                            val result = event.result
                            if (result is FailureResult) {
                                val failureDetails = result.failures?.mapNotNull { it.message }?.joinToString("\n") ?: ""
                                val errOut = JsonObject().apply {
                                    addProperty("type", "execution_output")
                                    val p = JsonObject().apply {
                                        addProperty("text", "\n=== ❌ 编译构建未通过 ===\n$failureDetails\n")
                                        addProperty("isStderr", true)
                                        addProperty("isSystem", true)
                                    }
                                    add("payload", p)
                                }
                                broadcastAndCache(errOut)

                                val endMsg = JsonObject().apply {
                                    addProperty("type", "execution_status")
                                    val p = JsonObject().apply {
                                        addProperty("status", "terminated")
                                        addProperty("exitCode", -1)
                                    }
                                    add("payload", p)
                                }
                                broadcastAndCache(endMsg)
                            }
                        }
                    }
                }
            }, this)
        } catch (e: Throwable) {
            LOG.warn("[EduPlus] 注册构建输出监听器失败", e)
        }
    }

    private fun attachProcessHandler(handler: ProcessHandler, runTitle: String) {
        if (!attachedHandlers.add(handler)) return

        val startMsg = JsonObject().apply {
            addProperty("type", "execution_status")
            val p = JsonObject().apply {
                addProperty("status", "started")
                addProperty("title", runTitle)
                addProperty("time", System.currentTimeMillis())
            }
            add("payload", p)
        }
        broadcastAndCache(startMsg)

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                val isStderr = ProcessOutputTypes.STDERR == outputType
                val isSystem = ProcessOutputTypes.SYSTEM == outputType
                val outMsg = JsonObject().apply {
                    addProperty("type", "execution_output")
                    val p = JsonObject().apply {
                        addProperty("text", text)
                        addProperty("isStderr", isStderr)
                        addProperty("isSystem", isSystem)
                    }
                    add("payload", p)
                }
                broadcastAndCache(outMsg)
            }

            override fun processTerminated(event: ProcessEvent) {
                attachedHandlers.remove(handler)
                val endMsg = JsonObject().apply {
                    addProperty("type", "execution_status")
                    val p = JsonObject().apply {
                        addProperty("status", "terminated")
                        addProperty("exitCode", event.exitCode)
                    }
                    add("payload", p)
                }
                broadcastAndCache(endMsg)
            }

            override fun startNotified(event: ProcessEvent) {}
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {}
        })
    }

    private fun broadcastAndCache(msg: JsonObject) {
        if (executionLogCache.size > 200) {
            executionLogCache.removeAt(0)
        }
        executionLogCache.add(msg)
        broadcast(gson.toJson(msg))
    }

    /**
     * 广播/单播工作区目录树
     */
    fun broadcastProjectTree(target: WebSocket? = null) {
        val rootPath = project.basePath ?: return
        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) return

        val treeObj = JsonObject().apply {
            addProperty("type", "directory_tree")
            val payload = JsonObject().apply {
                addProperty("projectName", project.name)
                addProperty("rootPath", rootPath)
                add("tree", scanDirRecursive(rootDir, rootDir, depth = 0, maxDepth = 4))
            }
            add("payload", payload)
        }
        val jsonStr = gson.toJson(treeObj)
        if (target != null) {
            try { if (target.isOpen) target.send(jsonStr) } catch (_: Exception) {}
        } else {
            broadcast(jsonStr)
        }
    }

    private fun scanDirRecursive(dir: File, baseDir: File, depth: Int, maxDepth: Int): JsonArray {
        val array = JsonArray()
        if (depth > maxDepth) return array

        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return array
        for (file in files) {
            val name = file.name
            if (name.startsWith(".") || name == "build" || name == "out" || name == "target" ||
                name == ".gradle" || name == ".idea" || name == ".git" || name == "node_modules") {
                continue
            }
            val relPath = file.relativeTo(baseDir).path.replace("\\", "/")
            val item = JsonObject().apply {
                addProperty("name", name)
                addProperty("path", relPath)
                addProperty("isDirectory", file.isDirectory)
                if (file.isDirectory) {
                    add("children", scanDirRecursive(file, baseDir, depth + 1, maxDepth))
                }
            }
            array.add(item)
        }
        return array
    }

    /**
     * 广播/单播当前所有已打开的编辑器页签列表
     */
    fun broadcastTabs(target: WebSocket? = null) {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles
        val selectedFile = fileEditorManager.selectedFiles.firstOrNull()
        val base = project.basePath

        val tabsArray = JsonArray()
        for (vf in openFiles) {
            val relPath = if (base != null && vf.path.startsWith(base)) {
                File(vf.path).relativeTo(File(base)).path.replace("\\", "/")
            } else {
                vf.name
            }
            val tabObj = JsonObject().apply {
                addProperty("name", vf.name)
                addProperty("path", relPath)
                addProperty("active", vf == selectedFile)
            }
            tabsArray.add(tabObj)
        }

        val activePath = if (selectedFile != null && base != null && selectedFile.path.startsWith(base)) {
            File(selectedFile.path).relativeTo(File(base)).path.replace("\\", "/")
        } else {
            selectedFile?.name ?: ""
        }

        val tabMsg = JsonObject().apply {
            addProperty("type", "tab_list")
            val p = JsonObject().apply {
                addProperty("activePath", activePath)
                add("tabs", tabsArray)
            }
            add("payload", p)
        }
        val jsonStr = gson.toJson(tabMsg)
        if (target != null) {
            try { if (target.isOpen) target.send(jsonStr) } catch (_: Exception) {}
        } else {
            broadcast(jsonStr)
        }
    }

    private fun getRelativePath(file: VirtualFile?): String {
        if (file == null) return "ActiveFile"
        val base = project.basePath ?: return file.name
        return if (file.path.startsWith(base)) {
            File(file.path).relativeTo(File(base)).path.replace("\\", "/")
        } else {
            file.name
        }
    }

    private fun mapLanguage(ext: String?): String {
        return when (ext?.lowercase()) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "html", "htm" -> "html"
            "css" -> "css"
            "json" -> "json"
            "xml" -> "xml"
            "md", "markdown" -> "markdown"
            "c", "h" -> "c"
            "cpp", "hpp", "cc" -> "cpp"
            "sql" -> "sql"
            "sh", "bash" -> "shell"
            "yml", "yaml" -> "yaml"
            else -> "plaintext"
        }
    }

    private fun broadcast(text: String) {
        clientSockets.values.forEach { ws ->
            try { if (ws.isOpen) ws.send(text) } catch (_: Exception) {}
        }
    }

    private fun sendCurrentEditorSnapshot(conn: WebSocket) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!file.isValid || file.isDirectory) return
        val basePath = project.basePath
        if (basePath != null && !file.path.startsWith(basePath)) return
        val relPath = getRelativePath(file)

        val full = JsonObject().apply {
            addProperty("type", "code_full")
            val payload = JsonObject().apply {
                addProperty("filePath", relPath)
                addProperty("fileName", file.name)
                addProperty("language", mapLanguage(file.extension))
                addProperty("content", doc.text)
                addProperty("version", seqCounter.incrementAndGet())
            }
            add("payload", payload)
        }
        conn.send(gson.toJson(full))
    }

    private fun extractParam(query: String, key: String): String? {
        val q = if (query.contains("?")) query.substringAfter("?") else query
        return q.split("&").mapNotNull {
            val parts = it.split("=")
            if (parts.size == 2 && parts[0] == key) parts[1] else null
        }.firstOrNull()
    }

    private fun findAvailablePort(startPort: Int): Int {
        for (p in startPort..(startPort + 50)) {
            try {
                ServerSocket(p).use { return p }
            } catch (_: Exception) {}
        }
        ServerSocket(0).use { return it.localPort }
    }

    override fun dispose() {
        stopSession()
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): TeachingSessionService {
            return project.getService(TeachingSessionService::class.java)
        }
    }
}
