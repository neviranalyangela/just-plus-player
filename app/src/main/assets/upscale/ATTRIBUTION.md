# Video upscaler attribution

The optional neural-network video upscalers in
`app/src/main/java/com/brouken/player/upscale/` are ports of mpv user shaders
to ESSL 1.00, executed as ExoPlayer `GlEffect` video effects
(`player.setVideoEffects(...)`). The network weights are embedded verbatim in
the generated `*Shaders.java` files — only the mpv sampling scaffolding
(`texture()` offsets, `//!BIND`/`//!SAVE` hooks) was translated; every weight
literal was carried over unchanged and verified against the originals.

## Anime4K v3.2 CNN x2 (M)

- Original: `Anime4K_Upscale_CNN_x2_M.glsl` by bloc97 (kept in this directory)
- License: MIT — Copyright (c) 2019-2021 bloc97
- The copyright notice is preserved in `Anime4KShaders.java`.

## FSRCNNX x2 8-0-4-1

- Original: `FSRCNNX_x2_8-0-4-1.glsl` by igv (kept in this directory,
  unmodified, with its copyright and license header intact)
- License: GNU Lesser General Public License v3.0 only —
  Copyright (C) 2017-2021 igv
- `LGPL-3.0.txt` is a copy of the license text; `GPL-3.0.txt` is included
  because LGPL-3.0 §4(b) references it.
- The model stays replaceable: the original `.glsl` ships in this directory
  and in the public fork, so a modified shader can be re-ported with the same
  generator that produced `FsrcnnxShaders.java`; no app code depends on the
  embedded copy beyond the weights themselves.

## Runtime behaviour

- 2x upscale of the decoded frame on the GPU (FP16 render targets).
- Automatically bypassed (plain blit, playback never breaks) for inputs above
  1920x1080, HDR content, DRM, tunneling mode, missing half-float
  render targets, or any shader compile/runtime failure.
- GPU memory while active: ~133 MB (Anime4K) / ~150 MB (FSRCNNX) at 1080p.
