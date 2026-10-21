package top.cylunex.shadowmedia.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID
import kotlinx.coroutines.*
import net.openid.appauth.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** System-browser OIDC. No password or token is placed in the Activity result. */
class NovelLoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var service: AuthorizationService
    private lateinit var store: CatalogConnectionStore
    private lateinit var status: TextView
    private var attemptId = ""
    private var stage = NovelLoginStage.CONFIGURATION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CatalogConnectionStore(this, "novel_connections", "shadow_novel_connections_v1")
        service = AuthorizationService(this)
        status = TextView(this).apply { text = "正在连接统一认证…"; setPadding(40, 60, 40, 40) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(status)
            addView(Button(this@NovelLoginActivity).apply { text = "返回"; setOnClickListener { runCatching { store.cancelLogin(attemptId) }; finish() } })
        })
        attemptId = savedInstanceState?.getString("attempt").orEmpty()
        if (savedInstanceState == null) scope.launch {
            try {
                check(!LibraryResources.offlineOnly) { "请先退出仅离线模式" }
                val base = requireNotNull(intent.getStringExtra("url")).toHttpUrl()
                val config = NovelLoginConfig.parse(base, novelAuthJson(base, "auth/native/config"))
                AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.issuer)) { discovery, error ->
                    if (isFinishing || isDestroyed) return@fetchFromIssuer
                    try {
                    if (discovery == null || error != null) { failed(); return@fetchFromIssuer }
                    if (discovery.discoveryDoc?.docJson?.optString("issuer") != config.issuer ||
                        discovery.authorizationEndpoint.scheme != "https" || discovery.tokenEndpoint?.scheme != "https") {
                        failed(); return@fetchFromIssuer
                    }
                    val request = AuthorizationRequest.Builder(discovery, config.clientId, ResponseTypeValues.CODE, Uri.parse(NOVEL_OIDC_REDIRECT))
                        .setScope("openid profile offline_access").setAdditionalParameters(mapOf("resource" to config.resource)).build()
                    attemptId = UUID.randomUUID().toString()
                    store.beginLogin(JSONObject().put("attempt", attemptId).put("created", System.currentTimeMillis())
                        .put("request", request.jsonSerializeString()).put("issuer", config.issuer).put("base", config.apiBase)
                        .put("name", intent.getStringExtra("name") ?: "Shadow Novel").put("previousId", intent.getStringExtra("connectionId").orEmpty()))
                    stage = NovelLoginStage.BROWSER
                    @Suppress("DEPRECATION")
                    startActivityForResult(service.getAuthorizationRequestIntent(request), 110)
                    } catch (_: Exception) { failed() }
                }
            } catch (_: CancellationException) { throw CancellationException() }
            catch (error: Exception) { failed((error as? NovelLoginHttpException)?.status) }
        }
        else {
            val pending = runCatching { store.pendingLogin() }.getOrNull()
            if (pending == null || pending.optString("attempt") != attemptId || pending.optBoolean("consumed") ||
                System.currentTimeMillis() - pending.optLong("created") !in 0..600_000) failed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("attempt", attemptId); super.onSaveInstanceState(outState) }

    @Deprecated("AppAuth uses the platform activity result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 110) return
        stage = NovelLoginStage.CALLBACK
        try {
            val attempt = store.consumeLogin(attemptId) ?: error("Login expired")
            val response = data?.let(AuthorizationResponse::fromIntent)
            if (response == null) {
                failed(oauthError = data?.let(AuthorizationException::fromIntent)?.error
                    ?: if (resultCode == RESULT_CANCELED) "access_denied" else null)
                return
            }
            val expected = AuthorizationRequest.jsonDeserialize(attempt.getString("request"))
            check(response.state == expected.state && response.request.nonce == expected.nonce &&
                response.request.jsonSerializeString() == expected.jsonSerializeString())
            val state = AuthState(response, null)
            stage = NovelLoginStage.TOKEN
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, error ->
                if (isFinishing || isDestroyed) return@performTokenRequest
                state.update(tokens, error)
                if (tokens == null || error != null || state.accessToken == null) { failed(oauthError = error?.error); return@performTokenRequest }
                scope.launch {
                    try {
                        stage = NovelLoginStage.IDENTITY
                        val identity = novelAuthJson(attempt.getString("base").toHttpUrl(), "auth/me", state.accessToken)
                        check(identity.getString("issuer") == attempt.getString("issuer") && identity.getString("sub").isNotBlank())
                        val connection = CatalogConnection(name = attempt.getString("name"), kind = CatalogKind.OPDS, url = attempt.getString("base"),
                            oidcIssuer = identity.getString("issuer"), oidcSubject = identity.getString("sub"),
                            oidcClientId = expected.clientId, oidcAuthState = state.jsonSerializeString())
                        stage = NovelLoginStage.SAVE
                        val accepted = store.finishLogin(attemptId, connection)
                        setResult(RESULT_OK, Intent().putExtra("connectionId", accepted.id)); finish()
                    } catch (_: CancellationException) { throw CancellationException() }
                    catch (error: Exception) { failed((error as? NovelLoginHttpException)?.status) }
                }
            }
        } catch (_: Exception) { failed() }
    }

    private fun failed(httpStatus: Int? = null, oauthError: String? = null) {
        runCatching { store.cancelLogin(attemptId) }
        val message = novelLoginFailure(stage, httpStatus, oauthError)
        runOnUiThread { status.text = message }
    }
    override fun onDestroy() {
        if (isFinishing) runCatching { store.cancelLogin(attemptId) }
        scope.cancel(); service.dispose(); super.onDestroy()
    }
}
