package com.jev.relationship.domain.chatassistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ChatAssistantErrorMessageTest {
    @Test
    fun `unauthorized response points to model credentials without exposing response body`() {
        val failure = HttpException(
            Response.error<Any>(401, "private provider response".toResponseBody("text/plain".toMediaType())),
        )

        val message = ChatAssistantErrorMessage.modelRequest(failure)

        assertTrue(message.contains("HTTP 401"))
        assertTrue(message.contains("API Key"))
        assertFalse(message.contains("private provider response"))
    }

    @Test
    fun `unknown host and timeout have different actionable messages`() {
        assertTrue(ChatAssistantErrorMessage.modelRequest(UnknownHostException()).contains("域名解析"))
        assertTrue(ChatAssistantErrorMessage.modelRequest(SocketTimeoutException()).contains("超时"))
    }

    @Test
    fun `OkHttp whole call timeout is identified instead of generic network IO`() {
        assertTrue(ChatAssistantErrorMessage.modelRequest(java.io.InterruptedIOException("timeout")).contains("超时"))
        assertFalse(ChatAssistantErrorMessage.modelRequest(java.io.IOException("connection reset")).contains("超时"))
    }

    @Test
    fun `empty model response points to compatibility`() {
        assertTrue(
            ChatAssistantErrorMessage.modelRequest(IllegalArgumentException("AI provider returned an empty response"))
                .contains("返回内容为空"),
        )
    }

    @Test
    fun `literal Bailian workspace placeholder gives a base URL fix`() {
        val message = ChatAssistantErrorMessage.modelRequest(
            IllegalArgumentException("Invalid URL host: [workspace-id].cn-beijing.maas.aliyuncs.com"),
        )

        assertTrue(message.contains("workspace-id"))
        assertTrue(message.contains("dashscope.aliyuncs.com/compatible-mode/v1"))
    }
}
