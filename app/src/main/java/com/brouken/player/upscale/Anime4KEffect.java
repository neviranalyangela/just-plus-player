package com.brouken.player.upscale;

import android.content.Context;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * Anime4K v3.2 CNN x2 (M) upscaler as an ExoPlayer video effect.
 *
 * <p>Model: MIT license, Copyright (c) 2019-2021 bloc97. The weights are embedded
 * verbatim in {@link Anime4KShaders} (generated from the original mpv shader).
 *
 * <p>HDR frames are passed through untouched: the network is trained for SDR.
 */
@UnstableApi
public final class Anime4KEffect implements GlEffect {
  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    if (useHdr) {
      return new PassthroughProgram();
    }
    return new CnnUpscalerProgram(
        Anime4KShaders.FRAGMENT,
        Anime4KShaders.PASS_SRC,
        Anime4KShaders.PASS_DST,
        Anime4KShaders.BANK_SIZE);
  }
}
