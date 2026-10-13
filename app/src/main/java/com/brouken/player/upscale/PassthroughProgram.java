package com.brouken.player.upscale;

import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import java.nio.FloatBuffer;

/**
 * A 1:1 texture copy used when an upscaler must not touch the frame
 * (HDR input) or as the guaranteed-safe fallback.
 */
@UnstableApi
public final class PassthroughProgram extends BaseGlShaderProgram {
  private static final String FRAGMENT_SHADER =
      "precision mediump float;\n"
          + "varying vec2 vTexCoord;\n"
          + "uniform sampler2D uTex0;\n"
          + "void main() {\n"
          + "  gl_FragColor = texture2D(uTex0, vTexCoord);\n"
          + "}\n";

  private final FloatBuffer positionBuffer = GlQuad.newPositionBuffer();
  private final FloatBuffer texCoordBuffer = GlQuad.newTexCoordBuffer();

  private int program;
  private int positionLocation;
  private int texCoordLocation;
  private int samplerLocation;

  public PassthroughProgram() {
    super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
  }

  @Override
  public Size configure(int inputWidth, int inputHeight)
      throws VideoFrameProcessingException {
    if (program != 0) {
      GLES20.glDeleteProgram(program);
      program = 0;
    }
    try {
      program = GlQuad.compileProgram(GlQuad.VERTEX_SHADER, FRAGMENT_SHADER);
    } catch (GlUtil.GlException e) {
      throw VideoFrameProcessingException.from(e);
    }
    positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
    texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
    samplerLocation = GLES20.glGetUniformLocation(program, "uTex0");
    return new Size(inputWidth, inputHeight);
  }

  @Override
  public void drawFrame(int inputTexId, long presentationTimeUs) {
    GLES20.glUseProgram(program);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId);
    GLES20.glUniform1i(samplerLocation, 0);
    GlQuad.draw(positionLocation, texCoordLocation, positionBuffer, texCoordBuffer);
    GLES20.glUseProgram(0);
  }

  @Override
  public void release() throws VideoFrameProcessingException {
    try {
      super.release();
    } finally {
      if (program != 0) {
        GLES20.glDeleteProgram(program);
        program = 0;
      }
    }
  }
}
