// Komplett synthetischer Sound über WebAudio (keine Audiodateien nötig)
export class Audio {
  constructor() {
    this.ctx = null;
    this.enabled = true;
    this.radioOn = true;
  }

  init() {
    if (this.ctx) { if (this.ctx.state === 'suspended') this.ctx.resume(); return; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    const ctx = this.ctx = new AC();
    this.master = ctx.createGain();
    this.master.gain.value = this.enabled ? 0.8 : 0;
    this.master.connect(ctx.destination);
    // Rauschpuffer für Schüsse/Explosionen
    const len = ctx.sampleRate * 2;
    this.noise = ctx.createBuffer(1, len, ctx.sampleRate);
    const d = this.noise.getChannelData(0);
    for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;

    // Motor
    this.eng = ctx.createOscillator(); this.eng.type = 'sawtooth';
    this.eng2 = ctx.createOscillator(); this.eng2.type = 'square';
    this.engF = ctx.createBiquadFilter(); this.engF.type = 'lowpass'; this.engF.frequency.value = 600;
    this.engG = ctx.createGain(); this.engG.gain.value = 0;
    this.eng.connect(this.engF); this.eng2.connect(this.engF); this.engF.connect(this.engG); this.engG.connect(this.master);
    this.eng.start(); this.eng2.start();

    // Sirene
    this.sir = ctx.createOscillator(); this.sir.type = 'triangle';
    this.sirG = ctx.createGain(); this.sirG.gain.value = 0;
    this.sir.connect(this.sirG); this.sirG.connect(this.master);
    this.sir.start();

    // Reifenquietschen
    this.skid = ctx.createBufferSource(); this.skid.buffer = this.noise; this.skid.loop = true;
    const sf = ctx.createBiquadFilter(); sf.type = 'bandpass'; sf.frequency.value = 2400; sf.Q.value = 6;
    this.skidG = ctx.createGain(); this.skidG.gain.value = 0;
    this.skid.connect(sf); sf.connect(this.skidG); this.skidG.connect(this.master);
    this.skid.start();

    this.radioG = ctx.createGain(); this.radioG.gain.value = 0;
    this.radioG.connect(this.master);
    this.step = 0;
    this.nextNote = 0;
  }

  setEnabled(on) {
    this.enabled = on;
    if (this.master) this.master.gain.value = on ? 0.8 : 0;
  }

  burst(dur, freq, vol, type = 'lowpass', q = 1) {
    const ctx = this.ctx;
    if (!ctx) return;
    const s = ctx.createBufferSource(); s.buffer = this.noise;
    const f = ctx.createBiquadFilter(); f.type = type; f.frequency.value = freq; f.Q.value = q;
    const g = ctx.createGain();
    const t = ctx.currentTime;
    g.gain.setValueAtTime(vol, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    s.connect(f); f.connect(g); g.connect(this.master);
    s.start(t, Math.random()); s.stop(t + dur + 0.05);
  }

  tone(freq, dur, vol, type = 'sine', slide = 0, dest) {
    const ctx = this.ctx;
    if (!ctx) return;
    const o = ctx.createOscillator(); o.type = type;
    const g = ctx.createGain();
    const t = ctx.currentTime;
    o.frequency.setValueAtTime(freq, t);
    if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(20, freq * slide), t + dur);
    g.gain.setValueAtTime(vol, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    o.connect(g); g.connect(dest || this.master);
    o.start(t); o.stop(t + dur + 0.05);
  }

  shot(kind, dist = 0) {
    const v = Math.max(0.05, 1 - dist / 80);
    if (kind === 'shotgun') { this.burst(0.35, 1400, 0.9 * v); this.tone(90, 0.2, 0.6 * v, 'sine', 0.4); }
    else if (kind === 'smg') { this.burst(0.09, 2600, 0.45 * v); }
    else { this.burst(0.16, 2000, 0.7 * v); this.tone(140, 0.1, 0.4 * v, 'sine', 0.4); }
  }
  punch() { this.burst(0.08, 600, 0.6); }
  hit() { this.tone(220, 0.08, 0.2, 'square', 0.5); }
  crash(v) { this.burst(0.3, 500, Math.min(1, v / 20)); this.burst(0.15, 3000, Math.min(0.5, v / 40), 'highpass'); }
  explosion() { this.burst(1.6, 300, 1.2); this.tone(60, 1.2, 0.9, 'sine', 0.3); }
  horn() { this.tone(392, 0.35, 0.25, 'square'); this.tone(494, 0.35, 0.2, 'square'); }
  coin() { this.tone(988, 0.08, 0.2, 'square'); setTimeout(() => this.tone(1319, 0.2, 0.2, 'square'), 80); }
  jingle(good = true) {
    const notes = good ? [523, 659, 784, 1047] : [392, 330, 262, 196];
    notes.forEach((n, i) => setTimeout(() => this.tone(n, 0.3, 0.2, 'triangle'), i * 140));
  }

  // pro Frame: Motor, Sirene, Radio
  update(dt, car, sirenNear, time) {
    const ctx = this.ctx;
    if (!ctx) return;
    const t = ctx.currentTime;
    if (car) {
      const v = car.speed;
      const gearTop = [8, 16, 26, 38, 52, 80];
      let g = 0; while (g < gearTop.length - 1 && v > gearTop[g]) g++;
      const lo = g ? gearTop[g - 1] : 0;
      const rpm = (v - lo) / (gearTop[g] - lo);
      const f = 38 + rpm * 70 + g * 6;
      this.eng.frequency.setTargetAtTime(f, t, 0.05);
      this.eng2.frequency.setTargetAtTime(f * 0.5, t, 0.05);
      this.engF.frequency.setTargetAtTime(300 + rpm * 900, t, 0.05);
      this.engG.gain.setTargetAtTime(car.destroyed ? 0 : 0.12 + (car.throttle || 0) * 0.08, t, 0.1);
      this.skidG.gain.setTargetAtTime(car.slip > 4 && v > 6 ? Math.min(0.25, car.slip / 40) : 0, t, 0.05);
    } else {
      this.engG.gain.setTargetAtTime(0, t, 0.1);
      this.skidG.gain.setTargetAtTime(0, t, 0.05);
    }
    if (sirenNear > 0) {
      const ph = (time * 0.8) % 1;
      this.sir.frequency.setTargetAtTime(ph < 0.5 ? 700 + ph * 1000 : 1700 - ph * 1000, t, 0.03);
      this.sirG.gain.setTargetAtTime(0.06 * sirenNear, t, 0.1);
    } else this.sirG.gain.setTargetAtTime(0, t, 0.2);

    // Radio „Neon FM“ – kleiner Synthwave-Sequencer
    const radio = car && this.radioOn && !car.destroyed;
    this.radioG.gain.setTargetAtTime(radio ? 0.22 : 0, t, 0.3);
    if (!radio) { this.nextNote = 0; return; }
    const spb = 60 / 108 / 4; // Sechzehntel
    if (this.nextNote < t) this.nextNote = t + 0.05;
    while (this.nextNote < t + 0.15) this.schedule(this.step++, this.nextNote), this.nextNote += spb;
  }

  schedule(step, t) {
    const ctx = this.ctx, out = this.radioG;
    const bar = Math.floor(step / 16) % 4, s = step % 16;
    const roots = [57, 53, 48, 55]; // A F C G
    const midi = (m) => 440 * Math.pow(2, (m - 69) / 12);
    const note = (freq, dur, vol, type, cutoff) => {
      const o = ctx.createOscillator(); o.type = type; o.frequency.value = freq;
      const g = ctx.createGain();
      g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.001, t + dur);
      if (cutoff) {
        const f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = cutoff;
        o.connect(f); f.connect(g);
      } else o.connect(g);
      g.connect(out); o.start(t); o.stop(t + dur + 0.02);
    };
    if (s % 4 === 0) { // Kick
      const o = ctx.createOscillator(), g = ctx.createGain();
      o.frequency.setValueAtTime(140, t); o.frequency.exponentialRampToValueAtTime(40, t + 0.15);
      g.gain.setValueAtTime(0.9, t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.2);
      o.connect(g); g.connect(out); o.start(t); o.stop(t + 0.25);
    }
    if (s === 4 || s === 12) { // Snare
      const n = ctx.createBufferSource(); n.buffer = this.noise;
      const f = ctx.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = 1500;
      const g = ctx.createGain(); g.gain.setValueAtTime(0.35, t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.18);
      n.connect(f); f.connect(g); g.connect(out); n.start(t, Math.random()); n.stop(t + 0.2);
    }
    if (s % 2 === 1) { // Hi-Hat
      const n = ctx.createBufferSource(); n.buffer = this.noise;
      const f = ctx.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = 7000;
      const g = ctx.createGain(); g.gain.setValueAtTime(0.08, t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.04);
      n.connect(f); f.connect(g); g.connect(out); n.start(t, Math.random()); n.stop(t + 0.05);
    }
    const root = roots[bar];
    if (s % 2 === 0) note(midi(root - 12 + (s % 4 === 2 ? 12 : 0)), 0.2, 0.35, 'sawtooth', 500);
    const third = bar === 0 ? 3 : 4;
    const a = [0, third, 7, 12, 7, third][s % 6];
    note(midi(root + 12 + a), 0.14, 0.09, 'square', 2400);
    if (s === 0) for (const iv of [0, third, 7]) note(midi(root + iv), 2.2, 0.05, 'sawtooth', 1200);
  }
}
