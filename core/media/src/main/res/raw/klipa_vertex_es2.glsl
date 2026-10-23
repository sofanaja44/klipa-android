#version 100
// Draws the input frame unchanged onto the output frame.
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;

void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
