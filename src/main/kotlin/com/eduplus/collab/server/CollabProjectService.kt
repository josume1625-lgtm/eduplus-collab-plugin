package com.eduplus.collab.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IntelliJ 平台级项目服务：管理本地协同服务生命周期
 *
 * 核心特性：
 * 1. 继承 Disposable，随 Project 关闭自动优雅析构所有网络与端口资源。
 * 2. 异步后台启动，严禁阻塞 UI 事件分发线程 (EDT)。
 * 3. 供 ToolWindow 与插件各模块安全调用，支持协同服务的启停控制。
 */
@Service(Service.Level.PROJECT)
class CollabProjectService(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(CollabProjectService::class.java)

    private val jettyServer = EmbeddedJettyServer(preferredPort = 9876)
    val coordinator = CollabWebSocketCoordinator()

    private val isRunning = AtomicBoolean(false)

    val serverPort: Int get() = jettyServer.port
    val serverToken: String get() = jettyServer.token

    /**
     * 异步启动协同服务
     */
    fun startCollabService(onSuccess: ((port: Int, token: String) -> Unit)? = null, onError: ((Throwable) -> Unit)? = null) {
        if (isRunning.get()) {
            onSuccess?.invoke(jettyServer.port, jettyServer.token)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                LOG.info("[EduPlu] 正在为项目 [${project.name}] 启动本地嵌入式协同服务...")
                jettyServer.start()
                isRunning.set(true)
                LOG.info("[EduPlu] 本地协同服务就绪 -> 端口: ${jettyServer.port}, Token: ${jettyServer.token}")
                onSuccess?.invoke(jettyServer.port, jettyServer.token)
            } catch (t: Throwable) {
                LOG.error("[EduPlu] 启动本地协同服务失败", t)
                onError?.invoke(t)
            }
        }
    }

    /**
     * 优雅停机并释放所有句柄
     */
    fun stopCollabService() {
        if (!isRunning.getAndSet(false)) return

        try {
            LOG.info("[EduPlu] 正在关闭项目 [${project.name}] 的本地协同服务...")
            coordinator.shutdown()
            jettyServer.stop()
            LOG.info("[EduPlu] 本地协同服务已完全关闭释放")
        } catch (e: Exception) {
            LOG.error("[EduPlu] 关闭协同服务发生异常", e)
        }
    }

    /**
     * ToolWindow 开关切换
     */
    fun toggleService(onStateChanged: (active: Boolean, port: Int, token: String) -> Unit) {
        if (isRunning.get()) {
            stopCollabService()
            onStateChanged(false, 0, "")
        } else {
            startCollabService(
                onSuccess = { port, token -> onStateChanged(true, port, token) },
                onError = { onStateChanged(false, 0, "") }
            )
        }
    }

    fun isServiceRunning(): Boolean = isRunning.get() && jettyServer.isRunning()

    /**
     * Disposable 析构回调：IntelliJ 项目关闭时自动触发
     */
    override fun dispose() {
        LOG.info("[EduPlu] 项目 [${project.name}] 正在析构，执行协同资源清理...")
        stopCollabService()
    }

    companion object {
        fun getInstance(project: Project): CollabProjectService {
            return project.getService(CollabProjectService::class.java)
        }
    }
}
