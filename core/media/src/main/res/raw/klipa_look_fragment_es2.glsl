#version 100
// A clip's look, as ReaCut renders it (src/core/visual.ts and src/core/color.ts):
// chroma key and shape mask on the source pixels, then brightness and contrast,
// Rec. 709 saturation and color temperature, each clamped like ReaCut's 8-bit stages.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;

// Frame size in pixels.
uniform vec2 uSize;

// Color, on 0..1 RGB: brightness offset, contrast and saturation multipliers, and the
// temperature tint added to red and taken from blue.
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uTint;

// Chroma key: 1 when enabled; RGB distance below the similarity is keyed out and the
// blend band above it fades in.
uniform int uKeyEnabled;
uniform vec3 uKeyColor;
uniform float uKeySimilarity;
uniform float uKeyBlend;

// Shape mask centered on the frame: 0 none, 1 rectangle, 2 ellipse. Half extents and
// feather are in pixels; the feather spans both sides of the edge.
uniform int uMaskShape;
uniform vec2 uMaskRadius;
uniform float uMaskFeather;
uniform int uMaskInvert;

// 0 straight alpha, 1 premultiplied alpha, 2 composited over black (opaque).
uniform int uOutputMode;

float keyAlpha(vec3 color) {
  float distance = length(color - uKeyColor) / sqrt(3.0);
  if (distance <= uKeySimilarity) {
    return 0.0;
  }
  if (uKeyBlend > 0.0) {
    return clamp((distance - uKeySimilarity) / uKeyBlend, 0.0, 1.0);
  }
  return 1.0;
}

float maskAlpha(vec2 pixel) {
  vec2 offset = abs(pixel - uSize * 0.5);
  float distance;
  if (uMaskShape == 2) {
    distance = (length(offset / uMaskRadius) - 1.0) * min(uMaskRadius.x, uMaskRadius.y);
  } else {
    vec2 outside = offset - uMaskRadius;
    distance = length(max(outside, 0.0)) + min(max(outside.x, outside.y), 0.0);
  }
  float inside;
  if (uMaskFeather > 0.0) {
    inside = clamp(0.5 - distance / uMaskFeather, 0.0, 1.0);
  } else {
    inside = distance <= 0.0 ? 1.0 : 0.0;
  }
  return uMaskInvert == 1 ? 1.0 - inside : inside;
}

void main() {
  vec4 source = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 color = source.rgb;
  float alpha = source.a;
  if (uKeyEnabled == 1) {
    alpha *= keyAlpha(color);
  }
  if (uMaskShape > 0) {
    alpha *= maskAlpha(vTexSamplingCoord * uSize);
  }
  color = clamp(color + uBrightness, 0.0, 1.0);
  color = clamp((color - 0.5) * uContrast + 0.5, 0.0, 1.0);
  float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
  color = clamp(vec3(luma) + (color - vec3(luma)) * uSaturation, 0.0, 1.0);
  color.r = clamp(color.r + uTint, 0.0, 1.0);
  color.b = clamp(color.b - uTint, 0.0, 1.0);
  if (uOutputMode == 1) {
    gl_FragColor = vec4(color * alpha, alpha);
  } else if (uOutputMode == 2) {
    gl_FragColor = vec4(color * alpha, 1.0);
  } else {
    gl_FragColor = vec4(color, alpha);
  }
}
