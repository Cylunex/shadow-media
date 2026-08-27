package top.cylunex.shadowmedia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.material3.Player
import top.cylunex.shadowmedia.model.MediaItem
import top.cylunex.shadowmedia.model.MediaLibrary
import top.cylunex.shadowmedia.model.embyTicksToMilliseconds
import top.cylunex.shadowmedia.playback.PlaybackRuntime

@Composable
fun ShadowMediaRoot(viewModel: MainViewModel, container: AppContainer) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        when (state.screen) {
            Screen.LOGIN -> LoginScreen(state, viewModel)
            Screen.LIBRARIES -> LibraryScreen(state, viewModel)
            Screen.ITEMS -> ItemScreen(state, viewModel)
            Screen.PLAYER -> PlayerScreen(state, viewModel, container)
        }
        if (state.isLoading) LoadingOverlay()
    }
}

@Composable
private fun LoginScreen(state: MainUiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Shadow Media", style = MaterialTheme.typography.headlineLarge)
        Text("连接 Emby，先验证 PlaybackInfo → 302 → 播放上报闭环")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::updateServerUrl,
            label = { Text("Emby 地址") },
            placeholder = { Text("https://media.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.userName,
            onValueChange = viewModel::updateUserName,
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.allowInsecureHttp, onCheckedChange = viewModel::updateAllowInsecure)
            Text("允许受信任局域网使用未加密 HTTP")
        }
        ErrorText(state.errorMessage)
        Button(
            onClick = viewModel::login,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("登录并读取媒体库") }
    }
}

@Composable
private fun LibraryScreen(state: MainUiState, viewModel: MainViewModel) {
    ContentListHeader(
        title = "选择媒体库",
        subtitle = "${state.session?.userName.orEmpty()} · ${state.session?.serverUrl.orEmpty()}",
        actionText = "退出登录",
        onAction = viewModel::logout,
    )
    LazyColumn(Modifier.fillMaxSize().padding(top = 88.dp)) {
        items(state.libraries, key = MediaLibrary::id) { library ->
            ListCard(
                title = library.name,
                subtitle = library.collectionType ?: "媒体库",
                onClick = { viewModel.selectLibrary(library) },
            )
        }
        if (!state.isLoading && state.libraries.isEmpty()) {
            item { EmptyState("此账号没有可用媒体库") }
        }
        item { ErrorText(state.errorMessage, Modifier.padding(16.dp)) }
    }
}

@Composable
private fun ItemScreen(state: MainUiState, viewModel: MainViewModel) {
    BackHandler(onBack = viewModel::back)
    ContentListHeader(
        title = state.selectedLibrary?.name ?: "视频",
        subtitle = "最近新增的 20 个可播放条目",
        actionText = "返回媒体库",
        onAction = viewModel::back,
    )
    LazyColumn(Modifier.fillMaxSize().padding(top = 88.dp)) {
        items(state.items, key = MediaItem::id) { item ->
            val episode = if (item.seriesName != null) {
                "${item.seriesName} · S${item.seasonNumber ?: 0}E${item.episodeNumber ?: 0}"
            } else item.type
            val progress = item.playbackPositionTicks.takeIf { it > 0 }?.let {
                " · 续播 ${formatDuration(it.embyTicksToMilliseconds())}"
            }.orEmpty()
            ListCard(item.name, episode + progress, onClick = { viewModel.play(item) })
        }
        if (!state.isLoading && state.items.isEmpty()) item { EmptyState("这个媒体库没有找到视频") }
        item { ErrorText(state.errorMessage, Modifier.padding(16.dp)) }
    }
}

@Composable
private fun PlayerScreen(state: MainUiState, viewModel: MainViewModel, container: AppContainer) {
    val session = requireNotNull(state.session)
    val plan = requireNotNull(state.playbackPlan)
    val item = requireNotNull(state.selectedItem)
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime = androidx.compose.runtime.remember(plan) {
        PlaybackRuntime(
            context = context,
            session = session,
            plan = plan,
            repository = container.embyRepository,
            clientIdentity = container.clientIdentity,
            startPositionMs = item.playbackPositionTicks.embyTicksToMilliseconds(),
        )
    }
    val diagnostics by runtime.diagnostics.collectAsStateWithLifecycle()
    DisposableEffect(runtime) { onDispose(runtime::close) }
    BackHandler(onBack = viewModel::back)

    Box(Modifier.fillMaxSize()) {
        Player(player = runtime.player, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(16.dp)
        ) {
            OutlinedButton(onClick = viewModel::back) { Text("返回") }
            Spacer(Modifier.height(8.dp))
            Text(item.name, color = MaterialTheme.colorScheme.onSurface)
        }
        Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("播放诊断", style = MaterialTheme.typography.titleSmall)
                Text("${diagnostics.method} · ${diagnostics.requestHost}")
                Text(
                    "${diagnostics.container ?: "?"} · " +
                        "${diagnostics.videoCodec ?: "?"} / ${diagnostics.audioCodec ?: "?"} · " +
                        "链路 ${diagnostics.candidateIndex + 1}/${diagnostics.candidateCount}"
                )
                diagnostics.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ContentListHeader(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        OutlinedButton(onClick = onAction) { Text(actionText) }
    }
}

@Composable
private fun ListCard(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(message) }
}

@Composable
private fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    message?.let { Text(it, modifier = modifier, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card { CircularProgressIndicator(Modifier.padding(20.dp).size(40.dp)) }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
