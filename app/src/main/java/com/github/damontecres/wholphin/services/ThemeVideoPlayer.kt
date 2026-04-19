package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.github.damontecres.wholphin.preferences.ThemeSongVolume
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple service to play theme videos
 */

@OptIn(UnstableApi::class)
@Singleton
class ThemeVideoPlayer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:AuthOkHttpClient private val authOkHttpClient: OkHttpClient,
        private val api: ApiClient,
    ) {
        private val _playerFlow = MutableStateFlow<Player?>(null)
        val playerFlow: StateFlow<Player?> = _playerFlow

        private var _player: ExoPlayer? = null

        private fun getOrCreatePlayer(): ExoPlayer =
            _player ?: ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(OkHttpDataSource.Factory(authOkHttpClient)),
                ).build()
                .also { exo ->
                    exo.repeatMode = Player.REPEAT_MODE_OFF
                    exo.addListener(object: Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                _playerFlow.value = null
                            }
                        }
                    })
                    _player = exo
                }

        suspend fun playThemeVideoFor(
            itemId: UUID,
            volume: ThemeSongVolume,
        ): Boolean =
            withContext(Dispatchers.IO) {
                if (volume == ThemeSongVolume.DISABLED || volume == ThemeSongVolume.UNRECOGNIZED) {
                    return@withContext false
                }
                val themeVideos by api.libraryApi.getThemeVideos(itemId)
                val video =
                    themeVideos.items.randomOrNull() ?: run {
                        Timber.v("No theme video for $itemId")
                        return@withContext false
                    }
                val url =
                    api.videosApi.getVideoStreamUrl(
                        itemId = video.id,
                        static = true,
                    )
                val volumeLevel =
                    when (volume) {
                        ThemeSongVolume.UNRECOGNIZED,
                        ThemeSongVolume.DISABLED,
                        -> return@withContext false
                        ThemeSongVolume.LOWEST -> .05f
                        ThemeSongVolume.LOW -> .1f
                        ThemeSongVolume.MEDIUM -> .25f
                        ThemeSongVolume.HIGH -> .5f
                        ThemeSongVolume.HIGHEST -> .75f
                    }
                Timber.v("Playing theme video for $itemId")
                withContext(Dispatchers.Main) {
                    getOrCreatePlayer().apply {
                        stop()
                        this.volume = volumeLevel
                        setMediaItem(MediaItem.fromUri(url))
                        prepare()
                        play()
                    }
                    _playerFlow.value = _player
                }
                true
            }

        fun stop() {
            _player?.let { p ->
                if (p.isPlaying) {
                    Timber.v("Stopping theme video")
                    p.stop()
                }
            }
            _playerFlow.value = null
        }
    }
