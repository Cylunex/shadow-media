package com.shadow.media

import com.shadow.media.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal data class MdcDiscovery(val connections: List<CatalogConnection>, val failedAccounts: List<String>)

internal fun readMdcConnections(
    manual: () -> List<CatalogConnection>, shared: () -> List<CatalogConnection>,
): MdcDiscovery {
    val failed = mutableListOf<String>()
    fun read(name: String, load: () -> List<CatalogConnection>) = try { load() }
    catch (e: CancellationException) { throw e }
    catch (_: IllegalStateException) { failed += name; emptyList() }
    return MdcDiscovery(read("手动 MDC 来源", manual) + read("统一账号", shared), failed)
}

/** Service connections reference the existing encrypted account; never copy refresh tokens. */
internal class SharedMdcSessions(private val novel: NovelRepository) {
    @Volatile private var discovered: Map<String, Pair<CatalogConnection, CatalogConnection>> = emptyMap()

    fun connections(): List<CatalogConnection> {
        if (discovered.isEmpty()) return emptyList()
        val accounts = novel.availableConnections()
        return discovered.values.filter { (_, account) ->
            accounts.any { it.id == account.id && it.oidcAuthState.isNotBlank() && it.retainsAccountOf(account) }
        }.map { it.first }
    }

    private val discoveryLock = Mutex()
    private var lastAccounts = emptyList<CatalogConnection>()
    private var lastSuccess: Long? = null

    suspend fun discover(force: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        discoveryLock.withLock {
            val accounts = novel.availableConnections().filter { it.oidcIssuer.isNotBlank() && it.oidcAuthState.isNotBlank() }
            val sameAccounts = accounts.size == lastAccounts.size && accounts.all { current ->
                lastAccounts.any { it.id == current.id && it.name == current.name && current.retainsAccountOf(it) }
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && sameAccounts && lastSuccess?.let { now - it < 60_000 } == true) return@withLock emptyList()
            val result = mutableMapOf<String, Pair<CatalogConnection, CatalogConnection>>()
            val failed = mutableListOf<String>()
            for (account in accounts) {
                try {
                    val manifest = novel.live(account, listOf("services"))
                    sharedMdcConnection(account, manifest)?.let { result[it.id] = it to account }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is NovelApiException && e.status == 404) continue
                    failed += account.name
                    if (e is java.io.IOException && !(e is NovelApiException && e.status in 400..499)) {
                        discovered["shared-mdc:${account.id}"]?.takeIf { account.retainsAccountOf(it.second) }
                            ?.let { result[it.first.id] = it }
                    }
                }
            }
            lastAccounts = accounts
            lastSuccess = if (failed.isEmpty()) now else null
            discovered = result
            failed
        }
    }

    suspend fun token(connection: CatalogConnection, rejected: String?): String {
        val (service, account) = discovered[connection.id] ?: throw java.io.IOException("统一服务连接已失效，请重新打开 MDC")
        if (connection != service) throw java.io.IOException("统一服务地址已变化，请重新打开 MDC")
        return novel.oidc.tokenAfterRejection(account, rejected)
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
        kind = CatalogKind.OPDS, url = url.toString(),
        username = "shared-" + java.util.UUID.nameUUIDFromBytes(
            "${account.oidcIssuer}\u0000${account.oidcSubject}".toByteArray(Charsets.UTF_8)))
}
