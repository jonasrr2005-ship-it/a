import * as THREE from '../lib/three.module.min.js';

// Sammelt viele Boxen/Quads/Geometrien in einer einzigen BufferGeometry,
// damit die ganze Stadt mit wenigen Draw-Calls gezeichnet wird.
export class GeoBuilder {
  constructor() {
    this.pos = []; this.nor = []; this.uv = []; this.col = []; this.idx = [];
    this.color = [1, 1, 1];
  }

  setColor(c) {
    const cc = c instanceof THREE.Color ? c : new THREE.Color(c);
    this.color = [cc.r, cc.g, cc.b];
    return this;
  }

  // a,b,c,d gegen den Uhrzeigersinn von vorne gesehen (unten links, unten rechts, oben rechts, oben links)
  quad(a, b, c, d, n, uv) {
    const i = this.pos.length / 3;
    for (const p of [a, b, c, d]) {
      this.pos.push(p[0], p[1], p[2]);
      this.nor.push(n[0], n[1], n[2]);
      this.col.push(this.color[0], this.color[1], this.color[2]);
    }
    this.uv.push(...(uv || [0, 0, 1, 0, 1, 1, 0, 1]));
    this.idx.push(i, i + 1, i + 2, i, i + 2, i + 3);
  }

  // Box mit Weltmaß-UVs: su/sv = Meter pro Texturwiederholung (0 = 0..1 je Fläche)
  box(x0, y0, z0, x1, y1, z1, opt = {}) {
    const su = opt.su || 0, sv = opt.sv || su, off = opt.uOff || 0;
    const faces = opt.faces || 'nsewt';
    const wall = (len) => su
      ? [off / su, y0 / sv, (off + len) / su, y0 / sv, (off + len) / su, y1 / sv, off / su, y1 / sv]
      : undefined;
    const w = x1 - x0, d = z1 - z0;
    if (faces.includes('s')) this.quad([x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1], [0, 0, 1], wall(w));
    if (faces.includes('n')) this.quad([x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0], [0, 0, -1], wall(w));
    if (faces.includes('e')) this.quad([x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1], [1, 0, 0], wall(d));
    if (faces.includes('w')) this.quad([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0], [-1, 0, 0], wall(d));
    if (faces.includes('t')) {
      const ts = opt.topScale || 0;
      const uv = ts ? [x0 / ts, -z1 / ts, x1 / ts, -z1 / ts, x1 / ts, -z0 / ts, x0 / ts, -z0 / ts] : undefined;
      this.quad([x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0], [0, 1, 0], uv);
    }
  }

  // Horizontale Fläche (Straße, Boden) mit frei wählbaren UVs
  flat(x0, z0, x1, z1, y, uv) {
    this.quad([x0, y, z1], [x1, y, z1], [x1, y, z0], [x0, y, z0], [0, 1, 0], uv);
  }

  // Beliebige three.js-Geometrie transformiert anhängen
  add(geo, matrix, color) {
    if (color !== undefined) this.setColor(color);
    const g = geo;
    const p = g.attributes.position, n = g.attributes.normal, t = g.attributes.uv;
    const base = this.pos.length / 3;
    const v = new THREE.Vector3();
    const nm = new THREE.Matrix3().getNormalMatrix(matrix);
    for (let i = 0; i < p.count; i++) {
      v.fromBufferAttribute(p, i).applyMatrix4(matrix);
      this.pos.push(v.x, v.y, v.z);
      v.fromBufferAttribute(n, i).applyMatrix3(nm).normalize();
      this.nor.push(v.x, v.y, v.z);
      this.uv.push(t ? t.getX(i) : 0, t ? t.getY(i) : 0);
      this.col.push(this.color[0], this.color[1], this.color[2]);
    }
    if (g.index) for (let i = 0; i < g.index.count; i++) this.idx.push(base + g.index.getX(i));
    else for (let i = 0; i < p.count; i++) this.idx.push(base + i);
  }

  get empty() { return this.idx.length === 0; }

  build() {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(this.pos, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(this.nor, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(this.uv, 2));
    g.setAttribute('color', new THREE.Float32BufferAttribute(this.col, 3));
    g.setIndex(this.idx);
    g.computeBoundingSphere();
    g.computeBoundingBox();
    return g;
  }
}

const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();
const _e = new THREE.Euler();
const _s = new THREE.Vector3();
const _t = new THREE.Vector3();
export function mat(x, y, z, rx = 0, ry = 0, rz = 0, sx = 1, sy = 1, sz = 1) {
  _q.setFromEuler(_e.set(rx, ry, rz));
  return _m.compose(_t.set(x, y, z), _q, _s.set(sx, sy, sz));
}

// Deterministischer Zufall, damit die Stadt bei jedem Start gleich aussieht
export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function canvasTex(w, h, draw, opts = {}) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  draw(c.getContext('2d'), w, h);
  const t = new THREE.CanvasTexture(c);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  if (opts.srgb !== false) t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = opts.aniso || 4;
  return t;
}

export function noise(ctx, w, h, base, amount, count) {
  ctx.fillStyle = base;
  ctx.fillRect(0, 0, w, h);
  for (let i = 0; i < count; i++) {
    const v = (Math.random() - 0.5) * amount;
    ctx.fillStyle = v > 0 ? `rgba(255,255,255,${v})` : `rgba(0,0,0,${-v})`;
    ctx.fillRect(Math.random() * w, Math.random() * h, 1 + Math.random() * 2, 1 + Math.random() * 2);
  }
}

export const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
export const lerp = (a, b, t) => a + (b - a) * t;
export function angleDiff(a, b) {
  let d = b - a;
  while (d > Math.PI) d -= Math.PI * 2;
  while (d < -Math.PI) d += Math.PI * 2;
  return d;
}
