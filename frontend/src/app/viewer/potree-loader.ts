/**
 * Best-effort Potree wrapper: dynamically loads the Potree bundle + its
 * dependencies from a CDN and opens the COPC stream. If anything fails
 * (offline, CDN layout changes), the caller falls back to the built-in
 * three.js LAS viewer so the demo always works.
 */
declare const window: any;

const POTREE_CDN = 'https://cdn.jsdelivr.net/gh/potree/potree@1.8.2/build/potree';
const LIBS = 'https://cdn.jsdelivr.net/gh/potree/potree@1.8.2/libs';

function script(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error(`failed to load ${src}`));
    document.head.appendChild(s);
  });
}

function css(href: string): void {
  const l = document.createElement('link');
  l.rel = 'stylesheet';
  l.href = href;
  document.head.appendChild(l);
}

let loaded = false;

export async function tryPotree(container: HTMLElement, copcUrl: string): Promise<any> {
  if (!loaded) {
    css(`${POTREE_CDN}/potree.css`);
    await script(`${LIBS}/jquery/jquery-3.1.1.min.js`);
    await script(`${LIBS}/three.js/build/three.min.js`);
    await script(`${LIBS}/other/BinaryHeap.js`);
    await script(`${LIBS}/tween/tween.min.js`);
    await script(`${LIBS}/proj4/proj4.js`);
    await script(`${POTREE_CDN}/potree.js`);
    loaded = true;
  }
  if (!window.Potree) throw new Error('Potree global missing');

  const viewer = new window.Potree.Viewer(container);
  viewer.setEDLEnabled(true);
  viewer.setPointBudget(2_000_000);
  viewer.setBackground('gradient');

  // COPC support landed in newer Potree builds; older builds throw here and we fall back.
  const cloud = await window.Potree.loadPointCloud(copcUrl, 'deliverable');
  viewer.scene.addPointCloud(cloud.pointcloud);
  viewer.fitToScreen();
  return viewer;
}
