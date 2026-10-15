package com.strike.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.strike.daemon.DaemonLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "Shader"
private const val FLOAT_BYTES = 4
private const val COORDS_PER_VERTEX = 2

private const val VERTEX = """
attribute vec2 position;
attribute vec2 sourceCoord;
varying vec2 source;
void main() {
    source = sourceCoord;
    gl_Position = vec4(position, 0.0, 1.0);
}
"""

// Camera gralloc buffers require samplerExternalOES.
private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 source;
uniform samplerExternalOES camera;
void main() {
    gl_FragColor = texture2D(camera, source);
}
"""

// Must agree with lensSource. slice is the camera's x and width in the strip; lens is k1, k2, tile aspect.
// mediump is 16-bit on Adreno, a few pixels of error across the strip.
private const val LENS_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 source;
uniform samplerExternalOES camera;
uniform vec2 slice;
uniform vec3 lens;
void main() {
    vec2 offset = vec2((source.x - slice.x) / slice.y, source.y) - 0.5;
    float aspect2 = lens.z * lens.z;
    float r2 = (offset.x * offset.x * aspect2 + offset.y * offset.y) / (0.25 * (aspect2 + 1.0));
    vec2 bent = clamp(0.5 + offset * (1.0 + lens.x * r2 + lens.y * r2 * r2), 0.0, 1.0);
    gl_FragColor = texture2D(camera, vec2(slice.x + bent.x * slice.y, bent.y));
}
"""

private val QUAD = floatArrayOf(
    -1f, -1f,
    1f, -1f,
    -1f, 1f,
    1f, 1f
)

class StripShader private constructor(
    private val program: Int,
    private val positionSlot: Int,
    private val sourceSlot: Int,
    private val cameraSlot: Int,
    private val sliceSlot: Int,
    private val lensSlot: Int
) {

    private val positions = buffer(QUAD)
    private val sources = buffer(FloatArray(QUAD.size))

    fun newTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        return id
    }

    fun draw(texture: Int, tiles: List<Tile>, frame: Frame, correction: LensCorrection = LensCorrection.NONE) {
        clear()
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(cameraSlot, 0)
        GLES20.glEnableVertexAttribArray(positionSlot)
        GLES20.glEnableVertexAttribArray(sourceSlot)
        GLES20.glVertexAttribPointer(
            positionSlot, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, positions
        )
        for (tile in tiles) {
            viewport(tile, frame)
            if (lensSlot >= 0) {
                GLES20.glUniform2f(sliceSlot, tile.sourceX, tile.sourceWidth)
                val aspect = (tile.destWidth * frame.width) / (tile.destHeight * frame.height)
                GLES20.glUniform3f(lensSlot, correction.k1, correction.k2, aspect)
            }
            sources.clear()
            sources.put(sourceCoords(tile)).position(0)
            GLES20.glVertexAttribPointer(
                sourceSlot, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, sources
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD.size / COORDS_PER_VERTEX)
        }
        GLES20.glDisableVertexAttribArray(positionSlot)
        GLES20.glDisableVertexAttribArray(sourceSlot)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {

        fun compile(): StripShader? = compile(FRAGMENT)

        fun compileLens(): StripShader? = compile(LENS_FRAGMENT)

        private fun compile(source: String): StripShader? {
            val vertex = stage(GLES20.GL_VERTEX_SHADER, VERTEX) ?: return null
            val fragment = stage(GLES20.GL_FRAGMENT_SHADER, source) ?: return null
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] != GLES20.GL_TRUE) {
                DaemonLog.e(TAG, "the crop program will not link: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                return null
            }
            return StripShader(
                program = program,
                positionSlot = GLES20.glGetAttribLocation(program, "position"),
                sourceSlot = GLES20.glGetAttribLocation(program, "sourceCoord"),
                cameraSlot = GLES20.glGetUniformLocation(program, "camera"),
                sliceSlot = GLES20.glGetUniformLocation(program, "slice"),
                lensSlot = GLES20.glGetUniformLocation(program, "lens")
            )
        }

        private fun stage(type: Int, source: String): Int? {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val built = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, built, 0)
            if (built[0] != GLES20.GL_TRUE) {
                DaemonLog.e(TAG, "the crop shader will not build: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return null
            }
            return shader
        }
    }
}

private fun viewport(tile: Tile, frame: Frame) {
    val width = Math.round(tile.destWidth * frame.width)
    val height = Math.round(tile.destHeight * frame.height)
    // GL counts rows from the bottom and the tiles are laid out from the top.
    val bottom = frame.height - Math.round(tile.destY * frame.height) - height
    GLES20.glViewport(Math.round(tile.destX * frame.width), bottom, width, height)
}

private fun buffer(values: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(values)
        .also { it.position(0) }

// Quad corners are bottom-left, bottom-right, top-left, top-right; invert the strip's V axis.
internal fun sourceCoords(tile: Tile): FloatArray {
    val left = tile.sourceX
    val right = tile.sourceX + tile.sourceWidth
    return floatArrayOf(
        left, 1f,
        right, 1f,
        left, 0f,
        right, 0f
    )
}
