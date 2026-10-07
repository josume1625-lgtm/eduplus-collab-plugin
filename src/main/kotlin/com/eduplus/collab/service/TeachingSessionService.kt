package com.eduplus.collab.service

import com.eduplus.collab.editor.RemoteApplyGuard
import com.eduplus.collab.model.ConnectionStatus
import com.eduplus.collab.model.RemotePeer
import com.eduplus.collab.model.TeachingMode
import com.eduplus.collab.model.UserRole
import com.eduplus.collab.render.RemoteCursorManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
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
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 教学协同会话服务 - Project 级单例
 * 负责本地 HTTP 静态资源服务与 WebSocket 全双工通信管线生命周期管理
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
    private val currentMode = AtomicReference(TeachingMode.TEACHER_EXCLUSIVE)
    private val activePeers = CopyOnWriteArrayList<RemotePeer>()
    private val clientSockets = ConcurrentHashMap<String, WebSocket>()

    var port: Int = 8765
        private set
    var wsPort: Int = 8766
        private set

    val status: ConnectionStatus get() = currentStatus.get()
    val mode: TeachingMode get() = currentMode.get()
    val peers: List<RemotePeer> get() = activePeers.toList()
    val accessUrl: String get() = "http://127.0.0.1:$port/?role=student"

    private var httpServer: HttpServer? = null
    private var wsServer: WebSocketServer? = null
    private var remoteCursorManager: RemoteCursorManager? = null

    fun addListener(listener: SessionEventListener) { listeners.add(listener) }
    fun removeListener(listener: SessionEventListener) { listeners.remove(listener) }

    /**
     * 启动教学协同服务 (HTTP + WebSocket)
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

            updateStatus(ConnectionStatus.WAITING)
            LOG.info("[EduPlus] 协同服务启动成功 -> HTTP: http://127.0.0.1:$port, WS: ws://127.0.0.1:$wsPort")
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
            remoteCursorManager?.dispose()
            remoteCursorManager = null
            updateStatus(ConnectionStatus.IDLE)
            listeners.forEach { it.onPeerUpdated(emptyList()) }
        }
    }

    /**
     * 切换教学模式
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
     * 启动本地 HTTP 静态资源服务 (托管 Monaco WebApp)
     */
    private fun startHttpServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
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
                        else -> "application/octet-stream"
                    }
                    exchange.responseHeaders.set("Content-Type", contentType)
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
     * 启动 WebSocket 服务端
     */
    private fun startWebSocketServer() {
        val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", wsPort)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                val uri = handshake.resourceDescriptor ?: ""
                val studentId = extractParam(uri, "userId") ?: ("stu_" + (1000..9999).random())
                val studentName = extractParam(uri, "name") ?: ("学生·" + studentId.takeLast(4))

                clientSockets[studentId] = conn
                addOrUpdatePeer(RemotePeer(
                    id = studentId,
                    name = studentName,
                    role = UserRole.STUDENT,
                    status = ConnectionStatus.CONNECTED,
                    latencyMs = 12,
                    currentFile = "Active.java"
                ))
                LOG.info("[EduPlus] 学生已连接: $studentName ($studentId)")

                // 发送当前正在编辑的文档全量代码给学生
                ApplicationManager.getApplication().invokeLater {
                    sendCurrentEditorSnapshot(conn)
                }
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                var removedId: String? = null
                clientSockets.entries.removeIf { (id, ws) ->
                    if (ws == conn) { removedId = id; true } else false
                }
                removedId?.let { id ->
                    removePeer(id)
                    ApplicationManager.getApplication().invokeLater {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor
                        if (editor != null && !editor.isDisposed) {
                            remoteCursorManager?.clearStudent(editor, id)
                        }
                    }
                }
            }

            override fun onMessage(conn: WebSocket, message: String) {
                handleClientMessage(conn, message)
            }

            override fun onError(conn: WebSocket?, ex: Exception?) {
                LOG.warn("[EduPlus] WebSocket 异常: ${ex?.message}")
            }

            override fun onStart() {
                LOG.info("[EduPlus] WebSocket 服务就绪，监听 127.0.0.1:$wsPort")
            }
        }
        server.isReuseAddr = true
        server.start()
        this.wsServer = server
    }

    /**
     * 处理学生端上行消息
     */
    private fun handleClientMessage(conn: WebSocket, message: String) {
        try {
            val json = JsonParser.parseString(message).asJsonObject
            val type = json.get("type")?.asString ?: return

            when (type) {
                "cursor_student" -> {
                    val payload = json.getAsJsonObject("payload") ?: return
                    val studentId = payload.get("studentId")?.asString ?: "student"
                    val studentName = payload.get("studentName")?.asString ?: "学生"
                    val line = payload.get("line")?.asInt ?: 1
                    val ch = payload.get("ch")?.asInt ?: 1
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
     * 注册本地 IDEA 事件监听器 (老师端上行广播)
     */
    private fun registerEditorListeners() {
        val eventMulticaster = EditorFactory.getInstance().eventMulticaster

        eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (status == ConnectionStatus.IDLE) return
                if (RemoteApplyGuard.isRemoteUpdating) return
                if (event.document.getUserData(RemoteApplyGuard.IS_REMOTE_EDIT_KEY) == true) return

                val delta = JsonObject().apply {
                    addProperty("type", "code_delta")
                    val payload = JsonObject().apply {
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

        eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (status == ConnectionStatus.IDLE) return
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

        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (status == ConnectionStatus.IDLE) return
                ApplicationManager.getApplication().invokeLater {
                    val editor = event.manager.selectedTextEditor ?: return@invokeLater
                    val full = JsonObject().apply {
                        addProperty("type", "code_full")
                        val payload = JsonObject().apply {
                            addProperty("filePath", event.newFile?.path ?: "")
                            addProperty("language", event.newFile?.extension ?: "java")
                            addProperty("content", editor.document.text)
                            addProperty("version", seqCounter.incrementAndGet())
                        }
                        add("payload", payload)
                    }
                    broadcast(gson.toJson(full))
                }
            }
        })
    }

    private fun broadcast(text: String) {
        clientSockets.values.forEach { ws ->
            try { if (ws.isOpen) ws.send(text) } catch (_: Exception) {}
        }
    }

    private fun sendCurrentEditorSnapshot(conn: WebSocket) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc)

        val full = JsonObject().apply {
            addProperty("type", "code_full")
            val payload = JsonObject().apply {
                addProperty("filePath", file?.path ?: "Main.java")
                addProperty("language", file?.extension ?: "java")
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
        for (p in startPort..(startPort + 30)) {
            try {
                ServerSocket(p, 1, java.net.InetAddress.getByName("127.0.0.1")).use { return p }
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
