package top.cylunex.shadowmedia

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import top.cylunex.shadowmedia.database.ShadowMediaDatabase

/** Only visible cards observe their own availability; byte ticks do not recompose the shelf. */
@Composable internal fun AssetAvailabilityLabel(provider: String, item: String, progress: String? = null) {
    val context = LocalContext.current.applicationContext
    val container = (context as ShadowMediaApplication).container
    val source by remember(provider) { container.providerRegistry.providers.map { rows -> rows.firstOrNull { it.descriptor.id == provider }?.descriptor?.name }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val state by remember(provider, item) { ShadowMediaDatabase.create(context).libraryDao().availability(provider, item).distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val availability = when {
        state?.localCopy == true || (state?.state == "OFFLINE_AVAILABLE" && state?.currentRevision == true) -> "可离线"
        state?.state == "OFFLINE_PENDING" -> "保存中"
        state?.state == "OFFLINE_PAUSED" -> "已暂停保存"
        state?.state == "OFFLINE_FAILED" -> "保存失败"
        state?.state == "OFFLINE_STALE" || state?.state == "OFFLINE_AVAILABLE" -> "副本已过期"
        else -> "未保存副本"
    }
    val name = source ?: when {
        provider == "local" -> "本机"
        provider.startsWith("emby:") -> "Emby"
        provider.startsWith("catalog:") -> provider.substringAfter(':').substringBefore(':')
        provider.startsWith("storage:") -> "网络存储"
        else -> "外部来源"
    }
    Text(listOfNotNull(name, availability, progress).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
