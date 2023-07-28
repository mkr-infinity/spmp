package com.toasterofbread.spmp.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.session.BitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.cash.sqldelight.Query
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.toasterofbread.Database
import com.toasterofbread.spmp.api.setSongLiked
import com.toasterofbread.spmp.exovisualiser.FFTAudioProcessor
import com.toasterofbread.spmp.model.mediaitem.loader.MediaItemThumbnailLoader
import com.toasterofbread.spmp.model.mediaitem.MediaItemThumbnailProvider
import com.toasterofbread.spmp.model.mediaitem.Song
import com.toasterofbread.spmp.model.mediaitem.SongData
import com.toasterofbread.spmp.model.mediaitem.SongLikedStatus
import com.toasterofbread.spmp.model.mediaitem.toSongLikedStatus
import com.toasterofbread.spmp.model.mediaitem.toThumbnailProvider
import com.toasterofbread.spmp.resources.getStringTODO
import com.toasterofbread.spmp.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val NOTIFICATION_ID = 2
private const val NOTIFICATION_CHANNEL_ID = "playback_channel"

private const val COMMAND_SET_LIKE_TRUE = "com.toasterofbread.spmp.setliketrue"
private const val COMMAND_SET_LIKE_NEUTRAL = "com.toasterofbread.spmp.setlikeneutral"

private const val A13_MEDIA_NOTIFICATION_ASPECT = 2.9f / 5.7f

fun getMediaNotificationImageMaxOffset(image: Bitmap): IntOffset {
    val dimensions = getMediaNotificationImageSize(image)
    return IntOffset(
        (image.width - dimensions.width) / 2,
        (image.height - dimensions.height) / 2
    )
}

fun getMediaNotificationImageSize(image: Bitmap): IntSize {
    val aspect = if (Build.VERSION.SDK_INT >= 33) A13_MEDIA_NOTIFICATION_ASPECT else 1f
    if (image.width > image.height) {
        return IntSize(
            image.height,
            (image.height * aspect).roundToInt()
        )
    }
    else {
        return IntSize(
            image.width,
            (image.width * aspect).roundToInt()
        )
    }
}

private fun formatMediaNotificationImage(
    image: Bitmap,
    song: Song,
    db: Database
): Bitmap {
    val notif_image_offset = db.songQueries.notifImageOffsetById(song.id).executeAsOne()

    val dimensions = getMediaNotificationImageSize(image)
    val offset = IntOffset(
        notif_image_offset.notif_image_offset_x?.toInt() ?: 0,
        notif_image_offset.notif_image_offset_y?.toInt() ?: 0
    )

    return Bitmap.createBitmap(
        image,
        (((image.width - dimensions.width) / 2) + offset.x).coerceIn(0, image.width - dimensions.width),
        (((image.height - dimensions.height) / 2) + offset.y).coerceIn(0, image.height - dimensions.height),
        dimensions.width,
        dimensions.height
    )
}

@UnstableApi
class MediaPlayerServiceSession: MediaSessionService() {
    private val coroutine_scope = CoroutineScope(Dispatchers.Main)
    private val context = PlatformContext(this)
    private lateinit var player: ExoPlayer
    private lateinit var media_session: MediaSession
    private lateinit var notification_builder: NotificationCompat.Builder

    private var current_song: Song? = null

    private inner class PlayerListener: Player.Listener {
        private fun onSongLikeStatusChanged() {
            val liked_status = current_song?.let { song ->
                context.database.songQueries.likedById(song.id).executeAsOne().liked.toSongLikedStatus()
            }

            coroutine_scope.launch {
                updatePlayerCustomActions(liked_status)
            }
        }

        val song_liked_listener = Query.Listener {

            onSongLikeStatusChanged()
        }

        override fun onMediaItemTransition(media_item: MediaItem?, reason: Int) {
            val song = media_item?.getSong()
            if (song?.id == current_song?.id) {
                return
            }

            with(context.database.songQueries) {
                current_song?.also {
                    likedById(it.id).removeListener(song_liked_listener)
                }
                song?.also {
                    likedById(it.id).addListener(song_liked_listener)
                }
            }

            current_song = song
            onSongLikeStatusChanged()
        }
    }

    companion object {
        // If there's a better way to provide this to MediaControllers, I'd like to know
        val audio_processor = FFTAudioProcessor()
    }

    override fun onCreate() {
        super.onCreate()

        initialiseSessionAndPlayer()
        createNotificationChannel()
        updatePlayerCustomActions(null)

        notification_builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_spmp)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(media_session))
            .setContentIntent(PendingIntent.getActivity(
                this,
                1,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE
            ))

        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                synchronized(coroutine_scope) {
                    coroutine_scope.coroutineContext.cancelChildren()
                    coroutine_scope.launch {
                        updatePlayerNotification()
                    }
                }
                return MediaNotification(NOTIFICATION_ID, notification_builder.build())
            }

            override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean {
                TODO("Action $action")
            }
        })
    }

    override fun onDestroy() {
        coroutine_scope.cancel()
        player.release()
        media_session.release()
        clearListener()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun updatePlayerCustomActions(liked: SongLikedStatus?) {
        val like_action: CommandButton? =
            if (liked != null)
                CommandButton.Builder()
                    .setDisplayName(getStringTODO("TODO"))
                    .setSessionCommand(SessionCommand(
                        if (liked == SongLikedStatus.NEUTRAL) COMMAND_SET_LIKE_TRUE else COMMAND_SET_LIKE_NEUTRAL,
                        Bundle.EMPTY
                    ))
                    .setIconResId(if (liked == SongLikedStatus.LIKED) R.drawable.ic_thumb_up else R.drawable.ic_thumb_up_off)
                    .build()
            else null

        media_session.setCustomLayout(listOfNotNull(
            like_action
        ))
    }

    private suspend fun updatePlayerNotification() {
        val song = media_session.player.currentMediaItem?.getSong()

        notification_builder.apply {
            if (Build.VERSION.SDK_INT < 33) {
                val large_icon: Bitmap? =
                    if (song != null) getCurrentLargeIcon(song)
                    else null
                setLargeIcon(large_icon)
            }
            setContentTitle(song?.title ?: "")
            setContentText(song?.artist?.title ?: "")
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification_builder.build())
    }

    private suspend fun getCurrentLargeIcon(song: Song): Bitmap? {
        try {
            val thumbnail_provider =
                context.database.mediaItemQueries.thumbnailProviderById(song.id).executeAsOne().toThumbnailProvider()
                ?: return null

            val result = MediaItemThumbnailLoader.loadItemThumbnail(song, thumbnail_provider, MediaItemThumbnailProvider.Quality.HIGH, context)
            result.onSuccess { image ->
                return formatMediaNotificationImage(image.asAndroidBitmap(), song, context.database)
            }

            return null
        }
        catch (e: IndexOutOfBoundsException) {
            return null
        }
    }

    private fun initialiseSessionAndPlayer() {
        val renderers_factory = RenderersFactory { handler: Handler?, _, audioListener: AudioRendererEventListener?, _, _ ->
            arrayOf(
                MediaCodecAudioRenderer(
                    this,
                    MediaCodecSelector.DEFAULT,
                    handler,
                    audioListener,
                    AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES,
                    audio_processor
                )
            )
        }

        player = ExoPlayer.Builder(
            this,
            renderers_factory,
            DefaultMediaSourceFactory(
                createDataSourceFactory(),
                { arrayOf(MatroskaExtractor(), FragmentedMp4Extractor()) }
            )
            .setLoadErrorHandlingPolicy(
                object : LoadErrorHandlingPolicy {
                    override fun getFallbackSelectionFor(
                        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
                    ): LoadErrorHandlingPolicy.FallbackSelection? {
                        return null
                    }

                    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                        return 1000
                    }

                    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
                        return Int.MAX_VALUE
                    }
                }
            )
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setUsePlatformDiagnostics(false)
            .build()
            .apply {
                addListener(PlayerListener())
                playWhenReady = true
                prepare()
            }

        media_session = MediaSession.Builder(this, player)
            .setBitmapLoader(object : BitmapLoader {
                val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

                override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
                    throw NotImplementedError()
                }

                override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                    val song = SongData(uri.toString())
                    return executor.submit<Bitmap> {
                        runBlocking {
                            val result = MediaItemThumbnailLoader.loadItemThumbnail(song, MediaItemThumbnailProvider.Quality.HIGH, context)
                            formatMediaNotificationImage(
                                result.getOrThrow().asAndroidBitmap(),
                                song,
                                context.database
                            )
                        }
                    }
                }
            })
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    media_session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    media_items: List<MediaItem>
                ): ListenableFuture<List<MediaItem>> {
                    val updated_media_items = media_items.map { item ->
                        item.buildUpon()
                            .setUri(item.requestMetadata.mediaUri)
                            .setMediaId(item.requestMetadata.mediaUri.toString())
                            .build()
                    }
                    return Futures.immediateFuture(updated_media_items)
                }

                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val result = super.onConnect(session, controller)
                    val session_commands = result.availableSessionCommands
                        .buildUpon()
                        .add(SessionCommand(COMMAND_SET_LIKE_TRUE, Bundle()))
                        .add(SessionCommand(COMMAND_SET_LIKE_NEUTRAL, Bundle()))
                    return MediaSession.ConnectionResult.accept(session_commands.build(), result.availablePlayerCommands)
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    val song = current_song
                    if (song != null) {
                        when (customCommand.customAction) {
                            COMMAND_SET_LIKE_TRUE ->
                                coroutine_scope.launch {
                                    setSongLiked(song.id, SongLikedStatus.LIKED, context.database)
                                }
                            COMMAND_SET_LIKE_NEUTRAL ->
                                coroutine_scope.launch {
                                    setSongLiked(song.id, SongLikedStatus.NEUTRAL, context.database)
                                }
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory({
            DefaultDataSource.Factory(this).createDataSource()
        }) { data_spec: DataSpec ->
            try {
                return@Factory processMediaDataSpec(data_spec, context, isConnectionMetered())
            }
            catch (e: Throwable) {
                throw IOException(e)
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }

        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "TODO",
                NotificationManager.IMPORTANCE_LOW
            )
            .apply {
                enableVibration(false)
            }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return media_session
    }
}