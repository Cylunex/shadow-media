package top.cylunex.shadowmedia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.network.ClientIdentity
import top.cylunex.shadowmedia.playback.PlaybackHeaderPolicy

val LocalEmbyImageLoader: ProvidableCompositionLocal<ImageLoader?> =
    staticCompositionLocalOf { null }

@Composable
fun rememberEmbyImageLoader(session: EmbySession?, clientIdentity: ClientIdentity): ImageLoader? {
    val context = LocalContext.current.applicationContext
    val imageClient = remember(session, clientIdentity) {
        session?.let {
            val policy = PlaybackHeaderPolicy(it.serverUrl.toHttpUrl(), it, clientIdentity)
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addNetworkInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    chain.proceed(
                        request.newBuilder()
                            .headers(policy.apply(request.url, request.headers))
                            .build()
                    )
                })
                .build()
        }
    }
    val imageLoader: ImageLoader? = remember(imageClient) {
        imageClient?.let { client ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
                .build()
        }
    }
    DisposableEffect(imageLoader) {
        onDispose {
            imageClient?.dispatcher?.cancelAll()
        }
    }
    return imageLoader
}

fun embyArtworkUrl(session: EmbySession?, itemId: String, width: Int = 720): String? =
    session?.serverUrl?.toHttpUrl()?.newBuilder()?.apply {
        val currentSegments = build().pathSegments.filter(String::isNotEmpty)
        if (currentSegments.lastOrNull()?.equals("emby", ignoreCase = true) != true) {
            addPathSegment("emby")
        }
        addPathSegment("Items")
        addPathSegment(itemId)
        addPathSegment("Images")
        addPathSegment("Primary")
        addQueryParameter("maxWidth", width.toString())
        addQueryParameter("quality", "86")
    }?.build()?.toString()

@Composable
fun EmbyArtwork(
    session: EmbySession?,
    itemId: String,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val loader = LocalEmbyImageLoader.current
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.firstOrNull()?.uppercase() ?: "S",
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        if (loader != null) {
            AsyncImage(
                model = embyArtworkUrl(session, itemId),
                imageLoader = loader,
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)))
            )
        )
    }
}
