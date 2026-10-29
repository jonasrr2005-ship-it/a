import * as THREE from '../lib/three.module.min.js';
import { canvasTex } from './geo.js';

// Partikel (Rauch, Feuer, Funken, Blut), Leuchtspuren und Explosionen
export class FX {
  constructor(scene) {
    this.scene = scene;
    const soft = canvasTex(64, 64, (g) => {
      const gr = g.createRadialGradient(32, 32, 0, 32, 32, 32);
      gr.addColorStop(0, 'rgba(255,255,255,1)'); gr.addColorStop(0.5, 'rgba(255,255,255,0.5)'); gr.addColorStop(1, 'rgba(255,255,255,0)');
      g.fillStyle = gr; g.fillRect(0, 0, 64, 64);
    });
    this.pool = [];
    for (let i = 0; i < 160; i++) {
      const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: soft, transparent: true, depthWrite: false }));
      s.visible = false;
      s.userData = { life: 0 };
      scene.add(s);
      this.pool.push(s);
    }
    this.next = 0;
    this.tracers = [];
    for (let i = 0; i < 24; i++) {
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0, 0, 0, 0], 3));
      const l = new THREE.Line(g, new THREE.LineBasicMaterial({ color: 0xffe082, transparent: true }));
      l.frustumCulled = false;
      l.visible = false;
      l.userData = { life: 0 };
      scene.add(l);
      this.tracers.push(l);
    }
    this.nextT = 0;
    this.flash = new THREE.PointLight(0xffaa55, 0, 40, 1.6);
    scene.add(this.flash);
    this.muzzle = new THREE.PointLight(0xffd27a, 0, 8, 2);
    scene.add(this.muzzle);
    this.rings = [];
  }

  particle(x, y, z, o) {
    const s = this.pool[this.next];
    this.next = (this.next + 1) % this.pool.length;
    s.position.set(x, y, z);
    s.material.color.set(o.color ?? 0xffffff);
    s.material.opacity = o.opacity ?? 1;
    s.material.blending = o.add ? THREE.AdditiveBlending : THREE.NormalBlending;
    s.scale.setScalar(o.size ?? 1);
    s.visible = true;
    Object.assign(s.userData, {
      life: o.life ?? 1, max: o.life ?? 1, vx: o.vx ?? 0, vy: o.vy ?? 0, vz: o.vz ?? 0,
      grow: o.grow ?? 0, g: o.gravity ?? 0, op: o.opacity ?? 1,
    });
  }

  smoke(p, dark = false, n = 1) {
    for (let i = 0; i < n; i++) this.particle(p.x + (Math.random() - 0.5), p.y + Math.random() * 0.5, p.z + (Math.random() - 0.5), {
      color: dark ? 0x222222 : 0x9e9e9e, opacity: 0.55, size: 1 + Math.random(), grow: 1.6, life: 2 + Math.random(),
      vx: (Math.random() - 0.5) * 0.6, vy: 1.5 + Math.random(), vz: (Math.random() - 0.5) * 0.6,
    });
  }

  fire(p) {
    this.particle(p.x + (Math.random() - 0.5) * 1.2, p.y, p.z + (Math.random() - 0.5) * 1.2, {
      color: Math.random() < 0.5 ? 0xff6d00 : 0xffab00, add: true, size: 0.9 + Math.random() * 0.8, grow: -0.4, life: 0.6,
      vy: 2 + Math.random() * 2,
    });
  }

  sparks(p, color = 0xffd54f, n = 5) {
    for (let i = 0; i < n; i++) this.particle(p.x, p.y, p.z, {
      color, add: true, size: 0.18, life: 0.35, gravity: -9,
      vx: (Math.random() - 0.5) * 6, vy: Math.random() * 4, vz: (Math.random() - 0.5) * 6,
    });
  }

  blood(p) {
    for (let i = 0; i < 6; i++) this.particle(p.x, p.y, p.z, {
      color: 0x8b0000, size: 0.25, life: 0.5, gravity: -9,
      vx: (Math.random() - 0.5) * 3, vy: Math.random() * 3, vz: (Math.random() - 0.5) * 3,
    });
  }

  tracer(a, b) {
    const l = this.tracers[this.nextT];
    this.nextT = (this.nextT + 1) % this.tracers.length;
    const arr = l.geometry.attributes.position.array;
    arr[0] = a.x; arr[1] = a.y; arr[2] = a.z; arr[3] = b.x; arr[4] = b.y; arr[5] = b.z;
    l.geometry.attributes.position.needsUpdate = true;
    l.visible = true;
    l.material.opacity = 1;
    l.userData.life = 0.08;
    this.muzzle.position.copy(a);
    this.muzzle.intensity = 6;
  }

  explosion(p) {
    for (let i = 0; i < 26; i++) this.particle(p.x, p.y + 1, p.z, {
      color: i % 3 ? 0xff8f00 : 0xffd54f, add: true, size: 2 + Math.random() * 2, grow: 2.5, life: 0.7 + Math.random() * 0.4,
      vx: (Math.random() - 0.5) * 12, vy: Math.random() * 9, vz: (Math.random() - 0.5) * 12, gravity: -4,
    });
    for (let i = 0; i < 14; i++) this.particle(p.x, p.y + 1.5, p.z, {
      color: 0x333333, opacity: 0.7, size: 2.5, grow: 2.2, life: 3 + Math.random() * 2,
      vx: (Math.random() - 0.5) * 4, vy: 2 + Math.random() * 3, vz: (Math.random() - 0.5) * 4,
    });
    this.sparks({ x: p.x, y: p.y + 1, z: p.z }, 0xffcc80, 20);
    this.flash.position.set(p.x, p.y + 3, p.z);
    this.flash.intensity = 120;
  }

  update(dt) {
    for (const s of this.pool) {
      if (!s.visible) continue;
      const u = s.userData;
      u.life -= dt;
      if (u.life <= 0) { s.visible = false; continue; }
      u.vy += u.g * dt;
      s.position.x += u.vx * dt; s.position.y += u.vy * dt; s.position.z += u.vz * dt;
      if (s.position.y < 0.05) { s.position.y = 0.05; u.vy = 0; }
      s.scale.multiplyScalar(1 + u.grow * dt);
      s.material.opacity = u.op * (u.life / u.max);
    }
    for (const l of this.tracers) {
      if (!l.visible) continue;
      l.userData.life -= dt;
      if (l.userData.life <= 0) l.visible = false;
    }
    this.flash.intensity *= Math.max(0, 1 - dt * 5);
    this.muzzle.intensity *= Math.max(0, 1 - dt * 30);
  }
}
