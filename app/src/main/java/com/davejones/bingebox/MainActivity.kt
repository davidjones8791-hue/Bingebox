@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.davejones.bingebox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Xml
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

// ============================================================
// MODELS
// ============================================================

data class Movie(
    val title: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    val releaseDate: String = "",
    val voteAverage: Double = 0.0,
    val streamUrl: String = ""
)

data class TvSeries(
    val title: String,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    val releaseDate: String = "",
    val voteAverage: Double = 0.0,
    val seasons: List<TvSeason> = emptyList()
)

data class TvSeason(
    val seasonNumber: Int,
    val name: String = "",
    val episodes: List<TvEpisode> = emptyList()
)

data class TvEpisode(
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val overview: String = "",
    val duration: String = ""
)

data class LiveChannel(
    val name: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val group: String = "",
    val tvgId: String = "",
    val streamId: String = ""
)

data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long
)

data class PodcastShow(
    val name: String,
    val author: String,
    val artworkUrl: String,
    val feedUrl: String
)

data class PodcastEpisode(
    val title: String,
    val description: String,
    val audioUrl: String,
    val pubDate: String
)

data class XtreamConnection(
    val serverUrl: String,
    val username: String,
    val password: String
)

data class M3uParseResult(
    val channels: List<LiveChannel>,
    val epg: List<EpgProgram>
)

enum class MainCategory(val title: String) {
    HOME("Home"),
    LIVE("Live TV"),
    GUIDE("TV Guide"),
    MOVIES("Movies"),
    SERIES("TV Series"),
    PODCASTS("Podcasts"),
    SOCIAL("Social Media")
}

class MainViewModel : ViewModel() {
    var selectedCategory by mutableStateOf(MainCategory.HOME)
    var selectedChannelStreamUrl by mutableStateOf<String?>(null)
    var showFullscreenPlayer by mutableStateOf(false)
    var channelList by mutableStateOf<List<LiveChannel>>(emptyList())
    var epgList by mutableStateOf<List<EpgProgram>>(emptyList())
    var movies by mutableStateOf<List<Movie>>(emptyList())
    var homeLoading by mutableStateOf(false)
    var homeLoaded by mutableStateOf(false)
    var guideLoading by mutableStateOf(false)
    var groupFilterVersion by mutableStateOf(0)
}

// ============================================================
// HELPERS
// ============================================================

fun normalizeIptvGroup(group: String): String {

    val cleaned =
        group
            .trim()
            .replace(
                Regex("\\s+"),
                " "
            )

    return if (cleaned.isBlank()) {
        "General"
    } else {
        cleaned
    }
}

fun getFilteredChannels(
    channels: List<LiveChannel>,
    context: Context,
    filterVersion: Int = 0
): List<LiveChannel> {
    if (filterVersion < 0) return channels
    val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
    val disabledGroups = prefs.getStringSet("disabled_groups", emptySet()) ?: emptySet()
    if (disabledGroups.isEmpty()) return channels
    return channels.filter { channel ->
        val groupNorm = normalizeIptvGroup(channel.group)
        groupNorm !in disabledGroups
    }
}

fun normalizeGuideKey(value: String): String =
    value.lowercase(Locale.getDefault())
        .replace(Regex("""[^a-z0-9]+"""), "")

fun channelInitials(name: String): String {

    val cleaned = name
        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        .trim()

    if (cleaned.isEmpty()) return "TV"

    val words = cleaned
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() }

    return if (words.size >= 2) {
        words.take(3).joinToString("") {
            it.first().uppercase()
        }
    } else {
        words.first()
            .filter { it.isLetterOrDigit() }
            .take(3)
            .uppercase()
    }
}

fun extractM3uAttribute(
    text: String,
    vararg names: String
): String {

    for (name in names) {

        val regex = Regex(
            """(?i)(?:^|\s)$name\s*=\s*["']([^"']*)["']"""
        )

        val value = regex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        if (!value.isNullOrBlank()) {
            return value
        }
    }

    return ""
}

fun extractStreamIdFromUrl(streamUrl: String): String {

    return try {

        URL(streamUrl)
            .path
            .trimEnd('/')
            .substringAfterLast('/')
            .substringBeforeLast('.')

    } catch (_: Exception) {

        streamUrl
            .trimEnd('/')
            .substringAfterLast('/')
            .substringBeforeLast('.')
    }
}

fun parseDateMillis(value: String): Long {

    val cleaned = value.trim()

    if (cleaned.isBlank()) return 0L

    cleaned.toLongOrNull()?.let {
        return if (it < 10_000_000_000L) {
            it * 1000L
        } else {
            it
        }
    }

    val formats = listOf(
        "yyyyMMddHHmmss Z",
        "yyyyMMddHHmmssZ",
        "yyyyMMddHHmmssXXX",
        "yyyyMMddHHmmss",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd"
    )

    for (pattern in formats) {

        try {

            val sdf =
                SimpleDateFormat(
                    pattern,
                    Locale.US
                )

            sdf.timeZone =
                TimeZone.getTimeZone("UTC")

            sdf.parse(cleaned)?.let {
                return it.time
            }

        } catch (_: Exception) {
        }
    }

    return 0L
}

fun formatProgramTime(time: Long): String {

    if (time <= 0L) return "--:--"

    return SimpleDateFormat(
        "HH:mm",
        Locale.getDefault()
    ).format(Date(time))
}

fun currentProgramForChannel(
    channel: LiveChannel,
    epgMap: Map<String, List<EpgProgram>>
): EpgProgram? {

    val now = System.currentTimeMillis()

    return findProgramsForChannel(
        channel,
        epgMap
    ).firstOrNull {
        it.startTime <= now &&
                it.endTime >= now
    }
}

// ============================================================
// CHANNEL LOGO
// ============================================================

@Composable
fun ChannelLogo(
    channel: LiveChannel,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 62.dp
) {

    var imageFailed by remember(
        channel.logoUrl
    ) {
        mutableStateOf(false)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(
                RoundedCornerShape(12.dp)
            )
            .background(
                Color.White.copy(alpha = 0.08f)
            ),
        contentAlignment = Alignment.Center
    ) {

        if (
            channel.logoUrl.isNotBlank() &&
            !imageFailed
        ) {

            AsyncImage(
                model = channel.logoUrl,

                contentDescription =
                    channel.name,

                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),

                contentScale =
                    ContentScale.Fit,

                onError = {
                    imageFailed = true
                }
            )

        } else {

            Text(
                text =
                    channelInitials(
                        channel.name
                    ),

                color = Color.White,

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}

// ============================================================
// MAIN ACTIVITY
// ============================================================

class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {

            val darkScheme =
                darkColorScheme(
                    background =
                        Color(0xFF0B0B0F),

                    surface =
                        Color(0xFF15151B),

                    surfaceVariant =
                        Color(0xFF202027),

                    primary =
                        Color(0xFF8AB4F8),

                    secondary =
                        Color(0xFFB8C7E8)
                )

            MaterialTheme(
                colorScheme = darkScheme
            ) {

                Surface(
                    modifier =
                        Modifier.fillMaxSize(),

                    color =
                        MaterialTheme
                            .colorScheme
                            .background
                ) {

                    MainContainer()
                }
            }
        }
    }
}

// ============================================================
// MAIN CONTAINER
// ============================================================

@Composable
fun MainContainer(
    viewModel: MainViewModel = viewModel()
) {

    val selectedCategory = viewModel.selectedCategory
    val selectedChannelStreamUrl = viewModel.selectedChannelStreamUrl
    val showFullscreenPlayer = viewModel.showFullscreenPlayer
    val channelList = viewModel.channelList
    val epgList = viewModel.epgList
    val movies = viewModel.movies
    val homeLoading = viewModel.homeLoading
    val homeLoaded = viewModel.homeLoaded
    val guideLoading = viewModel.guideLoading

    val selectedChannel = remember(channelList, selectedChannelStreamUrl) {
        channelList.find { it.streamUrl == selectedChannelStreamUrl }
    }

    val context =
        androidx.compose.ui.platform
            .LocalContext.current

    val player = remember(context) {
        ExoPlayer.Builder(context).build()
    }

    DisposableEffect(Unit) {

        onDispose {
            player.release()
        }
    }

    // ========================================================
    // LOAD HOME DATA
    // ========================================================

    LaunchedEffect(Unit) {

        if (homeLoaded) return@LaunchedEffect

        viewModel.homeLoading = true

        try {

            val prefs =
                context.getSharedPreferences(
                    "bingebox_iptv",
                    Context.MODE_PRIVATE
                )

            val server =
                prefs.getString(
                    "xtream_server",
                    ""
                ).orEmpty()

            val username =
                prefs.getString(
                    "xtream_username",
                    ""
                ).orEmpty()

            val password =
                prefs.getString(
                    "xtream_password",
                    ""
                ).orEmpty()

            val playlist =
                prefs.getString(
                    "playlist_url",
                    ""
                ).orEmpty()

            val epgUrl =
                prefs.getString(
                    "epg_url",
                    ""
                ).orEmpty()

            if (
                server.isNotBlank() &&
                username.isNotBlank() &&
                password.isNotBlank()
            ) {

                val connection =
                    XtreamConnection(
                        serverUrl = server,
                        username = username,
                        password = password
                    )

                val channels =
                    withContext(
                        Dispatchers.IO
                    ) {
                        fetchXtreamChannels(
                            connection
                        )
                    }

                viewModel.channelList = channels

                if (channels.isNotEmpty()) {
                    val allGroups = channels.map { normalizeIptvGroup(it.group) }.toSet()
                    prefs.edit().putStringSet("all_groups", allGroups).apply()

                    viewModel.epgList =
                        fetchXtreamEpgForChannels(
                            connection = connection,
                            channels = channels,
                            customEpgUrl = epgUrl
                        )
                }

            } else if (
                playlist.isNotBlank()
            ) {

                val result =
                    withContext(
                        Dispatchers.IO
                    ) {
                        parseM3uPlaylist(
                            playlist
                        )
                    }

                viewModel.channelList =
                    result.channels

                if (result.channels.isNotEmpty()) {
                    val allGroups = result.channels.map { normalizeIptvGroup(it.group) }.toSet()
                    prefs.edit().putStringSet("all_groups", allGroups).apply()
                }

                var epg = result.epg
                if (epgUrl.isNotBlank()) {
                    try {
                        val customEpg = withContext(Dispatchers.IO) { parseXmltvEpg(epgUrl) }
                        if (customEpg.isNotEmpty()) {
                            epg = customEpg
                        }
                    } catch (e: Exception) {
                        Log.e("BingeBox", "Custom EPG failed", e)
                    }
                }
                viewModel.epgList = epg
            } else if (epgUrl.isNotBlank()) {
                try {
                    val customEpg = withContext(Dispatchers.IO) { parseXmltvEpg(epgUrl) }
                    if (customEpg.isNotEmpty()) {
                        viewModel.epgList = customEpg
                    }
                } catch (e: Exception) {
                    Log.e("BingeBox", "Custom EPG failed", e)
                }
            }

        } catch (e: Exception) {

            Log.e(
                "BingeBox",
                "Home IPTV loading failed",
                e
            )
        }

        try {

            viewModel.movies =
                withContext(
                    Dispatchers.IO
                ) {
                    fetchPopularMovies()
                }

        } catch (e: Exception) {

            Log.e(
                "BingeBox",
                "Home movie loading failed",
                e
            )
        }

        viewModel.homeLoading = false
        viewModel.homeLoaded = true
    }

    // ========================================================
    // LOAD GUIDE IF NECESSARY
    // ========================================================

    LaunchedEffect(
        selectedCategory
    ) {

        if (
            selectedCategory ==
            MainCategory.GUIDE &&
            (channelList.isEmpty() || epgList.isEmpty())
        ) {

            val prefs =
                context.getSharedPreferences(
                    "bingebox_iptv",
                    Context.MODE_PRIVATE
                )

            val playlistUrl =
                prefs.getString(
                    "playlist_url",
                    ""
                ).orEmpty()

            val server =
                prefs.getString(
                    "xtream_server",
                    ""
                ).orEmpty()

            val username =
                prefs.getString(
                    "xtream_username",
                    ""
                ).orEmpty()

            val password =
                prefs.getString(
                    "xtream_password",
                    ""
                ).orEmpty()

            val epgUrl =
                prefs.getString(
                    "epg_url",
                    ""
                ).orEmpty()

            viewModel.guideLoading = true

            try {

                if (
                    server.isNotBlank() &&
                    username.isNotBlank() &&
                    password.isNotBlank()
                ) {

                    val connection =
                        XtreamConnection(
                            serverUrl = server,
                            username = username,
                            password = password
                        )

                    val channels =
                        if (channelList.isNotEmpty()) {
                            channelList
                        } else {
                            withContext(
                                Dispatchers.IO
                            ) {
                                fetchXtreamChannels(
                                    connection
                                )
                            }
                        }

                    viewModel.channelList =
                        channels

                    viewModel.epgList =
                        fetchXtreamEpgForChannels(
                            connection = connection,
                            channels = channels,
                            customEpgUrl = epgUrl
                        )

                } else if (
                    playlistUrl.isNotBlank()
                ) {

                    if (channelList.isEmpty()) {
                        val result =
                            withContext(
                                Dispatchers.IO
                            ) {
                                parseM3uPlaylist(
                                    playlistUrl
                                )
                            }

                        viewModel.channelList =
                            result.channels

                        viewModel.epgList =
                            result.epg
                    }

                    if (epgUrl.isNotBlank()) {
                        val customEpg =
                            withContext(
                                Dispatchers.IO
                            ) {
                                parseXmltvEpg(
                                    epgUrl
                                )
                            }
                        if (customEpg.isNotEmpty()) {
                            viewModel.epgList = customEpg
                        }
                    }

                } else if (
                    epgUrl.isNotBlank()
                ) {
                    val customEpg =
                        withContext(
                            Dispatchers.IO
                        ) {
                            parseXmltvEpg(
                                epgUrl
                            )
                        }
                    if (customEpg.isNotEmpty()) {
                        viewModel.epgList = customEpg
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    "BingeBox",
                    "Guide loading failed",
                    e
                )

            } finally {

                viewModel.guideLoading = false
            }
        }
    }

    Scaffold(

        containerColor =
            Color(0xFF0B0B0F),

        topBar = {

            BingeBoxTopBar(
                selectedCategory =
                    selectedCategory,

                onCategorySelected = {
                    viewModel.selectedCategory = it
                }
            )
        }

    ) { paddingValues ->

        val filteredChannels = remember(channelList, viewModel.groupFilterVersion) {
            getFilteredChannels(channelList, context)
        }

        val epgMap by produceState<Map<String, List<EpgProgram>>>(initialValue = emptyMap(), key1 = epgList) {
            value = withContext(Dispatchers.Default) {
                buildEpgIndex(epgList)
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        paddingValues
                    )
        ) {

            when (
                selectedCategory
            ) {

                MainCategory.HOME -> {

                    HomeScreen(
                        channels =
                            filteredChannels,

                        epgMap =
                            epgMap,

                        movies =
                            movies,

                        loading =
                            homeLoading,

                        onChannelSelected = {
                            viewModel.selectedChannelStreamUrl = it.streamUrl
                        },

                        onMovieSelected = {
                            viewModel.selectedCategory =
                                MainCategory.MOVIES
                        },

                        onGroupSelected = {
                            viewModel.selectedCategory =
                                MainCategory.LIVE
                        },

                        onSeeAllLive = {
                            viewModel.selectedCategory =
                                MainCategory.LIVE
                        },

                        onSeeAllMovies = {
                            viewModel.selectedCategory =
                                MainCategory.MOVIES
                        }
                    )
                }

                MainCategory.LIVE -> {

                    IptvLiveTvScreen(
                        player =
                            player,

                        channels =
                            filteredChannels,

                        selectedChannel =
                            selectedChannel,

                        onChannelSelected = {
                            viewModel.selectedChannelStreamUrl = it.streamUrl
                        },

                        onFullscreen = {
                            viewModel.showFullscreenPlayer = true
                        },

                        onChannelsLoaded = {
                            viewModel.channelList = it
                        },

                        onEpgLoaded = {
                            viewModel.epgList = it
                        },

                        onGroupsChanged = {
                            viewModel.groupFilterVersion++
                        }
                    )
                }

                MainCategory.GUIDE -> {

                    TvGuideScreen(
                        channels =
                            filteredChannels,

                        epgMap =
                            epgMap,

                        isLoading =
                            guideLoading,

                        onChannelSelected = {
                            viewModel.selectedChannelStreamUrl = it.streamUrl
                            viewModel.selectedCategory =
                                MainCategory.LIVE
                        }
                    )
                }

                MainCategory.MOVIES -> {

                    MovieScreen(player = player)
                }

                MainCategory.SERIES -> {

                    SeriesScreen(player = player)
                }

                MainCategory.PODCASTS -> {

                    PodcastScreen(player = player)
                }

                MainCategory.SOCIAL -> {

                    SocialScreen()
                }
            }
        }
    }

    // ========================================================
    // FULLSCREEN PLAYER (True Full-Screen Dialog)
    // ========================================================

    if (
        showFullscreenPlayer &&
        selectedChannel != null
    ) {

        Dialog(

            onDismissRequest = {
                viewModel.showFullscreenPlayer = false
            },

            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {

                Box(
                    modifier = Modifier.fillMaxSize()
                ) {

                    AndroidView(

                        factory = { ctx ->

                            PlayerView(ctx).apply {

                                this.player =
                                    player

                                useController =
                                    true
                            }
                        },

                        modifier =
                            Modifier.fillMaxSize()
                    )

                    IconButton(
                        onClick = {
                            viewModel.showFullscreenPlayer = false
                        },

                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.6f),
                                    RoundedCornerShape(24.dp)
                                )
                    ) {

                        Icon(
                            Icons.Default.Close,
                            contentDescription =
                                "Close",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// TOP NAVIGATION
// ============================================================

@Composable
fun BingeBoxTopBar(
    selectedCategory: MainCategory,
    onCategorySelected: (MainCategory) -> Unit
) {
    Surface(
        color = Color(0xFF0F1015)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BingeBox",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp, top = 12.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF8AB4F8))
                )
            }

            Spacer(Modifier.width(32.dp))

            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(MainCategory.values().toList()) { category ->
                    val selected = selectedCategory == category
                    val icon = when (category) {
                        MainCategory.HOME -> Icons.Default.Home
                        MainCategory.LIVE -> Icons.Default.LiveTv
                        MainCategory.GUIDE -> Icons.Default.Tv
                        MainCategory.MOVIES -> Icons.Default.Movie
                        MainCategory.SERIES -> Icons.Default.VideoLibrary
                        MainCategory.PODCASTS -> Icons.Default.Podcasts
                        MainCategory.SOCIAL -> Icons.Default.Public
                    }

                    Surface(
                        color = if (selected) Color(0xFFE2E2E9) else Color(0xFF1E1F28),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.clickable { onCategorySelected(category) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = category.title,
                                tint = if (selected) Color(0xFF0F1015) else Color(0xFF9EA3B0),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = category.title,
                                color = if (selected) Color(0xFF0F1015) else Color(0xFFD0D3E0),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = { onCategorySelected(MainCategory.LIVE) },
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E1F28))
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ============================================================
// HOME SCREEN
// ============================================================

// ============================================================
// UK TV APPS & LIVE CATEGORY BOXES
// ============================================================

data class UkTvApp(
    val name: String,
    val packageName: String,
    val iconUrl: String,
    val description: String,
    val brandColor: Color
)

val DEFAULT_UK_TV_APPS = listOf(
    UkTvApp(
        name = "YouTube",
        packageName = "com.google.android.youtube.tv",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/YouTube_full-color_icon_%282017%29.svg/512px-YouTube_full-color_icon_%282017%29.svg.png",
        description = "Videos, Music & Live Streams",
        brandColor = Color(0xFFFF0000)
    ),
    UkTvApp(
        name = "TikTok",
        packageName = "com.tiktok.tv",
        iconUrl = "https://upload.wikimedia.org/wikipedia/en/thumb/a/a9/TikTok_logo.svg/512px-TikTok_logo.svg.png",
        description = "Short-form Social Videos",
        brandColor = Color(0xFF111111)
    ),
    UkTvApp(
        name = "Netflix",
        packageName = "com.netflix.ninja",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/08/Netflix_2015_N_logo.svg/512px-Netflix_2015_N_logo.svg.png",
        description = "Movies, Series & Originals",
        brandColor = Color(0xFFE50914)
    ),
    UkTvApp(
        name = "Prime Video",
        packageName = "com.amazon.amazonvideo.livingroom",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/11/Amazon_Prime_Video_logo.svg/512px-Amazon_Prime_Video_logo.svg.png",
        description = "Amazon Prime Movies & TV",
        brandColor = Color(0xFF00A8E1)
    ),
    UkTvApp(
        name = "BBC iPlayer",
        packageName = "uk.co.bbc.iplayer",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/BBC_iPlayer_2021.svg/512px-BBC_iPlayer_2021.svg.png",
        description = "BBC Live TV & Catch Up",
        brandColor = Color(0xFFE40066)
    ),
    UkTvApp(
        name = "ITVX",
        packageName = "air.itv.itvplayer.android",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/13/ITVX_logo.svg/512px-ITVX_logo.svg.png",
        description = "ITV Shows, Movies & Live TV",
        brandColor = Color(0xFF4B0082)
    ),
    UkTvApp(
        name = "Channel 4",
        packageName = "com.channel4.onetv",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e0/Channel_4_2015_logo.svg/512px-Channel_4_2015_logo.svg.png",
        description = "Channel 4 Streaming",
        brandColor = Color(0xFF002B49)
    ),
    UkTvApp(
        name = "My5",
        packageName = "com.demand5",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/9/91/My5_logo.svg/512px-My5_logo.svg.png",
        description = "Channel 5 On Demand",
        brandColor = Color(0xFFFF6600)
    ),
    UkTvApp(
        name = "NOW",
        packageName = "com.bskyb.nowtv.uk",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/3/36/NOW_logo_2021.svg/512px-NOW_logo_2021.svg.png",
        description = "Sky Sports, Movies & TV",
        brandColor = Color(0xFF003399)
    ),
    UkTvApp(
        name = "BBC Sounds",
        packageName = "uk.co.bbc.sounds",
        iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d7/BBC_Sounds_logo_2021.svg/512px-BBC_Sounds_logo_2021.svg.png",
        description = "BBC Radio & Podcasts",
        brandColor = Color(0xFFB00067)
    )
)

fun getPackageVariants(app: UkTvApp): List<String> {
    return when (app.name) {
        "YouTube" -> listOf("com.google.android.youtube.tv", "com.google.android.youtube")
        "TikTok" -> listOf("com.tiktok.tv", "com.zhiliaoapp.musically")
        "Netflix" -> listOf("com.netflix.ninja", "com.netflix.mediaclient")
        "Prime Video" -> listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
        "BBC iPlayer" -> listOf("uk.co.bbc.iplayer", "com.bbc.iplayer.android", "uk.co.bbc.android.iplayer")
        "ITVX" -> listOf("air.itv.itvplayer.android", "com.itv.itvplayer", "uk.itv.itvplayer")
        "Channel 4" -> listOf("com.channel4.onetv", "uk.co.channel4.onetv")
        "My5" -> listOf("com.demand5", "uk.co.five.my5")
        "NOW" -> listOf("com.bskyb.nowtv.uk", "com.nowtv.uk")
        "BBC Sounds" -> listOf("uk.co.bbc.sounds", "com.bbc.sounds")
        else -> listOf(app.packageName)
    }
}

fun isAppInstalled(context: Context, app: UkTvApp): Boolean {
    val pm = context.packageManager
    val variants = getPackageVariants(app)
    for (pkg in variants) {
        try {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) return true
            pm.getPackageInfo(pkg, 0)
            return true
        } catch (_: Exception) {}
    }
    return false
}

fun launchOrInstallApp(context: Context, app: UkTvApp) {
    val pm = context.packageManager
    val variants = getPackageVariants(app)

    // 1. Direct Launch Intent Lookup
    for (pkg in variants) {
        try {
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return
            }
        } catch (_: Exception) {}
    }

    // 2. Query Installed Launcher Intents Fallback
    try {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        for (resolveInfo in apps) {
            val pkg = resolveInfo.activityInfo.packageName
            if (variants.contains(pkg) || pkg.contains(app.name.lowercase().replace(" ", ""))) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            }
        }
    } catch (_: Exception) {}

    // 3. Fallback to Play Store if not installed
    try {
        val encodedQuery = URLEncoder.encode(app.name, "UTF-8")
        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encodedQuery&c=apps")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(searchIntent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$encodedQuery&c=apps")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    } catch (_: Exception) {
        Toast.makeText(context, "Opening Play Store for ${app.name}...", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun UkLiveTvBox(
    title: String,
    subtitle: String,
    currentProgram: String? = null,
    icon: ImageVector,
    channelCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(240.dp)
            .height(145.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1F2B))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF23283B), Color(0xFF141724))
                        )
                    )
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF8AB4F8).copy(alpha = 0.85f),
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!currentProgram.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFFD93025),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "NOW",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = currentProgram,
                            color = Color(0xFF8AB4F8),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (channelCount > 0) "$channelCount channels • $subtitle" else subtitle,
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

fun getAppIconBitmap(context: Context, app: UkTvApp): ImageBitmap? {
    val pm = context.packageManager
    val variants = getPackageVariants(app)
    for (pkg in variants) {
        try {
            val drawable = pm.getApplicationIcon(pkg)
            return drawable.toBitmap().asImageBitmap()
        } catch (_: Exception) {}
    }
    return null
}

@Composable
fun UkTvAppCard(
    app: UkTvApp,
    isInstalled: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val installedBitmap = remember(app, isInstalled) { getAppIconBitmap(context, app) }

    Card(
        modifier = Modifier
            .width(180.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1F2B))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(app.brandColor, app.brandColor.copy(alpha = 0.75f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (installedBitmap != null) {
                    Image(
                        bitmap = installedBitmap,
                        contentDescription = app.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    AsyncImage(
                        model = app.iconUrl,
                        contentDescription = app.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = app.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(4.dp))

            Surface(
                color = if (isInstalled) Color(0xFF1E3A2B) else Color(0xFF252A38),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = if (isInstalled) "Open App" else "Install",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color = if (isInstalled) Color(0xFF4CAF50) else Color(0xFF8AB4F8),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ============================================================
// HOME SCREEN
// ============================================================

@Composable
fun HomeScreen(
    channels: List<LiveChannel>,
    epgMap: Map<String, List<EpgProgram>>,
    movies: List<Movie>,
    loading: Boolean,
    onChannelSelected: (LiveChannel) -> Unit,
    onMovieSelected: () -> Unit,
    onGroupSelected: (String) -> Unit,
    onSeeAllLive: () -> Unit,
    onSeeAllMovies: () -> Unit
) {
    val context = LocalContext.current
    val heroMovie = movies.firstOrNull()
    val heroChannel = channels.firstOrNull()
    val heroProgram = heroChannel?.let { currentProgramForChannel(it, epgMap) }

    val terrestrialChannel = channels.firstOrNull {
        val g = normalizeIptvGroup(it.group)
        g.contains("UK", ignoreCase = true) || g.contains("BBC", ignoreCase = true) || g.contains("GENERAL", ignoreCase = true)
    } ?: channels.firstOrNull()
    val terrestrialProg = terrestrialChannel?.let { currentProgramForChannel(it, epgMap)?.title }

    val sportsChannel = channels.firstOrNull {
        normalizeIptvGroup(it.group).contains("SPORT", ignoreCase = true)
    }
    val sportsProg = sportsChannel?.let { currentProgramForChannel(it, epgMap)?.title }

    val movieChannel = channels.firstOrNull {
        val g = normalizeIptvGroup(it.group)
        g.contains("MOVIE", ignoreCase = true) || g.contains("CINEMA", ignoreCase = true)
    }
    val movieProg = movieChannel?.let { currentProgramForChannel(it, epgMap)?.title }

    val newsChannel = channels.firstOrNull {
        normalizeIptvGroup(it.group).contains("NEWS", ignoreCase = true)
    }
    val newsProg = newsChannel?.let { currentProgramForChannel(it, epgMap)?.title }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 50.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ====================================================
        // HERO: Latest Movies & TV Releases with Rating & Synopsis
        // ====================================================
        item {
            HomeHero(
                channel = heroChannel,
                program = heroProgram,
                movie = heroMovie,
                loading = loading,
                onWatch = {
                    if (heroMovie?.streamUrl.orEmpty().isNotBlank()) {
                        onMovieSelected()
                    } else if (heroChannel != null) {
                        onChannelSelected(heroChannel)
                    } else {
                        onSeeAllMovies()
                    }
                }
            )
        }

        // ====================================================
        // UK CATCH-UP & TV APPS
        // ====================================================
        item {
            HomeSectionHeader(
                title = "UK Catch-Up & TV Apps",
                subtitle = "Launch or install official UK streaming apps"
            )

            Spacer(Modifier.height(10.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(DEFAULT_UK_TV_APPS) { app ->
                    val installed = remember(app.packageName) { isAppInstalled(context, app) }
                    UkTvAppCard(
                        app = app,
                        isInstalled = installed,
                        onClick = { launchOrInstallApp(context, app) }
                    )
                }
            }
        }

        // ====================================================
        // UK LIVE TV CATEGORY BOXES
        // ====================================================
        if (channels.isNotEmpty()) {
            item {
                HomeSectionHeader(
                    title = "UK Live TV Category Boxes",
                    subtitle = "Browse UK channels by category",
                    onSeeAll = onSeeAllLive
                )

                Spacer(Modifier.height(10.dp))

                val groups = channels.map { normalizeIptvGroup(it.group) }.distinct().sorted()

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        UkLiveTvBox(
                            title = "UK Terrestrial & General",
                            subtitle = "BBC, ITV, Ch4, Channel 5",
                            currentProgram = terrestrialProg,
                            icon = Icons.Default.Tv,
                            channelCount = channels.count { normalizeIptvGroup(it.group).contains("UK", ignoreCase = true) || normalizeIptvGroup(it.group).contains("BBC", ignoreCase = true) },
                            onClick = onSeeAllLive
                        )
                    }
                    item {
                        UkLiveTvBox(
                            title = "UK Sports & Football",
                            subtitle = "Sky Sports, TNT Sports",
                            currentProgram = sportsProg,
                            icon = Icons.Default.Sports,
                            channelCount = channels.count { normalizeIptvGroup(it.group).contains("SPORT", ignoreCase = true) },
                            onClick = onSeeAllLive
                        )
                    }
                    item {
                        UkLiveTvBox(
                            title = "UK Cinema & Movies",
                            subtitle = "Sky Cinema, Film4",
                            currentProgram = movieProg,
                            icon = Icons.Default.Movie,
                            channelCount = channels.count { normalizeIptvGroup(it.group).contains("MOVIE", ignoreCase = true) || normalizeIptvGroup(it.group).contains("CINEMA", ignoreCase = true) },
                            onClick = onSeeAllMovies
                        )
                    }
                    item {
                        UkLiveTvBox(
                            title = "UK News & Factual",
                            subtitle = "BBC News, Sky News",
                            currentProgram = newsProg,
                            icon = Icons.Default.Newspaper,
                            channelCount = channels.count { normalizeIptvGroup(it.group).contains("NEWS", ignoreCase = true) },
                            onClick = onSeeAllLive
                        )
                    }

                    itemsIndexed(groups) { _, group ->
                        GroupCard(
                            group = group,
                            count = channels.count { normalizeIptvGroup(it.group) == group },
                            onClick = { onGroupSelected(group) }
                        )
                    }
                }
            }
        }

        // ====================================================
        // TRENDING MOVIES & RELEASES
        // ====================================================
        if (movies.isNotEmpty()) {
            item {
                HomeSectionHeader(
                    title = "Latest Movie Releases",
                    subtitle = "Popular films with ratings & synopsis",
                    onSeeAll = onSeeAllMovies
                )

                Spacer(Modifier.height(10.dp))

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(
                        movies.take(15),
                        key = { index, movie -> "${movie.title}_$index" }
                    ) { _, movie ->
                        HomeMovieCard(
                            movie = movie,
                            onClick = onSeeAllMovies
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// HERO
// ============================================================

@Composable
fun HomeHero(
    channel: LiveChannel?,
    program: EpgProgram?,
    movie: Movie?,
    loading: Boolean,
    onWatch: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF212534),
                        Color(0xFF13151F),
                        Color(0xFF0B0C10)
                    )
                )
            )
    ) {
        if (movie != null && (movie.backdropUrl.isNotBlank() || movie.posterUrl.isNotBlank())) {
            AsyncImage(
                model = movie.backdropUrl.ifBlank { movie.posterUrl },
                contentDescription = movie.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF0F1015).copy(alpha = 0.95f),
                                Color(0xFF0F1015).copy(alpha = 0.65f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xFF0F1015).copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp)
                    .widthIn(max = 640.dp)
            ) {
                Surface(
                    color = Color(0xFF8AB4F8).copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, Color(0xFF8AB4F8)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FEATURED MOVIE",
                            color = Color(0xFF8AB4F8),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = movie.title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (movie.voteAverage > 0) {
                        Text(
                            text = "★ ${String.format(Locale.US, "%.1f", movie.voteAverage)}",
                            color = Color(0xFFFFD700),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    if (movie.releaseDate.isNotBlank()) {
                        Text(
                            text = movie.releaseDate,
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (movie.overview.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = movie.overview,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onWatch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Watch Now", fontWeight = FontWeight.Bold)
                }
            }
        } else if (channel != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp)
                    .widthIn(max = 640.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(channel = channel, size = 68.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Surface(
                            color = Color(0xFFD93025),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "LIVE TV",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = channel.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = program?.title ?: "Live Broadcast",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!program?.description.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = program?.description ?: "",
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onWatch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Watch Live", fontWeight = FontWeight.Bold)
                }
            }
        } else if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF8AB4F8))
                    Spacer(Modifier.height(16.dp))
                    Text("Loading BingeBox...", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp)
            ) {
                Text(
                    text = "BingeBox",
                    color = Color.White,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Your Movies, TV Series, Live TV & Podcasts in one place.",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

// ============================================================
// SECTION HEADER
// ============================================================

@Composable
fun HomeSectionHeader(
    title: String,
    subtitle: String,
    onSeeAll: (() -> Unit)? = null
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp
                ),

        verticalAlignment =
            Alignment.Bottom
    ) {

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text = title,
                color = Color.White,
                style =
                    MaterialTheme
                        .typography
                        .headlineSmall,
                fontWeight =
                    FontWeight.ExtraBold
            )

            Text(
                text = subtitle,
                color =
                    Color.White.copy(
                        alpha = 0.55f
                    ),
                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )
        }

        if (onSeeAll != null) {

            TextButton(
                onClick = onSeeAll
            ) {

                Text(
                    "See all"
                )

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription =
                        null
                )
            }
        }
    }
}

// ============================================================
// LIVE CARD
// ============================================================

@Composable
fun HomeLiveCard(
    channel: LiveChannel,
    program: EpgProgram?,
    onClick: () -> Unit
) {

    Card(
        modifier =
            Modifier
                .width(190.dp)
                .clickable(
                    onClick = onClick
                ),

        shape =
            RoundedCornerShape(
                18.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFF17171E)
            )
    ) {

        Column(
            modifier =
                Modifier.padding(12.dp)
        ) {

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(
                            RoundedCornerShape(
                                13.dp
                            )
                        )
                        .background(
                            Color(0xFF25252D)
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                ChannelLogo(
                    channel =
                        channel,

                    size =
                        74.dp
                )

                Surface(
                    modifier =
                        Modifier
                            .align(
                                Alignment.TopStart
                            )
                            .padding(7.dp),

                    color =
                        Color(0xFFD93025),

                    shape =
                        RoundedCornerShape(
                            5.dp
                        )
                ) {

                    Text(
                        text = "LIVE",

                        modifier =
                            Modifier.padding(
                                horizontal = 6.dp,
                                vertical = 3.dp
                            ),

                        color =
                            Color.White,

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        fontWeight =
                            FontWeight.ExtraBold
                    )
                }
            }

            Spacer(
                Modifier.height(10.dp)
            )

            Text(
                text =
                    channel.name,

                color =
                    Color.White,

                fontWeight =
                    FontWeight.Bold,

                maxLines = 1,

                overflow =
                    TextOverflow.Ellipsis
            )

            Spacer(
                Modifier.height(4.dp)
            )

            Text(
                text =
                    program?.title
                        ?: "Live TV",

                color =
                    Color.White.copy(
                        alpha = 0.58f
                    ),

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                maxLines = 2,

                overflow =
                    TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================
// GROUP CARD
// ============================================================

@Composable
fun GroupCard(
    group: String,
    count: Int,
    onClick: () -> Unit
) {

    Card(
        modifier =
            Modifier
                .width(190.dp)
                .height(105.dp)
                .clickable(
                    onClick = onClick
                ),

        shape =
            RoundedCornerShape(
                18.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFF1B1C25)
            )
    ) {

        Box(
            modifier =
                Modifier.fillMaxSize()
        ) {

            Icon(
                Icons.Default.Folder,
                contentDescription =
                    null,

                tint =
                    Color(0xFF8AB4F8),

                modifier =
                    Modifier
                        .size(52.dp)
                        .align(
                            Alignment.TopEnd
                        )
                        .padding(12.dp)
            )

            Column(
                modifier =
                    Modifier
                        .align(
                            Alignment.BottomStart
                        )
                        .padding(15.dp)
            ) {

                Text(
                    text = group,
                    color =
                        Color.White,
                    fontWeight =
                        FontWeight.Bold,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis
                )

                Text(
                    text =
                        "$count channels",

                    color =
                        Color.White.copy(
                            alpha = 0.55f
                        ),

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }
    }
}

// ============================================================
// MOVIE CARD
// ============================================================

@Composable
fun HomeMovieCard(
    movie: Movie,
    onClick: () -> Unit
) {

    Card(
        modifier =
            Modifier
                .width(145.dp)
                .clickable(
                    onClick = onClick
                ),

        shape =
            RoundedCornerShape(
                16.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFF17171E)
            )
    ) {

        Column {

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(205.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp
                            )
                        )
            ) {

                if (
                    movie.posterUrl.isNotBlank()
                ) {

                    AsyncImage(
                        model =
                            movie.posterUrl,

                        contentDescription =
                            movie.title,

                        modifier =
                            Modifier.fillMaxSize(),

                        contentScale =
                            ContentScale.Crop
                    )
                } else {

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Color.DarkGray
                                )
                    )
                }

                if (
                    movie.voteAverage > 0
                ) {

                    Surface(
                        modifier =
                            Modifier
                                .align(
                                    Alignment.TopEnd
                                )
                                .padding(7.dp),

                        color =
                            Color.Black.copy(
                                alpha = 0.75f
                            ),

                        shape =
                            RoundedCornerShape(
                                6.dp
                            )
                    ) {

                        Text(
                            text =
                                "★ ${
                                    String.format(
                                        Locale.US,
                                        "%.1f",
                                        movie.voteAverage
                                    )
                                }",

                            modifier =
                                Modifier.padding(
                                    horizontal = 6.dp,
                                    vertical = 4.dp
                                ),

                            color =
                                Color.White,

                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,

                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }
            }

            Text(
                text =
                    movie.title,

                color =
                    Color.White,

                fontWeight =
                    FontWeight.Bold,

                modifier =
                    Modifier.padding(
                        10.dp
                    ),

                maxLines = 2,

                overflow =
                    TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================
// QUICK ACCESS
// ============================================================

@Composable
fun HomeQuickAccess(
    onLive: () -> Unit,
    onMovies: () -> Unit
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp
                ),

        horizontalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        QuickAccessCard(
            modifier =
                Modifier.weight(1f),

            icon =
                Icons.Default.LiveTv,

            title =
                "Live TV",

            subtitle =
                "Watch channels",

            onClick =
                onLive
        )

        QuickAccessCard(
            modifier =
                Modifier.weight(1f),

            icon =
                Icons.Default.Movie,

            title =
                "Movies",

            subtitle =
                "Browse films",

            onClick =
                onMovies
        )
    }
}

@Composable
fun QuickAccessCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {

    Card(
        modifier =
            modifier.clickable(
                onClick = onClick
            ),

        shape =
            RoundedCornerShape(
                18.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFF17171E)
            )
    ) {

        Row(
            modifier =
                Modifier.padding(18.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Icon(
                imageVector =
                    icon,

                contentDescription =
                    null,

                tint =
                    Color(0xFF8AB4F8),

                modifier =
                    Modifier.size(36.dp)
            )

            Spacer(
                Modifier.width(12.dp)
            )

            Column {

                Text(
                    text = title,
                    color = Color.White,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text = subtitle,
                    color =
                        Color.White.copy(
                            alpha = 0.55f
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }
    }
}

// ============================================================
// EMPTY HOME
// ============================================================

@Composable
fun EmptyHomeCard(
    onOpenLive: () -> Unit
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp
                ),

        shape =
            RoundedCornerShape(
                22.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFF17171E)
            )
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(28.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Icon(
                Icons.Default.LiveTv,
                contentDescription =
                    null,

                modifier =
                    Modifier.size(55.dp),

                tint =
                    Color(0xFF8AB4F8)
            )

            Spacer(
                Modifier.height(12.dp)
            )

            Text(
                text =
                    "Let's get BingeBox connected",

                color =
                    Color.White,

                style =
                    MaterialTheme
                        .typography
                        .headlineSmall,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                Modifier.height(6.dp)
            )

            Text(
                text =
                    "Connect your IPTV provider to populate Live TV, groups and the TV Guide.",

                color =
                    Color.White.copy(
                        alpha = 0.6f
                    )
            )

            Spacer(
                Modifier.height(18.dp)
            )

            Button(
                onClick =
                    onOpenLive
            ) {

                Icon(
                    Icons.Default.Settings,
                    contentDescription =
                        null
                )

                Spacer(
                    Modifier.width(6.dp)
                )

                Text(
                    "Set Up IPTV"
                )
            }
        }
    }
}

// ============================================================
// IPTV LIVE TV
// ============================================================

fun getFavoriteChannels(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
    return prefs.getStringSet("favorite_channels", emptySet()) ?: emptySet()
}

fun toggleFavoriteChannel(context: Context, streamUrl: String): Boolean {
    val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
    val set = (prefs.getStringSet("favorite_channels", emptySet()) ?: emptySet()).toMutableSet()
    val isFav = if (set.contains(streamUrl)) {
        set.remove(streamUrl)
        false
    } else {
        set.add(streamUrl)
        true
    }
    prefs.edit().putStringSet("favorite_channels", set).apply()
    return isFav
}

@Composable
fun IptvLiveTvScreen(
    player: ExoPlayer,
    channels: List<LiveChannel>,
    selectedChannel: LiveChannel?,
    onChannelSelected:
        (LiveChannel) -> Unit,
    onFullscreen: () -> Unit,
    onChannelsLoaded:
        (List<LiveChannel>) -> Unit,
    onEpgLoaded:
        (List<EpgProgram>) -> Unit,
    onGroupsChanged: () -> Unit = {}
) {

    val context =
        androidx.compose.ui.platform
            .LocalContext.current

    val scope =
        rememberCoroutineScope()

    var selectedGroup by remember {
        mutableStateOf(
            "All Channels"
        )
    }

    var showSetupDialog by remember {
        mutableStateOf(false)
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    var statusMessage by remember {
        mutableStateOf(
            "No IPTV source loaded"
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var favoriteSet by remember { mutableStateOf(getFavoriteChannels(context)) }

    val availableGroups =
        remember(channels, favoriteSet) {
            val groups =
                channels
                    .map { channel ->
                        normalizeIptvGroup(channel.group)
                    }
                    .filter { group ->
                        group.isNotBlank()
                    }
                    .distinct()
                    .sorted()

            if (favoriteSet.isNotEmpty()) {
                listOf("★ Favorites", "All Channels") + groups
            } else {
                listOf("All Channels") + groups
            }
        }

    LaunchedEffect(channels) {
        if (channels.isNotEmpty()) {
            statusMessage =
                "${channels.size} channels loaded"
        } else {
            statusMessage =
                "No IPTV source loaded"
        }

        if (
            selectedGroup != "All Channels" &&
            selectedGroup != "★ Favorites" &&
            selectedGroup !in availableGroups
        ) {
            selectedGroup = "All Channels"
        }
    }

    val visibleChannels =
        remember(
            channels,
            selectedGroup,
            searchQuery,
            favoriteSet
        ) {
            var list = when (selectedGroup) {
                "★ Favorites" -> channels.filter { it.streamUrl in favoriteSet }
                "All Channels" -> channels
                else -> channels.filter { channel ->
                    normalizeIptvGroup(channel.group) == selectedGroup
                }
            }
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase(Locale.getDefault())
                list = list.filter {
                    it.name.lowercase(Locale.getDefault()).contains(q) ||
                    it.group.lowercase(Locale.getDefault()).contains(q)
                }
            }
            list
        }

    // ========================================================
    // PLAY CHANNEL
    // ========================================================

    LaunchedEffect(
        selectedChannel
    ) {

        selectedChannel?.let { channel ->

            try {

                player.setMediaItem(
                    MediaItem.fromUri(
                        channel.streamUrl
                    )
                )

                player.prepare()

                player.playWhenReady =
                    true

            } catch (e: Exception) {

                Log.e(
                    "BingeBox",
                    "Player error",
                    e
                )

                Toast.makeText(
                    context,
                    "Unable to play ${channel.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
    ) {

        if (
            selectedChannel != null
        ) {

            Text(
                text =
                    selectedChannel.name,

                style =
                    MaterialTheme
                        .typography
                        .titleLarge,

                fontWeight =
                    FontWeight.Bold,

                modifier =
                    Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    )
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(
                            Color.Black
                        )
            ) {

                AndroidView(
                    factory = { ctx ->

                        PlayerView(ctx).apply {

                            this.player =
                                player

                            useController =
                                true
                        }
                    },

                    modifier =
                        Modifier.fillMaxSize()
                )

                IconButton(
                    onClick =
                        onFullscreen,

                    modifier =
                        Modifier
                            .align(
                                Alignment.BottomEnd
                            )
                            .padding(8.dp)
                ) {

                    Icon(
                        Icons.Default.Fullscreen,
                        contentDescription =
                            "Fullscreen"
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 6.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                label = { Text("Search Live Channels") },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                }
            )

            Spacer(Modifier.width(8.dp))

            IconButton(
                onClick = {
                    showSetupDialog =
                        true
                }
            ) {

                Icon(
                    Icons.Default.Settings,
                    contentDescription =
                        "IPTV settings"
                )
            }
        }

        HorizontalDivider()

        if (
            availableGroups.isNotEmpty()
        ) {

            LazyRow(
                contentPadding =
                    PaddingValues(
                        horizontal = 16.dp,
                        vertical = 10.dp
                    ),

                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                itemsIndexed(
                    availableGroups,
                    key = { index, group ->
                        "${group}_$index"
                    }
                ) { _, group ->

                    FilterChip(
                        selected =
                            selectedGroup ==
                                    group,

                        onClick = {
                            selectedGroup =
                                group
                        },

                        label = {
                            Text(group)
                        }
                    )
                }
            }
        }

        if (isLoading) {

            Box(
                modifier =
                    Modifier.fillMaxSize(),

                contentAlignment =
                    Alignment.Center
            ) {

                CircularProgressIndicator()
            }

        } else {

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                itemsIndexed(
                    visibleChannels,
                    key = { index, channel ->
                        "${channel.group}_${channel.streamUrl}_${channel.name}_$index"
                    }
                ) { _, channel ->

                    ChannelCard(
                        channel =
                            channel,

                        isSelected =
                            selectedChannel
                                ?.streamUrl ==
                                    channel.streamUrl,

                        isFavorite = channel.streamUrl in favoriteSet,

                        onFavoriteToggle = {
                            toggleFavoriteChannel(context, channel.streamUrl)
                            favoriteSet = getFavoriteChannels(context)
                        },

                        onClick = {
                            onChannelSelected(
                                channel
                            )
                        }
                    )
                }
            }
        }
    }

    // ========================================================
    // IPTV SETUP
    // ========================================================

    if (
        showSetupDialog
    ) {

        IptvSetupDialog(

            onDismiss = {
                showSetupDialog =
                    false
            },

            onGroupsChanged = onGroupsChanged,

            onLoad = {
                    connection,
                    playlistUrl,
                    epgUrl ->

                showSetupDialog =
                    false

                isLoading =
                    true

                selectedGroup =
                    "All Channels"

                scope.launch {

                    try {

                        val prefs =
                            context.getSharedPreferences(
                                "bingebox_iptv",
                                Context.MODE_PRIVATE
                            )

                        prefs.edit()
                            .putString(
                                "xtream_server",
                                connection
                                    ?.serverUrl
                                    ?: ""
                            )
                            .putString(
                                "xtream_username",
                                connection
                                    ?.username
                                    ?: ""
                            )
                            .putString(
                                "xtream_password",
                                connection
                                    ?.password
                                    ?: ""
                            )
                            .putString(
                                "playlist_url",
                                playlistUrl
                            )
                            .putString(
                                "epg_url",
                                epgUrl
                            )
                            .apply()

                        var channels =
                            emptyList<LiveChannel>()

                        var epg =
                            emptyList<EpgProgram>()

                        var usedXtream =
                            false

                        if (
                            connection != null
                        ) {

                            channels =
                                withContext(
                                    Dispatchers.IO
                                ) {

                                    fetchXtreamChannels(
                                        connection
                                    )
                                }

                            if (
                                channels.isNotEmpty()
                            ) {

                                usedXtream =
                                    true

                                epg =
                                    fetchXtreamEpgForChannels(
                                        connection,
                                        channels
                                    )
                            }
                        }

                        if (
                            channels.isEmpty() &&
                            playlistUrl.isNotBlank()
                        ) {

                            val result =
                                withContext(
                                    Dispatchers.IO
                                ) {

                                    parseM3uPlaylist(
                                        playlistUrl
                                    )
                                }

                            channels =
                                result.channels

                            epg =
                                result.epg
                        }

                        if (
                            epgUrl.isNotBlank()
                        ) {

                            try {

                                val custom =
                                    withContext(
                                        Dispatchers.IO
                                    ) {

                                        parseXmltvEpg(
                                            epgUrl
                                        )
                                    }

                                if (
                                    custom.isNotEmpty()
                                ) {

                                    epg =
                                        custom
                                }

                            } catch (e: Exception) {

                                Log.e(
                                    "BingeBox",
                                    "Custom EPG failed",
                                    e
                                )
                            }
                        }

                        if (channels.isNotEmpty()) {
                            val allGroups = channels.map { normalizeIptvGroup(it.group) }.toSet()
                            prefs.edit().putStringSet("all_groups", allGroups).apply()
                        }

                        onChannelsLoaded(
                            channels
                        )

                        onEpgLoaded(
                            epg
                        )

                        statusMessage =
                            when {

                                usedXtream &&
                                        epg.isNotEmpty() ->
                                    "${channels.size} channels • ${epg.size} EPG programmes"

                                usedXtream ->
                                    "${channels.size} channels • Xtream connected, no EPG returned"

                                epg.isNotEmpty() ->
                                    "${channels.size} channels • ${epg.size} EPG programmes"

                                channels.isNotEmpty() ->
                                    "${channels.size} channels • No EPG supplied"

                                else ->
                                    "No IPTV channels found"
                            }

                        isLoading =
                            false

                    } catch (e: Exception) {

                        Log.e(
                            "BingeBox",
                            "IPTV setup failed",
                            e
                        )

                        statusMessage =
                            "IPTV connection failed"

                        isLoading =
                            false

                        Toast.makeText(
                            context,
                            "Unable to connect to IPTV provider",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }
}

// ============================================================
// CHANNEL CARD
// ============================================================

@Composable
fun ChannelCard(
    channel: LiveChannel,
    isSelected: Boolean,
    isFavorite: Boolean = false,
    onFavoriteToggle: (() -> Unit)? = null,
    onClick: () -> Unit
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onClick
                ),

        shape =
            RoundedCornerShape(
                14.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        Color(0xFF252A38)
                    } else {
                        Color(0xFF17171E)
                    }
            )
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            ChannelLogo(
                channel =
                    channel
            )

            Spacer(
                Modifier.width(14.dp)
            )

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text =
                        channel.name,

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,

                    fontWeight =
                        FontWeight.SemiBold,

                    maxLines = 1,

                    overflow =
                        TextOverflow.Ellipsis
                )

                if (
                    channel.group.isNotBlank()
                ) {

                    Text(
                        text =
                            normalizeIptvGroup(
                                channel.group
                            ),

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }
            }

            if (onFavoriteToggle != null) {
                IconButton(onClick = onFavoriteToggle) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFFFD700) else Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            if (
                isSelected
            ) {

                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription =
                        "Playing",
                    tint = Color(0xFF8AB4F8)
                )
            }
        }
    }
}

// ============================================================
// IPTV SETUP DIALOG
// ============================================================

@Composable
fun IptvSetupDialog(
    onDismiss: () -> Unit,
    onLoad: (
        connection: XtreamConnection?,
        playlistUrl: String,
        epgUrl: String
    ) -> Unit,
    onGroupsChanged: () -> Unit = {}
) {

    val context =
        androidx.compose.ui.platform
            .LocalContext.current

    val prefs =
        remember {

            context.getSharedPreferences(
                "bingebox_iptv",
                Context.MODE_PRIVATE
            )
        }

    var server by remember {
        mutableStateOf(
            prefs.getString(
                "xtream_server",
                ""
            ).orEmpty()
        )
    }

    var username by remember {
        mutableStateOf(
            prefs.getString(
                "xtream_username",
                ""
            ).orEmpty()
        )
    }

    var password by remember {
        mutableStateOf(
            prefs.getString(
                "xtream_password",
                ""
            ).orEmpty()
        )
    }

    var playlistUrl by remember {
        mutableStateOf(
            prefs.getString(
                "playlist_url",
                ""
            ).orEmpty()
        )
    }

    var epgUrl by remember {
        mutableStateOf(
            prefs.getString(
                "epg_url",
                ""
            ).orEmpty()
        )
    }

    var disabledGroups by remember {
        mutableStateOf<Set<String>>(
            prefs.getStringSet("disabled_groups", emptySet()) ?: emptySet()
        )
    }

    AlertDialog(

        onDismissRequest =
            onDismiss,

        title = {

            Text(
                "IPTV Setup",
                fontWeight =
                    FontWeight.Bold
            )
        },

        text = {

            LazyColumn(
                modifier =
                    Modifier.height(
                        430.dp
                    )
            ) {

                item {

                    Text(
                        text =
                            "Xtream Codes",

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(4.dp)
                    )

                    Text(
                        text =
                            "Connect BingeBox to your IPTV provider.",

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    Spacer(
                        Modifier.height(10.dp)
                    )

                    OutlinedTextField(
                        value =
                            server,

                        onValueChange = {
                            server = it
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        label = {
                            Text(
                                "Server Hostname"
                            )
                        },

                        placeholder = {
                            Text(
                                "http://server:8080"
                            )
                        },

                        singleLine = true
                    )

                    Spacer(
                        Modifier.height(10.dp)
                    )

                    OutlinedTextField(
                        value =
                            username,

                        onValueChange = {
                            username = it
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        label = {
                            Text(
                                "Server Username"
                            )
                        },

                        singleLine = true
                    )

                    Spacer(
                        Modifier.height(10.dp)
                    )

                    OutlinedTextField(
                        value =
                            password,

                        onValueChange = {
                            password = it
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        label = {
                            Text(
                                "Server Password"
                            )
                        },

                        singleLine = true
                    )

                    Spacer(
                        Modifier.height(18.dp)
                    )

                    HorizontalDivider()

                    Spacer(
                        Modifier.height(14.dp)
                    )

                    Text(
                        text =
                            "Optional M3U / XMLTV",

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    OutlinedTextField(
                        value =
                            playlistUrl,

                        onValueChange = {
                            playlistUrl = it
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        label = {
                            Text(
                                "M3U Playlist URL"
                            )
                        },

                        singleLine = true
                    )

                    Spacer(
                        Modifier.height(10.dp)
                    )

                    OutlinedTextField(
                        value =
                            epgUrl,

                        onValueChange = {
                            epgUrl = it
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        label = {
                            Text(
                                "Optional XMLTV EPG URL"
                            )
                        },

                        singleLine = true
                    )

                    Spacer(
                        Modifier.height(18.dp)
                    )

                    HorizontalDivider()

                    Spacer(
                        Modifier.height(14.dp)
                    )

                    Text(
                        text = "Filter Channel Groups",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    val allGroups = remember {
                        prefs.getStringSet("all_groups", emptySet())?.sorted() ?: emptyList()
                    }

                    if (allGroups.isEmpty()) {
                        Text(
                            text = "Connect to IPTV to load and filter channel groups.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        allGroups.forEach { group ->
                            val isEnabled = group !in disabledGroups
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        disabledGroups = if (isEnabled) {
                                            disabledGroups + group
                                        } else {
                                            disabledGroups - group
                                        }
                                        prefs.edit().putStringSet("disabled_groups", disabledGroups).apply()
                                        onGroupsChanged()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        disabledGroups = if (checked) {
                                            disabledGroups - group
                                        } else {
                                            disabledGroups + group
                                        }
                                        prefs.edit().putStringSet("disabled_groups", disabledGroups).apply()
                                        onGroupsChanged()
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(text = group, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },

        confirmButton = {

            Button(
                onClick = {

                    val cleanServer =
                        server
                            .trim()
                            .trimEnd('/')

                    prefs.edit()
                        .putString("xtream_server", cleanServer)
                        .putString("xtream_username", username.trim())
                        .putString("xtream_password", password)
                        .putString("playlist_url", playlistUrl.trim())
                        .putString("epg_url", epgUrl.trim())
                        .putStringSet("disabled_groups", disabledGroups)
                        .apply()

                    onGroupsChanged()

                    val connection =
                        if (
                            cleanServer.isNotBlank() &&
                            username.trim()
                                .isNotBlank() &&
                            password.isNotBlank()
                        ) {

                            XtreamConnection(
                                serverUrl =
                                    cleanServer,

                                username =
                                    username.trim(),

                                password =
                                    password
                            )

                        } else {

                            null
                        }

                    if (
                        connection != null ||
                        playlistUrl
                            .trim()
                            .isNotBlank()
                    ) {

                        onLoad(
                            connection,
                            playlistUrl.trim(),
                            epgUrl.trim()
                        )
                    }
                }
            ) {

                Text(
                    "Connect"
                )
            }
        },

        dismissButton = {

            TextButton(
                onClick =
                    onDismiss
            ) {

                Text(
                    "Cancel"
                )
            }
        }
    )
}

// ============================================================
// TV GUIDE
// ============================================================

// ============================================================
// TV GUIDE (EPG GRID)
// ============================================================

@Composable
fun TvGuideScreen(
    channels: List<LiveChannel>,
    epgMap: Map<String, List<EpgProgram>>,
    isLoading: Boolean,
    onChannelSelected: (LiveChannel) -> Unit
) {
    var selectedGroup by remember { mutableStateOf("All Channels") }
    var selectedProgramInfo by remember { mutableStateOf<Pair<LiveChannel, EpgProgram>?>(null) }

    val groups = remember(channels) {
        listOf("All Channels") + channels.map { normalizeIptvGroup(it.group) }.distinct().sorted()
    }

    LaunchedEffect(groups) {
        if (selectedGroup !in groups) {
            selectedGroup = "All Channels"
        }
    }

    val visibleChannels = remember(channels, selectedGroup) {
        if (selectedGroup == "All Channels") {
            channels
        } else {
            channels.filter { normalizeIptvGroup(it.group) == selectedGroup }
        }
    }

    val now = System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val timelineStart = calendar.timeInMillis
    val slotDurationMs = 30 * 60 * 1000L // 30 minutes per slot
    val totalSlots = 12 // 6 hours total
    val slotWidth = 160.dp

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B0F))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TV Guide",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = SimpleDateFormat("EEEE, d MMMM • HH:mm", Locale.getDefault()).format(Date(now)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        if (groups.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(groups, key = { index, g -> "${g}_$index" }) { _, group ->
                    FilterChip(
                        selected = selectedGroup == group,
                        onClick = { selectedGroup = group },
                        label = { Text(group) }
                    )
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF8AB4F8))
                    Spacer(Modifier.height(14.dp))
                    Text("Loading TV Guide...", color = Color.White)
                }
            }
            return
        }

        if (visibleChannels.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No channels found in this category.", color = Color.White.copy(alpha = 0.7f))
            }
            return
        }

        val horizontalScrollState = rememberScrollState()

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF15151B))
                ) {
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .height(48.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "Channels",
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        for (i in 0 until totalSlots) {
                            val slotTime = timelineStart + (i * slotDurationMs)
                            Box(
                                modifier = Modifier
                                    .width(slotWidth)
                                    .height(48.dp)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = formatProgramTime(slotTime),
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8AB4F8),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            }

            itemsIndexed(
                visibleChannels,
                key = { index, channel -> "${channel.streamUrl}_${channel.name}_$index" }
            ) { index, channel ->
                val programs = findProgramsForChannel(channel, epgMap)
                val channelNum = String.format(Locale.US, "%03d", index + 1)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(if (index % 2 == 0) Color(0xFF101015) else Color(0xFF15151B))
                        .clickable { onChannelSelected(channel) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .width(400.dp)
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = channelNum,
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(10.dp))
                        ChannelLogo(channel = channel, size = 48.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = channel.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScrollState),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val timelineEnd = timelineStart + (totalSlots * slotDurationMs)
                        val relevantPrograms = programs.filter { it.endTime > timelineStart && it.startTime < timelineEnd }

                        if (relevantPrograms.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .width(slotWidth * totalSlots)
                                    .fillMaxHeight()
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = "Live Broadcast • No Epg Schedule",
                                    color = Color.White.copy(alpha = 0.4f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            relevantPrograms.forEach { program ->
                                val progStart = maxOf(program.startTime, timelineStart)
                                val progEnd = minOf(program.endTime, timelineEnd)
                                
                                if (progEnd > progStart) {
                                    val durationMs = progEnd - progStart
                                    val width = (durationMs.toFloat() / slotDurationMs.toFloat() * slotWidth.value).dp
                                    val isNow = program.startTime <= now && program.endTime >= now

                                    Box(
                                        modifier = Modifier
                                            .width(maxOf(width, 100.dp))
                                            .fillMaxHeight(0.85f)
                                            .padding(horizontal = 3.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isNow) Color(0xFF25314C) else Color(0xFF1E1F29))
                                            .clickable {
                                                selectedProgramInfo = Pair(channel, program)
                                            }
                                            .padding(10.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (isNow) {
                                                    Surface(
                                                        color = Color(0xFF8AB4F8),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "NOW",
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                            color = Color.Black,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Spacer(Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = formatProgramTime(program.startTime),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White.copy(alpha = 0.7f)
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = program.title,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
            }
        }
    }

    if (selectedProgramInfo != null) {
        val (channel, program) = selectedProgramInfo!!
        Dialog(onDismissRequest = { selectedProgramInfo = null }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1B1C25),
                border = BorderStroke(1.dp, Color(0xFF8AB4F8))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(channel = channel, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                color = Color(0xFF8AB4F8),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${formatProgramTime(program.startTime)} - ${formatProgramTime(program.endTime)}",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    if (program.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = program.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { selectedProgramInfo = null }) {
                            Text("Close", color = Color.White.copy(alpha = 0.7f))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                selectedProgramInfo = null
                                onChannelSelected(channel)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8), contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Watch Live", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// EPG MATCHING
// ============================================================

fun buildEpgIndex(
    epgList: List<EpgProgram>
): Map<String, List<EpgProgram>> {
    if (epgList.isEmpty()) return emptyMap()
    val map = mutableMapOf<String, MutableList<EpgProgram>>()
    for (program in epgList) {
        val key = normalizeGuideKey(program.channelId)
        if (key.isNotBlank()) {
            map.getOrPut(key) { mutableListOf() }.add(program)
        }
    }
    return map
}

fun findProgramsForChannel(
    channel: LiveChannel,
    epgMap: Map<String, List<EpgProgram>>
): List<EpgProgram> {
    if (epgMap.isEmpty()) return emptyList()

    val ids = listOf(channel.tvgId, channel.streamId, channel.name)

    for (rawId in ids) {
        if (rawId.isBlank()) continue
        val key = normalizeGuideKey(rawId)
        if (key.isBlank()) continue
        val list = epgMap[key]
        if (!list.isNullOrEmpty()) {
            return list
        }
    }

    return emptyList()
}

// ============================================================
// GUIDE CHANNEL CARD
// ============================================================

@Composable
fun GuideChannelCard(
    channel: LiveChannel,
    programs: List<EpgProgram>,
    now: Long,
    onChannelSelected: () -> Unit
) {

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                14.dp
            )
    ) {

        Column(
            modifier =
                Modifier.padding(
                    12.dp
                )
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                ChannelLogo(
                    channel =
                        channel
                )

                Spacer(
                    Modifier.width(12.dp)
                )

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(
                        text =
                            channel.name,

                        fontWeight =
                            FontWeight.Bold,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick =
                        onChannelSelected
                ) {

                    Text(
                        "Watch"
                    )
                }
            }

            Spacer(
                Modifier.height(10.dp)
            )

            programs.forEach { program ->

                val isNow =
                    program.startTime <= now &&
                            program.endTime >= now

                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                vertical = 3.dp
                            ),

                    colors =
                        CardDefaults
                            .cardColors(
                                containerColor =
                                    if (isNow) {
                                        Color(
                                            0xFF252A38
                                        )
                                    } else {
                                        Color(
                                            0xFF202027
                                        )
                                    }
                            )
                ) {

                    Row(
                        modifier =
                            Modifier.padding(
                                10.dp
                            ),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Column(
                            modifier =
                                Modifier.width(
                                    62.dp
                                )
                        ) {

                            Text(
                                formatProgramTime(
                                    program.startTime
                                ),

                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(
                                formatProgramTime(
                                    program.endTime
                                ),

                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall
                            )
                        }

                        Spacer(
                            Modifier.width(10.dp)
                        )

                        Column(
                            modifier =
                                Modifier.weight(1f)
                        ) {

                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                if (isNow) {

                                    Text(
                                        "NOW",

                                        style =
                                            MaterialTheme
                                                .typography
                                                .labelSmall,

                                        fontWeight =
                                            FontWeight.Bold
                                    )

                                    Spacer(
                                        Modifier.width(8.dp)
                                    )
                                }

                                Text(
                                    program.title,

                                    fontWeight =
                                        FontWeight.SemiBold,

                                    maxLines = 1,

                                    overflow =
                                        TextOverflow.Ellipsis
                                )
                            }

                            if (
                                program.description
                                    .isNotBlank()
                            ) {

                                Text(
                                    program.description,

                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,

                                    maxLines = 2,

                                    overflow =
                                        TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// M3U PARSER
// ============================================================

fun parseM3uPlaylist(
    playlistUrl: String
): M3uParseResult {

    val channels =
        mutableListOf<LiveChannel>()

    var detectedEpgUrl = ""

    val connection =
        URL(playlistUrl)
            .openConnection() as HttpURLConnection

    connection.connectTimeout = 20_000
    connection.readTimeout = 30_000
    connection.requestMethod = "GET"

    connection.setRequestProperty(
        "User-Agent",
        "BingeBox/1.0"
    )

    connection.connect()

    val stream =
        if (
            connection.contentEncoding
                ?.contains("gzip", true) == true
        ) {
            GZIPInputStream(
                connection.inputStream
            )
        } else {
            connection.inputStream
        }

    val reader =
        BufferedReader(
            InputStreamReader(stream)
        )

    val lines = reader.readLines()

    reader.close()
    connection.disconnect()

    var currentExtInf = ""
    var currentExtGrp = ""

    for (line in lines) {

        val trimmed = line.trim()

        // Playlist header / automatic EPG
        if (
            trimmed.startsWith(
                "#EXTM3U",
                ignoreCase = true
            )
        ) {

            detectedEpgUrl =
                extractM3uAttribute(
                    trimmed,
                    "url-tvg",
                    "x-tvg-url",
                    "epg-url",
                    "epg"
                )
        }

        // Alternative EPG formats
        if (
            trimmed.startsWith(
                "#EXT-X-TVG-URL:",
                ignoreCase = true
            )
        ) {

            detectedEpgUrl =
                trimmed
                    .substringAfter(":")
                    .trim()
        }

        if (
            trimmed.startsWith(
                "#EXT-X-EPG-URL:",
                ignoreCase = true
            )
        ) {

            detectedEpgUrl =
                trimmed
                    .substringAfter(":")
                    .trim()
        }

        // EXTGRP fallback
        if (
            trimmed.startsWith(
                "#EXTGRP:",
                ignoreCase = true
            )
        ) {

            currentExtGrp =
                trimmed
                    .substringAfter(":")
                    .trim()
        }

        // Channel information
        if (
            trimmed.startsWith(
                "#EXTINF:",
                ignoreCase = true
            )
        ) {

            currentExtInf = trimmed
            continue
        }

        // The line following EXTINF is the stream URL
        if (
            currentExtInf.isNotBlank() &&
            trimmed.isNotEmpty() &&
            !trimmed.startsWith("#")
        ) {

            val streamUrl = trimmed

            // -----------------------------
            // CHANNEL NAME
            // -----------------------------

            val commaIndex =
                currentExtInf.indexOf(",")

            val channelName =
                if (commaIndex >= 0) {

                    currentExtInf
                        .substring(commaIndex + 1)
                        .trim()

                } else {

                    extractM3uAttribute(
                        currentExtInf,
                        "tvg-name"
                    ).ifBlank {
                        "Unknown Channel"
                    }
                }

            // -----------------------------
            // CHANNEL LOGO
            // -----------------------------

            val logoUrl =
                extractM3uAttribute(
                    currentExtInf,
                    "tvg-logo",
                    "logo"
                )

            // -----------------------------
            // CHANNEL GROUP
            // -----------------------------

            val groupFromExtInf =
                extractM3uAttribute(
                    currentExtInf,
                    "group-title"
                ).trim()

            val groupFromAlternativeAttribute =
                extractM3uAttribute(
                    currentExtInf,
                    "group",
                    "category_name",
                    "category",
                    "genre"
                ).trim()

            val finalGroup =
                when {
                    groupFromExtInf.isNotBlank() ->
                        groupFromExtInf

                    groupFromAlternativeAttribute.isNotBlank() ->
                        groupFromAlternativeAttribute

                    currentExtGrp.isNotBlank() ->
                        currentExtGrp

                    else ->
                        "General"
                }

            // -----------------------------
            // TVG ID
            // -----------------------------

            val tvgId =
                extractM3uAttribute(
                    currentExtInf,
                    "tvg-id"
                )

            // -----------------------------
            // STREAM ID
            // -----------------------------

            val streamId =
                extractM3uAttribute(
                    currentExtInf,
                    "stream-id",
                    "stream_id"
                ).ifBlank {

                    extractStreamIdFromUrl(
                        streamUrl
                    )
                }

            // -----------------------------
            // ADD CHANNEL
            // -----------------------------

            channels.add(
                LiveChannel(
                    name = channelName,

                    streamUrl = streamUrl,

                    logoUrl = logoUrl,

                    group =
                        normalizeIptvGroup(
                            finalGroup
                        ),

                    tvgId = tvgId,

                    streamId = streamId
                )
            )

            // Reset EXTINF so the next URL
            // cannot accidentally reuse this channel.
            currentExtInf = ""
        }
    }

    // -----------------------------
    // AUTOMATIC EPG
    // -----------------------------

    val epg =
        if (detectedEpgUrl.isNotBlank()) {

            try {

                parseXmltvEpg(
                    detectedEpgUrl
                )

            } catch (e: Exception) {

                Log.e(
                    "BingeBox",
                    "Automatic EPG failed",
                    e
                )

                emptyList()
            }

        } else {

            emptyList()
        }

    return M3uParseResult(
        channels = channels,
        epg = epg
    )
}

// ============================================================
// XMLTV EPG
// ============================================================

fun parseXmltvEpg(
    epgUrl: String
): List<EpgProgram> {

    val result =
        mutableListOf<EpgProgram>()

    val connection =
        URL(epgUrl)
            .openConnection()
                as HttpURLConnection

    connection.connectTimeout =
        20_000

    connection.readTimeout =
        30_000

    connection.setRequestProperty(
        "User-Agent",
        "BingeBox/1.0"
    )

    connection.connect()

    val isGzip =
        connection.contentEncoding
            ?.contains(
                "gzip",
                true
            ) == true ||
                epgUrl
                    .lowercase(
                        Locale.US
                    )
                    .contains(".gz")

    val inputStream =
        if (isGzip) {

            GZIPInputStream(
                connection.inputStream
            )

        } else {

            connection.inputStream
        }

    val parser =
        Xml.newPullParser()

    parser.setInput(
        inputStream,
        "UTF-8"
    )

    var event =
        parser.eventType

    var currentChannelId = ""
    var currentTitle = ""
    var currentDescription = ""
    var currentStart = 0L
    var currentEnd = 0L
    var insideProgramme = false

    val channelNameMap = mutableMapOf<String, MutableList<String>>()
    var parsingChannelId = ""
    var insideChannel = false

    while (
        event !=
        XmlPullParser.END_DOCUMENT
    ) {

        when (event) {

            XmlPullParser.START_TAG -> {

                when (
                    parser.name
                ) {

                    "channel" -> {
                        insideChannel = true
                        parsingChannelId =
                            parser.getAttributeValue(null, "id").orEmpty()
                    }

                    "display-name" -> {
                        if (insideChannel && parsingChannelId.isNotBlank()) {
                            try {
                                val name = parser.nextText()
                                if (name.isNotBlank()) {
                                    channelNameMap.getOrPut(parsingChannelId) { mutableListOf() }.add(name)
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    "programme" -> {

                        insideProgramme =
                            true

                        currentChannelId =
                            parser
                                .getAttributeValue(
                                    null,
                                    "channel"
                                )
                                .orEmpty()

                        currentStart =
                            parseDateMillis(
                                parser
                                    .getAttributeValue(
                                        null,
                                        "start"
                                    )
                                    .orEmpty()
                            )

                        currentEnd =
                            parseDateMillis(
                                parser
                                    .getAttributeValue(
                                        null,
                                        "stop"
                                    )
                                    .orEmpty()
                            )
                    }

                    "title" -> {

                        if (
                            insideProgramme
                        ) {

                            currentTitle =
                                parser.nextText()
                        }
                    }

                    "desc" -> {

                        if (
                            insideProgramme
                        ) {

                            currentDescription =
                                parser.nextText()
                        }
                    }
                }
            }

            XmlPullParser.END_TAG -> {

                if (parser.name == "channel") {
                    insideChannel = false
                    parsingChannelId = ""
                }

                if (
                    parser.name ==
                    "programme" &&
                    insideProgramme
                ) {

                    if (
                        currentChannelId.isNotBlank() &&
                        currentTitle.isNotBlank()
                    ) {
                        val cutoffTime = System.currentTimeMillis() - (12 * 3600 * 1000L)
                        if (currentEnd >= cutoffTime || currentEnd == 0L) {
                            val prog = EpgProgram(
                                channelId = currentChannelId,
                                title = currentTitle,
                                description = currentDescription,
                                startTime = currentStart,
                                endTime = currentEnd
                            )
                            result.add(prog)
                        }
                    }

                    currentChannelId = ""
                    currentTitle = ""
                    currentDescription = ""
                    currentStart = 0L
                    currentEnd = 0L
                    insideProgramme = false
                }
            }
        }

        event =
            parser.next()
    }

    inputStream.close()
    connection.disconnect()

    return result
}

// ============================================================
// XTREAM CHANNELS
// ============================================================

fun fetchXtreamChannels(
    connection: XtreamConnection
): List<LiveChannel> {

    val cleanServer =
        connection.serverUrl
            .trimEnd('/')

    val username =
        URLEncoder.encode(
            connection.username,
            "UTF-8"
        )

    val password =
        URLEncoder.encode(
            connection.password,
            "UTF-8"
        )

    // 1. FETCH CATEGORIES FIRST
    val categoriesUrl =
        "$cleanServer/player_api.php" +
                "?username=$username" +
                "&password=$password" +
                "&action=get_live_categories"

    val categoriesMap = mutableMapOf<String, String>()

    try {
        val catHttp =
            URL(categoriesUrl)
                .openConnection() as HttpURLConnection

        catHttp.connectTimeout = 15_000
        catHttp.readTimeout = 15_000
        catHttp.setRequestProperty(
            "User-Agent",
            "BingeBox/1.0"
        )
        catHttp.connect()

        val catResponse =
            catHttp.inputStream
                .bufferedReader()
                .use { it.readText() }

        catHttp.disconnect()

        if (catResponse.isNotBlank()) {
            val catArray = JSONArray(catResponse)
            for (i in 0 until catArray.length()) {
                val catObj = catArray.getJSONObject(i)
                val catId = catObj.optString("category_id")
                val catName = catObj.optString("category_name")
                if (catId.isNotBlank() && catName.isNotBlank()) {
                    categoriesMap[catId] = catName
                }
            }
        }
    } catch (e: Exception) {
        Log.e("BingeBox", "Failed to load categories", e)
    }

    // 2. FETCH LIVE STREAMS
    val url =
        "$cleanServer/player_api.php" +
                "?username=$username" +
                "&password=$password" +
                "&action=get_live_streams"

    val http =
        URL(url)
            .openConnection()
                as HttpURLConnection

    http.connectTimeout =
        20_000

    http.readTimeout =
        30_000

    http.setRequestProperty(
        "User-Agent",
        "BingeBox/1.0"
    )

    http.connect()

    val response =
        http.inputStream
            .bufferedReader()
            .use {
                it.readText()
            }

    http.disconnect()

    if (
        response.isBlank()
    ) {
        return emptyList()
    }

    val array =
        JSONArray(response)

    val result =
        mutableListOf<LiveChannel>()

    for (
    i in 0 until array.length()
    ) {

        val item =
            array.getJSONObject(i)

        val name =
            item.optString(
                "name",
                "Unknown Channel"
            )

        val streamId =
            item.optString(
                "stream_id"
            )

        if (
            streamId.isBlank()
        ) {
            continue
        }

        val logo =
            item.optString(
                "stream_icon"
            )

        val categoryId =
            item.optString(
                "category_id"
            )

        val categoryName =
            categoriesMap[categoryId] ?: "General"

        val streamUrl =
            "$cleanServer/live/" +
                    "${connection.username}/" +
                    "${connection.password}/" +
                    "$streamId.ts"

        result.add(
            LiveChannel(
                name =
                    name,

                streamUrl =
                    streamUrl,

                logoUrl =
                    logo,

                group =
                    normalizeIptvGroup(
                        categoryName
                    ),

                tvgId =
                    item.optString(
                        "epg_channel_id"
                    ),

                streamId =
                    streamId
            )
        )
    }

    return result
}

// ============================================================
// XTREAM SINGLE CHANNEL EPG
// ============================================================

fun fetchXtreamEpg(
    connection: XtreamConnection,
    streamId: String
): List<EpgProgram> {

    if (
        streamId.isBlank()
    ) {
        return emptyList()
    }

    val cleanServer =
        connection.serverUrl
            .trimEnd('/')

    val username =
        URLEncoder.encode(
            connection.username,
            "UTF-8"
        )

    val password =
        URLEncoder.encode(
            connection.password,
            "UTF-8"
        )

    val encodedStreamId =
        URLEncoder.encode(
            streamId,
            "UTF-8"
        )

    val url =
        "$cleanServer/player_api.php" +
                "?username=$username" +
                "&password=$password" +
                "&action=get_short_epg" +
                "&stream_id=$encodedStreamId" +
                "&limit=20"

    val http =
        URL(url)
            .openConnection()
                as HttpURLConnection

    http.connectTimeout =
        15_000

    http.readTimeout =
        20_000

    http.setRequestProperty(
        "User-Agent",
        "BingeBox/1.0"
    )

    http.connect()

    val text =
        http.inputStream
            .bufferedReader()
            .use {
                it.readText()
            }

    http.disconnect()

    if (
        text.isBlank()
    ) {
        return emptyList()
    }

    val json =
        JSONObject(text)

    val listings =
        json.optJSONArray(
            "epg_listings"
        ) ?: return emptyList()

    val result =
        mutableListOf<EpgProgram>()

    for (
    i in 0 until listings.length()
    ) {

        val item =
            listings.getJSONObject(i)

        val title =
            item.optString(
                "title"
            )

        val description =
            item.optString(
                "description"
            )

        val start =
            if (
                item.has(
                    "start_timestamp"
                )
            ) {

                parseDateMillis(
                    item.optString(
                        "start_timestamp"
                    )
                )

            } else {

                parseDateMillis(
                    item.optString(
                        "start"
                    )
                )
            }

        val end =
            if (
                item.has(
                    "stop_timestamp"
                )
            ) {

                parseDateMillis(
                    item.optString(
                        "stop_timestamp"
                    )
                )

            } else {

                parseDateMillis(
                    item.optString(
                        "end"
                    )
                )
            }

        if (
            title.isNotBlank()
        ) {

            result.add(
                EpgProgram(
                    channelId =
                        streamId,

                    title =
                        title,

                    description =
                        description,

                    startTime =
                        start,

                    endTime =
                        end
                )
            )
        }
    }

    return result
}

// ============================================================
// XTREAM EPG FOR ALL CHANNELS
// ============================================================

suspend fun fetchXtreamEpgForChannels(
    connection: XtreamConnection,
    channels: List<LiveChannel>,
    customEpgUrl: String = ""
): List<EpgProgram> =
    withContext(
        Dispatchers.IO
    ) {
        val cleanServer = connection.serverUrl.trimEnd('/')
        val username = URLEncoder.encode(connection.username, "UTF-8")
        val password = URLEncoder.encode(connection.password, "UTF-8")

        val urlsToTry = mutableListOf<String>()
        if (customEpgUrl.isNotBlank()) {
            urlsToTry.add(customEpgUrl)
        }
        urlsToTry.add("$cleanServer/xmltv.php?username=$username&password=$password")
        urlsToTry.add("$cleanServer/xmltv.xml")

        for (url in urlsToTry) {
            try {
                val programs = parseXmltvEpg(url)
                if (programs.isNotEmpty()) {
                    return@withContext programs
                        .filter { it.endTime > 0L }
                        .distinctBy { "${it.channelId}_${it.startTime}_${it.title}" }
                        .sortedBy { it.startTime }
                }
            } catch (e: Exception) {
                Log.e("BingeBox", "XMLTV EPG failed for $url", e)
            }
        }

        val channelsWithIds =
            channels.filter {
                it.streamId.isNotBlank()
            }

        val result =
            mutableListOf<EpgProgram>()

        channelsWithIds
            .chunked(8)
            .forEach { batch ->

                val batchResults =
                    coroutineScope {

                        batch.map { channel ->

                            async {

                                try {

                                    fetchXtreamEpg(
                                        connection,
                                        channel.streamId
                                    )

                                } catch (
                                    e: Exception
                                ) {

                                    Log.e(
                                        "BingeBox",
                                        "EPG failed for ${channel.name}",
                                        e
                                    )

                                    emptyList()
                                }
                            }
                        }.awaitAll()
                    }

                batchResults.forEach {
                    result.addAll(it)
                }
            }

        result
            .filter {
                it.endTime > 0L
            }
            .distinctBy {
                "${it.channelId}_${it.startTime}_${it.title}"
            }
            .sortedBy {
                it.startTime
            }
    }

// ============================================================
// PODCASTS
// ============================================================

@Composable
fun PodcastScreen(player: ExoPlayer) {

    var searchText by remember {
        mutableStateOf("")
    }

    var shows by remember {
        mutableStateOf<List<PodcastShow>>(
            emptyList()
        )
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var selectedShow by remember {
        mutableStateOf<PodcastShow?>(null)
    }

    var episodes by remember {
        mutableStateOf<List<PodcastEpisode>>(
            emptyList()
        )
    }

    var episodesLoading by remember {
        mutableStateOf(false)
    }

    var currentPlayingUrl by remember {
        mutableStateOf<String?>(null)
    }

    var currentPlayingTitle by remember {
        mutableStateOf<String?>(null)
    }

    val context = LocalContext.current
    val scope =
        rememberCoroutineScope()

    Column(
        modifier =
            Modifier.fillMaxSize()
    ) {

        Text(
            text = "Podcasts",

            style =
                MaterialTheme
                    .typography
                    .headlineSmall,

            fontWeight =
                FontWeight.Bold,

            modifier =
                Modifier.padding(
                    16.dp
                )
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            OutlinedTextField(
                value =
                    searchText,

                onValueChange = {
                    searchText = it
                },

                modifier =
                    Modifier.weight(1f),

                label = {
                    Text(
                        "Search podcasts"
                    )
                },

                singleLine = true
            )

            Spacer(
                Modifier.width(8.dp)
            )

            IconButton(
                onClick = {

                    if (
                        searchText
                            .trim()
                            .isNotEmpty()
                    ) {

                        loading = true

                        scope.launch {

                            shows =
                                withContext(
                                    Dispatchers.IO
                                ) {

                                    searchPodcasts(
                                        searchText
                                            .trim()
                                    )
                                }

                            loading = false
                        }
                    }
                }
            ) {

                Icon(
                    Icons.Default.Search,
                    contentDescription =
                        "Search"
                )
            }
        }

        Spacer(
            Modifier.height(12.dp)
        )

        if (loading) {

            Box(
                modifier =
                    Modifier.fillMaxSize(),

                contentAlignment =
                    Alignment.Center
            ) {

                CircularProgressIndicator()
            }

        } else {

            LazyColumn(
                contentPadding =
                    PaddingValues(
                        16.dp
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(
                        10.dp
                    ),

                modifier = Modifier.weight(1f)
            ) {

                items(
                    shows
                ) { show ->

                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedShow = show
                                    episodesLoading = true
                                    scope.launch {
                                        episodes = withContext(Dispatchers.IO) {
                                            parsePodcastRss(show.feedUrl)
                                        }
                                        episodesLoading = false
                                    }
                                }
                    ) {

                        Row(
                            modifier =
                                Modifier.padding(
                                    10.dp
                                ),

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            if (
                                show.artworkUrl
                                    .isNotBlank()
                            ) {

                                AsyncImage(
                                    model =
                                        show.artworkUrl,

                                    contentDescription =
                                        show.name,

                                    modifier =
                                        Modifier
                                            .size(75.dp)
                                            .clip(
                                                RoundedCornerShape(
                                                    10.dp
                                                )
                                            ),

                                    contentScale =
                                        ContentScale.Crop
                                )
                            }

                            Spacer(
                                Modifier.width(12.dp)
                            )

                            Column(modifier = Modifier.weight(1f)) {

                                Text(
                                    text =
                                        show.name,

                                    fontWeight =
                                        FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Text(
                                    text =
                                        show.author,

                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        if (currentPlayingUrl != null) {
            Surface(
                color = Color(0xFF1E222D),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Podcasts,
                        contentDescription = null,
                        tint = Color(0xFF8AB4F8),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Playing: ${currentPlayingTitle ?: "Podcast"}",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Audio stream",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                        }
                    ) {
                        Icon(
                            if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = {
                            player.stop()
                            currentPlayingUrl = null
                            currentPlayingTitle = null
                        }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    if (selectedShow != null) {
        Dialog(
            onDismissRequest = { selectedShow = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF15151B)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedShow?.artworkUrl.orEmpty().isNotBlank()) {
                            AsyncImage(
                                model = selectedShow?.artworkUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedShow?.name.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = selectedShow?.author.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { selectedShow = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    if (episodesLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (episodes.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No episodes found or unable to load feed.",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(episodes) { episode ->
                                val isCurrentPlaying = currentPlayingUrl == episode.audioUrl
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentPlayingUrl = episode.audioUrl
                                            currentPlayingTitle = episode.title
                                            try {
                                                player.setMediaItem(MediaItem.fromUri(episode.audioUrl))
                                                player.prepare()
                                                player.playWhenReady = true
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Unable to play episode", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isCurrentPlaying) Color(0xFF252A38) else Color(0xFF202027)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.PlayCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF8AB4F8),
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = episode.title,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (episode.pubDate.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = episode.pubDate,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun parsePodcastRss(feedUrl: String): List<PodcastEpisode> {
    if (feedUrl.isBlank()) return emptyList()
    val episodes = mutableListOf<PodcastEpisode>()
    try {
        val url = URL(feedUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "BingeBox/1.0")
        connection.connect()

        val stream = if (connection.contentEncoding?.contains("gzip", true) == true) {
            GZIPInputStream(connection.inputStream)
        } else {
            connection.inputStream
        }

        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")

        var event = parser.eventType
        var insideItem = false
        var title = ""
        var description = ""
        var audioUrl = ""
        var pubDate = ""

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "item" -> {
                            insideItem = true
                            title = ""
                            description = ""
                            audioUrl = ""
                            pubDate = ""
                        }
                        "title" -> {
                            if (insideItem) title = parser.nextText().trim()
                        }
                        "description", "summary" -> {
                            if (insideItem && description.isBlank()) description = parser.nextText().trim()
                        }
                        "enclosure" -> {
                            if (insideItem) {
                                val urlAttr = parser.getAttributeValue(null, "url").orEmpty()
                                val typeAttr = parser.getAttributeValue(null, "type").orEmpty()
                                if (urlAttr.isNotBlank() && (typeAttr.contains("audio") || urlAttr.contains(".mp3") || urlAttr.contains(".m4a") || urlAttr.contains(".aac"))) {
                                    audioUrl = urlAttr
                                } else if (urlAttr.isNotBlank() && audioUrl.isBlank()) {
                                    audioUrl = urlAttr
                                }
                            }
                        }
                        "pubdate" -> {
                            if (insideItem) pubDate = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase() == "item") {
                        if (title.isNotBlank() && audioUrl.isNotBlank()) {
                            episodes.add(PodcastEpisode(title, description, audioUrl, pubDate))
                        }
                        insideItem = false
                    }
                }
            }
            event = parser.next()
        }
        stream.close()
        connection.disconnect()
    } catch (_: Exception) {
        Log.e("BingeBox", "Failed to parse podcast RSS", null)
    }
    return episodes
}

// ============================================================
// PODCAST SEARCH
// ============================================================

fun searchPodcasts(
    query: String
): List<PodcastShow> {

    return try {

        val encoded =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        val url =
            "https://itunes.apple.com/search" +
                    "?term=$encoded" +
                    "&media=podcast" +
                    "&limit=25"

        val connection =
            URL(url)
                .openConnection()
                    as HttpURLConnection

        connection.connectTimeout =
            15_000

        connection.readTimeout =
            20_000

        val text =
            connection.inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }

        connection.disconnect()

        val json =
            JSONObject(text)

        val results =
            json.optJSONArray(
                "results"
            ) ?: return emptyList()

        val podcasts =
            mutableListOf<PodcastShow>()

        for (
        i in 0 until results.length()
        ) {

            val item =
                results.getJSONObject(i)

            podcasts.add(
                PodcastShow(

                    name =
                        item.optString(
                            "collectionName"
                        ),

                    author =
                        item.optString(
                            "artistName"
                        ),

                    artworkUrl =
                        item.optString(
                            "artworkUrl600"
                        ),

                    feedUrl =
                        item.optString(
                            "feedUrl"
                        )
                )
            )
        }

        podcasts

    } catch (e: Exception) {

        Log.e(
            "BingeBox",
            "Podcast search failed",
            e
        )

        emptyList()
    }
}

// ============================================================
// MOVIE & SERIES HELPERS
// ============================================================

fun cleanMediaTitle(rawTitle: String): String {
    return rawTitle
        .replace(Regex("""(?i)\b(4k|fhd|hd|sd|hevc|x264|x265|1080p|720p|2160p|web-dl|bluray|rip)\b"""), "")
        .replace(Regex("""^[A-Z]{2,3}\s*[:\|-]\s*"""), "")
        .replace(Regex("""\.[a-zA-Z0-9]{3,4}$"""), "")
        .replace(Regex("""[\(\[\{].*?[\)\]\}]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

fun enrichMovieWithTmdb(movie: Movie): Movie {
    val cleanTitle = cleanMediaTitle(movie.title)
    if (cleanTitle.isBlank()) return movie

    val apiKey = "8b32d99f943f72e9441eff5dc6da83ac"
    return try {
        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=$encoded&language=en-GB"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(text)
        val results = json.optJSONArray("results")
        if (results != null && results.length() > 0) {
            val item = results.getJSONObject(0)
            val posterPath = item.optString("poster_path")
            val backdropPath = item.optString("backdrop_path")
            movie.copy(
                posterUrl = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else movie.posterUrl,
                backdropUrl = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/w1280$backdropPath" else movie.backdropUrl,
                overview = item.optString("overview").ifBlank { movie.overview },
                releaseDate = item.optString("release_date").ifBlank { movie.releaseDate },
                voteAverage = item.optDouble("vote_average", movie.voteAverage)
            )
        } else {
            movie
        }
    } catch (_: Exception) {
        movie
    }
}

fun enrichSeriesWithTmdb(series: TvSeries): TvSeries {
    val cleanTitle = cleanMediaTitle(series.title)
    if (cleanTitle.isBlank()) return series

    val apiKey = "8b32d99f943f72e9441eff5dc6da83ac"
    return try {
        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$encoded&language=en-GB"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(text)
        val results = json.optJSONArray("results")
        if (results != null && results.length() > 0) {
            val item = results.getJSONObject(0)
            val posterPath = item.optString("poster_path")
            val backdropPath = item.optString("backdrop_path")
            series.copy(
                posterUrl = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else series.posterUrl,
                backdropUrl = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/w1280$backdropPath" else series.backdropUrl,
                overview = item.optString("overview").ifBlank { series.overview },
                releaseDate = item.optString("first_air_date").ifBlank { series.releaseDate },
                voteAverage = item.optDouble("vote_average", series.voteAverage)
            )
        } else {
            series
        }
    } catch (_: Exception) {
        series
    }
}

fun fetchM3uMovies(playlistUrl: String): List<Movie> {
    if (playlistUrl.isBlank()) return emptyList()
    val rawMovies = mutableListOf<Movie>()
    try {
        val connection = URL(playlistUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val lines = text.lines()
        var currentExtInf = ""
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                currentExtInf = trimmed
                continue
            }
            if (currentExtInf.isNotBlank() && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val streamUrl = trimmed
                val group = extractM3uAttribute(currentExtInf, "group-title", "category_name", "genre")
                val isVodMovie = group.contains("Movie", ignoreCase = true) ||
                        group.contains("VOD", ignoreCase = true) ||
                        group.contains("Film", ignoreCase = true) ||
                        group.contains("Cinema", ignoreCase = true) ||
                        streamUrl.endsWith(".mp4", ignoreCase = true) ||
                        streamUrl.endsWith(".mkv", ignoreCase = true)

                if (isVodMovie) {
                    val rawName = currentExtInf.substringAfter(",").trim()
                    if (rawName.isNotBlank()) {
                        rawMovies.add(
                            Movie(
                                title = cleanMediaTitle(rawName),
                                streamUrl = streamUrl
                            )
                        )
                    }
                }
                currentExtInf = ""
            }
        }
    } catch (e: Exception) {
        Log.e("BingeBox", "Failed to parse M3U movies", e)
    }

    return rawMovies.distinctBy { it.title }.take(30).map { movie ->
        enrichMovieWithTmdb(movie)
    }
}

fun fetchM3uSeries(playlistUrl: String): List<TvSeries> {
    if (playlistUrl.isBlank()) return emptyList()
    val seriesMap = mutableMapOf<String, MutableList<TvEpisode>>()
    try {
        val connection = URL(playlistUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val lines = text.lines()
        var currentExtInf = ""
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                currentExtInf = trimmed
                continue
            }
            if (currentExtInf.isNotBlank() && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val streamUrl = trimmed
                val group = extractM3uAttribute(currentExtInf, "group-title", "category_name", "genre")
                val isVodSeries = group.contains("Series", ignoreCase = true) ||
                        group.contains("Season", ignoreCase = true) ||
                        group.contains("Show", ignoreCase = true) ||
                        currentExtInf.contains("S0", ignoreCase = true) ||
                        currentExtInf.contains("E0", ignoreCase = true)

                if (isVodSeries) {
                    val rawName = currentExtInf.substringAfter(",").trim()
                    val cleanTitle = cleanMediaTitle(rawName)
                    if (cleanTitle.isNotBlank()) {
                        val ep = TvEpisode(
                            episodeNumber = 1,
                            title = rawName,
                            streamUrl = streamUrl
                        )
                        seriesMap.getOrPut(cleanTitle) { mutableListOf() }.add(ep)
                    }
                }
                currentExtInf = ""
            }
        }
    } catch (e: Exception) {
        Log.e("BingeBox", "Failed to parse M3U series", e)
    }

    val resultSeries = mutableListOf<TvSeries>()
    seriesMap.entries.take(20).forEach { (seriesTitle, episodes) ->
        val series = TvSeries(
            title = seriesTitle,
            seasons = listOf(TvSeason(seasonNumber = 1, name = "Season 1", episodes = episodes))
        )
        resultSeries.add(enrichSeriesWithTmdb(series))
    }
    return resultSeries
}

fun loadLocalMovies(context: Context): List<Movie> {
    return try {
        val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("local_movies_json", "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Movie>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                Movie(
                    title = obj.optString("title"),
                    posterUrl = obj.optString("posterUrl"),
                    backdropUrl = obj.optString("backdropUrl"),
                    overview = obj.optString("overview"),
                    releaseDate = obj.optString("releaseDate"),
                    voteAverage = obj.optDouble("voteAverage", 0.0),
                    streamUrl = obj.optString("streamUrl")
                )
            )
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

fun saveLocalMovie(context: Context, movie: Movie) {
    try {
        val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("local_movies_json", "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val obj = JSONObject().apply {
            put("title", movie.title)
            put("posterUrl", movie.posterUrl)
            put("backdropUrl", movie.backdropUrl)
            put("overview", movie.overview)
            put("releaseDate", movie.releaseDate)
            put("voteAverage", movie.voteAverage)
            put("streamUrl", movie.streamUrl)
        }
        array.put(obj)
        prefs.edit().putString("local_movies_json", array.toString()).apply()
    } catch (e: Exception) {
        Log.e("BingeBox", "Failed to save local movie", e)
    }
}

fun fetchCloudMovies(cloudUrl: String): List<Movie> {
    if (cloudUrl.isBlank()) return emptyList()
    return try {
        val connection = URL(cloudUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "BingeBox/1.0")
        connection.connect()

        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = if (text.trim().startsWith("[")) {
            JSONArray(text)
        } else {
            val obj = JSONObject(text)
            obj.optJSONArray("movies") ?: obj.optJSONArray("results") ?: JSONArray()
        }

        val list = mutableListOf<Movie>()
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            list.add(
                Movie(
                    title = item.optString("title", "Cloud Movie"),
                    posterUrl = item.optString("posterUrl").ifBlank { item.optString("poster_path") },
                    backdropUrl = item.optString("backdropUrl").ifBlank { item.optString("backdrop_path") },
                    overview = item.optString("overview"),
                    releaseDate = item.optString("releaseDate").ifBlank { item.optString("release_date") },
                    voteAverage = item.optDouble("voteAverage", item.optDouble("vote_average", 0.0)),
                    streamUrl = item.optString("streamUrl").ifBlank { item.optString("url") }
                )
            )
        }
        list
    } catch (e: Exception) {
        Log.e("BingeBox", "Failed to fetch cloud movies", e)
        emptyList()
    }
}

fun loadLocalSeries(context: Context): List<TvSeries> {
    return try {
        val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("local_series_json", "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<TvSeries>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val seasonsArray = obj.optJSONArray("seasons") ?: JSONArray()
            val seasonsList = mutableListOf<TvSeason>()
            for (s in 0 until seasonsArray.length()) {
                val sObj = seasonsArray.getJSONObject(s)
                val epArray = sObj.optJSONArray("episodes") ?: JSONArray()
                val epList = mutableListOf<TvEpisode>()
                for (e in 0 until epArray.length()) {
                    val eObj = epArray.getJSONObject(e)
                    epList.add(
                        TvEpisode(
                            episodeNumber = eObj.optInt("episodeNumber", e + 1),
                            title = eObj.optString("title", "Episode ${e + 1}"),
                            streamUrl = eObj.optString("streamUrl"),
                            overview = eObj.optString("overview"),
                            duration = eObj.optString("duration")
                        )
                    )
                }
                seasonsList.add(
                    TvSeason(
                        seasonNumber = sObj.optInt("seasonNumber", s + 1),
                        name = sObj.optString("name", "Season ${s + 1}"),
                        episodes = epList
                    )
                )
            }
            list.add(
                TvSeries(
                    title = obj.optString("title"),
                    posterUrl = obj.optString("posterUrl"),
                    backdropUrl = obj.optString("backdropUrl"),
                    overview = obj.optString("overview"),
                    releaseDate = obj.optString("releaseDate"),
                    voteAverage = obj.optDouble("voteAverage", 0.0),
                    seasons = seasonsList
                )
            )
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

fun saveLocalSeries(context: Context, series: TvSeries) {
    try {
        val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("local_series_json", "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val obj = JSONObject().apply {
            put("title", series.title)
            put("posterUrl", series.posterUrl)
            put("backdropUrl", series.backdropUrl)
            put("overview", series.overview)
            put("releaseDate", series.releaseDate)
            put("voteAverage", series.voteAverage)
            val sArr = JSONArray()
            series.seasons.forEach { season ->
                val sObj = JSONObject().apply {
                    put("seasonNumber", season.seasonNumber)
                    put("name", season.name)
                    val eArr = JSONArray()
                    season.episodes.forEach { ep ->
                        eArr.put(JSONObject().apply {
                            put("episodeNumber", ep.episodeNumber)
                            put("title", ep.title)
                            put("streamUrl", ep.streamUrl)
                            put("overview", ep.overview)
                            put("duration", ep.duration)
                        })
                    }
                    put("episodes", eArr)
                }
                sArr.put(sObj)
            }
            put("seasons", sArr)
        }
        array.put(obj)
        prefs.edit().putString("local_series_json", array.toString()).apply()
    } catch (e: Exception) {
        Log.e("BingeBox", "Failed to save local series", e)
    }
}

fun fetchCloudSeries(cloudUrl: String): List<TvSeries> {
    if (cloudUrl.isBlank()) return emptyList()
    return try {
        val connection = URL(cloudUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "BingeBox/1.0")
        connection.connect()

        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = if (text.trim().startsWith("[")) {
            JSONArray(text)
        } else {
            val obj = JSONObject(text)
            obj.optJSONArray("series") ?: obj.optJSONArray("results") ?: JSONArray()
        }

        val list = mutableListOf<TvSeries>()
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            val seasonsArray = item.optJSONArray("seasons") ?: JSONArray()
            val seasonsList = mutableListOf<TvSeason>()
            for (s in 0 until seasonsArray.length()) {
                val sObj = seasonsArray.getJSONObject(s)
                val epArray = sObj.optJSONArray("episodes") ?: JSONArray()
                val epList = mutableListOf<TvEpisode>()
                for (e in 0 until epArray.length()) {
                    val eObj = epArray.getJSONObject(e)
                    epList.add(
                        TvEpisode(
                            episodeNumber = eObj.optInt("episodeNumber", e + 1),
                            title = eObj.optString("title", "Episode ${e + 1}"),
                            streamUrl = eObj.optString("streamUrl").ifBlank { eObj.optString("url") },
                            overview = eObj.optString("overview"),
                            duration = eObj.optString("duration")
                        )
                    )
                }
                seasonsList.add(
                    TvSeason(
                        seasonNumber = sObj.optInt("seasonNumber", s + 1),
                        name = sObj.optString("name", "Season ${s + 1}"),
                        episodes = epList
                    )
                )
            }
            list.add(
                TvSeries(
                    title = item.optString("title", "Cloud Series"),
                    posterUrl = item.optString("posterUrl").ifBlank { item.optString("poster_path") },
                    backdropUrl = item.optString("backdropUrl").ifBlank { item.optString("backdrop_path") },
                    overview = item.optString("overview"),
                    releaseDate = item.optString("releaseDate").ifBlank { item.optString("release_date") },
                    voteAverage = item.optDouble("voteAverage", item.optDouble("vote_average", 0.0)),
                    seasons = seasonsList
                )
            )
        }
        list
    } catch (e: Exception) {
        Log.e("BingeBox", "Failed to fetch cloud series", e)
        emptyList()
    }
}

// ============================================================
// MOVIES
// ============================================================

@Composable
fun MovieScreen(player: ExoPlayer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var movies by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var playingMovieUrl by remember { mutableStateOf<String?>(null) }

    fun loadAllMovies() {
        loading = true
        scope.launch {
            val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
            val cloudUrl = prefs.getString("cloud_movie_url", "").orEmpty()
            val m3uUrl = prefs.getString("playlist_url", "").orEmpty()

            val local = withContext(Dispatchers.IO) { loadLocalMovies(context) }
            val cloud = withContext(Dispatchers.IO) { fetchCloudMovies(cloudUrl) }
            val m3uMovies = withContext(Dispatchers.IO) { fetchM3uMovies(m3uUrl) }
            val tmdb = withContext(Dispatchers.IO) { fetchPopularMovies() }

            movies = (m3uMovies + local + cloud + tmdb).distinctBy { it.title }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadAllMovies()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Movies",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${movies.size} movies available",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            IconButton(
                onClick = { showSettingsDialog = true }
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Movie Settings", tint = Color.White)
            }
        }

        HorizontalDivider()

        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (movies.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No movies found. Configure cloud or local library.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Open Settings")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(movies) { movie ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (movie.streamUrl.isNotBlank()) {
                                    playingMovieUrl = movie.streamUrl
                                    try {
                                        player.setMediaItem(MediaItem.fromUri(movie.streamUrl))
                                        player.prepare()
                                        player.playWhenReady = true
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Unable to play movie", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    selectedMovie = movie
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp)
                        ) {
                            if (movie.posterUrl.isNotBlank()) {
                                AsyncImage(
                                    model = movie.posterUrl,
                                    contentDescription = movie.title,
                                    modifier = Modifier
                                        .width(85.dp)
                                        .height(125.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .width(85.dp)
                                        .height(125.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.DarkGray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White)
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = movie.title,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(4.dp))
                                if (movie.releaseDate.isNotBlank()) {
                                    Text(
                                        text = movie.releaseDate,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = movie.overview.ifBlank { "No overview available." },
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )

                                if (movie.streamUrl.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Surface(
                                        color = Color(0xFF252A38),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Play Stream", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        MovieLibrarySettingsDialog(
            onDismiss = { showSettingsDialog = false },
            onSaved = { loadAllMovies() }
        )
    }

    if (selectedMovie != null) {
        val m = selectedMovie!!
        Dialog(onDismissRequest = { selectedMovie = null }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1B1C25),
                border = BorderStroke(1.dp, Color(0xFF8AB4F8))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = m.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    if (m.releaseDate.isNotBlank()) {
                        Text(
                            text = "Release Date: ${m.releaseDate}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (m.overview.isNotBlank()) {
                        Text(
                            text = m.overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { selectedMovie = null }) {
                            Text("Close", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }

    if (playingMovieUrl != null) {
        Dialog(
            onDismissRequest = {
                player.stop()
                playingMovieUrl = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = {
                            player.stop()
                            playingMovieUrl = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun MovieLibrarySettingsDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE) }

    var cloudUrl by remember { mutableStateOf(prefs.getString("cloud_movie_url", "").orEmpty()) }
    var newTitle by remember { mutableStateOf("") }
    var newStreamUrl by remember { mutableStateOf("") }
    var newPosterUrl by remember { mutableStateOf("") }
    var newOverview by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Movie Library Settings", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.height(380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Cloud Server Movie Library", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = { cloudUrl = it },
                        label = { Text("Cloud Server Movie URL (JSON)") },
                        placeholder = { Text("https://mycloud.com/movies.json") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text("Add Local Movie", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Movie Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = newStreamUrl,
                        onValueChange = { newStreamUrl = it },
                        label = { Text("Video Stream URL (.mp4/.m3u8)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = newPosterUrl,
                        onValueChange = { newPosterUrl = it },
                        label = { Text("Poster Image URL (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = newOverview,
                        onValueChange = { newOverview = it },
                        label = { Text("Overview / Description (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Button(
                        onClick = {
                            if (newTitle.isNotBlank() && newStreamUrl.isNotBlank()) {
                                saveLocalMovie(
                                    context,
                                    Movie(
                                        title = newTitle.trim(),
                                        streamUrl = newStreamUrl.trim(),
                                        posterUrl = newPosterUrl.trim(),
                                        overview = newOverview.trim()
                                    )
                                )
                                newTitle = ""
                                newStreamUrl = ""
                                newPosterUrl = ""
                                newOverview = ""
                                Toast.makeText(context, "Local movie added!", Toast.LENGTH_SHORT).show()
                                onSaved()
                            } else {
                                Toast.makeText(context, "Title and Stream URL required", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add to Local Library")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit().putString("cloud_movie_url", cloudUrl.trim()).apply()
                    onSaved()
                    onDismiss()
                }
            ) {
                Text("Save & Refresh")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ============================================================
// TV SERIES
// ============================================================

@Composable
fun SeriesScreen(player: ExoPlayer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var seriesList by remember { mutableStateOf<List<TvSeries>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<TvSeries?>(null) }
    var playingEpisodeUrl by remember { mutableStateOf<String?>(null) }

    fun loadAllSeries() {
        loading = true
        scope.launch {
            val prefs = context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE)
            val cloudUrl = prefs.getString("cloud_series_url", "").orEmpty()
            val m3uUrl = prefs.getString("playlist_url", "").orEmpty()

            val local = withContext(Dispatchers.IO) { loadLocalSeries(context) }
            val cloud = withContext(Dispatchers.IO) { fetchCloudSeries(cloudUrl) }
            val m3uSeries = withContext(Dispatchers.IO) { fetchM3uSeries(m3uUrl) }

            seriesList = (m3uSeries + local + cloud).distinctBy { it.title }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadAllSeries()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TV Series",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${seriesList.size} series available (Local & Cloud)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            IconButton(
                onClick = { showSettingsDialog = true }
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Series Settings", tint = Color.White)
            }
        }

        HorizontalDivider()

        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (seriesList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No TV Series found. Configure your local or cloud library in settings.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Open Settings")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(seriesList) { series ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedSeries = series
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp)
                        ) {
                            if (series.posterUrl.isNotBlank()) {
                                AsyncImage(
                                    model = series.posterUrl,
                                    contentDescription = series.title,
                                    modifier = Modifier
                                        .width(85.dp)
                                        .height(125.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .width(85.dp)
                                        .height(125.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.DarkGray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color.White)
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = series.title,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(4.dp))
                                val seasonCount = series.seasons.size
                                val totalEpisodes = series.seasons.sumOf { it.episodes.size }
                                Text(
                                    text = "$seasonCount Seasons • $totalEpisodes Episodes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8AB4F8),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = series.overview.ifBlank { "No overview available." },
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        SeriesLibrarySettingsDialog(
            onDismiss = { showSettingsDialog = false },
            onSaved = { loadAllSeries() }
        )
    }

    // Series Seasons & Episodes Dialog
    if (selectedSeries != null) {
        val series = selectedSeries!!
        Dialog(
            onDismissRequest = { selectedSeries = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF15151B)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = series.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (series.overview.isNotBlank()) {
                                Text(
                                    text = series.overview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = { selectedSeries = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    if (series.seasons.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No seasons/episodes added for this series.", color = Color.White.copy(alpha = 0.7f))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            series.seasons.forEach { season ->
                                item {
                                    Text(
                                        text = season.name.ifBlank { "Season ${season.seasonNumber}" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF8AB4F8)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }

                                items(season.episodes) { episode ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (episode.streamUrl.isNotBlank()) {
                                                    playingEpisodeUrl = episode.streamUrl
                                                    try {
                                                        player.setMediaItem(MediaItem.fromUri(episode.streamUrl))
                                                        player.prepare()
                                                        player.playWhenReady = true
                                                    } catch (_: Exception) {
                                                        Toast.makeText(context, "Unable to play episode", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF202027))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.PlayCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF8AB4F8),
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "E${episode.episodeNumber}. ${episode.title}",
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                                if (episode.overview.isNotBlank()) {
                                                    Text(
                                                        text = episode.overview,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.White.copy(alpha = 0.7f),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (playingEpisodeUrl != null) {
        Dialog(
            onDismissRequest = {
                player.stop()
                playingEpisodeUrl = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = {
                            player.stop()
                            playingEpisodeUrl = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesLibrarySettingsDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bingebox_iptv", Context.MODE_PRIVATE) }

    var cloudUrl by remember { mutableStateOf(prefs.getString("cloud_series_url", "").orEmpty()) }
    var newSeriesTitle by remember { mutableStateOf("") }
    var newEpTitle by remember { mutableStateOf("") }
    var newEpStreamUrl by remember { mutableStateOf("") }
    var newPosterUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TV Series Settings", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.height(380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Cloud Server TV Series Library", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = { cloudUrl = it },
                        label = { Text("Cloud Server Series URL (JSON)") },
                        placeholder = { Text("https://mycloud.com/series.json") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text("Add Local Series Episode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newSeriesTitle,
                        onValueChange = { newSeriesTitle = it },
                        label = { Text("Series Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = newEpTitle,
                        onValueChange = { newEpTitle = it },
                        label = { Text("Episode Title (e.g. S1E1 - Pilot)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = newEpStreamUrl,
                        onValueChange = { newEpStreamUrl = it },
                        label = { Text("Episode Video Stream URL (.mp4/.m3u8)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = newPosterUrl,
                        onValueChange = { newPosterUrl = it },
                        label = { Text("Poster Image URL (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    Button(
                        onClick = {
                            if (newSeriesTitle.isNotBlank() && newEpStreamUrl.isNotBlank()) {
                                val existingList = loadLocalSeries(context).toMutableList()
                                val existingSeriesIdx = existingList.indexOfFirst { it.title.equals(newSeriesTitle.trim(), ignoreCase = true) }
                                val epTitle = if (newEpTitle.isNotBlank()) newEpTitle.trim() else "Episode 1"

                                val newEpisode = TvEpisode(
                                    episodeNumber = 1,
                                    title = epTitle,
                                    streamUrl = newEpStreamUrl.trim()
                                )

                                if (existingSeriesIdx >= 0) {
                                    val existing = existingList[existingSeriesIdx]
                                    val updatedSeasons = existing.seasons.toMutableList()
                                    if (updatedSeasons.isEmpty()) {
                                        updatedSeasons.add(TvSeason(seasonNumber = 1, name = "Season 1", episodes = listOf(newEpisode)))
                                    } else {
                                        val firstSeason = updatedSeasons[0]
                                        val updatedEps = firstSeason.episodes + newEpisode
                                        updatedSeasons[0] = firstSeason.copy(episodes = updatedEps)
                                    }
                                    existingList[existingSeriesIdx] = existing.copy(seasons = updatedSeasons)
                                } else {
                                    val newSeries = TvSeries(
                                        title = newSeriesTitle.trim(),
                                        posterUrl = newPosterUrl.trim(),
                                        seasons = listOf(TvSeason(seasonNumber = 1, name = "Season 1", episodes = listOf(newEpisode)))
                                    )
                                    existingList.add(newSeries)
                                }

                                saveLocalSeries(context, existingList.last())
                                newSeriesTitle = ""
                                newEpTitle = ""
                                newEpStreamUrl = ""
                                newPosterUrl = ""
                                Toast.makeText(context, "Local series episode added!", Toast.LENGTH_SHORT).show()
                                onSaved()
                            } else {
                                Toast.makeText(context, "Series Title and Episode Stream URL required", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add to Local Series Library")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit().putString("cloud_series_url", cloudUrl.trim()).apply()
                    onSaved()
                    onDismiss()
                }
            ) {
                Text("Save & Refresh")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ============================================================
// SOCIAL MEDIA SCREEN
// ============================================================

@Composable
fun SocialScreen() {
    var selectedUrl by remember { mutableStateOf("https://www.youtube.com") }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Social Media & Video",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Browse YouTube, TikTok, Twitch & more",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        HorizontalDivider()

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Button(
                    onClick = { selectedUrl = "https://www.youtube.com" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000))
                ) {
                    Text("YouTube", fontWeight = FontWeight.Bold)
                }
            }
            item {
                Button(
                    onClick = { selectedUrl = "https://www.tiktok.com" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF010101))
                ) {
                    Text("TikTok", fontWeight = FontWeight.Bold)
                }
            }
            item {
                Button(
                    onClick = { selectedUrl = "https://www.twitch.tv" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9146FF))
                ) {
                    Text("Twitch", fontWeight = FontWeight.Bold)
                }
            }
            item {
                Button(
                    onClick = { selectedUrl = "https://twitter.com" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DA1F2))
                ) {
                    Text("X / Twitter", fontWeight = FontWeight.Bold)
                }
            }
            item {
                Button(
                    onClick = { selectedUrl = "https://www.reddit.com" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4500))
                ) {
                    Text("Reddit", fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(selectedUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != selectedUrl) {
                        webView.loadUrl(selectedUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

fun fetchPopularMovies(): List<Movie> {

    val apiKey =
        "8b32d99f943f72e9441eff5dc6da83ac"

    val url =
        "https://api.themoviedb.org/3/movie/popular" +
                "?api_key=$apiKey" +
                "&language=en-GB" +
                "&page=1"

    val connection =
        URL(url)
            .openConnection()
                as HttpURLConnection

    connection.connectTimeout =
        15_000

    connection.readTimeout =
        20_000

    val text =
        connection.inputStream
            .bufferedReader()
            .use {
                it.readText()
            }

    connection.disconnect()

    val json =
        JSONObject(text)

    val results =
        json.optJSONArray(
            "results"
        ) ?: return emptyList()

    val movies =
        mutableListOf<Movie>()

    for (
    i in 0 until results.length()
    ) {

        val item =
            results.getJSONObject(i)

        val posterPath =
            item.optString(
                "poster_path"
            )

        val backdropPath =
            item.optString(
                "backdrop_path"
            )

        movies.add(
            Movie(

                title =
                    item.optString(
                        "title"
                    ),

                posterUrl =
                    if (
                        posterPath.isNotBlank()
                    ) {

                        "https://image.tmdb.org/t/p/w500$posterPath"

                    } else {

                        ""
                    },

                backdropUrl =
                    if (
                        backdropPath.isNotBlank()
                    ) {

                        "https://image.tmdb.org/t/p/w1280$backdropPath"

                    } else {

                        ""
                    },

                overview =
                    item.optString(
                        "overview"
                    ),

                releaseDate =
                    item.optString(
                        "release_date"
                    ),

                voteAverage =
                    item.optDouble(
                        "vote_average",
                        0.0
                    )
            )
        )
    }

    return movies
}