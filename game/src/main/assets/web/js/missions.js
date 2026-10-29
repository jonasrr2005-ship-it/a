import * as THREE from '../lib/three.module.min.js';
import { N, CELL, PLACES, nodePos, blockRect, lineBlocked } from './world.js';
import { Vehicle, Character } from './entities.js';
import { DIRS, nearestNode, validNode } from './ai.js';
import { clamp } from './geo.js';

// Leuchtende Zylinder-Marker
function makeMarker(scene, color, r = 1.6, h = 2.5) {
  const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, h, 24, 1, true),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.35, side: THREE.DoubleSide, depthWrite: false }));
  m.position.y = h / 2;
  const g = new THREE.Group();
  g.add(m);
  const ring = new THREE.Mesh(new THREE.RingGeometry(r * 0.8, r, 32), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.7, side: THREE.DoubleSide, depthWrite: false }));
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.25;
  g.add(ring);
  scene.add(g);
  return g;
}

const fmt = (n) => '$' + Math.round(n).toLocaleString('de-DE');

export class Missions {
  constructor(game) {
    this.game = game;
    this.active = null;
    this.markers = [];
    for (const k in PLACES) {
      const p = PLACES[k];
      if (!p.icon) continue;
      const m = makeMarker(game.scene, p.color === '#ffd600' ? 0xffd600 : new THREE.Color(p.color).getHex());
      m.position.set(p.x, 0.15, p.z);
      this.markers.push({ key: k, place: p, mesh: m, cool: 0 });
    }
    this.cp = makeMarker(game.scene, 0xffeb3b, 4, 6);
    this.cp.visible = false;
    this.objective = null;
    this.shells = [];
    this.spawnShells();
  }

  // 20 versteckte Muscheln als Sammelobjekte
  spawnShells() {
    const g = this.game;
    const found = new Set(g.save.shells || []);
    const geo = new THREE.ConeGeometry(0.45, 0.7, 8);
    geo.rotateX(Math.PI / 2);
    const matl = new THREE.MeshStandardMaterial({ color: 0xffb3c6, emissive: 0xff80ab, emissiveIntensity: 0.5, roughness: 0.3 });
    let seed = 7;
    const R = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    for (let i = 0; i < 20; i++) {
      let x, z;
      if (i < 6) { x = N * CELL + 20 + R() * 60; z = R() * N * CELL; }
      else {
        const bx = Math.floor(R() * N), bz = Math.floor(R() * N), r = blockRect(bx, bz);
        const side = Math.floor(R() * 4);
        x = side < 2 ? r.x0 + 2 + R() * 60 : (side === 2 ? r.x0 + 1.2 : r.x1 - 1.2);
        z = side >= 2 ? r.z0 + 2 + R() * 60 : (side === 0 ? r.z0 + 1.2 : r.z1 - 1.2);
      }
      if (found.has(i)) continue;
      const m = new THREE.Mesh(geo, matl);
      m.position.set(x, 1, z);
      g.scene.add(m);
      this.shells.push({ id: i, mesh: m });
    }
  }

  start(kind) {
    const g = this.game;
    this.active = { kind, t: 0, stage: 0, cleanup: [] };
    const M = this.active;
    const P = g.player.pos;
    g.audio.jingle(true);
    if (kind === 'race') {
      // Strecke: zufälliger Weg über benachbarte Kreuzungen
      let n = nearestNode(P.x, P.z);
      const route = [n];
      const seen = new Set([n.i + ',' + n.j]);
      let dir = Math.floor(Math.random() * 4);
      while (route.length < 12) {
        const opts = [0, 1, 2, 3].filter((d) => {
          const a = n.i + DIRS[d][0], b = n.j + DIRS[d][1];
          return validNode(a, b) && !seen.has(a + ',' + b);
        });
        if (!opts.length) break;
        dir = opts.includes(dir) && Math.random() < 0.55 ? dir : opts[Math.floor(Math.random() * opts.length)];
        n = { i: n.i + DIRS[dir][0], j: n.j + DIRS[dir][1] };
        seen.add(n.i + ',' + n.j);
        route.push(n);
      }
      M.route = route.map((r) => ({ x: nodePos(r.i), z: nodePos(r.j) }));
      M.cp = 1;
      M.title = 'Straßenrennen';
      // Startaufstellung: Spieler + 2 Gegner
      const start = M.route[0], next = M.route[1];
      const h = Math.atan2(next.x - start.x, next.z - start.z);
      const fx = Math.sin(h), fz = Math.cos(h), rx = -fz, rz = fx;
      let car = g.player.vehicle;
      if (!car || car.destroyed) {
        car = new Vehicle(g.scene, 'sport');
        g.vehicles.push(car);
        if (g.player.vehicle) g.exitVehicle();
        g.enterVehicle(car, true);
      }
      car.pos.set(start.x - fx * 4 + rx * 2.5, 0, start.z - fz * 4 + rz * 2.5);
      car.vel.set(0, 0, 0); car.heading = h;
      M.racers = [];
      for (let i = 0; i < 2; i++) {
        const v = new Vehicle(g.scene, 'sport');
        v.pos.set(start.x - fx * (4 + i * 7) - rx * 2.5 * (i ? -1 : 1) - (i ? fx * 3 : 0), 0, start.z - fz * (4 + i * 7) - rz * 2.5 * (i ? -1 : 1) - (i ? fz * 3 : 0));
        if (i === 1) v.pos.set(start.x - fx * 12, 0, start.z - fz * 12);
        v.heading = h;
        v.mission = true;
        v.ai = { type: 'race', cp: 1, skill: 0.85 + Math.random() * 0.12, done: false };
        const d = new Character(g.scene); d.vehicle = v; v.driver = d; d.ai = { type: 'driver' }; d.mission = true;
        g.peds.push(d);
        g.vehicles.push(v);
        M.racers.push(v);
      }
      M.countdown = 3.5;
      M.finished = [];
      g.big('3', 0.9);
    } else if (kind === 'delivery') {
      const v = new Vehicle(g.scene, 'van');
      const p = PLACES.delivery;
      v.pos.set(p.x - 6, 0, p.z); v.heading = 0; v.mission = true;
      g.vehicles.push(v);
      M.van = v;
      M.title = 'Lieferauftrag';
      // Ziel: weit entfernter Straßenrand
      let best = null;
      for (let k = 0; k < 30; k++) {
        const bx = Math.floor(Math.random() * N), bz = Math.floor(Math.random() * N), r = blockRect(bx, bz);
        const c = { x: r.x0 + 32, z: r.z0 - 4 };
        if (Math.hypot(c.x - p.x, c.z - p.z) > 380 || (!best && k === 29)) { best = c; break; }
        if (!best || Math.hypot(c.x - p.x, c.z - p.z) > Math.hypot(best.x - p.x, best.z - p.z)) best = c;
      }
      M.dest = best;
      M.hot = Math.random() < 0.4;
      M.limit = 25 + Math.hypot(best.x - p.x, best.z - p.z) / 9;
      g.objectiveText('Steig in den Lieferwagen.');
    } else if (kind === 'bounty') {
      M.title = 'Kopfgeld';
      let bx, bz;
      let tries = 0;
      do { bx = Math.floor(Math.random() * N); bz = Math.floor(Math.random() * N); }
      while (Math.abs(Math.hypot(nodePos(bx) - P.x, nodePos(bz) - P.z) - 300) > 80 && ++tries < 200);
      const r = blockRect(bx, bz);
      const cx = r.x0 + 2, cz = r.z0 + 20;
      const look = { shirt: 0x111111, pants: 0x111111, hair: 0xeeeeee };
      M.target = new Character(g.scene, { shirt: 0xb71c1c, pants: 0x212121 });
      M.target.setPos(cx, cz); M.target.mission = true; M.target.health = 120;
      M.target.ai = { type: 'idle' };
      g.peds.push(M.target);
      M.guards = [];
      for (let i = 0; i < 3; i++) {
        const c = new Character(g.scene, look);
        c.setPos(cx + 2 + i * 1.5, cz + (i - 1) * 2);
        c.mission = true; c.gun.visible = true; c.health = 80;
        c.ai = { type: 'hostile', cool: 1 + Math.random(), alert: false };
        g.peds.push(c);
        M.guards.push(c);
      }
      g.objectiveText('Schalte die Zielperson (rot) aus. Vorsicht, sie ist bewacht!');
    } else if (kind === 'taxi') {
      M.title = 'Taxi-Job';
      const v = new Vehicle(g.scene, 'taxi');
      const p = PLACES.taxi;
      v.pos.set(p.x - 6, 0, p.z); v.heading = 0; v.mission = true;
      g.vehicles.push(v);
      M.taxi = v; M.fares = 0;
      g.objectiveText('Steig ins Taxi.');
    }
    g.banner(M.title);
  }

  end(success, msg, reward = 0) {
    const g = this.game, M = this.active;
    if (!M) return;
    if (success) {
      g.addMoney(reward);
      g.save.done = g.save.done || {};
      g.save.done[M.kind] = (g.save.done[M.kind] || 0) + 1;
      g.big('MISSION ERFÜLLT', 3, '#ffd600', `${msg}  +${fmt(reward)}`);
      g.audio.jingle(true);
    } else {
      g.big('MISSION GESCHEITERT', 3, '#ff5252', msg);
      g.audio.jingle(false);
    }
    for (const c of g.peds) if (c.mission) { c.mission = false; if (c.ai && c.ai.type === 'hostile') c.ai = { type: 'wander' }; if (c.ai && c.ai.type === 'idle') c.ai = { type: 'wander' }; }
    for (const v of g.vehicles) if (v.mission) {
      v.mission = false;
      if (v.ai && v.ai.type === 'race') { v.ai = null; }
    }
    this.active = null;
    this.objective = null;
    this.cp.visible = false;
    g.objectiveText('');
    g.persist();
    for (const m of this.markers) m.cool = 4;
  }

  setCheckpoint(p, r = 4) {
    this.cp.visible = true;
    this.cp.position.set(p.x, 0.1, p.z);
    this.cp.scale.setScalar(r / 4);
    this.objective = p;
  }

  update(dt) {
    const g = this.game, P = g.player.vehicle ? g.player.vehicle.pos : g.player.pos;
    for (const s of this.shells) {
      s.mesh.rotation.y += dt * 2;
      s.mesh.position.y = 1 + Math.sin(g.clock * 3 + s.id) * 0.2;
      if (!s.got && Math.hypot(P.x - s.mesh.position.x, P.z - s.mesh.position.z) < 2) {
        s.got = true;
        g.scene.remove(s.mesh);
        g.save.shells = [...(g.save.shells || []), s.id];
        g.addMoney(250);
        g.toast(`Muschel gefunden! ${g.save.shells.length}/20  (+$250)`);
        g.audio.coin();
        g.persist();
      }
    }
    for (const m of this.markers) {
      m.mesh.children[0].material.opacity = 0.25 + Math.sin(g.clock * 4) * 0.1;
      m.mesh.visible = !this.active || !m.place.mission;
      m.cool -= dt;
      if (m.cool > 0 || !m.mesh.visible || g.player.dead) continue;
      const d = Math.hypot(P.x - m.place.x, P.z - m.place.z);
      if (d < 2.2) {
        m.cool = 3;
        if (m.place.mission) this.start(m.place.mission);
        else if (m.key === 'shop') { if (!g.player.vehicle) g.openShop(); else m.cool = 0.5; }
        else if (m.key === 'spray') g.spray();
      }
    }
    const M = this.active;
    if (!M) return;
    M.t += dt;
    if (g.player.dead) { this.end(false, 'Du wurdest ausgeschaltet.'); return; }
    const car = g.player.vehicle;
    if (M.kind === 'race') {
      if (M.countdown > 0) {
        const before = Math.ceil(M.countdown);
        M.countdown -= dt;
        const now = Math.ceil(M.countdown);
        if (now !== before) { g.big(now > 0 ? String(now) : 'LOS!', 0.9); g.audio.tone(now > 0 ? 440 : 880, 0.25, 0.3, 'square'); }
        if (car) { car.vel.set(0, 0, 0); }
        for (const r of M.racers) r.vel.set(0, 0, 0);
        this.setCheckpoint(M.route[1]);
        g.objectiveText('Mach dich bereit…');
        return;
      }
      if (!car) {
        M.out = (M.out || 0) + dt;
        g.objectiveText(`Steig wieder ein! (${Math.ceil(10 - M.out)})`);
        if (M.out > 10) this.end(false, 'Du hast dein Fahrzeug verlassen.');
        return;
      }
      M.out = 0;
      const cp = M.route[M.cp];
      this.setCheckpoint(cp);
      const pos = 1 + M.racers.filter((r) => r.ai && (r.ai.cp > M.cp || (r.ai.cp === M.cp && Math.hypot(r.pos.x - cp.x, r.pos.z - cp.z) < Math.hypot(P.x - cp.x, P.z - cp.z)))).length;
      g.objectiveText(`Platz ${pos}/3 · Checkpoint ${M.cp}/${M.route.length - 1}`);
      if (Math.hypot(P.x - cp.x, P.z - cp.z) < 9) {
        M.cp++;
        g.audio.tone(1047, 0.12, 0.2, 'square');
        if (M.cp >= M.route.length) {
          const place = 1 + M.racers.filter((r) => r.ai && r.ai.done).length;
          const rew = [0, 2500, 1000, 300][place];
          if (place === 1) this.end(true, 'Erster Platz!', rew);
          else this.end(place < 3, `Platz ${place}`, rew);
        }
      }
    } else if (M.kind === 'delivery') {
      if (M.van.destroyed) { this.end(false, 'Die Ware ist zerstört.'); return; }
      if (car !== M.van) {
        this.setCheckpoint({ x: M.van.pos.x, z: M.van.pos.z }, 2);
        this.cp.visible = false;
        if (M.stage === 1) g.objectiveText('Steig wieder in den Lieferwagen!');
        return;
      }
      if (M.stage === 0) {
        M.stage = 1;
        M.left = M.limit;
        if (M.hot) { g.setWanted(Math.max(g.wanted, 2)); g.toast('Die Ware ist heiß – die Polizei ist dir auf den Fersen!'); }
      }
      M.left -= dt;
      this.setCheckpoint(M.dest);
      g.objectiveText(`Liefere die Ware ab · ${Math.max(0, Math.ceil(M.left))} s · Zustand ${Math.round(clamp(M.van.health / M.van.t.hp, 0, 1) * 100)}%`);
      if (M.left <= 0) { this.end(false, 'Zu spät!'); return; }
      if (Math.hypot(P.x - M.dest.x, P.z - M.dest.z) < 5 && car.speed < 3) {
        const cond = clamp(M.van.health / M.van.t.hp, 0, 1);
        this.end(true, `Zustand ${Math.round(cond * 100)}%`, 800 + cond * 900 + M.left * 15 + (M.hot ? 700 : 0));
      }
    } else if (M.kind === 'bounty') {
      const T = M.target;
      if (T.dead) {
        g.setWanted(Math.max(g.wanted, 2));
        this.end(true, 'Zielperson ausgeschaltet.', 3000);
        return;
      }
      this.setCheckpoint({ x: T.pos.x, z: T.pos.z }, 1.2);
      this.cp.visible = false;
      const d = Math.hypot(T.pos.x - P.x, T.pos.z - P.z);
      if (d < 35 && T.ai.type === 'idle') {
        T.ai = { type: 'flee', fx: P.x, fz: P.z, t: 60 };
        for (const c of M.guards) c.ai.alert = true;
        g.toast('Die Zielperson flieht!');
      }
      if (T.ai.type === 'flee') { T.ai.fx = P.x; T.ai.fz = P.z; T.ai.t = 60; }
      if (d > 260 && T.ai.type === 'flee') this.end(false, 'Die Zielperson ist entkommen.');
    } else if (M.kind === 'taxi') {
      if (M.taxi.destroyed) { this.end(false, 'Das Taxi ist zerstört.'); return; }
      if (car !== M.taxi) {
        if (M.stage > 0) {
          M.out = (M.out || 0) + dt;
          g.objectiveText(`Zurück ins Taxi! (${Math.ceil(15 - M.out)})`);
          if (M.out > 15) this.end(false, 'Du hast das Taxi verlassen.');
        }
        return;
      }
      M.out = 0;
      if (M.stage === 0 || M.stage === 3) {
        // neuen Fahrgast erzeugen
        const sp = g.world.sidewalkPoint(clamp(Math.floor(P.x / CELL) + Math.floor(Math.random() * 5) - 2, 0, N - 1), clamp(Math.floor(P.z / CELL) + Math.floor(Math.random() * 5) - 2, 0, N - 1), Math.random() * 4, 1);
        const c = new Character(g.scene);
        c.setPos(sp.x, sp.z); c.mission = true; c.ai = { type: 'idle' }; c.hands = true;
        g.peds.push(c);
        M.fare = c; M.stage = 1;
      }
      if (M.stage === 1) {
        if (M.fare.dead) { this.end(false, 'Dein Fahrgast ist tot.'); return; }
        this.setCheckpoint({ x: M.fare.pos.x, z: M.fare.pos.z }, 2.5);
        g.objectiveText(`Hol den Fahrgast ab (Fahrt ${M.fares + 1}/3)`);
        if (Math.hypot(P.x - M.fare.pos.x, P.z - M.fare.pos.z) < 7 && car.speed < 2) {
          M.fare.remove(); g.peds.splice(g.peds.indexOf(M.fare), 1);
          M.stage = 2;
          let d;
          do { d = g.world.randomSidewalk(); } while (Math.hypot(d.x - P.x, d.z - P.z) < 200);
          M.dest = d;
          M.left = 20 + Math.hypot(d.x - P.x, d.z - P.z) / 8;
          g.toast('Fahrgast: „Schnell bitte, ich bin spät dran!“');
        }
      } else if (M.stage === 2) {
        M.left -= dt;
        this.setCheckpoint(M.dest, 3);
        g.objectiveText(`Bring den Fahrgast ans Ziel · ${Math.max(0, Math.ceil(M.left))} s`);
        if (Math.hypot(P.x - M.dest.x, P.z - M.dest.z) < 7 && car.speed < 2) {
          M.fares++;
          const pay = 250 + Math.max(0, M.left) * 12;
          g.addMoney(pay);
          g.toast(`Fahrpreis + Trinkgeld: ${fmt(pay)}`);
          g.audio.coin();
          if (M.fares >= 3) this.end(true, '3 Fahrten erledigt.', 600);
          else M.stage = 3;
        } else if (M.left <= 0) this.end(false, 'Der Fahrgast ist zu Fuß gegangen.');
      }
    }
  }

  // KI der Renngegner: fahren die Kreuzungen der Strecke ab
  racerDrive(v, dt, steerTo) {
    const M = this.active;
    if (!M || M.kind !== 'race' || M.countdown > 0) { v.drive(dt, 0, 0, true); return; }
    const ai = v.ai;
    if (ai.done) { v.drive(dt, 0, 0, true); return; }
    const cp = M.route[ai.cp], nx = M.route[ai.cp + 1];
    let want = v.t.max * ai.skill;
    const d = Math.hypot(cp.x - v.pos.x, cp.z - v.pos.z);
    if (nx) {
      const a1 = Math.atan2(cp.x - v.pos.x, cp.z - v.pos.z), a2 = Math.atan2(nx.x - cp.x, nx.z - cp.z);
      const turn = Math.abs(Math.atan2(Math.sin(a2 - a1), Math.cos(a2 - a1)));
      if (turn > 0.5 && d < 40) want = Math.min(want, 13 + (d / 40) * 20);
    }
    steerTo(v, dt, cp.x, cp.z, want);
    if (d < 9) {
      ai.cp++;
      if (ai.cp >= M.route.length) ai.done = true;
    }
  }

  // Bewaffnete Gegner (Leibwächter)
  hostile(c, dt) {
    const g = this.game, ai = c.ai;
    const T = g.player.vehicle ? g.player.vehicle.pos : g.player.pos;
    const dx = T.x - c.pos.x, dz = T.z - c.pos.z, d = Math.hypot(dx, dz);
    if (!ai.alert && d < 25) ai.alert = true;
    if (!ai.alert || g.player.dead) { c.move(dt, 0, 0, 0); return; }
    const see = d < 40 && !lineBlocked(c.pos.x, 1.5, c.pos.z, T.x, 1.5, T.z);
    c.aiming = see;
    if (see && d < 28) {
      c.move(dt, 0, 0, 0);
      c.heading = Math.atan2(dx, dz);
      ai.cool -= dt;
      if (ai.cool <= 0) { ai.cool = 0.6 + Math.random() * 0.6; g.npcShoot(c, 0.3, 8); }
    } else c.move(dt, dx, dz, 4.5);
  }
}
