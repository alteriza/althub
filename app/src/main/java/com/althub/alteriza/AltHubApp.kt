package com.althub.alteriza

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

private enum class NavDestination(val label: String, val icon: ImageVector) {
    FEED("FEED", Icons.Default.Home),
    VIDEOS("VIDEOS", Icons.Default.Movie),
    PHOTOS("PHOTOS", Icons.Default.Image),
    INFO("INFO", Icons.Default.Campaign)
}

private enum class FeedChip(val label: String) {
    ALL("ALL"),
    VIDEOS("VIDEOS"),
    PHOTOS("PHOTOS"),
    INFO("INFO")
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

@Composable
fun AltHubApp() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(NavDestination.FEED) }
    var activeFeedChip by remember { mutableStateOf(FeedChip.ALL) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var discovery by remember {
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

    suspend fun syncRepository() {
        loading = true
        discovery = AutoDiscoveryRepository.discoverAll()
        loading = false
    }

    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        syncRepository()
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
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                isSyncing = loading,
                onToggleSearch = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) searchQuery = ""
                },
                onSearchChange = { searchQuery = it },
                onSync = { scope.launch { syncRepository() } }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorder)
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> {
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
                                    text = "SYNCING FEED...",
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
                                        subtitle = discovery.errorMessage ?: "CDN: ${AutoDiscoveryRepository.MAIN_CDN_URL}"
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
                        if (allVideos.isEmpty()) {
                            EmptyFeedBox(
                                title = "NO VIDEOS FOUND",
                                subtitle = discovery.errorMessage ?: "Belum ada video di repository"
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(allVideos, key = { _, item -> item.id }) { idx, video ->
                                    VideoStreamCard(
                                        indexNumber = allVideos.size - idx,
                                        item = video,
                                        onWatch = { openMedia(video) }
                                    )
                                }
                            }
                        }
                    }

                    currentTab == NavDestination.PHOTOS -> {
                        if (allPhotos.isEmpty()) {
                            EmptyFeedBox(
                                title = "NO PHOTOS FOUND",
                                subtitle = discovery.errorMessage ?: "Belum ada foto di repository"
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(allPhotos, key = { it.id }) { photo ->
                                    SocialStreamPostCard(
                                        item = photo,
                                        onOpen = { openMedia(photo) }
                                    )
                                }
                            }
                        }
                    }

                    currentTab == NavDestination.INFO -> {
                        if (allPosts.isEmpty()) {
                            EmptyFeedBox(
                                title = "NO INFO POSTS",
                                subtitle = "Sumber: ${AutoDiscoveryRepository.MAIN_POST_URL}"
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(allPosts, key = { it.id }) { post ->
                                    SocialAnnouncementCard(post = post)
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
                videoCount = allVideos.size,
                photoCount = allPhotos.size,
                infoCount = allPosts.size,
                onSelect = { currentTab = it }
            )
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
                        onOrientationResolved = { isLandscape ->
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                            applyImmersiveSystemBars(activity)
                        },
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
                        onBack = { closeViewer() }
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
    searchExpanded: Boolean,
    searchQuery: String,
    isSyncing: Boolean,
    onToggleSearch: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSync: () -> Unit
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
                    .background(BbWhite),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(BbBlack)
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
            Spacer(Modifier.width(8.dp))
            CubicActionButton(
                text = if (isSyncing) "..." else "SYNC",
                icon = Icons.Default.Refresh,
                primary = false,
                onClick = onSync
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
    onOpen: () -> Unit,
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
                        "${item.fileName} • ${item.sizeBytes / 1024} KB"
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
            Spacer(Modifier.width(10.dp))
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
    onWatch: () -> Unit
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
            Spacer(Modifier.width(10.dp))
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
    videoCount: Int,
    photoCount: Int,
    infoCount: Int,
    onSelect: (NavDestination) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BbBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NavDestination.entries.forEach { dest ->
            val selected = current == dest
            val badge = when (dest) {
                NavDestination.FEED -> ""
                NavDestination.VIDEOS -> " [$videoCount]"
                NavDestination.PHOTOS -> " [$photoCount]"
                NavDestination.INFO -> " [$infoCount]"
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(if (selected) BbWhite else BbSurface)
                    .border(1.dp, if (selected) BbWhite else BbBorder)
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
                            tint = if (selected) BbBlack else BbMuted,
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
                        color = if (selected) BbBlack else BbMuted,
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
            .fillMaxSize()
            .padding(24.dp),
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
    onOrientationResolved: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val streamUrl = remember(item.id) { LocalStreamServer.getStreamUrl(item) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }
    var ready by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var isSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, interactionTick, isPlaying, isSeeking) {
        if (controlsVisible && isPlaying && !isSeeking) {
            delay(3000L)
            controlsVisible = false
        }
    }

    LaunchedEffect(videoView) {
        while (true) {
            val vv = videoView
            if (vv != null && ready && !isSeeking) {
                positionMs = vv.currentPosition.coerceAtLeast(0)
                if (positionMs >= durationMs - 400 && !vv.isPlaying) {
                    isPlaying = false
                }
            }
            delay(300L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BbBlack)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) interactionTick++
                    }
                )
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
                        setVideoURI(Uri.parse(streamUrl))
                        setOnPreparedListener { mp ->
                            durationMs = mp.duration.coerceAtLeast(1)
                            ready = true
                            mp.isLooping = false
                            if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                onOrientationResolved(mp.videoWidth > mp.videoHeight)
                            }
                            start()
                            isPlaying = true
                            interactionTick++
                        }
                        setOnCompletionListener {
                            isPlaying = false
                            positionMs = durationMs
                            controlsVisible = true
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
            modifier = Modifier.fillMaxSize()
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
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .background(BbBlack.copy(alpha = 0.85f))
                        .border(1.dp, BbBorder)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CubicActionButton(
                        text = "BACK",
                        icon = Icons.Default.ArrowBack,
                        primary = true,
                        onClick = onBack
                    )
                    Spacer(Modifier.width(12.dp))
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
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .background(BbBlack.copy(alpha = 0.88f))
                        .border(1.dp, BbBorder)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
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
                    Spacer(Modifier.height(6.dp))
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
                }
            }
        }
    }
}

@Composable
private fun CubicStreamingImageViewer(
    item: RemoteMediaItem,
    onOrientationResolved: (Boolean) -> Unit,
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

    LaunchedEffect(item.id) {
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
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .background(BbBlack.copy(alpha = 0.85f))
                        .border(1.dp, BbBorder)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CubicActionButton(
                        text = "BACK",
                        icon = Icons.Default.ArrowBack,
                        primary = true,
                        onClick = onBack
                    )
                    Spacer(Modifier.width(12.dp))
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
                    if (scale > 1f) {
                        CubicActionButton(
                            text = "1:1",
                            primary = false,
                            onClick = {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                                interactionTick++
                            }
                        )
                    }
                }

                if (activeBmp != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp)
                            .background(BbBlack.copy(alpha = 0.85f))
                            .border(1.dp, BbBorder)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${activeBmp.width}x${activeBmp.height} PX",
                            color = BbMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "ZOOM ${(scale * 100).toInt()}%",
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
