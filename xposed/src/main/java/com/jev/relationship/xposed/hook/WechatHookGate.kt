package com.jev.relationship.xposed.hook

data class WechatVersion(
    val versionName: String,
    val versionCode: Long,
)

enum class HookInstallResult {
    INSTALLED,
    UNSUPPORTED_PACKAGE,
    UNSUPPORTED_PROCESS,
    UNSUPPORTED_VERSION,
    TARGET_CLASS_UNAVAILABLE,
    TARGET_METHOD_UNAVAILABLE,
    ALREADY_INSTALLED,
    FAILED,
}

object WechatHookGate {
    const val PACKAGE_NAME = "com.tencent.mm"
    const val MAIN_PROCESS = "com.tencent.mm"
    const val TARGET_VERSION_NAME = "8.0.72"
    const val TARGET_VERSION_CODE = 3085L

    fun accepts(
        packageName: String?,
        processName: String?,
        version: WechatVersion,
    ): Boolean = packageName == PACKAGE_NAME &&
        processName == MAIN_PROCESS &&
        version.versionName == TARGET_VERSION_NAME &&
        version.versionCode == TARGET_VERSION_CODE
}
