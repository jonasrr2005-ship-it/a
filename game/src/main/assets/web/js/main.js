import * as THREE from '../lib/three.module.min.js';
import {
  World, PLACES, collideCircle, lineBlocked, solidAt, N, CELL, SIZE, HALF, blockRect, blockType, BOUNDS, groundHeight,
} from './world.js';
import { Character, Vehicle } from './entities.js';
import { FX } from './fx.js';
import { Audio } from './audio.js';
import { Population, nearestNode } from './ai.js';
import { Missions } from './missions.js';
import { clamp, lerp, angleDiff } from './geo.js';

const $ = (id) => document.getElementById(id);
const fmt = (n) => '$' + Math.round(n).toLocaleString('de-DE');

const WEAPONS = {
  fist: { name: 'Faust', icon: '👊', dmg: 14, rate: 0.45, range: 1.8, melee: true },
  pistol: { name: 'Pistole', icon: '🔫', dmg: 28, rate: 0.3, range: 70, spread: 0.015, sound: 'pistol' },
  smg: { name: 'MP', icon: '🔫²', dmg: 15, rate: 0.085, range: 55, spread: 0.04, sound: 'smg' },
  shotgun: { name: 'Schrotflinte', icon: '💥', dmg: 13, rate: 0.95, range: 30, spread: 0.1, pellets: 7, sound: 'shotgun' },
};
const ORDER = ['fist', 'pistol', 'smg', 'shotgun'];

// ---------- Spielstand ----------
function loadSave() {
  try { return JSON.parse(localStorage.getItem('palmera_save') || '{}'); } catch { return {}; }
}
const save = loadSave();
save.money ??= 500;
save.ammo ??= { pistol: 72, smg: 0, shotgun: 0 };
save.owned ??= ['fist', 'pistol'];
save.shells ??= [];
save.done ??= {};
save.quality ??= 2;
save.sound ??= true;

// ---------- Renderer ----------
const Q = save.quality; // 0 niedrig, 1 mittel, 2 hoch
const canvas = $('c');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: Q > 1, powerPreference: 'high-performance' });
const maxPR = [1, 1.5, 2.2][Q];
let pixelRatio = Math.min(window.devicePixelRatio || 1, maxPR);
renderer.setPixelRatio(pixelRatio);
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.shadowMap.enabled = Q > 0;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(65, window.innerWidth / window.innerHeight, 0.3, 1800);
window.addEventListener('resize', () => {
  renderer.setSize(window.innerWidth, window.innerHeight);
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
});

// ---------- Spielobjekt ----------
const world = new World(scene, Q);
const game = {
  scene, camera, world, quality: Q, save,
  fx: new FX(scene), audio: new Audio(),
  vehicles: [], peds: [], pickups: [],
  wanted: 0, heat: 0, lastSeen: 0, clock: 0,
  paused: true, started: false, state: 'play', stateT: 0,
};
game.audio.enabled = save.sound;

const player = new Character(scene, { shirt: 0xf5f5f5, pants: 0x34507a, hair: 0x1b1b1b, skin: 0xd1a27a });
player.isPlayer = true;
player.armor = save.armor || 0;
player.weapon = 'pistol';
player.fireCool = 0;
game.player = player;
const startR = blockRect(2, 4);
player.setPos(startR.x1 - 3, startR.z0 + 26);
player.heading = -Math.PI / 2;

const pop = new Population(game);
game.pop = pop;
const missions = new Missions(game);
game.missions = missions;

pop.spawnParked();
// Ein Sportwagen direkt neben dem Start
{
  let best = null, bd = 1e9;
  for (const v of game.vehicles) {
    const d = Math.hypot(v.pos.x - player.pos.x, v.pos.z - player.pos.z);
    if (v.kind !== 'police' && d < bd) { bd = d; best = v; }
  }
  if (best) {
    const s = new Vehicle(scene, 'sport', 0xd50000);
    s.pos.copy(best.pos); s.heading = best.heading; s.parked = true;
    best.remove();
    game.vehicles[game.vehicles.indexOf(best)] = s;
  }
}
for (let i = 0; i < pop.pedTarget * 0.7; i++) pop.spawnPed(false);
for (let i = 0; i < pop.carTarget * 0.7; i++) pop.spawnCar(undefined, 25, 170);

// ---------- UI-Helfer ----------
let toastT = 0;
game.toast = (msg) => { $('toast').textContent = msg; $('toast').classList.add('show'); toastT = 3.2; };
let bigT = 0;
game.big = (t, dur = 2.5, color = '#fff', sub = '') => {
  $('bigt').textContent = t; $('bigt').style.color = color; $('bigs').textContent = sub;
  $('big').classList.add('show'); bigT = dur;
};
game.banner = (t) => { game.big(t, 2.2, '#ffd600'); $('mtitle').textContent = t; };
game.objectiveText = (t) => { $('mobj').textContent = t; if (!t) $('mtitle').textContent = ''; };
game.addMoney = (n) => { save.money = Math.max(0, save.money + n); };
game.persist = () => {
  save.armor = player.armor;
  save.time = world.time;
  try { localStorage.setItem('palmera_save', JSON.stringify(save)); } catch { /* voll */ }
};
const vibrate = (ms) => { try { if (window.Android) window.Android.vibrate(ms); else navigator.vibrate?.(ms); } catch { /* egal */ } };
if (save.time) world.time = save.time;

// ---------- Fahndung ----------
game.setWanted = (n) => {
  n = clamp(n, 0, 5);
  if (n > game.wanted) game.lastSeen = game.clock;
  game.wanted = n;
  if (n === 0) {
    game.heat = 0;
    for (const v of game.vehicles) v.sirenOn = v.ai && v.ai.type === 'police';
  }
};
game.addHeat = (h) => {
  game.heat += h;
  const lvl = game.heat >= 22 ? 5 : game.heat >= 14 ? 4 : game.heat >= 7 ? 3 : game.heat >= 3 ? 2 : 1;
  if (lvl > game.wanted) { game.setWanted(lvl); vibrate(60); }
  game.lastSeen = game.clock;
};
function copNear(r) {
  const P = player.pos;
  return game.peds.some((c) => c.isCop && !c.dead && Math.hypot(c.pos.x - P.x, c.pos.z - P.z) < r)
    || game.vehicles.some((v) => v.kind === 'police' && v.ai && Math.hypot(v.pos.x - P.x, v.pos.z - P.z) < r);
}
game.crime = (kind) => {
  if (kind === 'shoot') { if (copNear(55)) game.addHeat(0.6); }
  else if (kind === 'assault') { if (copNear(35)) game.addHeat(0.6); }
  else if (kind === 'steal') { if (copNear(40)) game.addHeat(0.6); }
  else if (kind === 'kill') game.addHeat(1);
  else if (kind === 'kill_cop') game.addHeat(3.5);
  else if (kind === 'steal_cop') game.addHeat(3);
  else if (kind === 'hit_cop') game.addHeat(0.4);
};

function updateWanted(dt) {
  if (game.wanted === 0) return;
  const P = player.vehicle ? player.vehicle.pos : player.pos;
  let seen = false;
  for (const c of game.peds) {
    if (!c.isCop || c.dead) continue;
    const d = Math.hypot(c.pos.x - P.x, c.pos.z - P.z);
    if (d < 45 && !lineBlocked(c.pos.x, 1.6, c.pos.z, P.x, 1.6, P.z)) { seen = true; break; }
  }
  if (!seen) for (const v of game.vehicles) {
    if (!v.ai || v.ai.type !== 'police') continue;
    const d = Math.hypot(v.pos.x - P.x, v.pos.z - P.z);
    if (d < 60 && !lineBlocked(v.pos.x, 1.5, v.pos.z, P.x, 1.5, P.z)) { seen = true; break; }
  }
  if (!seen && pop.heli && !pop.heli.dead && Math.hypot(pop.heli.pos.x - P.x, pop.heli.pos.z - P.z) < 70) seen = true;
  if (seen) game.lastSeen = game.clock;
  if (game.clock - game.lastSeen > 9 + game.wanted * 4) {
    game.setWanted(0);
    game.toast('Du hast die Polizei abgehängt.');
  }
}

// ---------- Kampf ----------
const _v = new THREE.Vector3();
function raySphere(o, d, cx, cy, cz, r) {
  const ox = o.x - cx, oy = o.y - cy, oz = o.z - cz;
  const b = ox * d.x + oy * d.y + oz * d.z;
  const c = ox * ox + oy * oy + oz * oz - r * r;
  const h = b * b - c;
  if (h < 0) return -1;
  const t = -b - Math.sqrt(h);
  return t > 0 ? t : -1;
}
function rayCar(o, d, v) {
  const c = Math.cos(v.heading), s = Math.sin(v.heading);
  const wx = o.x - v.pos.x, wz = o.z - v.pos.z;
  const lo = [wx * c - wz * s, o.y - v.pos.y, wx * s + wz * c];
  const ld = [d.x * c - d.z * s, d.y, d.x * s + d.z * c];
  const mn = [-v.t.wid / 2, 0.2, -v.t.len / 2], mx = [v.t.wid / 2, v.t.hei, v.t.len / 2];
  let t0 = 0, t1 = 1e9;
  for (let i = 0; i < 3; i++) {
    if (Math.abs(ld[i]) < 1e-6) { if (lo[i] < mn[i] || lo[i] > mx[i]) return -1; continue; }
    let a = (mn[i] - lo[i]) / ld[i], b = (mx[i] - lo[i]) / ld[i];
    if (a > b) [a, b] = [b, a];
    t0 = Math.max(t0, a); t1 = Math.min(t1, b);
    if (t0 > t1) return -1;
  }
  return t0;
}

// Strahl durch die Welt: trifft Figuren, Fahrzeuge, Hubschrauber oder Wände
function raycast(o, d, range, ignore) {
  let best = range, hit = null;
  const chars = game.peds.concat([player]);
  for (const c of chars) {
    if (c === ignore || c.vehicle || c.dead) continue;
    if (Math.abs(c.pos.x - o.x) > range + 2 || Math.abs(c.pos.z - o.z) > range + 2) continue;
    for (const [y, r] of [[1.25, 0.42], [0.55, 0.35]]) {
      const t = raySphere(o, d, c.pos.x, c.pos.y + y, c.pos.z, r);
      if (t > 0 && t < best) { best = t; hit = { char: c }; }
    }
  }
  for (const v of game.vehicles) {
    if (ignore && ignore.vehicle === v) continue;
    if (Math.abs(v.pos.x - o.x) > range + 6 || Math.abs(v.pos.z - o.z) > range + 6) continue;
    const t = rayCar(o, d, v);
    if (t > 0 && t < best) { best = t; hit = { veh: v }; }
  }
  if (pop.heli && !pop.heli.dead) {
    const h = pop.heli.pos;
    const t = raySphere(o, d, h.x, h.y, h.z, 3);
    if (t > 0 && t < best) { best = t; hit = { heli: pop.heli }; }
  }
  for (let t = 0.5; t < best; t += 0.5) {
    const x = o.x + d.x * t, y = o.y + d.y * t, z = o.z + d.z * t;
    if (y < groundHeight(x, z) || solidAt(x, z, y)) { best = t; hit = { wall: true }; break; }
  }
  return { t: best, hit, point: new THREE.Vector3(o.x + d.x * best, o.y + d.y * best, o.z + d.z * best) };
}

function onKill(c, byPlayer) {
  if (!byPlayer) return;
  if (c.isCop) game.crime('kill_cop');
  else game.crime('kill');
  if (!c.isCop && Math.random() < 0.6) addPickup('cash', c.pos.x, c.pos.z, 10 + Math.floor(Math.random() * 70));
  if ((c.isCop || c.gun.visible) && Math.random() < 0.7) addPickup('ammo', c.pos.x + 0.5, c.pos.z, 12);
}

function hitChar(c, dmg, from, byPlayer) {
  if (c === player) { damagePlayer(dmg); return; }
  game.fx.blood(new THREE.Vector3(c.pos.x, c.pos.y + 1.2, c.pos.z));
  const died = c.damage(dmg);
  if (died) onKill(c, byPlayer);
  else if (c.ai && (c.ai.type === 'walk' || c.ai.type === 'wander' || c.ai.type === 'idle') && !c.mission) pop.flee(c, from, 10);
  if (c.ai && c.ai.type === 'hostile') c.ai.alert = true;
  if (byPlayer && c.isCop && !died) game.addHeat(0.8);
}

function hitVehicle(v, dmg, p, byPlayer) {
  game.fx.sparks(p, 0xffe082, 3);
  v.damage(dmg);
  if (byPlayer) {
    v.lastByPlayer = true;
    if (v.kind === 'police' && v.ai) game.crime('hit_cop');
    if (v.driver && v.driver !== player) pop.flee(v.driver, player.pos);
  }
}

function fireShot(o, d, w, shooter) {
  const byPlayer = shooter === player;
  const r = raycast(o, d, w.range, shooter);
  const h = r.hit;
  if (h) {
    if (h.char) hitChar(h.char, w.dmg, o, byPlayer);
    else if (h.veh) hitVehicle(h.veh, w.dmg * 0.6, r.point, byPlayer);
    else if (h.heli) {
      h.heli.health -= w.dmg;
      game.fx.sparks(r.point, 0xffe082, 4);
      if (h.heli.health <= 0 && !h.heli.dead) { h.heli.dead = true; game.addHeat(4); game.toast('Polizeihubschrauber abgeschossen!'); }
    } else if (h.wall) game.fx.sparks(r.point, 0xcccccc, 3);
  }
  game.fx.tracer(o, r.point);
}

// automatische Zielerfassung im Kegel vor dem Spieler
function findTarget(origin, yaw, range, cone) {
  let best = null, bs = 1e9;
  const consider = (obj, x, y, z, prio) => {
    const dx = x - origin.x, dz = z - origin.z, d = Math.hypot(dx, dz);
    if (d > range || d < 0.5) return;
    const a = Math.abs(angleDiff(yaw, Math.atan2(dx, dz)));
    if (a > cone && !(d < 9 && prio > 0) && !(d < 5)) return; // nahe Gegner auch seitlich erfassen
    const s = d + a * 25 - prio;
    if (s < bs && !lineBlocked(origin.x, origin.y, origin.z, x, y, z)) { bs = s; best = { obj, x, y, z }; }
  };
  for (const c of game.peds) {
    if (c.dead || c.vehicle) continue;
    consider(c, c.pos.x, c.pos.y + 1.2, c.pos.z, c.isCop || (c.ai && c.ai.type === 'hostile') || c === missions.active?.target ? 12 : 0);
  }
  if (pop.heli && !pop.heli.dead) consider(pop.heli, pop.heli.pos.x, pop.heli.pos.y, pop.heli.pos.z, 5);
  return best;
}

let target = null;
function playerCombat(dt) {
  player.fireCool -= dt;
  const w = WEAPONS[player.weapon];
  player.gun.visible = !w.melee;
  const firing = input.held.fire && !player.dead;
  const car = player.vehicle;
  const origin = car ? new THREE.Vector3(car.pos.x, 1.4, car.pos.z) : new THREE.Vector3(player.pos.x, player.pos.y + 1.4, player.pos.z);
  const yaw = car ? car.heading : camYaw;
  target = w.melee ? null : findTarget(origin, yaw, w.range * 0.75, car ? 0.7 : 0.55);
  player.aiming = firing && !w.melee;
  if (!firing || player.fireCool > 0) return;
  if (w.melee) {
    if (car) return;
    player.fireCool = w.rate;
    player.punch = 0.3;
    for (const c of game.peds) {
      if (c.dead || c.vehicle) continue;
      const dx = c.pos.x - player.pos.x, dz = c.pos.z - player.pos.z, d = Math.hypot(dx, dz);
      if (d < w.range && (dx * Math.sin(player.heading) + dz * Math.cos(player.heading)) / d > 0.4) {
        hitChar(c, w.dmg, player.pos, true);
        c.knock(dx / d, dz / d, 3);
        game.audio.punch();
        if (!c.dead) game.crime('assault');
        break;
      }
    }
    return;
  }
  if ((save.ammo[player.weapon] || 0) <= 0) {
    game.toast('Keine Munition!');
    player.fireCool = 0.5;
    cycleWeapon();
    return;
  }
  save.ammo[player.weapon]--;
  player.fireCool = w.rate;
  // Zielrichtung: erfasstes Ziel oder Kamerablick
  let aim;
  if (target) aim = new THREE.Vector3(target.x, target.y, target.z);
  else {
    const dir = new THREE.Vector3();
    camera.getWorldDirection(dir);
    if (car) dir.set(Math.sin(car.heading), 0, Math.cos(car.heading));
    const r = raycast(camera.position.clone().addScaledVector(dir, car ? 0 : camDist), dir, 90, player);
    aim = r.point;
  }
  if (!car) player.heading = Math.atan2(aim.x - player.pos.x, aim.z - player.pos.z);
  const muzzle = car
    ? new THREE.Vector3(car.pos.x - Math.cos(car.heading) * 1.2, 1.5, car.pos.z + Math.sin(car.heading) * 1.2)
    : new THREE.Vector3(player.pos.x + Math.sin(player.heading) * 0.6 + Math.cos(player.heading) * 0.3, player.pos.y + 1.45, player.pos.z + Math.cos(player.heading) * 0.6 - Math.sin(player.heading) * 0.3);
  const base = aim.clone().sub(muzzle).normalize();
  for (let i = 0; i < (w.pellets || 1); i++) {
    const d = base.clone();
    d.x += (Math.random() - 0.5) * w.spread * 2; d.y += (Math.random() - 0.5) * w.spread * 2; d.z += (Math.random() - 0.5) * w.spread * 2;
    d.normalize();
    fireShot(muzzle, d, w, player);
  }
  game.audio.shot(w.sound);
  shake = Math.max(shake, w.pellets ? 0.25 : 0.08);
  game.crime('shoot');
  pop.panic(player.pos, 35);
}

function cycleWeapon() {
  const owned = ORDER.filter((k) => save.owned.includes(k) && (k === 'fist' || save.ammo[k] > 0));
  const i = owned.indexOf(player.weapon);
  player.weapon = owned[(i + 1) % owned.length] || 'fist';
}

// Schüsse von NPCs (Polizei, Gegner, Hubschrauber) auf den Spieler
game.npcShoot = (shooter, accuracy, dmg) => {
  if (player.dead) return;
  const T = player.vehicle ? player.vehicle.pos : player.pos;
  const o = shooter.heli ? shooter.pos : new THREE.Vector3(shooter.pos.x + Math.sin(shooter.heading) * 0.5, shooter.pos.y + 1.45, shooter.pos.z + Math.cos(shooter.heading) * 0.5);
  if (shooter.punch !== undefined) shooter.punch = 0;
  const tp = new THREE.Vector3(T.x, (player.vehicle ? 1 : T.y + 1.2), T.z);
  const d = o.distanceTo(tp);
  const hit = Math.random() < accuracy * clamp(1.4 - d / 40, 0.3, 1.2) && !lineBlocked(o.x, o.y, o.z, tp.x, tp.y, tp.z);
  if (hit) {
    if (player.vehicle) { player.vehicle.damage(dmg * 0.7); if (Math.random() < 0.25) damagePlayer(dmg * 0.5); }
    else damagePlayer(dmg);
    game.fx.tracer(o, tp);
  } else {
    tp.x += (Math.random() - 0.5) * 3; tp.y += (Math.random() - 0.5) * 2; tp.z += (Math.random() - 0.5) * 3;
    game.fx.tracer(o, tp);
    game.fx.sparks(tp, 0xcccccc, 2);
  }
  game.audio.shot('pistol', d);
};

let hurtFlash = 0;
function damagePlayer(n) {
  if (player.dead) return;
  player.damage(n);
  hurtFlash = 0.4;
  vibrate(40);
  if (player.dead) wasted();
}

game.explode = (pos, byPlayer = false) => {
  game.fx.explosion(pos);
  const dd = Math.hypot(pos.x - player.pos.x, pos.z - player.pos.z);
  game.audio.explosion();
  shake = Math.max(shake, clamp(1.2 - dd / 60, 0, 1.2));
  vibrate(dd < 30 ? 250 : 60);
  for (const c of game.peds.concat([player])) {
    if (c.dead) continue;
    const P = c.vehicle ? c.vehicle.pos : c.pos;
    const d = Math.hypot(P.x - pos.x, P.z - pos.z);
    if (d < 9) {
      const dmg = (1 - d / 9) * 160;
      if (c === player) { if (!c.vehicle || d < 4) damagePlayer(dmg); }
      else if (!c.vehicle) { const died = c.damage(dmg); c.knock((P.x - pos.x) / (d + 0.1), (P.z - pos.z) / (d + 0.1), 8 * (1 - d / 9)); if (died) onKill(c, byPlayer); }
    } else if (d < 40) pop.flee(c, pos, 10);
  }
  for (const v of game.vehicles) {
    const d = Math.hypot(v.pos.x - pos.x, v.pos.z - pos.z);
    if (d < 9 && d > 0.5) {
      v.damage((1 - d / 9) * 70);
      v.vel.x += (v.pos.x - pos.x) / d * 6; v.vel.z += (v.pos.z - pos.z) / d * 6;
      v.sleep = false;
      if (byPlayer) v.lastByPlayer = true;
    }
  }
};

game.honk = (v) => {
  if (Math.hypot(v.pos.x - player.pos.x, v.pos.z - player.pos.z) < 40) game.audio.horn();
};

// ---------- Pickups (Geld, Munition, Gesundheit) ----------
const pickupGeo = { cash: new THREE.BoxGeometry(0.5, 0.25, 0.3), ammo: new THREE.BoxGeometry(0.4, 0.3, 0.4), health: new THREE.BoxGeometry(0.6, 0.6, 0.2), armor: new THREE.BoxGeometry(0.6, 0.7, 0.2) };
const pickupMat = {
  cash: new THREE.MeshStandardMaterial({ color: 0x4caf50, emissive: 0x2e7d32, emissiveIntensity: 0.6 }),
  ammo: new THREE.MeshStandardMaterial({ color: 0xffc107, emissive: 0x8d6e00, emissiveIntensity: 0.6 }),
  health: new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xff1744, emissiveIntensity: 0.7 }),
  armor: new THREE.MeshStandardMaterial({ color: 0x2196f3, emissive: 0x0d47a1, emissiveIntensity: 0.7 }),
};
function addPickup(kind, x, z, amount, respawn = 0) {
  const m = new THREE.Mesh(pickupGeo[kind], pickupMat[kind]);
  m.position.set(x, groundHeight(x, z) + 0.6, z);
  scene.add(m);
  game.pickups.push({ kind, x, z, amount, mesh: m, ttl: respawn ? Infinity : 40, respawn, wait: 0 });
}
addPickup('health', PLACES.hospital.x, PLACES.hospital.z - 3, 50, 30);
addPickup('armor', PLACES.police.x - 3, PLACES.police.z, 50, 60);
for (const [bx, bz] of [[1, 1], [7, 5], [3, 7], [4, 4]]) {
  const r = blockRect(bx, bz);
  addPickup('health', (r.x0 + r.x1) / 2 + 9, (r.z0 + r.z1) / 2, 40, 45);
}
function updatePickups(dt) {
  const P = player.vehicle ? player.vehicle.pos : player.pos;
  game.pickups = game.pickups.filter((p) => {
    p.mesh.rotation.y += dt * 2;
    if (p.wait > 0) { p.wait -= dt; p.mesh.visible = p.wait <= 0; return true; }
    p.ttl -= dt;
    if (p.ttl <= 0) { scene.remove(p.mesh); return false; }
    if (!player.dead && Math.hypot(P.x - p.x, P.z - p.z) < 1.6) {
      if (p.kind === 'health' && player.health >= 100) return true;
      if (p.kind === 'armor' && player.armor >= 100) return true;
      if (p.kind === 'cash') { game.addMoney(p.amount); game.audio.coin(); }
      else if (p.kind === 'ammo') { save.ammo.pistol += p.amount; game.toast(`+${p.amount} Pistolenmunition`); game.audio.coin(); }
      else if (p.kind === 'health') { player.health = Math.min(100, player.health + p.amount); game.audio.coin(); }
      else if (p.kind === 'armor') { player.armor = Math.min(100, (player.armor || 0) + p.amount); game.audio.coin(); }
      if (p.respawn) { p.wait = p.respawn; p.mesh.visible = false; return true; }
      scene.remove(p.mesh);
      return false;
    }
    return true;
  });
}

// ---------- Fahrzeuge betreten / verlassen ----------
game.enterVehicle = (v, silent) => {
  player.vehicle = v;
  v.driver = player;
  v.ai = null;
  v.parked = false;
  v.sleep = false;
  player.aiming = false;
  if (!silent) {
    $('carname').textContent = v.t.name;
    $('carname').classList.add('show');
    setTimeout(() => $('carname').classList.remove('show'), 2500);
  }
  camYawFollow = 0;
};
game.exitVehicle = () => {
  const v = player.vehicle;
  if (!v) return;
  const f = v.fwd;
  const side = v.t.wid / 2 + 0.7;
  const p = { x: v.pos.x + f.z * side, z: v.pos.z - f.x * side };
  collideCircle(p, 0.4, 0.5);
  player.vehicle = null;
  v.driver = null;
  player.setPos(p.x, p.z);
  player.vel.set(0, 0, 0);
  player.heading = v.heading;
};

function tryEnter() {
  if (player.vehicle) {
    if (player.vehicle.speed < 8) game.exitVehicle();
    return;
  }
  let best = null, bd = 1e9;
  for (const v of game.vehicles) {
    if (v.destroyed) continue;
    const d = Math.hypot(v.pos.x - player.pos.x, v.pos.z - player.pos.z);
    if (d < v.t.len / 2 + 2 && d < bd && v.speed < 7) { bd = d; best = v; }
  }
  if (!best) return;
  const v = best;
  if (v.driver && v.driver !== player) {
    // Fahrer rauszerren
    const dr = v.driver;
    dr.vehicle = null;
    v.driver = null;
    const f = v.fwd, side = v.t.wid / 2 + 0.8;
    dr.setPos(v.pos.x + f.z * side, v.pos.z - f.x * side);
    collideCircle(dr.pos, 0.4, 0.5);
    dr.knock(f.z, -f.x, 3);
    dr.ai = { type: 'flee', fx: player.pos.x, fz: player.pos.z, t: 12 };
    game.crime('steal');
    game.audio.punch();
  }
  if (v.ai && v.ai.type === 'police' && v.ai.crew > 0) pop.exitCops(v);
  if (v.kind === 'police' && !v.mission) game.crime('steal_cop');
  game.enterVehicle(v);
}

game.spray = () => {
  const v = player.vehicle;
  if (!v) { game.toast('Die Lackiererei ist nur für Fahrzeuge.'); return; }
  if (save.money < 250) { game.toast('Neu lackieren kostet $250.'); return; }
  game.addMoney(-250);
  v.health = v.t.hp; v.burning = 0;
  if (!v.mission && v.kind !== 'police') {
    const nv = new Vehicle(scene, v.kind);
    nv.pos.copy(v.pos); nv.heading = v.heading; nv.vel.set(0, 0, 0);
    v.remove();
    game.vehicles[game.vehicles.indexOf(v)] = nv;
    player.vehicle = null;
    game.enterVehicle(nv, true);
  }
  game.setWanted(0);
  game.toast('Neu lackiert & repariert. Fahndung gelöscht! (-$250)');
  game.audio.jingle(true);
};

// ---------- Tod & Festnahme ----------
function wasted() {
  game.state = 'wasted'; game.stateT = 4.5;
  game.big('K.O.', 4.5, '#ff5252', 'Du bist im Krankenhaus aufgewacht.');
  canvas.style.filter = 'grayscale(1) brightness(0.8)';
  vibrate(400);
}
game.bust = () => {
  if (game.state !== 'play' || player.dead) return;
  game.state = 'busted'; game.stateT = 4;
  player.hands = true;
  if (player.vehicle) player.vehicle.vel.set(0, 0, 0);
  game.big('FESTGENOMMEN', 4, '#64b5f6', 'Die Polizei hat dich geschnappt.');
  canvas.style.filter = 'grayscale(0.7) brightness(0.8)';
};
function respawn() {
  const wasBusted = game.state === 'busted';
  if (player.vehicle) game.exitVehicle();
  if (missions.active) missions.end(false, wasBusted ? 'Du wurdest festgenommen.' : 'Du wurdest ausgeschaltet.');
  const p = wasBusted ? PLACES.police : PLACES.hospital;
  player.setPos(p.x, p.z + (wasBusted ? 0 : 2));
  player.dead = false; player.health = 100; player.hands = false;
  player.group.rotation.x = 0;
  if (wasBusted) {
    const lost = Math.min(save.money, 500);
    game.addMoney(-lost);
    save.ammo = { pistol: 0, smg: 0, shotgun: 0 };
    player.weapon = 'fist';
    game.toast(`Kaution: ${fmt(lost)} · Waffen beschlagnahmt`);
  } else {
    const lost = Math.min(save.money, 400);
    game.addMoney(-lost);
    player.armor = 0;
    game.toast(`Krankenhausrechnung: ${fmt(lost)}`);
  }
  game.setWanted(0);
  for (const c of game.peds) if (c.isCop) { c.remove(); c.gone = true; }
  game.peds = game.peds.filter((c) => !c.gone);
  game.state = 'play';
  canvas.style.filter = '';
  game.persist();
}

// ---------- Eingabe ----------
const input = { mx: 0, my: 0, lookX: 0, lookY: 0, held: {}, pressed: {} };
let lastLookInput = 0;
const stick = { id: null, ox: 0, oy: 0 };
const looks = new Map();
const stickEl = $('stick'), knob = $('knob');
$('stickzone').addEventListener('pointerdown', (e) => {
  if (stick.id !== null) return;
  stick.id = e.pointerId;
  const r = $('stickzone').getBoundingClientRect();
  stickEl.style.left = (e.clientX - r.left - 65) + 'px';
  stickEl.style.bottom = (r.bottom - e.clientY - 65) + 'px';
  stick.ox = e.clientX; stick.oy = e.clientY;
  try { e.target.setPointerCapture(e.pointerId); } catch { /* synthetischer Pointer */ }
});
$('lookzone').addEventListener('pointerdown', (e) => {
  looks.set(e.pointerId, { x: e.clientX, y: e.clientY });
  try { e.target.setPointerCapture(e.pointerId); } catch { /* synthetischer Pointer */ }
});
window.addEventListener('pointermove', (e) => {
  if (e.pointerId === stick.id) {
    let dx = e.clientX - stick.ox, dy = e.clientY - stick.oy;
    const l = Math.hypot(dx, dy), R = 55;
    if (l > R) { dx *= R / l; dy *= R / l; }
    knob.style.transform = `translate(${dx}px,${dy}px)`;
    input.mx = dx / R; input.my = dy / R;
  } else if (looks.has(e.pointerId)) {
    const l = looks.get(e.pointerId);
    input.lookX += e.clientX - l.x; input.lookY += e.clientY - l.y;
    l.x = e.clientX; l.y = e.clientY;
  }
});
const endPointer = (e) => {
  if (e.pointerId === stick.id) {
    stick.id = null; input.mx = input.my = 0;
    knob.style.transform = '';
    stickEl.style.left = ''; stickEl.style.bottom = '';
  }
  looks.delete(e.pointerId);
};
window.addEventListener('pointerup', endPointer);
window.addEventListener('pointercancel', endPointer);
for (const b of document.querySelectorAll('[data-b]')) {
  const k = b.dataset.b;
  b.addEventListener('pointerdown', (e) => {
    e.stopPropagation();
    input.held[k] = true; input.pressed[k] = true;
    b.classList.add('on');
    try { b.setPointerCapture(e.pointerId); } catch { /* synthetischer Pointer */ }
    if (k === 'sprint') { sprintToggle = !sprintToggle; }
  });
  const up = () => { input.held[k] = false; b.classList.remove('on'); };
  b.addEventListener('pointerup', up);
  b.addEventListener('pointercancel', up);
  b.addEventListener('lostpointercapture', up);
}
let sprintToggle = false;
$('weapon').addEventListener('pointerdown', () => cycleWeapon());
$('minimap').addEventListener('pointerdown', () => openMap());
$('btn-pause').addEventListener('pointerdown', () => openMenu());
$('btn-radio').addEventListener('pointerdown', () => {
  game.audio.radioOn = !game.audio.radioOn;
  game.toast(game.audio.radioOn ? '📻 Neon FM' : 'Radio aus');
});
let camFar = false;
$('btn-cam').addEventListener('pointerdown', () => { camFar = !camFar; });

// Tastatur (zum Testen am PC)
const keys = {};
const keyMap = { KeyF: 'enter', Space: 'jump', KeyJ: 'fire', ControlLeft: 'fire', ShiftLeft: 'sprint', KeyH: 'horn' };
window.addEventListener('keydown', (e) => {
  keys[e.code] = true;
  if (keyMap[e.code]) { input.held[keyMap[e.code]] = true; if (!e.repeat) input.pressed[keyMap[e.code]] = true; }
  if (e.code === 'Tab') { e.preventDefault(); cycleWeapon(); }
  if (e.code === 'KeyM') openMap();
  if (e.code === 'Escape') { if (!$('menu').classList.contains('hidden')) closeMenu(); else openMenu(); }
});
window.addEventListener('keyup', (e) => { keys[e.code] = false; if (keyMap[e.code]) input.held[keyMap[e.code]] = false; });
canvas.addEventListener('mousedown', (e) => { if (e.button === 0) input.held.fire = true; });
window.addEventListener('mouseup', () => { input.held.fire = false; });

// ---------- Kamera ----------
let camYaw = player.heading, camPitch = 0.28, camDist = 4.6, camYawFollow = 0, shake = 0;
const camPos = new THREE.Vector3();
const camLook = new THREE.Vector3();
function updateCamera(dt) {
  const kx = (keys.KeyE ? 1 : 0) - (keys.KeyQ ? 1 : 0);
  input.lookX += kx * 500 * dt;
  if (input.lookX || input.lookY) lastLookInput = game.clock;
  camYaw -= input.lookX * 0.0065;
  camPitch = clamp(camPitch + input.lookY * 0.004, -0.35, 1.2);
  input.lookX = input.lookY = 0;

  const car = player.vehicle;
  let tx, ty, tz, dist;
  if (car) {
    tx = car.pos.x; ty = car.pos.y + car.t.hei + 0.4; tz = car.pos.z;
    dist = (camFar ? 9 : 5.5) + car.t.len * 0.55;
    // Kamera schwenkt automatisch hinter das Auto
    if (game.clock - lastLookInput > 1.2) {
      const fv = car.vel.x * car.fwd.x + car.vel.z * car.fwd.z;
      const want = fv < -2 ? car.heading + Math.PI : car.heading;
      camYawFollow = Math.min(1, camYawFollow + dt * 0.8);
      camYaw += angleDiff(camYaw, want) * Math.min(1, dt * 3.5 * camYawFollow);
      camPitch = lerp(camPitch, 0.2, dt * 2);
    } else camYawFollow = 0;
    camera.fov = lerp(camera.fov, 65 + clamp(car.speed * 0.4, 0, 20), dt * 3);
  } else {
    tx = player.pos.x; ty = player.pos.y + 1.65; tz = player.pos.z;
    dist = camFar ? 7.5 : camDist;
    camera.fov = lerp(camera.fov, player.aiming ? 55 : 65, dt * 6);
    if (player.dead) dist = 7;
  }
  camera.updateProjectionMatrix();
  const fx = Math.sin(camYaw), fz = Math.cos(camYaw);
  const rx = -fz, rz = fx;
  const sh = car ? 0 : 0.55; // über die Schulter
  const lx = tx + rx * sh, lz = tz + rz * sh;
  let cx = lx - fx * dist * Math.cos(camPitch), cy = ty + dist * Math.sin(camPitch), cz = lz - fz * dist * Math.cos(camPitch);
  // nicht durch Wände schauen
  const blk = lineBlocked(lx, ty, lz, cx, cy, cz, 0.5);
  if (blk) {
    const t = Math.max(0.12, blk - 0.08);
    cx = lx + (cx - lx) * t; cy = ty + (cy - ty) * t; cz = lz + (cz - lz) * t;
  }
  cy = Math.max(cy, groundHeight(cx, cz) + 0.4);
  const k = car ? Math.min(1, dt * 12) : 1;
  camPos.set(lerp(camPos.x, cx, k), lerp(camPos.y, cy, k), lerp(camPos.z, cz, k));
  camLook.set(lx + fx * 2, ty + 0.1 - (car ? 0.6 : 0), lz + fz * 2);
  camera.position.copy(camPos);
  if (shake > 0) {
    camera.position.x += (Math.random() - 0.5) * shake * 0.6;
    camera.position.y += (Math.random() - 0.5) * shake * 0.6;
    shake = Math.max(0, shake - dt * 2.5);
  }
  camera.lookAt(camLook);
}

// ---------- Spieler ----------
function updatePlayer(dt) {
  if (player.dead) { player.animate(dt); return; }
  if (game.state === 'busted') { player.move(dt, 0, 0, 0); player.animate(dt); if (player.vehicle) player.vehicle.drive(dt, 0, 0, true); return; }
  const kmx = (keys.KeyD || keys.ArrowRight ? 1 : 0) - (keys.KeyA || keys.ArrowLeft ? 1 : 0);
  const kmy = (keys.KeyS || keys.ArrowDown ? 1 : 0) - (keys.KeyW || keys.ArrowUp ? 1 : 0);
  const mx = clamp(input.mx + kmx, -1, 1), my = clamp(input.my + kmy, -1, 1);
  if (input.pressed.enter) tryEnter();
  const car = player.vehicle;
  if (car) {
    let thr = 0;
    if (input.held.gas || (keys.KeyW || keys.ArrowUp)) thr = 1;
    else if (input.held.brake || (keys.KeyS || keys.ArrowDown)) thr = -1;
    else if (input.my < -0.35) thr = clamp(-input.my * 1.3, 0, 1);
    else if (input.my > 0.5) thr = -1;
    const hand = input.held.hand || keys.Space;
    car.throttle = Math.max(0, thr);
    const before = car.speed;
    car.drive(dt, thr, mx, hand);
    if (car.lastImpact > 5) { game.audio.crash(car.lastImpact); shake = Math.max(shake, car.lastImpact / 30); vibrate(Math.min(150, car.lastImpact * 6)); }
    if (input.pressed.horn) { game.audio.horn(); pop.panic(car.pos, 12); }
    void before;
    player.pos.copy(car.pos);
  } else {
    const fx = Math.sin(camYaw), fz = Math.cos(camYaw);
    const dx = fx * -my + -fz * mx, dz = fz * -my + fx * mx;
    const mag = Math.min(1, Math.hypot(mx, my));
    const sprint = sprintToggle || input.held.sprint || mag > 0.95;
    const speed = mag < 0.08 ? 0 : sprint ? 7.2 : 1.8 + 2.4 * mag;
    player.move(dt, dx, dz, speed, input.pressed.jump);
    if (player.aiming) {
      const yaw = target ? Math.atan2(target.x - player.pos.x, target.z - player.pos.z) : camYaw;
      player.heading += angleDiff(player.heading, yaw) * Math.min(1, dt * 15);
    }
    // Passanten wegschieben
    for (const c of game.peds) {
      if (c.vehicle || c.dead) continue;
      const ox = c.pos.x - player.pos.x, oz = c.pos.z - player.pos.z, d = Math.hypot(ox, oz);
      if (d < 0.7 && d > 0.01) { c.pos.x += ox / d * (0.7 - d); c.pos.z += oz / d * (0.7 - d); }
    }
  }
  player.animate(dt);
}

// ---------- Kollisionen Fahrzeug/Fahrzeug und Fahrzeug/Person ----------
function vehicleCollisions() {
  const vs = game.vehicles;
  for (let i = 0; i < vs.length; i++) {
    const a = vs[i];
    for (let j = i + 1; j < vs.length; j++) {
      const b = vs[j];
      if (a.sleep && b.sleep) continue;
      const lim = (a.t.len + b.t.len) / 2 + 1;
      if (Math.abs(a.pos.x - b.pos.x) > lim || Math.abs(a.pos.z - b.pos.z) > lim) continue;
      const fa = a.fwd, fb = b.fwd;
      const oa = a.t.len / 2 - a.radius * 0.8, ob = b.t.len / 2 - b.radius * 0.8;
      for (const sa of [1, 0, -1]) for (const sb of [1, 0, -1]) {
        const ax = a.pos.x + fa.x * oa * sa, az = a.pos.z + fa.z * oa * sa;
        const bx = b.pos.x + fb.x * ob * sb, bz = b.pos.z + fb.z * ob * sb;
        const dx = bx - ax, dz = bz - az, d = Math.hypot(dx, dz), R = a.radius + b.radius;
        if (d >= R || d < 1e-4) continue;
        const nx = dx / d, nz = dz / d, ov = R - d;
        const ma = a.parked ? a.t.mass * 2 : a.t.mass, mb = b.parked ? b.t.mass * 2 : b.t.mass;
        a.pos.x -= nx * ov * mb / (ma + mb); a.pos.z -= nz * ov * mb / (ma + mb);
        b.pos.x += nx * ov * ma / (ma + mb); b.pos.z += nz * ov * ma / (ma + mb);
        const rel = (b.vel.x - a.vel.x) * nx + (b.vel.z - a.vel.z) * nz;
        if (rel < 0) {
          const jimp = -1.3 * rel / (1 / ma + 1 / mb);
          a.vel.x -= nx * jimp / ma; a.vel.z -= nz * jimp / ma;
          b.vel.x += nx * jimp / mb; b.vel.z += nz * jimp / mb;
          const imp = -rel;
          if (imp > 5) {
            a.damage((imp - 4) * 1.6 * mb / ma); b.damage((imp - 4) * 1.6 * ma / mb);
            const pv = player.vehicle;
            if (a === pv || b === pv) {
              game.audio.crash(imp);
              shake = Math.max(shake, imp / 25);
              vibrate(Math.min(200, imp * 8));
              const o = a === pv ? b : a;
              o.lastByPlayer = true;
              if (o.kind === 'police' && o.ai) game.crime('hit_cop');
              if (o.driver && o.driver !== player) pop.flee(o.driver, player.pos);
            }
          }
          a.sleep = b.sleep = false;
          a.parked = b.parked = false;
        }
      }
    }
  }
}

function carHitsPeople() {
  const chars = game.peds.concat([player]);
  for (const v of game.vehicles) {
    if (v.speed < 0.3) continue;
    const c0 = Math.cos(v.heading), s0 = Math.sin(v.heading);
    for (const c of chars) {
      if (c.vehicle || (c.dead && v.speed < 3)) continue;
      const wx = c.pos.x - v.pos.x, wz = c.pos.z - v.pos.z;
      if (Math.abs(wx) > 7 || Math.abs(wz) > 7) continue;
      const lx = wx * c0 - wz * s0, lz = wx * s0 + wz * c0;
      const hw = v.t.wid / 2 + 0.3, hl = v.t.len / 2 + 0.3;
      if (Math.abs(lx) > hw || Math.abs(lz) > hl) continue;
      // Person aus dem Auto-Umriss schieben
      const px = hw - Math.abs(lx), pz = hl - Math.abs(lz);
      let nlx = 0, nlz = 0;
      if (px < pz) nlx = Math.sign(lx) * px; else nlz = Math.sign(lz) * pz;
      c.pos.x += nlx * c0 + nlz * s0; c.pos.z += -nlx * s0 + nlz * c0;
      if (v.speed > 3 && !c.dead) {
        const dmg = v.speed * 3.2;
        const nx = v.vel.x / v.speed, nz = v.vel.z / v.speed;
        if (c === player) { damagePlayer(dmg * 0.5); c.knock(nx, nz, v.speed * 0.5); }
        else {
          const died = c.damage(dmg);
          c.knock(nx, nz, Math.min(14, v.speed * 0.7));
          if (v.driver === player) { if (died) onKill(c, true); else game.crime('assault'); }
          if (!died) pop.flee(c, v.pos, 8);
          if (v === player.vehicle) { vibrate(60); game.audio.punch(); }
        }
        v.vel.multiplyScalar(0.94);
      }
    }
  }
}

function updateVehicles(dt) {
  for (const v of game.vehicles) {
    if (v.burning > 0) {
      v.burning -= dt;
      if (Math.random() < dt * 30) game.fx.fire(new THREE.Vector3(v.pos.x + v.fwd.x * v.t.len * 0.35, v.pos.y + 1.2, v.pos.z + v.fwd.z * v.t.len * 0.35));
      if (v.burning <= 0) {
        v.burning = 0;
        v.setDestroyed();
        game.explode(v.pos.clone(), !!v.lastByPlayer);
        if (v.driver === player) { damagePlayer(500); }
        else if (v.driver) { v.driver.vehicle = null; v.driver.setPos(v.pos.x, v.pos.z); v.driver.damage(999); v.driver = null; if (v.lastByPlayer) game.crime('kill'); }
        if (v.ai && v.ai.type === 'police' && v.lastByPlayer) game.crime('kill_cop');
        v.ai = null;
      }
    } else if (!v.destroyed && v.health < v.t.hp * 0.35 && Math.random() < dt * 8) {
      game.fx.smoke(new THREE.Vector3(v.pos.x + v.fwd.x * v.t.len * 0.4, v.pos.y + 1, v.pos.z + v.fwd.z * v.t.len * 0.4), v.health < v.t.hp * 0.15);
    }
    v.update(dt, game.clock);
  }
}

// ---------- Shop & Menüs ----------
const SHOP = [
  { id: 'pistolA', label: 'Pistolenmunition ×48', price: 120, buy: () => { save.ammo.pistol += 48; if (!save.owned.includes('pistol')) save.owned.push('pistol'); } },
  { id: 'smg', label: 'Maschinenpistole + 120 Schuss', price: 1500, buy: () => { if (!save.owned.includes('smg')) save.owned.push('smg'); save.ammo.smg += 120; player.weapon = 'smg'; } },
  { id: 'smgA', label: 'MP-Munition ×120', price: 250, need: 'smg', buy: () => { save.ammo.smg += 120; } },
  { id: 'shotgun', label: 'Schrotflinte + 24 Patronen', price: 1200, buy: () => { if (!save.owned.includes('shotgun')) save.owned.push('shotgun'); save.ammo.shotgun += 24; player.weapon = 'shotgun'; } },
  { id: 'shotgunA', label: 'Schrotpatronen ×24', price: 180, need: 'shotgun', buy: () => { save.ammo.shotgun += 24; } },
  { id: 'armor', label: 'Schutzweste', price: 300, buy: () => { player.armor = 100; } },
];
game.openShop = () => {
  game.paused = true;
  const box = $('shopitems');
  box.innerHTML = '';
  for (const it of SHOP) {
    if (it.need && !save.owned.includes(it.need)) continue;
    const row = document.createElement('div');
    row.className = 'item';
    row.innerHTML = `<span>${it.label}</span>`;
    const b = document.createElement('button');
    b.textContent = fmt(it.price);
    b.onclick = () => {
      if (save.money < it.price) { b.textContent = 'Zu wenig Geld'; return; }
      game.addMoney(-it.price); it.buy(); game.audio.coin(); game.persist(); game.openShop();
    };
    row.appendChild(b);
    box.appendChild(row);
  }
  const m = document.createElement('div');
  m.style.cssText = 'margin-top:6px;opacity:.8';
  m.textContent = `Guthaben: ${fmt(save.money)}`;
  box.appendChild(m);
  $('shop').classList.remove('hidden');
};

function openMenu() {
  if (!game.started) return;
  game.paused = true;
  $('qv').textContent = ['Niedrig', 'Mittel', 'Hoch'][save.quality];
  $('sv').textContent = save.sound ? 'An' : 'Aus';
  const d = save.done;
  $('stats').innerHTML = `Geld: ${fmt(save.money)}<br>Muscheln: ${save.shells.length}/20<br>Rennen gewonnen: ${d.race || 0} · Lieferungen: ${d.delivery || 0} · Kopfgelder: ${d.bounty || 0} · Taxi: ${d.taxi || 0}`;
  $('menu').classList.remove('hidden');
}
function closeMenu() {
  for (const id of ['menu', 'help', 'shop', 'mapview']) $(id).classList.add('hidden');
  game.paused = false;
  game.audio.init();
}
for (const b of document.querySelectorAll('[data-m]')) {
  b.addEventListener('click', () => {
    const m = b.dataset.m;
    if (m === 'resume' || m === 'closeshop') closeMenu();
    else if (m === 'map') { $('menu').classList.add('hidden'); openMap(); }
    else if (m === 'quality') {
      save.quality = (save.quality + 1) % 3;
      game.persist();
      location.reload();
    } else if (m === 'sound') {
      save.sound = !save.sound; game.audio.setEnabled(save.sound);
      $('sv').textContent = save.sound ? 'An' : 'Aus';
      game.persist();
    } else if (m === 'help') { $('menu').classList.add('hidden'); $('help').classList.remove('hidden'); }
    else if (m === 'back') { $('help').classList.add('hidden'); openMenu(); }
    else if (m === 'reset') {
      if (confirm('Spielstand wirklich löschen?')) { localStorage.removeItem('palmera_save'); location.reload(); }
    }
  });
}

// ---------- Karte & Radar ----------
let waypoint = null;
const PLACE_BLIPS = [
  ...Object.values(PLACES).filter((p) => p.icon),
  { ...PLACES.hospital, icon: '+', color: '#e53935' },
  { ...PLACES.police, icon: 'P', color: '#1e88e5' },
];
function objective() {
  if (missions.objective) return missions.objective;
  return waypoint;
}
function routePoints(from, to) {
  // Weg über das Straßenraster: erst entlang x, dann entlang z
  const a = nearestNode(from.x, from.z), b = nearestNode(to.x, to.z);
  return [[from.x, from.z], [a.i * CELL, a.j * CELL], [b.i * CELL, a.j * CELL], [b.i * CELL, b.j * CELL], [to.x, to.z]];
}
const BLOCK_COL = { park: '#3f7f3a', plaza: '#b9ad8f', parking: '#5b5f66', police: '#4a5f9e', hospital: '#d9d9d9', tower: '#7b8794', office: '#8c8577', res: '#a19480' };
function drawWorld(ctx, routeWidth) {
  ctx.fillStyle = '#d8c08a';
  ctx.fillRect(BOUNDS.x0, BOUNDS.z0, BOUNDS.x1 - BOUNDS.x0, BOUNDS.z1 - BOUNDS.z0);
  ctx.fillStyle = '#4b4f55';
  ctx.fillRect(-HALF, -HALF, SIZE + HALF * 2, SIZE + HALF * 2);
  for (let bx = 0; bx < N; bx++) for (let bz = 0; bz < N; bz++) {
    const r = blockRect(bx, bz);
    ctx.fillStyle = BLOCK_COL[blockType(bx, bz)];
    ctx.fillRect(r.x0, r.z0, r.x1 - r.x0, r.z1 - r.z0);
  }
  const P = player.vehicle ? player.vehicle.pos : player.pos;
  const obj = objective();
  if (obj) {
    const pts = routePoints(P, obj);
    ctx.strokeStyle = missions.objective ? '#ffd600' : '#c56cf0';
    ctx.lineWidth = routeWidth;
    ctx.lineJoin = 'round';
    ctx.beginPath();
    pts.forEach(([x, z], i) => (i ? ctx.lineTo(x, z) : ctx.moveTo(x, z)));
    ctx.stroke();
  }
}
function blip(ctx, x, y, text, color, r = 9) {
  ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fillStyle = color; ctx.fill();
  ctx.lineWidth = 2; ctx.strokeStyle = '#000'; ctx.stroke();
  if (text) {
    ctx.fillStyle = color === '#ffd600' || color === '#ffffff' ? '#000' : '#fff';
    ctx.font = `bold ${r * 1.3}px sans-serif`; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(text, x, y + 1);
  }
}
function dynamicBlips() {
  const out = [];
  for (const c of game.peds) {
    if (c.dead || c.vehicle) continue;
    if (c.isCop && game.wanted) out.push([c.pos.x, c.pos.z, Math.floor(game.clock * 4) % 2 ? '#ff1744' : '#2979ff', 4]);
    else if (c.ai && c.ai.type === 'hostile') out.push([c.pos.x, c.pos.z, '#ff5252', 4]);
  }
  for (const v of game.vehicles) {
    if (v.ai && v.ai.type === 'police' && game.wanted) out.push([v.pos.x, v.pos.z, Math.floor(game.clock * 4) % 2 ? '#2979ff' : '#ff1744', 6]);
    if (v.ai && v.ai.type === 'race') out.push([v.pos.x, v.pos.z, '#00e5ff', 6]);
    if (v.mission && v !== player.vehicle && !(v.ai && v.ai.type === 'race')) out.push([v.pos.x, v.pos.z, '#64b5f6', 7]);
  }
  const M = missions.active;
  if (M && M.target && !M.target.dead) out.push([M.target.pos.x, M.target.pos.z, '#ff1744', 7]);
  if (M && M.fare && M.stage === 1) out.push([M.fare.pos.x, M.fare.pos.z, '#64b5f6', 7]);
  if (pop.heli && !pop.heli.dead) out.push([pop.heli.pos.x, pop.heli.pos.z, '#ff1744', 7]);
  return out;
}

const mm = $('minimap'), mctx = mm.getContext('2d');
function drawRadar() {
  const S = mm.width, C = S / 2;
  const car = player.vehicle;
  const range = car ? 110 + clamp(car.speed * 2, 0, 80) : 90;
  const sc = C / range;
  const P = car ? car.pos : player.pos;
  const cs = Math.cos(camYaw), sn = Math.sin(camYaw);
  const a = -cs * sc, b = -sn * sc, c = sn * sc, d = -cs * sc;
  const tr = (x, z) => [a * (x - P.x) + c * (z - P.z) + C, b * (x - P.x) + d * (z - P.z) + C];
  mctx.setTransform(1, 0, 0, 1, 0, 0);
  mctx.clearRect(0, 0, S, S);
  mctx.save();
  mctx.beginPath(); mctx.arc(C, C, C, 0, Math.PI * 2); mctx.clip();
  mctx.fillStyle = '#2a6f97'; mctx.fillRect(0, 0, S, S);
  mctx.setTransform(a, b, c, d, C - (a * P.x + c * P.z), C - (b * P.x + d * P.z));
  drawWorld(mctx, 4);
  mctx.setTransform(1, 0, 0, 1, 0, 0);
  for (const [x, z, col, r] of dynamicBlips()) {
    const [sx, sy] = tr(x, z);
    blip(mctx, sx, sy, '', col, r * 1.4);
  }
  mctx.restore();
  const edge = (x, z, text, col, r) => {
    let [sx, sy] = tr(x, z);
    const dx = sx - C, dy = sy - C, l = Math.hypot(dx, dy);
    if (l > C - 14) { sx = C + dx / l * (C - 14); sy = C + dy / l * (C - 14); }
    blip(mctx, sx, sy, text, col, r);
  };
  if (!missions.active) for (const p of PLACE_BLIPS) {
    const [sx, sy] = tr(p.x, p.z);
    if (Math.hypot(sx - C, sy - C) < C - 12 || p.mission) edge(p.x, p.z, p.icon, p.color, 13);
  }
  const obj = objective();
  if (obj) edge(obj.x, obj.z, '', missions.objective ? '#ffd600' : '#c56cf0', 11);
  // Spielerpfeil
  const ph = (car ? car.heading : player.heading) - camYaw;
  mctx.save();
  mctx.translate(C, C);
  mctx.rotate(-ph);
  mctx.beginPath(); mctx.moveTo(0, -16); mctx.lineTo(11, 12); mctx.lineTo(0, 6); mctx.lineTo(-11, 12); mctx.closePath();
  mctx.fillStyle = '#fff'; mctx.fill(); mctx.lineWidth = 3; mctx.strokeStyle = '#000'; mctx.stroke();
  mctx.restore();
  // Norden
  const [nx, ny] = [C + (-sn) * (C - 16) * -1, C - cs * (C - 16) * -1];
  void nx; void ny;
}

const bm = $('bigmap'), bctx = bm.getContext('2d');
let mapView = null;
function openMap() {
  if (!game.started) return;
  game.paused = true;
  $('mapview').classList.remove('hidden');
  drawBigMap();
}
function drawBigMap() {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  bm.width = bm.clientWidth * dpr; bm.height = bm.clientHeight * dpr;
  const W = bm.width, H = bm.height;
  const ww = BOUNDS.x1 - BOUNDS.x0 + 40, wh = BOUNDS.z1 - BOUNDS.z0 + 40;
  const s = Math.min(W / ww, H / wh);
  const ox = (W - ww * s) / 2 - (BOUNDS.x0 - 20) * s, oy = (H - wh * s) / 2 - (BOUNDS.z0 - 20) * s;
  mapView = { s, ox, oy, dpr };
  bctx.setTransform(1, 0, 0, 1, 0, 0);
  bctx.fillStyle = '#2a6f97'; bctx.fillRect(0, 0, W, H);
  bctx.setTransform(s, 0, 0, s, ox, oy);
  drawWorld(bctx, 4);
  bctx.setTransform(1, 0, 0, 1, 0, 0);
  const tr = (x, z) => [x * s + ox, z * s + oy];
  for (const p of PLACE_BLIPS) { const [x, y] = tr(p.x, p.z); blip(bctx, x, y, p.icon, p.color, 11 * dpr); }
  for (const sh of missions.shells) if (!sh.got && save.shells.length >= 10) { const [x, y] = tr(sh.mesh.position.x, sh.mesh.position.z); blip(bctx, x, y, '', '#ff80ab', 4 * dpr); }
  for (const [x, z, col, r] of dynamicBlips()) { const [sx, sy] = tr(x, z); blip(bctx, sx, sy, '', col, r * dpr); }
  const obj = objective();
  if (obj) { const [x, y] = tr(obj.x, obj.z); blip(bctx, x, y, '', missions.objective ? '#ffd600' : '#c56cf0', 9 * dpr); }
  const P = player.vehicle ? player.vehicle.pos : player.pos;
  const [px, py] = tr(P.x, P.z);
  const h = player.vehicle ? player.vehicle.heading : player.heading;
  bctx.save(); bctx.translate(px, py); bctx.rotate(Math.PI - h);
  bctx.beginPath(); bctx.moveTo(0, -14 * dpr); bctx.lineTo(10 * dpr, 10 * dpr); bctx.lineTo(0, 5 * dpr); bctx.lineTo(-10 * dpr, 10 * dpr); bctx.closePath();
  bctx.fillStyle = '#fff'; bctx.fill(); bctx.lineWidth = 3; bctx.strokeStyle = '#000'; bctx.stroke();
  bctx.restore();
  bctx.fillStyle = '#fff'; bctx.font = `bold ${16 * dpr}px sans-serif`; bctx.textAlign = 'left';
  bctx.fillText('PALMERA CITY', 16 * dpr, 30 * dpr);
}
bm.addEventListener('pointerdown', (e) => {
  if (!mapView) return;
  const { s, ox, oy, dpr } = mapView;
  const x = (e.offsetX * dpr - ox) / s, z = (e.offsetY * dpr - oy) / s;
  if (x < BOUNDS.x0 || x > BOUNDS.x1 || z < BOUNDS.z0 || z > BOUNDS.z1) { closeMenu(); return; }
  waypoint = waypoint && Math.hypot(waypoint.x - x, waypoint.z - z) < 20 ? null : { x, z };
  drawBigMap();
});
$('mapclose').addEventListener('click', closeMenu);

// ---------- HUD ----------
let hudT = 0, shownMoney = save.money;
const starEls = [...document.querySelectorAll('#stars b')];
const reticle = $('reticle');
function updateHud(dt) {
  if (toastT > 0) { toastT -= dt; if (toastT <= 0) $('toast').classList.remove('show'); }
  if (bigT > 0) { bigT -= dt; if (bigT <= 0) $('big').classList.remove('show'); }
  shownMoney += (save.money - shownMoney) * Math.min(1, dt * 6);
  if (Math.abs(save.money - shownMoney) < 1) shownMoney = save.money;
  // Ziel-Markierung
  if (target && !player.dead) {
    _v.set(target.x, target.y, target.z).project(camera);
    if (_v.z < 1) {
      reticle.style.display = 'block';
      reticle.style.left = ((_v.x + 1) / 2 * window.innerWidth) + 'px';
      reticle.style.top = ((1 - _v.y) / 2 * window.innerHeight) + 'px';
    } else reticle.style.display = 'none';
  } else reticle.style.display = 'none';
  hudT -= dt;
  if (hudT > 0) return;
  hudT = 0.1;
  $('money').textContent = fmt(shownMoney);
  const h = Math.floor(world.time), m = Math.floor((world.time % 1) * 60);
  $('clock').textContent = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  const w = WEAPONS[player.weapon];
  $('wicon').textContent = w.icon;
  $('ammo').textContent = w.melee ? '' : String(save.ammo[player.weapon] || 0);
  $('hp').firstChild.style.width = player.health + '%';
  $('ar').style.display = player.armor > 0 ? '' : 'none';
  $('ar').firstChild.style.width = (player.armor || 0) + '%';
  starEls.forEach((s, i) => s.classList.toggle('on', i < game.wanted));
  $('stars').classList.toggle('blink', game.wanted > 0 && game.clock - game.lastSeen > 2);
  const car = player.vehicle;
  $('foot').classList.toggle('hidden', !!car);
  $('drive').classList.toggle('hidden', !car);
  $('btn-radio').classList.toggle('hidden', !car);
  $('speedo').innerHTML = car ? `${Math.round(car.speed * 3.6)}<small> km/h</small>` : '';
  // Einsteigen-Knopf nur hervorheben, wenn ein Auto in der Nähe ist
  if (!car) {
    const near = game.vehicles.some((v) => !v.destroyed && Math.hypot(v.pos.x - player.pos.x, v.pos.z - player.pos.z) < v.t.len / 2 + 2);
    document.querySelector('#foot [data-b=enter]').classList.toggle('dim', !near);
  }
  document.querySelector('#foot [data-b=sprint]').classList.toggle('on', sprintToggle);
  document.querySelector('#foot [data-b=fire]').textContent = w.melee ? '👊' : '🔫';
  drawRadar();
  canvas.style.boxShadow = '';
  document.body.style.background = hurtFlash > 0 ? '#600' : '#000';
}
const vignette = document.createElement('div');
vignette.style.cssText = 'position:fixed;inset:0;pointer-events:none;box-shadow:inset 0 0 120px 30px rgba(200,0,0,.0);transition:box-shadow .15s';
document.body.appendChild(vignette);

// ---------- Hauptschleife ----------
function step(dt) {
  if (game.state !== 'play') {
    game.stateT -= dt;
    dt *= 0.4;
    if (game.stateT <= 0) respawn();
  }
  game.clock += dt;
  updatePlayer(dt);
  if (input.pressed.weapon) cycleWeapon();
  playerCombat(dt);
  pop.update(dt);
  vehicleCollisions();
  carHitsPeople();
  updateVehicles(dt);
  missions.update(dt);
  updatePickups(dt);
  updateWanted(dt);
  world.update(dt, player.vehicle ? player.vehicle.pos : player.pos, camera.position);
  game.fx.update(dt);
  updateCamera(dt);
  const sirenNear = game.vehicles.reduce((m, v) => (v.sirenOn ? Math.max(m, clamp(1 - Math.hypot(v.pos.x - camera.position.x, v.pos.z - camera.position.z) / 120, 0, 1)) : m), 0);
  game.audio.update(dt, player.vehicle, sirenNear, game.clock);
  if (hurtFlash > 0) hurtFlash -= dt;
  vignette.style.boxShadow = `inset 0 0 120px 30px rgba(200,0,0,${hurtFlash > 0 ? 0.55 : player.health < 25 ? 0.3 : 0})`;
  updateHud(dt);
  input.pressed = {};
  autosave -= dt;
  if (autosave <= 0) { autosave = 30; game.persist(); }
}
window.__sim = (seconds) => { for (let t = 0; t < seconds; t += 1 / 30) step(1 / 30); renderer.render(scene, camera); };

let last = performance.now(), fpsAcc = 0, fpsN = 0, autosave = 30;
function frame(now) {
  requestAnimationFrame(frame);
  let dt = Math.min(0.05, (now - last) / 1000);
  last = now;
  if (!game.started) return;
  if (game.paused) { input.pressed = {}; return; }

  // Dynamische Auflösung, falls die Bildrate einbricht
  fpsAcc += dt; fpsN++;
  if (fpsAcc > 4) {
    const fps = fpsN / fpsAcc;
    if (fps < 40 && pixelRatio > 1) { pixelRatio = Math.max(1, pixelRatio - 0.25); renderer.setPixelRatio(pixelRatio); }
    else if (fps > 57 && pixelRatio < Math.min(window.devicePixelRatio || 1, maxPR)) { pixelRatio = Math.min(maxPR, pixelRatio + 0.1); renderer.setPixelRatio(pixelRatio); }
    fpsAcc = 0; fpsN = 0;
  }

  step(dt);
  renderer.render(scene, camera);
}

// ---------- Start ----------
$('loading').classList.add('hidden');
renderer.compile(scene, camera);
updateCamera(0.016);
renderer.render(scene, camera);
$('play').addEventListener('click', () => {
  game.audio.init();
  $('title').classList.add('hidden');
  $('hud').classList.remove('hidden');
  $('controls').classList.remove('hidden');
  game.started = true;
  game.paused = false;
  if (!save.seenHelp) {
    save.seenHelp = true;
    game.toast('Willkommen in Palmera City! Gelbe Marker = Missionen. Radar antippen = Karte.');
  }
  if (document.documentElement.requestFullscreen && !window.Android) document.documentElement.requestFullscreen().catch(() => {});
});
requestAnimationFrame(frame);

// Android-Hooks
window.onNativeBack = () => {
  if (!game.started) return false;
  if (game.paused) closeMenu(); else openMenu();
  return true;
};
window.onNativePause = () => { game.persist(); if (game.started) openMenu(); game.audio.ctx?.suspend(); };
window.onNativeResume = () => { game.audio.ctx?.resume(); };
document.addEventListener('visibilitychange', () => { if (document.hidden) window.onNativePause(); });
window.__game = game;
