package com.toasterofbread.spmp.ui.component

import LocalPlayerState
import SpMp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toasterofbread.spmp.model.mediaitem.MediaItemPreviewParams
import com.toasterofbread.spmp.model.mediaitem.Song
import com.toasterofbread.spmp.platform.vibrateShort

class LongPressMenuActionProvider(
    val content_colour: () -> Color,
    val accent_colour: () -> Color,
    val background_colour: () -> Color,
    val onAction: () -> Unit
) {
    @Composable
    fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onAction: () -> Unit = this.onAction, fill_width: Boolean = true) =
        ActionButton(icon, label, accent_colour, modifier = modifier, onClick = onClick, onLongClick = onLongClick, onAction = onAction, fill_width = fill_width)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun ActiveQueueIndexAction(
        getText: (distance: Int) -> String,
        onClick: (active_queue_index: Int) -> Unit,
        onLongClick: ((active_queue_index: Int) -> Unit)? = null
    ) {
        val player = LocalPlayerState.current
        val service = LocalPlayerState.current.player ?: return
        
        var active_queue_item: Song? by remember { mutableStateOf(null) }
        AnimatedVisibility(service.active_queue_index < player.status.m_song_count) {
            if (service.active_queue_index < player.status.m_song_count) {
                active_queue_item = service.getSong(service.active_queue_index)
            }

            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val distance = service.active_queue_index - service.current_song_index + 1
                    ActionButton(
                        Icons.Filled.SubdirectoryArrowRight,
                        getText(distance),
                        fill_width = false,
                        onClick = { onClick(service.active_queue_index) },
                        onLongClick = onLongClick?.let { { it.invoke(service.active_queue_index) } }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val button_modifier = Modifier
                            .size(30.dp)
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .align(Alignment.CenterVertically)

                        Surface(
                            button_modifier.combinedClickable(
                                remember { MutableInteractionSource() },
                                rememberRipple(),
                                onClick = {
                                    service.updateActiveQueueIndex(-1)
                                },
                                onLongClick = {
                                    SpMp.context.vibrateShort()
                                    service.updateActiveQueueIndex(Int.MIN_VALUE)
                                }
                            ),
                            color = accent_colour(),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Filled.Remove, null, tint = background_colour())
                        }

                        Surface(
                            button_modifier.combinedClickable(
                                remember { MutableInteractionSource() },
                                rememberRipple(),
                                onClick = {
                                    service.updateActiveQueueIndex(1)
                                },
                                onLongClick = {
                                    SpMp.context.vibrateShort()
                                    service.updateActiveQueueIndex(Int.MAX_VALUE)
                                }
                            ),
                            color = accent_colour(),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Filled.Add, null, tint = background_colour())
                        }
                    }
                }

                Crossfade(active_queue_item, animationSpec = tween(100)) {
                    CompositionLocalProvider(
                        LocalPlayerState provides remember { player.copy(onClickedOverride = { item, _ -> player.openMediaItem(item) }) }
                    ) {
                        it?.PreviewLong(MediaItemPreviewParams(contentColour = content_colour))
                    }
                }
            }
        }
    }

    companion object {
        @OptIn(ExperimentalFoundationApi::class)
        @Composable
        fun ActionButton(
            icon: ImageVector,
            label: String,
            icon_colour: () -> Color = { Color.Unspecified },
            text_colour: () -> Color = { Color.Unspecified },
            modifier: Modifier = Modifier,
            onClick: () -> Unit,
            onLongClick: (() -> Unit)? = null,
            onAction: () -> Unit,
            fill_width: Boolean = true
        ) {
            Row(
                modifier
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onClick()
                            onAction()
                        },
                        onLongClick = if (onLongClick == null) null else {
                            {
                                SpMp.context.vibrateShort()
                                onLongClick()
                                onAction()
                            }
                        }
                    )
                    .let { if (fill_width) it.fillMaxWidth() else it },
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon_col = icon_colour()
                Icon(icon, null, tint = if (icon_col.isUnspecified) LocalContentColor.current else icon_col)
                Text(label, fontSize = 15.sp, color = text_colour())
            }
        }
    }
}