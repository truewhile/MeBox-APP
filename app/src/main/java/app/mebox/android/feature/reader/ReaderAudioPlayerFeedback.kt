package app.mebox.android.feature.reader

internal enum class AudioPlayerPhase { PREPARING, BUFFERING, PLAYING, PAUSED, FAILED }

/** 页面反馈只使用当前书/当前章的音频状态，避免后台另一本书的状态串入。 */
internal data class AudioPlayerFeedback(
    val phase: AudioPlayerPhase,
    val playRequested: Boolean,
    val error: String? = null,
) {
    val loading: Boolean
        get() = phase == AudioPlayerPhase.PREPARING || phase == AudioPlayerPhase.BUFFERING

    val statusMessage: String
        get() = when (phase) {
            AudioPlayerPhase.PREPARING -> if (playRequested) "正在准备音频…" else "正在准备音频，准备后暂停"
            AudioPlayerPhase.BUFFERING -> if (playRequested) "正在缓冲音频…" else "已暂停 · 音频缓冲中"
            AudioPlayerPhase.PLAYING -> "后台播放中 · 通知栏和锁屏可控制"
            AudioPlayerPhase.PAUSED -> "已暂停 · 点击播放继续"
            AudioPlayerPhase.FAILED -> error.orEmpty()
        }

    val actionDescription: String
        get() = when {
            phase == AudioPlayerPhase.FAILED -> "重试播放"
            playRequested && loading -> "暂停，音频加载中"
            playRequested -> "暂停"
            loading -> "播放，音频加载中"
            else -> "播放"
        }
}

internal val ReaderUiState.audioPlayerVisible: Boolean
    get() = content?.type == "audio" ||
        (content == null && (book?.isAudio == true || (audio.active && audio.bookId == book?.id)))

internal fun ReaderUiState.audioPlayerFeedback(): AudioPlayerFeedback {
    val sameChapter = audio.active && audio.bookId == book?.id && audio.chapterIndex == chapterIndex
    val error = chapterError ?: audio.error.takeIf { sameChapter && !chapterLoading }
    val inheritedRequest = if (chapterLoading) {
        audio.active && audio.bookId == book?.id && audio.playWhenReady
    } else {
        sameChapter && (audio.playWhenReady || audio.playing)
    }
    val requested = audioPlayRequested ?: inheritedRequest
    val phase = when {
        error != null -> AudioPlayerPhase.FAILED
        chapterLoading || (sameChapter && audio.preparing) -> AudioPlayerPhase.PREPARING
        sameChapter && audio.loading -> AudioPlayerPhase.BUFFERING
        sameChapter && audio.playing -> AudioPlayerPhase.PLAYING
        else -> AudioPlayerPhase.PAUSED
    }
    return AudioPlayerFeedback(phase, requested && error == null, error)
}
