package app.mebox.android.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.reader.ReaderUrls
import app.mebox.android.core.ui.MeBoxDimens
import app.mebox.android.core.ui.MeBoxGlassSurface
import coil.compose.AsyncImage

/**
 * 首页底部的「继续阅读/收听」悬浮条。
 *
 * 只展示一本书：正在播放的有声书，或最近一次有进度的书。点击整条或右侧按钮
 * 都会进入对应页面——文本/漫画进正文，有声书进播放页并开始播放。
 */
@Composable
internal fun HomeReadingBar(
    book: ReaderBookDto,
    origin: String,
    listening: Boolean,
    chapterLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAudio = book.isAudio
    val actionLabel = if (isAudio) "继续收听" else "继续阅读"
    val cover = book.coverUrl
        .takeIf { ReaderUrls.isRenderable(it) }
        ?.let { ReaderUrls.resolve(origin, it) }
    MeBoxGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MeBoxDimens.CardRadius),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = actionLabel, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 42.dp, height = 56.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = book.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        book.name.take(1).ifBlank { "书" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        isAudio && listening -> "正在收听"
                        isAudio -> "上次收听"
                        else -> "上次阅读"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    book.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    chapterLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isAudio) Icons.Default.Headphones else Icons.Default.Book,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
