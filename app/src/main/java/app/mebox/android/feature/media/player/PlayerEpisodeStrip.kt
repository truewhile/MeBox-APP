package app.mebox.android.feature.media.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.mebox.android.feature.media.PlaybackEpisode

/**
 * 分集选择条：参考 CapyPlayer 点击「分集」按钮后浮在底部控制栏上方的横向卡片。
 *
 * 选中的一集用白色描边；卡片下方是集数和单集标题。外层加一层由透明到黑的
 * 渐变，保证在亮画面上也能看清分集列表。
 */
@Composable
fun PlayerEpisodeStrip(
    episodes: List<PlaybackEpisode>,
    currentMediaId: String,
    onSelect: (PlaybackEpisode) -> Unit,
    landscape: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tv = LocalPlayerIsTv.current
    val currentIndex = episodes.indexOfFirst { it.mediaId == currentMediaId }
    // 电视上从当前集开始显示，焦点也落在当前集上，避免从第一集重新找。
    val focusIndex = if (currentIndex >= 0) currentIndex else 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusIndex,
    )
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(tv, currentMediaId) {
        if (tv) requestPlayerTvFocus(firstFocus)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (tv) 340.dp else 300.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color(0x99000000),
                        1f to Color(0xE6000000),
                    ),
                ),
        )
        LazyRow(
            state = listState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = if (tv) 116.dp else if (landscape) 104.dp else 118.dp),
            contentPadding = PaddingValues(
                start = when {
                    tv -> 64.dp
                    landscape -> 56.dp
                    else -> 18.dp
                },
                end = when {
                    tv -> 72.dp
                    landscape -> 64.dp
                    else -> 18.dp
                },
            ),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 14.dp),
        ) {
            itemsIndexed(episodes, key = { _, episode -> episode.mediaId }) { index, episode ->
                EpisodeCard(
                    episode = episode,
                    selected = episode.mediaId == currentMediaId,
                    onClick = { onSelect(episode) },
                    focusRequester = if (tv && index == focusIndex) firstFocus else null,
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: PlaybackEpisode,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val tv = LocalPlayerIsTv.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(if (tv) 168.dp else 132.dp)
            .clip(shape)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .playerTvFocus(
                focused = focused,
                shape = shape,
                scale = PlayerTvFocus.CardScale,
                ringWidth = PlayerTvFocus.CardRingWidth,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (tv) 94.dp else 74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF202020))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Color.White else Color(0x33FFFFFF),
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!episode.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = episode.imageUrl,
                    contentDescription = episode.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    episode.label,
                    color = Color(0x99FFFFFF),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = if (tv) 16.sp else 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            episode.label,
            color = Color.White,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = if (tv) 16.sp else 14.sp,
                lineHeight = if (tv) 21.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            episode.title,
            color = Color(0x99FFFFFF),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = if (tv) 14.sp else 12.sp,
                lineHeight = if (tv) 19.sp else 16.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
