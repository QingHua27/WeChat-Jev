package com.jev.relationship.xposed.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.jev.relationship.ipc.IpcHello
import com.jev.relationship.ipc.IpcProtocol
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class JevIpcClientAutomaticConnectionTest {
    @Test fun `foreground resume preserves a binding already in flight`() {
        var binds = 0
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                binds++
                return true
            }
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val client = JevIpcClient(context, { "token" }, "test")
        client.start()
        shadowOf(Looper.getMainLooper()).idle()
        repeat(3) { client.ensureConnected() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, binds)
        client.close()
    }

    @Test fun `dead binding is released and automatically rebound`() {
        var connection: ServiceConnection? = null
        var binds = 0
        var unbinds = 0
        var authenticatedCallbacks = 0
        val server = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what == IpcProtocol.MSG_HELLO) message.replyTo.send(
                    Message.obtain(null, IpcProtocol.MSG_HANDSHAKE_RESULT).apply {
                        data = com.jev.relationship.ipc.IpcCodec.encodeHandshakeResult(
                            com.jev.relationship.ipc.HandshakeResult(true),
                        )
                    },
                )
            }
        })
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, callback: ServiceConnection, flags: Int): Boolean {
                connection = callback
                binds++
                callback.onServiceConnected(ComponentName("com.jev.relationship", "JevIpcService"), server.binder)
                return true
            }
            override fun unbindService(connection: ServiceConnection) { unbinds++ }
        }
        val client = JevIpcClient(context, { "token" }, "test", onConnected = { authenticatedCallbacks++ })
        client.start()
        shadowOf(Looper.getMainLooper()).idle()
        connection!!.onBindingDied(ComponentName("com.jev.relationship", "JevIpcService"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, binds)
        assertEquals(1, unbinds)
        assertEquals("each recovered handshake notifies the cached UI", 2, authenticatedCallbacks)
        client.close()
    }

    @Test fun `bind bootstraps app before token and delayed token sends authenticated hello`() {
        val messages = mutableListOf<Message>()
        val server = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) { messages.add(Message.obtain(message)) }
        })
        var binds = 0
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                binds++
                connection.onServiceConnected(ComponentName("com.jev.relationship", "JevIpcService"), server.binder)
                return true
            }
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        var token: String? = null
        val client = JevIpcClient(context, { token }, "test")
        client.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, binds)
        assertTrue(messages.isEmpty())
        token = "new-automatic-token"
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val hello = messages.single { it.what == IpcProtocol.MSG_HELLO }
        assertEquals(token, IpcHello.fromBundle(hello.data).pairingToken)
        client.close()
        val count = messages.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(35))
        assertEquals(count, messages.size)
    }
}
