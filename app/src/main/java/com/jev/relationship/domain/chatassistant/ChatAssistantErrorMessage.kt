package com.jev.relationship.domain.chatassistant

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import retrofit2.HttpException

object ChatAssistantErrorMessage {
    fun modelRequest(error: Throwable?): String {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any { it.message.orEmpty().contains("workspace-id", ignoreCase = true) }) {
            return "百炼 Base URL 中的 workspace-id 是占位符，请替换为实际业务空间 ID；北京地域也可使用 https://dashscope.aliyuncs.com/compatible-mode/v1。"
        }
        val http = causes.filterIsInstance<HttpException>().firstOrNull()
        if (http != null) {
            return when (http.code()) {
                401, 403 -> "模型服务鉴权失败（HTTP ${http.code()}），请检查理解模型 API Key 和权限。"
                404 -> "模型接口或模型名称不存在（HTTP 404），请检查服务地址和模型名称。"
                408, 504 -> "模型服务响应超时（HTTP ${http.code()}），请重试或检查服务状态。"
                413 -> "聊天上下文超过模型接口容量（HTTP 413），请缩短聊天记录或调整模型上下文限制。"
                429 -> "模型服务限流或额度不足（HTTP 429），请检查账户额度或稍后重试。"
                400 -> "模型接口拒绝了请求（HTTP 400），请检查模型名称、接口兼容性和上下文长度。"
                in 500..599 -> "模型服务暂时不可用（HTTP ${http.code()}），请稍后重试。"
                else -> "模型服务请求失败（HTTP ${http.code()}），请检查服务配置。"
            }
        }
        if (causes.any { it is UnknownHostException }) {
            return "模型服务域名解析失败，请检查网络或服务地址。"
        }
        if (causes.any { it is SocketTimeoutException ||
                (it is InterruptedIOException && it.message.equals("timeout", ignoreCase = true)) }) {
            return "模型请求超时，请检查网络或模型服务状态后重试。"
        }
        if (causes.any { it is SSLException }) {
            return "模型服务安全连接失败，请检查服务地址的 HTTPS 证书和网络。"
        }
        if (causes.any { it is ConnectException }) {
            return "无法连接模型服务，请检查网络和服务地址。"
        }
        if (causes.any { it.message?.contains("AI provider returned an empty response") == true }) {
            return "模型接口返回内容为空，请检查模型是否兼容 OpenAI 对话接口。"
        }
        if (causes.any { it is IOException }) {
            return "模型请求发生网络 I/O 错误，请检查网络后重试。"
        }
        return "模型返回格式无效或接口不兼容，请确认服务支持 OpenAI chat/completions 接口。"
    }
}
