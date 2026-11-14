package com.althub.alteriza

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
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
import com.althub.alteriza.ui.theme.BbBackground
import com.althub.alteriza.ui.theme.BbBlack
import com.althub.alteriza.ui.theme.BbBorder
import com.althub.alteriza.ui.theme.BbBorderBright
import com.althub.alteriza.ui.theme.BbMuted
import com.althub.alteriza.ui.theme.BbOrange
import com.althub.alteriza.ui.theme.BbSurface
import com.althub.alteriza.ui.theme.BbSurfaceElevated
import com.althub.alteriza.ui.theme.BbWhite
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CORN_ALERT_TITLE = "CORN // HIDDEN SECTION"
private const val CORN_ALERT_MESSAGE = "WARNING! ADULT ONLY — SOLVE CAPTCHA TO ENTER"
private const val CORN_TAP_TARGET = 10
private const val CORN_TAP_MAX_DELAY_MS = 450L

private fun generateRandomCaptcha(length: Int = 6): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rng = SecureRandom()
    return buildString(length) {
        repeat(length) {
            append(chars[rng.nextInt(chars.length)])
        }
    }
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AltHubApp() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var currentTab by remember { mutableStateOf(NavDestination.FEED) }
    var settingsOpen by remember { mutableStateOf(false) }
    var activeFeedChip by remember { mutableStateOf(FeedChip.ALL) }
    var activeCORNChip by remember { mutableStateOf(CORNChip.ALL) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var initialLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    var CORNUnlocked by remember { mutableStateOf(false) }
    var showCORNAlert by remember { mutableStateOf(false) }
    var cornAlertIsExit by remember { mutableStateOf(false) }
    var captchaCode by remember { mutableStateOf(generateRandomCaptcha()) }
    var captchaInput by remember { mutableStateOf("") }
    var captchaError by remember { mutableStateOf(false) }
    var logoTapCount by remember { mutableIntStateOf(0) }
    var lastLogoTapMs by remember { mutableLongStateOf(0L) }

    var welcomePopupPost by remember { mutableStateOf<InfoPost?>(null) }
    var selectedPost by remember { mutableStateOf<InfoPost?>(null) }
    var activeSyncJob by remember { mutableStateOf<Job?>(null) }

    val feedListState = rememberLazyListState()
    val videosListState = rememberLazyListState()
    val photosListState = rememberLazyListState()
    val infoListState = rememberLazyListState()
    val cornListState = rememberLazyListState()

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
        activeSyncJob?.cancel()
        activeSyncJob = null
        isRefreshing = false
        AppStorageManager.recordHistory(context, item)
        storageRefreshTick++
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
        if (selectedMedia != null) return
        if (fromPullRefresh) {
            AutoDiscoveryRepository.clearFailedThumbCache()
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
                showCORNAlert = false
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

    LaunchedEffect(logoTapCount, lastLogoTapMs) {
        if (logoTapCount in 1 until CORN_TAP_TARGET) {
            delay(CORN_TAP_MAX_DELAY_MS + 20L)
            logoTapCount = 0
        }
    }

    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activeSyncJob = scope.launch { syncRepository(fromPullRefresh = false) }
    }

    val allVideos = remember(discovery.mediaItems, searchQuery) {
        discovery.mediaItems.filter { item ->
            item.kind == MediaKind.VIDEO && (
                searchQuery.isBlank() ||
                    item.title.contains(searchQuery, true) ||
                    item.fileName.contains(searchQuery, true)
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
                item.fileName.contains(searchQuery, true)
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
                item.fileName.contains(searchQuery, true)
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

    val activeMedia = selectedMedia
    val isViewerActive = activeMedia != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBackground)
    ) {
        if (!isViewerActive) {
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
                    logoTapCount = if (now - lastLogoTapMs <= CORN_TAP_MAX_DELAY_MS) logoTapCount + 1 else 1
                    lastLogoTapMs = now
                    if (logoTapCount >= CORN_TAP_TARGET) {
                        logoTapCount = 0
                        settingsOpen = false
                        cornAlertIsExit = CORNUnlocked
                        captchaCode = generateRandomCaptcha()
                        captchaInput = ""
                        captchaError = false
                        showCORNAlert = true
                    } else if (logoTapCount == 1) {
                        settingsOpen = !settingsOpen
                    } else {
                        settingsOpen = false
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
                                isContainer = rec.isContainer
                            )
                        )
                    },
                    onDeleteDownloadedFile = { entry ->
                        runCatching { File(entry.path).delete() }
                        AppStorageManager.removeDownloadedItemRecord(context, entry.path)
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
                        activeSyncJob?.cancel()
                        activeSyncJob = scope.launch { syncRepository(fromPullRefresh = true) }
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
                                state = feedListState,
                                contentPadding = PaddingValues(bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (featuredItem != null && searchQuery.isBlank() && activeFeedChip == FeedChip.ALL) {
                                    val heroKey = "hero_${featuredItem.id}"
                                    item(key = heroKey) {
                                        val isHeroVisible by remember(heroKey) {
                                            derivedStateOf {
                                                feedListState.layoutInfo.visibleItemsInfo.any { it.key == heroKey }
                                            }
                                        }
                                        FeaturedMediaSpotlight(
                                            item = featuredItem,
                                            isVisible = isHeroVisible,
                                            isViewerActive = isViewerActive,
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
                                                val itemKey = "m_${entry.item.id}"
                                                val isCardVisible by remember(itemKey) {
                                                    derivedStateOf {
                                                        feedListState.layoutInfo.visibleItemsInfo.any { it.key == itemKey }
                                                    }
                                                }
                                                SocialStreamPostCard(
                                                    item = entry.item,
                                                    isVisible = isCardVisible,
                                                    isViewerActive = isViewerActive,
                                                    onOpen = { openMedia(entry.item) },
                                                    onDownload = { requestDownload(entry.item) },
                                                    modifier = Modifier.padding(horizontal = 14.dp)
                                                )
                                            }
                                            is TimelineEntry.PostEntry -> {
                                                SocialAnnouncementCard(
                                                    post = entry.post,
                                                    onOpenFull = { selectedPost = entry.post },
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
                                state = videosListState,
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
                                    itemsIndexed(allVideos, key = { _, item -> item.id }) { idx, video ->
                                        val isCardVisible by remember(video.id) {
                                            derivedStateOf {
                                                videosListState.layoutInfo.visibleItemsInfo.any { it.key == video.id }
                                            }
                                        }
                                        VideoStreamCard(
                                            indexNumber = allVideos.size - idx,
                                            item = video,
                                            isVisible = isCardVisible,
                                            isViewerActive = isViewerActive,
                                            onWatch = { openMedia(video) },
                                            onDownload = { requestDownload(video) }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.PHOTOS -> {
                            LazyColumn(
                                state = photosListState,
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
                                        val isCardVisible by remember(photo.id) {
                                            derivedStateOf {
                                                photosListState.layoutInfo.visibleItemsInfo.any { it.key == photo.id }
                                            }
                                        }
                                        SocialStreamPostCard(
                                            item = photo,
                                            isVisible = isCardVisible,
                                            isViewerActive = isViewerActive,
                                            onOpen = { openMedia(photo) },
                                            onDownload = { requestDownload(photo) }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.INFO -> {
                            LazyColumn(
                                state = infoListState,
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
                                        SocialAnnouncementCard(
                                            post = post,
                                            onOpenFull = { selectedPost = post }
                                        )
                                    }
                                }
                            }
                        }

                        currentTab == NavDestination.CORN -> {
                            LazyColumn(
                                state = cornListState,
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
                                            text = "CORN // PRIVATE STREAM",
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
                                        val itemKey = "CORN_${media.id}"
                                        val isCardVisible by remember(itemKey) {
                                            derivedStateOf {
                                                cornListState.layoutInfo.visibleItemsInfo.any { it.key == itemKey }
                                            }
                                        }
                                        SocialStreamPostCard(
                                            item = media,
                                            isVisible = isCardVisible,
                                            isViewerActive = isViewerActive,
                                            onOpen = { openMedia(media) },
                                            onDownload = { requestDownload(media) }
                                        )
                                    }
                                    items(CORNDiscovery.infoPosts, key = { "CORN_post_${it.id}" }) { post ->
                                        SocialAnnouncementCard(
                                            post = post,
                                            onOpenFull = { selectedPost = post }
                                        )
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

        if (showCORNAlert) {
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
                            text = CORN_ALERT_TITLE,
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BbBorder)
                    )
                    Text(
                        text = if (cornAlertIsExit) {
                            "HIDDEN SECTION IS ACTIVE. EXIT AND LOCK STREAM?"
                        } else {
                            CORN_ALERT_MESSAGE
                        },
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    if (!cornAlertIsExit) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CaptchaCanvas(
                                code = captchaCode,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .background(BbBlack)
                                    .border(1.dp, BbBorderBright)
                            )
                            CubicSquareIconButton(
                                icon = Icons.Default.Refresh,
                                active = false,
                                onClick = {
                                    captchaCode = generateRandomCaptcha()
                                    captchaInput = ""
                                    captchaError = false
                                }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .background(BbBlack)
                                .border(1.dp, if (captchaError) BbOrange else BbBorderBright)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (captchaInput.isEmpty()) {
                                Text(
                                    text = "ENTER CAPTCHA CODE...",
                                    color = BbMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            BasicTextField(
                                value = captchaInput,
                                onValueChange = {
                                    captchaInput = it.uppercase().take(8)
                                    captchaError = false
                                },
                                singleLine = true,
                                cursorBrush = SolidColor(BbOrange),
                                textStyle = TextStyle(
                                    color = BbWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    letterSpacing = 2.sp
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (captchaError) {
                            Text(
                                text = "INVALID CAPTCHA — NEW CODE GENERATED",
                                color = BbOrange,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                    ) {
                        if (cornAlertIsExit) {
                            CubicActionButton(
                                text = "CANCEL",
                                primary = false,
                                onClick = { showCORNAlert = false }
                            )
                            CubicActionButton(
                                text = "EXIT",
                                icon = Icons.Default.Close,
                                primary = true,
                                onClick = {
                                    showCORNAlert = false
                                    CORNUnlocked = false
                                    CORNDiscovery = DiscoveryResult(emptyList(), emptyList(), emptyList())
                                    if (currentTab == NavDestination.CORN) {
                                        currentTab = NavDestination.FEED
                                    }
                                }
                            )
                        } else {
                            CubicActionButton(
                                text = "EXIT",
                                icon = Icons.Default.Close,
                                primary = false,
                                onClick = {
                                    showCORNAlert = false
                                    captchaInput = ""
                                    captchaError = false
                                }
                            )
                            CubicActionButton(
                                text = "ENTER",
                                icon = Icons.Default.NorthEast,
                                primary = true,
                                onClick = {
                                    if (captchaInput.trim().equals(captchaCode, ignoreCase = true)) {
                                        showCORNAlert = false
                                        captchaInput = ""
                                        captchaError = false
                                        CORNUnlocked = true
                                        currentTab = NavDestination.CORN
                                        activeSyncJob?.cancel()
                                        activeSyncJob = scope.launch {
                                            isRefreshing = true
                                            CORNDiscovery = AutoDiscoveryRepository.discoverHiddenCdn()
                                            isRefreshing = false
                                        }
                                    } else {
                                        captchaError = true
                                        captchaCode = generateRandomCaptcha()
                                        captchaInput = ""
                                    }
                                }
                            )
                        }
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

        val activeFullPost = selectedPost
        if (activeFullPost != null) {
            BackHandler {
                selectedPost = null
            }
            FullPostReaderScreen(
                post = activeFullPost,
                onBack = { selectedPost = null }
            )
        }
        } else if (activeMedia != null) {
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
                        storageRefreshTick = storageRefreshTick,
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
                        storageRefreshTick = storageRefreshTick,
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

                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(top = 66.dp)
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
                        text = if (hwAcceleration) "Hardware video decoding active" else "Software layer fallback active",
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
                            text = "${if (entry.isVideo) "VIDEO" else "PHOTO"} • ${formatBytes(entry.sizeBytes)}",
                            color = BbMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
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
                    .size(28.dp)
                    .background(if (settingsOpen) BbOrange else BbWhite)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLogoTap
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(if (CORNUnlocked && !settingsOpen) BbOrange else BbBlack)
                )
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
    isVisible: Boolean,
    isViewerActive: Boolean,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }

    LaunchedEffect(item.id, isVisible, isViewerActive) {
        if (thumb == null && isVisible && !isViewerActive) {
            delay(180L)
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
    isVisible: Boolean,
    isViewerActive: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var thumbLoading by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, isVisible, isViewerActive) {
        if (thumb == null && isVisible && !isViewerActive) {
            thumbLoading = true
            delay(180L)
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
            thumbLoading = false
        } else if (!isVisible) {
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
    indexNumber: Int,
    item: RemoteMediaItem,
    isVisible: Boolean,
    isViewerActive: Boolean,
    onWatch: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var thumbLoading by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, isVisible, isViewerActive) {
        if (thumb == null && isVisible && !isViewerActive) {
            thumbLoading = true
            delay(180L)
            thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
            thumbLoading = false
        } else if (!isVisible) {
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
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(BbBlack.copy(alpha = 0.85f))
                    .border(1.dp, BbBorderBright)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(BbOrange))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "VIDEO #%02d".format(indexNumber),
                        color = BbWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
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
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BbSurface)
            .border(1.dp, BbBorder)
            .clickable(onClick = onOpenFull)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
            ) {
                BBCodeView(bbcode = post.bbcodeContent)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorder)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                CubicActionButton(
                    text = "FULL VIEW",
                    icon = Icons.Default.NorthEast,
                    primary = true,
                    onClick = onOpenFull
                )
            }
        }
    }
}

@Composable
private fun FullPostReaderScreen(
    post: InfoPost,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BbSurfaceElevated)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CubicActionButton(
                text = "BACK",
                icon = Icons.Default.ArrowBack,
                primary = true,
                onClick = onBack
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ALTERIZA // FULL POST",
                    color = BbOrange,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Text(
                    text = post.fileName,
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = post.title.uppercase(),
                color = BbWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 0.8.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorderBright)
            )
            BBCodeView(bbcode = post.bbcodeContent)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CaptchaCanvas(
    code: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val rng = SecureRandom()
        val w = size.width
        val h = size.height
        repeat(7) {
            drawLine(
                color = if (it % 2 == 0) BbOrange.copy(alpha = 0.45f) else BbBorderBright,
                start = Offset(rng.nextFloat() * w, rng.nextFloat() * h),
                end = Offset(rng.nextFloat() * w, rng.nextFloat() * h),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
            )
        }
        val stepX = w / (code.length + 1)
        code.forEachIndexed { idx, ch ->
            val cx = stepX * (idx + 1)
            val cy = h * 0.5f + ((idx % 2) * 2 - 1) * 5f
            val angle = ((idx * 17 + code.hashCode()) % 28 - 14).toFloat()
            rotate(degrees = angle, pivot = Offset(cx, cy)) {
                translate(left = cx - 8f, top = cy - 11f) {
                    drawCaptchaGlyph(ch, if (idx % 2 == 0) BbWhite else BbOrange)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCaptchaGlyph(ch: Char, color: Color) {
    val w = 16f
    val h = 22f
    val sw = 3.2f
    val cap = StrokeCap.Square
    val segs: List<Pair<Offset, Offset>> = when (ch) {
        'A' -> listOf(Offset(0f, h) to Offset(w / 2, 0f), Offset(w / 2, 0f) to Offset(w, h), Offset(3f, h * 0.6f) to Offset(w - 3f, h * 0.6f))
        'B' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(0f, 0f) to Offset(w * 0.8f, 0f), Offset(w * 0.8f, 0f) to Offset(w * 0.8f, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, h / 2) to Offset(w, h), Offset(0f, h) to Offset(w, h))
        'C' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h))
        'D' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(0f, 0f) to Offset(w * 0.75f, 0f), Offset(w * 0.75f, 0f) to Offset(w, h * 0.3f), Offset(w, h * 0.3f) to Offset(w, h * 0.7f), Offset(w, h * 0.7f) to Offset(w * 0.75f, h), Offset(0f, h) to Offset(w * 0.75f, h))
        'E' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h / 2) to Offset(w * 0.8f, h / 2), Offset(0f, h) to Offset(w, h))
        'F' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h / 2) to Offset(w * 0.8f, h / 2))
        'G' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h), Offset(w, h) to Offset(w, h / 2), Offset(w / 2, h / 2) to Offset(w, h / 2))
        'H' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(w, 0f) to Offset(w, h), Offset(0f, h / 2) to Offset(w, h / 2))
        'J' -> listOf(Offset(w, 0f) to Offset(w, h), Offset(0f, h) to Offset(w, h), Offset(0f, h * 0.65f) to Offset(0f, h))
        'K' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(w, 0f) to Offset(0f, h / 2), Offset(0f, h / 2) to Offset(w, h))
        'L' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h))
        'M' -> listOf(Offset(0f, h) to Offset(0f, 0f), Offset(0f, 0f) to Offset(w / 2, h * 0.55f), Offset(w / 2, h * 0.55f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h))
        'N' -> listOf(Offset(0f, h) to Offset(0f, 0f), Offset(0f, 0f) to Offset(w, h), Offset(w, h) to Offset(w, 0f))
        'P' -> listOf(Offset(0f, h) to Offset(0f, 0f), Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h / 2), Offset(0f, h / 2) to Offset(w, h / 2))
        'Q' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h * 0.85f), Offset(w * 0.85f, h * 0.85f) to Offset(0f, h * 0.85f), Offset(0f, h * 0.85f) to Offset(0f, 0f), Offset(w * 0.55f, h * 0.6f) to Offset(w, h))
        'R' -> listOf(Offset(0f, h) to Offset(0f, 0f), Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w * 0.45f, h / 2) to Offset(w, h))
        'S' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, h / 2) to Offset(w, h), Offset(0f, h) to Offset(w, h))
        'T' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w / 2, 0f) to Offset(w / 2, h))
        'U' -> listOf(Offset(0f, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h), Offset(w, h) to Offset(w, 0f))
        'V' -> listOf(Offset(0f, 0f) to Offset(w / 2, h), Offset(w / 2, h) to Offset(w, 0f))
        'W' -> listOf(Offset(0f, 0f) to Offset(w * 0.25f, h), Offset(w * 0.25f, h) to Offset(w / 2, h * 0.45f), Offset(w / 2, h * 0.45f) to Offset(w * 0.75f, h), Offset(w * 0.75f, h) to Offset(w, 0f))
        'X' -> listOf(Offset(0f, 0f) to Offset(w, h), Offset(w, 0f) to Offset(0f, h))
        'Y' -> listOf(Offset(0f, 0f) to Offset(w / 2, h / 2), Offset(w, 0f) to Offset(w / 2, h / 2), Offset(w / 2, h / 2) to Offset(w / 2, h))
        'Z' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(0f, h), Offset(0f, h) to Offset(w, h))
        '2' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(0f, h / 2) to Offset(0f, h), Offset(0f, h) to Offset(w, h))
        '3' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, h), Offset(w * 0.2f, h / 2) to Offset(w, h / 2), Offset(0f, h) to Offset(w, h))
        '4' -> listOf(Offset(0f, 0f) to Offset(0f, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, 0f) to Offset(w, h))
        '5' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h / 2), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, h / 2) to Offset(w, h), Offset(0f, h) to Offset(w, h))
        '6' -> listOf(Offset(w, 0f) to Offset(0f, 0f), Offset(0f, 0f) to Offset(0f, h), Offset(0f, h / 2) to Offset(w, h / 2), Offset(w, h / 2) to Offset(w, h), Offset(0f, h) to Offset(w, h))
        '7' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w * 0.35f, h))
        '8' -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(0f, h / 2) to Offset(w, h / 2), Offset(0f, h) to Offset(w, h), Offset(0f, 0f) to Offset(0f, h), Offset(w, 0f) to Offset(w, h))
        else -> listOf(Offset(0f, 0f) to Offset(w, 0f), Offset(0f, h / 2) to Offset(w, h / 2), Offset(0f, h) to Offset(w, h), Offset(0f, 0f) to Offset(0f, h / 2), Offset(w, 0f) to Offset(w, h))
    }
    segs.forEach { (p1, p2) ->
        drawLine(color = color, start = p1, end = p2, strokeWidth = sw, cap = cap)
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

@Composable
private fun CubicStreamingVideoPlayer(
    item: RemoteMediaItem,
    hwAcceleration: Boolean,
    storageRefreshTick: Int,
    onOrientationResolved: (Boolean) -> Unit,
    onToggleOrientation: () -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val localFile = remember(item.id, storageRefreshTick) {
        AppStorageManager.findDownloadedFile(context, item)
    }
    val isLocalSource = localFile != null
    val playbackUri = remember(item.id, localFile?.absolutePath) {
        if (localFile != null) {
            if (localFile.name.endsWith(".vcv", ignoreCase = true)) {
                Uri.parse(LocalStreamServer.getLocalStreamUrl(item, localFile))
            } else {
                Uri.fromFile(localFile)
            }
        } else {
            Uri.parse(LocalStreamServer.getStreamUrl(item))
        }
    }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }
    var ready by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
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

    DisposableEffect(lifecycleOwner, videoView) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                runCatching {
                    videoView?.pause()
                    isPlaying = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { videoView?.stopPlayback() }
        }
    }

    LaunchedEffect(videoView) {
        while (true) {
            val vv = videoView
            if (vv != null && ready && !isSeeking) {
                positionMs = vv.currentPosition.coerceAtLeast(0)
                if (positionMs >= durationMs - 400 && !vv.isPlaying && !isLooping) {
                    isPlaying = false
                }
            }
            delay(300L)
        }
    }

    val scaleMod = when (resizeMode) {
        VideoResizeMode.FIT -> Modifier.fillMaxSize()
        VideoResizeMode.FILL -> Modifier.fillMaxSize().graphicsLayer(scaleX = 1.28f, scaleY = 1.28f)
        VideoResizeMode.STRETCH -> Modifier.fillMaxSize().graphicsLayer(scaleX = 1.15f, scaleY = 0.92f)
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
                        val vv = videoView ?: return@detectTapGestures
                        val half = size.width / 2f
                        if (offset.x < half) {
                            val next = (vv.currentPosition - 10_000).coerceAtLeast(0)
                            vv.seekTo(next)
                            positionMs = next
                            gestureBadge = "-10s"
                        } else {
                            val next = (vv.currentPosition + 10_000).coerceAtMost(durationMs)
                            vv.seekTo(next)
                            positionMs = next
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
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val vv = VideoView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                        setLayerType(
                            if (hwAcceleration) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE,
                            null
                        )
                        setVideoURI(playbackUri)
                        setOnPreparedListener { mp ->
                            mediaPlayerRef = mp
                            durationMs = mp.duration.coerceAtLeast(1)
                            ready = true
                            mp.isLooping = isLooping
                            if (isMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                            if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                onOrientationResolved(mp.videoWidth > mp.videoHeight)
                            }
                            start()
                            isPlaying = true
                            interactionTick++
                        }
                        setOnCompletionListener {
                            if (!isLooping) {
                                isPlaying = false
                                positionMs = durationMs
                                controlsVisible = true
                            }
                        }
                        setOnErrorListener { _, _, _ ->
                            hasError = true
                            controlsVisible = true
                            true
                        }
                    }
                    videoView = vv
                    addView(vv)
                }
            },
            modifier = scaleMod
        )

        if (!ready && !hasError) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = BbOrange,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(30.dp)
                )
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "STREAM UNAVAILABLE",
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
                                    val vv = videoView ?: return@CubicSquareIconButton
                                    val next = (vv.currentPosition - 10_000).coerceAtLeast(0)
                                    vv.seekTo(next)
                                    positionMs = next
                                    interactionTick++
                                }
                            )
                            CubicActionButton(
                                text = if (isPlaying) "PAUSE" else "PLAY",
                                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                primary = true,
                                onClick = {
                                    val vv = videoView ?: return@CubicActionButton
                                    if (vv.isPlaying) {
                                        vv.pause()
                                        isPlaying = false
                                    } else {
                                        vv.start()
                                        isPlaying = true
                                    }
                                    interactionTick++
                                }
                            )
                            CubicSquareIconButton(
                                icon = Icons.Default.Forward10,
                                active = false,
                                onClick = {
                                    val vv = videoView ?: return@CubicSquareIconButton
                                    val next = (vv.currentPosition + 10_000).coerceAtMost(durationMs)
                                    vv.seekTo(next)
                                    positionMs = next
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
                                    videoView?.seekTo(positionMs)
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
                                    val vol = if (isMuted) 0f else 1f
                                    runCatching { mediaPlayerRef?.setVolume(vol, vol) }
                                    interactionTick++
                                }
                            )
                            CubicActionButton(
                                text = "${speeds[speedIndex]}x",
                                icon = Icons.Default.Speed,
                                primary = speedIndex != 1,
                                onClick = {
                                    speedIndex = (speedIndex + 1) % speeds.size
                                    val spd = speeds[speedIndex]
                                    runCatching {
                                        mediaPlayerRef?.let { mp ->
                                            mp.playbackParams = PlaybackParams().setSpeed(spd)
                                        }
                                    }
                                    interactionTick++
                                }
                            )
                            CubicSquareIconButton(
                                icon = Icons.Default.Loop,
                                active = isLooping,
                                onClick = {
                                    isLooping = !isLooping
                                    runCatching { mediaPlayerRef?.isLooping = isLooping }
                                    gestureBadge = if (isLooping) "LOOP ON" else "LOOP OFF"
                                    interactionTick++
                                }
                            )
                            Spacer(Modifier.weight(1f))
                            CubicActionButton(
                                text = if (isLocalSource) "LOCAL" else "STREAM",
                                icon = if (isLocalSource) Icons.Default.Verified else Icons.Default.Download,
                                primary = isLocalSource,
                                onClick = {
                                    interactionTick++
                                    if (isLocalSource) {
                                        Toast.makeText(
                                            context,
                                            "Playing local file: ${localFile?.name}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        onDownload()
                                    }
                                }
                            )
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
    storageRefreshTick: Int,
    onOrientationResolved: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val localFile = remember(item.id, storageRefreshTick) {
        AppStorageManager.findDownloadedFile(context, item)
    }
    val isLocalSource = localFile != null
    var bitmap by remember(item.id) { mutableStateOf(AutoDiscoveryRepository.getCachedThumbnail(item.id)) }
    var isFullLoaded by remember(item.id) { mutableStateOf(false) }
    var loadFailed by remember(item.id) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(item.id, localFile?.absolutePath) {
        val fullBmp = AutoDiscoveryRepository.loadBestImageBitmap(context, item)
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
                CubicActionButton(
                    text = if (isLocalSource) "LOCAL" else "STREAM",
                    icon = if (isLocalSource) Icons.Default.Verified else Icons.Default.Download,
                    primary = isLocalSource,
                    onClick = {
                        interactionTick++
                        if (isLocalSource) {
                            Toast.makeText(
                                context,
                                "Viewing local file: ${localFile?.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            onDownload()
                        }
                    }
                )
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
