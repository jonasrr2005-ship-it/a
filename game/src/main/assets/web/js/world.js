import * as THREE from '../lib/three.module.min.js';
import { GeoBuilder, mat, rng, canvasTex, noise, clamp, lerp } from './geo.js';

// Stadtraster: N×N Blöcke, dazwischen 16 m breite Straßen
export const N = 9, CELL = 80, ROAD = 16, HALF = ROAD / 2, WALK = 4;
export const SIZE = N * CELL;
export const BOUNDS = { x0: -38, z0: -38, x1: SIZE + 92, z1: SIZE + 38 };
export const LANES = [2.3, 5.7];

export function blockRect(bx, bz) {
  const x0 = bx * CELL + HALF, z0 = bz * CELL + HALF;
  return { x0, z0, x1: x0 + CELL - ROAD, z1: z0 + CELL - ROAD };
}
export const nodePos = (i) => i * CELL;

// Blockarten: Hochhäuser in der Mitte, Büros, Wohnviertel am Rand, dazu Parks & Sonderorte
const SPECIAL = {
  '1,1': 'park', '7,5': 'park', '3,7': 'park', '5,2': 'parking', '2,4': 'parking',
  '1,6': 'police', '6,1': 'hospital', '4,4': 'plaza',
};
export function blockType(bx, bz) {
  const s = SPECIAL[bx + ',' + bz];
  if (s) return s;
  const d = Math.hypot(bx - (N - 1) / 2, bz - (N - 1) / 2);
  return d < 1.6 ? 'tower' : d < 3.0 ? 'office' : 'res';
}

// Feste Orte (Marker) – Positionen auf Gehwegen
export const PLACES = {
  hospital: { x: blockRect(6, 1).x0 + 24, z: blockRect(6, 1).z1 - 2, name: 'Krankenhaus' },
  police: { x: blockRect(1, 6).x1 - 2, z: blockRect(1, 6).z0 + 24, name: 'Polizeirevier' },
  shop: { x: blockRect(3, 2).x0 + 20, z: blockRect(3, 2).z1 - 2, name: 'Waffenladen', icon: 'W', color: '#ff5252' },
  spray: { x: blockRect(6, 6).x0 + 24, z: blockRect(6, 6).z0 - 1, name: 'Lackiererei', icon: 'L', color: '#7c4dff', car: true },
  race: { x: blockRect(5, 7).x0 + 2, z: blockRect(5, 7).z0 + 30, name: 'Straßenrennen', icon: 'R', color: '#ffd600', mission: 'race' },
  delivery: { x: blockRect(2, 2).x1 - 2, z: blockRect(2, 2).z0 + 30, name: 'Lieferauftrag', icon: 'D', color: '#ffd600', mission: 'delivery' },
  bounty: { x: blockRect(7, 3).x0 + 22, z: blockRect(7, 3).z1 - 2, name: 'Kopfgeld', icon: 'K', color: '#ffd600', mission: 'bounty' },
  taxi: { x: blockRect(4, 5).x0 + 2, z: blockRect(4, 5).z0 + 20, name: 'Taxi-Job', icon: 'T', color: '#ffd600', mission: 'taxi' },
};
export const PLAYER_START = { x: blockRect(3, 4).x1 + 3, z: blockRect(3, 4).z0 + 20 };

// ---------- Kollision ----------
const cells = new Map(); // Blockindex -> Liste von AABBs {x0,z0,x1,z1,h}
const key = (bx, bz) => bx * 1000 + bz;
function addCollider(x0, z0, x1, z1, h) {
  const bx = Math.floor((x0 + x1) / 2 / CELL), bz = Math.floor((z0 + z1) / 2 / CELL);
  const k = key(bx, bz);
  if (!cells.has(k)) cells.set(k, []);
  cells.get(k).push({ x0, z0, x1, z1, h });
}

// Kreis aus Gebäuden herausschieben. Gibt Normalen der stärksten Kollision zurück (oder null)
export function collideCircle(p, r, y = 0) {
  let hit = null, best = 0;
  const bx = Math.floor(p.x / CELL), bz = Math.floor(p.z / CELL);
  for (let i = bx - 1; i <= bx + 1; i++) for (let j = bz - 1; j <= bz + 1; j++) {
    const list = cells.get(key(i, j));
    if (!list) continue;
    for (const b of list) {
      if (y > b.h) continue;
      const cx = clamp(p.x, b.x0, b.x1), cz = clamp(p.z, b.z0, b.z1);
      let dx = p.x - cx, dz = p.z - cz;
      const d2 = dx * dx + dz * dz;
      if (d2 >= r * r) continue;
      let push, nx, nz;
      if (d2 > 1e-8) {
        const d = Math.sqrt(d2);
        nx = dx / d; nz = dz / d; push = r - d;
      } else {
        // Mittelpunkt steckt in der Box: zur nächsten Kante
        const l = p.x - b.x0, rr = b.x1 - p.x, t = p.z - b.z0, bb = b.z1 - p.z;
        const m = Math.min(l, rr, t, bb);
        if (m === l) { nx = -1; nz = 0; } else if (m === rr) { nx = 1; nz = 0; } else if (m === t) { nx = 0; nz = -1; } else { nx = 0; nz = 1; }
        push = m + r;
      }
      p.x += nx * push; p.z += nz * push;
      if (push > best) { best = push; hit = { nx, nz, push }; }
    }
  }
  // Weltgrenzen (Wasser)
  const B = BOUNDS;
  if (p.x < B.x0 + r) { p.x = B.x0 + r; hit = hit || { nx: 1, nz: 0, push: 0.1 }; }
  if (p.x > B.x1 - r) { p.x = B.x1 - r; hit = hit || { nx: -1, nz: 0, push: 0.1 }; }
  if (p.z < B.z0 + r) { p.z = B.z0 + r; hit = hit || { nx: 0, nz: 1, push: 0.1 }; }
  if (p.z > B.z1 - r) { p.z = B.z1 - r; hit = hit || { nx: 0, nz: -1, push: 0.1 }; }
  return hit;
}

export function solidAt(x, z, y) {
  const list = cells.get(key(Math.floor(x / CELL), Math.floor(z / CELL)));
  if (!list) return false;
  for (const b of list) if (y < b.h && x > b.x0 && x < b.x1 && z > b.z0 && z < b.z1) return true;
  return false;
}

// Sichtlinie zwischen zwei Punkten (für Schüsse, Polizei, Kamera)
export function lineBlocked(ax, ay, az, bx, by, bz, step = 1.5) {
  const dx = bx - ax, dy = by - ay, dz = bz - az;
  const len = Math.hypot(dx, dz);
  const n = Math.ceil(len / step);
  for (let i = 1; i < n; i++) {
    const t = i / n;
    if (solidAt(ax + dx * t, az + dz * t, ay + dy * t)) return t;
  }
  return 0;
}

export function groundHeight(x, z) {
  const fx = ((x % CELL) + CELL) % CELL, fz = ((z % CELL) + CELL) % CELL;
  if (x < -HALF || z < -HALF || x > SIZE + HALF || z > SIZE + HALF) return 0;
  return fx > HALF && fx < CELL - HALF && fz > HALF && fz < CELL - HALF ? 0.15 : 0;
}

export function onRoad(x, z) {
  const fx = ((x % CELL) + CELL) % CELL, fz = ((z % CELL) + CELL) % CELL;
  const inX = fx < HALF || fx > CELL - HALF, inZ = fz < HALF || fz > CELL - HALF;
  return (inX || inZ) && x > -HALF && z > -HALF && x < SIZE + HALF && z < SIZE + HALF;
}

// ---------- Texturen ----------
function facadeTextures(kind) {
  const S = 512, C = 8;
  const lit = [];
  const map = canvasTex(S, S, (g) => {
    const cw = S / C;
    if (kind === 'glass') {
      g.fillStyle = '#7d8c96'; g.fillRect(0, 0, S, S);
      for (let y = 0; y < C; y++) for (let x = 0; x < C; x++) {
        const gr = g.createLinearGradient(x * cw, y * cw, x * cw + cw, y * cw + cw);
        const l = 28 + Math.random() * 18;
        gr.addColorStop(0, `hsl(195,35%,${l + 18}%)`); gr.addColorStop(1, `hsl(205,45%,${l}%)`);
        g.fillStyle = gr; g.fillRect(x * cw + 2, y * cw + 2, cw - 4, cw - 6);
      }
    } else if (kind === 'office') {
      noise(g, S, S, '#d9d2c3', 0.08, 4000);
      for (let y = 0; y < C; y++) {
        g.fillStyle = '#b9b1a0'; g.fillRect(0, y * cw + cw - 8, S, 6);
        for (let x = 0; x < C; x++) {
          g.fillStyle = `hsl(210,25%,${20 + Math.random() * 14}%)`;
          g.fillRect(x * cw + 6, y * cw + 14, cw - 12, cw - 30);
        }
      }
    } else if (kind === 'brick') {
      noise(g, S, S, '#a0523f', 0.15, 9000);
      g.fillStyle = 'rgba(0,0,0,0.12)';
      for (let y = 0; y < S; y += 8) g.fillRect(0, y, S, 1);
      for (let y = 0; y < C; y++) for (let x = 0; x < C; x++) {
        g.fillStyle = '#e8e2d6'; g.fillRect(x * cw + 14, y * cw + 12, cw - 28, cw - 22);
        g.fillStyle = `hsl(215,20%,${16 + Math.random() * 12}%)`; g.fillRect(x * cw + 17, y * cw + 15, cw - 34, cw - 28);
      }
    } else { // pastel – wird per Vertexfarbe eingefärbt
      noise(g, S, S, '#f4f1ea', 0.06, 3000);
      for (let y = 0; y < C; y++) for (let x = 0; x < C; x++) {
        g.fillStyle = `hsl(200,25%,${22 + Math.random() * 14}%)`;
        g.fillRect(x * cw + 16, y * cw + 14, cw - 32, cw - 26);
        g.fillStyle = 'rgba(255,255,255,0.9)'; g.fillRect(x * cw + 10, y * cw + cw - 12, cw - 20, 4);
      }
    }
  }, { aniso: 8 });
  const emissive = canvasTex(S, S, (g) => {
    g.fillStyle = '#000'; g.fillRect(0, 0, S, S);
    const cw = S / C;
    for (let y = 0; y < C; y++) for (let x = 0; x < C; x++) {
      if (Math.random() < 0.38) {
        g.fillStyle = Math.random() < 0.75 ? `hsl(${38 + Math.random() * 12},90%,${55 + Math.random() * 15}%)` : '#bfe3ff';
        if (kind === 'glass') g.fillRect(x * cw + 2, y * cw + 2, cw - 4, cw - 6);
        else if (kind === 'office') g.fillRect(x * cw + 6, y * cw + 14, cw - 12, cw - 30);
        else if (kind === 'brick') g.fillRect(x * cw + 17, y * cw + 15, cw - 34, cw - 28);
        else g.fillRect(x * cw + 16, y * cw + 14, cw - 32, cw - 26);
      }
    }
  }, { aniso: 8 });
  return { map, emissive, lit };
}

function roadTexture() {
  return canvasTex(256, 256, (g, w, h) => {
    noise(g, w, h, '#3a3d42', 0.12, 9000);
    const m = w / 16; // Pixel pro Meter
    g.fillStyle = '#e8c547'; // doppelte gelbe Mittellinie
    g.fillRect(w / 2 - 0.35 * m, 0, 0.15 * m, h); g.fillRect(w / 2 + 0.2 * m, 0, 0.15 * m, h);
    g.fillStyle = '#e9e9e9';
    for (const off of [-4, 4]) for (let y = 0; y < h; y += 4 * m) g.fillRect(w / 2 + off * m - 0.08 * m, y, 0.16 * m, 2 * m);
    g.fillRect(0.4 * m, 0, 0.15 * m, h); g.fillRect(w - 0.55 * m, 0, 0.15 * m, h);
  }, { aniso: 8 });
}

function crossTexture() {
  return canvasTex(256, 256, (g, w, h) => {
    noise(g, w, h, '#3a3d42', 0.12, 9000);
    const m = w / 16;
    g.fillStyle = 'rgba(235,235,235,0.9)';
    for (let i = 1; i < 15; i += 1.2) {
      g.fillRect(i * m, 0.3 * m, 0.6 * m, 2.2 * m); g.fillRect(i * m, h - 2.5 * m, 0.6 * m, 2.2 * m);
      g.fillRect(0.3 * m, i * m, 2.2 * m, 0.6 * m); g.fillRect(w - 2.5 * m, i * m, 2.2 * m, 0.6 * m);
    }
  }, { aniso: 8 });
}

// ---------- Aufbau ----------
export class World {
  constructor(scene, quality) {
    this.scene = scene;
    this.quality = quality;
    this.nightMats = [];   // Materialien, die nachts leuchten
    this.parkingSpots = [];
    this.palms = [];
    this.time = 9.0;
    this.build();
  }

  build() {
    const R = rng(1337);
    const scene = this.scene;

    // Materialien
    const tex = {
      glass: facadeTextures('glass'), office: facadeTextures('office'),
      brick: facadeTextures('brick'), pastel: facadeTextures('pastel'),
    };
    const facadeMat = {};
    for (const k in tex) {
      facadeMat[k] = new THREE.MeshStandardMaterial({
        map: tex[k].map, emissiveMap: tex[k].emissive, emissive: 0xffffff, emissiveIntensity: 0,
        vertexColors: true, roughness: k === 'glass' ? 0.25 : 0.85, metalness: k === 'glass' ? 0.4 : 0.0,
      });
      this.nightMats.push(facadeMat[k]);
    }
    const roofMat = new THREE.MeshStandardMaterial({
      map: canvasTex(128, 128, (g, w, h) => noise(g, w, h, '#8d8d8d', 0.2, 2500)), vertexColors: true, roughness: 0.95,
    });
    const walkMat = new THREE.MeshStandardMaterial({
      map: canvasTex(256, 256, (g, w, h) => {
        noise(g, w, h, '#b9b6ae', 0.12, 6000);
        g.fillStyle = 'rgba(0,0,0,0.18)';
        for (let i = 0; i < w; i += 64) { g.fillRect(i, 0, 2, h); g.fillRect(0, i, w, 2); }
      }), roughness: 0.9, vertexColors: true,
    });
    const grassMat = new THREE.MeshStandardMaterial({
      map: canvasTex(256, 256, (g, w, h) => noise(g, w, h, '#4f8a3a', 0.25, 12000)), roughness: 1, vertexColors: true,
    });
    const roadMat = new THREE.MeshStandardMaterial({ map: roadTexture(), roughness: 0.8 });
    const crossMat = new THREE.MeshStandardMaterial({ map: crossTexture(), roughness: 0.8 });
    const sandMat = new THREE.MeshStandardMaterial({
      map: canvasTex(256, 256, (g, w, h) => noise(g, w, h, '#e3cf9a', 0.18, 9000)), roughness: 1,
    });
    const lotMat = new THREE.MeshStandardMaterial({
      map: canvasTex(256, 256, (g, w, h) => {
        noise(g, w, h, '#45484d', 0.12, 8000);
        g.fillStyle = '#ddd';
        for (let x = 0; x < w; x += 48) g.fillRect(x, 0, 3, h * 0.42);
      }), roughness: 0.85,
    });

    const walls = { glass: new GeoBuilder(), office: new GeoBuilder(), brick: new GeoBuilder(), pastel: new GeoBuilder() };
    const roofs = new GeoBuilder();
    const walk = new GeoBuilder();
    const grass = new GeoBuilder();
    const lots = new GeoBuilder();
    const roads = new GeoBuilder();
    const cross = new GeoBuilder();
    const lamps = [];

    const building = (x0, z0, x1, z1, h, kind, tint, y0 = 0.15) => {
      walls[kind].setColor(tint || 0xffffff);
      walls[kind].box(x0, y0, z0, x1, h, z1, { su: 24, sv: 28, uOff: Math.floor(R() * 8) * 3, faces: 'nsew' });
      roofs.setColor(0xffffff);
      roofs.box(x0, h, z0, x1, h + 0.01, z1, { faces: 't', topScale: 8 });
      // Dachrand + Technik auf dem Dach
      roofs.setColor(0xbbbbbb);
      const p = 0.4;
      roofs.box(x0, h, z0, x1, h + 0.8, z0 + p, { faces: 'nsewt' });
      roofs.box(x0, h, z1 - p, x1, h + 0.8, z1, { faces: 'nsewt' });
      roofs.box(x0, h, z0, x0 + p, h + 0.8, z1, { faces: 'nsewt' });
      roofs.box(x1 - p, h, z0, x1, h + 0.8, z1, { faces: 'nsewt' });
      if (x1 - x0 > 10 && z1 - z0 > 10) {
        const n = 1 + Math.floor(R() * 3);
        for (let i = 0; i < n; i++) {
          const w = 2 + R() * 4, d = 2 + R() * 4;
          const cx = lerp(x0 + 2, x1 - 2 - w, R()), cz = lerp(z0 + 2, z1 - 2 - d, R());
          roofs.setColor(0x9a9a9a);
          roofs.box(cx, h, cz, cx + w, h + 1 + R() * 2, cz + d);
        }
      }
      addCollider(x0, z0, x1, z1, h);
    };

    const pastel = () => new THREE.Color().setHSL(R(), 0.45 + R() * 0.3, 0.72 + R() * 0.12);

    for (let bx = 0; bx < N; bx++) for (let bz = 0; bz < N; bz++) {
      const r = blockRect(bx, bz);
      const type = blockType(bx, bz);
      // Gehweg-Platte
      walk.setColor(0xffffff);
      walk.box(r.x0, 0, r.z0, r.x1, 0.15, r.z1, { faces: 'nsewt', topScale: 4 });
      // Laternen am Gehwegrand
      for (const [lx, lz, ry] of [
        [r.x0 + 1, r.z0 + 1, 0], [r.x1 - 1, r.z0 + 1, 0], [r.x0 + 1, r.z1 - 1, 0], [r.x1 - 1, r.z1 - 1, 0],
        [(r.x0 + r.x1) / 2, r.z0 + 0.8, 0], [(r.x0 + r.x1) / 2, r.z1 - 0.8, Math.PI],
        [r.x0 + 0.8, (r.z0 + r.z1) / 2, -Math.PI / 2], [r.x1 - 0.8, (r.z0 + r.z1) / 2, Math.PI / 2],
      ]) lamps.push([lx, lz, ry]);

      const ix0 = r.x0 + WALK + 1, iz0 = r.z0 + WALK + 1, ix1 = r.x1 - WALK - 1, iz1 = r.z1 - WALK - 1;
      if (type === 'tower') {
        const split = R() < 0.5;
        const parts = split ? [[ix0, iz0, (ix0 + ix1) / 2 - 2, iz1], [(ix0 + ix1) / 2 + 2, iz0, ix1, iz1]] : [[ix0, iz0, ix1, iz1]];
        for (const [a, b, c, d] of parts) {
          building(a, b, c, d, 9, 'office', 0xffffff);
          const h = 55 + R() * 95;
          const ins = 3 + R() * 3;
          building(a + ins, b + ins, c - ins, d - ins, h, R() < 0.65 ? 'glass' : 'office', R() < 0.5 ? 0xffffff : new THREE.Color().setHSL(0.55 + R() * 0.1, 0.3, 0.8), 9);
          if (h > 120) { // Spitze
            roofs.setColor(0x777777);
            const cx = (a + c) / 2, cz = (b + d) / 2;
            roofs.box(cx - 0.3, h, cz - 0.3, cx + 0.3, h + 18, cz + 0.3);
          }
        }
      } else if (type === 'office') {
        const mx = (ix0 + ix1) / 2, mz = (iz0 + iz1) / 2;
        for (const [a, b, c, d] of [[ix0, iz0, mx - 1.5, mz - 1.5], [mx + 1.5, iz0, ix1, mz - 1.5], [ix0, mz + 1.5, mx - 1.5, iz1], [mx + 1.5, mz + 1.5, ix1, iz1]]) {
          const kinds = ['office', 'brick', 'glass', 'pastel'];
          const k = kinds[Math.floor(R() * kinds.length)];
          building(a, b, c, d, 14 + R() * 34, k, k === 'pastel' ? pastel() : 0xffffff);
        }
      } else if (type === 'res') {
        const n = 3, w = (ix1 - ix0) / n;
        grass.setColor(0xffffff);
        grass.box(ix0 - 1, 0.15, iz0 - 1, ix1 + 1, 0.2, iz1 + 1, { faces: 't', topScale: 8 });
        for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
          if (i === 1 && j === 1) { this.palms.push([ix0 + w * 1.5, iz0 + w * 1.5]); continue; }
          if (R() < 0.12) { this.palms.push([ix0 + w * (i + 0.5), iz0 + w * (j + 0.5)]); continue; }
          const m = 2 + R() * 1.5;
          building(ix0 + i * w + m, iz0 + j * w + m, ix0 + (i + 1) * w - m, iz0 + (j + 1) * w - m, 6 + Math.floor(R() * 3) * 3.2, R() < 0.8 ? 'pastel' : 'brick', pastel(), 0.2);
        }
        if (R() < 0.6) this.palms.push([ix0 + 2, iz0 + 2]);
      } else if (type === 'park' || type === 'plaza') {
        grass.setColor(type === 'plaza' ? 0xc8c2b0 : 0xffffff);
        if (type === 'plaza') {
          walk.setColor(0xe6dcc4);
          walk.box(ix0 - 1, 0.15, iz0 - 1, ix1 + 1, 0.2, iz1 + 1, { faces: 't', topScale: 4 });
          // Brunnen in der Mitte
          const cx = (ix0 + ix1) / 2, cz = (iz0 + iz1) / 2;
          walk.setColor(0xcfc6b0);
          walk.add(new THREE.CylinderGeometry(7, 7.4, 0.8, 24), mat(cx, 0.6, cz));
          walk.setColor(0x4fa3d1);
          walk.add(new THREE.CylinderGeometry(6.4, 6.4, 0.1, 24), mat(cx, 0.95, cz));
          walk.setColor(0xcfc6b0);
          walk.add(new THREE.CylinderGeometry(0.8, 1.2, 3.5, 12), mat(cx, 2.2, cz));
          addCollider(cx - 6.5, cz - 6.5, cx + 6.5, cz + 6.5, 1);
          for (const [a, b] of [[ix0 + 3, iz0 + 3], [ix1 - 3, iz0 + 3], [ix0 + 3, iz1 - 3], [ix1 - 3, iz1 - 3]]) this.palms.push([a, b]);
        } else {
          grass.box(ix0 - 1, 0.15, iz0 - 1, ix1 + 1, 0.2, iz1 + 1, { faces: 't', topScale: 8 });
          for (let i = 0; i < 12; i++) this.palms.push([lerp(ix0 + 2, ix1 - 2, R()), lerp(iz0 + 2, iz1 - 2, R())]);
          // Wege
          walk.setColor(0xd8cfb8);
          walk.box((ix0 + ix1) / 2 - 1.5, 0.15, iz0 - 1, (ix0 + ix1) / 2 + 1.5, 0.22, iz1 + 1, { faces: 't', topScale: 4 });
          walk.box(ix0 - 1, 0.15, (iz0 + iz1) / 2 - 1.5, ix1 + 1, 0.22, (iz0 + iz1) / 2 + 1.5, { faces: 't', topScale: 4 });
        }
      } else if (type === 'parking') {
        lots.box(ix0 - 1, 0.15, iz0 - 1, ix1 + 1, 0.17, iz1 + 1, { faces: 't', topScale: 16 });
        for (let row = 0; row < 2; row++) for (let i = 0; i < 9; i++) {
          if (R() < 0.35) continue;
          this.parkingSpots.push({ x: ix0 + 4 + i * 5.3, z: row === 0 ? iz0 + 5 : iz1 - 5, h: row === 0 ? 0 : Math.PI });
        }
      } else if (type === 'police') {
        building(ix0, iz0, ix1 - 14, iz1, 14, 'brick', 0x9fb4ff);
        lots.box(ix1 - 13, 0.15, iz0, ix1 + 1, 0.17, iz1, { faces: 't', topScale: 16 });
        this.policeLot = { x: ix1 - 6, z: (iz0 + iz1) / 2 };
      } else if (type === 'hospital') {
        building(ix0, iz0, ix1, iz1 - 10, 26, 'pastel', 0xffffff);
        roofs.setColor(0xd32f2f);
        const cx = (ix0 + ix1) / 2, cz = (iz0 + iz1 - 10) / 2;
        roofs.box(cx - 6, 26.05, cz - 1.8, cx + 6, 26.15, cz + 1.8);
        roofs.box(cx - 1.8, 26.05, cz - 6, cx + 1.8, 26.15, cz + 6);
      }
    }

    // Straßen: Abschnitte zwischen den Kreuzungen + Kreuzungen
    for (let i = 0; i <= N; i++) for (let j = 0; j <= N; j++) {
      const x = nodePos(i), z = nodePos(j);
      cross.flat(x - HALF, z - HALF, x + HALF, z + HALF, 0.02);
      const L = CELL - ROAD, rep = L / 16;
      if (i < N) roads.flat(x + HALF, z - HALF, x + HALF + L, z + HALF, 0.02, [0, 0, 0, rep, 1, rep, 1, 0]);
      if (j < N) roads.flat(x - HALF, z + HALF, x + HALF, z + HALF + L, 0.02, [0, 0, 1, 0, 1, rep, 0, rep]);
    }

    const add = (b, m, shadow = true) => {
      if (b.empty) return null;
      const mesh = new THREE.Mesh(b.build(), m);
      mesh.receiveShadow = true;
      mesh.castShadow = shadow;
      mesh.matrixAutoUpdate = false;
      scene.add(mesh);
      return mesh;
    };
    for (const k in walls) add(walls[k], facadeMat[k]);
    add(roofs, roofMat);
    add(walk, walkMat, false);
    add(grass, grassMat, false);
    add(lots, lotMat, false);
    add(roads, roadMat, false);
    add(cross, crossMat, false);

    // Sand/Strand rund um die Insel, breiter Strand im Osten
    const sand = new THREE.Mesh(new THREE.PlaneGeometry(BOUNDS.x1 - BOUNDS.x0 + 30, BOUNDS.z1 - BOUNDS.z0 + 30), sandMat);
    sand.geometry.attributes.uv.array.forEach((v, i, a) => { a[i] = v * 60; });
    sand.rotation.x = -Math.PI / 2;
    sand.position.set((BOUNDS.x0 + BOUNDS.x1) / 2, -0.08, (BOUNDS.z0 + BOUNDS.z1) / 2);
    sand.receiveShadow = true;
    scene.add(sand);

    // Meer
    const waterTex = canvasTex(256, 256, (g, w, h) => {
      g.fillStyle = '#1b6f93'; g.fillRect(0, 0, w, h);
      for (let i = 0; i < 260; i++) {
        g.strokeStyle = `rgba(255,255,255,${0.05 + Math.random() * 0.12})`;
        g.beginPath();
        const x = Math.random() * w, y = Math.random() * h;
        g.moveTo(x, y); g.quadraticCurveTo(x + 8, y - 3, x + 16 + Math.random() * 10, y);
        g.stroke();
      }
    });
    waterTex.repeat.set(120, 120);
    this.water = new THREE.Mesh(new THREE.PlaneGeometry(6000, 6000),
      new THREE.MeshStandardMaterial({ map: waterTex, color: 0x9fd8ee, roughness: 0.12, metalness: 0.3 }));
    this.water.rotation.x = -Math.PI / 2;
    this.water.position.set(SIZE / 2, -0.45, SIZE / 2);
    scene.add(this.water);
    this.waterTex = waterTex;

    // Palmen am Strand
    for (let z = BOUNDS.z0 + 10; z < BOUNDS.z1 - 10; z += 16 + R() * 10) this.palms.push([SIZE + 30 + R() * 30, z]);
    for (let x = 10; x < SIZE; x += 30 + R() * 20) { this.palms.push([x, -24 - R() * 8]); this.palms.push([x, SIZE + 24 + R() * 8]); }

    this.buildPalms(R);
    this.buildLamps(lamps);
    this.buildFerrisWheel();
    this.buildSky();
    this.buildLights();
  }

  buildPalms(R) {
    const trunk = new GeoBuilder();
    const H = 7;
    for (let i = 0; i < 6; i++) {
      trunk.setColor(i % 2 ? 0x8b6a45 : 0x7a5a38);
      trunk.add(new THREE.CylinderGeometry(0.22 - i * 0.015, 0.25 - i * 0.015, H / 6, 7), mat(Math.sin(i * 0.35) * 0.3 * i / 6, H / 12 + i * H / 6, 0));
    }
    const leaf = new GeoBuilder();
    for (let k = 0; k < 8; k++) {
      const a = (k / 8) * Math.PI * 2;
      for (let s = 0; s < 3; s++) {
        leaf.setColor(s === 0 ? 0x3f7d2c : 0x4e9a35);
        const len = 1.4;
        const g = new THREE.PlaneGeometry(len, 0.7 - s * 0.15);
        const r = 0.6 + s * len * 0.9;
        const m = new THREE.Matrix4().makeRotationY(-a)
          .multiply(new THREE.Matrix4().makeTranslation(r, -s * s * 0.35, 0))
          .multiply(new THREE.Matrix4().makeRotationZ(-0.25 - s * 0.45))
          .multiply(new THREE.Matrix4().makeRotationX(Math.PI / 2));
        leaf.add(g, m);
      }
    }
    const tg = trunk.build(), lg = leaf.build();
    const count = this.palms.length;
    const tm = new THREE.InstancedMesh(tg, new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 1 }), count);
    const lm = new THREE.InstancedMesh(lg, new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.9, side: THREE.DoubleSide }), count);
    const m = new THREE.Matrix4(), top = new THREE.Matrix4();
    this.palms.forEach(([x, z], i) => {
      const s = 0.8 + R() * 0.5, ry = R() * Math.PI * 2;
      const y = groundHeight(x, z) + 0.05;
      m.compose(new THREE.Vector3(x, y, z), new THREE.Quaternion().setFromEuler(new THREE.Euler(0, ry, 0)), new THREE.Vector3(s, s, s));
      tm.setMatrixAt(i, m);
      top.copy(m).multiply(new THREE.Matrix4().makeTranslation(Math.sin(5 * 0.35) * 0.3 * 5 / 6, H, 0));
      lm.setMatrixAt(i, top);
      addCollider(x - 0.3, z - 0.3, x + 0.3, z + 0.3, 8);
    });
    tm.castShadow = lm.castShadow = true;
    this.scene.add(tm, lm);
  }

  buildLamps(lamps) {
    const pole = new GeoBuilder();
    pole.setColor(0x3d4148);
    pole.add(new THREE.CylinderGeometry(0.08, 0.12, 6.5, 6), mat(0, 3.25, 0));
    pole.add(new THREE.BoxGeometry(0.08, 0.08, 1.6), mat(0, 6.45, -0.8));
    const head = new THREE.BoxGeometry(0.35, 0.12, 0.6);
    head.translate(0, 6.38, -1.5);
    const pm = new THREE.InstancedMesh(pole.build(), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.6, metalness: 0.5 }), lamps.length);
    this.lampMat = new THREE.MeshStandardMaterial({ color: 0x444444, emissive: 0xffd9a0, emissiveIntensity: 0 });
    const hm = new THREE.InstancedMesh(head, this.lampMat, lamps.length);
    // Lichtkegel auf dem Boden (günstiger als echte Lichtquellen)
    const pool = new THREE.CircleGeometry(4.2, 20);
    pool.rotateX(-Math.PI / 2);
    pool.translate(0, 0.2, -1.8);
    const poolTex = canvasTex(64, 64, (g) => {
      const gr = g.createRadialGradient(32, 32, 0, 32, 32, 32);
      gr.addColorStop(0, 'rgba(255,220,160,0.55)'); gr.addColorStop(1, 'rgba(255,220,160,0)');
      g.fillStyle = gr; g.fillRect(0, 0, 64, 64);
    });
    this.poolMat = new THREE.MeshBasicMaterial({ map: poolTex, transparent: true, depthWrite: false, opacity: 0, blending: THREE.AdditiveBlending });
    const pl = new THREE.InstancedMesh(pool, this.poolMat, lamps.length);
    const m = new THREE.Matrix4();
    lamps.forEach(([x, z, ry], i) => {
      // Arm zeigt Richtung Straße
      const bx = Math.floor(x / CELL), bz = Math.floor(z / CELL);
      const r = blockRect(bx, bz);
      const cx = (r.x0 + r.x1) / 2, cz = (r.z0 + r.z1) / 2;
      const dx = x - cx, dz = z - cz;
      const ang = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? -Math.PI / 2 : Math.PI / 2) : (dz > 0 ? Math.PI : 0);
      m.makeRotationY(ang).setPosition(x, 0.15, z);
      pm.setMatrixAt(i, m); hm.setMatrixAt(i, m); pl.setMatrixAt(i, m);
    });
    pm.castShadow = true;
    this.scene.add(pm, hm, pl);
  }

  buildFerrisWheel() {
    const g = new THREE.Group();
    const steel = new THREE.MeshStandardMaterial({ color: 0xeeeeee, metalness: 0.6, roughness: 0.4 });
    const R = 22;
    const ring = new THREE.Mesh(new THREE.TorusGeometry(R, 0.35, 6, 48), steel);
    const ring2 = ring.clone(); ring2.position.z = 2; ring.position.z = -2;
    const wheel = new THREE.Group();
    wheel.add(ring, ring2);
    const b = new GeoBuilder();
    b.setColor(0xeeeeee);
    for (let i = 0; i < 16; i++) {
      const a = i / 16 * Math.PI * 2;
      b.add(new THREE.BoxGeometry(0.2, R, 0.2), mat(Math.cos(a) * R / 2, Math.sin(a) * R / 2, -2, 0, 0, a - Math.PI / 2));
      b.add(new THREE.BoxGeometry(0.2, R, 0.2), mat(Math.cos(a) * R / 2, Math.sin(a) * R / 2, 2, 0, 0, a - Math.PI / 2));
    }
    wheel.add(new THREE.Mesh(b.build(), new THREE.MeshStandardMaterial({ vertexColors: true, metalness: 0.6, roughness: 0.4 })));
    this.cabins = [];
    const colors = [0xff4081, 0x40c4ff, 0xffd740, 0x69f0ae];
    for (let i = 0; i < 16; i++) {
      const c = new THREE.Mesh(new THREE.BoxGeometry(2.2, 2.2, 3), new THREE.MeshStandardMaterial({ color: colors[i % 4], emissive: colors[i % 4], emissiveIntensity: 0 }));
      this.nightMats.push(c.material);
      wheel.add(c);
      this.cabins.push(c);
    }
    this.wheelLights = new THREE.Mesh(new THREE.TorusGeometry(R + 0.4, 0.15, 4, 64),
      new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xff66cc, emissiveIntensity: 0 }));
    this.nightMats.push(this.wheelLights.material);
    wheel.add(this.wheelLights);
    wheel.position.y = R + 4;
    g.add(wheel);
    const legs = new GeoBuilder();
    legs.setColor(0xdddddd);
    for (const s of [-1, 1]) for (const z of [-3, 3]) legs.add(new THREE.BoxGeometry(0.6, R + 8, 0.6), mat(s * 7, (R + 4) / 2, z, 0, 0, s * 0.3));
    g.add(new THREE.Mesh(legs.build(), new THREE.MeshStandardMaterial({ vertexColors: true, metalness: 0.5, roughness: 0.5 })));
    g.position.set(SIZE + 52, 0, SIZE / 2);
    g.rotation.y = Math.PI / 2;
    g.traverse((o) => { o.castShadow = true; });
    this.scene.add(g);
    this.wheel = wheel;
    addCollider(SIZE + 48, SIZE / 2 - 9, SIZE + 56, SIZE / 2 + 9, 30);
  }

  buildSky() {
    this.skyUniforms = {
      top: { value: new THREE.Color(0x2a6fd6) },
      horizon: { value: new THREE.Color(0xbfe3ff) },
      sunDir: { value: new THREE.Vector3(0, 1, 0) },
      sunColor: { value: new THREE.Color(0xfff2c0) },
      night: { value: 0 },
    };
    const sky = new THREE.Mesh(new THREE.SphereGeometry(1400, 32, 16), new THREE.ShaderMaterial({
      side: THREE.BackSide, depthWrite: false, fog: false, uniforms: this.skyUniforms,
      vertexShader: `varying vec3 vDir; void main(){ vDir = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }`,
      fragmentShader: `uniform vec3 top; uniform vec3 horizon; uniform vec3 sunDir; uniform vec3 sunColor; uniform float night; varying vec3 vDir;
        float h(vec2 p){ return fract(sin(dot(p, vec2(12.9898,78.233))) * 43758.5453); }
        void main(){
          float y = max(vDir.y, 0.0);
          vec3 c = mix(horizon, top, pow(y, 0.55));
          float s = max(dot(vDir, normalize(sunDir)), 0.0);
          c += sunColor * (pow(s, 900.0) * 3.0 + pow(s, 12.0) * 0.35);
          vec2 g = floor(vDir.xz / (vDir.y + 0.3) * 260.0);
          float st = step(0.9975, h(g)) * night * smoothstep(0.05, 0.3, vDir.y);
          c += vec3(st);
          gl_FragColor = vec4(c, 1.0);
        }`,
    }));
    sky.renderOrder = -1;
    sky.frustumCulled = false;
    this.sky = sky;
    this.scene.add(sky);
    this.scene.fog = new THREE.Fog(0xbfe3ff, 120, 700);
  }

  buildLights() {
    this.hemi = new THREE.HemisphereLight(0xcfe8ff, 0x8a7a5a, 1.0);
    this.sun = new THREE.DirectionalLight(0xffffff, 2.5);
    this.sun.castShadow = this.quality > 0;
    const s = this.quality > 1 ? 2048 : 1024;
    this.sun.shadow.mapSize.set(s, s);
    const c = this.sun.shadow.camera;
    c.left = c.bottom = -70; c.right = c.top = 70; c.near = 1; c.far = 500;
    this.sun.shadow.bias = -0.0004;
    this.sun.shadow.normalBias = 0.04;
    this.scene.add(this.hemi, this.sun, this.sun.target);
  }

  // Tageszeit in Stunden (0..24)
  update(dt, focus, camPos) {
    this.time = (this.time + dt / 30) % 24; // 1 Spielstunde = 30 s
    const t = this.time;
    const ang = ((t - 6) / 24) * Math.PI * 2; // 6 Uhr Aufgang, 18 Uhr Untergang
    const sunY = Math.sin(ang);
    const sunDir = new THREE.Vector3(Math.cos(ang) * 0.8, sunY, 0.45).normalize();
    const day = clamp((sunY + 0.12) / 0.4, 0, 1);       // 0 Nacht .. 1 Tag
    const dusk = clamp(1 - Math.abs(sunY) / 0.3, 0, 1);  // Dämmerung

    const top = new THREE.Color(0x0a1230).lerp(new THREE.Color(0x2f74d8), day);
    const hor = new THREE.Color(0x1a2240).lerp(new THREE.Color(0xc4e4ff), day).lerp(new THREE.Color(0xff9a5a), dusk * 0.7);
    this.skyUniforms.top.value.copy(top);
    this.skyUniforms.horizon.value.copy(hor);
    this.skyUniforms.sunDir.value.copy(sunY > -0.1 ? sunDir : sunDir.clone().negate());
    this.skyUniforms.sunColor.value.set(sunY > -0.1 ? 0xfff0c8 : 0x8899bb).multiplyScalar(sunY > -0.1 ? 1 : 0.25);
    this.skyUniforms.night.value = 1 - day;
    this.scene.fog.color.copy(hor);

    const lightDir = sunY > -0.05 ? sunDir : new THREE.Vector3(-sunDir.x, -sunDir.y, sunDir.z); // nachts Mond
    this.sun.intensity = sunY > -0.05 ? 0.3 + 2.4 * day : 0.7;
    this.sun.color.set(0xffffff).lerp(new THREE.Color(0xffa060), dusk * 0.8);
    if (sunY <= -0.05) this.sun.color.set(0x8fa6d8);
    this.hemi.intensity = 0.55 + 0.55 * day;
    this.hemi.color.set(0x7080b8).lerp(new THREE.Color(0xcfe8ff), day);

    this.sun.position.set(focus.x + lightDir.x * 200, lightDir.y * 200, focus.z + lightDir.z * 200);
    this.sun.target.position.set(focus.x, 0, focus.z);

    const night = 1 - day;
    for (const m of this.nightMats) m.emissiveIntensity = night * 1.3;
    this.lampMat.emissiveIntensity = night > 0.3 ? 2.5 : 0;
    this.poolMat.opacity = clamp((night - 0.3) * 1.5, 0, 1);

    this.sky.position.copy(camPos);
    this.waterTex.offset.x += dt * 0.004;
    this.waterTex.offset.y += dt * 0.002;
    this.wheel.rotation.z += dt * 0.05;
    for (const c of this.cabins) {
      const i = this.cabins.indexOf(c), a = i / 16 * Math.PI * 2;
      c.position.set(Math.cos(a) * 22, Math.sin(a) * 22 - 1.5, 0);
      c.rotation.z = -this.wheel.rotation.z;
    }
    this.night = night;
  }

  // Zufälliger Punkt auf einem Gehweg (für Fußgänger)
  randomSidewalk(R = Math.random) {
    const bx = Math.floor(R() * N), bz = Math.floor(R() * N);
    return this.sidewalkPoint(bx, bz, R() * 4, R() < 0.5 ? 1 : -1);
  }

  // Punkt auf dem Gehweg-Rundweg eines Blocks; s = 0..4 (Seite + Anteil)
  sidewalkPoint(bx, bz, s, dir) {
    const r = blockRect(bx, bz);
    const ins = 2;
    const x0 = r.x0 + ins, x1 = r.x1 - ins, z0 = r.z0 + ins, z1 = r.z1 - ins;
    s = ((s % 4) + 4) % 4;
    const side = Math.floor(s), f = s - side;
    let x, z;
    if (side === 0) { x = lerp(x0, x1, f); z = z0; }
    else if (side === 1) { x = x1; z = lerp(z0, z1, f); }
    else if (side === 2) { x = lerp(x1, x0, f); z = z1; }
    else { x = x0; z = lerp(z1, z0, f); }
    return { x, z, bx, bz, s, dir };
  }
}
