// Compiles Klipa's GLSL ES 1.00 shaders in WebGL 1 (headless Chromium, SwiftShader) and
// compares their output with ReaCut's own pixel functions, ported verbatim to JS below.
// Usage: node tools/shader-check/check.js core/media/src/main/res/raw
// (set PLAYWRIGHT_MODULE to Playwright's path if it is not installed locally).
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
const vertex = fs.readFileSync(path.join(dir, 'klipa_vertex_es2.glsl'), 'utf8');
const look = fs.readFileSync(path.join(dir, 'klipa_look_fragment_es2.glsl'), 'utf8');
const unpremultiply = fs.readFileSync(path.join(dir, 'klipa_unpremultiply_fragment_es2.glsl'), 'utf8');

// ---- ReaCut reference (src/core/color.ts, src/core/visual.ts) ----
const clamp = (v) => Math.max(0, Math.min(1, v));
const byte = (v) => Math.round(Math.max(0, Math.min(255, v)));
function applyColorAdjustments(data, a) {
  const { brightness, contrast, saturation, temperature } = a;
  if (brightness === 0 && contrast === 1 && saturation === 1 && temperature === 0) return;
  const offset = brightness * 255;
  const tint = temperature * 35;
  for (let i = 0; i + 3 < data.length; i += 4) {
    let r = byte((byte(data[i] + offset) - 127.5) * contrast + 127.5);
    let g = byte((byte(data[i + 1] + offset) - 127.5) * contrast + 127.5);
    let b = byte((byte(data[i + 2] + offset) - 127.5) * contrast + 127.5);
    if (saturation !== 1) {
      const luma = r * 0.2126 + g * 0.7152 + b * 0.0722;
      r = byte(luma + (r - luma) * saturation);
      g = byte(luma + (g - luma) * saturation);
      b = byte(luma + (b - luma) * saturation);
    }
    data[i] = byte(r + tint);
    data[i + 1] = g;
    data[i + 2] = byte(b - tint);
  }
}
function keyRGB(key) { return [1, 3, 5].map((i) => parseInt(key.color.slice(i, i + 2), 16)); }
function applyChromaKey(data, key) {
  if (!key?.enabled) return;
  const [red, green, blue] = keyRGB(key);
  for (let i = 0; i + 3 < data.length; i += 4) {
    const distance = Math.hypot(data[i] - red, data[i + 1] - green, data[i + 2] - blue) / (255 * Math.sqrt(3));
    const alpha = distance <= key.similarity ? 0 : key.blend > 0 ? clamp((distance - key.similarity) / key.blend) : 1;
    const keyed = Math.floor(255 * alpha);
    data[i + 3] = Math.floor((data[i + 3] * keyed) / 255);
  }
}
function maskOpacity(x, y, width, height, mask) {
  const rx = Math.max(Number.EPSILON, (width * mask.width) / 2);
  const ry = Math.max(Number.EPSILON, (height * mask.height) / 2);
  const dx = Math.abs(x + 0.5 - width / 2);
  const dy = Math.abs(y + 0.5 - height / 2);
  let distance;
  if (mask.shape === 'ellipse') distance = (Math.hypot(dx / rx, dy / ry) - 1) * Math.min(rx, ry);
  else {
    const qx = dx - rx, qy = dy - ry;
    distance = Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0);
  }
  const feather = mask.feather * Math.min(width, height);
  const inside = feather > 0 ? clamp(0.5 - distance / feather) : distance <= 0 ? 1 : 0;
  return mask.invert ? 1 - inside : inside;
}
function applyShapeMask(data, width, height, mask) {
  if (!mask) return;
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      const alpha = (y * width + x) * 4 + 3;
      const maskByte = byte(255 * maskOpacity(x, y, width, height, mask));
      data[alpha] = Math.floor((data[alpha] * maskByte) / 255);
    }
}

// ---- Test frames ----
const W = 64, H = 48;
function frame(seed) {
  let s = seed;
  const rand = () => ((s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
  const data = new Uint8ClampedArray(W * H * 4);
  for (let i = 0; i < W * H; i++) {
    // Mix of random colors and near-green-screen pixels.
    const green = rand() < 0.3;
    data[i * 4] = green ? 10 + rand() * 40 : rand() * 255;
    data[i * 4 + 1] = green ? 200 + rand() * 55 : rand() * 255;
    data[i * 4 + 2] = green ? 10 + rand() * 40 : rand() * 255;
    data[i * 4 + 3] = rand() < 0.1 ? 128 : 255;
  }
  return data;
}

const neutral = { brightness: 0, contrast: 1, saturation: 1, temperature: 0 };
const presets = {
  cinema: { brightness: -0.04, contrast: 1.22, saturation: 0.78, temperature: -0.08 },
  warm: { brightness: 0.05, contrast: 1.05, saturation: 1.1, temperature: 0.3 },
  cool: { brightness: 0.015, contrast: 1.12, saturation: 0.9, temperature: -0.35 },
  mono: { brightness: 0, contrast: 1.15, saturation: 0, temperature: 0 },
  vintage: { brightness: 0.045, contrast: 0.86, saturation: 0.72, temperature: 0.22 },
  vivid: { brightness: 0.02, contrast: 1.1, saturation: 1.45, temperature: 0 },
  extremeLow: { brightness: -1, contrast: 0, saturation: 0, temperature: -1 },
  extremeHigh: { brightness: 1, contrast: 3, saturation: 3, temperature: 1 },
  mixed: { brightness: 0.5, contrast: 0.5, saturation: 2.5, temperature: 0.6 },
};
const cases = [];
for (const [name, color] of Object.entries(presets)) cases.push({ name: `color ${name}`, color });
cases.push({ name: 'key soft', color: neutral, key: { enabled: true, color: '#00ff00', similarity: 0.1, blend: 0.05 } });
cases.push({ name: 'key hard', color: neutral, key: { enabled: true, color: '#00ff00', similarity: 0.3, blend: 0 } });
cases.push({ name: 'key blue + warm', color: presets.warm, key: { enabled: true, color: '#1030c0', similarity: 0.25, blend: 0.2 } });
cases.push({ name: 'mask rect', color: neutral, mask: { shape: 'rectangle', width: 0.5, height: 0.6, feather: 0, invert: false } });
cases.push({ name: 'mask rect feather', color: neutral, mask: { shape: 'rectangle', width: 0.7, height: 0.4, feather: 0.15, invert: false } });
cases.push({ name: 'mask ellipse', color: neutral, mask: { shape: 'ellipse', width: 0.8, height: 0.5, feather: 0, invert: false } });
cases.push({ name: 'mask ellipse feather invert', color: presets.cinema, mask: { shape: 'ellipse', width: 0.6, height: 0.9, feather: 0.2, invert: true } });
cases.push({ name: 'key + mask', color: presets.vivid, key: { enabled: true, color: '#00ff00', similarity: 0.12, blend: 0.1 }, mask: { shape: 'ellipse', width: 0.9, height: 0.9, feather: 0.05, invert: false } });

function uniformsFor(c) {
  const k = c.key;
  const m = c.mask;
  return {
    uSize: [W, H],
    uBrightness: c.color.brightness,
    uContrast: c.color.contrast,
    uSaturation: c.color.saturation,
    uTint: (c.color.temperature * 35) / 255,
    uKeyEnabled: k?.enabled ? 1 : 0,
    uKeyColor: k ? keyRGB(k).map((v) => v / 255) : [0, 0, 0],
    uKeySimilarity: k ? k.similarity : 0,
    uKeyBlend: k ? k.blend : 0,
    uMaskShape: m ? (m.shape === 'ellipse' ? 2 : 1) : 0,
    uMaskRadius: m ? [Math.max(1e-6, (W * m.width) / 2), Math.max(1e-6, (H * m.height) / 2)] : [1, 1],
    uMaskFeather: m ? m.feather * Math.min(W, H) : 0,
    uMaskInvert: m?.invert ? 1 : 0,
  };
}

(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  await page.setContent('<canvas id="c" width="64" height="48"></canvas>');
  const results = await page.evaluate(({ vertex, look, unpremultiply, W, H, jobs }) => {
    const gl = document.getElementById('c').getContext('webgl', { premultipliedAlpha: false, antialias: false });
    if (!gl) return { error: 'no webgl' };
    const out = { renderer: gl.getParameter(gl.VERSION) + ' / ' + gl.getParameter(gl.SHADING_LANGUAGE_VERSION) + ' / highp ' + gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT).precision };
    function compile(type, src) {
      const s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('compile: ' + gl.getShaderInfoLog(s));
      return s;
    }
    function program(fs) {
      const p = gl.createProgram();
      gl.attachShader(p, compile(gl.VERTEX_SHADER, vertex));
      gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
      gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link: ' + gl.getProgramInfoLog(p));
      return p;
    }
    let lookProgram, unpremultiplyProgram;
    try {
      lookProgram = program(look);
      unpremultiplyProgram = program(unpremultiply);
    } catch (e) {
      return { error: String(e) };
    }
    // Active uniforms: Media3's GlProgram throws for a uniform the compiler removed.
    const active = [];
    for (let i = 0; i < gl.getProgramParameter(lookProgram, gl.ACTIVE_UNIFORMS); i++) active.push(gl.getActiveUniform(lookProgram, i).name);
    out.active = active;
    const quad = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, quad);
    // Media3's GlUtil.getNormalizedCoordinateBounds(): a triangle strip in homogeneous coordinates.
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1]), gl.STATIC_DRAW);
    function texture(data) {
      const t = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, W, H, 0, gl.RGBA, gl.UNSIGNED_BYTE, data);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      return t;
    }
    function draw(p, input, uniforms) {
      const target = texture(null);
      const fbo = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0);
      gl.viewport(0, 0, W, H);
      gl.disable(gl.BLEND);
      gl.useProgram(p);
      const loc = gl.getAttribLocation(p, 'aFramePosition');
      gl.bindBuffer(gl.ARRAY_BUFFER, quad);
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, 4, gl.FLOAT, false, 0, 0);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, input);
      gl.uniform1i(gl.getUniformLocation(p, 'uTexSampler'), 0);
      for (const [name, value] of Object.entries(uniforms)) {
        const u = gl.getUniformLocation(p, name);
        if (Array.isArray(value)) (value.length === 2 ? gl.uniform2fv : gl.uniform3fv).call(gl, u, value);
        else if (Number.isInteger(value) && /^u(Key|Mask)(Enabled|Shape|Invert)$|^uOutputMode$/.test(name)) gl.uniform1i(u, value);
        else gl.uniform1f(u, value);
      }
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const pixels = new Uint8Array(W * H * 4);
      gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
      return { pixels, target };
    }
    out.jobs = jobs.map((job) => {
      const input = texture(new Uint8Array(job.input));
      const result = {};
      for (const mode of [0, 1, 2]) result[mode] = Array.from(draw(lookProgram, input, { ...job.uniforms, uOutputMode: mode }).pixels);
      // Premultiplied output back through unpremultiply.
      const premultiplied = draw(lookProgram, input, { ...job.uniforms, uOutputMode: 1 });
      result.roundTrip = Array.from(draw(unpremultiplyProgram, premultiplied.target, {}).pixels);
      return result;
    });
    return out;
  }, {
    vertex, look, unpremultiply, W, H,
    jobs: cases.map((c, i) => ({ input: Array.from(frame(i + 1)), uniforms: uniformsFor(c) })),
  });
  await browser.close();
  if (results.error) {
    console.log('FAILED:', results.error);
    process.exit(1);
  }
  console.log('WebGL:', results.renderer);
  console.log('active uniforms:', results.active.sort().join(', '));
  let failed = 0;
  cases.forEach((c, i) => {
    const expected = frame(i + 1);
    applyChromaKey(expected, c.key);
    applyShapeMask(expected, W, H, c.mask);
    applyColorAdjustments(expected, c.color);
    const got = results.jobs[i];
    let maxRgb = 0, maxAlpha = 0, maxPremul = 0, maxBlack = 0, maxRound = 0, compared = 0;
    for (let p = 0; p < W * H; p++) {
      const o = p * 4;
      const a = expected[o + 3];
      const straight = got[0];
      maxAlpha = Math.max(maxAlpha, Math.abs(straight[o + 3] - a));
      // Color only matters where the pixel is visible; ReaCut keeps RGB under alpha 0 too.
      for (let ch = 0; ch < 3; ch++) {
        maxRgb = Math.max(maxRgb, Math.abs(straight[o + ch] - expected[o + ch]));
        const premul = (expected[o + ch] * a) / 255;
        maxPremul = Math.max(maxPremul, Math.abs(got[1][o + ch] - premul));
        maxBlack = Math.max(maxBlack, Math.abs(got[2][o + ch] - premul));
        if (a >= 64) maxRound = Math.max(maxRound, Math.abs(got.roundTrip[o + ch] - expected[o + ch]));
      }
      maxBlack = Math.max(maxBlack, Math.abs(got[2][o + 3] - 255));
      compared++;
    }
    const ok = maxRgb <= 2 && maxAlpha <= 3 && maxPremul <= 3 && maxBlack <= 3 && maxRound <= 4;
    if (!ok) failed++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${c.name.padEnd(28)} rgb Δ${maxRgb} alpha Δ${maxAlpha} premultiplied Δ${maxPremul.toFixed(1)} over-black Δ${maxBlack.toFixed(1)} unpremultiplied Δ${maxRound} (${compared} px)`);
  });
  console.log(failed ? `${failed} case(s) failed` : 'all cases match ReaCut');
  process.exit(failed ? 1 : 0);
})();
