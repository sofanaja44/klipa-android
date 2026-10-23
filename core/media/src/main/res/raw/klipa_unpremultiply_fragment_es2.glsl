#version 100
// Turns premultiplied alpha back into straight alpha, which Media3's compositor blends.
precision mediump float;

uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;

void main() {
  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
  gl_FragColor = color.a > 0.0 ? vec4(color.rgb / color.a, color.a) : vec4(0.0);
}
