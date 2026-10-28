package com.shadow.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val MediaModule.icon: ImageVector get() = when (this) {
    MediaModule.VIDEO -> Icons.Outlined.Movie
    MediaModule.NOVEL -> Icons.AutoMirrored.Outlined.MenuBook
    MediaModule.MUSIC -> Icons.Outlined.MusicNote
    MediaModule.COMIC -> Icons.Outlined.AutoStories
    MediaModule.AUDIOBOOK -> Icons.Outlined.Headphones
    MediaModule.LIVE -> Icons.Outlined.LiveTv
}

internal val MediaDestination.icon: ImageVector get() = when (this) {
    MediaDestination.HOME -> Icons.Outlined.Home
    MediaDestination.LIBRARIES -> Icons.Outlined.VideoLibrary
    MediaDestination.VIDEO_SOURCES -> Icons.Outlined.Subscriptions
    MediaDestination.SHELF -> Icons.Outlined.Bookmarks
    MediaDestination.CITY -> Icons.Outlined.LocalLibrary
    MediaDestination.DISCOVERY -> Icons.Outlined.Explore
    MediaDestination.BOOK_SOURCES -> Icons.Outlined.TravelExplore
    MediaDestination.MUSIC_LIBRARY -> Icons.Outlined.LibraryMusic
    MediaDestination.PLAYLISTS -> Icons.AutoMirrored.Outlined.QueueMusic
    MediaDestination.COMIC_LIBRARY -> Icons.Outlined.CollectionsBookmark
    MediaDestination.AUDIO_LIBRARY -> Icons.Outlined.Headphones
    MediaDestination.CHANNELS -> Icons.Outlined.LiveTv
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MediaModuleHeader(
    module: MediaModule, onModule: (MediaModule) -> Unit, onTools: () -> Unit, onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Box {
                TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("module-switch"),
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(module.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.KeyboardArrowDown, "切换模块", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 208.dp)) {
                    MediaModule.entries.forEach { item ->
                        DropdownMenuItem(text = { Text(item.title) }, leadingIcon = { Icon(item.icon, null) },
                            trailingIcon = { if (item == module) Icon(Icons.Outlined.Check, "当前模块") },
                            modifier = Modifier.testTag("module-${item.name}"),
                            onClick = { expanded = false; onModule(item) })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DropdownMenuItem(text = { Text("通用设置") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                        onClick = { expanded = false; onSettings() })
                }
            }
        },
        actions = { IconButton(onClick = onTools, modifier = Modifier.testTag("module-tools")) { Icon(Icons.Outlined.MoreVert, "${module.title}工具与设置") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable internal fun MediaModuleNavigation(
    module: MediaModule, selected: MediaDestination, onSelect: (MediaDestination) -> Unit,
) {
    if (!module.hasBottomNavigation) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup().testTag("module-navigation")) {
            module.destinations.forEach { destination ->
                val active = selected == destination
                val color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).heightIn(min = 68.dp)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(destination) })
                        .testTag("destination-${destination.name}").padding(top = 6.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.width(20.dp).height(3.dp).background(if (active) color else Color.Transparent, RoundedCornerShape(2.dp)))
                    Icon(destination.icon, null, Modifier.size(23.dp), tint = color)
                    Text(destination.title, color = color, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
