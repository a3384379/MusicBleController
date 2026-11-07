package com.example.controllerapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
        ArtworkAtmosphereBackground(
            bitmap = artwork.bitmap,
            overlayColors = listOf(
                Color(0xd8090b10),
                Color(0xaa090b10),
                Color(0xff090b10)
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
private fun ArtworkAtmosphereBackground(
    bitmap: Bitmap?,
    overlayColors: List<Color>
) {
    bitmap?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.14f
                    scaleY = 1.14f
                    alpha = 0.76f
                }
                .blur(
                    radius = 34.dp,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded
                )
        )
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(overlayColors)
        )
    )
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
        NaturalSpectrum(
            playback = playback,
            lyrics = lyrics,
            settings = settings,
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
private fun NaturalSpectrum(
    playback: PlaybackState,
    lyrics: LyricsState,
    settings: ControllerSettings,
    positionMs: Long,
    animated: Boolean
) {
    val lyricPositionMs = positionMs + settings.lyricOffsetMs
    val linePosition = currentLineIndex(
        lyrics.lines,
        lyricPositionMs,
        lyrics.currentLineIndex
    )
    val line = lyrics.lines.getOrNull(linePosition)
    val wordPosition = line?.let { LyricTimeline.currentWordPosition(it, lyricPositionMs) } ?: -1
    val engine = remember(playback.trackId) { NaturalSpectrumEngine() }
    val framePosition = if (animated) positionMs else 0L
    val levels = remember(
        playback.trackId,
        framePosition,
        playback.isPlaying,
        line?.index,
        wordPosition,
        animated
    ) {
        engine.levels(
            SpectrumFrame(
                trackSeed = playback.trackId.ifBlank { "waiting" },
                positionMs = framePosition,
                playing = playback.isPlaying && animated,
                lyricProgress = line?.let {
                    LyricTimeline.lineProgress(it, lyricPositionMs)
                } ?: 0f,
                wordSignature = if (animated && line != null) {
                    "${line.index}:$wordPosition"
                } else {
                    ""
                }
            )
        )
    }
    val accent = MaterialTheme.colorScheme.primary
    val opacity = when {
        playback.isPlaying && animated -> 0.72f
        playback.isPlaying -> 0.32f
        else -> 0.22f
    }
    Canvas(
        Modifier.fillMaxWidth(0.58f)
            .height(32.dp)
            .padding(vertical = 4.dp)
            .testTag("natural_spectrum")
    ) {
        if (levels.isEmpty()) return@Canvas
        val spacing = size.width / levels.size
        val gap = (spacing * 0.30f).coerceIn(2f, 4f)
        val barWidth = (spacing - gap).coerceAtLeast(2.4f)
        val centerY = size.height / 2f
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = opacity * 0.28f), Color.Transparent)
            ),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.2f
        )
        levels.forEachIndexed { index, level ->
            val height = (size.height * (0.10f + level * 0.84f)).coerceAtMost(size.height)
            val top = centerY - height / 2f
            val left = index * spacing + (spacing - barWidth) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = opacity),
                        accent.copy(alpha = opacity * 0.42f)
                    ),
                    startY = top,
                    endY = top + height
                ),
                topLeft = Offset(left, top),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
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
    Box(
        modifier = Modifier.fillMaxWidth()
            .height(172.dp)
            .clickable(onClick = onClick)
            .testTag("compact_lyrics")
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
                lyricAuxiliaryLines(current, settings.lyricDisplayMode).take(2).forEach { text ->
                    Text(
                        text,
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
private fun WordHighlightedText(
    line: LyricLine,
    positionMs: Long,
    modifier: Modifier = Modifier,
    fontSizeValue: Int = if (line.text.length > 42) 16 else 20,
    lineHeightValue: Int = if (line.text.length > 42) 21 else 26,
    textAlign: TextAlign = TextAlign.Center,
    highlightColor: Color = Color.White,
    pendingColor: Color = Color.White.copy(alpha = 0.45f),
    maxLines: Int = 3
) {
    val characterProgresses = LyricTimeline.characterProgresses(line, positionMs)
    val value = buildAnnotatedString {
        line.text.forEachIndexed { index, character ->
            withStyle(
                SpanStyle(
                    color = lerp(
                        pendingColor,
                        highlightColor,
                        characterProgresses.getOrElse(index) { 0f }
                    )
                )
            ) {
                append(character)
            }
        }
    }
    Text(
        value,
        modifier = modifier,
        fontSize = fontSizeValue.sp,
        lineHeight = lineHeightValue.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign
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
    val sliderState = remember { VolumeSliderState() }
    val value = sliderState.displayedValue(playback.volumeCurrent, playback.volumeMax)
    LaunchedEffect(enabled, playback.volumeMax) {
        if (!enabled || playback.volumeMax <= 0) sliderState.cancel()
    }
    LaunchedEffect(sliderState.draggedValue, enabled, playback.volumeMax) {
        if (sliderState.draggedValue != null && enabled && playback.volumeMax > 0) {
            delay(90L)
            onVolume(sliderState.displayedValue(playback.volumeCurrent, playback.volumeMax).toInt())
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
                sliderState.drag(it)
            },
            onValueChangeFinished = {
                if (enabled && playback.volumeMax > 0) {
                    sliderState.finish(playback.volumeCurrent, playback.volumeMax)?.let(onVolume)
                } else {
                    sliderState.cancel()
                }
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
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val position by viewModel.displayedPositionMs.collectAsStateWithLifecycle()
    val reduceAnimations by viewModel.reduceAnimations.collectAsStateWithLifecycle()
    LaunchedEffect(lyrics.trackId, lyrics.isFinal) {
        if (lyrics.trackId.isNotBlank() && !lyrics.isFinal) viewModel.ensureFullLyrics()
    }
    FullLyricsContent(
        connection = connection, playback = playback, lyrics = lyrics, artwork = artwork,
        settings = settings, position = position, navController = navController,
        reduceAnimations = reduceAnimations,
        onModeSelected = viewModel::updateLyricMode, onRetry = viewModel::retryLyrics,
        onSeek = viewModel::seekTo, onPrevious = viewModel::previous,
        onPlayPause = viewModel::playPause, onNext = viewModel::next
    )
}

@Composable
internal fun FullLyricsContent(
    connection: ConnectionState,
    playback: PlaybackState,
    lyrics: LyricsState,
    artwork: ArtworkState,
    settings: ControllerSettings,
    position: Long,
    navController: NavHostController,
    reduceAnimations: Boolean,
    onModeSelected: (LyricDisplayMode) -> Unit,
    onRetry: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val displayLines = lyrics.lines
    val current = currentLineIndex(
        displayLines,
        position + settings.lyricOffsetMs,
        lyrics.currentLineIndex
    )
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    var selectedLineId by remember { mutableStateOf<Int?>(null) }
    var selectionFromScroll by remember { mutableStateOf(false) }
    var lastScrollEndMs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var touchInterruptedScroll by remember { mutableStateOf(false) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val nearestVisibleLineId by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            LyricBrowsePolicy.nearestLine(
                layout.visibleItemsInfo.mapNotNull { item ->
                    LyricViewportItem(
                        linePosition = item.key as? Int ?: return@mapNotNull null,
                        topPx = item.offset,
                        bottomPx = item.offset + item.size
                    )
                },
                viewportCenterPx = viewportCenter
            )
        }
    }
    val lyricViewportHeight by remember {
        derivedStateOf {
            (listState.layoutInfo.viewportEndOffset -
                listState.layoutInfo.viewportStartOffset).coerceAtLeast(0)
        }
    }
    val lyricTopPaddingPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val missingMessage = secondaryMissingMessage(displayLines, settings.lyricDisplayMode, lyrics.isFinal)
    LaunchedEffect(lyrics.trackId) {
        following = true
        selectedLineId = null
        selectionFromScroll = false
    }
    LaunchedEffect(lyrics.trackId, current, following, lyrics.isFinal, lyricViewportHeight, reduceAnimations) {
        if (following && lyricViewportHeight > 0 && current >= 0 && current < displayLines.size) {
            val offset = -(lyricViewportHeight / 2 - lyricTopPaddingPx).coerceAtLeast(0)
            if (reduceAnimations) listState.scrollToItem(current, offset)
            else listState.animateScrollToItem(current, offset)
        }
    }
    LaunchedEffect(isDragged) {
        if (isDragged) {
            following = false
            selectionFromScroll = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) lastScrollEndMs = SystemClock.uptimeMillis()
        }
    }
    LaunchedEffect(following, listState.isScrollInProgress) {
        if (!following && listState.isScrollInProgress) selectionFromScroll = true
    }
    LaunchedEffect(nearestVisibleLineId, following, selectionFromScroll) {
        if (!following && selectionFromScroll) {
            nearestVisibleLineId?.let { selectedLineId = it }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xff090b10))) {
        val compactScreen = maxHeight < 420.dp
        ArtworkAtmosphereBackground(
            bitmap = artwork.bitmap,
            overlayColors = listOf(
                Color(0xe8090b10),
                Color(0xc8090b10),
                Color(0xff090b10)
            )
        )
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (!compactScreen) {
                ControllerTopBar(
                    title = "歌词",
                    navController = navController,
                    transparent = true
                )
                }
            }
        ) { padding ->
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val compactHeight = maxHeight < 420.dp
                Column(
                    Modifier.fillMaxSize()
                        .padding(horizontal = 20.dp)
                ) {
                if (!compactHeight) {
                Text(
                    playback.title.ifBlank { "等待 QQ 音乐" },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    playback.artist,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.58f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                }
                if (compactScreen) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                        }
                        LyricModeSelector(
                            selected = settings.lyricDisplayMode,
                            onSelected = onModeSelected,
                            modifier = Modifier.weight(1f),
                            inlineStatus = if (!lyrics.isFinal && displayLines.isNotEmpty()) {
                                lyricStageText(lyrics.loadingStage)
                            } else missingMessage,
                            onInlineRetry = onRetry.takeIf { !lyrics.isFinal && displayLines.isNotEmpty() }
                        )
                    }
                } else {
                    LyricModeSelector(selected = settings.lyricDisplayMode, onSelected = onModeSelected)
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.CenterStart) {
                        if (!lyrics.isFinal && displayLines.isNotEmpty()) {
                            FullLyricsLoadingBanner(lyrics, settings, onRetry)
                        } else if (missingMessage != null) {
                            Text(
                                missingMessage,
                                modifier = Modifier.testTag("lyrics_status"),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
                if (displayLines.isEmpty()) {
                    EmptyState(
                        title = lyricStageText(lyrics.loadingStage),
                        message = lyrics.failureReason.ifBlank {
                            "正在等待 Sony 解析 QQ 音乐歌词"
                        },
                        action = "重新请求",
                        onAction = onRetry,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                top = 24.dp,
                                bottom = 24.dp,
                                start = 2.dp,
                                end = 2.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize().testTag("full_lyrics_list")
                                .pointerInput(listState) {
                                    awaitEachGesture {
                                        try {
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                val pressed = event.changes.any { it.pressed }
                                                if (pressed && listState.isScrollInProgress) {
                                                    touchInterruptedScroll = true
                                                }
                                            } while (pressed)
                                        } finally {
                                            if (touchInterruptedScroll) {
                                                lastScrollEndMs = SystemClock.uptimeMillis()
                                                touchInterruptedScroll = false
                                            }
                                        }
                                    }
                                }
                        ) {
                            itemsIndexed(displayLines, key = { _, line -> line.index }) {
                                    linePosition, line ->
                                val isCurrent = linePosition == current
                                val isBrowseSelected = !following &&
                                    line.index == selectedLineId
                                FullLyricRow(
                                    line = line,
                                    positionMs = position + settings.lyricOffsetMs,
                                    displayMode = settings.lyricDisplayMode,
                                    isCurrent = isCurrent,
                                    isBrowseSelected = isBrowseSelected,
                                    connected = connection.connected,
                                    onSelect = {
                                        if (!LyricBrowsePolicy.canActivate(
                                                listState.isScrollInProgress || touchInterruptedScroll, lastScrollEndMs,
                                                SystemClock.uptimeMillis()
                                            )) return@FullLyricRow
                                        val result = LyricBrowsePolicy.onLineTapped(
                                            browsing = !following,
                                            selectedLineId = selectedLineId,
                                            tappedLineId = line.index,
                                            lineTimeMs = line.timeMs,
                                            connected = connection.connected
                                        )
                                        following = false
                                        selectionFromScroll = false
                                        selectedLineId = result.selectedLineId
                                        result.seekPositionMs?.let { seekPosition ->
                                            onSeek(seekPosition)
                                            following = true
                                            selectedLineId = null
                                        }
                                    },
                                    onSeek = {
                                        if (connection.connected && LyricBrowsePolicy.canActivate(
                                                listState.isScrollInProgress || touchInterruptedScroll, lastScrollEndMs,
                                                SystemClock.uptimeMillis()
                                            )) {
                                            onSeek(line.timeMs)
                                            following = true
                                            selectedLineId = null
                                        }
                                    }
                                )
                            }
                        }
                        if (!following) {
                            Box(
                                Modifier.align(Alignment.CenterStart)
                                    .width(3.dp)
                                    .height(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xff8ee6a3))
                            )
                        }
                    }
                }
                if (compactHeight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LyricsReturnControl(following, Modifier.weight(1f)) {
                            following = true
                            selectedLineId = null
                        }
                        FullLyricsControls(
                            isPlaying = playback.isPlaying,
                            enabled = connection.connected,
                            onPrevious = onPrevious,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            compact = true
                        )
                    }
                } else {
                    LyricsReturnControl(following, Modifier.align(Alignment.CenterHorizontally)) {
                        following = true
                        selectedLineId = null
                    }
                    FullLyricsControls(
                        isPlaying = playback.isPlaying,
                        enabled = connection.connected,
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext
                    )
                }
                }
                }
            }
        }
    }
}

@Composable
private fun LyricsReturnControl(following: Boolean, modifier: Modifier = Modifier, onReturn: () -> Unit) {
    TextButton(
        onClick = onReturn,
        enabled = !following,
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color(0xffa4efb6),
            disabledContentColor = Color.White.copy(alpha = 0.72f)
        ),
        modifier = modifier.heightIn(min = 48.dp).testTag("lyrics_return_current")
    ) { Text(if (following) "跟随中" else "回到当前歌词") }
}

@Composable
private fun LyricModeSelector(
    selected: LyricDisplayMode,
    onSelected: (LyricDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
    inlineStatus: String? = null,
    onInlineRetry: (() -> Unit)? = null
) {
    val options = listOf(
        LyricDisplayMode.ORIGINAL to "原文",
        LyricDisplayMode.ORIGINAL_TRANSLATION to "翻译",
        LyricDisplayMode.ORIGINAL_ROMANIZATION to "罗马音",
        LyricDisplayMode.ALL to "全部"
    )
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (mode, label) ->
            Surface(
                onClick = { onSelected(mode) },
                shape = RoundedCornerShape(11.dp),
                color = if (selected == mode) {
                    Color.White.copy(alpha = 0.17f)
                } else {
                    Color.Transparent
                },
                contentColor = Color.White,
                modifier = Modifier.widthIn(min = 72.dp).heightIn(min = 48.dp)
                    .semantics { this.selected = selected == mode }
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (selected == mode) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                )
            }
            if (selected == mode && inlineStatus != null) {
                Text(
                    inlineStatus,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("lyrics_status").padding(horizontal = 8.dp)
                )
                if (onInlineRetry != null) {
                    TextButton(
                        onClick = onInlineRetry,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun FullLyricsLoadingBanner(
    lyrics: LyricsState,
    settings: ControllerSettings,
    retry: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(lyricStageText(lyrics.loadingStage), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (settings.experienceMode == AppExperienceMode.DEBUG) {
                Text(
                    if (lyrics.expectedChunks > 0) {
                        "${lyrics.receivedChunks}/${lyrics.expectedChunks} 分片 · ${lyrics.protocolFormat}"
                    } else {
                        "${lyrics.partialFullLines.size} 行 · ${lyrics.protocolFormat}"
                    },
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.50f)
                )
            }
        }
        TextButton(onClick = retry, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
            Text("重试", fontSize = 12.sp)
        }
    }
}

@Composable
internal fun FullLyricRow(
    line: LyricLine,
    positionMs: Long,
    displayMode: LyricDisplayMode,
    isCurrent: Boolean,
    isBrowseSelected: Boolean,
    connected: Boolean,
    onSelect: () -> Unit,
    onSeek: () -> Unit
) {
    val actionHeight = with(LocalDensity.current) { maxOf(48.dp, 12.sp.toDp() + 16.dp) }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .testTag("lyric_row_${line.index}")
            .clickable(onClick = onSelect)
            .heightIn(min = 48.dp)
            .background(
                when {
                    isBrowseSelected -> Color.White.copy(alpha = 0.105f)
                    isCurrent -> Color(0xff8ee6a3).copy(alpha = 0.075f)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isCurrent) {
                WordHighlightedText(
                    line = line,
                    positionMs = positionMs,
                    modifier = Modifier.fillMaxWidth().testTag("lyric_original_${line.index}"),
                    fontSizeValue = if (line.text.length > 48) 17 else 20,
                    lineHeightValue = if (line.text.length > 48) 23 else 28,
                    textAlign = TextAlign.Start,
                    highlightColor = Color(0xff8ee6a3),
                    pendingColor = Color.White.copy(alpha = 0.76f),
                    maxLines = Int.MAX_VALUE
                )
            } else {
                Text(
                    line.text,
                    modifier = Modifier.testTag("lyric_original_${line.index}"),
                    fontSize = if (line.text.length > 48) 17.sp else 20.sp,
                    lineHeight = if (line.text.length > 48) 23.sp else 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = if (isBrowseSelected) 0.96f else 0.76f)
                )
            }
            lyricAuxiliaryLines(line, displayMode).forEachIndexed { index, auxiliary ->
                Text(
                    auxiliary,
                    modifier = Modifier.testTag("lyric_aux_${line.index}_$index"),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(
                        alpha = when {
                            isCurrent -> 0.82f
                            isBrowseSelected -> 0.82f
                            else -> 0.76f
                        }
                    )
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(actionHeight), contentAlignment = Alignment.CenterEnd) {
        if (isBrowseSelected) {
            Surface(
                onClick = onSeek,
                enabled = connected,
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.14f),
                modifier = Modifier.heightIn(min = actionHeight).testTag("lyric_seek_time_${line.index}")
                    .semantics { contentDescription = "跳转到 ${formatDuration(line.timeMs)}" }
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(13.dp))
                    Text(
                        formatDuration(line.timeMs),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun FullLyricsControls(
    isPlaying: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean = false
) {
    Row(
        if (compact) Modifier else Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 30.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(enabled = enabled, onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(30.dp))
        }
        FilledIconButton(
            enabled = enabled,
            onClick = onPlayPause,
            modifier = Modifier.size(if (compact) 48.dp else 58.dp)
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "暂停" else "播放",
                Modifier.size(if (compact) 26.dp else 32.dp)
            )
        }
        IconButton(enabled = enabled, onClick = onNext) {
            Icon(Icons.Default.SkipNext, "下一首", Modifier.size(30.dp))
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
    // Entering an older window keeps the current scroll anchor. Returning to
    // recent replaces the list and its state together, instead of scrolling a
    // list whose backing window has just shrunk from 1000 to 500 rows.
    val previousListState = remember { arrayOfNulls<LazyListState>(1) }
    val listState = rememberSaveable(history.showingOlderWindow, saver = LazyListState.Saver) {
        if (history.showingOlderWindow) previousListState[0] ?: LazyListState()
        else LazyListState()
    }
    SideEffect { previousListState[0] = listState }
    var range by remember { mutableStateOf("TODAY") }
    LaunchedEffect(Unit) { viewModel.syncHistory() }
    Scaffold(
        topBar = {
            ControllerTopBar(
                title = "播放历史",
                navController = navController,
                action = {
                    IconButton(
                        onClick = viewModel::syncHistory,
                        enabled = !history.loading && !history.clearing
                    ) {
                        Icon(Icons.Default.Sync, "同步历史")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            HistoryStatusPanel(history, range, viewModel)
            key(history.showingOlderWindow) {
                LazyColumn(
                    Modifier.weight(1f).testTag("history_list"),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 12.dp,
                        bottom = padding.calculateBottomPadding() + 28.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "history_range", contentType = "history_range") {
                        StatsRangeSelector(range) { range = it }
                    }
                    history.stats[range]?.let { stats ->
                        item(key = "history_stats", contentType = "history_stats") { StatsOverview(stats) }
                    }
                    item(key = "history_recent_title", contentType = "history_recent_title") {
                        Text(
                            "最近播放",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (history.sessions.isEmpty()) {
                        item(key = "history_empty", contentType = "history_empty") {
                            Text(
                                history.status.ifBlank { if (history.loading) "正在同步…" else "暂无历史" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 28.dp)
                            )
                        }
                    }
                    items(history.sessions, key = { it.sessionId }, contentType = { "history_session" }) { session ->
                        Card(
                            modifier = Modifier.testTag("history_session_${session.sessionId}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
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
                                        HistoryDisplayPolicy.status(session),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (history.sessions.isNotEmpty() && history.hasMore) {
                        item(key = "history_load_more", contentType = "history_load_more") {
                            OutlinedButton(
                                onClick = viewModel::loadMoreHistory,
                                enabled = !history.loading && !history.clearing,
                                modifier = Modifier.fillMaxWidth().testTag("history_load_more")
                            ) {
                                Text(if (history.loading) "正在加载…" else "加载更早记录")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryStatusPanel(history: HistoryState, range: String, viewModel: ControllerViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (history.loading || history.clearing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            history.status.ifBlank { "同步 Sony 播放历史，保留本地缓存" },
            modifier = Modifier.testTag("history_status"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (range in history.failedRanges) {
            Text(
                if (range in history.stats) "当前范围统计更新失败，显示上次数据" else "当前范围统计更新失败，可重试",
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(
                onClick = viewModel::syncHistory,
                enabled = !history.loading && !history.clearing
            ) { Text("重试同步") }
        }
        if (history.showingOlderWindow) {
            Text("正在查看更早记录，每次最多展示 1000 条", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = viewModel::showRecentHistory,
                enabled = !history.loading && !history.clearing,
                modifier = Modifier.testTag("history_show_recent")
            ) { Text("返回最近播放") }
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
            Text(
                "统计按会话开始时间归属所选范围，本周和本月采用 Sony 当地自然周、自然月。实际收听满 30 秒，或歌曲时长已知且听满一半，计为一次有效播放；时长未知时以 30 秒为准。进行中会话计入收听时长及已达到门槛的播放次数，但不计入完播率和跳过率；两项比率均以已结束会话数为分母。",
                modifier = Modifier.testTag("stats_explanation"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    var confirmSnapshotDeletion by remember { mutableStateOf(false) }
    if (confirmSnapshotDeletion) {
        AlertDialog(
            onDismissRequest = { confirmSnapshotDeletion = false },
            title = { Text("删除已导出的日志副本？") },
            text = { Text("删除后，尚未读取完成的分享可能失败；本地日志不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSnapshotDeletion = false
                    viewModel.clearExportedLogs { success ->
                        Toast.makeText(context, if (success) "已删除导出副本" else "删除导出副本失败", Toast.LENGTH_LONG).show()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmSnapshotDeletion = false }) { Text("取消") } }
        )
    }
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
                viewModel.exportLogs { file, error ->
                    if (file == null) {
                        Toast.makeText(context, "日志导出失败：${error ?: "未知错误"}", Toast.LENGTH_LONG).show()
                    } else {
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
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
                        } catch (_: Exception) {
                            Toast.makeText(context, "无法打开日志分享", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            TextButton(onClick = { confirmSnapshotDeletion = true }) { Text("删除已导出的日志副本") }
            OutlinedButton(
                onClick = {
                    viewModel.clearLogs { success ->
                        Toast.makeText(context, if (success) "已清理本地日志" else "清理本地日志失败", Toast.LENGTH_LONG).show()
                    }
                },
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
    transparent: Boolean = false,
    action: @Composable (() -> Unit)? = null
) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = if (transparent) Color.Transparent else MaterialTheme.colorScheme.surface,
            scrolledContainerColor = if (transparent) {
                Color.Black.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            navigationIconContentColor = if (transparent) Color.White else MaterialTheme.colorScheme.onSurface,
            titleContentColor = if (transparent) Color.White else MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = if (transparent) Color.White else MaterialTheme.colorScheme.onSurface
        ),
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

private fun lyricAuxiliaryLines(line: LyricLine, mode: LyricDisplayMode): List<String> =
    buildList {
        if (mode.showsTranslation) line.translation?.takeIf(String::isNotBlank)?.let(::add)
        if (mode.showsRomanization) line.romanization?.takeIf(String::isNotBlank)?.let(::add)
    }

private fun secondaryMissingMessage(
    lines: List<LyricLine>,
    mode: LyricDisplayMode,
    isFinal: Boolean
): String? {
    if (!isFinal || lines.isEmpty() || mode == LyricDisplayMode.ORIGINAL) return null
    val translationMissing = mode.showsTranslation && lines.none {
        !it.translation.isNullOrBlank()
    }
    val romanizationMissing = mode.showsRomanization && lines.none {
        !it.romanization.isNullOrBlank()
    }
    return when {
        translationMissing && romanizationMissing -> "该歌曲暂无翻译和罗马音"
        translationMissing -> "该歌曲暂无翻译"
        romanizationMissing -> "该歌曲暂无罗马音"
        else -> null
    }
}

private fun formatDuration(milliseconds: Long): String =
    DateUtils.formatElapsedTime(milliseconds.coerceAtLeast(0L) / 1_000L)

private fun formatDate(milliseconds: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(milliseconds))
