package top.cylunex.shadowmedia.model

import java.net.URI
import java.security.MessageDigest

/** Includes the reverse-proxy path: cloned server ids never identify the same account. */
fun accountScope(protocol: String, address: String, serverId: String, userId: String, profileId: String = "default"): String {
    val endpoint = runCatching {
        val uri = URI(address)
        val scheme = uri.scheme.lowercase()
        val port = uri.port.takeUnless { it == -1 || scheme == "https" && it == 443 || scheme == "http" && it == 80 }
        "$scheme://${uri.host.lowercase()}${port?.let { ":$it" }.orEmpty()}${uri.rawPath.orEmpty().trimEnd('/')}"
    }.getOrElse { address.trimEnd('/') }
    val value = scopedContentId(profileId, endpoint, serverId, userId)
    return "$protocol:" + MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Legacy identities can be resolved only when exactly one stored account owns them. */
fun List<EmbySession>.accountFor(providerId: String): EmbySession? = firstOrNull { it.providerId == providerId }
    ?: filter { it.legacyProviderId == providerId }.singleOrNull()
