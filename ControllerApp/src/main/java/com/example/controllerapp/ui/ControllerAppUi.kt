package com.example.controllerapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.controllerapp.ControllerViewModel
import com.example.controllerapp.model.AppExperienceMode
import com.example.controllerapp.model.ArtworkLoadingStage
import com.example.controllerapp.model.ArtworkQuality
import com.example.controllerapp.model.ArtworkState
import com.example.controllerapp.model.ConnectionHealth
import com.example.controllerapp.model.ConnectionPhase
import com.example.controllerapp.model.ConnectionState
import com.example.controllerapp.model.ControllerSettings
import com.example.controllerapp.model.DiagnosticsState
import com.example.controllerapp.model.HistoryState
import com.example.controllerapp.model.LyricDisplayMode
import com.example.controllerapp.model.LyricLine
import com.example.controllerapp.model.LyricLoadingStage
import com.example.controllerapp.model.LyricsState
import com.example.controllerapp.model.PlaybackPerformanceMode
import com.example.controllerapp.model.PlaybackState
import com.example.controllerapp.protocol.LyricTimeline
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sin

private object Routes {
    const val PLAYER = "player"
    const val LYRICS = "lyrics"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val HEALTH = "health"
    const val TRACK_DIAGNOSTIC = "track-diagnostic"
    const val LYRIC_DIAGNOSTIC = "lyric-diagnostic"
    const val DEBUG_TOOLS = "debug-tools"
}

@Composable
fun ControllerApp(viewModel: ControllerViewModel) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = Routes.PLAYER) {
        composable(Routes.PLAYER) {
            PlayerScreen(viewModel, navController)
        }
        composable(Routes.LYRICS) {
            FullLyricsScreen(viewModel, navController)
        }
        composable(Routes.HISTORY) {
            HistoryScreen(viewModel, navController)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel, navController)
        }
        composable(Routes.HEALTH) {
            HealthScreen(viewModel, navController)
        }
        composable(Routes.TRACK_DIAGNOSTIC) {
            TrackDiagnosticScreen(viewModel, navController)
        }
        composable(Routes.LYRIC_DIAGNOSTIC) {
            LyricDiagnosticScreen(viewModel, navController)
        }
        composable(Routes.DEBUG_TOOLS) {
            DebugToolsScreen(viewModel, navController)
        }
    }
}

@Composable
private fun PlayerScreen(
    viewModel: ControllerViewModel,
    navController: NavHostController
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val position by viewModel.displayedPositionMs.collectAsStateWithLifecycle()
    val reduceAnimations by viewModel.reduceAnimations.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        artwork.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xe8090b10),
                        Color(0xb5090b10),
                        Color(0xff090b10)
                    )
                )
            )
        )
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                PlayerHeader(connection, settings, navController) {
                    viewModel.reconnect(forceScan = connection.deviceAddress.isBlank())
                }
                PlayerBody(
                    connection = connection,
                    playback = playback,
                    lyrics = lyrics,
                    artwork = artwork,
                    settings = settings,
                    reduceAnimations = reduceAnimations,
                    positionMs = position,
                    onOpenLyrics = { navController.navigate(Routes.LYRICS) },
                    onPrevious = viewModel::previous,
                    onPlayPause = viewModel::playPause,
                    onNext = viewModel::next,
                    onSeek = viewModel::seekTo,
                    onVolume = viewModel::setVolume,
                    onRetryLyrics = viewModel::retryLyrics,
                    onRetryArtwork = viewModel::retryArtwork
                )
            }
        }
    }
}

@Composable
private fun PlayerHeader(
    connection: ConnectionState,
    settings: ControllerSettings,
    navController: NavHostController,
    onReconnect: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = onReconnect,
            label = {
                Text(connectionLabel(connection), maxLines = 1)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Bluetooth,
                    null,
                    tint = when (connection.health) {
                        ConnectionHealth.HEALTHY -> Color(0xff8bd6a3)
                        ConnectionHealth.SUSPECT -> Color(0xffffd166)
                        ConnectionHealth.STALE -> MaterialTheme.colorScheme.error
                        ConnectionHealth.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        )
        Spacer(Modifier.weight(1f))
        Text(
            "QQ 音乐",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, "打开菜单")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                MenuEntry("播放历史", Icons.Default.History) {
                    menuExpanded = false
                    navController.navigate(Routes.HISTORY)
                }
                MenuEntry("设置", Icons.Default.Settings) {
                    menuExpanded = false
                    navController.navigate(Routes.SETTINGS)
                }
                MenuEntry("系统健康", Icons.Default.HealthAndSafety) {
                    menuExpanded = false
                    navController.navigate(Routes.HEALTH)
                }
                if (settings.experienceMode == AppExperienceMode.DEBUG) {
                    MenuEntry("调试工具", Icons.Default.BugReport) {
                        menuExpanded = false
                        navController.navigate(Routes.DEBUG_TOOLS)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick
    )
}

@Composable
private fun PlayerBody(
    connection: ConnectionState,
    playback: PlaybackState,
    lyrics: LyricsState,
    artwork: ArtworkState,
    settings: ControllerSettings,
    reduceAnimations: Boolean,
    positionMs: Long,
    onOpenLyrics: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
    onRetryLyrics: () -> Unit,
    onRetryArtwork: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp && maxWidth > maxHeight
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkPanel(
                    artwork,
                    playback,
                    settings,
                    Modifier.weight(0.8f).fillMaxHeight()
                )
                PlayerControlsPanel(
                    connection,
                    playback,
                    lyrics,
                    artwork,
                    settings,
                    reduceAnimations,
                    positionMs,
                    onOpenLyrics,
                    onPrevious,
                    onPlayPause,
                    onNext,
                    onSeek,
                    onVolume,
                    onRetryLyrics,
                    onRetryArtwork,
                    Modifier.weight(1.2f).verticalScroll(rememberScrollState())
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ArtworkPanel(
                    artwork,
                    playback,
                    settings,
                    Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                PlayerControlsPanel(
                    connection,
                    playback,
                    lyrics,
                    artwork,
                    settings,
                    reduceAnimations,
                    positionMs,
                    onOpenLyrics,
                    onPrevious,
                    onPlayPause,
                    onNext,
                    onSeek,
                    onVolume,
                    onRetryLyrics,
                    onRetryArtwork,
                    Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ArtworkPanel(
    artwork: ArtworkState,
    playback: PlaybackState,
    settings: ControllerSettings,
    modifier: Modifier = Modifier
) {
    val maxArtwork = settings.artworkDisplaySizeDp.dp
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(maxArtwork)
                .shadow(28.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            artwork.bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "${playback.title} 的封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } ?: Icon(
                Icons.Default.Image,
                contentDescription = "暂无封面",
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
            )
            if (artwork.loadingStage == ArtworkLoadingStage.PREVIEW ||
                artwork.loadingStage == ArtworkLoadingStage.HQ
            ) {
                CircularProgressIndicator(
                    Modifier.size(32.dp),
                    strokeWidth = 2.dp
                )
            }
            if (playback.restoredSnapshot || artwork.restoredSnapshot) {
                Surface(
                    color = Color(0xaa000000),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                ) {
                    Text(
                        "上次播放 · 等待同步",
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerControlsPanel(
    connection: ConnectionState,
    playback: PlaybackState,
    lyrics: LyricsState,
    artwork: ArtworkState,
    settings: ControllerSettings,
    reduceAnimations: Boolean,
    positionMs: Long,
    onOpenLyrics: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
    onRetryLyrics: () -> Unit,
    onRetryArtwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            playback.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            listOf(playback.artist, playback.album)
                .filter { it.isNotBlank() && it != "-" }
                .joinToString(" · ")
                .ifBlank { "等待 QQ 音乐" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spectrum(
            playing = playback.isPlaying,
            positionMs = positionMs,
            animated = !reduceAnimations
        )
        CompactLyrics(
            lyrics = lyrics,
            settings = settings,
            positionMs = positionMs,
            onClick = onOpenLyrics
        )
        PlaybackProgress(playback, positionMs, connection.connected, onSeek)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                enabled = connection.connected,
                onClick = onPrevious,
                modifier = Modifier.semantics { contentDescription = "上一首" }
            ) {
                Icon(Icons.Default.SkipPrevious, null, Modifier.size(34.dp))
            }
            FilledIconButton(
                enabled = connection.connected,
                onClick = onPlayPause,
                modifier = Modifier.size(66.dp)
                    .semantics { contentDescription = if (playback.isPlaying) "暂停" else "播放" }
            ) {
                Icon(
                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    Modifier.size(38.dp)
                )
            }
            IconButton(
                enabled = connection.connected,
                onClick = onNext,
                modifier = Modifier.semantics { contentDescription = "下一首" }
            ) {
                Icon(Icons.Default.SkipNext, null, Modifier.size(34.dp))
            }
        }
        VolumeControl(playback, connection.connected, onVolume)
        AnimatedVisibility(settings.experienceMode == AppExperienceMode.DEBUG) {
            DebugTransferStatus(lyrics, artwork, onRetryLyrics, onRetryArtwork)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Spectrum(playing: Boolean, positionMs: Long, animated: Boolean) {
    val phase = if (playing && animated) positionMs / 90f else 0f
    Canvas(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 56.dp)) {
        val bars = 20
        val spacing = size.width / bars
        repeat(bars) { index ->
            val amplitude = if (playing) {
                0.25f + (sin(phase + index * 0.8f) + 1f) * 0.28f
            } else {
                0.18f
            }
            val barHeight = size.height * amplitude
            drawLine(
                color = Color.White.copy(alpha = 0.48f),
                start = Offset(index * spacing + spacing / 2, size.height / 2 - barHeight / 2),
                end = Offset(index * spacing + spacing / 2, size.height / 2 + barHeight / 2),
                strokeWidth = max(2f, spacing * 0.32f)
            )
        }
    }
}

@Composable
private fun CompactLyrics(
    lyrics: LyricsState,
    settings: ControllerSettings,
    positionMs: Long,
    onClick: () -> Unit
) {
    val currentIndex = currentLineIndex(
        lyrics.lines,
        positionMs + settings.lyricOffsetMs,
        lyrics.currentLineIndex
    )
    val previous = lyrics.lines.getOrNull(currentIndex - 1)
    val current = lyrics.lines.getOrNull(currentIndex)
    val next = lyrics.lines.getOrNull(currentIndex + 1)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.055f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(178.dp).testTag("compact_lyrics")
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                previous?.text.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (current != null) {
                WordHighlightedText(current, positionMs + settings.lyricOffsetMs)
                auxiliaryText(current, settings.lyricDisplayMode)?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    lyrics.currentText.ifBlank { lyricStageText(lyrics.loadingStage) },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                next?.text.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WordHighlightedText(line: LyricLine, positionMs: Long) {
    val completed = line.words.indexOfLast { positionMs >= it.startMs + it.durationMs }
    val active = line.words.indexOfLast { positionMs >= it.startMs }.coerceAtLeast(0)
    val value = if (line.words.isEmpty()) {
        buildAnnotatedString { append(line.text) }
    } else {
        buildAnnotatedString {
            line.words.forEachIndexed { index, word ->
                withStyle(
                    SpanStyle(
                        color = when {
                            index <= completed -> Color.White
                            index == active -> Color(0xffb9c7ff)
                            else -> Color.White.copy(alpha = 0.45f)
                        },
                        fontWeight = if (index == active) FontWeight.Bold else FontWeight.Medium
                    )
                ) {
                    append(word.text)
                }
            }
        }
    }
    Text(
        value,
        fontSize = if (line.text.length > 42) 16.sp else 20.sp,
        lineHeight = if (line.text.length > 42) 21.sp else 26.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PlaybackProgress(
    playback: PlaybackState,
    positionMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit
) {
    var dragged by remember(playback.trackId) { mutableStateOf<Float?>(null) }
    val maximum = playback.durationMs.coerceAtLeast(1L).toFloat()
    val shown = dragged ?: positionMs.coerceIn(0L, maximum.toLong()).toFloat()
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown.coerceIn(0f, maximum),
            valueRange = 0f..maximum,
            enabled = enabled && playback.durationMs > 0L,
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { onSeek(it.toLong()) }
                dragged = null
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shown.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(playback.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VolumeControl(
    playback: PlaybackState,
    enabled: Boolean,
    onVolume: (Int) -> Unit
) {
    var value by remember(playback.volumeCurrent, playback.volumeMax) {
        mutableFloatStateOf(playback.volumeCurrent.toFloat())
    }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value, dragging) {
        if (dragging) {
            delay(90L)
            onVolume(value.toInt())
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, "音量", Modifier.size(20.dp))
        Slider(
            value = value.coerceIn(0f, playback.volumeMax.coerceAtLeast(1).toFloat()),
            valueRange = 0f..playback.volumeMax.coerceAtLeast(1).toFloat(),
            enabled = enabled && playback.volumeMax > 0,
            onValueChange = {
                dragging = true
                value = it
            },
            onValueChangeFinished = {
                onVolume(value.toInt())
                dragging = false
            },
            modifier = Modifier.weight(1f)
        )
        Text("${value.toInt()}", Modifier.width(34.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun DebugTransferStatus(
    lyrics: LyricsState,
    artwork: ArtworkState,
    onRetryLyrics: () -> Unit,
    onRetryArtwork: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TransferRow(
                "完整歌词",
                lyricStageText(lyrics.loadingStage),
                lyrics.receivedChunks,
                lyrics.expectedChunks,
                lyrics.failureReason,
                onRetryLyrics
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            TransferRow(
                "封面",
                artwork.loadingStage.name,
                artwork.receivedChunks,
                artwork.expectedChunks,
                artwork.failureReason,
                onRetryArtwork
            )
        }
    }
}

@Composable
private fun TransferRow(
    name: String,
    stage: String,
    received: Int,
    expected: Int,
    failure: String,
    retry: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$name · $stage", style = MaterialTheme.typography.labelLarge)
            if (expected > 0) {
                LinearProgressIndicator(
                    progress = {
                        (received.toFloat() / expected).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                )
            }
            if (failure.isNotBlank()) {
                Text(failure, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        TextButton(onClick = retry) { Text("重试") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullLyricsScreen(
    viewModel: ControllerViewModel,
    navController: NavHostController
) {
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val position by viewModel.displayedPositionMs.collectAsStateWithLifecycle()
    val displayLines = lyrics.lines
    val current = currentLineIndex(
        displayLines,
        position + settings.lyricOffsetMs,
        lyrics.currentLineIndex
    )
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    var resumeFollowToken by remember { mutableStateOf(0) }
    LaunchedEffect(lyrics.trackId, lyrics.isFinal) {
        if (lyrics.trackId.isNotBlank() && !lyrics.isFinal) {
            viewModel.ensureFullLyrics()
        }
    }
    LaunchedEffect(current, following, lyrics.isFinal) {
        if (following && current >= 0 && current < displayLines.size) {
            programmaticScroll = true
            try {
                val loadingHeaderOffset = if (lyrics.isFinal) 0 else 1
                listState.animateScrollToItem(
                    current + loadingHeaderOffset,
                    scrollOffset = -180
                )
            } finally {
                programmaticScroll = false
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress, programmaticScroll) {
        if (listState.isScrollInProgress && !programmaticScroll) {
            following = false
            resumeFollowToken += 1
        }
    }
    LaunchedEffect(resumeFollowToken) {
        if (resumeFollowToken > 0) {
            delay(4_000L)
            following = true
        }
    }
    Scaffold(
        topBar = {
            ControllerTopBar(
                title = "完整歌词",
                navController = navController,
                action = {
                    TextButton(onClick = { following = !following }) {
                        Text(if (following) "跟随中" else "恢复跟随")
                    }
                }
            )
        }
    ) { padding ->
        if (displayLines.isEmpty()) {
            EmptyState(
                title = lyricStageText(lyrics.loadingStage),
                message = lyrics.failureReason.ifBlank { "正在等待 Sony 解析 QQ 音乐歌词" },
                action = "重新请求",
                onAction = viewModel::retryLyrics,
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = padding.calculateTopPadding() + 120.dp,
                    bottom = padding.calculateBottomPadding() + 240.dp,
                    start = 22.dp,
                    end = 22.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!lyrics.isFinal) {
                    item(key = "full-lyrics-loading") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        lyricStageText(lyrics.loadingStage),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        if (settings.experienceMode != AppExperienceMode.DEBUG) {
                                            "正在获取完整歌词，当前内容可先浏览"
                                        } else {
                                            when {
                                                lyrics.partialFullLines.isNotEmpty() ->
                                                    "已接收 ${lyrics.partialFullLines.size} 行，完成后自动替换"
                                                lyrics.windowLines.isNotEmpty() ->
                                                    "当前仅展示 ${lyrics.windowLines.size} 行歌词窗口"
                                                else -> "正在等待 Sony 返回完整歌词"
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = viewModel::retryLyrics) { Text("重试") }
                            }
                        }
                    }
                }
                items(displayLines, key = LyricLine::index) { line ->
                    val selected = line.index == displayLines.getOrNull(current)?.index
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { viewModel.seekTo(line.timeMs) }
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .padding(12.dp)
                    ) {
                        if (selected) {
                            WordHighlightedText(line, position + settings.lyricOffsetMs)
                        } else {
                            Text(
                                line.text,
                                fontSize = if (line.text.length > 48) 16.sp else 19.sp,
                                lineHeight = 25.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (line.timeMs < position) 0.48f else 0.76f
                                )
                            )
                        }
                        auxiliaryText(line, settings.lyricDisplayMode)?.let {
                            Text(
                                it,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    viewModel: ControllerViewModel,
    navController: NavHostController
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    var range by remember { mutableStateOf("TODAY") }
    LaunchedEffect(Unit) { viewModel.syncHistory() }
    Scaffold(
        topBar = {
            ControllerTopBar(
                title = "播放历史",
                navController = navController,
                action = {
                    IconButton(onClick = viewModel::syncHistory) {
                        Icon(Icons.Default.Sync, "同步历史")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                StatsRangeSelector(range) { range = it }
            }
            history.stats[range]?.let { stats ->
                item { StatsOverview(stats) }
            }
            item {
                Text(
                    "最近播放",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (history.sessions.isEmpty()) {
                item {
                    Text(
                        history.status.ifBlank { if (history.loading) "正在同步…" else "暂无历史" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 28.dp)
                    )
                }
            }
            items(history.sessions, key = { it.sessionId }) { session ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HistoryArtwork(session.artworkId, viewModel)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.title.ifBlank { "未知歌曲" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${session.artist} · ${formatDate(session.startedAt)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatDuration(session.listenedMs))
                            Text(
                                when {
                                    session.completed -> "已完播"
                                    session.skipped -> "已跳过"
                                    else -> "播放中断"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (history.sessions.isNotEmpty() && history.hasMore) {
                item {
                    OutlinedButton(
                        onClick = viewModel::loadMoreHistory,
                        enabled = !history.loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (history.loading) "正在加载…" else "加载更多")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryArtwork(
    artworkId: String?,
    viewModel: ControllerViewModel
) {
    var bitmap by remember(artworkId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(artworkId) {
        bitmap = artworkId?.let { viewModel.historyArtwork(it) }
    }
    Box(
        Modifier.size(48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: Icon(
            Icons.Default.GraphicEq,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StatsRangeSelector(selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("TODAY" to "今天", "WEEK" to "本周", "MONTH" to "本月").forEach { item ->
            if (selected == item.first) {
                Button(onClick = { onSelected(item.first) }, modifier = Modifier.weight(1f)) {
                    Text(item.second)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelected(item.first) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(item.second)
                }
            }
        }
    }
}

@Composable
private fun StatsOverview(stats: com.example.controllerapp.model.PlaybackStats) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("听歌时长", formatDuration(stats.totalListenMs))
                Stat("播放", "${stats.playCount} 次")
                Stat("歌曲", "${stats.uniqueTrackCount} 首")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("完播率", "${(stats.completionRate * 100).toInt()}%")
                Stat("跳过率", "${(stats.skipRate * 100).toInt()}%")
                Stat("趋势", "${stats.dailyTrend.size} 天")
            }
            stats.topTracks.firstOrNull()?.let {
                Text(
                    "常听歌曲：${it.title} · ${it.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            stats.topArtists.firstOrNull()?.let {
                Text(
                    "常听歌手：${it.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: ControllerViewModel,
    navController: NavHostController
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var lyricOffset by remember(settings.lyricOffsetMs) {
        mutableFloatStateOf(settings.lyricOffsetMs.toFloat())
    }
    var artworkSize by remember(settings.artworkDisplaySizeDp) {
        mutableFloatStateOf(settings.artworkDisplaySizeDp.toFloat())
    }
    Scaffold(topBar = { ControllerTopBar("设置", navController) }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SettingSection("显示模式") {
                ChoiceRow(
                    values = AppExperienceMode.entries,
                    selected = settings.experienceMode,
                    label = { if (it == AppExperienceMode.DAILY) "日常" else "调试" },
                    onSelected = viewModel::updateExperienceMode
                )
            }
            SettingSection("性能模式") {
                ChoiceRow(
                    values = PlaybackPerformanceMode.entries,
                    selected = settings.performanceMode,
                    label = {
                        when (it) {
                            PlaybackPerformanceMode.AUTOMATIC -> "自动"
                            PlaybackPerformanceMode.SMOOTH -> "流畅"
                            PlaybackPerformanceMode.POWER_SAVING -> "省电"
                        }
                    },
                    onSelected = viewModel::updatePerformanceMode
                )
            }
            SettingSwitch(
                "自动重连",
                "连接意外中断时使用指数退避恢复",
                settings.autoReconnect,
                viewModel::updateAutoReconnect
            )
            SettingSection("歌词显示") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyricDisplayMode.entries.forEach { mode ->
                        val label = when (mode) {
                            LyricDisplayMode.ORIGINAL -> "原文"
                            LyricDisplayMode.ORIGINAL_TRANSLATION -> "原文 + 翻译"
                            LyricDisplayMode.ORIGINAL_ROMANIZATION -> "原文 + 罗马音"
                            LyricDisplayMode.ALL -> "原文 + 翻译 + 罗马音"
                        }
                        SelectableSetting(
                            label,
                            selected = settings.lyricDisplayMode == mode
                        ) { viewModel.updateLyricMode(mode) }
                    }
                }
            }
            SettingSection("歌词偏移 · ${lyricOffset.toLong()} ms") {
                Slider(
                    value = lyricOffset,
                    valueRange = -2_000f..2_000f,
                    onValueChange = { lyricOffset = it },
                    onValueChangeFinished = {
                        viewModel.updateLyricOffset(lyricOffset.toLong())
                    }
                )
            }
            SettingSection("封面大小 · ${artworkSize.toInt()} dp") {
                Slider(
                    value = artworkSize,
                    valueRange = 200f..260f,
                    onValueChange = { artworkSize = it },
                    onValueChangeFinished = {
                        viewModel.updateArtworkSize(artworkSize.toInt())
                    }
                )
            }
            SettingSwitch(
                "本地封面增强",
                "HQ 到达后在后台轻量锐化，不阻塞基础封面",
                settings.artworkEnhancementEnabled,
                viewModel::updateArtworkEnhancement
            )
            OutlinedButton(
                onClick = viewModel::clearArtworkCache,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清理当前歌曲封面缓存")
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { item ->
            if (item == selected) {
                Button(onClick = { onSelected(item) }, modifier = Modifier.weight(1f)) {
                    Text(label(item), maxLines = 1)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelected(item) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(label(item), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SelectableSetting(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            if (selected) {
                Text("已选择", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthScreen(
    viewModel: ControllerViewModel,
    navController: NavHostController
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    Scaffold(topBar = { ControllerTopBar("系统健康", navController) }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                "BLE 连接",
                connectionLabel(connection),
                "MTU ${connection.mtu} · generation ${connection.generation}"
            )
            StatusCard(
                "协议",
                if (connection.serverSupportsV2) "Sony BLE V2" else "兼容协议",
                "协议版本 ${connection.serverProtocolVersion}"
            )
            StatusCard(
                "健康探测",
                connection.health.name,
                connection.lastReconnectReason.ifBlank { "连接活动正常" }
            )
            if (diagnostics.lastIssue.isNotBlank()) {
                StatusCard("最近异常", diagnostics.lastIssue, "自愈仅影响当前连接或当前歌曲")
            }
            Button(
                onClick = { viewModel.reconnect(false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("立即检查并重连")
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, value: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackDiagnosticScreen(
    viewModel: ControllerViewModel,
    navController: NavHostController
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    Scaffold(topBar = { ControllerTopBar("当前歌曲诊断", navController) }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DiagnosticValue("trackId", playback.trackId)
            DiagnosticValue("标题", playback.title)
            DiagnosticValue("歌手", playback.artist)
            DiagnosticValue("专辑", playback.album)
            DiagnosticValue("歌词窗口", "${lyrics.windowLines.size} 行")
            DiagnosticValue("完整歌词", "${lyrics.fullLines.size} 行 · final=${lyrics.isFinal}")
            DiagnosticValue("歌词状态", lyrics.loadingStage.name)
            DiagnosticValue("歌词协议", lyrics.protocolFormat)
            DiagnosticValue("transferId", lyrics.transferId)
            DiagnosticValue("传输进度", "${lyrics.receivedChunks}/${lyrics.expectedChunks}")
            DiagnosticValue("重试次数", lyrics.retryCount.toString())
            DiagnosticValue("封面 ID", artwork.artworkId)
            DiagnosticValue("封面质量", artwork.quality.name)
            DiagnosticValue("封面状态", artwork.loadingStage.name)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::retryLyrics, modifier = Modifier.weight(1f)) {
                    Text("重试歌词")
                }
                OutlinedButton(onClick = viewModel::retryArtwork, modifier = Modifier.weight(1f)) {
                    Text("重试封面")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricDiagnosticScreen(
    viewModel: ControllerViewModel,
    navController: NavHostController
) {
    val diagnostic by viewModel.diagnostics.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.requestLyricDiagnostic() }
    val lyric = diagnostic.lyricDiagnostic
    Scaffold(topBar = { ControllerTopBar("歌词诊断", navController) }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DiagnosticValue("状态", lyric.status.ifBlank { "等待 Sony 返回" })
            DiagnosticValue("原因", lyric.reason)
            DiagnosticValue("建议", lyric.suggestion)
            lyric.details.forEach { (key, value) -> DiagnosticValue(key, value) }
            Button(
                onClick = viewModel::requestLyricDiagnostic,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新诊断")
            }
        }
    }
}

@Composable
private fun DiagnosticValue(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(13.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value.ifBlank { "—" }, maxLines = 8, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugToolsScreen(
    viewModel: ControllerViewModel,
    navController: NavHostController
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { ControllerTopBar("调试工具", navController) }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DebugAction("强制重新扫描", Icons.Default.Bluetooth) { viewModel.reconnect(true) }
            DebugAction("同步播放状态", Icons.Default.Refresh, viewModel::requestPlaybackState)
            DebugAction("请求完整歌词", Icons.AutoMirrored.Filled.LibraryBooks, viewModel::requestFullLyrics)
            DebugAction("请求 HQ 封面", Icons.Default.Image) {
                viewModel.requestArtwork(ArtworkQuality.HQ)
            }
            DebugAction("当前歌曲诊断", Icons.Default.GraphicEq) {
                navController.navigate(Routes.TRACK_DIAGNOSTIC)
            }
            DebugAction("歌词诊断", Icons.Default.BugReport) {
                navController.navigate(Routes.LYRIC_DIAGNOSTIC)
            }
            DebugAction("获取 Sony 日志", Icons.Default.ContentCopy, viewModel::requestSonyLogs)
            DebugAction("获取 Media Dump", Icons.Default.ContentCopy, viewModel::requestMediaDump)
            DebugAction("分享 Android 日志", Icons.Default.Share) {
                val file = viewModel.logFile()
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.files",
                        file
                    )
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "分享诊断日志"
                        )
                    )
                }
            }
            OutlinedButton(
                onClick = viewModel::clearLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Close, null)
                Spacer(Modifier.width(8.dp))
                Text("清理本地日志")
            }
            Text(
                "RFCOMM 兼容入口",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "仅用于旧 Sony 版本。启动时会主动停止 BLE V2，退出后恢复已保存设备的 BLE 连接。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            DebugAction(
                "启动 RFCOMM 兼容模式",
                Icons.Default.Bluetooth,
                viewModel::startLegacyRfcomm
            )
            DebugAction(
                "停止 RFCOMM 并恢复 BLE",
                Icons.Default.Refresh,
                viewModel::stopLegacyRfcomm
            )
            if (diagnostics.sonyLogs.isNotBlank()) {
                DiagnosticText("Sony 日志", diagnostics.sonyLogs)
            }
            if (diagnostics.mediaFieldDump.isNotBlank()) {
                DiagnosticText("Media Dump", diagnostics.mediaFieldDump)
            }
            if (diagnostics.recentLogs.isNotEmpty()) {
                DiagnosticText("Android 最近日志", diagnostics.recentLogs.takeLast(80).joinToString("\n"))
            }
        }
    }
}

@Composable
private fun DebugAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun DiagnosticText(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text,
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControllerTopBar(
    title: String,
    navController: NavHostController,
    action: @Composable (() -> Unit)? = null
) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
        },
        actions = {
            action?.invoke()
        }
    )
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.LibraryBooks,
            null,
            Modifier.size(54.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = onAction) { Text(action) }
    }
}

private fun connectionLabel(value: ConnectionState): String =
    when (value.phase) {
        ConnectionPhase.CONNECTED -> when (value.health) {
            ConnectionHealth.HEALTHY -> "Sony 已连接"
            ConnectionHealth.SUSPECT -> "连接待确认"
            ConnectionHealth.STALE -> "连接无响应"
            ConnectionHealth.DISCONNECTED -> "Sony 已断开"
        }
        ConnectionPhase.SCANNING -> "正在查找 Sony"
        ConnectionPhase.CONNECTING -> "正在连接"
        ConnectionPhase.DISCOVERING -> "正在发现服务"
        ConnectionPhase.SUBSCRIBING -> "正在订阅"
        ConnectionPhase.RECONNECTING -> "正在重连 ${value.reconnectAttempt}"
        ConnectionPhase.DISCONNECTED -> "Sony 未连接"
    }

private fun lyricStageText(value: LyricLoadingStage): String =
    when (value) {
        LyricLoadingStage.IDLE -> "等待播放"
        LyricLoadingStage.WAITING_QQ_QRC -> "等待 QQ 音乐歌词"
        LyricLoadingStage.WINDOW_READY -> "当前歌词已就绪"
        LyricLoadingStage.FULL_LYRICS -> "正在接收完整歌词"
        LyricLoadingStage.READY -> "完整歌词已就绪"
        LyricLoadingStage.FAILED -> "歌词获取失败"
    }

private fun currentLineIndex(
    lines: List<LyricLine>,
    positionMs: Long,
    serverIndex: Int
): Int = LyricTimeline.currentLinePosition(lines, positionMs, serverIndex)

private fun auxiliaryText(line: LyricLine, mode: LyricDisplayMode): String? =
    buildList {
        if (mode.showsTranslation) line.translation?.takeIf(String::isNotBlank)?.let(::add)
        if (mode.showsRomanization) line.romanization?.takeIf(String::isNotBlank)?.let(::add)
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun formatDuration(milliseconds: Long): String =
    DateUtils.formatElapsedTime(milliseconds.coerceAtLeast(0L) / 1_000L)

private fun formatDate(milliseconds: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(milliseconds))
