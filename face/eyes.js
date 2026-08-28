// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
(() => {
  const body = document.body;
  const face = document.getElementById('face');
  const caption = document.getElementById('caption');
  let state = 'idle';
  let mouth = 0;
  let glance = { x: 0, y: 0 }, target = { x: 0, y: 0 };
  let captionTimer = null, captionReveal = null;

  function setState(s) {
    state = s;
    body.dataset.state = s;
    if (s === 'thinking') target = { x: 0.3, y: -0.4 };
    else if (s === 'listening' || s === 'speaking' || s === 'noticing') target = { x: 0, y: 0 };
    kick();
  }

  // Cancel in-flight caption timers so turns don't overlap.
  function resetCaption() {
    if (captionReveal) { clearInterval(captionReveal); captionReveal = null; }
    clearTimeout(captionTimer);
  }

  function showCaption(text, who, seconds) {
    resetCaption();
    caption.classList.toggle('heard', who === 'heard');
    caption.classList.add('show');

    // What the companion heard: appears whole, immediately.
    if (who !== 'said') {
      caption.textContent = '“' + text + '”';
      captionTimer = setTimeout(() => caption.classList.remove('show'), 6000);
      return;
    }

    // reply: lay out every word up front, then reveal them across `seconds`
    const words = text.split(/\s+/).filter(Boolean);
    caption.textContent = '';
    const spans = words.map((w, i) => {
      const s = document.createElement('span');
      s.className = 'word';
      s.textContent = i < words.length - 1 ? w + ' ' : w;
      caption.appendChild(s);
      return s;
    });
    if (!spans.length) return;
    const total = Math.max(0.4, seconds || 1.2);
    const step = (total * 1000) / spans.length;
    let i = 0;
    const reveal = () => {
      if (i < spans.length) spans[i++].classList.add('on');
      if (i >= spans.length) {
        clearInterval(captionReveal);
        captionReveal = null;
        captionTimer = setTimeout(() => caption.classList.remove('show'), 4000);
      }
    };
    reveal();  // first word right away, the rest on the interval
    if (i < spans.length) captionReveal = setInterval(reveal, step);
  }

  function blink() {
    if (state === 'sleeping' || state === 'noticing') return;
    body.classList.add('blink');
    setTimeout(() => body.classList.remove('blink'), 120);
  }

  function scheduleBlink() {
    setTimeout(() => { blink(); scheduleBlink(); }, 2500 + Math.random() * 3500);
  }

  function scheduleGlance() {
    setTimeout(() => {
      if (state === 'idle') target = { x: (Math.random() - 0.5) * 0.8, y: (Math.random() - 0.5) * 0.5 };
      if (state === 'thinking') target = { x: (Math.random() - 0.2) * 0.6, y: -0.4 };
      kick();
      scheduleGlance();
    }, 1500 + Math.random() * 3000);
  }

  // on-demand animation loop: kick() starts it, frame() reschedules while moving
  const FRAME_MS = 32, EPS = 0.002;
  let running = false, lastT = 0;

  function moving() {
    return state === 'speaking' || mouth > 0.005 ||
      Math.abs(target.x - glance.x) > EPS || Math.abs(target.y - glance.y) > EPS;
  }

  function kick() {
    if (running || document.hidden) return;
    running = true;
    requestAnimationFrame(frame);
  }

  function frame(t) {
    running = false;
    if (document.hidden) return;
    if (t - lastT < FRAME_MS) { kick(); return; }
    lastT = t;
    glance.x += (target.x - glance.x) * 0.16;
    glance.y += (target.y - glance.y) * 0.16;
    const bob = state === 'speaking' ? mouth * 0.25 : 0;
    const dx = glance.x * 6, dy = glance.y * 6 - bob * 8;
    face.style.transform = `translate(${dx}vw, ${dy}vw) scaleY(${1 - bob * 0.3})`;
    mouth *= 0.72;
    if (mouth < 0.005) mouth = 0;
    if (moving()) kick();
  }

  document.addEventListener('visibilitychange', () => { if (!document.hidden) kick(); });

  function connect() {
    const ws = new WebSocket(`ws://${location.host}/ws`);
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.type === 'state') setState(m.state);
      else if (m.type === 'mouth') { mouth = Math.max(mouth, m.level); kick(); }
      else if (m.type === 'caption') showCaption(m.text, m.who, m.seconds);
    };
    ws.onclose = () => setTimeout(connect, 2000);
    ws.onerror = () => ws.close();
  }

  // Android ignores settings-based immersive mode, so go fullscreen on the first tap.
  document.addEventListener('click', () => {
    try {
      const el = document.documentElement;
      if (el.requestFullscreen && !document.fullscreenElement) el.requestFullscreen().catch(() => {});
    } catch (e) { /* unsupported: stay windowed */ }
  });

  scheduleBlink();
  scheduleGlance();
  kick();
  connect();
})();
