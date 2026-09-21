package com.jev.relationship.xposed.hook

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Wechat072MessageAdapterTest {
    @Test
    fun `installs exact Cb target and emits mapped message after original`() {
        val installer = RecordingAfterHookInstaller()
        val emitted = mutableListOf<com.jev.relationship.ipc.CapturedMessage>()
        val adapter = adapter(installer)

        assertEquals(
            HookInstallResult.INSTALLED,
            adapter.installForClassLoader(testClassLoader) { message: com.jev.relationship.ipc.CapturedMessage -> emitted.add(message) },
        )
        assertEquals("Cb", installer.target?.name)
        assertEquals(1, installer.target?.parameterTypes?.size)

        installer.fireAfterOriginal(
            FixtureMessage(
                type = 1,
                content = "  hello  ",
                talker = " chat-1 ",
                isSend = 0,
                createTime = 1_700_000_000L,
                msgId = 12L,
                msgSvrId = 13L,
            ),
        )

        assertEquals(1, emitted.size)
        assertEquals("hello", emitted.single().text)
        assertTrue(installer.originalCompleted)
    }

    @Test
    fun `returns target class unavailable without installing`() {
        val installer = RecordingAfterHookInstaller()
        val adapter = adapter(installer) { name, _ ->
            throw ClassNotFoundException(name)
        }

        assertEquals(
            HookInstallResult.TARGET_CLASS_UNAVAILABLE,
            adapter.installForClassLoader(testClassLoader) { },
        )
        assertEquals(0, installer.installCount)
    }

    @Test
    fun `returns target method unavailable without installing`() {
        val installer = RecordingAfterHookInstaller()
        val adapter = adapter(installer) { name, _ ->
            when (name) {
                "com.tencent.mm.storage.f9" -> FixtureMessage::class.java
                "com.tencent.mm.storage.h9" -> FixtureStorageWithoutTarget::class.java
                else -> throw ClassNotFoundException(name)
            }
        }

        assertEquals(
            HookInstallResult.TARGET_METHOD_UNAVAILABLE,
            adapter.installForClassLoader(testClassLoader) { },
        )
        assertEquals(0, installer.installCount)
    }

    @Test
    fun `filters non text and invalid ids before emission`() {
        val installer = RecordingAfterHookInstaller()
        val emittedCount = AtomicInteger()
        val adapter = adapter(installer)
        assertEquals(
            HookInstallResult.INSTALLED,
            adapter.installForClassLoader(testClassLoader) { emittedCount.incrementAndGet() },
        )

        installer.fireAfterOriginal(FixtureMessage(type = 3))
        installer.fireAfterOriginal(FixtureMessage(msgId = 0L, msgSvrId = -1L))

        assertEquals(0, emittedCount.get())
    }

    @Test
    fun `validates and deduplicates before emission`() {
        val installer = RecordingAfterHookInstaller()
        val emitted = mutableListOf<com.jev.relationship.ipc.CapturedMessage>()
        val adapter = adapter(installer)
        assertEquals(
            HookInstallResult.INSTALLED,
            adapter.installForClassLoader(testClassLoader) { message: com.jev.relationship.ipc.CapturedMessage -> emitted.add(message) },
        )
        val message = FixtureMessage(msgId = 91L)

        installer.fireAfterOriginal(message)
        installer.fireAfterOriginal(message)
        installer.fireAfterOriginal(
            message.copy(content = "x".repeat(4_001), msgId = 92L),
        )

        assertEquals(1, emitted.size)
        assertEquals("wechat-8.0.72-91", emitted.single().messageId)
    }

    @Test
    fun `does not throw when emitter fails`() {
        val installer = RecordingAfterHookInstaller()
        val adapter = adapter(installer)
        assertEquals(
            HookInstallResult.INSTALLED,
            adapter.installForClassLoader(testClassLoader) {
                throw IllegalStateException("test emitter failure")
            },
        )

        installer.fireAfterOriginal(FixtureMessage())
        assertTrue(installer.originalCompleted)
    }

    @Test
    fun `install is idempotent and uninstall releases hook`() {
        val installer = RecordingAfterHookInstaller()
        val adapter = adapter(installer)

        assertEquals(HookInstallResult.INSTALLED, adapter.installForClassLoader(testClassLoader) { })
        assertEquals(HookInstallResult.ALREADY_INSTALLED, adapter.installForClassLoader(testClassLoader) { })
        adapter.uninstall()
        assertTrue(installer.unhooked)
        assertFalse(installer.originalCompleted)
        assertEquals(HookInstallResult.INSTALLED, adapter.installForClassLoader(testClassLoader) { })
        assertEquals(2, installer.installCount)
    }

    private fun adapter(
        installer: RecordingAfterHookInstaller,
        resolver: (String, ClassLoader) -> Class<*> = ::resolveFixture,
    ) = Wechat072MessageAdapter(
        installer = installer,
        classResolver = resolver,
        logger = { _ -> },
    )

    private fun resolveFixture(name: String, @Suppress("UNUSED_PARAMETER") loader: ClassLoader): Class<*> = when (name) {
        "com.tencent.mm.storage.f9" -> FixtureMessage::class.java
        "com.tencent.mm.storage.h9" -> FixtureStorage::class.java
        else -> throw ClassNotFoundException(name)
    }

    private class RecordingAfterHookInstaller : WechatAfterHookInstaller {
        var target: Method? = null
        var callback: ((Any?) -> Unit)? = null
        var installCount = 0
        var unhooked = false
        var originalCompleted = false

        override fun install(target: Method, after: (Any?) -> Unit): WechatHookHandle {
            this.target = target
            callback = after
            installCount += 1
            unhooked = false
            return WechatHookHandle { unhooked = true }
        }

        fun fireAfterOriginal(message: Any) {
            originalCompleted = false
            originalCompleted = true
            callback?.invoke(message)
        }
    }

    private companion object {
        val testClassLoader: ClassLoader = Wechat072MessageAdapterTest::class.java.classLoader
            ?: error("test class loader unavailable")
    }
}

data class FixtureMessage(
    private val type: Int = 1,
    private val content: String = "你好",
    private val talker: String = "chat-1",
    private val isSend: Int = 0,
    private val createTime: Long = 1_700_000_000L,
    private val msgId: Long = 1L,
    private val msgSvrId: Long = 2L,
) {
    fun getType(): Int = type
    fun j(): String = content
    fun O0(): String = talker
    fun C0(): Int = isSend
    fun getCreateTime(): Long = createTime
    fun getMsgId(): Long = msgId
    fun I0(): Long = msgSvrId
}

class FixtureStorage {
    fun Cb(message: FixtureMessage): Long = message.getMsgId()
}

class FixtureStorageWithoutTarget
