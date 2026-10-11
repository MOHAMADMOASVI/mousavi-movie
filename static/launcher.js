// The TV home screen's extras on top of app.js: the clock and the sample app tiles. ES5, like app.js.
(function () {
  'use strict';

  var FA = '۰۱۲۳۴۵۶۷۸۹';
  function fa(s) { return String(s).replace(/\d/g, function (d) { return FA[d]; }); }
  function $(id) { return document.getElementById(id); }

  // The clock runs on the server's time (see time_json in server.py), not the TV's own clock,
  // which is often wrong after the TV has been switched off. Between checks it counts on with a
  // timer that the TV's clock setting can't move. Only the copy built into the TV app, shown
  // while the server can't be reached, has to trust the TV's clock.
  var DAYS = ['یکشنبه', 'دوشنبه', 'سه‌شنبه', 'چهارشنبه', 'پنجشنبه', 'جمعه', 'شنبه'];
  var MONTHS = ['فروردین', 'اردیبهشت', 'خرداد', 'تیر', 'مرداد', 'شهریور', 'مهر', 'آبان', 'آذر', 'دی', 'بهمن', 'اسفند'];
  var time = window.TV_TIME, since = mono();
  function mono() { return window.performance && performance.now ? performance.now() : Date.now(); }
  function int(x) { return Math.floor(x); }
  // Gregorian to Persian (Jalali) date, worked out here so it doesn't depend on the TV's browser.
  function jalali(gy, gm, gd) {
    var before = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
    var leap = gm > 2 ? gy + 1 : gy;
    var days = 355666 + 365 * gy + int((leap + 3) / 4) - int((leap + 99) / 100) + int((leap + 399) / 400) + gd + before[gm - 1];
    var jy = -1595 + 33 * int(days / 12053);
    days %= 12053;
    jy += 4 * int(days / 1461);
    days %= 1461;
    if (days > 365) { jy += int((days - 1) / 365); days = (days - 1) % 365; }
    return days < 186 ? [jy, int(days / 31), 1 + days % 31] : [jy, 6 + int((days - 186) / 30), 1 + (days - 186) % 30];
  }
  function tick() {
    // shifted so that the UTC fields read as the server's local time
    var local = time ? time.now + mono() - since + time.tz * 60000 : Date.now() - new Date().getTimezoneOffset() * 60000;
    var now = new Date(local), m = now.getUTCMinutes();
    var j = jalali(now.getUTCFullYear(), now.getUTCMonth() + 1, now.getUTCDate());
    var weekday = DAYS[now.getUTCDay()], date = fa(j[2]) + ' ' + MONTHS[j[1]];
    $('clock').textContent = $('rest-clock').textContent = fa(now.getUTCHours() + ':' + (m < 10 ? '0' : '') + m);
    $('date').textContent = weekday + ' ' + date;
    $('rest-day').textContent = weekday;
    $('rest-date').textContent = date + ' ' + fa(j[0]);
  }
  function sync() {
    if (!time) return;
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/time');
    xhr.onload = function () {
      try { time = JSON.parse(xhr.responseText); since = mono(); tick(); } catch (e) {}
    };
    xhr.send(); // if the server can't be reached the clock just keeps counting from the last answer
  }
  tick();
  setInterval(tick, 20000);
  setInterval(sync, 5 * 60 * 1000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) sync(); }); // the TV came back from standby

  // Left alone for half an hour, the page frosts over and shows only the time and the day.
  // The next button press just brings the page back. (/tv?idle=5 shortens the wait to 5 seconds, for trying it.)
  var custom = /[?&]idle=(\d+)/.exec(location.search);
  var IDLE_MS = (custom ? +custom[1] : 30 * 60) * 1000, idleTimer;
  function resting() { return /\bresting\b/.test(document.body.className); }
  function wake() {
    document.body.className = document.body.className.replace(/\s*\bresting\b/, '');
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function () { document.body.className += ' resting'; }, IDLE_MS);
  }
  ['keydown', 'mousedown', 'mousemove', 'touchstart', 'wheel'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (resting() && type === 'keydown') { e.preventDefault(); e.stopPropagation(); }
      wake();
    }, true);
  });
  wake();

  // Other apps and the inputs only exist on the TV itself; here they just say so.
  var toast = $('toast'), toastTimer;
  document.addEventListener('click', function (e) {
    var tile = e.target.closest ? e.target.closest('[data-app]') : null;
    if (!tile) return;
    toast.textContent = '«' + tile.getAttribute('data-app') + '» روی تلویزیون باز می‌شود';
    toast.className = 'toast show';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toast.className = 'toast'; }, 2200);
  });

  // Inside the TV app (android-tv/src/app/videotv/TvBridge.java) the sample tiles give way to
  // the apps really installed on the TV, its real inputs, and its settings.
  var TV = window.TV;
  function real(tile, action) {
    tile.removeAttribute('data-app');
    tile.addEventListener('click', action);
    return tile;
  }
  if (TV) {
    var apps = document.querySelector('#apps .track'), own = apps.querySelector('.own');
    apps.innerHTML = '';
    apps.appendChild(own);
    document.querySelector('#apps h2 small').textContent = '';
    JSON.parse(TV.apps()).forEach(function (app) {
      var tile = document.createElement('button'), picture = document.createElement('img');
      tile.className = app.banner ? 'app banner' : 'app';
      tile.setAttribute('data-focus', '');
      tile.setAttribute('aria-label', app.name);
      picture.src = 'http://appicon.invalid/' + app.pkg; // answered by the TV app itself, not the network
      picture.alt = '';
      picture.onerror = function () { // no picture: fall back to the name alone
        tile.removeChild(picture);
        if (app.banner) { tile.className = 'app'; tile.appendChild(document.createTextNode(app.name)); }
      };
      tile.appendChild(picture);
      if (!app.banner) tile.appendChild(document.createTextNode(app.name)); // a banner already carries the name
      apps.appendChild(real(tile, function () { TV.open(app.pkg); }));
    });

    var sources = document.querySelector('.source').parentNode, wifi = sources.querySelector('[data-kind="wifi"]');
    var samples = {};
    ['antenna', 'av', 'hdmi'].forEach(function (kind) { samples[kind] = sources.querySelector('[data-kind="' + kind + '"]'); });
    [].forEach.call(sources.querySelectorAll('.source'), function (tile) {
      var kind = tile.getAttribute('data-kind');
      if (kind === 'wifi' || kind === 'settings') real(tile, function () { TV.settings(kind); });
      else sources.removeChild(tile);
    });
    JSON.parse(TV.inputs()).forEach(function (input) {
      var tile = (samples[input.kind] || samples.hdmi).cloneNode(true); // for its icon
      tile.lastChild.nodeValue = input.name;
      if (!input.on) tile.className += ' off'; // the TV says nothing is plugged in
      sources.insertBefore(real(tile, function () { TV.openInput(input.id, input.name); }), wifi);
    });
  }
  // Leaves a note on the server of what this TV is and has, so input problems can be looked into from the computer.
  if (TV && TV.device && time) {
    var report = new XMLHttpRequest();
    report.open('POST', '/tvinfo');
    report.send('{"device":' + TV.device() + ',"inputs":' + TV.inputs() + ',"apps":' + TV.apps() + '}');
  }
  if (!$('hero-play')) document.querySelector('#apps .app').focus(); // no films on screen: start on the apps

  $('tab-apps').addEventListener('click', function (e) {
    e.preventDefault();
    document.querySelector('#apps .app').focus();
  });
})();
