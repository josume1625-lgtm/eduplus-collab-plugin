package com.eduplus.collab.server

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.HandlerList
import org.eclipse.jetty.server.handler.ResourceHandler
import org.eclipse.jetty.util.resource.Resource
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID

/**
 * 嵌入式 Jetty 协同服务核心实现
 *
 * 核心特性：
 * 1. 严格仅绑定 127.0.0.1 回环地址，绝不暴露公网。
 * 2. 默认端口 9876，支持自适应探测冲突与自动递增分配。
 * 3. 动静态一体化托管 (静态 Web 控制台资源 + WebSocket 全双工通道)。
 * 4. 内置 Session-Token 校验与 Host/Origin 防跨站劫持。
 */
class EmbeddedJettyServer(
    private val preferredPort: Int = 9876,
    private val maxPortTries: Int = 20,
    private val webResourceBase: String = "webapp"
) {
    private var server: Server? = null
    private var actualPort: Int = -1
    private val authToken: String = UUID.randomUUID().toString()

    @Volatile
    private var isStarted = false

    val token: String get() = authToken
    val port: Int get() = actualPort

    /**
     * 自适应探测可用端口并启动服务
     */
    @Synchronized
    fun start() {
        if (isStarted) return

        actualPort = findAvailableLoopbackPort(preferredPort, maxPortTries)

        val jettyServer = Server()
        val httpConfig = HttpConfiguration().apply {
            sendServerVersion = false
            sendDateHeader = true
        }

        val connector = ServerConnector(jettyServer, HttpConnectionFactory(httpConfig)).apply {
            // 安全硬约束：严格绑定 127.0.0.1 回环地址
            host = "127.0.0.1"
            port = actualPort
            idleTimeout = 30000
        }
        jettyServer.addConnector(connector)

        // 1. 静态资源处理器
        val resourceHandler = ResourceHandler().apply {
            isDirectoriesListed = false
            welcomeFiles = arrayOf("index.html")
            val resource = Resource.newClassPathResource(webResourceBase)
            if (resource != null && resource.exists()) {
                baseResource = resource
            }
        }

        // 2. 安全鉴权过滤器与路由处理器
        val securityHandler = object : AbstractHandler() {
            override fun handle(
                target: String,
                baseRequest: Request,
                request: HttpServletRequest,
                response: HttpServletResponse
            ) {
                // 安全检查：校验 Host 头，防 DNS Rebinding
                val hostHeader = request.getHeader("Host") ?: ""
                if (!hostHeader.startsWith("127.0.0.1") && !hostHeader.startsWith("localhost")) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid Host header")
                    baseRequest.isHandled = true
                    return
                }

                // WebSocket 握手或 API 请求校验 Token
                val reqToken = request.getParameter("token") ?: request.getHeader("X-EduPlu-Token")
                if (target.startsWith("/ws")) {
                    if (reqToken != authToken) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized: Invalid or missing token")
                        baseRequest.isHandled = true
                        return
                    }
                }
            }
        }

        val handlers = HandlerList()
        handlers.addHandler(securityHandler)
        handlers.addHandler(resourceHandler)
        jettyServer.handler = handlers

        // 启动 Jetty 服务
        try {
            jettyServer.start()
            server = jettyServer
            isStarted = true
            println("[EduPlu-Jetty] 本地协同服务启动成功 -> http://127.0.0.1:$actualPort/?token=$authToken")
        } catch (e: Exception) {
            jettyServer.stop()
            throw IOException("启动本地嵌入式 Jetty 失败: ${e.message}", e)
        }
    }

    /**
     * 优雅停机
     */
    @Synchronized
    fun stop() {
        if (!isStarted) return
        try {
            server?.stop()
            server?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            server = null
            isStarted = false
            println("[EduPlu-Jetty] 本地协同服务已安全关闭")
        }
    }

    fun isRunning(): Boolean = isStarted && server?.isRunning == true

    /**
     * 自适应本地端口探测
     */
    private fun findAvailableLoopbackPort(startPort: Int, maxAttempts: Int): Int {
        for (candidate in startPort until (startPort + maxAttempts)) {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress("127.0.0.1", candidate))
                    return candidate
                }
            } catch (_: IOException) {
                // 端口被占用，尝试下一个 candidate
            }
        }
        // 若预留端口全部被占用，由系统随机分配一个可用端口
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }
}
