package com.jev.relationship.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedModuleMetadataTest {
    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path))
            .bufferedReader().use { it.readText() }

    @Test
    fun `module metadata targets WeChat and names the Xposed entry`() {
        assertEquals("com.jev.relationship.xposed.JevXposedModule", resource("META-INF/xposed/java_init.list").trim())
        assertEquals("com.tencent.mm", resource("META-INF/xposed/scope.list").trim())
        assertTrue(resource("META-INF/xposed/module.prop").contains("staticScope=true"))
    }
}
