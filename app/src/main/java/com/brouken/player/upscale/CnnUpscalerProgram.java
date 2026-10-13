package com.brouken.player.upscale;

import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import java.nio.FloatBuffer;

/**
 * Runs a small convolutional upscaler network (Anime4K / FSRCNNX) as a chain of
 * internal render passes inside a single {@link BaseGlShaderProgram}.
 *
 * <p>A linear chain of {@code GlEffect}s cannot express the skip connections these
 * networks need (Anime4K's dense 1x1 pass reads 7 earlier feature maps at once;
 * FSRCNNX's sub-band passes read the early FEATURE maps), so all passes live here:
 * each pass renders a fullscreen quad into a private RGBA16F texture, and the last
 * pass renders the 2x result into the framework's output framebuffer.
 *
 * <p>Failure policy: this program must never break playback. Whenever the GPU cannot
 * run the network (input too large, no half-float render targets, shader compile
 * failure, or a runtime GL error), it degrades to a plain texture copy instead of
 * throwing out of the frame path.
 */
@UnstableApi
public final class CnnUpscalerProgram extends BaseGlShaderProgram {
  private static final String TAG = "CnnUpscalerProgram";

  /** Sentinel matching the generated *Shaders classes: framework input texture. */
  private static final int INPUT = -1;
  /** Sentinel matching the generated *Shaders classes: framework output target. */
  private static final int OUTPUT = -1;

  /** Inputs larger than 1920x1080 bypass the CNN (transient ~130-150 MB of FP16). */
  private static final long MAX_INPUT_AREA = 1920L * 1080L;
  /** The dense Anime4K pass binds 7 textures at once; require at least 8 units. */
  private static final int MIN_TEXTURE_UNITS = 8;

  private static final String BLIT_FRAGMENT =
      "precision mediump float;\n"
          + "varying vec2 vTexCoord;\n"
          + "uniform sampler2D uTex0;\n"
          + "void main() {\n"
          + "  gl_FragColor = texture2D(uTex0, vTexCoord);\n"
          + "}\n";

  private final String[] fragmentShaders;
  private final int[][] passSrc;
  private final int[] passDst;
  private final int bankSize;

  private final FloatBuffer positionBuffer = GlQuad.newPositionBuffer();
  private final FloatBuffer texCoordBuffer = GlQuad.newTexCoordBuffer();

  private int inputWidth;
  private int inputHeight;
  private boolean passthrough;

  private int[] programs = new int[0];
  private int[][] samplerLocations = new int[0][];
  private int[] texelLocations = new int[0];
  private int[] inputSizeLocations = new int[0];
  private int[] positionLocations = new int[0];
  private int[] texCoordLocations = new int[0];

  private int[] bankTextures = new int[0];
  private int[] bankFbos = new int[0];

  private int blitProgram;
  private int blitPositionLocation;
  private int blitTexCoordLocation;
  private int blitSamplerLocation;

  public CnnUpscalerProgram(
      String[] fragmentShaders, int[][] passSrc, int[] passDst, int bankSize) {
    super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
    this.fragmentShaders = fragmentShaders;
    this.passSrc = passSrc;
    this.passDst = passDst;
    this.bankSize = bankSize;
  }

  @Override
  public Size configure(int inputWidth, int inputHeight)
      throws VideoFrameProcessingException {
    releaseGlObjects();
    this.inputWidth = inputWidth;
    this.inputHeight = inputHeight;
    try {
      blitProgram = GlQuad.compileProgram(GlQuad.VERTEX_SHADER, BLIT_FRAGMENT);
      blitPositionLocation = GLES20.glGetAttribLocation(blitProgram, "aPosition");
      blitTexCoordLocation = GLES20.glGetAttribLocation(blitProgram, "aTexCoord");
      blitSamplerLocation = GLES20.glGetUniformLocation(blitProgram, "uTex0");
    } catch (GlUtil.GlException e) {
      throw VideoFrameProcessingException.from(e);
    }

    passthrough = shouldBypass(inputWidth, inputHeight);
    if (!passthrough) {
      try {
        allocateBank(inputWidth, inputHeight);
        compilePasses();
      } catch (GlUtil.GlException | RuntimeException e) {
        Log.e(TAG, "CNN setup failed, falling back to passthrough", e);
        releaseCnnGlObjects();
        passthrough = true;
      }
    }
    if (passthrough) {
      return new Size(inputWidth, inputHeight);
    }
    return new Size(inputWidth * 2, inputHeight * 2);
  }

  @Override
  public void drawFrame(int inputTexId, long presentationTimeUs) {
    if (passthrough || programs.length == 0) {
      blit(inputTexId);
      return;
    }
    int[] savedFbo = new int[1];
    int[] savedViewport = new int[4];
    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFbo, 0);
    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);
    try {
      for (int p = 0; p < programs.length; p++) {
        boolean isFinal = passDst[p] == OUTPUT;
        int targetFbo = isFinal ? savedFbo[0] : bankFbos[passDst[p]];
        int targetW = isFinal ? inputWidth * 2 : inputWidth;
        int targetH = isFinal ? inputHeight * 2 : inputHeight;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo);
        GLES20.glViewport(0, 0, targetW, targetH);
        GLES20.glUseProgram(programs[p]);
        int[] srcs = passSrc[p];
        for (int u = 0; u < srcs.length; u++) {
          int texId = srcs[u] == INPUT ? inputTexId : bankTextures[srcs[u]];
          GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + u);
          GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
          GLES20.glUniform1i(samplerLocations[p][u], u);
        }
        GLES20.glUniform2f(texelLocations[p], 1f / inputWidth, 1f / inputHeight);
        GLES20.glUniform2f(inputSizeLocations[p], (float) inputWidth, (float) inputHeight);
        GlQuad.draw(positionLocations[p], texCoordLocations[p],
            positionBuffer, texCoordBuffer);
      }
    } catch (RuntimeException e) {
      // Never break playback because of the upscaler: show the frame unscaled.
      Log.e(TAG, "CNN drawFrame failed, blitting instead", e);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFbo[0]);
      GLES20.glViewport(
          savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
      blit(inputTexId);
      return;
    } finally {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFbo[0]);
      GLES20.glViewport(
          savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glUseProgram(0);
    }
  }

  @Override
  public void release() throws VideoFrameProcessingException {
    try {
      super.release();
    } finally {
      releaseGlObjects();
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private boolean shouldBypass(int w, int h) {
    if ((long) w * h > MAX_INPUT_AREA) {
      Log.i(TAG, "Input " + w + "x" + h + " too large for CNN upscaler, bypassing");
      return true;
    }
    int[] maxUnits = new int[1];
    GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, maxUnits, 0);
    if (maxUnits[0] < MIN_TEXTURE_UNITS) {
      Log.i(TAG, "Only " + maxUnits[0] + " texture units, CNN upscaler needs "
          + MIN_TEXTURE_UNITS + ", bypassing");
      return true;
    }
    return false;
  }

  private void allocateBank(int w, int h) throws GlUtil.GlException {
    bankTextures = new int[bankSize];
    bankFbos = new int[bankSize];
    for (int i = 0; i < bankSize; i++) {
      int texId = GlUtil.createTexture(w, h, /* useHighPrecisionColorComponents= */ true);
      int fboId = GlUtil.createFboForTexture(texId);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
      int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
      if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
        throw new GlUtil.GlException(
            "Half-float framebuffer not complete, status=" + status);
      }
      bankTextures[i] = texId;
      bankFbos[i] = fboId;
    }
  }

  private void compilePasses() throws GlUtil.GlException {
    int n = fragmentShaders.length;
    programs = new int[n];
    samplerLocations = new int[n][];
    texelLocations = new int[n];
    inputSizeLocations = new int[n];
    positionLocations = new int[n];
    texCoordLocations = new int[n];
    for (int p = 0; p < n; p++) {
      int program = GlQuad.compileProgram(GlQuad.VERTEX_SHADER, fragmentShaders[p]);
      programs[p] = program;
      int[] srcs = passSrc[p];
      samplerLocations[p] = new int[srcs.length];
      for (int u = 0; u < srcs.length; u++) {
        samplerLocations[p][u] = GLES20.glGetUniformLocation(program, "uTex" + u);
      }
      texelLocations[p] = GLES20.glGetUniformLocation(program, "uTexel");
      inputSizeLocations[p] = GLES20.glGetUniformLocation(program, "uInputSize");
      positionLocations[p] = GLES20.glGetAttribLocation(program, "aPosition");
      texCoordLocations[p] = GLES20.glGetAttribLocation(program, "aTexCoord");
    }
  }

  /** Plain texture copy used for the bypass path and as the last-resort fallback. */
  private void blit(int inputTexId) {
    try {
      GLES20.glUseProgram(blitProgram);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId);
      GLES20.glUniform1i(blitSamplerLocation, 0);
      GlQuad.draw(blitPositionLocation, blitTexCoordLocation,
          positionBuffer, texCoordBuffer);
      GLES20.glUseProgram(0);
    } catch (RuntimeException e) {
      Log.e(TAG, "Blit fallback failed", e);
    }
  }

  private void releaseGlObjects() {
    releaseCnnGlObjects();
    if (blitProgram != 0) {
      GLES20.glDeleteProgram(blitProgram);
      blitProgram = 0;
    }
  }

  /** Releases CNN passes and bank textures, keeping the blit fallback alive. */
  private void releaseCnnGlObjects() {
    for (int program : programs) {
      GLES20.glDeleteProgram(program);
    }
    programs = new int[0];
    samplerLocations = new int[0][];
    texelLocations = new int[0];
    inputSizeLocations = new int[0];
    positionLocations = new int[0];
    texCoordLocations = new int[0];
    for (int fbo : bankFbos) {
      if (fbo != 0) {
        GLES20.glDeleteFramebuffers(1, new int[] {fbo}, 0);
      }
    }
    bankFbos = new int[0];
    for (int tex : bankTextures) {
      if (tex != 0) {
        GLES20.glDeleteTextures(1, new int[] {tex}, 0);
      }
    }
    bankTextures = new int[0];
  }
}
