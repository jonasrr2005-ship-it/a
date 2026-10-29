import * as THREE from '../lib/three.module.min.js';
import { N, CELL, LANES, nodePos, blockRect, lineBlocked, BOUNDS, SIZE } from './world.js';
import { Character, Vehicle, TRAFFIC_TYPES } from './entities.js';
import { clamp, angleDiff } from './geo.js';

export const DIRS = [[1, 0], [0, 1], [-1, 0], [0, -1]];
const validNode = (i, j) => i >= 0 && j >= 0 && i <= N && j <= N;
export const nearestNode = (x, z) => ({ i: clamp(Math.round(x / CELL), 0, N), j: clamp(Math.round(z / CELL), 0, N) });
export const COP_LOOK = { shirt: 0x1a3a8a, pants: 0x101828, cap: 0x101828, skin: undefined };

// Steuert ein KI-Fahrzeug entlang der Fahrspuren. chooseDir(ai) wählt an Kreuzungen die neue Richtung.
function laneDrive(game, v, dt, cruise, chooseDir, careful = true) {
  const ai = v.ai;
  const [dx, dz] = DIRS[ai.dir];
  const rx = -dz, rz = dx; // rechts von der Fahrtrichtung
  const nx = nodePos(ai.ti), nz = nodePos(ai.tj);
  let tx, tz, want = cruise;
  const along = (nx - v.pos.x) * dx + (nz - v.pos.z) * dz; // Restweg bis zur Kreuzung
  if (ai.turn == null && along < 14) ai.turn = chooseDir(ai);
  if (ai.turn != null) {
    const [ex, ez] = DIRS[ai.turn];
    const erx = -ez, erz = ex;
    tx = nx + ex * 12 + erx * ai.lane;
    tz = nz + ez * 12 + erz * ai.lane;
    if (ai.turn !== ai.dir) want = Math.min(cruise, ai.turn === (ai.dir + 2) % 4 ? 5 : 9);
    const past = (v.pos.x - nx) * ex + (v.pos.z - nz) * ez;
    if (past > 5) {
      ai.dir = ai.turn; ai.turn = null;
      ai.ti = clamp(Math.round(nx / CELL) + ex, 0, N); ai.tj = clamp(Math.round(nz / CELL) + ez, 0, N);
    }
  } else {
    // Punkt auf der Spur voraus anpeilen
    const lx = nx + rx * ai.lane, lz = nz + rz * ai.lane;
    const proj = (v.pos.x - lx) * dx + (v.pos.z - lz) * dz;
    tx = lx + dx * (proj + 9); tz = lz + dz * (proj + 9);
    if (along < 26 && cruise > 14) want = Math.max(14, cruise * (along / 26));
  }
  // Hindernisse voraus
  if (careful) {
    const f = v.fwd;
    let block = 99;
    const check = (p, r) => {
      const ox = p.x - v.pos.x, oz = p.z - v.pos.z;
      const fwd = ox * f.x + oz * f.z;
      if (fwd < 0 || fwd > 16) return;
      const lat = Math.abs(ox * f.z - oz * f.x);
      if (lat < r + v.t.wid * 0.5) block = Math.min(block, fwd - v.t.len / 2);
    };
    for (const o of game.vehicles) if (o !== v && !o.destroyed) check(o.pos, o.t.wid * 0.5);
    for (const p of game.peds) if (!p.dead && !p.vehicle) check(p.pos, 0.4);
    if (!game.player.vehicle) check(game.player.pos, 0.4);
    if (block < 12) want = Math.min(want, Math.max(0, (block - 3) * 1.4));
    if (block < 6 && v.speed < 1) { ai.honk = (ai.honk || 0) + dt; if (ai.honk > 3) { ai.honk = -6; game.honk(v); } }
  }
  steerTo(v, dt, tx, tz, want);
}

function steerTo(v, dt, tx, tz, want, reverseOk = false) {
  const a = angleDiff(v.heading, Math.atan2(tx - v.pos.x, tz - v.pos.z));
  let steer = clamp(-a * 2.4, -1, 1);
  const fv = v.vel.x * v.fwd.x + v.vel.z * v.fwd.z;
  let thr = fv < want - 1 ? 1 : fv > want + 1 ? -0.7 : 0.15;
  if (want <= 0.2) thr = fv > 0.3 ? -1 : 0;
  if (reverseOk && Math.abs(a) > 2.2 && Math.hypot(tx - v.pos.x, tz - v.pos.z) < 20) { thr = -1; steer = -steer; }
  v.throttle = Math.max(0, thr);
  v.drive(dt, thr, steer, false);
}

export class Population {
  constructor(game) {
    this.game = game;
    this.pedTarget = game.quality > 1 ? 34 : game.quality > 0 ? 26 : 16;
    this.carTarget = game.quality > 1 ? 24 : game.quality > 0 ? 18 : 12;
    this.timer = 0;
    this.heli = null;
  }

  spawnPed(near = true) {
    const g = this.game, p = g.player.pos;
    for (let k = 0; k < 10; k++) {
      const bx = clamp(Math.floor(p.x / CELL) + Math.floor(Math.random() * 5) - 2, 0, N - 1);
      const bz = clamp(Math.floor(p.z / CELL) + Math.floor(Math.random() * 5) - 2, 0, N - 1);
      const sp = g.world.sidewalkPoint(bx, bz, Math.random() * 4, Math.random() < 0.5 ? 1 : -1);
      const d = Math.hypot(sp.x - p.x, sp.z - p.z);
      if (near && (d < 35 || d > 120)) continue;
      const c = new Character(g.scene);
      c.setPos(sp.x, sp.z);
      c.ai = { type: 'walk', bx, bz, s: sp.s, dir: sp.dir, speed: 1.2 + Math.random() * 0.6, t: 0 };
      g.peds.push(c);
      return c;
    }
    return null;
  }

  // Spawnpunkt auf einer Fahrspur außerhalb der Sichtweite
  roadSpawn(minD, maxD) {
    const g = this.game, p = g.player.pos;
    for (let k = 0; k < 20; k++) {
      const n = nearestNode(p.x + (Math.random() - 0.5) * maxD * 2, p.z + (Math.random() - 0.5) * maxD * 2);
      const dir = Math.floor(Math.random() * 4);
      const [dx, dz] = DIRS[dir];
      if (!validNode(n.i + dx, n.j + dz)) continue;
      const lane = LANES[Math.floor(Math.random() * 2)];
      const x = nodePos(n.i) + dx * CELL * 0.45 - dz * lane, z = nodePos(n.j) + dz * CELL * 0.45 + dx * lane;
      const d = Math.hypot(x - p.x, z - p.z);
      if (d < minD || d > maxD) continue;
      if (g.vehicles.some((v) => Math.hypot(v.pos.x - x, v.pos.z - z) < 10)) continue;
      return { x, z, dir, lane, ti: n.i + dx, tj: n.j + dz, heading: Math.atan2(dx, dz) };
    }
    return null;
  }

  spawnCar(kind, minD = 70, maxD = 160) {
    const g = this.game;
    const s = this.roadSpawn(minD, maxD);
    if (!s) return null;
    const v = new Vehicle(g.scene, kind || TRAFFIC_TYPES[Math.floor(Math.random() * TRAFFIC_TYPES.length)]);
    v.pos.set(s.x, 0, s.z);
    v.heading = s.heading;
    v.vel.set(Math.sin(s.heading) * 8, 0, Math.cos(s.heading) * 8);
    v.ai = { type: 'traffic', dir: s.dir, lane: s.lane, ti: s.ti, tj: s.tj, turn: null, cruise: 9 + Math.random() * 5 };
    const d = new Character(g.scene);
    d.vehicle = v; v.driver = d; d.ai = { type: 'driver' };
    g.peds.push(d);
    g.vehicles.push(v);
    return v;
  }

  spawnParked() {
    const g = this.game;
    for (const s of g.world.parkingSpots) {
      const v = new Vehicle(g.scene, TRAFFIC_TYPES[Math.floor(Math.random() * 8)]);
      v.pos.set(s.x, 0, s.z); v.heading = s.h; v.parked = true;
      g.vehicles.push(v);
    }
    if (g.world.policeLot) {
      for (let i = 0; i < 3; i++) {
        const v = new Vehicle(g.scene, 'police');
        v.pos.set(g.world.policeLot.x, 0, g.world.policeLot.z - 12 + i * 8); v.heading = Math.PI / 2; v.parked = true;
        g.vehicles.push(v);
      }
    }
  }

  spawnCop(x, z) {
    const g = this.game;
    const c = new Character(g.scene, { ...COP_LOOK, vest: 0x2b2b2b });
    c.setPos(x, z);
    c.isCop = true;
    c.gun.visible = true;
    c.health = 70;
    c.ai = { type: 'cop', cool: Math.random(), arrest: 0 };
    g.peds.push(c);
    return c;
  }

  spawnPolice() {
    const g = this.game;
    const s = this.roadSpawn(80, 150);
    if (!s) return;
    const v = new Vehicle(g.scene, 'police');
    v.pos.set(s.x, 0, s.z); v.heading = s.heading;
    v.ai = { type: 'police', dir: s.dir, lane: 2.3, ti: s.ti, tj: s.tj, turn: null, crew: 2, stuck: 0 };
    v.sirenOn = true;
    g.vehicles.push(v);
  }

  update(dt) {
    const g = this.game, P = g.player.pos;
    this.timer -= dt;
    if (this.timer <= 0) {
      this.timer = 0.5;
      // Entfernte Objekte aufräumen
      g.peds = g.peds.filter((c) => {
        const far = Math.hypot(c.pos.x - P.x, c.pos.z - P.z) > 150;
        const gone = (c.dead && c.deadTime > 25) || (far && !c.vehicle && !c.mission) || (c.vehicle && c.vehicle.removed);
        if (gone) { c.remove(); if (c.vehicle) c.vehicle.driver = null; }
        return !gone;
      });
      g.vehicles = g.vehicles.filter((v) => {
        const d = Math.hypot(v.pos.x - P.x, v.pos.z - P.z);
        const gone = !v.mission && v !== g.player.vehicle && !v.parked && ((d > 190) || (v.destroyed && d > 60) || (v.ai && v.ai.stuckT > 12 && d > 50));
        if (gone) { v.remove(); v.removed = true; if (v.driver && v.driver !== g.player) { v.driver.remove(); v.driver.vehicle = null; v.driver.gone = true; } }
        return !gone;
      });
      g.peds = g.peds.filter((c) => !c.gone);
      const walkers = g.peds.filter((c) => !c.vehicle && !c.isCop && !c.mission).length;
      if (walkers < this.pedTarget) this.spawnPed();
      const traffic = g.vehicles.filter((v) => v.ai && v.ai.type === 'traffic').length;
      if (traffic < this.carTarget) this.spawnCar();
      const police = g.vehicles.filter((v) => v.ai && v.ai.type === 'police').length;
      const copsOnFoot = g.peds.filter((c) => c.isCop && !c.dead).length;
      const want = [0, 1, 2, 3, 4, 5][g.wanted];
      if (g.wanted > 0 && police < want && copsOnFoot < 4 + g.wanted * 2) this.spawnPolice();
      if (g.wanted >= 3 && !this.heli) this.spawnHeli();
    }

    for (const v of g.vehicles) {
      if (v.ai && v.driver !== g.player && !v.destroyed) {
        if (v.ai.type === 'traffic') this.traffic(v, dt);
        else if (v.ai.type === 'police') this.police(v, dt);
        else if (v.ai.type === 'race') g.missions.racerDrive(v, dt, steerTo);
        else if (v.ai.type === 'flee') this.fleeCar(v, dt);
        if (v.speed < 0.5 && v.ai) v.ai.stuckT = (v.ai.stuckT || 0) + dt; else if (v.ai) v.ai.stuckT = 0;
      } else if (v !== g.player.vehicle && !v.sleep) {
        v.throttle = 0;
        v.drive(dt, 0, 0, true);
      }
    }

    for (const c of g.peds) {
      if (c.vehicle) continue;
      if (c.dead) { c.animate(dt); continue; }
      const ai = c.ai || {};
      if (c.isCop) this.cop(c, dt);
      else if (ai.type === 'walk') this.walk(c, dt);
      else if (ai.type === 'flee') {
        ai.t -= dt;
        const dx = c.pos.x - ai.fx, dz = c.pos.z - ai.fz, l = Math.hypot(dx, dz) || 1;
        c.move(dt, dx / l + Math.sin(ai.t * 2) * 0.3, dz / l, ai.t > 0 ? 5.5 : 1.3);
        if (ai.t < -20) ai.type = 'wander';
      } else if (ai.type === 'wander') {
        ai.a = (ai.a ?? Math.random() * 6) + (Math.random() - 0.5) * dt;
        c.move(dt, Math.sin(ai.a), Math.cos(ai.a), 1.3);
      } else if (ai.type === 'hostile') g.missions.hostile(c, dt);
      else if (ai.type === 'idle') c.move(dt, 0, 0, 0);
      c.animate(dt);
    }
    if (this.heli) this.updateHeli(dt);
  }

  walk(c, dt) {
    const ai = c.ai;
    ai.t += dt;
    if (ai.pause > 0) { ai.pause -= dt; c.move(dt, 0, 0, 0); return; }
    if (Math.random() < dt * 0.02) ai.pause = 1 + Math.random() * 3;
    if (Math.random() < dt * 0.01) ai.dir *= -1;
    ai.s += ai.dir * ai.speed * dt / (CELL - 16 - 4);
    const p = this.game.world.sidewalkPoint(ai.bx, ai.bz, ai.s, ai.dir);
    // An einer Ecke ggf. über die Straße zum Nachbarblock wechseln
    const frac = ((ai.s % 1) + 1) % 1;
    if (frac < 0.01 && Math.random() < 0.3) {
      const nb = [[1, 0], [0, 1], [-1, 0], [0, -1]][Math.floor(Math.random() * 4)];
      if (ai.bx + nb[0] >= 0 && ai.bx + nb[0] < N && ai.bz + nb[1] >= 0 && ai.bz + nb[1] < N) { ai.bx += nb[0]; ai.bz += nb[1]; }
    }
    c.move(dt, p.x - c.pos.x, p.z - c.pos.z, Math.min(ai.speed, Math.hypot(p.x - c.pos.x, p.z - c.pos.z) * 4 + 0.5));
  }

  flee(c, from, t = 8) {
    if (!c || c.dead || c.isCop || c.mission) return;
    if (c.vehicle) { // Fahrer flüchtet mit dem Auto
      const v = c.vehicle;
      if (v.ai && v.ai.type === 'traffic') v.ai.cruise = 22;
      return;
    }
    c.ai = { type: 'flee', fx: from.x, fz: from.z, t };
  }

  panic(pos, radius) {
    for (const c of this.game.peds) if (Math.hypot(c.pos.x - pos.x, c.pos.z - pos.z) < radius) this.flee(c, pos);
  }

  traffic(v, dt) {
    laneDrive(this.game, v, dt, v.ai.cruise, (ai) => {
      const opts = [];
      for (let d = 0; d < 4; d++) {
        if (d === (ai.dir + 2) % 4) continue;
        if (!validNode(ai.ti + DIRS[d][0], ai.tj + DIRS[d][1])) continue;
        opts.push(d, d === ai.dir ? d : -1);
      }
      const o = opts.filter((d) => d >= 0);
      if (!o.length) return (ai.dir + 2) % 4;
      return o[Math.floor(Math.random() * o.length)];
    });
  }

  fleeCar(v, dt) {
    const g = this.game;
    laneDrive(g, v, dt, 26, (ai) => {
      let best = ai.dir, bd = -1;
      for (let d = 0; d < 4; d++) {
        const ni = ai.ti + DIRS[d][0], nj = ai.tj + DIRS[d][1];
        if (!validNode(ni, nj) || d === (ai.dir + 2) % 4) continue;
        const dist = Math.hypot(nodePos(ni) - g.player.pos.x, nodePos(nj) - g.player.pos.z) + Math.random() * 30;
        if (dist > bd) { bd = dist; best = d; }
      }
      return best;
    }, false);
  }

  police(v, dt) {
    const g = this.game, P = g.player.vehicle ? g.player.vehicle.pos : g.player.pos;
    const ai = v.ai;
    if (g.wanted === 0) { // Einsatz beendet
      v.ai = { type: 'traffic', dir: ai.dir, lane: ai.lane, ti: ai.ti, tj: ai.tj, turn: null, cruise: 11 };
      v.sirenOn = false;
      return;
    }
    const d = Math.hypot(P.x - v.pos.x, P.z - v.pos.z);
    const see = d < 45 && !lineBlocked(v.pos.x, 1, v.pos.z, P.x, 1, P.z);
    if (see) {
      // Direkt verfolgen / rammen; zu Fuß: anhalten und aussteigen
      const onFoot = !g.player.vehicle;
      const want = onFoot ? (d < 14 ? 0 : 18) : clamp(d * 1.2, 10, 50);
      steerTo(v, dt, P.x, P.z, want, true);
      if (onFoot && d < 16 && v.speed < 2 && ai.crew > 0) this.exitCops(v);
      if (!onFoot && v.speed < 1.5 && d < 10 && g.player.vehicle.speed < 1.5 && ai.crew > 0) this.exitCops(v);
      // Knoten neu bestimmen, damit die Straßenfahrt danach passt
      const n = nearestNode(v.pos.x, v.pos.z);
      let bd = 0, bdir = 0;
      for (let k = 0; k < 4; k++) { const dd = v.fwd.x * DIRS[k][0] + v.fwd.z * DIRS[k][1]; if (dd > bd) { bd = dd; bdir = k; } }
      ai.dir = bdir; ai.ti = clamp(n.i + DIRS[bdir][0], 0, N); ai.tj = clamp(n.j + DIRS[bdir][1], 0, N); ai.turn = null;
      if (!validNode(n.i + DIRS[bdir][0], n.j + DIRS[bdir][1])) { ai.ti = n.i; ai.tj = n.j; }
    } else {
      laneDrive(g, v, dt, 30, (a) => {
        const pn = nearestNode(P.x, P.z);
        let best = a.dir, bd = 1e9;
        for (let k = 0; k < 4; k++) {
          const ni = a.ti + DIRS[k][0], nj = a.tj + DIRS[k][1];
          if (!validNode(ni, nj)) continue;
          const dist = Math.abs(ni - pn.i) + Math.abs(nj - pn.j) + (k === (a.dir + 2) % 4 ? 1.5 : 0) + Math.random() * 0.3;
          if (dist < bd) { bd = dist; best = k; }
        }
        return best;
      }, false);
    }
    if (ai.stuckT > 4 && ai.crew > 0) this.exitCops(v);
  }

  exitCops(v) {
    const n = v.ai.crew;
    v.ai.crew = 0;
    const f = v.fwd;
    for (let i = 0; i < n; i++) {
      const s = i ? 1 : -1;
      this.spawnCop(v.pos.x + f.z * s * 1.8, v.pos.z - f.x * s * 1.8);
    }
    v.ai = null;
    v.parked = false;
  }

  cop(c, dt) {
    const g = this.game, ai = c.ai;
    const T = g.player.vehicle ? g.player.vehicle.pos : g.player.pos;
    const dx = T.x - c.pos.x, dz = T.z - c.pos.z, d = Math.hypot(dx, dz);
    if (g.wanted === 0 || g.player.dead) {
      c.aiming = false;
      ai.a = ai.a ?? Math.random() * 6;
      c.move(dt, Math.sin(ai.a), Math.cos(ai.a), 1.3);
      return;
    }
    const see = d < 50 && !lineBlocked(c.pos.x, 1.5, c.pos.z, T.x, 1.5, T.z);
    if (g.wanted === 1) {
      // Festnahme versuchen
      c.aiming = d < 12;
      if (d > 1.4) c.move(dt, dx, dz, d > 8 ? 5.5 : 3);
      else {
        c.move(dt, 0, 0, 0);
        c.heading = Math.atan2(dx, dz);
        const slow = g.player.vehicle ? g.player.vehicle.speed < 1 : g.player.speed < 2.5;
        ai.arrest = slow ? ai.arrest + dt : Math.max(0, ai.arrest - dt);
        if (ai.arrest > (g.player.vehicle ? 2 : 1.2)) g.bust();
      }
      if (d > 2 && d < 25 && Math.random() < dt * 0.15) g.toast('Polizei: „Stehen bleiben!“');
      return;
    }
    c.aiming = see && d < 30;
    if (see && d < 26) {
      c.move(dt, d < 7 ? -dx : 0, d < 7 ? -dz : 0, 2);
      c.heading = Math.atan2(dx, dz);
      ai.cool -= dt;
      if (ai.cool <= 0) {
        ai.cool = 0.7 + Math.random() * 0.8;
        g.npcShoot(c, g.wanted >= 4 ? 0.4 : 0.28, 7);
      }
    } else c.move(dt, dx, dz, 5.5);
  }

  spawnHeli() {
    const g = this.game;
    const grp = new THREE.Group();
    const body = new THREE.Mesh(new THREE.BoxGeometry(2.4, 2.2, 5), new THREE.MeshStandardMaterial({ color: 0x1a237e, roughness: 0.5, metalness: 0.4 }));
    const tail = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.6, 5), body.material);
    tail.position.set(0, 0.4, -4.5);
    const glass = new THREE.Mesh(new THREE.BoxGeometry(2.2, 1.4, 1.6), new THREE.MeshStandardMaterial({ color: 0x223344, roughness: 0.1, metalness: 0.8 }));
    glass.position.set(0, 0.2, 2.3);
    const rotor = new THREE.Mesh(new THREE.BoxGeometry(11, 0.08, 0.4), new THREE.MeshStandardMaterial({ color: 0x111111 }));
    rotor.position.y = 1.4;
    const rotor2 = rotor.clone(); rotor2.rotation.y = Math.PI / 2;
    const rot = new THREE.Group(); rot.add(rotor, rotor2);
    const skid = new THREE.Mesh(new THREE.BoxGeometry(2.4, 0.1, 4), new THREE.MeshStandardMaterial({ color: 0x333333 }));
    skid.position.y = -1.4;
    const beam = new THREE.Mesh(new THREE.ConeGeometry(7, 40, 16, 1, true), new THREE.MeshBasicMaterial({ color: 0xfff5cc, transparent: true, opacity: 0.12, depthWrite: false, blending: THREE.AdditiveBlending }));
    beam.position.y = -20;
    grp.add(body, tail, glass, rot, skid, beam);
    grp.traverse((o) => { if (o !== beam) o.castShadow = true; });
    const P = g.player.pos;
    grp.position.set(P.x + 150, 40, P.z + 150);
    g.scene.add(grp);
    this.heli = { grp, rot, beam, health: 300, cool: 2, ang: 0, dead: false, vy: 0, isHeli: true, pos: grp.position };
  }

  updateHeli(dt) {
    const g = this.game, h = this.heli, P = g.player.vehicle ? g.player.vehicle.pos : g.player.pos;
    h.rot.rotation.y += dt * 25;
    h.beam.visible = g.world.night > 0.4 && !h.dead;
    if (h.dead) {
      h.vy -= 12 * dt;
      h.grp.position.y += h.vy * dt;
      h.grp.rotation.y += dt * 3;
      g.fx.fire(h.grp.position);
      if (h.grp.position.y < 1) { g.explode(h.grp.position.clone(), true); g.scene.remove(h.grp); this.heli = null; }
      return;
    }
    const leaving = g.wanted < 3;
    h.ang += dt * 0.25;
    const tx = leaving ? h.grp.position.x + 50 : P.x + Math.cos(h.ang) * 28;
    const tz = leaving ? h.grp.position.z + 50 : P.z + Math.sin(h.ang) * 28;
    const p = h.grp.position;
    p.x += clamp(tx - p.x, -25, 25) * dt * 1.2;
    p.z += clamp(tz - p.z, -25, 25) * dt * 1.2;
    p.y += ((leaving ? 80 : 32) - p.y) * dt;
    h.grp.rotation.y = Math.atan2(P.x - p.x, P.z - p.z);
    h.grp.rotation.z = Math.sin(h.ang * 3) * 0.08;
    if (leaving && Math.hypot(P.x - p.x, P.z - p.z) > 250) { g.scene.remove(h.grp); this.heli = null; return; }
    if (!leaving) {
      h.cool -= dt;
      if (h.cool <= 0) { h.cool = 1.4; g.npcShoot({ pos: new THREE.Vector3(p.x, p.y - 4, p.z), heli: true }, 0.25, 9); }
    }
  }
}

export { steerTo, laneDrive, validNode, BOUNDS, SIZE, blockRect };
