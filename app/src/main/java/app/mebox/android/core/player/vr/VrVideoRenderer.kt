package app.mebox.android.core.player.vr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * 把普通 2D 视频画到球面上，渲染成全景/VR 画面。
 *
 * 实现取巧地用全屏四边形 + 片元射线，而不是真的生成球面网格：
 * 每个片元根据屏幕坐标算出一条视线，再把视线方向换算成等距柱状图的 UV。
 * 这样 360°/180°、左右/上下立体、镜头畸变都只需要改着色器，没有网格接缝。
 *
 * 纹理来自 [SurfaceTexture]（外部 OES 纹理），因此 ExoPlayer 和 LibVLC
 * 都可以把解码后的帧直接交给它，App 不需要自己拷贝像素。
 */
class VrVideoRenderer(
    /** SurfaceTexture 在 GL 线程创建，回调会切到主线程再通知内核。 */
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile
    private var settings: VrSettings = VrSettings(
        projection = VrProjection.EQUIRECT_360,
        stereo = VrStereo.MONO,
        display = VrDisplayMode.FULLSCREEN,
        lensCorrection = false,
    )

    // 传感器在主线程写入，渲染线程只读。
    @Volatile private var sensorRotation: FloatArray? = null
    @Volatile private var recenterRequested = false
    @Volatile private var manualYawDeg = 0f
    @Volatile private var manualPitchDeg = 0f

    // 以下字段只在 GL 线程访问。
    private var recenterHeadingRad = 0f

    private var program = 0
    private var positionLocation = -1
    private var textureUniform = -1
    private var cameraUniform = -1
    private var fovUniform = -1
    private var projectionUniform = -1
    private var stereoUniform = -1
    private var eyeUniform = -1
    private var lensUniform = -1
    private var textureMatrixUniform = -1

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var frameAvailable = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val textureMatrix = FloatArray(16)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    private val cameraMatrix = FloatArray(9)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram()
        if (program == 0) return

        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureUniform = GLES20.glGetUniformLocation(program, "uTexture")
        cameraUniform = GLES20.glGetUniformLocation(program, "uCamera")
        fovUniform = GLES20.glGetUniformLocation(program, "uTanHalfFov")
        projectionUniform = GLES20.glGetUniformLocation(program, "uProjection")
        stereoUniform = GLES20.glGetUniformLocation(program, "uStereo")
        eyeUniform = GLES20.glGetUniformLocation(program, "uEye")
        lensUniform = GLES20.glGetUniformLocation(program, "uLens")
        textureMatrixUniform = GLES20.glGetUniformLocation(program, "uTexMatrix")

        Matrix.setIdentityM(textureMatrix, 0)
        createSurfaceTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture
        if (program == 0 || st == null) return

        if (frameAvailable) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(textureMatrix)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "VR 纹理更新失败", error)
            }
            frameAvailable = false
        }

        advanceOrientation()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glUniformMatrix4fv(textureMatrixUniform, 1, false, textureMatrix, 0)

        val yawRadians = Math.toRadians(manualYawDeg.toDouble()).toFloat()
        val pitchRadians = Math.toRadians(manualPitchDeg.toDouble()).toFloat()
        val rotation = sensorRotation
        if (rotation != null) {
            VrOrientation.cameraMatrix(
                rotation = rotation,
                recenterHeadingRadians = recenterHeadingRad,
                manualYawRadians = yawRadians,
                manualPitchRadians = pitchRadians,
                out = cameraMatrix,
            )
        } else {
            VrOrientation.gestureMatrix(yawRadians, pitchRadians, cameraMatrix)
        }
        GLES20.glUniformMatrix3fv(cameraUniform, 1, false, cameraMatrix, 0)

        val current = settings
        GLES20.glUniform1i(
            projectionUniform,
            if (current.projection == VrProjection.HEMISPHERE_180) 1 else 0,
        )
        GLES20.glUniform1i(
            stereoUniform,
            when (current.stereo) {
                VrStereo.SIDE_BY_SIDE -> 1
                VrStereo.TOP_BOTTOM -> 2
                else -> 0
            },
        )
        GLES20.glUniform1f(
            lensUniform,
            if (current.display == VrDisplayMode.HEADSET && current.lensCorrection) 1f else 0f,
        )

        GLES20.glEnableVertexAttribArray(positionLocation)
        quad.position(0)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quad,
        )

        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return
        if (current.display == VrDisplayMode.HEADSET) {
            val half = (width / 2).coerceAtLeast(1)
            drawEye(eye = 0, x = 0, y = 0, width = half, height = height)
            drawEye(eye = 1, x = half, y = 0, width = (width - half).coerceAtLeast(1), height = height)
        } else {
            drawEye(eye = 0, x = 0, y = 0, width = width, height = height)
        }

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawEye(eye: Int, x: Int, y: Int, width: Int, height: Int) {
        GLES20.glViewport(x, y, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        // 垂直视场 90°，水平视场随屏幕/单眼宽高比变化。
        val tanHalfY = tan(Math.toRadians(45.0)).toFloat()
        GLES20.glUniform2f(fovUniform, aspect * tanHalfY, tanHalfY)
        GLES20.glUniform1i(eyeUniform, eye)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * 重定位只在渲染线程落地：传感器矩阵随时在更新，读取当前朝向后再
     * 记录偏移量，避免主线程和渲染线程拿到不同帧的姿态。
     */
    private fun advanceOrientation() {
        if (!recenterRequested) return
        val rotation = sensorRotation
        if (rotation != null) {
            recenterHeadingRad = VrOrientation.headingRadians(rotation)
        } else {
            // 没有传感器时，重定位等价于把手指拖动产生的偏航/俯仰归零。
            manualYawDeg = 0f
            manualPitchDeg = 0f
        }
        recenterRequested = false
    }

    /** 主线程调用：更新旋转矢量传感器返回的 9 元旋转矩阵。 */
    fun setSensorRotation(rotation: FloatArray) {
        sensorRotation = rotation.copyOf(9)
    }

    /** 主线程调用：把当前视线方向重设为正前方。 */
    fun recenter() {
        recenterRequested = true
    }

    /**
     * 主线程调用：手指拖动微调视角。
     *
     * 没有陀螺仪的设备（或用户想临时偏转视线）也能使用。
     */
    fun nudge(yawDeltaDegrees: Float, pitchDeltaDegrees: Float) {
        manualYawDeg = VrOrientation.normalizeDegrees(manualYawDeg + yawDeltaDegrees)
        manualPitchDeg = (manualPitchDeg + pitchDeltaDegrees).coerceIn(-89f, 89f)
    }

    /** 主线程调用：切换投影/立体/显示方式。 */
    fun setSettings(settings: VrSettings) {
        this.settings = settings
    }

    fun releaseGlResources() {
        surfaceTexture?.let { st ->
            runCatching { st.setOnFrameAvailableListener(null) }
            runCatching { st.release() }
        }
        surfaceTexture = null
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable = true
    }

    private fun createSurfaceTexture() {
        // GL 上下文可能重建，旧对象先释放。
        surfaceTexture?.let { old ->
            runCatching { old.setOnFrameAvailableListener(null) }
            runCatching { old.release() }
        }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        val next = SurfaceTexture(textureId)
        // GL 线程没有 Looper，显式指定主线程接收帧通知。
        next.setOnFrameAvailableListener(this, mainHandler)
        surfaceTexture = next
        onSurfaceTextureReady(next)
    }

    private fun buildProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) return 0
        val next = GLES20.glCreateProgram()
        GLES20.glAttachShader(next, vertex)
        GLES20.glAttachShader(next, fragment)
        GLES20.glLinkProgram(next)
        val status = IntArray(1)
        GLES20.glGetProgramiv(next, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "VR 着色器链接失败: ${GLES20.glGetProgramInfoLog(next)}")
            GLES20.glDeleteProgram(next)
            return 0
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return next
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "VR 着色器编译失败: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private companion object {
        const val TAG = "VrRenderer"

        val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vPosition;
            void main() {
                vPosition = aPosition;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        /**
         * 全屏射线：把屏幕坐标当成视线方向，再把方向换算成等距柱状 UV。
         *
         * uProjection: 0 = 360°，1 = 180°
         * uStereo:     0 = 单目，1 = 左右，2 = 上下
         * uEye:        0 = 左眼，1 = 右眼
         */
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;

            varying vec2 vPosition;
            uniform samplerExternalOES uTexture;
            uniform mat3 uCamera;
            uniform vec2 uTanHalfFov;
            uniform mat4 uTexMatrix;
            uniform int uProjection;
            uniform int uStereo;
            uniform int uEye;
            uniform float uLens;

            const float PI = 3.141592653589793;

            void main() {
                vec2 p = vPosition;
                if (uLens > 0.5) {
                    float r2 = dot(p, p);
                    p *= (1.0 + 0.44 * r2 + 0.156 * r2 * r2);
                }

                vec3 dirCamera = normalize(vec3(p.x * uTanHalfFov.x, p.y * uTanHalfFov.y, -1.0));
                vec3 dir = normalize(uCamera * dirCamera);

                float yaw = atan(dir.x, -dir.z);
                float pitch = asin(clamp(dir.y, -1.0, 1.0));

                float u;
                float v;
                if (uProjection == 0) {
                    u = yaw / (2.0 * PI) + 0.5;
                    v = 0.5 - pitch / PI;
                } else {
                    if (abs(yaw) > PI * 0.5) {
                        discard;
                    }
                    u = yaw / PI + 0.5;
                    v = 0.5 - pitch / PI;
                }

                if (uStereo == 1) {
                    u = u * 0.5 + (uEye == 0 ? 0.0 : 0.5);
                } else if (uStereo == 2) {
                    // v 是「顶部为 0」的图像坐标：左右格式的左眼在上半部分。
                    v = v * 0.5 + (uEye == 0 ? 0.0 : 0.5);
                }

                // SurfaceTexture 的变换矩阵按 OpenGL 的下原点纹理坐标设计，
                // 这里把顶部为 0 的 v 转成下原点后再应用。
                vec2 uv = (uTexMatrix * vec4(clamp(u, 0.0, 1.0), 1.0 - clamp(v, 0.0, 1.0), 0.0, 1.0)).xy;
                vec3 color = texture2D(uTexture, uv).rgb;
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
