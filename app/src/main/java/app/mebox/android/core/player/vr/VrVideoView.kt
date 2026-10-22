package app.mebox.android.core.player.vr

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

/**
 * VR 画面视图：GL 全景渲染 + 陀螺仪头部追踪 + 字幕层。
 *
 * 内核只需要通过 [onSurfaceTextureReady] 拿到 [SurfaceTexture]，把解码帧
 * 输出上去即可；ExoPlayer 用 `setVideoSurface`，LibVLC 用
 * `vlcVout.setVideoSurface(SurfaceTexture)`。
 *
 * 头显模式下字幕按左右眼各放一份，避免横跨两块镜片；手机全屏模式只保留一份。
 */
@OptIn(UnstableApi::class)
class VrVideoView(context: Context) : FrameLayout(context) {

    /** GL 线程创建纹理后回调；视图被销毁重建时会再次回调新的 SurfaceTexture。 */
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    private val renderer = VrVideoRenderer { surface ->
        // 裸 SurfaceTexture 的默认缓冲是 1×1；LibVLC 的 Android 视频输出会
        // 按窗口尺寸出图，没有这行会只拿到一个像素。ExoPlayer 会自己按解码
        // 尺寸重设，不影响。
        surface.setDefaultBufferSize(
            width.takeIf { it > 0 } ?: 1920,
            height.takeIf { it > 0 } ?: 1080,
        )
        post { onSurfaceTextureReady?.invoke(surface) }
    }

    private val glView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private val subtitleLeft = createSubtitleView()
    private val subtitleRight = createSubtitleView()

    private var settings = VrSettings(
        projection = VrProjection.EQUIRECT_360,
        stereo = VrStereo.MONO,
        display = VrDisplayMode.FULLSCREEN,
        lensCorrection = false,
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? = sensorManager?.let { manager ->
        // 优先用游戏旋转矢量：只用陀螺仪+加速度计，没有磁力计抖动，
        // 长时间观看时头部追踪更稳；方位漂移由「重新居中」修正。
        manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private var sensorRegistered = false
    private var firstSensorFix = true

    private val lifecycleOwner = context as? LifecycleOwner
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            else -> Unit
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensor = rotationSensor ?: return
            if (event.sensor.type != sensor.type) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val from = rotationMatrix
            val to = remappedMatrix
            @Suppress("DEPRECATION")
            when (displayRotation()) {
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                    from,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    to,
                )
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                    from,
                    SensorManager.AXIS_MINUS_X,
                    SensorManager.AXIS_MINUS_Y,
                    to,
                )
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                    from,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    to,
                )
                else -> SensorManager.remapCoordinateSystem(
                    from,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Y,
                    to,
                )
            }
            renderer.setSensorRotation(to)
            if (firstSensorFix) {
                // 进入 VR 时用户看向哪里，哪里就是新的正前方。
                firstSensorFix = false
                renderer.recenter()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        addView(
            glView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(subtitleLeft, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            subtitleRight,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.END),
        )
        subtitleRight.visibility = View.GONE
        applySubtitleLayout()
        lifecycleOwner?.lifecycle?.addObserver(lifecycleObserver)
        val resumed = lifecycleOwner == null ||
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (resumed) resume()
    }

    fun setSettings(next: VrSettings) {
        val displayChanged = settings.display != next.display
        settings = next
        renderer.setSettings(next)
        if (displayChanged) applySubtitleLayout()
    }

    /** 接收播放内核解析出的字幕；两份视图各自排版。 */
    fun setCues(cues: List<Cue>) {
        subtitleLeft.setCues(cues)
        subtitleRight.setCues(cues)
    }

    /** 把当前视线方向重设为正前方。 */
    fun recenter() {
        renderer.recenter()
    }

    /** 手指拖动微调视角，单位是像素位移。 */
    fun dragBy(dx: Float, dy: Float) {
        renderer.nudge(
            yawDeltaDegrees = -dx * DRAG_DEGREES_PER_PIXEL,
            pitchDeltaDegrees = dy * DRAG_DEGREES_PER_PIXEL,
        )
    }

    fun release() {
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        stopSensors()
        glView.queueEvent { renderer.releaseGlResources() }
        glView.onPause()
        removeAllViews()
        onSurfaceTextureReady = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val resumed = lifecycleOwner == null ||
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (resumed) resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySubtitleLayout()
    }

    private fun resume() {
        glView.onResume()
        startSensors()
    }

    private fun pause() {
        stopSensors()
        glView.onPause()
    }

    private fun startSensors() {
        val manager = sensorManager ?: return
        val sensor = rotationSensor ?: return
        if (sensorRegistered) return
        val registered = manager.registerListener(
            sensorListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        sensorRegistered = registered
    }

    private fun stopSensors() {
        if (!sensorRegistered) return
        sensorManager?.unregisterListener(sensorListener)
        sensorRegistered = false
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation
        } else {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay
                ?.rotation
        }
        return rotation ?: Surface.ROTATION_0
    }

    private fun applySubtitleLayout() {
        val width = width
        val height = height
        val split = settings.display == VrDisplayMode.HEADSET
        val horizontalPadding = dp(16)
        val bottomPadding = dp(if (split) 28 else 40)
        subtitleLeft.setPadding(horizontalPadding, 0, horizontalPadding, bottomPadding)
        subtitleRight.setPadding(horizontalPadding, 0, horizontalPadding, bottomPadding)

        if (split && width > 0) {
            val leftWidth = width / 2
            subtitleLeft.layoutParams = LayoutParams(leftWidth, height, Gravity.START)
            subtitleRight.layoutParams = LayoutParams(width - leftWidth, height, Gravity.END)
            subtitleRight.visibility = View.VISIBLE
        } else {
            subtitleLeft.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            subtitleRight.visibility = View.GONE
        }
        subtitleLeft.requestLayout()
        subtitleRight.requestLayout()
    }

    private fun createSubtitleView(): SubtitleView = SubtitleView(context).apply {
        setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null,
            ),
        )
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        /** 手指拖动 1 像素对应的角度：全屏宽约 1200px 时拖满一屏约 90°。 */
        const val DRAG_DEGREES_PER_PIXEL = 0.075f
    }
}
