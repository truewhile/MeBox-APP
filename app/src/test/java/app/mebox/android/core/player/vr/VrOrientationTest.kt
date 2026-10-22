package app.mebox.android.core.player.vr

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class VrOrientationTest {

    private val identity = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    /** 立姿、屏幕朝北：X 向右(东)、Y 向上(天)、Z 向外(南)。 */
    private val uprightNorth = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f,
    )

    /** 立姿、屏幕朝东：X 向右(南)、Y 向上(天)、Z 向外(西)。 */
    private val uprightEast = floatArrayOf(
        0f, 0f, -1f,
        -1f, 0f, 0f,
        0f, 1f, 0f,
    )

    private fun forward(matrix: FloatArray): FloatArray {
        // 列主序下第三列是相机 +Z，前方是它的相反数。
        return floatArrayOf(-matrix[6], -matrix[7], -matrix[8])
    }

    private fun assertVector(expected: FloatArray, actual: FloatArray, delta: Float = 1e-4f) {
        expected.forEachIndexed { index, value ->
            assertEquals("component $index", value, actual[index], delta)
        }
    }

    @Test
    fun `flat phone with identity rotation looks down the shader up axis`() {
        val matrix = VrOrientation.cameraMatrix(identity, 0f, 0f, 0f)
        // 平放朝天时相机朝下，对应着色器坐标的 -Y。
        assertVector(floatArrayOf(0f, -1f, 0f), forward(matrix))
    }

    @Test
    fun `level camera facing north maps to identity`() {
        val matrix = VrOrientation.cameraMatrix(uprightNorth, 0f, 0f, 0f)
        assertVector(floatArrayOf(0f, 0f, -1f), forward(matrix))
        // 相机上方向（+Y）应该仍然朝上。
        assertVector(floatArrayOf(0f, 1f, 0f), floatArrayOf(matrix[3], matrix[4], matrix[5]))
    }

    @Test
    fun `heading is zero when facing north and a quarter turn when facing east`() {
        assertEquals(0f, VrOrientation.headingRadians(uprightNorth), 1e-5f)
        assertEquals((PI / 2).toFloat(), VrOrientation.headingRadians(uprightEast), 1e-5f)
    }

    @Test
    fun `recenter brings the current heading back to the image center`() {
        val heading = VrOrientation.headingRadians(uprightEast)
        val matrix = VrOrientation.cameraMatrix(
            rotation = uprightEast,
            recenterHeadingRadians = heading,
            manualYawRadians = 0f,
            manualPitchRadians = 0f,
        )
        assertVector(floatArrayOf(0f, 0f, -1f), forward(matrix))
    }

    @Test
    fun `positive manual yaw looks left and positive pitch looks up`() {
        val yaw = VrOrientation.cameraMatrix(
            rotation = uprightNorth,
            recenterHeadingRadians = 0f,
            manualYawRadians = (PI / 2).toFloat(),
            manualPitchRadians = 0f,
        )
        // 正角度相当于视线向左偏（世界向左转）。
        assertVector(floatArrayOf(-1f, 0f, 0f), forward(yaw))

        val pitch = VrOrientation.cameraMatrix(
            rotation = uprightNorth,
            recenterHeadingRadians = 0f,
            manualYawRadians = 0f,
            manualPitchRadians = (PI / 2).toFloat(),
        )
        assertVector(floatArrayOf(0f, 1f, 0f), forward(pitch))
    }

    @Test
    fun `gesture matrix keeps a unit camera and applies yaw and pitch`() {
        assertVector(floatArrayOf(0f, 0f, -1f), forward(VrOrientation.gestureMatrix(0f, 0f)))

        val yaw = VrOrientation.gestureMatrix((PI / 2).toFloat(), 0f)
        assertVector(floatArrayOf(-1f, 0f, 0f), forward(yaw))

        val pitch = VrOrientation.gestureMatrix(0f, (PI / 2).toFloat())
        assertVector(floatArrayOf(0f, 1f, 0f), forward(pitch))
    }

    @Test
    fun `camera matrix stays orthonormal`() {
        val matrix = VrOrientation.cameraMatrix(
            rotation = uprightEast,
            recenterHeadingRadians = 0.3f,
            manualYawRadians = 0.2f,
            manualPitchRadians = -0.4f,
        )
        // 每列长度应为 1，任意两列点积为 0。
        for (column in 0..2) {
            val x = matrix[column * 3]
            val y = matrix[column * 3 + 1]
            val z = matrix[column * 3 + 2]
            assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-4f)
        }
        for (a in 0..2) {
            for (b in a + 1..2) {
                val dot = matrix[a * 3] * matrix[b * 3] +
                    matrix[a * 3 + 1] * matrix[b * 3 + 1] +
                    matrix[a * 3 + 2] * matrix[b * 3 + 2]
                assertEquals(0f, dot, 1e-4f)
            }
        }
    }

    @Test
    fun `normalizeDegrees wraps into minus 180 to 180`() {
        assertEquals(10f, VrOrientation.normalizeDegrees(370f), 1e-4f)
        assertEquals(-10f, VrOrientation.normalizeDegrees(-370f), 1e-4f)
        assertEquals(170f, VrOrientation.normalizeDegrees(-190f), 1e-4f)
        assertEquals(-180f, VrOrientation.normalizeDegrees(180f), 1e-4f)
    }
}
