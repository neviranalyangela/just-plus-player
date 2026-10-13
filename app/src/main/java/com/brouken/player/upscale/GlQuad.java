package com.brouken.player.upscale;

import android.opengl.GLES20;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Shared fullscreen-quad rendering and GLSL program compilation helpers. */
final class GlQuad {
  private static final String TAG = "GlQuad";

  static final String VERTEX_SHADER =
      "attribute vec4 aPosition;\n"
          + "attribute vec2 aTexCoord;\n"
          + "varying vec2 vTexCoord;\n"
          + "void main() {\n"
          + "  gl_Position = aPosition;\n"
          + "  vTexCoord = aTexCoord;\n"
          + "}\n";

  private static final float[] POSITION_COORDS = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
  private static final float[] TEX_COORDS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

  private GlQuad() {}

  static FloatBuffer newPositionBuffer() {
    return newFloatBuffer(POSITION_COORDS);
  }

  static FloatBuffer newTexCoordBuffer() {
    return newFloatBuffer(TEX_COORDS);
  }

  static void draw(int positionLocation, int texCoordLocation, FloatBuffer positions,
      FloatBuffer texCoords) {
    GLES20.glEnableVertexAttribArray(positionLocation);
    GLES20.glVertexAttribPointer(
        positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions);
    GLES20.glEnableVertexAttribArray(texCoordLocation);
    GLES20.glVertexAttribPointer(
        texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoords);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    GLES20.glDisableVertexAttribArray(positionLocation);
    GLES20.glDisableVertexAttribArray(texCoordLocation);
  }

  static int compileProgram(String vertexSource, String fragmentSource)
      throws GlUtil.GlException {
    int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
    int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
    int program = GLES20.glCreateProgram();
    if (program == 0) {
      GLES20.glDeleteShader(vertexShader);
      GLES20.glDeleteShader(fragmentShader);
      throw new GlUtil.GlException("glCreateProgram failed");
    }
    GLES20.glAttachShader(program, vertexShader);
    GLES20.glAttachShader(program, fragmentShader);
    GLES20.glLinkProgram(program);
    GLES20.glDeleteShader(vertexShader);
    GLES20.glDeleteShader(fragmentShader);
    int[] linkStatus = new int[1];
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
    if (linkStatus[0] == 0) {
      String log = GLES20.glGetProgramInfoLog(program);
      GLES20.glDeleteProgram(program);
      throw new GlUtil.GlException("Program link failed: " + log);
    }
    return program;
  }

  private static int compileShader(int type, String source) throws GlUtil.GlException {
    int shader = GLES20.glCreateShader(type);
    if (shader == 0) {
      throw new GlUtil.GlException("glCreateShader failed for type " + type);
    }
    GLES20.glShaderSource(shader, source);
    GLES20.glCompileShader(shader);
    int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
    if (compileStatus[0] == 0) {
      String log = GLES20.glGetShaderInfoLog(shader);
      GLES20.glDeleteShader(shader);
      String excerpt = source.length() > 300 ? source.substring(0, 300) : source;
      Log.e(TAG, "Shader compile failed: " + log);
      throw new GlUtil.GlException("Shader compile failed: " + log + " | src: " + excerpt);
    }
    return shader;
  }

  private static FloatBuffer newFloatBuffer(float[] data) {
    ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
    bb.order(ByteOrder.nativeOrder());
    FloatBuffer fb = bb.asFloatBuffer();
    fb.put(data);
    fb.position(0);
    return fb;
  }
}
