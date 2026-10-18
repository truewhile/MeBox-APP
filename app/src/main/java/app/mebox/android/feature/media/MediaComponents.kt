package app.mebox.android.feature.media

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mebox.android.core.media.LibraryArtwork
import app.mebox.android.core.media.LibraryTagTab
import app.mebox.android.core.media.MediaBrowseFormat
import app.mebox.android.core.media.MediaImageUrls
import app.mebox.android.core.network.ContinueWatchingDto
import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.MediaDto
import app.mebox.android.core.network.SeriesCardDto
import app.mebox.android.core.ui.MeBoxChip
import coil.compose.AsyncImage

/**
 * 影视模块的卡片尺寸。Yamby 的做法是「图片撑满、文字在图片外」，
 * 尽量少用描边和底色，让内容本身成为视觉主体。
 */
object MediaDimens {
    val PosterWidth = 118.dp
    val RailSpacing = 12.dp
    val PosterRadius = 12.dp
    val WideRadius = 16.dp
    val ContinueWidth = 238.dp
    val LandscapeMinWidth = 150.dp
}

/** 海报卡：2:3 封面 + 评分/集数角标，标题两行。 */
@Composable
fun MediaPosterCard(
    title: String,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    rating: Float = 0f,
    badge: String? = null,
    action: (@Composable BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MediaDimens.PosterRadius))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(MediaDimens.PosterRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            MediaArtwork(
                url = posterUrl,
                title = title,
                modifier = Modifier.fillMaxSize(),
            )
            if (rating > 0f) {
                RatingPill(
                    rating = rating,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }
            if (action != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    action()
                }
            } else if (!badge.isNullOrBlank()) {
                Surface(
                    color = Color(0xCC000000),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 宽幅卡：16:10 横图 + 标题/年份，用于媒体库网格（Yamby 的默认布局）。 */
@Composable
fun MediaLandscapeCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    rating: Float = 0f,
    badge: String? = null,
    action: (@Composable BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MediaDimens.WideRadius))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(MediaDimens.WideRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            MediaArtwork(
                url = imageUrl,
                title = title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x33000000)),
                        ),
                    ),
            )
            if (rating > 0f) {
                RatingPill(
                    rating = rating,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }
            if (action != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    action()
                }
            } else if (!badge.isNullOrBlank()) {
                Surface(
                    color = Color(0xCC000000),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 继续观看卡：16:9 横图、标题在下方、进度条贴在图片底部。 */
@Composable
fun ContinueWatchingCard(
    item: ContinueWatchingDto,
    origin: String,
    onClick: () -> Unit,
) {
    val media = item.media
    val duration = item.history.durationMs.takeIf { it > 0 } ?: (media.durationSec * 1000L)
    val fraction = if (duration > 0) {
        (item.history.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val image = MediaImageUrls.backdrop(
        origin = origin,
        remote = media.backdropUrl.ifBlank { media.posterUrl },
    )
    Column(
        modifier = Modifier
            .width(MediaDimens.ContinueWidth)
            .clip(RoundedCornerShape(MediaDimens.WideRadius))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(MediaDimens.WideRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            MediaArtwork(
                url = image,
                title = media.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x10000000),
                            0.55f to Color(0x33000000),
                            1f to Color(0xB3000000),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFF111111),
                    modifier = Modifier.size(21.dp),
                )
            }
            if (duration > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.28f),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            media.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            continueWatchingSubtitle(item),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 媒体库主卡：16:9 封面 + 渐变 + 名称和数量。 */
@Composable
fun LibrarySpotlightCard(
    library: LibraryDto,
    origin: String,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
    onClick: () -> Unit,
) {
    val cover = LibraryArtwork.cover(origin, library)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(MediaDimens.WideRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        MediaArtwork(
            url = cover,
            title = library.name,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x22000000), Color(0x22000000), Color(0xE6000000)),
                    ),
                ),
        )
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        ) {
            Text(
                libraryTypeName(library.type),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (pinned) {
            Surface(
                color = Color(0xCC000000),
                contentColor = Color(0xFFFFD166),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            ) {
                Text(
                    "置顶",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                library.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                library.total.takeIf { it > 0 }?.let { "$it 项内容" } ?: "浏览媒体库",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFDDDDDD),
            )
        }
    }
}

/**
 * 媒体库标签栏：首项「全部」，其后是用户在网页端维护的标签。
 *
 * 只负责切换筛选，不修改服务端标签；没有标签时整栏不显示，避免多出一行
 * 无意义的「全部」。
 */
@Composable
fun LibraryTagBar(
    tabs: List<LibraryTagTab>,
    selectedTag: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    if (tabs.size <= 1) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tabs, key = { it.name ?: "__all__" }) { tab ->
            MeBoxChip(
                text = if (tab.count > 0) "${tab.label} ${tab.count}" else tab.label,
                selected = tab.name == selectedTag,
                onClick = { onSelect(tab.name) },
            )
        }
    }
}

/** 评分角标：半透明黑底 + 星标，保证在亮/暗海报上都可读。 */
@Composable
fun RatingPill(rating: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xD9000000),
        contentColor = Color(0xFFFFD166),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                "%.1f".format(rating),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/** 简介：默认折叠 4 行，可展开/收起。 */
@Composable
fun ExpandableOverview(text: String, modifier: Modifier = Modifier) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(
            text.ifBlank { "暂无简介" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.animateContentSize(),
        )
        if (text.length > 80) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 图片占位：没有封面时用首字 + 渐变，避免出现大片空白。 */
@Composable
fun MediaArtwork(url: String?, title: String, modifier: Modifier = Modifier) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.take(1),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 加载骨架：呼吸动画的灰色卡片，比单个转圈更接近内容最终形态。 */
@Composable
fun MediaGridSkeleton(
    columns: Int = 3,
    rows: Int = 3,
    landscape: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "media-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "media-skeleton-alpha",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(columns) {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(if (landscape) 16f / 10f else 2f / 3f)
                                .clip(RoundedCornerShape(MediaDimens.PosterRadius))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                                ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                                ),
                        )
                    }
                }
            }
        }
    }
}

internal fun continueWatchingSubtitle(item: ContinueWatchingDto): String {
    val media = item.media
    val episode = media.displayEpisode.ifBlank { media.episodeTitle }
    val duration = item.history.durationMs.takeIf { it > 0 } ?: (media.durationSec * 1000L)
    return MediaBrowseFormat.continueSubtitle(episode, item.history.positionMs, duration)
}

internal fun seriesCardTitle(card: SeriesCardDto): String =
    card.rep.title.ifBlank { card.linkMedia?.title.orEmpty() }

internal fun seriesCardSubtitle(card: SeriesCardDto): String =
    MediaBrowseFormat.seriesSubtitle(card.rep)

internal fun seriesCardImage(origin: String, card: SeriesCardDto): String? =
    MediaImageUrls.backdrop(
        origin = origin,
        remote = card.rep.backdropUrl.ifBlank { card.rep.posterUrl },
    )

/**
 * 2:3 海报卡的封面：优先 `poster_url`，没有海报时才退回剧照。
 *
 * 网页端和手机端的海报卡都以 `poster_url` 为主图；电视端此前用了
 * [seriesCardImage]（剧照），同一部作品在电视上是横图裁成竖图，看起来
 * 和手机/网页不是同一张封面。所有端统一走这里，保证选图顺序和尺寸档位一致。
 */
internal fun seriesCardPoster(origin: String, card: SeriesCardDto): String? =
    MediaImageUrls.poster(
        origin = origin,
        remote = card.rep.posterUrl.ifBlank { card.rep.backdropUrl },
    )

/** 与 [seriesCardPoster] 相同，用于只有媒体行的收藏/搜索列表。 */
internal fun mediaPosterImage(origin: String, media: MediaDto): String? =
    MediaImageUrls.poster(
        origin = origin,
        remote = media.posterUrl.ifBlank { media.backdropUrl },
    )

internal fun mediaArtworkImage(origin: String, media: MediaDto): String? =
    MediaImageUrls.backdrop(
        origin = origin,
        remote = media.backdropUrl.ifBlank { media.posterUrl },
    )

/**
 * 单集封面：优先本集剧照（backdrop），其次本集海报，最后回落到整部剧的主图，
 * 保证没有刮削到分集剧照的剧集也有稳定的缩略图。
 */
internal fun episodeArtworkImage(
    origin: String,
    episode: MediaDto,
    fallback: MediaDto? = null,
): String? =
    MediaImageUrls.backdrop(
        origin = origin,
        remote = episode.backdropUrl
            .ifBlank { episode.posterUrl }
            .ifBlank { fallback?.backdropUrl.orEmpty() }
            .ifBlank { fallback?.posterUrl.orEmpty() },
    )

/**
 * 选集缩略图：16:9 剧照 + 集数角标。
 *
 * 选集列表里单集封面是主要视觉线索，集数角标只作为辅助信息压在图左下角；
 * 当前正在播放的集用主题色描边区分。
 */
@Composable
internal fun EpisodeThumbnail(
    url: String?,
    episodeLabel: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        // 底部压暗，保证集数角标在亮色剧照上依然可读。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color(0x99000000),
                    ),
                ),
        )
        Surface(
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color(0xCC000000)
            },
            contentColor = Color.White,
            shape = RoundedCornerShape(5.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp),
        ) {
            Text(
                episodeLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = shape,
                    ),
            )
        }
    }
}
