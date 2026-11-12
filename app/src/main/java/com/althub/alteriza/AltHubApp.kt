package com.althub.alteriza

import android.graphics.Bitmap
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import java.io.File

private enum class HubSection(val label: String) {
    ALL("ALL"),
    VIDEO("VIDEO"),
    PHOTO("PHOTO"),
    INFO("INFO")
}

@Composable
fun AltHubApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeSection by remember { mutableStateOf(HubSection.ALL) }
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
    var viewerVideoFile by remember { mutableStateOf<File?>(null) }
    var viewerImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewerLoading by remember { mutableStateOf(false) }

    suspend fun syncRepository() {
        loading = true
        discovery = AutoDiscoveryRepository.discoverAll()
        loading = false
    }

    LaunchedEffect(Unit) {
        syncRepository()
    }

    val filteredMedia = remember(discovery.mediaItems, activeSection, searchQuery) {
        discovery.mediaItems.filter { item ->
            val matchKind = when (activeSection) {
                HubSection.ALL -> true
                HubSection.VIDEO -> item.kind == MediaKind.VIDEO
                HubSection.PHOTO -> item.kind == MediaKind.IMAGE
                HubSection.INFO -> false
            }
            val matchQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, true) ||
                item.fileName.contains(searchQuery, true)
            matchKind && matchQuery
        }
    }

    val filteredPosts = remember(discovery.infoPosts, searchQuery) {
        discovery.infoPosts.filter { post ->
            searchQuery.isBlank() ||
                post.title.contains(searchQuery, true) ||
                post.bbcodeContent.contains(searchQuery, true)
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
            TopBrandBar(
                totalMedia = discovery.mediaItems.size,
                totalPosts = discovery.infoPosts.size,
                isSyncing = loading,
                onSync = { scope.launch { syncRepository() } }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorder)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HubSection.entries.forEach { section ->
                    val count = when (section) {
                        HubSection.ALL -> discovery.mediaItems.size
                        HubSection.VIDEO -> discovery.mediaItems.count { it.kind == MediaKind.VIDEO }
                        HubSection.PHOTO -> discovery.mediaItems.count { it.kind == MediaKind.IMAGE }
                        HubSection.INFO -> discovery.infoPosts.size
                    }
                    CubicTabButton(
                        label = "${section.label} [$count]",
                        active = activeSection == section,
                        onClick = { activeSection = section },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            CubicSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(12.dp))

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
                                    text = "AUTO-DISCOVERING CDN & POSTS...",
                                    color = BbWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    activeSection == HubSection.INFO -> {
                        if (filteredPosts.isEmpty()) {
                            EmptyTerminalState(
                                title = "NO POSTS FOUND",
                                subtitle = "Sumber: ${AutoDiscoveryRepository.MAIN_POST_URL}"
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredPosts, key = { it.id }) { post ->
                                    InfoTerminalCard(post = post)
                                }
                            }
                        }
                    }

                    filteredMedia.isEmpty() -> {
                        EmptyTerminalState(
                            title = "NO .VCV / .ICV MEDIA",
                            subtitle = discovery.errorMessage ?: "CDN: ${AutoDiscoveryRepository.MAIN_CDN_URL}"
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 164.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredMedia, key = { it.id }) { item ->
                                CubicMediaCard(
                                    item = item,
                                    onOpen = { selectedMedia = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val activeMedia = selectedMedia
    if (activeMedia != null) {
        LaunchedEffect(activeMedia.id) {
            viewerVideoFile = null
            viewerImageBitmap = null
            viewerLoading = true
            if (activeMedia.kind == MediaKind.VIDEO) {
                viewerVideoFile = AutoDiscoveryRepository.prepareMediaForPlayback(context, activeMedia)
            } else {
                viewerImageBitmap = AutoDiscoveryRepository.loadFullImageBitmap(context, activeMedia)
            }
            viewerLoading = false
        }

        Dialog(
            onDismissRequest = {
                selectedMedia = null
                viewerVideoFile = null
                viewerImageBitmap = null
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BbBlack)
            ) {
                when {
                    viewerLoading -> {
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
                                    color = BbOrange,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "DECODING ${activeMedia.fileName.uppercase()}...",
                                    color = BbWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    activeMedia.kind == MediaKind.VIDEO && viewerVideoFile != null -> {
                        CubicVideoPlayer(
                            title = activeMedia.title,
                            path = viewerVideoFile!!.absolutePath,
                            onBack = {
                                selectedMedia = null
                                viewerVideoFile = null
                            }
                        )
                    }

                    activeMedia.kind == MediaKind.IMAGE && viewerImageBitmap != null -> {
                        CubicImageViewer(
                            title = activeMedia.title,
                            bitmap = viewerImageBitmap!!,
                            onBack = {
                                selectedMedia = null
                                viewerImageBitmap = null
                            }
                        )
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "FAILED TO DECODE CONTAINER",
                                color = BbOrange,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(16.dp))
                            CubicActionButton(
                                text = "CLOSE",
                                primary = true,
                                onClick = { selectedMedia = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TechnicalGridBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 48.dp.toPx()
        val lineColor = Color(0xFF171717)
        val crossColor = Color(0xFF282828)
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
private fun TopBrandBar(
    totalMedia: Int,
    totalPosts: Int,
    isSyncing: Boolean,
    onSync: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BbBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            text = "RUST+KT",
                            color = BbWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Text(
                text = "CDN AUTO-DISCOVERY // $totalMedia MEDIA · $totalPosts INFO",
                color = BbMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
        CubicActionButton(
            text = if (isSyncing) "SYNC..." else "SYNC",
            icon = Icons.Default.Refresh,
            primary = false,
            onClick = onSync
        )
    }
}

@Composable
private fun CubicTabButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (active) BbWhite else BbSurface
    val fg = if (active) BbBlack else BbMuted
    val border = if (active) BbWhite else BbBorder

    Box(
        modifier = modifier
            .height(38.dp)
            .background(bg)
            .border(1.dp, border)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(if (active) BbOrange else BbBorderBright)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = fg,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
private fun CubicSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .background(BbSurface)
            .border(1.dp, BbBorder)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = BbMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "SEARCH .VCV / .ICV / INFO...",
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
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
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = BbWhite,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@Composable
private fun CubicMediaCard(
    item: RemoteMediaItem,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var thumbLoading by remember(item.id) { mutableStateOf(true) }

    LaunchedEffect(item.id) {
        thumb = AutoDiscoveryRepository.loadThumbnailBitmap(context, item)
        thumbLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BbSurface)
            .border(1.dp, BbBorder)
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BbSurfaceElevated)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (item.kind == MediaKind.VIDEO) BbOrange else BbWhite)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (item.kind == MediaKind.VIDEO) "VCV" else "ICV",
                color = BbWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = item.sourceType.name,
                color = BbMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
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
                .aspectRatio(1.15f)
                .background(BbBlack),
            contentAlignment = Alignment.Center
        ) {
            val b = thumb
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (thumbLoading) {
                CircularProgressIndicator(
                    color = BbMuted,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = if (item.kind == MediaKind.VIDEO) "[ VIDEO ]" else "[ IMAGE ]",
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            if (item.kind == MediaKind.VIDEO) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(BbWhite)
                        .border(1.dp, BbBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = BbBlack,
                        modifier = Modifier.size(22.dp)
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = item.title.uppercase(),
                color = BbWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (item.sizeBytes > 0L) "${item.sizeBytes / 1024} KB" else item.fileName,
                    color = BbMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(BbWhite)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "OPEN",
                            color = BbBlack,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Default.NorthEast,
                            contentDescription = null,
                            tint = BbBlack,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoTerminalCard(post: InfoPost) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BbSurface)
            .border(1.dp, BbBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BbSurfaceElevated)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(7.dp).background(BbOrange))
            Box(Modifier.size(7.dp).background(BbBorderBright))
            Box(Modifier.size(7.dp).background(BbBorderBright))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "POST // ${post.fileName.uppercase()}",
                color = BbMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "BBCODE",
                color = BbOrange,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
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
                letterSpacing = 0.8.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BbBorder)
            )
            BBCodeView(bbcode = post.bbcodeContent)
        }
    }
}

@Composable
private fun EmptyTerminalState(
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
private fun CubicVideoPlayer(
    title: String,
    path: String,
    onBack: () -> Unit
) {
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }
    var prepared by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(videoView) {
        while (true) {
            val vv = videoView
            if (vv != null && prepared) {
                positionMs = vv.currentPosition.coerceAtLeast(0)
                if (positionMs >= durationMs - 400 && !vv.isPlaying) {
                    isPlaying = false
                }
            }
            delay(300)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BbBlack)) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(path)
                    setOnPreparedListener { mp ->
                        durationMs = mp.duration.coerceAtLeast(1)
                        prepared = true
                        mp.isLooping = false
                        start()
                        isPlaying = true
                    }
                    setOnCompletionListener {
                        isPlaying = false
                        positionMs = durationMs
                    }
                    videoView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                }
        )

        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .fillMaxWidth()
                    .background(BbBlack.copy(alpha = 0.85f))
                    .border(1.dp, BbBorder)
                    .padding(12.dp),
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
                    text = title.uppercase(),
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
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .fillMaxWidth()
                    .background(BbBlack.copy(alpha = 0.90f))
                    .border(1.dp, BbBorder)
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                    }
                )
                Spacer(Modifier.height(8.dp))
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
                            videoView?.seekTo(v.toInt())
                            positionMs = v.toInt()
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

@Composable
private fun CubicImageViewer(
    title: String,
    bitmap: Bitmap,
    onBack: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize().background(BbBlack)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
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
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .fillMaxWidth()
                .background(BbBlack.copy(alpha = 0.85f))
                .border(1.dp, BbBorder)
                .padding(12.dp),
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
                text = title.uppercase(),
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
                    }
                )
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
