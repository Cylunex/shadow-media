package com.shadow.media.library

import org.junit.Assert.*
import org.junit.Test

class NovelLoginFailureTest {
    @Test fun `identity rejection is distinct from browser cancellation`() {
        val rejected = novelLoginFailure(NovelLoginStage.IDENTITY, 401)
        assertTrue(rejected.contains("统一账号已登录")); assertTrue(rejected.contains("HTTP 401"))
        assertFalse(rejected.contains("已取消"))
        assertTrue(novelLoginFailure(NovelLoginStage.CALLBACK, oauthError = "access_denied").contains("已取消登录"))
    }
    @Test fun `unknown errors cannot expose raw credentials`() {
        assertFalse(novelLoginFailure(NovelLoginStage.TOKEN, oauthError = "private-code-or-token").contains("private-code-or-token"))
        assertTrue(novelLoginFailure(NovelLoginStage.CONFIGURATION, 503).contains("读取统一认证配置"))
        assertTrue(novelLoginFailure(NovelLoginStage.TOKEN, oauthError = "invalid_grant").contains("凭据已失效"))
    }
}
