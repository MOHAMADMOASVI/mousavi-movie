// Written in plain ES5 on purpose: smart-TV browsers are often years behind.
(function () {
  'use strict';

  var LITE = /\blite\b/.test(document.documentElement.className);
  var FA = '۰۱۲۳۴۵۶۷۸۹';
  function fa(s) { return String(s).replace(/\d/g, function (d) { return FA[d]; }); }
  function clock(t) {
    if (!isFinite(t) || t < 0) t = 0;
    t = Math.floor(t);
    var h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), s = t % 60;
    return fa((h ? h + ':' + (m < 10 ? '0' : '') + m : m) + ':' + (s < 10 ? '0' : '') + s);
  }
  function $(id) { return document.getElementById(id); }
  function load(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }
  function save(key, value) {
    try { value === null ? localStorage.removeItem(key) : localStorage.setItem(key, value); } catch (e) {}
  }

  // One name per remote button, across keyboards and TV platforms (webOS, Tizen, Android TV).
  var CODES = {
    37: 'left', 38: 'up', 39: 'right', 40: 'down', 13: 'ok', 32: 'ok',
    8: 'back', 27: 'back', 461: 'back', 10009: 'back',
    179: 'playpause', 10252: 'playpause', 415: 'play', 19: 'pause', 413: 'stop',
    417: 'ff', 412: 'rw', 228: 'ff', 227: 'rw', 176: 'next', 177: 'prev', 10233: 'next', 10232: 'prev'
  };
  var KEYS = {
    ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down', Enter: 'ok', ' ': 'ok',
    Backspace: 'back', Escape: 'back', GoBack: 'back', BrowserBack: 'back',
    MediaPlayPause: 'playpause', MediaPlay: 'play', MediaPause: 'pause', MediaStop: 'stop',
    MediaFastForward: 'ff', MediaRewind: 'rw', MediaTrackNext: 'next', MediaTrackPrevious: 'prev'
  };
  function button(e) {
    if (e.ctrlKey || e.metaKey || e.altKey) return null;
    if (KEYS[e.key]) return KEYS[e.key];
    if (/^[0-9]$/.test(e.key)) return 'digit';
    if (e.key === 'f' || e.key === 'F') return 'fullscreen';
    return CODES[e.keyCode] || null;
  }

  // Move focus to the nearest focusable item in the pressed direction, by geometry, so the
  // same code works for the shelves, the player's strip, and right-to-left layouts.
  // Up/Down go to the nearest row first and then to the closest item in it; Left/Right
  // strongly prefer items on the same row.
  function moveFocus(dir, scope) {
    var items = [].slice.call((scope || document).querySelectorAll('[data-focus]')).filter(function (el) {
      return el.offsetWidth > 0;
    });
    if (!items.length) return false;
    var cur = document.activeElement;
    if (items.indexOf(cur) < 0) { items[0].focus(); return true; }
    var vertical = dir === 'up' || dir === 'down';
    var a = cur.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    var found = [], nearest = Infinity;
    items.forEach(function (el) {
      if (el === cur) return;
      var b = el.getBoundingClientRect();
      var dx = b.left + b.width / 2 - ax, dy = b.top + b.height / 2 - ay;
      var along = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      if (along < 4) return;
      found.push({ el: el, along: along, across: Math.abs(vertical ? dx : dy) });
      nearest = Math.min(nearest, along);
    });
    var best = null, bestScore = Infinity;
    found.forEach(function (c) {
      if (vertical && c.along > nearest + a.height / 2) return; // not in the nearest row
      var score = vertical ? c.across : c.along + c.across * 3;
      if (score < bestScore) { bestScore = score; best = c.el; }
    });
    if (best) best.focus();
    return !!best;
  }

  document.addEventListener('focusin', function (e) {
    if (!e.target.hasAttribute || !e.target.hasAttribute('data-focus')) return;
    try { e.target.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' }); }
    catch (err) { e.target.scrollIntoView(false); }
  });

  [].forEach.call(document.querySelectorAll('[data-copy]'), function (btn) {
    btn.addEventListener('click', function () {
      var text = btn.getAttribute('data-copy'), label = btn.textContent;
      function done() { btn.textContent = 'کپی شد'; setTimeout(function () { btn.textContent = label; }, 1500); }
      function select() { // no clipboard access (plain http on a LAN address): select it for manual copy
        var range = document.createRange();
        range.selectNodeContents(btn.previousElementSibling);
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, select);
      else select();
    });
  });

  // The focusable element of the listed film with this name.
  function cardFor(name, scope) {
    var found = (scope || document).querySelectorAll('[data-name]');
    for (var i = 0; i < found.length; i++) {
      if (found[i].getAttribute('data-name') === name) {
        return found[i].hasAttribute('data-focus') ? found[i] : found[i].querySelector('[data-focus]');
      }
    }
    return null;
  }

  // Mark every listed film as new, partly watched, or watched, and collect the partly watched
  // ones into the "continue watching" shelf.
  var shelf = $('continue-track');
  [].forEach.call(document.querySelectorAll('[data-name]'), function (item) {
    var name = item.getAttribute('data-name');
    var pos = parseFloat(load('pos:' + name)), dur = parseFloat(item.getAttribute('data-dur'));
    var state = pos > 5 && dur > 0 ? 'partial' : load('done:' + name) ? 'done' : 'fresh';
    item.setAttribute('data-state', state);
    if (state !== 'partial') return;
    item.querySelector('.prog i').style.width = Math.min(100, pos / dur * 100) + '%';
    item.setAttribute('data-left', String(Math.ceil((dur - pos) / 60)));
    if (shelf) {
      var copy = item.cloneNode(true), picture = copy.querySelector('img');
      if (picture) picture.src = item.getAttribute('data-small'); // this shelf is wide, so no poster
      shelf.appendChild(copy);
      $('continue').hidden = false;
    }
  });

  var dataEl = $('data');
  if (!dataEl) return home();
  player(JSON.parse(dataEl.textContent));

  function home() {
    document.addEventListener('keydown', function (e) {
      var b = button(e);
      if (b === 'left' || b === 'right' || b === 'up' || b === 'down') {
        e.preventDefault();
        moveFocus(b);
      } else if (b === 'back' && location.pathname === '/' && /\/tv$/.test(document.referrer)) {
        e.preventDefault(); // opened from the TV home screen: Back returns to it
        history.back();
      }
    });

    var play = $('hero-play');
    if (!play) return; // no films yet

    var hero = document.querySelector('.hero'), shown = null, swapTimer, pickTimer;

    function fillHero(card, tag) {
      var partial = card.getAttribute('data-state') === 'partial';
      var length = card.querySelector('.badge.dur');
      if ($('hero-bg')) $('hero-bg').src = card.getAttribute('data-wide');
      $('hero-title').textContent = card.querySelector('.card-title').textContent;
      $('hero-tag').textContent = partial ? 'ادامهٔ تماشا' : tag;
      $('hero-meta').textContent = partial
        ? fa(card.getAttribute('data-left')) + ' دقیقه باقی‌مانده'
        : (length ? length.textContent : '');
      play.href = card.href;
      play.lastChild.textContent = partial ? 'ادامهٔ تماشا' : 'تماشا';
      $('hero-restart').href = card.href + '?restart=1';
      $('hero-restart').hidden = !partial;
      shown = card.getAttribute('data-name');
    }
    // Cross-fades the banner to another film: fade out, swap the content, fade back in.
    function switchHero(card) {
      if (card.getAttribute('data-name') === shown) return;
      hero.classList.add('switching');
      clearTimeout(swapTimer);
      swapTimer = setTimeout(function () {
        fillHero(card, 'پیشنهاد تماشا');
        hero.classList.remove('switching');
      }, 260);
    }

    // The banner starts on the film this device watched last, then follows the selected card.
    var last = cardFor(load('last'));
    if (last) fillHero(last, 'آخرین تماشا');
    play.focus();
    document.addEventListener('focusin', function (e) {
      clearTimeout(pickTimer);
      var card = e.target;
      if (!card.classList || !card.classList.contains('card')) return;
      if (LITE) return; // the TV app keeps the banner still: swapping big pictures costs memory
      pickTimer = setTimeout(function () { switchHero(card); }, 450); // not on every step of a fast scroll
    });

  }

  function player(data) {
    var body = document.body, video = $('video'), rail = $('rail'), options = $('options');
    var posKey = 'pos:' + data.name;
    var SPEEDS = [1, 1.25, 1.5, 2, 0.75];
    var idleTimer, flashTimer, nextTimer, held = 0, lastSaved = 0;
    save('last', data.name);

    function railOpen() { return body.classList.contains('rail-open'); }
    function openRail(focusName) {
      body.classList.add('rail-open');
      var target = cardFor(focusName || data.nextName || data.name, rail) || rail.querySelector('.card');
      if (target) target.focus();
    }
    function closeRail() {
      body.classList.remove('rail-open');
      if (document.activeElement) document.activeElement.blur();
      wake();
    }
    // The options row under the progress bar: Down moves focus into it, Up or Back leaves,
    // and Down again goes on to the films strip.
    function menuOpen() { return options.contains(document.activeElement); }
    function closeMenu() {
      document.activeElement.blur();
      wake();
    }
    function wake() {
      if (body.classList.contains('idle')) { body.classList.remove('idle'); render(); }
      clearTimeout(idleTimer);
      idleTimer = setTimeout(function () {
        if (video && !video.paused && !railOpen() && !menuOpen()) body.classList.add('idle');
      }, 3500);
    }
    function flash(text) {
      var el = $('flash');
      el.textContent = text;
      el.classList.add('on');
      clearTimeout(flashTimer);
      flashTimer = setTimeout(function () { el.classList.remove('on'); }, 700);
    }

    // Formats the browser can't play: only the list and the VLC address are on screen.
    if (!video) {
      openRail();
      document.addEventListener('keydown', function (e) {
        var b = button(e);
        if (b === 'left' || b === 'right' || b === 'up' || b === 'down') { e.preventDefault(); moveFocus(b); }
        else if (b === 'back') { e.preventDefault(); location.href = '/'; }
      });
      return;
    }

    function toggle() {
      if (video.paused) { play(); flash('پخش'); } else { video.pause(); flash('مکث'); }
    }
    function play() {
      var p = video.play();
      if (p && p.catch) p.catch(function () { $('bigplay').hidden = false; }); // autoplay with sound was blocked
    }
    function seekBy(seconds) {
      if (!isFinite(video.duration)) return;
      video.currentTime = Math.max(0, Math.min(video.duration - 0.2, video.currentTime + seconds));
      var n = Math.abs(seconds);
      flash((n >= 60 ? fa(n / 60) + ' دقیقه' : fa(n) + ' ثانیه') + (seconds > 0 ? ' جلو' : ' عقب'));
      render();
    }
    function setSpeed(rate) {
      video.defaultPlaybackRate = video.playbackRate = rate;
      save('speed', String(rate));
      $('speed-btn').textContent = 'سرعت ' + fa(rate).replace('.', '٫') + '×';
    }
    function setSubs(on) {
      video.textTracks[0].mode = on ? 'showing' : 'hidden';
      save('subs', on ? 'on' : 'off');
      $('subs-btn').textContent = 'زیرنویس: ' + (on ? 'روشن' : 'خاموش');
    }
    function savePosition() {
      lastSaved = video.currentTime;
      if (lastSaved > 5) save(posKey, String(lastSaved));
    }
    // After a film ends the next one starts by itself unless a button is pressed first.
    function countdownToNext() {
      var left = 10;
      function show() { $('rail-title').textContent = 'پخش فیلم بعدی تا ' + fa(left) + ' ثانیهٔ دیگر'; }
      show();
      nextTimer = setInterval(function () {
        left--;
        if (left <= 0) location.href = data.next; else show();
      }, 1000);
    }
    function cancelNext() {
      if (!nextTimer) return;
      clearInterval(nextTimer);
      nextTimer = null;
      $('rail-title').textContent = 'فیلم‌های دیگر';
    }
    function render() {
      if (body.classList.contains('idle')) return; // nothing to draw while the controls are hidden
      var d = video.duration || 0, t = video.currentTime || 0;
      $('fill').style.width = (d ? t / d * 100 : 0) + '%';
      var buffered = 0;
      for (var i = 0; i < video.buffered.length; i++) {
        if (video.buffered.start(i) <= t + 0.5) buffered = Math.max(buffered, video.buffered.end(i));
      }
      $('buf').style.width = (d ? buffered / d * 100 : 0) + '%';
      $('time').textContent = clock(t) + ' / ' + clock(d);
    }

    video.addEventListener('loadedmetadata', function () {
      var saved = parseFloat(load(posKey));
      var restart = location.search.indexOf('restart=1') >= 0; // "play from the start" on the home banner
      if (!restart && saved > 5 && saved < video.duration - 10) video.currentTime = saved; // resume where it was left
      render();
    });
    video.addEventListener('timeupdate', function () {
      render();
      if (Math.abs(video.currentTime - lastSaved) > 5) savePosition();
    });
    video.addEventListener('progress', render);
    video.addEventListener('play', function () { $('bigplay').hidden = true; wake(); });
    video.addEventListener('pause', function () {
      if (!video.ended) savePosition();
      wake();
    });
    video.addEventListener('ended', function () {
      save(posKey, null);
      save('done:' + data.name, '1');
      openRail();
      if (data.next) countdownToNext();
    });
    setSpeed(parseFloat(load('speed')) || 1);
    if ($('subs-btn')) setSubs(load('subs') !== 'off');
    play();
    wake();

    video.addEventListener('click', function () {
      if (railOpen()) return closeRail();
      if (body.classList.contains('idle')) return wake(); // first tap on a phone only reveals the controls
      toggle();
    });
    $('bigplay').addEventListener('click', play);
    document.addEventListener('mousemove', wake);
    document.addEventListener('touchstart', wake);

    $('restart-btn').addEventListener('click', function () {
      video.currentTime = 0;
      play();
      flash('از اول');
    });
    $('speed-btn').addEventListener('click', function () {
      setSpeed(SPEEDS[(SPEEDS.indexOf(video.playbackRate) + 1) % SPEEDS.length]);
    });
    if ($('subs-btn')) $('subs-btn').addEventListener('click', function () {
      setSubs(video.textTracks[0].mode !== 'showing');
    });
    // Full screen: the page where the browser allows it, otherwise just the video (iPhone).
    var root = document.documentElement;
    var enterFull = root.requestFullscreen || root.webkitRequestFullscreen;
    var leaveFull = document.exitFullscreen || document.webkitExitFullscreen;
    function isFull() { return !!(document.fullscreenElement || document.webkitFullscreenElement); }
    function toggleFull() {
      if (!enterFull) { if (video.webkitEnterFullscreen) video.webkitEnterFullscreen(); return; }
      var done = isFull() ? leaveFull.call(document) : enterFull.call(root);
      if (done && done.catch) done.catch(function () {}); // the browser refused; nothing to do
    }
    if (enterFull || video.webkitEnterFullscreen) {
      $('fs-btn').hidden = false;
      $('fs-btn').addEventListener('click', toggleFull);
      ['fullscreenchange', 'webkitfullscreenchange'].forEach(function (type) {
        document.addEventListener(type, function () { $('fs-btn').textContent = isFull() ? 'خروج از تمام‌صفحه' : 'تمام‌صفحه'; });
      });
    }
    // A mouse click shouldn't leave focus in the menu, or the arrow keys would stop seeking.
    options.addEventListener('click', function (e) { if (e.detail) document.activeElement.blur(); });
    document.addEventListener('mousedown', cancelNext);
    document.addEventListener('touchstart', cancelNext);

    var bar = $('bar'), dragging = false;
    function seekTo(e) {
      var r = bar.getBoundingClientRect();
      var x = (e.touches ? e.touches[0].clientX : e.clientX) - r.left;
      if (isFinite(video.duration)) video.currentTime = Math.max(0, Math.min(1, x / r.width)) * video.duration;
      render();
      wake();
    }
    bar.addEventListener('mousedown', function (e) { dragging = true; seekTo(e); });
    document.addEventListener('mousemove', function (e) { if (dragging) seekTo(e); });
    document.addEventListener('mouseup', function () { dragging = false; });
    bar.addEventListener('touchstart', seekTo);
    bar.addEventListener('touchmove', function (e) { e.preventDefault(); seekTo(e); });

    document.addEventListener('keyup', function () { held = 0; });
    document.addEventListener('keydown', function (e) {
      var b = button(e);
      if (!b) return;
      cancelNext();
      if (menuOpen()) {
        if (b === 'left' || b === 'right') { e.preventDefault(); moveFocus(b, options); }
        else if (b === 'up' || b === 'back') { e.preventDefault(); closeMenu(); }
        else if (b === 'down') { e.preventDefault(); closeMenu(); openRail(); } // the films strip sits below the options
        wake();
        return; // OK falls through to the focused button
      }
      if (railOpen()) {
        if (b === 'left' || b === 'right') { e.preventDefault(); moveFocus(b, rail); }
        else if (b === 'up' || b === 'back') { e.preventDefault(); closeRail(); }
        else if (b === 'down') e.preventDefault();
        else if (b === 'playpause') toggle();
        return; // OK falls through to the focused card's link
      }
      e.preventDefault();
      wake();
      if (b === 'left' || b === 'right') {
        // holding the button speeds up: 10s, then 30s, then a minute per step
        held = e.repeat ? held + 1 : 0;
        var step = held > 20 ? 60 : held > 6 ? 30 : 10;
        seekBy(b === 'right' ? step : -step);
      }
      else if (b === 'ok' || b === 'playpause') toggle();
      else if (b === 'play') play();
      else if (b === 'pause') video.pause();
      else if (b === 'ff') seekBy(30);
      else if (b === 'rw') seekBy(-30);
      else if (b === 'down') $('speed-btn').focus();
      else if (b === 'next' && data.next) location.href = data.next;
      else if (b === 'prev' && data.prev) location.href = data.prev;
      else if (b === 'back' || b === 'stop') location.href = '/';
      else if (b === 'digit' && isFinite(video.duration)) {
        video.currentTime = video.duration * Number(e.key) / 10;
        flash(fa(Number(e.key) * 10) + '٪');
        render();
      }
      else if (b === 'fullscreen') toggleFull();
    });
  }
})();
