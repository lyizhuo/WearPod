package site.whitezaak.wearpod.presentation

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import site.whitezaak.wearpod.presentation.navigation.Screen
import site.whitezaak.wearpod.presentation.screens.FeedScreen
import site.whitezaak.wearpod.presentation.screens.HomeScreen
import site.whitezaak.wearpod.presentation.screens.InBoxScreen
import site.whitezaak.wearpod.presentation.screens.DownloadsScreen
import site.whitezaak.wearpod.presentation.screens.LibraryScreen
import site.whitezaak.wearpod.presentation.screens.PlayerScreen
import site.whitezaak.wearpod.presentation.screens.LanguageSettingsScreen
import site.whitezaak.wearpod.presentation.screens.SettingsScreen
import site.whitezaak.wearpod.presentation.screens.SleepTimerScreen
import androidx.wear.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun WearPodApp(
    openPlayerRequestNonce: Long = 0L,
    viewModel: MainViewModel = viewModel()
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context as? Activity
    val podcasts by viewModel.podcasts.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val inboxEpisodes by viewModel.inboxEpisodes.collectAsState()
    val visibleInboxEpisodes by viewModel.visibleInboxEpisodes.collectAsState()
    val hasMoreInboxEpisodes by viewModel.hasMoreInboxEpisodes.collectAsState()
    val currentPlayingEpisode by viewModel.currentPlayingEpisode.collectAsState()
    val isLoadingFeed by viewModel.isLoadingFeed.collectAsState()
    val downloadingEpisodes by viewModel.downloadingEpisodes.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val currentLanguageTag by viewModel.appLanguageTag.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.uiMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(openPlayerRequestNonce, currentPlayingEpisode?.audioUrl) {
        val audioUrl = currentPlayingEpisode?.audioUrl ?: return@LaunchedEffect
        if (openPlayerRequestNonce == 0L) return@LaunchedEffect
        navController.navigateSingleTop(Screen.Player.createRoute(audioUrl))
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route
        ) {
        composable(Screen.Home.route) {
            val isPlaying by viewModel.isPlaying.collectAsState()
            HomeScreen(
                podcasts = podcasts,
                currentPlayingEpisode = currentPlayingEpisode,
                isPlaying = isPlaying,
                onPodcastClick = { 
                    navController.navigate(Screen.Library.route)
                },
                onPlayerClick = {
                    val url = currentPlayingEpisode?.audioUrl ?: "demo_url"
                    navController.navigateFromHomeToPlayer(Screen.Player.createRoute(url))
                },
                onHomeClick = {
                    navController.navigateSingleTop(Screen.HomeFeed.route)
                },
                onDownloadsClick = {
                    navController.navigateSingleTop(Screen.Downloads.route)
                },
                onSettingsClick = {
                    navController.navigateSingleTop(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            val customOpmlId by viewModel.customOpmlId.collectAsState()
            SettingsScreen(
                currentOpmlId = customOpmlId,
                onLoadOpml = { id -> 
                    viewModel.loadCustomOpml(id)
                    navController.popBackStack()
                },
                onLanguageClick = {
                    navController.navigateSingleTop(Screen.SettingsLanguage.route)
                }
            )
        }

        composable(Screen.SettingsLanguage.route) {
            LanguageSettingsScreen(
                selectedLanguageTag = currentLanguageTag,
                onLanguageSelected = { languageTag ->
                    if (languageTag != currentLanguageTag) {
                        viewModel.setAppLanguage(languageTag)
                        activity?.recreate()
                    }
                }
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                podcasts = podcasts,
                onPodcastClick = { index ->
                    navController.navigateSingleTop(Screen.Feed.createRoute(index))
                }
            )
        }

        composable(Screen.HomeFeed.route) {
            val isRefreshing by viewModel.isRefreshingInbox.collectAsState()
            LaunchedEffect(Unit) {
                viewModel.onInboxScreenEntered()
            }
            DisposableEffect(Unit) {
                onDispose {
                    viewModel.onInboxScreenExited()
                }
            }
            InBoxScreen(
                episodes = visibleInboxEpisodes,
                hasMoreEpisodes = hasMoreInboxEpisodes,
                isRefreshing = isRefreshing,
                onEpisodeClick = { audioUrl ->
                    navController.navigateSingleTop(Screen.EpisodeDetail.createRoute(audioUrl))
                },
                onLoadMoreClick = {
                    viewModel.loadMoreInboxEpisodes()
                }
            )
        }

        composable(Screen.Downloads.route) {
            val downloads by viewModel.downloadedEpisodes.collectAsState()
            DownloadsScreen(
                downloads = downloads,
                downloading = downloadingEpisodes,
                progressMap = downloadProgress,
                onEpisodeClick = { episode ->
                    viewModel.playEpisode(episode)
                    navController.navigateSingleTop(Screen.Player.createRoute(episode.audioUrl))
                },
                onRemoveDownload = { episode ->
                    viewModel.deleteDownloadedEpisode(episode)
                }
            )
        }

        composable(Screen.Feed.route) { backStackEntry ->
            val indexStr = backStackEntry.arguments?.getString("podcastIndex")
            val index = indexStr?.toIntOrNull() ?: 0
            val podcast = podcasts.getOrNull(index)

            if (podcast != null) {
                LaunchedEffect(podcast.feedUrl) {
                    viewModel.onFeedScreenEntered(podcast.feedUrl)
                }
                DisposableEffect(podcast.feedUrl) {
                    onDispose {
                        viewModel.onFeedScreenExited(podcast.feedUrl)
                    }
                }
                FeedScreen(
                    podcast = podcast,
                    episodes = episodes,
                    isLoading = isLoadingFeed,
                    onEpisodeClick = { audioUrl ->
                        navController.navigateSingleTop(Screen.EpisodeDetail.createRoute(audioUrl))
                    }
                )
            } else {
                LaunchedEffect(index, podcasts.size) {
                    // Prevent blank screen when feed index becomes stale after data refresh.
                    if (!navController.popBackStack()) {
                        navController.navigateSingleTop(Screen.Library.route)
                    }
                }
            }
        }

        composable(Screen.EpisodeDetail.route) { backStackEntry ->
            val audioUrl = backStackEntry.arguments?.getString("episodeUrl")
                ?.let { Screen.EpisodeDetail.decodeRouteArg(it) }
                ?: ""
            val resolvedEpisode = viewModel.resolveEpisodeByAudioUrl(audioUrl)
            val retainedEpisode = remember(audioUrl) { mutableStateOf<site.whitezaak.wearpod.domain.Episode?>(null) }

            LaunchedEffect(resolvedEpisode?.audioUrl) {
                if (resolvedEpisode != null) {
                    retainedEpisode.value = resolvedEpisode
                }
            }

            val episode = resolvedEpisode ?: retainedEpisode.value

            LaunchedEffect(audioUrl) {
                viewModel.suspendBrowsingDataLoads()
            }
            
            if (episode != null) {
                site.whitezaak.wearpod.presentation.screens.EpisodeDetailScreen(
                    episode = episode,
                    onPlayClick = {
                        viewModel.playEpisode(episode)
                        navController.navigateSingleTop(Screen.Player.createRoute(audioUrl))
                    },
                    onPodcastTitleClick = {
                        val index = podcasts.indexOfFirst {
                            it.title.equals(episode.podcastTitle, ignoreCase = true)
                        }
                        if (index != -1) {
                            navController.navigateSingleTop(Screen.Feed.createRoute(index))
                        }
                    },
                    onQueueClick = {
                        viewModel.addToPlaylist(episode)
                    },
                    onDownloadClick = { 
                        viewModel.downloadEpisode(episode)
                    }
                )
            } else {
                LaunchedEffect(audioUrl, episodes.size, inboxEpisodes.size, currentPlayingEpisode?.audioUrl) {
                    delay(450)
                    if (viewModel.resolveEpisodeByAudioUrl(audioUrl) == null) {
                        if (!navController.popBackStack()) {
                            navController.navigateSingleTop(Screen.Home.route)
                        }
                    }
                }
            }
        }

        composable(Screen.Playlist.route) {
            val playlist by viewModel.playlist.collectAsState()
            site.whitezaak.wearpod.presentation.screens.PlaylistScreen(
                playlist = playlist,
                onEpisodeClick = { episode ->
                    // Clear and play, or just play? Let's just play the selected episode and leave queue alone as requested by simple UX
                    viewModel.playEpisode(episode)
                    navController.navigateSingleTop(Screen.Player.createRoute(episode.audioUrl))
                },
                onRemoveEpisode = { episode ->
                    viewModel.removeFromPlaylist(episode)
                }
            )
        }

        composable(Screen.Player.route) { backStackEntry ->
            val audioUrl = backStackEntry.arguments?.getString("episodeUrl")
                ?.let { Screen.Player.decodeRouteArg(it) }
                ?: ""
            val playerEnteredAtMs = remember(audioUrl) { mutableLongStateOf(0L) }
            val resolvedEpisode = viewModel.resolveEpisodeByAudioUrl(audioUrl)
            val retainedEpisode = remember(audioUrl) { mutableStateOf<site.whitezaak.wearpod.domain.Episode?>(null) }

            LaunchedEffect(resolvedEpisode?.audioUrl) {
                if (resolvedEpisode != null) {
                    retainedEpisode.value = resolvedEpisode
                }
            }

            val episode = resolvedEpisode ?: retainedEpisode.value

            LaunchedEffect(audioUrl) {
                viewModel.suspendBrowsingDataLoads()
                playerEnteredAtMs.longValue = SystemClock.elapsedRealtime()
                viewModel.onPlayerScreenEntered()
            }
            DisposableEffect(Unit) {
                onDispose {
                    viewModel.onPlayerScreenExited()
                }
            }
            
            val isPlaying by viewModel.isPlaying.collectAsState()
            val isBuffering by viewModel.isBuffering.collectAsState()
            val currentDuration by viewModel.currentDuration.collectAsState()

            PlayerScreen(
                episode = episode,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                currentPositionFlow = viewModel.currentPosition,
                currentDuration = currentDuration,
                onTitleClick = {
                    val elapsed = SystemClock.elapsedRealtime() - playerEnteredAtMs.longValue
                    if (episode != null && elapsed >= 600L) {
                        navController.navigateSingleTop(Screen.EpisodeDetail.createRoute(episode.audioUrl))
                    }
                },
                onPodcastTitleClick = {
                    val elapsed = SystemClock.elapsedRealtime() - playerEnteredAtMs.longValue
                    if (episode != null && elapsed >= 600L) {
                        val index = podcasts.indexOfFirst { 
                            it.title.equals(episode.podcastTitle, ignoreCase = true)
                        }
                        if (index != -1) {
                            navController.navigateSingleTop(Screen.Feed.createRoute(index))
                        }
                    }
                },
                onPlayPause = {
                    if (episode != null) {
                       if (isPlaying) {
                           viewModel.togglePlayPause()
                       } else {
                           viewModel.playEpisode(episode)
                       }
                    } 
                },
                onSkipBackward = { viewModel.skipBackward() },
                onSkipForward = { viewModel.skipForward() },
                onSeekTo = { positionMs -> viewModel.seekTo(positionMs) },
                onPlaylistClick = { navController.navigateSingleTop(Screen.Playlist.route) },
                onVolumeClick = { viewModel.openVolumeControl() },
                onSleepTimerClick = { navController.navigateSingleTop(Screen.SleepTimer.route) }
            )
        }

        composable(Screen.SleepTimer.route) {
            val currentMode by viewModel.currentSleepTimerMode.collectAsState()
            val remainingMs by viewModel.currentSleepTimerRemainingMs.collectAsState()
            SleepTimerScreen(
                currentTimerMode = currentMode,
                currentTimerRemainingMs = remainingMs,
                onSelectTimer = { mode ->
                    viewModel.setSleepTimer(mode)
                }
            )
        }
    }
}
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}

private fun NavHostController.navigateFromHomeToPlayer(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(Screen.Home.route) {
            saveState = true
        }
        restoreState = true
    }
}
