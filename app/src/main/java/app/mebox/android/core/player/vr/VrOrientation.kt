package app.mebox.android.core.player.vr

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * VR 相机姿态数学。
 *
 * 不依赖 Android 框架，便于在 JVM 单元测试里验证。
 *
 * 传感器的旋转矩阵 R 是「设备坐标 → 世界坐标（东、北、天）」的行主序矩阵。
 * 直接使用矩阵而不是欧拉角：手机竖立/戴在头显里时俯仰接近 ±90°，
 * `getOrientation` 会进入万向锁，方位角不可靠。
 *
 * 着色器使用自己的世界坐标：
 * - X = 东
 * - Y = 天（向上）
 * - Z = -北
 *
 * 因此先做 `A · R`（A 把东/北/天映射到着色器坐标），再在世界空间里
 * 绕 Y 轴重定位/手动偏航、绕 X 轴手动俯仰。
 */
object VrOrientation {

    /**
     * @param rotation 传感器返回的 9 元旋转矩阵（行主序，设备 → 东/北/天）
     * @param recenterHeadingRadians 重定位时记录的朝向，抵消累积的偏航漂移
     * @param manualYawRadians 手指拖动的额外偏航
     * @param manualPitchRadians 手指拖动的额外俯仰
     * @param out 长度至少 9 的列主序输出
     */
    fun cameraMatrix(
        rotation: FloatArray,
        recenterHeadingRadians: Float,
        manualYawRadians: Float,
        manualPitchRadians: Float,
        out: FloatArray = FloatArray(9),
    ): FloatArray {
        require(rotation.size >= 9) { "rotation needs 9 elements" }

        // B = A · R，A = [1 0 0; 0 0 1; 0 -1 0]
        val b00 = rotation[0]; val b01 = rotation[1]; val b02 = rotation[2]
        val b10 = rotation[6]; val b11 = rotation[7]; val b12 = rotation[8]
        val b20 = -rotation[3]; val b21 = -rotation[4]; val b22 = -rotation[5]

        // C = Ry(recenter + manualYaw) · B
        val yaw = recenterHeadingRadians + manualYawRadians
        val cy = cos(yaw); val sy = sin(yaw)
        val c00 = cy * b00 + sy * b20
        val c01 = cy * b01 + sy * b21
        val c02 = cy * b02 + sy * b22
        val c10 = b10
        val c11 = b11
        val c12 = b12
        val c20 = -sy * b00 + cy * b20
        val c21 = -sy * b01 + cy * b21
        val c22 = -sy * b02 + cy * b22

        // D = Rx(manualPitch) · C
        val cp = cos(manualPitchRadians); val sp = sin(manualPitchRadians)
        val d00 = c00
        val d01 = c01
        val d02 = c02
        val d10 = cp * c10 - sp * c20
        val d11 = cp * c11 - sp * c21
        val d12 = cp * c12 - sp * c22
        val d20 = sp * c10 + cp * c20
        val d21 = sp * c11 + cp * c21
        val d22 = sp * c12 + cp * c22

        // OpenGL 列主序
        out[0] = d00; out[1] = d10; out[2] = d20
        out[3] = d01; out[4] = d11; out[5] = d21
        out[6] = d02; out[7] = d12; out[8] = d22
        return out
    }

    /** 没有陀螺仪时的纯手势视角：单位相机 + 偏航/俯仰。 */
    fun gestureMatrix(
        manualYawRadians: Float,
        manualPitchRadians: Float,
        out: FloatArray = FloatArray(9),
    ): FloatArray {
        val cy = cos(manualYawRadians); val sy = sin(manualYawRadians)
        val cp = cos(manualPitchRadians); val sp = sin(manualPitchRadians)
        // D = Rx(pitch) · Ry(yaw)
        val d00 = cy
        val d01 = 0f
        val d02 = sy
        val d10 = sp * sy
        val d11 = cp
        val d12 = -sp * cy
        val d20 = -cp * sy
        val d21 = sp
        val d22 = cp * cy
        out[0] = d00; out[1] = d10; out[2] = d20
        out[3] = d01; out[4] = d11; out[5] = d21
        out[6] = d02; out[7] = d12; out[8] = d22
        return out
    }

    /**
     * 相机正前方（-Z）的朝向角（弧度）。
     *
     * 0 表示朝北，+π/2 表示朝东；用于重定位时抵消偏航漂移。
     */
    fun headingRadians(rotation: FloatArray): Float {
        require(rotation.size >= 9) { "rotation needs 9 elements" }
        // 第三列是设备 +Z 在世界中的方向，前方是它的相反数。
        val east = -rotation[2]
        val north = -rotation[5]
        return atan2(east, north)
    }

    /** 把角度规格化到 [-180, 180)。 */
    fun normalizeDegrees(degrees: Float): Float {
        var value = degrees % 360f
        if (value >= 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }
}
