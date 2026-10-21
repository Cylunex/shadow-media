package top.cylunex.shadowmedia.library

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationService
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONObject
import kotlin.coroutines.resume

const val NOVEL_OIDC_REDIRECT = "top.cylunex.shadowmedia.novel:/oauth2redirect"
internal class NovelLoginHttpException(val status: Int) : java.io.IOException("Login endpoint returned HTTP $status")

internal fun authRequired(): Nothing = throw NovelApiException(401, "authentication_required", JSONObject())

internal fun sameNovelOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

internal suspend fun novelAuthJson(base: HttpUrl, path: String, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
    require(base.isHttps && base.username.isBlank() && base.password.isBlank()) { "统一认证需要 HTTPS 地址" }
    val url = base.newBuilder().apply { if (!base.encodedPath.endsWith('/')) addPathSegment("") }.build().resolve(path)!!
    val client = OkHttpClient.Builder().addInterceptor(top.cylunex.shadowmedia.network.OfflineModeInterceptor())
        .addNetworkInterceptor { chain ->
            require(sameNovelOrigin(base, chain.request().url) && chain.request().url.isHttps) { "拒绝认证跨源重定向" }
            chain.proceed(chain.request())
        }.build()
    val call = client.newCall(Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }.build())
    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
    try { call.execute().use {
        if (!it.isSuccessful) throw NovelLoginHttpException(it.code)
        JSONObject(SafeArchives.readBounded(requireNotNull(it.body).byteStream(), 128 * 1024).toString(Charsets.UTF_8))
    } } finally { cancellation.cancel() }
}

internal data class NovelLoginConfig(val issuer: String, val clientId: String, val resource: String, val apiBase: String) {
    companion object {
        fun parse(base: HttpUrl, json: JSONObject): NovelLoginConfig {
            val issuer = json.getString("issuer").toHttpUrl()
            val api = json.getString("api_base").toHttpUrl()
            val resource = json.getString("resource").toHttpUrl()
            require(base.isHttps && issuer.isHttps && issuer.username.isBlank() && issuer.password.isBlank() && issuer.query == null && issuer.fragment == null)
            require(sameNovelOrigin(base, api) && sameNovelOrigin(base, resource) && api.username.isBlank() && api.password.isBlank() && api.query == null && api.fragment == null)
            require(json.getString("redirect_uri") == NOVEL_OIDC_REDIRECT && json.getString("client_id").isNotBlank()) { "服务端未配置原生统一认证" }
            return NovelLoginConfig(json.getString("issuer"), json.getString("client_id"), json.getString("resource"), api.toString())
        }
    }
}

/** A single refresh per process; rotations never change the verified account's scope. */
class NovelOidcTokens(private val context: Context, private val store: CatalogConnectionStore) : NovelBearerTokens {
    override suspend fun token(connection: CatalogConnection, forceRefresh: Boolean): String = refreshLock.withLock {
        val current = store.load().singleOrNull { it.id == connection.id } ?: authRequired()
        if (!connection.retainsAccountOf(current) || current.oidcAuthState.isBlank()) authRequired()
        val state = runCatching { AuthState.jsonDeserialize(current.oidcAuthState) }.getOrElse { authRequired() }
        if (forceRefresh) state.setNeedsTokenRefresh(true)
        val service = AuthorizationService(context.applicationContext)
        try {
            suspendCancellableCoroutine { continuation ->
                state.performActionWithFreshTokens(service) { token, _, error ->
                    if (!continuation.isActive) return@performActionWithFreshTokens
                    if (token == null || error != null) {
                        val permanent = error?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR && error.error !in setOf("server_error", "temporarily_unavailable")
                        if (permanent) store.updateAuthState(current.id, current.oidcAuthState, "")
                        if (permanent) continuation.resumeWith(Result.failure(NovelApiException(401, "authentication_required", JSONObject())))
                        else continuation.resumeWith(Result.failure(java.io.IOException("统一认证暂不可用，请稍后重试")))
                    } else {
                        val saved = store.updateAuthState(current.id, current.oidcAuthState, state.jsonSerializeString())
                        if (saved) continuation.resume(token)
                        else continuation.resumeWith(Result.failure(NovelApiException(401, "authentication_required", JSONObject())))
                    }
                }
            }
        } finally { service.dispose() }
    }

    suspend fun logout(connection: CatalogConnection) {
        val previous = store.signOut(connection.id) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val state = AuthState.jsonDeserialize(previous.oidcAuthState)
                val endpoint = state.authorizationServiceConfiguration?.discoveryDoc?.docJson?.optString("revocation_endpoint")?.takeIf { it.isNotBlank() }?.toHttpUrl() ?: return@runCatching
                require(endpoint.isHttps && sameNovelOrigin(previous.oidcIssuer.toHttpUrl(), endpoint))
                val client = OkHttpClient.Builder().followRedirects(false).build()
                listOfNotNull(state.refreshToken, state.accessToken).distinct().forEach { token ->
                    val body = FormBody.Builder().add("token", token).add("client_id", previous.oidcClientId).build()
                    client.newCall(Request.Builder().url(endpoint).post(body).build()).execute().close()
                }
            }
        }
    }
    companion object { private val refreshLock = Mutex() }
}
