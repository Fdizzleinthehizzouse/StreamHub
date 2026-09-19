'use strict';

/* StreamHub phone remote. Talks straight to the Fire TV over your wifi.
   The pairing token lives in this browser's storage. */

const TOKEN_KEY = 'streamhub.token';
const DEVICE_KEY = 'streamhub.device';

/**
 * Who this phone is, as opposed to its current login. The token is replaced on
 * every pairing; this is not, so re-pairing finds the same watchlist. Never
 * removed alongside the token. getRandomValues rather than randomUUID: the page
 * is plain http on the LAN, and randomUUID only exists in secure contexts.
 */
function deviceId() {
  let id = localStorage.getItem(DEVICE_KEY);
  if (!/^[a-f0-9]{32}$/.test(id || '')) {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    id = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
    localStorage.setItem(DEVICE_KEY, id);
  }
  return id;
}

function el(tag, attrs = {}, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(n.style, v);
    else if (k === 'text') n.textContent = v;
    else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2).toLowerCase(), v);
    else n.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of kids.flat()) {
    if (c === null || c === undefined || c === false) continue;
    n.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return n;
}
const $ = (s) => document.querySelector(s);
const img = (p, size = 'w342') => (p ? `https://image.tmdb.org/t/p/${size}${p}` : null);

const S = {
  token: null,
  wired: false,
  services: [],
  svcById: {},
  state: null,
  tv: null,
  view: 'home',
  homeCache: null,
  history: [],
};

/* ---------------------------------------------------------------- api */

async function api(pathname, { method = 'GET', body } = {}) {
  const res = await fetch(pathname, {
    method,
    headers: {
      'content-type': 'application/json',
      'x-streamhub-token': S.token || '',
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  // A 401 from a normal endpoint means our token died, so start over. A 401
  // from /api/pair just means a mistyped code - reloading there would wipe the
  // error message and look like the button did nothing.
  if (res.status === 401 && pathname !== '/api/pair') {
    localStorage.removeItem(TOKEN_KEY);
    location.reload();
    throw new Error('Not paired');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

let toastTimer;
function toast(msg, isErr = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast' + (isErr ? ' err' : '');
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), isErr ? 5200 : 2600);
}

/* ---------------------------------------------------------------- pairing */

function showPairing(message) {
  $('#pair').hidden = false;
  $('#app').hidden = true;
  if (message) $('#pair-err').textContent = message;

  const input = $('#pair-code');
  const go = $('#pair-go');
  input.focus();

  const submit = async () => {
    const code = input.value.trim().toUpperCase();
    if (code.length !== 6) {
      $('#pair-err').textContent = 'Six characters, please.';
      return;
    }
    go.disabled = true;
    try {
      const { token } = await api('/api/pair', { method: 'POST', body: { code, deviceId: deviceId() } });
      localStorage.setItem(TOKEN_KEY, token);
      S.token = token;
      $('#pair').hidden = true;
      await askProfilesOnce();
      $('#app').hidden = false;
      boot();
    } catch (err) {
      $('#pair-err').textContent = err.message;
      input.value = '';
      input.focus();
    } finally {
      go.disabled = false;
    }
  };

  go.onclick = submit;
  input.onkeydown = (e) => {
    if (e.key === 'Enter') submit();
  };
  input.oninput = () => {
    $('#pair-err').textContent = '';
    input.value = input.value.toUpperCase();
    if (input.value.trim().length === 6) submit();
  };
}

/* ---------------------------------------------------------------- profiles */

/** One field per service the TV can pick a profile on. */
function renderProfileFields(box, note, data) {
  box.replaceChildren(
    ...data.services.map((s) =>
      el(
        'label',
        {},
        s.name,
        el('input', {
          class: 'setting-input',
          type: 'text',
          maxlength: '60',
          autocapitalize: 'words',
          autocorrect: 'off',
          spellcheck: 'false',
          placeholder: 'Your profile name, exactly as shown',
          value: s.profile || '',
          'data-service': s.id,
        })
      )
    )
  );
  // Say plainly which services cannot do this, and whether the TV can yet.
  note.textContent =
    'Netflix and HBO Max don’t let other apps read their profile screen, so you’ll still choose those with the remote.' +
    (data.enabled ? '' : ' This also needs a one-time setup on the TV before it works.');
}

function readProfileFields(box) {
  const out = {};
  box.querySelectorAll('input[data-service]').forEach((i) => {
    out[i.dataset.service] = i.value.trim();
  });
  return out;
}

/** Right after pairing, once per phone. Never blocks getting into the app. */
async function askProfilesOnce() {
  let data;
  try {
    data = await api('/api/profiles');
  } catch {
    return; // Settings can do this later
  }
  if (data.asked || !data.services.length) return;

  const step = $('#profiles-step');
  const box = $('#profiles-fields');
  renderProfileFields(box, $('#profiles-note'), data);
  step.hidden = false;

  await new Promise((done) => {
    const send = async (profiles, alwaysContinue) => {
      $('#profiles-err').textContent = '';
      try {
        await api('/api/profiles', { method: 'POST', body: { profiles } });
        done();
      } catch (err) {
        if (alwaysContinue) done();
        else $('#profiles-err').textContent = err.message;
      }
    };
    $('#profiles-save').onclick = () => send(readProfileFields(box), false);
    $('#profiles-skip').onclick = () => send({}, true);
  });
  step.hidden = true;
}

/* ---------------------------------------------------------------- boot */

async function boot() {
  // Wire the controls first. If the TV is off, this used to return early and
  // leave a screen where nothing was clickable and nothing ever retried.
  if (!S.wired) {
    wire();
    S.wired = true;
  }

  try {
    const data = await api('/api/state');
    S.services = data.services;
    S.svcById = Object.fromEntries(data.services.map((s) => [s.id, s]));
    S.state = data.state;
    S.tv = data.tv;
  } catch (err) {
    showUnreachable(err.message);
    return;
  }

  renderTvStatus();
  setView('home');
  refreshTvStatusLoop();
}

/** The TV is off or asleep. Offer a retry rather than a dead page. */
function showUnreachable(message) {
  const box = $('#tv-status');
  box.className = 'tv-status off';
  box.replaceChildren(el('span', { class: 'dot' }), 'Can’t reach your TV');
  $('#view').replaceChildren(
    el(
      'div',
      { class: 'empty' },
      el('strong', { text: 'Can’t reach your TV' }),
      'Make sure the TV is on and StreamHub is installed on it, and that your phone is on the same wifi.',
      el('div', { style: { marginTop: '18px' } }, el('button', { class: 'btn primary', text: 'Try again', onClick: () => boot() })),
      el('div', { style: { marginTop: '14px', fontSize: '11.5px' } }, message || '')
    )
  );
}

function wire() {
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.onclick = () => setView(tab.dataset.view);
  });

  const q = $('#q');
  let deb;
  q.addEventListener('input', () => {
    clearTimeout(deb);
    const v = q.value.trim();
    deb = setTimeout(() => {
      if (v.length >= 2) doSearch(v);
    }, 420);
  });
  q.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      clearTimeout(deb);
      e.target.blur();
      if (q.value.trim()) doSearch(q.value.trim());
    }
  });

  $('#sheet-wrap').onclick = (e) => {
    if (e.target.id === 'sheet-wrap') closeSheet();
  };

  $('#settings-btn').onclick = openSettings;
  $('#settings-close').onclick = () => ($('#settings-wrap').hidden = true);
  $('#settings-wrap').onclick = (e) => {
    if (e.target.id === 'settings-wrap') $('#settings-wrap').hidden = true;
  };
  $('#settings-save').onclick = saveSettings;

  $('#back-btn').onclick = () => {
    const prev = S.history.pop();
    if (prev) setView(prev, true);
  };
}

/* ---------------------------------------------------------------- tv status */

function renderTvStatus() {
  const box = $('#tv-status');
  box.className = 'tv-status on';
  const missingKey = S.state && S.state.hasTmdbKey === false;
  box.replaceChildren(
    el('span', { class: 'dot' }),
    missingKey ? 'Connected — but add your free key in Settings to see anything' : 'Connected to your TV'
  );
}

/**
 * Poll only while the remote is actually on screen. A phone in your pocket
 * used to keep waking the PC every 20 seconds all evening - which on the PC
 * side meant spawning adb and querying the TV, and on the phone side meant a
 * radio wake-up per tick. Both are pure waste when nobody is looking.
 */
function refreshTvStatusLoop() {
  const PERIOD_MS = 45000;
  let timer = null;

  const tick = async () => {
    if (document.hidden) return;
    try {
      const data = await api('/api/state');
      S.state = data.state;
      S.tv = data.tv;
      renderTvStatus();
      updateBadge();
    } catch {
      /* transient - the TV may be asleep */
    }
  };

  const start = () => {
    if (timer) return;
    timer = setInterval(tick, PERIOD_MS);
  };
  const stop = () => {
    clearInterval(timer);
    timer = null;
  };

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      stop();
    } else {
      tick(); // catch up immediately on return, then resume the slow cadence
      start();
    }
  });

  start();
}

function updateBadge() {
  const n = (S.state.watchlist || []).length;
  $('#wl-count').textContent = n ? String(n) : '';
}

/* ---------------------------------------------------------------- settings */

async function openSettings() {
  $('#settings-wrap').hidden = false;
  $('#settings-msg').textContent = '';
  try {
    const s = await api('/api/settings');
    $('#set-region').value = s.region || 'BE';
    $('#set-tmdb').placeholder = s.hasTmdbKey ? 'Saved — paste a new one to replace it' : 'Paste your TMDB key';
    $('#set-omdb').placeholder = s.hasOmdbKey ? 'Saved — paste a new one to replace it' : 'Paste your OMDb key';
  } catch {
    /* first run, nothing saved yet */
  }
  S.profilesLoaded = false;
  $('#set-profiles').replaceChildren();
  try {
    renderProfileFields($('#set-profiles'), $('#set-profiles-note'), await api('/api/profiles'));
    S.profilesLoaded = true;
  } catch {
    $('#set-profiles-note').textContent = 'Couldn’t load your profiles from the TV.';
  }
}

async function saveSettings() {
  const body = {};
  const tmdb = $('#set-tmdb').value.trim();
  const omdb = $('#set-omdb').value.trim();
  const region = $('#set-region').value.trim().toUpperCase();
  if (tmdb) body.tmdbKey = tmdb;
  if (omdb) body.omdbKey = omdb;
  if (region) body.region = region;

  const msg = $('#settings-msg');
  msg.textContent = 'Saving…';
  try {
    // Only if they loaded: saving blank fields would wipe names the TV has.
    if (S.profilesLoaded) {
      await api('/api/profiles', { method: 'POST', body: { profiles: readProfileFields($('#set-profiles')) } });
    }
    const r = await api('/api/settings', { method: 'POST', body });
    msg.textContent = r.hasTmdbKey ? 'Saved. Loading your films…' : 'Saved — but the film key is still missing.';
    $('#set-tmdb').value = '';
    $('#set-omdb').value = '';
    S.homeCache = null;
    // Refresh the banner too, otherwise it keeps nagging about a key you just saved.
    try {
      const fresh = await api('/api/state');
      S.state = fresh.state;
      S.services = fresh.services;
      S.svcById = Object.fromEntries(fresh.services.map((x) => [x.id, x]));
      S.tv = fresh.tv;
      renderTvStatus();
    } catch {
      /* not worth blocking the save on */
    }
    if (r.hasTmdbKey) {
      setTimeout(() => {
        $('#settings-wrap').hidden = true;
        setView('home');
      }, 800);
    }
  } catch (err) {
    msg.textContent = err.message;
  }
}

/* ---------------------------------------------------------------- views */

function setView(view, fromBack = false) {
  if (!fromBack && S.view !== view) S.history.push(S.view);
  S.view = view;
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.view === view));
  $('#back-btn').hidden = S.history.length === 0;
  const host = $('#view');
  host.scrollTop = 0;
  updateBadge();

  if (view === 'home') return renderHome(host);
  if (view === 'services') return renderServices(host);
  if (view === 'watchlist') return renderWatchlist(host);
  if (view === 'search') return; // rendered by doSearch
}

function scoreClass(n) {
  if (n === null || n === undefined) return '';
  return n >= 70 ? 'good' : n >= 50 ? 'mid' : 'low';
}

function card(item) {
  const poster = img(item.poster);
  return el(
    'button',
    { class: 'card', onClick: () => openSheet(item.mediaType, item.id) },
    el(
      'div',
      { class: 'art' },
      poster ? el('img', { src: poster, alt: item.title, loading: 'lazy' }) : el('div', { class: 'noart', text: item.title }),
      item.score ? el('div', { class: `score ${scoreClass(item.score)}`, text: `${item.score}%` }) : null
    ),
    el('div', { class: 't', text: item.title }),
    el('div', { class: 'y', text: [item.year, item.mediaType === 'tv' ? 'Series' : 'Film'].filter(Boolean).join(' · ') }),
    badges(item.availableOn)
  );
}

/** Where a title streams, as small service tags. Rent-only is marked. */
function badges(availableOn) {
  if (!availableOn || !availableOn.length) return null;
  return el(
    'div',
    { class: 'badges' },
    availableOn.map((a) => {
      const svc = S.svcById[a.serviceId];
      if (!svc) return null;
      return el('span', {
        class: 'badge' + (a.kind === 'rent' ? ' rent' : ''),
        style: { background: svc.color },
        title: a.kind === 'rent' ? `${svc.name} (rent or buy)` : svc.name,
        text: a.kind === 'rent' ? `${svc.short} €` : svc.short,
      });
    })
  );
}

function section(title, items, { sub = null, grid = false } = {}) {
  return el(
    'section',
    { class: 'section' },
    el('h2', { text: title }),
    sub ? el('div', { class: 'sub', text: sub }) : null,
    el('div', { class: grid ? 'grid' : 'strip' }, items.map(card))
  );
}

async function renderHome(host) {
  const pinned = S.state.pinned || [];
  host.replaceChildren();

  if (pinned.length) host.append(section('Continue watching', pinned, { sub: 'Tap to send it to the TV' }));

  const spin = el('div', { class: 'spinner' });
  host.append(spin);

  try {
    const data = S.homeCache || (await api('/api/home'));
    S.homeCache = data;
    spin.remove();
    if (data.needsKey || !data.rows.length) {
      host.append(
        el(
          'div',
          { class: 'empty' },
          el('strong', { text: data.needsKey ? 'One thing left' : 'Nothing to show' }),
          data.needsKey
            ? 'Tap the cog at the top and paste in a free key — takes two minutes and only needs doing once.'
            : 'Check the key and country under the cog at the top.'
        )
      );
      return;
    }
    for (const row of data.rows) host.append(section(row.title, row.items, { sub: row.subtitle }));
  } catch (err) {
    spin.remove();
    host.append(el('div', { class: 'empty' }, el('strong', { text: 'Could not load' }), err.message));
  }
}

function renderServices(host) {
  host.replaceChildren(
    el('h1', { class: 'page-title', text: 'Services' }),
    el('p', { class: 'page-sub', text: 'Opens the app on your Fire TV.' }),
    el(
      'div',
      { class: 'svc-grid' },
      S.services.map((svc) => {
        const installed = S.tv && S.tv.services ? S.tv.services[svc.id] : undefined;
        return el(
          'button',
          {
            class: 'svc-card',
            style: { background: 'var(--bg-2)' },
            onClick: () => playOnTv(svc.id, null),
          },
          el('div', { class: 'glow', style: { background: `radial-gradient(120% 120% at 0% 0%, ${svc.accent}, transparent 66%)` } }),
          el('div', { class: 'n', text: svc.name }),
          el('div', {
            class: 's',
            text: installed === false ? 'Not installed on the TV' : 'Open on TV',
          })
        );
      })
    )
  );
}

function renderWatchlist(host) {
  const items = S.state.watchlist || [];
  host.replaceChildren(
    el('h1', { class: 'page-title', text: 'Watchlist' }),
    el('p', { class: 'page-sub', text: 'Saved on your TV.' }),
    items.length
      ? el('div', { class: 'grid' }, items.map(card))
      : el('div', { class: 'empty' }, el('strong', { text: 'Nothing saved yet' }), 'Search for something and add it here.')
  );
}

async function doSearch(query) {
  setView('search');
  const host = $('#view');
  host.replaceChildren(el('h1', { class: 'page-title', text: `“${query}”` }), el('div', { class: 'spinner' }));
  try {
    const data = await api(`/api/search?q=${encodeURIComponent(query)}`);
    host.replaceChildren(
      el('h1', { class: 'page-title', text: `“${query}”` }),
      el('p', { class: 'page-sub', text: `${data.results.length} result${data.results.length === 1 ? '' : 's'}` }),
      data.results.length
        ? el('div', { class: 'grid' }, data.results.map(card))
        : el('div', { class: 'empty' }, el('strong', { text: 'Not on your services' }), 'Nothing matching that is on Netflix, Disney+, HBO Max or Prime Video in your country.')
    );
  } catch (err) {
    host.replaceChildren(el('div', { class: 'empty' }, el('strong', { text: 'Search failed' }), err.message));
  }
}

/* ---------------------------------------------------------------- sheet */

async function openSheet(mediaType, id) {
  const wrap = $('#sheet-wrap');
  const sheet = $('#sheet');
  wrap.hidden = false;
  sheet.replaceChildren(el('div', { class: 'sheet-grab' }), el('div', { class: 'spinner' }));

  let d;
  try {
    d = await api(`/api/details?mediaType=${mediaType}&id=${id}`);
  } catch (err) {
    closeSheet();
    toast(err.message, true);
    return;
  }

  const slim = { id: d.id, mediaType: d.mediaType, title: d.title, poster: d.poster, year: d.year, genreIds: d.genreIds };

  const facts = [];
  if (d.year) facts.push(String(d.year));
  facts.push(d.mediaType === 'tv' ? 'Series' : 'Film');
  if (d.seasons) facts.push(`${d.seasons} season${d.seasons === 1 ? '' : 's'}`);
  if (d.runtime) facts.push(`${d.runtime} min`);
  if (d.ratings && d.ratings.rated) facts.push(d.ratings.rated);
  if (d.directors && d.directors.length) facts.push(`${d.mediaType === 'tv' ? 'Created by' : 'Dir.'} ${d.directors.join(', ')}`);

  const ratings = [];
  if (d.score) ratings.push(['TMDB', `${d.score}%`, scoreClass(d.score)]);
  if (d.ratings) {
    if (d.ratings.rottenTomatoes != null) ratings.push(['Rotten Tomatoes', `${d.ratings.rottenTomatoes}%`, scoreClass(d.ratings.rottenTomatoes)]);
    if (d.ratings.imdb) ratings.push(['IMDb', `${d.ratings.imdb}/10`, scoreClass(d.ratings.imdb * 10)]);
    if (d.ratings.metacritic) ratings.push(['Metacritic', String(d.ratings.metacritic), scoreClass(d.ratings.metacritic)]);
  }

  const inList = (S.state.watchlist || []).some((w) => w.key === `${d.mediaType}:${d.id}`);
  const pinned = (S.state.pinned || []).some((p) => p.key === `${d.mediaType}:${d.id}`);

  const backdrop = img(d.backdrop, 'w780');

  sheet.replaceChildren(
    // The close button lives outside the backdrop check on purpose: without a
    // backdrop image there is no hero, and on a phone the sheet covers almost
    // the whole screen, so "tap outside to dismiss" is not a reliable way out.
    backdrop
      ? el(
          'div',
          { class: 'hero' },
          el('img', { src: backdrop, alt: '' }),
          el('button', { class: 'close', text: '✕', onClick: closeSheet })
        )
      : el(
          'div',
          { style: { position: 'relative' } },
          el('div', { class: 'sheet-grab' }),
          el('button', { class: 'close', style: { position: 'absolute', top: '2px', right: '11px' }, text: '✕', onClick: closeSheet })
        ),

    el(
      'div',
      { class: 'sheet-body' },
      el('h2', { text: d.title }),
      el('div', { class: 'facts', text: facts.join(' · ') }),

      ratings.length
        ? el(
            'div',
            { class: 'ratings' },
            ratings.map(([s, v, c]) => el('div', { class: `rating ${c}` }, el('span', { class: 's', text: s }), el('span', { class: 'v', text: v })))
          )
        : null,

      el('div', { class: 'label', text: 'Play on the TV' }),
      d.availableOn && d.availableOn.length
        ? el(
            'div',
            { class: 'play-list' },
            d.availableOn.map((a) => {
              const svc = S.svcById[a.serviceId];
              const missing = S.tv && S.tv.services && S.tv.services[a.serviceId] === false;
              return el(
                'button',
                { class: 'play-btn', style: { background: svc.color }, onClick: () => playOnTv(a.serviceId, slim) },
                el(
                  'span',
                  {},
                  svc.name,
                  a.kind === 'rent' ? el('small', { text: ' · rent or buy' }) : null,
                  missing ? el('small', { text: ' · not installed on TV' }) : null
                ),
                el('span', { class: 'arrow', text: '›' })
              );
            })
          )
        : el(
            'div',
            { class: 'empty', style: { padding: '18px 0', textAlign: 'left' } },
            // No "search the TV" fallback: Fire TV's own search can't be
            // opened by an app, and trying it landed in the web browser.
            'Not on your four services right now.'
          ),

      el(
        'div',
        { class: 'btn-row' },
        // `e.currentTarget` is null once you await inside a handler, so grab
        // the button up front.
        el('button', {
          class: 'btn' + (inList ? ' on' : ''),
          text: inList ? '✓ In list' : '＋ Watchlist',
          onClick: async (e) => {
            const btn = e.currentTarget;
            try {
              S.state = await api('/api/watchlist', { method: 'POST', body: { item: slim } });
              const now = (S.state.watchlist || []).some((w) => w.key === `${d.mediaType}:${d.id}`);
              btn.textContent = now ? '✓ In list' : '＋ Watchlist';
              btn.classList.toggle('on', now);
              updateBadge();
            } catch (err) {
              toast(err.message, true);
            }
          },
        }),
        el('button', {
          class: 'btn' + (pinned ? ' on' : ''),
          text: pinned ? '✓ Pinned' : '📌 Pin',
          onClick: async (e) => {
            const btn = e.currentTarget;
            try {
              S.state = await api('/api/pinned', { method: 'POST', body: { item: slim } });
              const now = (S.state.pinned || []).some((p) => p.key === `${d.mediaType}:${d.id}`);
              btn.textContent = now ? '✓ Pinned' : '📌 Pin';
              btn.classList.toggle('on', now);
              S.homeCache = null;
            } catch (err) {
              toast(err.message, true);
            }
          },
        })
      ),

      d.overview ? el('div', {}, el('div', { class: 'label', text: 'Synopsis' }), el('p', { class: 'overview', text: d.overview })) : null,

      d.genres && d.genres.length
        ? el('div', {}, el('div', { class: 'label', text: 'Genres' }), el('div', { class: 'chips' }, d.genres.map((g) => el('span', { class: 'chip', text: g }))))
        : null,

      d.cast && d.cast.length
        ? el(
            'div',
            {},
            el('div', { class: 'label', text: 'Cast' }),
            el(
              'div',
              { class: 'cast' },
              d.cast.map((c) =>
                el(
                  'div',
                  { class: 'cast-m' },
                  el('div', { class: 'pic' }, c.profile ? el('img', { src: img(c.profile, 'w185'), alt: c.name, loading: 'lazy' }) : null),
                  el('div', { class: 'n', text: c.name }),
                  c.character ? el('div', { class: 'c', text: c.character }) : null
                )
              )
            )
          )
        : null,

      d.recommendations && d.recommendations.length
        ? el('div', {}, el('div', { class: 'label', text: 'If you like this' }), el('div', { class: 'strip', style: { padding: '0 0 4px' } }, d.recommendations.map(card)))
        : null
    )
  );
  sheet.scrollTop = 0;
}

function closeSheet() {
  $('#sheet-wrap').hidden = true;
  $('#sheet').replaceChildren();
}

/* ---------------------------------------------------------------- play */

async function playOnTv(serviceId, item) {
  const svc = S.svcById[serviceId];
  try {
    const res = await api('/api/play', { method: 'POST', body: { serviceId, item } });
    // The TV answers 200 even when it could not start anything, so the flag has
    // to be checked - otherwise a missing app looked like a success.
    if (res.ok === false) {
      toast(res.error || 'The TV could not open that.', true);
      return;
    }
    if (res.state) S.state = res.state;
    S.homeCache = null;
    toast(playMessage(res.kind, svc.name, item && item.title));
    closeSheet();
    updateBadge();
  } catch (err) {
    toast(err.message, true);
  }
}

/** Say what the TV actually did - never more than it did. */
function playMessage(kind, service, title) {
  if (!title) return `Opening ${service} on the TV`;
  switch (kind) {
    case 'deeplink':
      return `Opening “${title}” on ${service}`;
    case 'search':
      return `${service} is showing results for “${title}”`;
    case 'search-typing':
      return `Searching ${service} for “${title}”`;
    case 'search-page':
      return `${service} search is open. Type “${title}” with the remote`;
    default:
      return `${service} is open. Find “${title}” there`;
  }
}

/* ---------------------------------------------------------------- start */

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

window.__S = S; // handy in devtools, and the test suite reads it

S.token = localStorage.getItem(TOKEN_KEY);
if (S.token) {
  $('#app').hidden = false;
  boot();
} else {
  showPairing();
}
