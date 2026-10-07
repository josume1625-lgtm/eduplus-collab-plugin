package com.eduplus.collab.editor

import com.intellij.openapi.util.Key

/**
 * 远程写回隔离门闩守卫
 * 用于在学生端编辑写回本地 Document 时，屏蔽本地 DocumentListener 的二次广播，杜绝 Echo Loop 无限死循环。
 */
object RemoteApplyGuard {
    val IS_REMOTE_EDIT_KEY = Key.create<Boolean>("eduplus.collaboration.is_remote_edit")
    @PublishedApi
    internal val localFlag = ThreadLocal.withInitial { false }

    /**
     * 判断当前调用栈是否属于学生远程写入触发
     */
    val isRemoteUpdating: Boolean
        get() = localFlag.get()

    /**
     * 在隔离保护作用域下执行代码
     */
    inline fun <T> runGuarded(block: () -> T): T {
        localFlag.set(true)
        try {
            return block()
        } finally {
            localFlag.set(false)
        }
    }
}
