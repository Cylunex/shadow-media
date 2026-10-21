package top.cylunex.shadowmedia.library

internal enum class NovelLoginStage(val label: String) {
    CONFIGURATION("读取统一认证配置"), BROWSER("打开统一登录"), CALLBACK("校验登录回跳"),
    TOKEN("交换登录凭据"), IDENTITY("验证小说账号"), SAVE("保存登录状态")
}

/** Expose a safe stage/status, never provider descriptions, callback codes or tokens. */
internal fun novelLoginFailure(stage: NovelLoginStage, status: Int? = null, oauthError: String? = null): String {
    if (oauthError == "access_denied") return "已取消登录，请返回后重试。"
    if (stage == NovelLoginStage.IDENTITY && status in setOf(401, 403)) {
        return "统一账号已登录，但小说服务未接受此身份（HTTP $status）。请返回重试；若持续出现，请提供此提示。"
    }
    if (stage == NovelLoginStage.TOKEN && oauthError == "invalid_grant") return "登录凭据已失效，请返回并重新登录。"
    val http = status?.let { "（HTTP $it）" }.orEmpty()
    return "${stage.label}失败$http，请返回重试。"
}
