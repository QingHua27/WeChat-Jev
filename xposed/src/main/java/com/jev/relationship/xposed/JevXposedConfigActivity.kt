package com.jev.relationship.xposed

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class JevXposedConfigActivity : Activity() {
    private lateinit var status: TextView
    private var pairingToken: String? = null
    private var clearPairing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingToken = intent.getStringExtra(EXTRA_PAIRING_TOKEN)?.trim()?.takeIf(String::isNotEmpty)
        clearPairing = intent.getBooleanExtra(EXTRA_CLEAR_PAIRING, false)
        status = TextView(this).apply {
            textSize = 16f
            text = "正在连接 LSPosed 配置服务……"
        }
        val retry = Button(this).apply {
            text = "重试"
            setOnClickListener(::attemptProvisioning)
        }
        val simulate = Button(this).apply {
            text = "安排一次 Phase 3 模拟消息"
            setOnClickListener {
                val preferences = XposedServiceBridge.remotePreferences()
                val saved = preferences?.edit()
                    ?.putBoolean(XposedModulePreferences.SIMULATION_ENABLED, true)
                    ?.commit() == true
                status.text = if (saved) {
                    "已安排：下次启动微信时发送一条合成测试消息。"
                } else {
                    "LSPosed 服务未就绪，无法安排模拟消息。"
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@JevXposedConfigActivity).apply {
                text = "Jev 微信消息桥接"
                textSize = 22f
            })
            addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(retry, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(simulate, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        if (pairingToken == null && !clearPairing) {
            status.text = "缺少配对信息，请从 Jev 设置重新发起连接。"
            retry.isEnabled = false
            setResult(RESULT_CANCELED)
        } else {
            attemptProvisioning()
        }
    }

    private fun attemptProvisioning(@Suppress("UNUSED_PARAMETER") view: android.view.View? = null) {
        val preferences = XposedServiceBridge.remotePreferences()
        if (preferences == null) {
            status.text = "LSPosed 配置服务未连接。请确认模块已启用并重试。"
            return
        }
        if (clearPairing) {
            if (!XposedPairingProvisioner.clear(preferences)) {
                status.text = "远端令牌未能清除。Jev 已在本机停用接入，可稍后重试清除。"
                return
            }
            setResult(RESULT_OK)
            status.text = "已清除 LSPosed 中的配对令牌。"
            finish()
            return
        }
        val token = pairingToken ?: return
        if (!XposedPairingProvisioner.save(preferences, token)) {
            status.text = "配对信息写入失败，请检查 LSPosed 服务后重试。"
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PAIRING_TOKEN, token))
        status.text = "已连接 LSPosed。返回 Jev 完成配对。"
        finish()
    }

    companion object {
        const val EXTRA_PAIRING_TOKEN = "com.jev.relationship.xposed.PAIRING_TOKEN"
        const val EXTRA_CLEAR_PAIRING = "com.jev.relationship.xposed.CLEAR_PAIRING"
    }
}
