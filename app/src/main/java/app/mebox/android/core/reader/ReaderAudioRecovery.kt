package app.mebox.android.core.reader

import androidx.media3.common.PlaybackException

/**
 * 音源直链失效后的自动恢复策略。
 *
 * 背景：听书源（聆阅·懒人听书这类）的播放地址是第三方中转现签的 CDN 直链。
 * 票被上游判掉时播放器会收到两类错误：
 *
 *  - `ERROR_CODE_IO_BAD_HTTP_STATUS(2004)`：上游回 401/403（空的 401 是腾讯 CDN
 *    对无效 vkey/uid 的固定回答）；
 *  - `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED(3003)`：拿到的是错误页而不是音频，
 *    media3 的 19 个 extractor 一个都不认。
 *
 * 两者的根因都是「这张票不能用了」，服务端现在会换票重拉，所以客户端重装一次
 * 同一章就能拿到新票。本对象只负责「该不该重装」这一判断，便于单测。
 */
object ReaderAudioRecovery {

    /** 这些错误码可能由「签名票失效」引起，重装本章有意义。 */
    private val retriableCodes = setOf(
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    )

    /** 错误码是否属于「可能是票失效」这一类。 */
    fun isRetriable(errorCode: Int): Boolean = errorCode in retriableCodes

    /**
     * 是否应该自动重装这一章。
     *
     * 同一章只自动重装一次：源真的挂了时不能让播放器无限重装，也不该把书源
     * 打成限流。成功装载后调用 [forgetChapter] 清掉标记，这样下次再进这一章
     * （比如几小时后票又过期）仍能自动恢复一次。
     */
    fun shouldAutoReload(errorCode: Int, chapterIndex: Int, reloaded: MutableSet<Int>): Boolean {
        if (!isRetriable(errorCode)) return false
        return reloaded.add(chapterIndex)
    }

    /** 章节成功装载：清掉自动重装标记。 */
    fun forgetChapter(chapterIndex: Int, reloaded: MutableSet<Int>) {
        reloaded.remove(chapterIndex)
    }

    /** 停止播放/换书：清空全部标记。 */
    fun clear(reloaded: MutableSet<Int>) {
        reloaded.clear()
    }
}
