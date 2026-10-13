package com.brouken.player.upscale;

import android.content.Context;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * FSRCNNX x2 (8-0-4-1) upscaler as an ExoPlayer video effect.
 *
 * <p>Model: LGPL-3.0-only, Copyright (C) 2017-2021 igv. The weights are embedded
 * verbatim in {@link FsrcnnxShaders} (generated from the original mpv shader);
 * the original shader source and the LGPL-3.0 license text ship in
 * {@code assets/upscale/} so the model stays replaceable.
 *
 * <p>HDR frames are passed through untouched: the network is trained for SDR.
 */
@UnstableApi
public final class FsrcnnxEffect implements GlEffect {
  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    if (useHdr) {
      return new PassthroughProgram();
    }
    return new CnnUpscalerProgram(
        FsrcnnxShaders.FRAGMENT,
        FsrcnnxShaders.PASS_SRC,
        FsrcnnxShaders.PASS_DST,
        FsrcnnxShaders.BANK_SIZE);
  }
}
