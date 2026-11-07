// Export-only tool. Published docs use the resulting image, with no WebGL runtime.
import { ShaderMount } from './node_modules/@paper-design/shaders/dist/shader-mount.js';
import { lensDistortionFragmentShader } from './node_modules/@paper-design/shaders/dist/shaders/lens-distortion.js';

const status = document.querySelector('#status');
const button = document.querySelector('#export');
const image = new Image();
image.src = './blue-flower.png';

try {
  await image.decode();
  const mount = new ShaderMount(document.querySelector('#artwork'), lensDistortionFragmentShader, {
    u_image: image,
    u_fit: 2, u_scale: 1.12, u_rotation: 0,
    u_originX: 0.5, u_originY: 0.5, u_offsetX: 0, u_offsetY: 0,
    u_spread: 0.42, u_bias: 0.6, u_angle: 145, u_perspective: 0.3,
    u_count: 35, u_dispersion: 0.18, u_dispersionShift: 0.3, u_dispersionColor: 0.6,
    u_focusCenter: 0.7, u_focusEdges: 0.7, u_swirl: 0.3,
    u_noise: 0.03, u_noiseFrequency: 0.25, u_noiseOffset: 0,
    u_lensBulge: 0, u_lensCircle: 0,
    u_grainMixer: 0.08, u_grainOverlay: 0.16,
    u_imageX: 0, u_imageY: 0,
  }, { preserveDrawingBuffer: true, alpha: false }, 0, 0, 2, 1600 * 800);
  button.disabled = false;
  status.textContent = 'Ready · Paper Shaders 0.0.82 · static frame';
  button.addEventListener('click', () => {
    mount.canvasElement.toBlob(blob => {
      if (!blob) { status.textContent = 'Export failed: canvas is empty.'; return; }
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'blue-flower-lens.png';
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
      status.textContent = 'Exported · copy blue-flower-lens.png into tools/docs/artwork/';
    }, 'image/png');
  });
} catch (error) {
  status.textContent = `Unable to render: ${error.message}`;
}
