package com.example.wearpod.presentation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import com.example.wearpod.presentation.navigation.Screen
import com.example.wearpod.presentation.screens.FeedScreen
import com.example.wearpod.presentation.screens.HomeScreen
import com.example.wearpod.presentation.screens.InBoxScreen
import com.example.wearpod.presentation.screens.DownloadsScreen
import com.example.wearpod.presentation.screens.LibraryScreen
import com.example.wearpod.presentation.screens.PlayerScreen
import com.example.wearpod.presentation.screens.SettingsScreen
import com.example.wearpod.presentation.screens.SleepTimerScreen
import androidx.wear.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

@Composable
fun WearPodApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val podcasts by viewModel.podcasts.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val inboxEpisodes by viewModel.inboxEpisodes.collectAsState()
    val visibleInboxEpisodes by viewModel.visibleInboxEpisodes.collectAsState()
    val hasMoreInboxEpisodes by viewModel.hasMoreInboxEpisodes.collectAsState()
    val currentPlayingEpisode by viewModel.currentPlayingEpisode.collectAsState()
    val isLoadingFeed by viewModel.isLoadingFeed.collectAsState()

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
                    navController.navigateSingleTop(Screen.Player.createRoute(Uri.encode(url)))
                },
                onHomeClick = {
                    viewModel.loadInboxEpisodes()
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
                }
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                podcasts = podcasts,
                onPodcastClick = { index ->
                    val podcast = podcasts[index]
                    viewModel.loadEpisodes(podcast.feedUrl)
                    navController.navigateSingleTop(Screen.Feed.createRoute(index))
                }
            )
        }

        composable(Screen.HomeFeed.route) {
            val isRefreshing by viewModel.isRefreshingInbox.collectAsState()
            InBoxScreen(
                episodes = visibleInboxEpisodes,
                hasMoreEpisodes = hasMoreInboxEpisodes,
                isRefreshing = isRefreshing,
                onEpisodeClick = { audioUrl ->
                    val encodedUrl = Uri.encode(audioUrl)
                    navController.navigateSingleTop(Screen.EpisodeDetail.createRoute(encodedUrl))
                },
                onLoadMoreClick = {
                    viewModel.loadMoreInboxEpisodes()
                },
                onRefreshClick = {
                    viewModel.forceRefreshInbox()
                }
            )
        }

        composable(Screen.Downloads.route) {
            val downloads by viewModel.downloadedEpisodes.collectAsState()
            DownloadsScreen(
                downloads = downloads,
                onEpisodeClick = { episode ->
                    viewModel.playEpisode(episode)
                    val encodedUrl = Uri.encode(episode.audioUrl)
                    navController.navigateSingleTop(Screen.Player.createRoute(encodedUrl))
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
                FeedScreen(
                    podcast = podcast,
                    episodes = episodes,
                    isLoading = isLoadingFeed,
                    onEpisodeClick = { audioUrl ->
                        val encodedUrl = Uri.encode(audioUrl)
                        navController.navigateSingleTop(Screen.EpisodeDetail.createRoute(encodedUrl))
                    }
                )
            }
        }

        composable(Screen.EpisodeDetail.route) { backStackEntry ->
            val audioUrl = backStackEntry.arguments?.getString("episodeUrl")?.let { Uri.decode(it) } ?: ""
            val episode = episodes.find { it.audioUrl == audioUrl } ?: inboxEpisodes.find { it.audioUrl == audioUrl }
            
            if (episode != null) {
                com.example.wearpod.presentation.screens.EpisodeDetailScreen(
                    episode = episode,
                    onPlayClick = {
                        viewModel.playEpisode(episode)
                        val encodedUrl = Uri.encode(audioUrl)
                        navController.navigateSingleTop(Screen.Player.createRoute(encodedUrl))
                    },
                    onQueueClick = {
                        viewModel.addToPlaylist(episode)
                    },
                    onDownloadClick = { 
                        viewModel.downloadEpisode(episode)
                    }
                )
            }
        }

        composable(Screen.Playlist.route) {
            val playlist by viewModel.playlist.collectAsState()
            com.example.wearpod.presentation.screens.PlaylistScreen(
                playlist = playlist,
                onEpisodeClick = { episode ->
                    // Clear and play, or just play? Let's just play the selected episode and leave queue alone as requested by simple UX
                    viewModel.playEpisode(episode)
                    val encodedUrl = Uri.encode(episode.audioUrl)
                    navController.navigateSingleTop(Screen.Player.createRoute(encodedUrl))
                },
                onRemoveEpisode = { episode ->
                    viewModel.removeFromPlaylist(episode)
                }
            )
        }

        composable(Screen.Player.route) { backStackEntry ->
            val audioUrl = backStackEntry.arguments?.getString("episodeUrl")?.let { Uri.decode(it) } ?: ""
            // Find episode in both lists, or fallback to the currently playing episode track
            var episode = episodes.find { it.audioUrl == audioUrl } ?: inboxEpisodes.find { it.audioUrl == audioUrl }
            if (episode == null && currentPlayingEpisode?.audioUrl == audioUrl) {
                episode = currentPlayingEpisode
            }

            LaunchedEffect(audioUrl) {
                viewModel.onPlayerScreenEntered()
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
                    if (episode != null) {
                        val encodedUrl = Uri.encode(episode.audioUrl)
                        navController.navigateSingleTop(Screen.EpisodeDetail.createRoute(encodedUrl))
                    }
                },
                onPodcastTitleClick = {
                    if (episode != null) {
                        val index = podcasts.indexOfFirst { it.title == episode.podcastTitle }
                        if (index != -1) {
                            viewModel.loadEpisodes(podcasts[index].feedUrl)
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
            SleepTimerScreen(
                currentTimerMode = currentMode,
                onSelectTimer = { mode, minutes ->
                    viewModel.setSleepTimer(mode, minutes)
                    navController.popBackStack()
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
