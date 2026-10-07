package com.eduplus.collab.arbitration

import com.eduplus.collab.protocol.UserRole
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 教学写操作仲裁结果
 */
sealed class ArbitrationResult {
    object Allowed : ArbitrationResult()
    data class Blocked(val remainingMs: Long, val reason: String) : ArbitrationResult()
}

/**
 * 老师写锁状态变更监听器
 */
interface TeacherLockListener {
    fun onLockAcquired(teacherName: String, lockWindowMs: Long)
    fun onLockReleased()
}

/**
 * 教学冲突消解与老师优先写锁引擎 (Teacher Priority Lock Manager)
 *
 * 核心规则：
 * 1. 老师键入时自动激活独占写锁窗口 (默认 500ms)。
 * 2. 老师连续敲击按键平滑滑动续期锁窗口 (Sliding Expiry)。
 * 3. 锁定期间，学生端代码修改 (code_delta) 被拦截并反馈等待倒计时。
 * 4. 老师停止敲击 500ms 后释放写锁，恢复自由协同模式。
 */
class TeacherPriorityLockManager(
    private val lockWindowMs: Long = 500L,
    private val listeners: MutableList<TeacherLockListener> = mutableListOf()
) {
    private val lock = ReentrantLock()
    private val isTeacherLocked = AtomicBoolean(false)
    private val lockExpireTime = AtomicLong(0L)
    private val activeTeacherName = java.util.concurrent.atomic.AtomicReference("Teacher")

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Teacher-Lock-Scheduler").apply { isDaemon = true }
    }
    private var releaseTask: ScheduledFuture<*>? = null

    fun addListener(listener: TeacherLockListener) {
        lock.withLock { listeners.add(listener) }
    }

    fun removeListener(listener: TeacherLockListener) {
        lock.withLock { listeners.remove(listener) }
    }

    /**
     * 校验并发写权限并触发状态机迁移
     */
    fun checkAndAcquireWrite(role: UserRole, userName: String): ArbitrationResult {
        val now = System.currentTimeMillis()

        if (role == UserRole.TEACHER) {
            // 老师拥有绝对优先权：激活或续期锁
            acquireOrRenewTeacherLock(userName, now)
            return ArbitrationResult.Allowed
        }

        // 学生角色校验
        lock.withLock {
            val expireAt = lockExpireTime.get()
            if (isTeacherLocked.get() && now < expireAt) {
                val remaining = expireAt - now
                return ArbitrationResult.Blocked(
                    remainingMs = remaining,
                    reason = "👨‍🏫 老师正在书写演示，输入已锁定"
                )
            }
        }
        return ArbitrationResult.Allowed
    }

    /**
     * 老师敲击键盘：原子激活或平滑刷新锁窗口
     */
    fun onTeacherKeystroke(teacherName: String) {
        acquireOrRenewTeacherLock(teacherName, System.currentTimeMillis())
    }

    private fun acquireOrRenewTeacherLock(teacherName: String, now: Long) {
        lock.withLock {
            activeTeacherName.set(teacherName)
            lockExpireTime.set(now + lockWindowMs)

            // 取消此前的释放定时器
            releaseTask?.cancel(false)

            val wasLocked = isTeacherLocked.getAndSet(true)
            if (!wasLocked) {
                notifyLockAcquired(teacherName, lockWindowMs)
            }

            // 调度到期自动释放
            releaseTask = scheduler.schedule({
                checkAndReleaseLock()
            }, lockWindowMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun checkAndReleaseLock() {
        lock.withLock {
            val now = System.currentTimeMillis()
            if (now >= lockExpireTime.get()) {
                if (isTeacherLocked.getAndSet(false)) {
                    notifyLockReleased()
                }
            }
        }
    }

    private fun notifyLockAcquired(teacherName: String, windowMs: Long) {
        listeners.forEach {
            try {
                it.onLockAcquired(teacherName, windowMs)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun notifyLockReleased() {
        listeners.forEach {
            try {
                it.onLockReleased()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isCurrentlyLocked(): Boolean {
        return isTeacherLocked.get() && System.currentTimeMillis() < lockExpireTime.get()
    }

    fun shutdown() {
        lock.withLock {
            releaseTask?.cancel(true)
            scheduler.shutdownNow()
            listeners.clear()
        }
    }
}
