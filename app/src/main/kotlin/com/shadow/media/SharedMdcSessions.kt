package com.shadow.media

import com.shadow.media.library.*
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Service connections reference the existing encrypted account; never copy refresh tokens. */
internal class SharedMdcSessions(private val novel: NovelRepository) {
    @Volatile private var discovered: Map<String, Pair<CatalogConnection, CatalogConnection>> = emptyMap()

    fun connections(): List<CatalogConnection> {
        val accounts = novel.availableConnections()
        return discovered.values.filter { (_, account) ->
            accounts.any { it.id == account.id && it.oidcAuthState.isNotBlank() && it.retainsAccountOf(account) }
        }.map { it.first }
    }

    suspend fun discover(): List<CatalogConnection> {
        val result = mutableMapOf<String, Pair<CatalogConnection, CatalogConnection>>()
        for (account in novel.availableConnections().filter { it.oidcIssuer.isNotBlank() && it.oidcAuthState.isNotBlank() }) {
            val manifest = try { novel.live(account, listOf("services")) }
            catch (e: CancellationException) { throw e }
            catch (e: NovelApiException) { if (e.status == 404) continue else throw e }
            sharedMdcConnection(account, manifest)?.let { result[it.id] = it to account }
        }
        discovered = result
        return connections()
    }

    suspend fun token(connection: CatalogConnection, force: Boolean): String {
        val (service, account) = discovered[connection.id] ?: throw java.io.IOException("统一服务连接已失效，请重新打开 MDC")
        if (connection != service) throw java.io.IOException("统一服务地址已变化，请重新打开 MDC")
        return novel.oidc.token(account, force)
    }
}

internal fun CatalogConnection.isSharedMdc() = id.startsWith("shared-mdc:")

internal fun sharedMdcConnection(account: CatalogConnection, manifest: JSONObject): CatalogConnection? {
    val row = manifest.novelObjects("services").singleOrNull { it.optString("id") == "mdc" } ?: return null
    val base = account.base()
    val url = row.getString("base_url").toHttpUrl()
    val expected = base.newBuilder().addPathSegments("services/mdc/").build()
    require(base.isHttps && url == expected) { "统一服务必须使用已登录账号的同源服务入口" }
    return CatalogConnection(id = "shared-mdc:${account.id}", name = "Shadow MDC · ${account.name}",
        kind = CatalogKind.OPDS, url = url.toString())
}
