package com.eduplus.collab.service

import com.eduplus.collab.model.ConnectionStatus
import com.eduplus.collab.model.RemotePeer
import com.eduplus.collab.model.TeachingMode
import com.eduplus.collab.model.UserRole
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 教学协同会话服务 - Project 级单例
 * 负责状态流转、服务生命周期维护与组件事件广播
 */
@Service(Service.Level.PROJECT)
class TeachingSessionService(private val project: Project) : Disposable {

    interface SessionEventListener {
        fun onStatusChanged(newStatus: ConnectionStatus) {}
        fun onModeChanged(newMode: TeachingMode) {}
        fun onPeerUpdated(peers: List<RemotePeer>) {}
    }

    private val listeners = CopyOnWriteArrayList<SessionEventListener>()
    private val currentStatus = AtomicReference(ConnectionStatus.IDLE)
    private val currentMode = AtomicReference(TeachingMode.TEACHER_EXCLUSIVE)
    private val activePeers = CopyOnWriteArrayList<RemotePeer>()

    var port: Int = 8765
        private set

    val status: ConnectionStatus
        get() = currentStatus.get()

    val mode: TeachingMode
        get() = currentMode.get()

    val peers: List<RemotePeer>
        get() = activePeers.toList()

    val accessUrl: String
        get() = "http://127.0.0.1:$port/?role=student"

    fun addListener(listener: SessionEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SessionEventListener) {
        listeners.remove(listener)
    }

    /**
     * 启动教学协同服务
     */
    fun startSession(customPort: Int = 8765): Boolean {
        if (currentStatus.get() != ConnectionStatus.IDLE) {
            return false
        }
        this.port = customPort

        // 模拟/启动嵌入式 WebSocket 服务
        updateStatus(ConnectionStatus.WAITING)
        return true
    }

    /**
     * 停止教学协同服务
     */
    fun stopSession() {
        activePeers.clear()
        updateStatus(ConnectionStatus.IDLE)
        listeners.forEach { it.onPeerUpdated(emptyList()) }
    }

    /**
     * 切换教学模式（老师独占 vs 自由协同）
     */
    fun setTeachingMode(newMode: TeachingMode) {
        if (currentMode.getAndSet(newMode) != newMode) {
            listeners.forEach { it.onModeChanged(newMode) }
        }
    }

    /**
     * 更新连接状态
     */
    fun updateStatus(status: ConnectionStatus) {
        if (currentStatus.getAndSet(status) != status) {
            listeners.forEach { it.onStatusChanged(status) }
        }
    }

    /**
     * 模拟或者注册对端加入（测试/事件驱动）
     */
    fun addOrUpdatePeer(peer: RemotePeer) {
        activePeers.removeIf { it.id == peer.id }
        activePeers.add(peer)
        if (currentStatus.get() == ConnectionStatus.WAITING && activePeers.isNotEmpty()) {
            updateStatus(ConnectionStatus.CONNECTED)
        }
        listeners.forEach { it.onPeerUpdated(activePeers.toList()) }
    }

    fun removePeer(peerId: String) {
        activePeers.removeIf { it.id == peerId }
        if (activePeers.isEmpty() && currentStatus.get() == ConnectionStatus.CONNECTED) {
            updateStatus(ConnectionStatus.WAITING)
        }
        listeners.forEach { it.onPeerUpdated(activePeers.toList()) }
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
