@file:OptIn(ExperimentalMaterial3Api::class)

package com.althub.alteriza

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.althub.alteriza.ui.theme.BbBackground
import com.althub.alteriza.ui.theme.BbBlack
import com.althub.alteriza.ui.theme.BbBorder
import com.althub.alteriza.ui.theme.BbBorderBright
import com.althub.alteriza.ui.theme.BbMuted
import com.althub.alteriza.ui.theme.BbOrange
import com.althub.alteriza.ui.theme.BbSurface
import com.althub.alteriza.ui.theme.BbSurfaceElevated
import com.althub.alteriza.ui.theme.BbWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "althub_prefs"
private const val KEY_WELCOME_SHOWN = "welcome_popup_shown"

private enum class NavDestination(val label: String, val icon: ImageVector) {
    FEED("FEED", Icons.Default.Home),
    VIDEOS("VIDEOS", Icons.Default.Movie),
    PHOTOS("PHOTOS", Icons.Default.Image),
    INFO("INFO", Icons.Default.Campaign),
    CORN("CORN", Icons.Default.Lock)
}

private enum class FeedChip(val label: String) {
    ALL("ALL"),
    VIDEOS("VIDEOS"),
    PHOTOS("PHOTOS"),
    INFO("INFO")
}

private enum class CORNChip(val label: String) {
    ALL("ALL"),
    VIDEOS("VIDEOS"),
    PHOTOS("PHOTOS")
}

private enum class VideoResizeMode(val label: String) {
    FIT("FIT"),
    FILL("FILL"),
    STRETCH("16:9")
}

private sealed class TimelineEntry {
    data class MediaEntry(val item: RemoteMediaItem) : TimelineEntry()
    data class PostEntry(val post: InfoPost) : TimelineEntry()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun applyImmersiveSystemBars(activity: Activity?) {
    val window = activity?.window ?: return
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}

private fun requestNewCornCaptchaChallenge(): CaptchaChallenge {
    val rawJson = NativeCore.generateCornCaptcha()
    val obj = runCatching { JSONObject(rawJson) }.getOrNull()
    return CaptchaChallenge(
        challengeId = obj?.optString("challenge_id", "") ?: "",
        code = obj?.optString("code", "ALTHUB") ?: "ALTHUB"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AltHubApp() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val requiredCornTaps = remember { NativeCore.getCornRequiredTaps().coerceAtLeast(10) }
    val cornDisclaimerTitle = remember { NativeCore.getCornDisclaimerTitle() }
    val cornDisclaimerMessage = remember { NativeCore.getCornDisclaimerMessage() }

    var currentTab by remember { mutableStateOf(NavDestination.FEED) }
    var settingsOpen by remember { mutableStateOf(false) }
    var activeFeedChip by remember { mutableStateOf(FeedChip.ALL) }
    var activeCORNChip by remember { mutableStateOf(CORNChip.ALL) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var initialLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    var CORNUnlocked by remember { mutableStateOf(false) }
    var showCornDisclaimerAlert by remember { mutableStateOf(false) }
    var showCornCaptchaDialog by remember { mutableStateOf(false) }
    var cornCaptchaChallenge by remember { mutableStateOf<CaptchaChallenge?>(null) }
    var cornCaptchaInput by remember { mutableStateOf("") }
    var cornCaptchaError by remember { mutableStateOf<String?>(null) }
    var logoTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapMs by remember { mutableLongStateOf(0L) }

    var welcomePopupPost by remember { mutableStateOf<InfoPost?>(null) }

    var hwAcceleration by remember { mutableStateOf(AppStorageManager.isHwAccelerationEnabled(context)) }
    var downloadFormatPref by remember { mutableStateOf(AppStorageManager.getDownloadFormatPref(context)) }
    var pendingDownloadItem by remember { mutableStateOf<RemoteMediaItem?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var storageRefreshTick by remember { mutableIntStateOf(0) }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var discovery by remember {
        mutableStateOf(
            DiscoveryResult(
                mediaItems = emptyList(),
                infoPosts = emptyList(),
                activeSources = emptyList()
            )
        )
    }

    var CORNDiscovery by remember {
        mutableStateOf(
            DiscoveryResult(
                mediaItems = emptyList(),
                infoPosts = emptyList(),
                activeSources = emptyList()
            )
        )
    }

    var selectedMedia by remember { mutableStateOf<RemoteMediaItem?>(null) }

    fun openMedia(item: RemoteMediaItem) {
        if (item.localPath == null) {
            AppStorageManager.recordHistory(context, item)
            storageRefreshTick++
        }
        val cachedThumb = AutoDiscoveryRepository.getCachedThumbnail(item.id)
        if (cachedThumb != null) {
            activity?.requestedOrientation = if (cachedThumb.width > cachedThumb.height) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            applyImmersiveSystemBars(activity)
        }
        selectedMedia = item
    }

    fun closeViewer() {
        selectedMedia = null
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyImmersiveSystemBars(activity)
    }

    fun executeDownload(item: RemoteMediaItem, saveAsContainer: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        scope.launch {
            isDownloading = true
            val res = AppStorageManager.downloadMediaItem(context, item, saveAsContainer)
            isDownloading = false
            storageRefreshTick++
            res.onSuccess { file ->
                Toast.makeText(
                    context,
                    "Saved: ${file.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "Download failed: ${err.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun requestDownload(item: RemoteMediaItem) {
        when (downloadFormatPref) {
            DownloadFormatPref.ASK -> {
                pendingDownloadItem = item
            }
            DownloadFormatPref.CONTAINER -> {
                executeDownload(item, saveAsContainer = true)
            }
            DownloadFormatPref.ORIGINAL -> {
                executeDownload(item, saveAsContainer = false)
            }
        }
    }

    suspend fun syncRepository(fromPullRefresh: Boolean = false) {
        if (fromPullRefresh) {
            isRefreshing = true
        } else {
            initialLoading = true
        }
        val mainResult = AutoDiscoveryRepository.discoverAll()
        discovery = mainResult

        if (!prefs.getBoolean(KEY_WELCOME_SHOWN, false)) {
            val welcome = mainResult.infoPosts.firstOrNull {
                it.fileName.equals("welcome.bbcode", ignoreCase = true) ||
                    it.fileName.startsWith("welcome.", ignoreCase = true)
            }
            if (welcome != null) {
                prefs.edit().putBoolean(KEY_WELCOME_SHOWN, true).apply()
                welcomePopupPost = welcome
            }
        }

        if (CORNUnlocked) {
            CORNDiscovery = AutoDiscoveryRepository.discoverHiddenCdn()
        }

        initialLoading = false
        isRefreshing = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                CORNUnlocked = false
                showCornDisclaimerAlert = false
                showCornCaptchaDialog = false
                cornCaptchaChallenge = null
                cornCaptchaInput = ""
                cornCaptchaError = null
                logoTapCount = 0
                CORNDiscovery = DiscoveryResult(emptyList(), emptyList(), emptyList())
                if (currentTab == NavDestination.CORN) {
                    currentTab = NavDestination.FEED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        LocalStreamServer.initCacheDir(context)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        syncRepository(fromPullRefresh = false)
    }

    val allVideos = remember(discovery.mediaItems, searchQuery) {
        discovery.mediaItems.filter { item ->
            item.kind == MediaKind.VIDEO && (
                searchQuery.isBlank() ||
                    item.title.contains(searchQuery, true) ||
                    item.fileName.contains(searchQuery, true) ||
                    (item.videoTags?.contains(searchQuery, true) == true)
                )
        }
    }

    val allPhotos = remember(discovery.mediaItems, searchQuery) {
        discovery.mediaItems.filter { item ->
            item.kind == MediaKind.IMAGE && (
                searchQuery.isBlank() ||
                    item.title.contains(searchQuery, true) ||
                    item.fileName.contains(searchQuery, true)
                )
        }
    }

    val allPosts = remember(discovery.infoPosts, searchQuery) {
        discovery.infoPosts.filter { post ->
            searchQuery.isBlank() ||
                post.title.contains(searchQuery, true) ||
                post.bbcodeContent.contains(searchQuery, true)
        }
    }

    val CORNFilteredMedia = remember(CORNDiscovery.mediaItems, activeCORNChip, searchQuery) {
        CORNDiscovery.mediaItems.filter { item ->
            val matchChip = when (activeCORNChip) {
                CORNChip.ALL -> true
                CORNChip.VIDEOS -> item.kind == MediaKind.VIDEO
                CORNChip.PHOTOS -> item.kind == MediaKind.IMAGE
            }
            val matchQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, true) ||
                item.fileName.contains(searchQuery, true) ||
                (item.videoTags?.contains(searchQuery, true) == true)
            matchChip && matchQuery
        }
    }

    val timelineEntries = remember(discovery, activeFeedChip, searchQuery) {
        val mediaList = discovery.mediaItems.filter { item ->
            val matchChip = when (activeFeedChip) {
                FeedChip.ALL -> true
                FeedChip.VIDEOS -> item.kind == MediaKind.VIDEO
                FeedChip.PHOTOS -> item.kind == MediaKind.IMAGE
                FeedChip.INFO -> false
            }
            val matchQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, true) ||
                item.fileName.contains(searchQuery, true) ||
                (item.videoTags?.contains(searchQuery, true) == true)
            matchChip && matchQuery
        }.map { TimelineEntry.MediaEntry(it) }

        val postList = if (activeFeedChip == FeedChip.ALL || activeFeedChip == FeedChip.INFO) {
            allPosts.map { TimelineEntry.PostEntry(it) }
        } else {
            emptyList()
        }

        if (activeFeedChip == FeedChip.ALL) {
            val merged = mutableListOf<TimelineEntry>()
            val maxLen = maxOf(mediaList.size, postList.size)
            var pIdx = 0
            for (i in 0 until maxLen) {
                if (i < mediaList.size) merged.add(mediaList[i])
                if ((i + 1) % 2 == 0 && pIdx < postList.size) {
                    merged.add(postList[pIdx++])
                }
            }
            while (pIdx < postList.size) {
                merged.add(postList[pIdx++])
            }
            merged
        } else {
            mediaList + postList
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBackground)
    ) {
        TechnicalGridBackdrop()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            SocialTopBar(
                CORNUnlocked = CORNUnlocked,
                settingsOpen = settingsOpen,
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                onLogoTap = {
                    val now = SystemClock.elapsedRealtime()
                    logoTapCount = if (now - lastLogoTapMs <= 3000L) logoTapCount + 1 else 1
                    lastLogoTapMs = now
                    if (logoTapCount >= requiredCornTaps) {
                        logoTapCount = 0
                        settingsOpen = false
                        if (!CORNUnlocked) {
                            showCornCaptchaDialog = false
                            cornCaptchaChallenge = null
                            cornCaptchaInput = ""
                            cornCaptchaError = null
                            showCornDisclaimerAlert = true
                        } else {
                            CORNUnlocked = false
                            showCornDisclaimerAlert = false
                            showCornCaptchaDialog = false
                            cornCaptchaChallenge = null
                            CORNDiscovery = DiscoveryResult(emptyList(), emptyList(), emptyList())
                            if (currentTab == NavDestination.CORN) {
                                currentTab = NavDestination.FEED
                            }
                        }
                    } else if (logoTapCount == 1) {
                        settingsOpen = !settingsOpen
                    }
                },
                onToggleSearch = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) searchQuery = ""
                },
                onSearchChange = { searchQuery = it }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorder)
            )

            if (settingsOpen) {
                BackHandler {
                    settingsOpen = false
                }
                SettingsScreen(
                    hwAcceleration = hwAcceleration,
                    downloadFormatPref = downloadFormatPref,
                    refreshTick = storageRefreshTick,
                    onToggleHwAcceleration = { enabled ->
                        hwAcceleration = enabled
                        AppStorageManager.setHwAccelerationEnabled(context, enabled)
                    },
                    onSelectDownloadFormatPref = { pref ->
                        downloadFormatPref = pref
                        AppStorageManager.setDownloadFormatPref(context, pref)
                    },
                    onClearCache = {
                        AppStorageManager.clearAllCache(context)
                        storageRefreshTick++
                        Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                    },
                    onClearHistory = {
                        AppStorageManager.clearHistory(context)
                        storageRefreshTick++
                        Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                    },
                    onOpenHistoryItem = { rec ->
                        settingsOpen = false
                        openMedia(
                            RemoteMediaItem(
                                id = rec.id,
                                title = rec.title,
                                fileName = rec.fileName,
                                kind = rec.kind,
                                sourceType = rec.sourceType,
                                mediaUrl = rec.mediaUrl,
                                thumbnailUrl = rec.thumbnailUrl,
                                sizeBytes = 0L,
                                folderPath = "/history",
                                isContainer = rec.isContainer,
                                videoTags = if (rec.kind == MediaKind.VIDEO) {
                                    AutoDiscoveryRepository.extractVideoTags(rec.fileName)
                                } else {
                                    null
                                }
                            )
                        )
                    },
                    onOpenDownloadedFile = { entry ->
                        settingsOpen = false
                        val lower = entry.name.lowercase()
                        val isContainer = lower.endsWith(".vcv") || lower.endsWith(".icv")
                        openMedia(
                            RemoteMediaItem(
                                id = "local_${entry.path.hashCode()}",
                                title = if (entry.isVideo) {
                                    AutoDiscoveryRepository.formatVideoTitle(entry.name)
                                } else {
                                    entry.name.substringBeforeLast('.', entry.name)
                                },
                                fileName = entry.name,
                                kind = if (entry.isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                                sourceType = SourceType.DIRECT,
                                mediaUrl = Uri.fromFile(File(entry.path)).toString(),
                                thumbnailUrl = null,
                                sizeBytes = entry.sizeBytes,
                                folderPath = "/local",
                                isContainer = isContainer,
                                localPath = entry.path,
                                videoTags = if (entry.isVideo) {
                                    AutoDiscoveryRepository.extractVideoTags(entry.name)
                                } else {
                                    null
                                }
                            )
                        )
                    },
                    onDeleteDownloadedFile = { entry ->
                        runCatching { File(entry.path).delete() }
                        storageRefreshTick++
                    },
                    onClose = { settingsOpen = false },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                val pullRefreshState = rememberPullToRefreshState()

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        scope.launch { syncRepository(fromPullRefresh = true) }
                    },
                    state = pullRefreshState,
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullRefreshState,
                            isRefreshing = isRefreshing,
                            containerColor = BbSurfaceElevated,
                            color = BbOrange,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                        initialLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .background(BbSurface)
                                        .border(1.dp, BbBorder)
                                        .padding(24.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = BbWhite,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = "LOADING FEED...",
                                        color = BbWhite,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }

                        currentTab == NavDestination.FEED -> {
                            val featuredItem = allVideos.firstOrNull() ?: allPhotos.firstOrNull()
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (featuredItem != null && searchQuery.isBlank() && activeFeedChip == FeedChip.ALL) {
                                    item(key = "hero_${featuredItem.id}") {
                                        FeaturedMediaSpotlight(
                                            item = featuredItem,
                                            onOpen = { openMedia(featuredItem) }
                                        )
                                    }
                                }

                                item(key = "feed_chips") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(FeedChip.entries) { chip ->
                                            CubicFilterChip(
                                                label = chip.label,
                                                selected = activeFeedChip == chip,
                                                onClick = { activeFeedChip = chip }
                                            )
                                        }
                                    }
                                }

                                if (timelineEntries.isEmpty()) {
                                    item(key = "empty_feed") {
                                        EmptyFeedBox(
                                            title = "FEED IS EMPTY",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(
                                        items = timelineEntries,
                                        key = { entry ->
                                            when (entry) {
                                                is TimelineEntry.MediaEntry -> "m_${entry.item.id}"
                                                is TimelineEntry.PostEntry -> "p_${entry.post.id}"
                                            }
                                        }
                                    ) { entry ->
                                        when (entry) {
                                            is TimelineEntry.MediaEntry -> {
                                                SocialStreamPostCard(
                                                    item = entry.item,
                                                    onOpen = { openMedia(entry.item) },
                                                    onDownload = { requestDownload(entry.item) },
                                                    modifier = Modifier.padding(horizontal = 14.dp)
                                                )
                                            }
                                            is TimelineEntry.PostEntry -> {
                                                SocialAnnouncementCard(
                                                    post = entry.post,
                                                    modifier = Modifier.padding(horizontal = 14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.VIDEOS -> {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allVideos.isEmpty()) {
                                    item(key = "empty_videos") {
                                        EmptyFeedBox(
                                            title = "NO VIDEOS FOUND",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(allVideos, key = { it.id }) { video ->
                                        VideoStreamCard(
                                            item = video,
                                            onWatch = { openMedia(video) },
                                            onDownload = { requestDownload(video) }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.PHOTOS -> {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allPhotos.isEmpty()) {
                                    item(key = "empty_photos") {
                                        EmptyFeedBox(
                                            title = "NO PHOTOS FOUND",
                                            subtitle = discovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(allPhotos, key = { it.id }) { photo ->
                                        SocialStreamPostCard(
                                            item = photo,
                                            onOpen = { openMedia(photo) },
                                            onDownload = { requestDownload(photo) }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.INFO -> {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (allPosts.isEmpty()) {
                                    item(key = "empty_info") {
                                        EmptyFeedBox(
                                            title = "NO INFO POSTS",
                                            subtitle = "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(allPosts, key = { it.id }) { post ->
                                        SocialAnnouncementCard(post = post)
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.CORN -> {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(key = "CORN_header_banner") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BbSurfaceElevated)
                                            .border(1.dp, BbOrange)
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(BbOrange)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "CORN // ARCHIVE STREAM",
                                            color = BbWhite,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        CubicActionButton(
                                            text = "HIDE",
                                            primary = false,
                                            onClick = {
                                                CORNUnlocked = false
                                                CORNDiscovery = DiscoveryResult(emptyList(), emptyList(), emptyList())
                                                currentTab = NavDestination.FEED
                                            }
                                        )
                                    }
                                }

                                item(key = "CORN_chips") {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(CORNChip.entries) { chip ->
                                            CubicFilterChip(
                                                label = chip.label,
                                                selected = activeCORNChip == chip,
                                                onClick = { activeCORNChip = chip }
                                            )
                                        }
                                    }
                                }

                                if (CORNFilteredMedia.isEmpty() && CORNDiscovery.infoPosts.isEmpty()) {
                                    item(key = "empty_CORN") {
                                        EmptyFeedBox(
                                            title = "CORN STREAM EMPTY",
                                            subtitle = CORNDiscovery.errorMessage ?: "Pull down to refresh"
                                        )
                                    }
                                } else {
                                    items(CORNFilteredMedia, key = { "CORN_${it.id}" }) { media ->
                                        SocialStreamPostCard(
                                            item = media,
                                            onOpen = { openMedia(media) },
                                            onDownload = { requestDownload(media) }
                                        )
                                    }
                                    items(CORNDiscovery.infoPosts, key = { "CORN_post_${it.id}" }) { post ->
                                        SocialAnnouncementCard(post = post)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorder)
            )

            CubicBottomNavBar(
                current = currentTab,
                settingsOpen = settingsOpen,
                CORNUnlocked = CORNUnlocked,
                videoCount = allVideos.size,
                photoCount = allPhotos.size,
                infoCount = allPosts.size,
                CORNCount = CORNDiscovery.mediaItems.size,
                onSelect = { dest ->
                    settingsOpen = false
                    currentTab = dest
                }
            )
        }

        if (isDownloading) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 62.dp)
                    .background(BbSurfaceElevated)
                    .border(1.dp, BbOrange)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = BbOrange,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "DOWNLOADING TO STORAGE...",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }

        val dlTarget = pendingDownloadItem
        if (dlTarget != null) {
            val containerExt = if (dlTarget.kind == MediaKind.VIDEO) ".VCV" else ".ICV"
            val rawExt = dlTarget.fileName.substringAfterLast('.', "").uppercase().let {
                if (it == "VCV" || it == "ICV" || it.isBlank()) {
                    if (dlTarget.kind == MediaKind.VIDEO) "MP4" else "JPG"
                } else {
                    it
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.84f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbBorderBright)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(BbOrange))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "DOWNLOAD FORMAT",
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        CubicSquareIconButton(
                            icon = Icons.Default.Close,
                            active = false,
                            onClick = { pendingDownloadItem = null }
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BbBorder))
                    Text(
                        text = "Simpan \"${dlTarget.title}\" dalam format container $containerExt atau format asli ($rawExt)?",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        text = "Lokasi: ${AppStorageManager.getDefaultDownloadDir(context).absolutePath}",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CubicActionButton(
                            text = "FORMAT $containerExt",
                            primary = true,
                            onClick = {
                                val target = dlTarget
                                pendingDownloadItem = null
                                executeDownload(target, saveAsContainer = true)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        CubicActionButton(
                            text = "ASLI ($rawExt)",
                            primary = false,
                            onClick = {
                                val target = dlTarget
                                pendingDownloadItem = null
                                executeDownload(target, saveAsContainer = false)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (showCornDisclaimerAlert) {
            BackHandler {
                showCornDisclaimerAlert = false
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbOrange)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BbOrange)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = cornDisclaimerTitle,
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )
                    Text(
                        text = cornDisclaimerMessage,
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        text = "STEP 1 OF 2 // CONFIRM DISCLAIMER TO PROCEED TO CAPTCHA",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CubicActionButton(
                            text = "BACK",
                            icon = Icons.Default.ArrowBack,
                            primary = false,
                            onClick = {
                                showCornDisclaimerAlert = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                        CubicActionButton(
                            text = "NEXT",
                            icon = Icons.Default.NorthEast,
                            primary = true,
                            onClick = {
                                showCornDisclaimerAlert = false
                                cornCaptchaChallenge = requestNewCornCaptchaChallenge()
                                cornCaptchaInput = ""
                                cornCaptchaError = null
                                showCornCaptchaDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        val activeCaptcha = cornCaptchaChallenge
        if (showCornCaptchaDialog && activeCaptcha != null) {
            BackHandler {
                showCornCaptchaDialog = false
                showCornDisclaimerAlert = true
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbOrange)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BbOrange)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "CORN // CAPTCHA VERIFICATION",
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.weight(1f)
                        )
                        CubicSquareIconButton(
                            icon = Icons.Default.Close,
                            active = false,
                            onClick = {
                                showCornCaptchaDialog = false
                                cornCaptchaChallenge = null
                                cornCaptchaInput = ""
                                cornCaptchaError = null
                            }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )
                    Text(
                        text = "STEP 2 OF 2 // ENTER RANDOM GENERATED CAPTCHA CODE:",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .background(BbBlack)
                                .border(1.dp, BbBorderBright),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val lineCol1 = Color(0xFF2B2B2B)
                                val lineCol2 = Color(0xFFFF6B00).copy(alpha = 0.35f)
                                drawLine(
                                    lineCol1,
                                    Offset(0f, size.height * 0.28f),
                                    Offset(size.width, size.height * 0.72f),
                                    strokeWidth = 2f
                                )
                                drawLine(
                                    lineCol2,
                                    Offset(0f, size.height * 0.78f),
                                    Offset(size.width, size.height * 0.22f),
                                    strokeWidth = 2f
                                )
                                drawLine(
                                    lineCol1,
                                    Offset(size.width * 0.2f, 0f),
                                    Offset(size.width * 0.8f, size.height),
                                    strokeWidth = 1.5f
                                )
                            }
                            Text(
                                text = activeCaptcha.code.toCharArray().joinToString(" "),
                                color = BbWhite,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                letterSpacing = 4.sp
                            )
                        }
                        CubicSquareIconButton(
                            icon = Icons.Default.Refresh,
                            active = false,
                            onClick = {
                                cornCaptchaChallenge = requestNewCornCaptchaChallenge()
                                cornCaptchaInput = ""
                                cornCaptchaError = null
                            }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(BbSurfaceElevated)
                            .border(1.dp, if (cornCaptchaError != null) BbOrange else BbBorderBright)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (cornCaptchaInput.isEmpty()) {
                                Text(
                                    text = "TYPE 6-CHAR CODE...",
                                    color = BbMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                            BasicTextField(
                                value = cornCaptchaInput,
                                onValueChange = { raw ->
                                    cornCaptchaInput = raw.uppercase().take(8)
                                    cornCaptchaError = null
                                },
                                singleLine = true,
                                cursorBrush = SolidColor(BbOrange),
                                textStyle = TextStyle(
                                    color = BbWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    letterSpacing = 2.sp
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    val errText = cornCaptchaError
                    if (errText != null) {
                        Text(
                            text = errText,
                            color = BbOrange,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CubicActionButton(
                            text = "BACK",
                            icon = Icons.Default.ArrowBack,
                            primary = false,
                            onClick = {
                                showCornCaptchaDialog = false
                                showCornDisclaimerAlert = true
                            },
                            modifier = Modifier.weight(1f)
                        )
                        CubicActionButton(
                            text = "VERIFY",
                            icon = Icons.Default.NorthEast,
                            primary = true,
                            onClick = {
                                val verified = NativeCore.verifyCornCaptcha(
                                    activeCaptcha.challengeId,
                                    cornCaptchaInput
                                )
                                if (verified) {
                                    showCornCaptchaDialog = false
                                    cornCaptchaChallenge = null
                                    cornCaptchaInput = ""
                                    cornCaptchaError = null
                                    CORNUnlocked = true
                                    currentTab = NavDestination.CORN
                                    scope.launch {
                                        isRefreshing = true
                                        CORNDiscovery = AutoDiscoveryRepository.discoverHiddenCdn()
                                        isRefreshing = false
                                    }
                                } else {
                                    cornCaptchaError = "VERIFICATION FAILED // NEW CAPTCHA GENERATED"
                                    cornCaptchaChallenge = requestNewCornCaptchaChallenge()
                                    cornCaptchaInput = ""
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        val activeWelcome = welcomePopupPost
        if (activeWelcome != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 540.dp)
                        .background(BbSurface)
                        .border(1.dp, BbBorderBright)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BbSurfaceElevated)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BbOrange)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = activeWelcome.title.uppercase(),
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        CubicSquareIconButton(
                            icon = Icons.Default.Close,
                            active = false,
                            onClick = { welcomePopupPost = null }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        BBCodeView(bbcode = activeWelcome.bbcodeContent)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        CubicActionButton(
                            text = "CONTINUE",
                            icon = Icons.Default.NorthEast,
                            primary = true,
                            onClick = { welcomePopupPost = null }
                        )
                    }
                }
            }
        }

        val activeMedia = selectedMedia
        if (activeMedia != null) {
            BackHandler {
                closeViewer()
            }

            DisposableEffect(activeMedia.id) {
                onDispose {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    applyImmersiveSystemBars(activity)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BbBlack)
            ) {
                if (activeMedia.kind == MediaKind.VIDEO) {
                    CubicStreamingVideoPlayer(
                        item = activeMedia,
                        hwAcceleration = hwAcceleration,
                        onOrientationResolved = { isLandscape ->
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onToggleOrientation = {
                            val currentOrient = activity?.requestedOrientation
                            val nextLandscape = currentOrient != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            activity?.requestedOrientation = if (nextLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onDownload = { requestDownload(activeMedia) },
                        onBack = { closeViewer() }
                    )
                } else {
                    CubicStreamingImageViewer(
                        item = activeMedia,
                        onOrientationResolved = { isLandscape ->
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
                        onDownload = { requestDownload(activeMedia) },
                        onBack = { closeViewer() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    hwAcceleration: Boolean,
    downloadFormatPref: DownloadFormatPref,
    refreshTick: Int,
    onToggleHwAcceleration: (Boolean) -> Unit,
    onSelectDownloadFormatPref: (DownloadFormatPref) -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenHistoryItem: (HistoryRecord) -> Unit,
    onOpenDownloadedFile: (DownloadedFileEntry) -> Unit,
    onDeleteDownloadedFile: (DownloadedFileEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val cacheBytes = remember(refreshTick) { AppStorageManager.getCacheSizeBytes(context) }
    val historyList = remember(refreshTick) { AppStorageManager.getHistory(context) }
    val downloadedList = remember(refreshTick) { AppStorageManager.listDownloadedFiles(context) }
    val defaultDirPath = remember { AppStorageManager.getDefaultDownloadDir(context).absolutePath }
    val dateFmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        item(key = "settings_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BbSurfaceElevated)
                    .border(1.dp, BbBorderBright)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = BbOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SETTINGS // SYSTEM",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "AltHub v${BuildConfig.VERSION_NAME}",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                CubicActionButton(
                    text = "CLOSE",
                    primary = true,
                    onClick = onClose
                )
            }
        }

        item(key = "settings_github") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BbSurface)
                    .border(1.dp, BbBorder)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DEVELOPER // ALTERIZA",
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = BbOrange,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = "github.com/alteriza",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                CubicActionButton(
                    text = "GITHUB",
                    icon = Icons.Default.NorthEast,
                    primary = true,
                    onClick = {
                        runCatching { uriHandler.openUri(NativeCore.getDeveloperProfileUrl()) }
                    }
                )
            }
        }

        item(key = "settings_hw_accel") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BbSurface)
                    .border(1.dp, BbBorder)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HW ACCELERATION",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (hwAcceleration) "Hardware video decoding active" else "Software fallback active",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                CubicActionButton(
                    text = if (hwAcceleration) "ON" else "OFF",
                    primary = hwAcceleration,
                    onClick = { onToggleHwAcceleration(!hwAcceleration) }
                )
            }
        }

        item(key = "settings_cache") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BbSurface)
                    .border(1.dp, BbBorder)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CACHE STORAGE",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Used: ${formatBytes(cacheBytes)}",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                CubicActionButton(
                    text = "CLEAR CACHE",
                    primary = false,
                    onClick = onClearCache
                )
            }
        }

        item(key = "settings_download_pref") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BbSurface)
                    .border(1.dp, BbBorder)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "DOWNLOAD FORMAT & STORAGE",
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    text = "Default Folder: $defaultDirPath",
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DownloadFormatPref.entries) { pref ->
                        CubicFilterChip(
                            label = pref.label,
                            selected = downloadFormatPref == pref,
                            onClick = { onSelectDownloadFormatPref(pref) }
                        )
                    }
                }
            }
        }

        item(key = "settings_downloads_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BbSurfaceElevated)
                    .border(1.dp, BbBorder)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = BbOrange,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "DOWNLOADED FILES (${downloadedList.size})",
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        if (downloadedList.isEmpty()) {
            item(key = "settings_downloads_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbBorder)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Belum ada file yang di-download.",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            items(downloadedList, key = { "dl_${it.path}" }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbBorder)
                        .clickable { onOpenDownloadedFile(entry) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${if (entry.isVideo) "LOCAL VIDEO" else "LOCAL PHOTO"} • ${formatBytes(entry.sizeBytes)}",
                            color = BbMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    CubicSquareIconButton(
                        icon = Icons.Default.PlayArrow,
                        active = true,
                        onClick = { onOpenDownloadedFile(entry) }
                    )
                    Spacer(Modifier.width(6.dp))
                    CubicSquareIconButton(
                        icon = Icons.Default.Delete,
                        active = false,
                        onClick = { onDeleteDownloadedFile(entry) }
                    )
                }
            }
        }

        item(key = "settings_history_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BbSurfaceElevated)
                    .border(1.dp, BbBorder)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = BbOrange,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "WATCH & VIEW HISTORY (${historyList.size})",
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                if (historyList.isNotEmpty()) {
                    Text(
                        text = "CLEAR",
                        color = BbOrange,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable(onClick = onClearHistory)
                    )
                }
            }
        }

        if (historyList.isEmpty()) {
            item(key = "settings_history_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbBorder)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Riwayat tontonan & foto masih kosong.",
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            items(historyList, key = { "hist_${it.id}_${it.timestampMs}" }) { rec ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BbSurface)
                        .border(1.dp, BbBorder)
                        .clickable { onOpenHistoryItem(rec) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rec.title.uppercase(),
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${rec.kind.name} • ${rec.sourceType.name} • ${dateFmt.format(Date(rec.timestampMs))}",
                            color = BbMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.NorthEast,
                        contentDescription = null,
                        tint = BbWhite,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TechnicalGridBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 48.dp.toPx()
        val lineColor = Color(0xFF151515)
        val crossColor = Color(0xFF252525)
        var x = 0f
        while (x <= size.width) {
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        var gx = step
        while (gx < size.width) {
            var gy = step
            while (gy < size.height) {
                drawLine(crossColor, Offset(gx - 4f, gy), Offset(gx + 4f, gy), strokeWidth = 1.5f)
                drawLine(crossColor, Offset(gx, gy - 4f), Offset(gx, gy + 4f), strokeWidth = 1.5f)
                gy += step * 3
            }
            gx += step * 3
        }
    }
}

@Composable
private fun SocialTopBar(
    CORNUnlocked: Boolean,
    settingsOpen: Boolean,
    searchExpanded: Boolean,
    searchQuery: String,
    onLogoTap: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchChange: (String) -> Unit
) {
    val context = LocalContext.current
    val logoBitmap = remember(context) {
        val appCtx = context.applicationContext
        val resId = appCtx.resources.getIdentifier("ic_althub_logo", "drawable", appCtx.packageName)
        if (resId != 0) {
            runCatching { BitmapFactory.decodeResource(appCtx.resources, resId) }.getOrNull()
        } else {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BbBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(if (settingsOpen) BbOrange else BbWhite)
                    .border(1.dp, if (CORNUnlocked) BbOrange else BbBorderBright)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLogoTap
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (logoBitmap != null) {
                    Image(
                        bitmap = logoBitmap.asImageBitmap(),
                        contentDescription = "AltHub Logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(if (CORNUnlocked && !settingsOpen) BbOrange else BbBlack)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ALTHUB",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(BbSurfaceElevated)
                            .border(1.dp, BbBorder)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(BbOrange)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "LIVE",
                                color = BbWhite,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
            CubicSquareIconButton(
                icon = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                active = searchExpanded,
                onClick = onToggleSearch
            )
        }

        if (searchExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(BbSurface)
                    .border(1.dp, BbBorderBright)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = BbOrange,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "SEARCH VIDEOS, PHOTOS, INFO...",
                            color = BbMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        singleLine = true,
                        cursorBrush = SolidColor(BbOrange),
                        textStyle = TextStyle(
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = BbWhite,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onSearchChange("") }
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturedMediaSpotlight(
    item: RemoteMediaItem,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }

    LaunchedEffect(item.id) {
        if (thumb == null) {
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .background(BbBlack)
            .border(1.dp, BbBorder)
            .clickable(onClick = onOpen)
    ) {
        val b = thumb
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.55f),
                            BbBackground
                        )
                    )
                )
        )
        val tags = item.videoTags
        if (item.kind == MediaKind.VIDEO && !tags.isNullOrBlank()) {
            VideoTagBadge(
                tags = tags,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(BbOrange)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (item.kind == MediaKind.VIDEO) "FEATURED VIDEO" else "FEATURED PHOTO",
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
            Text(
                text = item.title.uppercase(),
                color = BbWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CubicActionButton(
                    text = if (item.kind == MediaKind.VIDEO) "WATCH NOW" else "OPEN PHOTO",
                    icon = Icons.Default.NorthEast,
                    primary = true,
                    onClick = onOpen
                )
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .background(BbSurface.copy(alpha = 0.85f))
                        .border(1.dp, BbBorderBright)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.sourceType.name,
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoTagBadge(
    tags: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(BbBlack.copy(alpha = 0.88f))
            .border(1.dp, BbBorderBright)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = tags,
            color = BbOrange,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CubicFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .background(if (selected) BbWhite else BbSurface)
            .border(1.dp, if (selected) BbWhite else BbBorder)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(if (selected) BbOrange else BbBorderBright)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = if (selected) BbBlack else BbMuted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun SocialStreamPostCard(
    item: RemoteMediaItem,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var thumbLoading by remember(item.id) { mutableStateOf(thumb == null) }

    LaunchedEffect(item.id) {
        if (thumb == null) {
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
            thumbLoading = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BbSurface)
            .border(1.dp, BbBorder)
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(BbSurfaceElevated)
                    .border(1.dp, BbBorderBright),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AH",
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ALTERIZA",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = BbOrange,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = "${item.sourceType.name} • ${item.folderPath}",
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .background(BbSurfaceElevated)
                    .border(1.dp, BbBorder)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(if (item.kind == MediaKind.VIDEO) BbOrange else BbWhite)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (item.kind == MediaKind.VIDEO) "VIDEO" else "PHOTO",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BbBorder)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.kind == MediaKind.VIDEO) 16f / 9f else 4f / 3f)
                .background(BbBlack),
            contentAlignment = Alignment.Center
        ) {
            val b = thumb
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (thumbLoading) {
                CircularProgressIndicator(
                    color = BbMuted,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(
                    text = if (item.kind == MediaKind.VIDEO) "[ VIDEO ]" else "[ PHOTO ]",
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            if (item.kind == MediaKind.VIDEO) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(BbWhite)
                        .border(1.dp, BbBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = BbBlack,
                        modifier = Modifier.size(28.dp)
                    )
                }

                val tags = item.videoTags
                if (!tags.isNullOrBlank()) {
                    VideoTagBadge(
                        tags = tags,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BbBorder)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.uppercase(),
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.sizeBytes > 0L) {
                        "${item.fileName} • ${formatBytes(item.sizeBytes)}"
                    } else {
                        item.fileName
                    },
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            CubicSquareIconButton(
                icon = Icons.Default.Download,
                active = false,
                onClick = onDownload
            )
            Spacer(Modifier.width(8.dp))
            CubicActionButton(
                text = if (item.kind == MediaKind.VIDEO) "PLAY" else "VIEW",
                icon = Icons.Default.NorthEast,
                primary = true,
                onClick = onOpen
            )
        }
    }
}

@Composable
private fun VideoStreamCard(
    item: RemoteMediaItem,
    onWatch: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var thumbLoading by remember(item.id) { mutableStateOf(thumb == null) }

    LaunchedEffect(item.id) {
        if (thumb == null) {
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
            thumbLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BbSurface)
            .border(1.dp, BbBorder)
            .clickable(onClick = onWatch)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(BbBlack),
            contentAlignment = Alignment.Center
        ) {
            val b = thumb
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (thumbLoading) {
                CircularProgressIndicator(
                    color = BbMuted,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(BbWhite)
                    .border(1.dp, BbBlack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = BbBlack,
                    modifier = Modifier.size(28.dp)
                )
            }

            val tags = item.videoTags
            if (!tags.isNullOrBlank()) {
                VideoTagBadge(
                    tags = tags,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BbBorder)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.uppercase(),
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.sourceType.name} • ${item.fileName}",
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            CubicSquareIconButton(
                icon = Icons.Default.Download,
                active = false,
                onClick = onDownload
            )
            Spacer(Modifier.width(8.dp))
            CubicActionButton(
                text = "WATCH",
                icon = Icons.Default.NorthEast,
                primary = true,
                onClick = onWatch
            )
        }
    }
}

@Composable
private fun SocialAnnouncementCard(
    post: InfoPost,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BbSurface)
            .border(1.dp, BbBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BbSurfaceElevated)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(BbWhite),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "!",
                    color = BbBlack,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ALTERIZA // INFO",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = BbOrange,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = post.fileName,
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
            Box(
                modifier = Modifier
                    .background(BbBlack)
                    .border(1.dp, BbBorderBright)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "INFO",
                    color = BbOrange,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BbBorder)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = post.title.uppercase(),
                color = BbWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 0.6.sp
            )
            BBCodeView(bbcode = post.bbcodeContent)
        }
    }
}

@Composable
private fun CubicBottomNavBar(
    current: NavDestination,
    settingsOpen: Boolean,
    CORNUnlocked: Boolean,
    videoCount: Int,
    photoCount: Int,
    infoCount: Int,
    CORNCount: Int,
    onSelect: (NavDestination) -> Unit
) {
    val destinations = remember(CORNUnlocked) {
        if (CORNUnlocked) {
            NavDestination.entries
        } else {
            NavDestination.entries.filter { it != NavDestination.CORN }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BbBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        destinations.forEach { dest ->
            val selected = !settingsOpen && current == dest
            val badge = when (dest) {
                NavDestination.FEED -> ""
                NavDestination.VIDEOS -> " [$videoCount]"
                NavDestination.PHOTOS -> " [$photoCount]"
                NavDestination.INFO -> " [$infoCount]"
                NavDestination.CORN -> " [$CORNCount]"
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(if (selected) BbWhite else BbSurface)
                    .border(
                        1.dp,
                        when {
                            selected -> BbWhite
                            dest == NavDestination.CORN -> BbOrange
                            else -> BbBorder
                        }
                    )
                    .clickable { onSelect(dest) },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = dest.label,
                            tint = when {
                                selected -> BbBlack
                                dest == NavDestination.CORN -> BbOrange
                                else -> BbMuted
                            },
                            modifier = Modifier.size(15.dp)
                        )
                        if (selected) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(BbOrange)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${dest.label}$badge",
                        color = when {
                            selected -> BbBlack
                            dest == NavDestination.CORN -> BbOrange
                            else -> BbMuted
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CubicSquareIconButton(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(if (active) BbWhite else BbSurfaceElevated)
            .border(1.dp, if (active) BbWhite else BbBorderBright)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) BbBlack else BbWhite,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun CubicActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "btnScale")

    val bg = if (primary) BbWhite else BbSurfaceElevated
    val fg = if (primary) BbBlack else BbWhite
    val border = if (primary) BbWhite else BbBorderBright

    Box(
        modifier = modifier
            .scale(scale)
            .height(36.dp)
            .background(bg)
            .border(1.dp, border)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = text,
                color = fg,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyFeedBox(
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BbSurface)
                .border(1.dp, BbBorder)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(10.dp).background(BbOrange))
            Text(
                text = title,
                color = BbWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.sp
            )
            Text(
                text = subtitle,
                color = BbMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun CubicStreamingVideoPlayer(
    item: RemoteMediaItem,
    hwAcceleration: Boolean,
    onOrientationResolved: (Boolean) -> Unit,
    onToggleOrientation: () -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var retryTick by remember { mutableIntStateOf(0) }
    var playableUri by remember(item.id, item.localPath, retryTick) { mutableStateOf<Uri?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }
    var ready by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf("") }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var isSeeking by remember { mutableStateOf(false) }

    var isMuted by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(false) }
    val speeds = remember { listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f) }
    var speedIndex by remember { mutableIntStateOf(1) }
    var resizeMode by remember { mutableStateOf(VideoResizeMode.FIT) }

    var gestureBadge by remember { mutableStateOf<String?>(null) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }

    val exoPlayer = remember(item.id, hwAcceleration, retryTick) {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(
                if (hwAcceleration) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
        }
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2500, 15000, 1000, 2000)
            .build()
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                        ready = true
                        hasError = false
                        durationMs = exoPlayer.duration.coerceAtLeast(1L).toInt()
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        if (!isLooping) {
                            isPlaying = false
                            positionMs = durationMs
                            controlsVisible = true
                        }
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) interactionTick++
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onOrientationResolved(videoSize.width > videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                hasError = true
                errorDetail = "PLAYER ERROR (${error.errorCodeName})"
                controlsVisible = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(item.id, item.localPath, retryTick) {
        ready = false
        isBuffering = true
        hasError = false
        errorDetail = ""
        playableUri = null
        runCatching {
            LocalStreamServer.preparePlayableUri(context, item)
        }.onSuccess { uri ->
            playableUri = uri
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            exoPlayer.volume = if (isMuted) 0f else 1f
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }.onFailure { err ->
            isBuffering = false
            hasError = true
            errorDetail = err.message ?: "Failed to prepare stream"
            controlsVisible = true
        }
    }

    LaunchedEffect(controlsVisible, interactionTick, isPlaying, isSeeking) {
        if (controlsVisible && isPlaying && !isSeeking) {
            delay(3200L)
            controlsVisible = false
        }
    }

    LaunchedEffect(gestureBadge) {
        if (gestureBadge != null) {
            delay(900L)
            gestureBadge = null
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (ready && !isSeeking) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L).toInt()
                val dur = exoPlayer.duration
                if (dur > 0L) durationMs = dur.toInt()
            }
            delay(250L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBlack)
            .pointerInput(controlsLocked) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) interactionTick++
                    },
                    onDoubleTap = { offset ->
                        if (controlsLocked) return@detectTapGestures
                        val half = size.width / 2f
                        if (offset.x < half) {
                            val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(next)
                            positionMs = next.toInt()
                            gestureBadge = "-10s"
                        } else {
                            val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(durationMs.toLong())
                            exoPlayer.seekTo(next)
                            positionMs = next.toInt()
                            gestureBadge = "+10s"
                        }
                        interactionTick++
                    }
                )
            }
            .pointerInput(controlsLocked) {
                detectDragGestures { change, dragAmount ->
                    if (controlsLocked) return@detectDragGestures
                    val deltaY = -dragAmount.y / size.height.coerceAtLeast(1)
                    if (change.position.x < size.width / 2f) {
                        brightnessLevel = (brightnessLevel + deltaY).coerceIn(0.05f, 1f)
                        activity?.window?.let { win ->
                            val lp = win.attributes
                            lp.screenBrightness = brightnessLevel
                            win.attributes = lp
                        }
                        gestureBadge = "BRIGHTNESS ${(brightnessLevel * 100).toInt()}%"
                    } else {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val step = if (deltaY > 0.02f) 1 else if (deltaY < -0.02f) -1 else 0
                        if (step != 0) {
                            val nextVol = (curVol + step).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                            gestureBadge = "VOLUME ${(nextVol * 100) / maxVol}%"
                        }
                    }
                }
            }
    ) {
        if (playableUri != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = false
                        keepScreenOn = true
                        player = exoPlayer
                    }
                },
                update = { pv ->
                    if (pv.player !== exoPlayer) {
                        pv.player = exoPlayer
                    }
                    pv.resizeMode = when (resizeMode) {
                        VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        VideoResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if ((!ready || isBuffering) && !hasError) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = BbOrange,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        text = if (item.localPath != null) "PREPARING LOCAL MEDIA..." else "BUFFERING STREAM...",
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }

        val badge = gestureBadge
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(BbBlack.copy(alpha = 0.85f))
                    .border(1.dp, BbOrange)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = badge,
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        if (hasError) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(BbSurface)
                    .border(1.dp, BbBorder)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "STREAM UNAVAILABLE",
                    color = BbOrange,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (errorDetail.isNotBlank()) {
                    Text(
                        text = errorDetail,
                        color = BbMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CubicActionButton(
                        text = "RETRY",
                        primary = true,
                        onClick = { retryTick++ }
                    )
                    CubicActionButton(
                        text = "BACK",
                        primary = false,
                        onClick = onBack
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                if (controlsLocked) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(14.dp)
                    ) {
                        CubicActionButton(
                            text = "UNLOCK",
                            icon = Icons.Default.LockOpen,
                            primary = true,
                            onClick = {
                                controlsLocked = false
                                interactionTick++
                            }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(12.dp)
                            .background(BbBlack.copy(alpha = 0.85f))
                            .border(1.dp, BbBorder)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CubicActionButton(
                            text = "BACK",
                            icon = Icons.Default.ArrowBack,
                            primary = true,
                            onClick = onBack
                        )
                        Text(
                            text = item.title.uppercase(),
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        CubicSquareIconButton(
                            icon = Icons.Default.ScreenRotation,
                            active = false,
                            onClick = {
                                onToggleOrientation()
                                interactionTick++
                            }
                        )
                        CubicSquareIconButton(
                            icon = Icons.Default.AspectRatio,
                            active = resizeMode != VideoResizeMode.FIT,
                            onClick = {
                                val modes = VideoResizeMode.entries
                                resizeMode = modes[(resizeMode.ordinal + 1) % modes.size]
                                gestureBadge = "ASPECT: ${resizeMode.label}"
                                interactionTick++
                            }
                        )
                        CubicSquareIconButton(
                            icon = Icons.Default.Lock,
                            active = false,
                            onClick = {
                                controlsLocked = true
                            }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp)
                            .background(BbBlack.copy(alpha = 0.88f))
                            .border(1.dp, BbBorder)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CubicSquareIconButton(
                                icon = Icons.Default.Replay10,
                                active = false,
                                onClick = {
                                    val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(next)
                                    positionMs = next.toInt()
                                    interactionTick++
                                }
                            )
                            CubicActionButton(
                                text = if (isPlaying) "PAUSE" else "PLAY",
                                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                primary = true,
                                onClick = {
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        exoPlayer.play()
                                    }
                                    interactionTick++
                                }
                            )
                            CubicSquareIconButton(
                                icon = Icons.Default.Forward10,
                                active = false,
                                onClick = {
                                    val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(durationMs.toLong())
                                    exoPlayer.seekTo(next)
                                    positionMs = next.toInt()
                                    interactionTick++
                                }
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = formatDuration(positionMs),
                                color = BbWhite,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Slider(
                                value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                                onValueChange = { v ->
                                    isSeeking = true
                                    positionMs = v.toInt()
                                    exoPlayer.seekTo(positionMs.toLong())
                                },
                                onValueChangeFinished = {
                                    isSeeking = false
                                    interactionTick++
                                },
                                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = BbOrange,
                                    activeTrackColor = BbWhite,
                                    inactiveTrackColor = BbBorderBright
                                )
                            )
                            Text(
                                text = formatDuration(durationMs),
                                color = BbWhite,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CubicSquareIconButton(
                                icon = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                active = isMuted,
                                onClick = {
                                    isMuted = !isMuted
                                    exoPlayer.volume = if (isMuted) 0f else 1f
                                    interactionTick++
                                }
                            )
                            CubicActionButton(
                                text = "${speeds[speedIndex]}x",
                                icon = Icons.Default.Speed,
                                primary = speedIndex != 1,
                                onClick = {
                                    speedIndex = (speedIndex + 1) % speeds.size
                                    exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIndex])
                                    interactionTick++
                                }
                            )
                            CubicSquareIconButton(
                                icon = Icons.Default.Loop,
                                active = isLooping,
                                onClick = {
                                    isLooping = !isLooping
                                    exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                                    gestureBadge = if (isLooping) "LOOP ON" else "LOOP OFF"
                                    interactionTick++
                                }
                            )
                            Spacer(Modifier.weight(1f))
                            if (item.localPath == null) {
                                CubicActionButton(
                                    text = "SAVE",
                                    icon = Icons.Default.Download,
                                    primary = false,
                                    onClick = {
                                        interactionTick++
                                        onDownload()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CubicStreamingImageViewer(
    item: RemoteMediaItem,
    onOrientationResolved: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit
) {
    var bitmap by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var isFullLoaded by remember(item.id) { mutableStateOf(false) }
    var loadFailed by remember(item.id) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(item.id, item.localPath) {
        val fullBmp = AutoDiscoveryRepository.streamFullImageBitmap(item)
        if (fullBmp != null) {
            bitmap = fullBmp
            isFullLoaded = true
            onOrientationResolved(fullBmp.width > fullBmp.height)
        } else if (bitmap == null) {
            loadFailed = true
        }
    }

    LaunchedEffect(controlsVisible, interactionTick) {
        if (controlsVisible) {
            delay(3000L)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBlack)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) interactionTick++
                    },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                        controlsVisible = true
                        interactionTick++
                    }
                )
            }
    ) {
        val activeBmp = bitmap
        if (activeBmp != null) {
            Image(
                bitmap = activeBmp.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
        }

        if (!isFullLoaded && !loadFailed) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(BbBlack.copy(alpha = 0.65f))
                    .padding(10.dp)
            ) {
                CircularProgressIndicator(
                    color = BbOrange,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        if (loadFailed) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(BbSurface)
                    .border(1.dp, BbBorder)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "FAILED TO STREAM PHOTO",
                    color = BbOrange,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                CubicActionButton(
                    text = "BACK",
                    primary = true,
                    onClick = onBack
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .background(BbBlack.copy(alpha = 0.85f))
                    .border(1.dp, BbBorder)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CubicActionButton(
                    text = "BACK",
                    icon = Icons.Default.ArrowBack,
                    primary = true,
                    onClick = onBack
                )
                Text(
                    text = item.title.uppercase(),
                    color = BbWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.localPath == null) {
                    CubicActionButton(
                        text = "SAVE",
                        icon = Icons.Default.Download,
                        primary = false,
                        onClick = {
                            interactionTick++
                            onDownload()
                        }
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.2f MB".format(mb)
        kb >= 1.0 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}