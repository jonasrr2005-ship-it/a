import * as THREE from '../lib/three.module.min.js';
import { GeoBuilder, mat, clamp, lerp, angleDiff } from './geo.js';
import { collideCircle, groundHeight } from './world.js';

const charMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.8 });
const carMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.35, metalness: 0.45 });
const lightMat = new THREE.MeshBasicMaterial({ vertexColors: true });

const SKIN = [0xf1c27d, 0xe0ac69, 0xc68642, 0x8d5524, 0xffdbac, 0x5c3a21];
const HAIR = [0x1b1b1b, 0x3b2314, 0x6a4e23, 0xd9b26f, 0x8c8c8c, 0xa0522d];
const SHIRT = [0xe53935, 0x1e88e5, 0x43a047, 0xfdd835, 0xffffff, 0x212121, 0x8e24aa, 0xff7043, 0x26c6da, 0xec407a];
const PANTS = [0x263238, 0x1565c0, 0x5d4037, 0x9e9e9e, 0x37474f, 0xd7ccc8];
const pick = (a) => a[Math.floor(Math.random() * a.length)];

const limbGeoCache = new Map();
function limbGeo(w, h, d, color, color2, split = 0.5) {
  const k = [w, h, d, color, color2, split].join();
  if (limbGeoCache.has(k)) return limbGeoCache.get(k);
  const b = new GeoBuilder();
  b.setColor(color2 ?? color);
  b.box(-w / 2, -h, -d / 2, w / 2, -h * split, d / 2);
  b.setColor(color);
  b.box(-w / 2, -h * split, -d / 2, w / 2, 0, d / 2);
  const g = b.build();
  limbGeoCache.set(k, g);
  return g;
}

// Menschliche Figur aus Boxen mit animierten Armen/Beinen
export class Character {
  constructor(scene, look = {}) {
    this.skin = look.skin ?? pick(SKIN);
    this.hair = look.hair ?? pick(HAIR);
    this.shirt = look.shirt ?? pick(SHIRT);
    this.pants = look.pants ?? pick(PANTS);
    this.cap = look.cap;
    this.group = new THREE.Group();
    const body = new GeoBuilder();
    body.setColor(this.shirt);
    body.box(-0.25, 0.9, -0.14, 0.25, 1.52, 0.14);
    body.setColor(this.pants);
    body.box(-0.24, 0.86, -0.13, 0.24, 0.98, 0.13);
    body.setColor(this.skin);
    body.box(-0.07, 1.52, -0.07, 0.07, 1.58, 0.07);
    body.box(-0.13, 1.58, -0.13, 0.13, 1.84, 0.13);
    body.setColor(0x111111);
    body.box(-0.08, 1.7, 0.13, -0.03, 1.74, 0.135);
    body.box(0.03, 1.7, 0.13, 0.08, 1.74, 0.135);
    body.setColor(this.hair);
    body.box(-0.14, 1.78, -0.14, 0.14, 1.88, 0.14);
    body.box(-0.14, 1.62, -0.14, 0.14, 1.8, -0.1);
    if (this.cap) {
      body.setColor(this.cap);
      body.box(-0.15, 1.82, -0.15, 0.15, 1.92, 0.15);
      body.box(-0.13, 1.82, 0.12, 0.13, 1.85, 0.28);
    }
    if (look.vest) {
      body.setColor(look.vest);
      body.box(-0.26, 1.0, -0.15, 0.26, 1.45, 0.15);
    }
    this.body = new THREE.Mesh(body.build(), charMat);
    this.group.add(this.body);
    const limb = (geo, x, y) => {
      const m = new THREE.Mesh(geo, charMat);
      m.position.set(x, y, 0);
      this.group.add(m);
      return m;
    };
    const arm = limbGeo(0.13, 0.66, 0.14, this.shirt, this.skin, 0.55);
    const leg = limbGeo(0.17, 0.9, 0.2, this.pants, 0x2b2b2b, 0.9);
    this.armL = limb(arm, -0.32, 1.5);
    this.armR = limb(arm, 0.32, 1.5);
    this.legL = limb(leg, -0.12, 0.9);
    this.legR = limb(leg, 0.12, 0.9);
    // Waffe in der rechten Hand
    this.gun = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.12, 0.3), new THREE.MeshStandardMaterial({ color: 0x222222, metalness: 0.7, roughness: 0.4 }));
    this.gun.position.set(0, -0.62, 0.12);
    this.gun.visible = false;
    this.armR.add(this.gun);
    this.group.traverse((o) => { o.castShadow = true; });
    scene.add(this.group);
    this.scene = scene;

    this.pos = new THREE.Vector3();
    this.vel = new THREE.Vector3();
    this.heading = 0;
    this.radius = 0.35;
    this.health = 100;
    this.dead = false;
    this.phase = 0;
    this.speed = 0;
    this.onGround = true;
    this.vehicle = null;
    this.aiming = false;
    this.punch = 0;
    this.deadTime = 0;
  }

  setPos(x, z) { this.pos.set(x, groundHeight(x, z), z); }

  // Bewegung mit gewünschter Richtung (Weltkoordinaten) und Geschwindigkeit
  move(dt, dx, dz, speed, jump = false) {
    if (this.dead || this.vehicle) return;
    const len = Math.hypot(dx, dz);
    const target = len > 0.01 ? speed : 0;
    if (len > 0.01) {
      dx /= len; dz /= len;
      if (!this.aiming) this.heading += angleDiff(this.heading, Math.atan2(dx, dz)) * Math.min(1, dt * 12);
    }
    const accel = this.onGround ? 14 : 3;
    this.vel.x = lerp(this.vel.x, dx * target * (len > 0.01 ? 1 : 0), Math.min(1, dt * accel));
    this.vel.z = lerp(this.vel.z, dz * target * (len > 0.01 ? 1 : 0), Math.min(1, dt * accel));
    if (jump && this.onGround) { this.vel.y = 5.2; this.onGround = false; }
    this.integrate(dt);
  }

  integrate(dt) {
    this.vel.y -= 16 * dt;
    this.pos.x += this.vel.x * dt;
    this.pos.z += this.vel.z * dt;
    this.pos.y += this.vel.y * dt;
    collideCircle(this.pos, this.radius, this.pos.y + 0.5);
    const gh = groundHeight(this.pos.x, this.pos.z);
    if (this.pos.y <= gh) { this.pos.y = gh; this.vel.y = 0; this.onGround = true; }
    this.speed = Math.hypot(this.vel.x, this.vel.z);
  }

  knock(dx, dz, force) {
    this.vel.x += dx * force; this.vel.z += dz * force; this.vel.y += force * 0.4;
    this.onGround = false;
  }

  damage(n) {
    if (this.dead) return false;
    if (this.armor > 0) {
      const a = Math.min(this.armor, n * 0.7);
      this.armor -= a; n -= a;
    }
    this.health -= n;
    if (this.health <= 0) { this.health = 0; this.die(); return true; }
    return false;
  }

  die() {
    this.dead = true;
    this.deadTime = 0;
  }

  animate(dt) {
    const g = this.group;
    if (this.vehicle) { g.visible = false; return; }
    g.visible = true;
    g.position.copy(this.pos);
    g.rotation.y = this.heading;
    if (this.dead) {
      this.deadTime += dt;
      if (!this.onGround) this.integrate(dt);
      else { this.vel.x *= 0.9; this.vel.z *= 0.9; this.integrate(dt); }
      g.rotation.x = lerp(g.rotation.x, -Math.PI / 2, Math.min(1, dt * 6));
      g.position.y = this.pos.y + 0.15;
      return;
    }
    g.rotation.x = 0;
    const s = this.speed;
    this.phase += dt * (2 + s * 2.2);
    const amp = clamp(s / 5, 0, 1) * 0.9;
    const sw = Math.sin(this.phase) * amp;
    this.legL.rotation.x = sw;
    this.legR.rotation.x = -sw;
    if (!this.onGround) { this.legL.rotation.x = -0.5; this.legR.rotation.x = 0.3; }
    this.armL.rotation.x = -sw * 0.8;
    this.armR.rotation.x = sw * 0.8;
    this.armL.rotation.z = 0; this.armR.rotation.z = 0;
    if (this.aiming && this.gun.visible) { this.armR.rotation.x = -Math.PI / 2; this.armL.rotation.x = -1.3; this.armL.rotation.z = -0.5; }
    if (this.punch > 0) {
      this.punch -= dt;
      this.armR.rotation.x = -Math.PI / 2 * Math.sin(clamp(this.punch / 0.3, 0, 1) * Math.PI);
    }
    if (this.hands) { this.armL.rotation.x = this.armR.rotation.x = -Math.PI * 0.95; }
  }

  remove() {
    this.scene.remove(this.group);
  }
}

// ---------- Fahrzeuge ----------
export const CAR_TYPES = {
  compact: { name: 'Kompakt', len: 3.8, wid: 1.75, hei: 1.45, max: 36, acc: 10, turn: 2.4, mass: 1, hp: 80, colors: [0xff7043, 0x42a5f5, 0x9ccc65, 0xffee58, 0xeceff1] },
  sedan: { name: 'Limousine', len: 4.7, wid: 1.9, hei: 1.45, max: 42, acc: 11, turn: 2.1, mass: 1.3, hp: 100, colors: [0x263238, 0xb0bec5, 0x8d1c1c, 0x1a237e, 0xf5f5f5, 0x004d40] },
  sport: { name: 'Sportwagen', len: 4.5, wid: 2.0, hei: 1.15, max: 62, acc: 17, turn: 2.5, mass: 1.1, hp: 90, colors: [0xd50000, 0xffd600, 0x00e5ff, 0xff4081, 0x111111, 0xff6d00] },
  pickup: { name: 'Pick-up', len: 5.2, wid: 2.05, hei: 1.85, max: 38, acc: 10, turn: 1.9, mass: 1.6, hp: 130, colors: [0x6d4c41, 0x37474f, 0xc62828, 0xe0e0e0, 0x33691e] },
  van: { name: 'Lieferwagen', len: 5.4, wid: 2.1, hei: 2.4, max: 32, acc: 8, turn: 1.8, mass: 2, hp: 140, colors: [0xffffff, 0xfbc02d, 0x90a4ae] },
  taxi: { name: 'Taxi', len: 4.7, wid: 1.9, hei: 1.45, max: 42, acc: 11, turn: 2.1, mass: 1.3, hp: 100, colors: [0xffc107] },
  police: { name: 'Streifenwagen', len: 4.9, wid: 1.95, hei: 1.5, max: 52, acc: 15, turn: 2.3, mass: 1.5, hp: 150, colors: [0xffffff] },
  bus: { name: 'Bus', len: 10, wid: 2.5, hei: 3.1, max: 26, acc: 6, turn: 1.3, mass: 4, hp: 220, colors: [0x1e88e5, 0xff7043] },
};
export const TRAFFIC_TYPES = ['compact', 'compact', 'sedan', 'sedan', 'sedan', 'sport', 'pickup', 'van', 'taxi', 'bus'];

function buildCar(t, color, kind) {
  const b = new GeoBuilder(), l = new GeoBuilder();
  const L = t.len, W = t.wid, H = t.hei;
  const c = new THREE.Color(color);
  const glass = 0x1c2733, tire = 0x161616, rim = 0x9e9e9e, trim = 0x2a2a2a;
  const wr = kind === 'bus' ? 0.5 : kind === 'sport' ? 0.36 : 0.38;
  const by = wr * 0.9;
  // Karosserie unten
  const bodyH = kind === 'bus' ? H - 0.3 : kind === 'van' ? H - 0.3 : H * 0.48;
  b.setColor(c);
  b.box(-W / 2, by, -L / 2, W / 2, by + bodyH, L / 2);
  b.setColor(trim);
  b.box(-W / 2 - 0.02, by, -L / 2 - 0.02, W / 2 + 0.02, by + 0.18, L / 2 + 0.02);
  if (kind === 'bus' || kind === 'van') {
    // Fensterband
    b.setColor(glass);
    b.box(-W / 2 - 0.01, by + bodyH * 0.55, -L / 2 + (kind === 'van' ? 1.6 : 0.3), W / 2 + 0.01, by + bodyH * 0.85, L / 2 - 0.3);
    b.box(-W / 2 + 0.15, by + bodyH * 0.5, L / 2, W / 2 - 0.15, by + bodyH * 0.88, L / 2 + 0.01);
    if (kind === 'van') {
      b.setColor(c);
      b.box(-W / 2 - 0.02, by + bodyH * 0.5, -L / 2 + 1.6, W / 2 + 0.02, by + bodyH + 0.01, L / 2 - 1.6);
    }
  } else {
    // Kabine
    const cl = kind === 'pickup' ? L * 0.32 : kind === 'sport' ? L * 0.38 : L * 0.48;
    const cz = kind === 'pickup' ? L * 0.08 : -L * 0.05;
    const ch = H - bodyH - by * 0.4;
    b.setColor(glass);
    b.box(-W / 2 + 0.12, by + bodyH, cz - cl / 2, W / 2 - 0.12, by + bodyH + ch * 0.85, cz + cl / 2);
    b.setColor(c);
    b.box(-W / 2 + 0.1, by + bodyH + ch * 0.85, cz - cl / 2 + 0.15, W / 2 - 0.1, by + bodyH + ch, cz + cl / 2 - 0.25);
    b.box(-W / 2 + 0.11, by + bodyH, cz - 0.08, W / 2 - 0.11, by + bodyH + ch * 0.86, cz + 0.08);
    if (kind === 'pickup') {
      b.setColor(trim);
      b.box(-W / 2 + 0.1, by + bodyH - 0.01, -L / 2 + 0.1, W / 2 - 0.1, by + bodyH + 0.02, cz - cl / 2 - 0.1);
      b.setColor(c);
      b.box(-W / 2, by + bodyH, -L / 2, -W / 2 + 0.1, by + bodyH + 0.35, cz - cl / 2);
      b.box(W / 2 - 0.1, by + bodyH, -L / 2, W / 2, by + bodyH + 0.35, cz - cl / 2);
      b.box(-W / 2, by + bodyH, -L / 2, W / 2, by + bodyH + 0.35, -L / 2 + 0.1);
    }
    if (kind === 'sport') {
      b.setColor(trim);
      b.box(-W / 2 + 0.1, by + bodyH + 0.25, -L / 2 + 0.05, W / 2 - 0.1, by + bodyH + 0.3, -L / 2 + 0.45);
      b.box(-0.5, by + bodyH, -L / 2 + 0.2, -0.4, by + bodyH + 0.26, -L / 2 + 0.3);
      b.box(0.4, by + bodyH, -L / 2 + 0.2, 0.5, by + bodyH + 0.26, -L / 2 + 0.3);
    }
    if (kind === 'taxi') {
      b.setColor(0x222222);
      b.box(-0.35, by + bodyH + ch, cz - 0.2, 0.35, by + bodyH + ch + 0.22, cz + 0.2);
    }
  }
  // Räder
  const wheel = new THREE.CylinderGeometry(wr, wr, 0.28, 12);
  wheel.rotateZ(Math.PI / 2);
  const hub = new THREE.CylinderGeometry(wr * 0.55, wr * 0.55, 0.3, 8);
  hub.rotateZ(Math.PI / 2);
  const ax = L / 2 - (kind === 'bus' ? 1.6 : 0.9);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.setColor(tire);
    b.add(wheel, mat(sx * (W / 2 - 0.12), wr, sz * ax));
    b.setColor(rim);
    b.add(hub, mat(sx * (W / 2 - 0.1), wr, sz * ax));
  }
  // Lichter
  l.setColor(0xfff8e1);
  l.box(-W / 2 + 0.15, by + bodyH * 0.55, L / 2, -W / 2 + 0.55, by + bodyH * 0.8, L / 2 + 0.03);
  l.box(W / 2 - 0.55, by + bodyH * 0.55, L / 2, W / 2 - 0.15, by + bodyH * 0.8, L / 2 + 0.03);
  l.setColor(0xd50000);
  l.box(-W / 2 + 0.12, by + bodyH * 0.55, -L / 2 - 0.03, -W / 2 + 0.5, by + bodyH * 0.8, -L / 2);
  l.box(W / 2 - 0.5, by + bodyH * 0.55, -L / 2 - 0.03, W / 2 - 0.12, by + bodyH * 0.8, -L / 2);
  if (kind === 'police') {
    // schwarze Türen + Schriftzug-Streifen
    b.setColor(0x111111);
    b.box(-W / 2 - 0.01, by + 0.18, -L * 0.25, W / 2 + 0.01, by + bodyH, L * 0.2);
    b.setColor(0x1565c0);
    b.box(-W / 2 - 0.02, by + bodyH * 0.6, -L / 2, W / 2 + 0.02, by + bodyH * 0.72, L / 2);
  }
  return { body: b.build(), lights: l.build(), roof: by + H - by * 0.4 };
}

export class Vehicle {
  constructor(scene, kind = 'sedan', color) {
    this.kind = kind;
    this.t = CAR_TYPES[kind];
    this.color = color ?? pick(this.t.colors);
    const geo = buildCar(this.t, this.color, kind);
    this.group = new THREE.Group();
    this.bodyMesh = new THREE.Mesh(geo.body, carMat);
    this.bodyMesh.castShadow = true;
    this.lightMesh = new THREE.Mesh(geo.lights, lightMat);
    this.group.add(this.bodyMesh, this.lightMesh);
    if (kind === 'police') {
      this.siren = [
        new THREE.Mesh(new THREE.BoxGeometry(0.6, 0.18, 0.3), new THREE.MeshBasicMaterial({ color: 0xff1744 })),
        new THREE.Mesh(new THREE.BoxGeometry(0.6, 0.18, 0.3), new THREE.MeshBasicMaterial({ color: 0x2979ff })),
      ];
      this.siren[0].position.set(-0.35, geo.roof + 0.1, -0.2);
      this.siren[1].position.set(0.35, geo.roof + 0.1, -0.2);
      this.group.add(...this.siren);
    }
    scene.add(this.group);
    this.scene = scene;
    this.pos = new THREE.Vector3();
    this.vel = new THREE.Vector3();
    this.heading = 0;
    this.steer = 0;
    this.health = this.t.hp;
    this.driver = null;
    this.ai = null;
    this.destroyed = false;
    this.burning = 0;
    this.sleep = true;
    this.speed = 0;
    this.radius = this.t.wid * 0.55;
    this.horn = 0;
    this.sirenOn = false;
    this.tilt = 0;
  }

  get fwd() { return { x: Math.sin(this.heading), z: Math.cos(this.heading) }; }

  // Vereinfachtes Arcade-Fahrmodell mit Driften
  drive(dt, throttle, steerIn, handbrake) {
    const t = this.t;
    if (this.destroyed) { throttle = 0; steerIn = 0; handbrake = true; }
    const f = this.fwd;
    let v = this.vel.x * f.x + this.vel.z * f.z;
    this.steer = lerp(this.steer, steerIn, Math.min(1, dt * 6));
    if (throttle > 0) v += (v < -0.5 ? t.acc * 2.2 : t.acc) * throttle * dt * (1 - Math.max(0, v) / t.max * 0.6);
    else if (throttle < 0) v += (v > 0.5 ? t.acc * 2.5 : t.acc * 0.6) * throttle * dt;
    v -= v * (0.15 + (throttle === 0 ? 0.35 : 0)) * dt;
    if (handbrake) v -= v * 1.4 * dt;
    v = clamp(v, -t.max * 0.3, t.max);
    // Lenkung: wirkt abhängig von der Geschwindigkeit
    const sf = clamp(Math.abs(v) / 6, 0, 1) * (1 - clamp(Math.abs(v) / (t.max * 1.6), 0, 0.5));
    this.heading -= this.steer * t.turn * sf * Math.sign(v) * dt * (handbrake ? 1.35 : 1);
    const nf = this.fwd;
    const grip = handbrake ? 1.4 : 7;
    this.vel.x = lerp(this.vel.x, nf.x * v, Math.min(1, dt * grip));
    this.vel.z = lerp(this.vel.z, nf.z * v, Math.min(1, dt * grip));
    this.slip = Math.abs(this.vel.x * nf.z - this.vel.z * nf.x);
    this.move(dt);
  }

  move(dt) {
    this.pos.x += this.vel.x * dt;
    this.pos.z += this.vel.z * dt;
    // zwei Kreise (vorne/hinten) gegen Gebäude prüfen
    const f = this.fwd;
    const off = this.t.len / 2 - this.radius * 0.8;
    let impact = 0;
    for (const s of [1, -1]) {
      const p = { x: this.pos.x + f.x * off * s, z: this.pos.z + f.z * off * s };
      const hit = collideCircle(p, this.radius, 0.5);
      if (hit) {
        const dx = p.x - (this.pos.x + f.x * off * s), dz = p.z - (this.pos.z + f.z * off * s);
        this.pos.x += dx; this.pos.z += dz;
        const vn = this.vel.x * hit.nx + this.vel.z * hit.nz;
        if (vn < 0) {
          impact = Math.max(impact, -vn);
          this.vel.x -= vn * hit.nx * 1.3; this.vel.z -= vn * hit.nz * 1.3;
          this.vel.multiplyScalar(0.8);
        }
      }
    }
    if (impact > 6) this.damage((impact - 6) * 2.2);
    this.lastImpact = impact;
    this.speed = Math.hypot(this.vel.x, this.vel.z);
    this.pos.y = groundHeight(this.pos.x, this.pos.z) * 0.6;
    this.sleep = this.speed < 0.05 && !this.driver;
  }

  damage(n) {
    if (this.destroyed) return;
    this.health -= n;
    if (this.health <= 0 && !this.burning) this.burning = 4;
  }

  update(dt, time) {
    const g = this.group;
    g.position.copy(this.pos);
    g.rotation.y = this.heading;
    const fv = this.vel.x * this.fwd.x + this.vel.z * this.fwd.z;
    this.tilt = lerp(this.tilt, clamp(this.steer * fv * 0.004, -0.06, 0.06), Math.min(1, dt * 5));
    g.rotation.z = this.tilt;
    if (this.siren) {
      const on = this.sirenOn && Math.floor(time * 6) % 2 === 0;
      this.siren[0].material.color.set(this.sirenOn ? (on ? 0xff1744 : 0x440000) : 0x661111);
      this.siren[1].material.color.set(this.sirenOn ? (!on ? 0x2979ff : 0x001144) : 0x112266);
    }
  }

  setDestroyed() {
    this.destroyed = true;
    this.bodyMesh.material = new THREE.MeshStandardMaterial({ color: 0x1a1a1a, roughness: 1 });
    this.lightMesh.visible = false;
    if (this.siren) this.siren.forEach((s) => (s.visible = false));
  }

  remove() { this.scene.remove(this.group); }
}
