'use strict';

/* ------------------------------------------------------------------ *
 * tiny DOM helpers
 * ------------------------------------------------------------------ */

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'text') node.textContent = v;
    else if (k === 'html') node.innerHTML = v;
    else node.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return node;
}

const $ = (sel) => document.querySelector(sel);

/* ------------------------------------------------------------------ *
 * state
 * ------------------------------------------------------------------ */

const S = {
  services: [],
  serviceById: {},
  state: null,
  browser: null,
  view: 'home',
  homeCache: null,
  searchQuery: '',
  searchResults: null,
  providerIds: {},
};

/* ------------------------------------------------------------------ *
 * generic call wrapper - surfaces errors as a toast, returns null
 * ------------------------------------------------------------------ */

async function call(promise, { silent = false } = {}) {
  const res = await promise;
  if (!res || !res.ok) {
    const msg = (res && res.error && res.error.message) || 'Something went wrong.';
    if (!silent) toast(msg, true);
    return null;
  }
  return res.data;
}

let toastTimer = null;
function toast(message, isError = false) {
  const t = $('#toast');
  t.textContent = message;
  t.className = 'toast' + (isError ? ' err' : '');
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    t.hidden = true;
  }, isError ? 6000 : 3000);
}

/* ------------------------------------------------------------------ *
 * boot
 * ------------------------------------------------------------------ */

async function boot() {
  const data = await call(window.hub.bootstrap());
  if (!data) return;

  S.services = data.services;
  S.serviceById = Object.fromEntries(data.services.map((s) => [s.id, s]));
  S.state = data.state;
  S.browser = data.browser;

  renderSidebar();
  renderBrowserLine();
  renderRegionChip();
  wireGlobals();

  setView(S.state.settings.tmdbKey ? 'home' : 'settings');
}

function wireGlobals() {
  const q = $('#q');
  let debounce = null;
  q.addEventListener('input', () => {
    clearTimeout(debounce);
    const value = q.value.trim();
    debounce = setTimeout(() => {
      if (!value) return;
      S.searchQuery = value;
      setView('search');
    }, 350);
  });
  q.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      clearTimeout(debounce);
      S.searchQuery = q.value.trim();
      if (S.searchQuery) setView('search');
    }
  });

  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      q.focus();
      q.select();
    }
    if (e.key === 'Escape') closeSheet();
  });

  $('#sheet-backdrop').addEventListener('click', (e) => {
    if (e.target.id === 'sheet-backdrop') closeSheet();
  });

  document.addEventListener('click', (e) => {
    const link = e.target.closest('[data-ext]');
    if (link) {
      e.preventDefault();
      window.hub.open.external(link.dataset.ext);
    }
  });

  // The phone shares this watchlist, so pick up its edits without a refresh.
  if (window.hub.onStateChanged) {
    window.hub.onStateChanged((state) => {
      S.state = state;
      markActive();
      if (S.view === 'watchlist') renderWatchlist($('#view'));
    });
  }
}

/* ------------------------------------------------------------------ *
 * sidebar
 * ------------------------------------------------------------------ */

function renderSidebar() {
  const nav = $('#nav-services');
  nav.replaceChildren(
    ...S.services.map((svc) =>
      el(
        'button',
        {
          class: 'nav-item',
          'data-view': `service:${svc.id}`,
          onClick: () => setView(`service:${svc.id}`),
          oncontextmenu: (e) => {
            e.preventDefault();
            launchService(svc.id);
          },
          title: `Browse ${svc.name} — right-click to open it straight away`,
        },
        el('span', { class: 'svc-dot', style: { background: svc.color }, text: svc.short }),
        svc.name
      )
    )
  );

  $('#nav-main').querySelectorAll('[data-view]').forEach((btn) => {
    btn.onclick = () => setView(btn.dataset.view);
  });
  document.querySelectorAll('.sidebar-foot [data-view]').forEach((btn) => {
    btn.onclick = () => setView(btn.dataset.view);
  });
}

function renderBrowserLine() {
  const line = $('#browser-line');
  if (S.browser) {
    const mode = S.state.settings.profileMode === 'isolated' ? 'separate logins' : 'your normal profile';
    line.textContent = `Playing through ${titleCase(S.browser.name)} · ${mode}`;
  } else {
    line.textContent = 'No Chromium browser found — install Chrome or Edge to play video.';
  }
}

function renderRegionChip() {
  $('#region-chip').textContent = `Availability for ${S.state.settings.region}`;
}

function markActive() {
  document.querySelectorAll('.nav-item').forEach((b) => {
    b.classList.toggle('active', b.dataset.view === S.view);
  });
  const count = (S.state.watchlist || []).length;
  $('#wl-count').textContent = count ? String(count) : '';
}

/* ------------------------------------------------------------------ *
 * routing
 * ------------------------------------------------------------------ */

function setView(view) {
  S.view = view;
  markActive();
  const host = $('#view');
  host.scrollTop = 0;

  if (view === 'home') return renderHome(host);
  if (view === 'search') return renderSearch(host);
  if (view === 'watchlist') return renderWatchlist(host);
  if (view === 'settings') return renderSettings(host);
  if (view === 'tv') return renderTv(host);
  if (view.startsWith('service:')) return renderService(host, view.slice(8));
}

function loading(host) {
  host.replaceChildren(el('div', { class: 'spinner' }));
}

/* ------------------------------------------------------------------ *
 * cards & rows
 * ------------------------------------------------------------------ */

function scoreClass(score) {
  if (score === null || score === undefined) return '';
  if (score >= 70) return 'good';
  if (score >= 50) return 'mid';
  return 'low';
}

function card(item, opts = {}) {
  const poster = window.hub.img(item.poster, 'w342');
  const art = el(
    'div',
    { class: 'art' },
    poster
      ? el('img', { src: poster, alt: item.title, loading: 'lazy' })
      : el('div', { class: 'noart', text: item.title || 'No artwork' }),
    item.score ? el('div', { class: `score ${scoreClass(item.score)}`, text: `${item.score}%` }) : null,
    opts.pips && opts.pips.length
      ? el(
          'div',
          { class: 'pips' },
          opts.pips.map((sid) => {
            const svc = S.serviceById[sid];
            return svc ? el('div', { class: 'pip', style: { background: svc.color }, text: svc.short, title: svc.name }) : null;
          })
        )
      : null
  );

  return el(
    'button',
    { class: 'card', onClick: () => openSheet(item.mediaType, item.id, item) },
    art,
    el(
      'div',
      { class: 'meta' },
      el('div', { class: 't', text: item.title }),
      el('div', {
        class: 'y',
        text: [item.year || null, item.mediaType === 'tv' ? 'Series' : 'Film'].filter(Boolean).join(' · '),
      })
    )
  );
}

function rowEl(row, { layout = 'strip' } = {}) {
  return el(
    'section',
    { class: 'row' },
    el(
      'div',
      { class: 'row-head' },
      el('h2', { text: row.title }),
      row.subtitle ? el('span', { class: 'sub', text: row.subtitle }) : null
    ),
    el('div', { class: layout }, row.items.map((it) => card(it, { pips: row.pips ? row.pips(it) : null })))
  );
}

/* ------------------------------------------------------------------ *
 * home
 * ------------------------------------------------------------------ */

function serviceTiles() {
  return el(
    'div',
    { class: 'svc-tiles' },
    S.services.map((svc) =>
      el(
        'button',
        { class: 'svc-tile', onClick: () => launchService(svc.id) },
        el('div', { class: 'glow', style: { background: `radial-gradient(120% 120% at 0% 0%, ${svc.accent}, transparent 65%)` } }),
        el('div', { class: 'name', text: svc.name }),
        el('div', { class: 'hint', text: 'Opens in its own window' })
      )
    )
  );
}

async function renderHome(host) {
  if (!S.state.settings.tmdbKey) return renderSettings(host, true);

  const pinned = S.state.pinned || [];

  const head = el(
    'div',
    { class: 'page-head' },
    el('h1', { text: 'Home' }),
    el('p', {
      text: 'Click a service to open it in its own window, or pick a title below to jump straight to it.',
    })
  );

  host.replaceChildren(head, serviceTiles());

  if (pinned.length) {
    host.append(
      rowEl({
        id: 'pinned',
        title: 'Continue watching',
        subtitle: 'Your pinned titles — click to jump back in',
        items: pinned,
      })
    );
  }

  const spinner = el('div', { class: 'spinner' });
  host.append(spinner);

  const data = S.homeCache || (await call(window.hub.data.home()));
  spinner.remove();
  if (!data) return;
  S.homeCache = data;
  S.providerIds = data.providerIds || {};

  if (!data.rows.length) {
    host.append(
      el(
        'div',
        { class: 'empty' },
        el('h3', { text: 'Nothing to show yet' }),
        el('p', { text: 'Check your TMDB key and region in Settings, then reload.' })
      )
    );
    return;
  }

  for (const row of data.rows) {
    host.append(rowEl({ ...row, pips: pipsFor }));
  }
}

/** Which of the four services carry this title, based on the cached provider ids. */
function pipsFor() {
  return null; // resolved per-title in the sheet; keeping cards light avoids 20 API calls per row
}

/* ------------------------------------------------------------------ *
 * search
 * ------------------------------------------------------------------ */

async function renderSearch(host) {
  const q = S.searchQuery;
  if (!q) {
    host.replaceChildren(
      el('div', { class: 'page-head' }, el('h1', { text: 'Search' })),
      el(
        'div',
        { class: 'empty' },
        el('h3', { text: 'Find something to watch' }),
        el('p', { text: 'Type a title above. Each result shows which of your four services has it, and one click opens it there.' })
      )
    );
    return;
  }

  $('#q').value = q;
  host.replaceChildren(
    el('div', { class: 'page-head' }, el('h1', { text: `Results for “${q}”` })),
    el('div', { class: 'spinner' })
  );

  const data = await call(window.hub.data.search(q));
  if (!data) return;
  S.providerIds = data.providerIds || S.providerIds;

  const head = el(
    'div',
    { class: 'page-head' },
    el('h1', { text: `Results for “${q}”` }),
    el('p', { text: `${data.results.length} match${data.results.length === 1 ? '' : 'es'} — open one to see where it is streaming.` })
  );

  if (!data.results.length) {
    host.replaceChildren(
      head,
      el(
        'div',
        { class: 'empty' },
        el('h3', { text: 'No matches' }),
        el('p', { text: 'Try a shorter query, or the original-language title.' })
      )
    );
    return;
  }

  host.replaceChildren(head, el('div', { class: 'grid' }, data.results.map((it) => card(it))));
}

/* ------------------------------------------------------------------ *
 * watchlist
 * ------------------------------------------------------------------ */

function renderWatchlist(host) {
  const items = S.state.watchlist || [];
  const head = el(
    'div',
    { class: 'page-head' },
    el('h1', { text: 'Watchlist' }),
    el('p', { text: 'One list across all four services. Open a title to see where it is streaming right now.' })
  );

  if (!items.length) {
    host.replaceChildren(
      head,
      el(
        'div',
        { class: 'empty' },
        el('h3', { text: 'Nothing saved yet' }),
        el('p', { text: 'Search for something, open it, and hit “Add to watchlist”.' })
      )
    );
    return;
  }

  host.replaceChildren(head, el('div', { class: 'grid' }, items.map((it) => card(it))));
}

/* ------------------------------------------------------------------ *
 * one service
 * ------------------------------------------------------------------ */

async function renderService(host, serviceId) {
  const svc = S.serviceById[serviceId];
  if (!svc) return;

  const head = el(
    'div',
    { class: 'page-head' },
    el('h1', { text: svc.name }),
    el('p', { text: `What is on ${svc.name} in ${S.state.settings.region} right now, according to JustWatch.` })
  );

  const openBtn = el(
    'div',
    { class: 'sheet-actions', style: { marginTop: '0', marginBottom: '26px' } },
    el('button', {
      class: 'btn primary',
      text: `Open ${svc.name}`,
      onClick: () => launchService(svc.id),
    })
  );

  host.replaceChildren(head, openBtn, el('div', { class: 'spinner' }));

  const data = await call(window.hub.data.browseService(serviceId));
  host.replaceChildren(head, openBtn);
  if (!data) return;

  if (data.note) {
    host.append(el('div', { class: 'callout', text: data.note }));
    return;
  }

  for (const row of data.rows) host.append(rowEl(row));
}

/* ------------------------------------------------------------------ *
 * detail sheet
 * ------------------------------------------------------------------ */

async function openSheet(mediaType, id, seed = null) {
  const backdrop = $('#sheet-backdrop');
  const sheet = $('#sheet');
  backdrop.hidden = false;

  sheet.replaceChildren(
    el('button', { class: 'sheet-close', text: '✕', onClick: closeSheet }),
    el('div', { class: 'sheet-body', style: { marginTop: '0', paddingTop: '28px' } }, el('div', { class: 'spinner' }))
  );

  const d = await call(window.hub.data.details(mediaType, id));
  if (!d) {
    closeSheet();
    return;
  }

  const backdropUrl = window.hub.img(d.backdrop, 'w1280');
  const posterUrl = window.hub.img(d.poster, 'w342');

  const facts = [];
  if (d.year) facts.push(String(d.year));
  facts.push(d.mediaType === 'tv' ? 'Series' : 'Film');
  if (d.mediaType === 'tv' && d.seasons) facts.push(`${d.seasons} season${d.seasons === 1 ? '' : 's'}`);
  if (d.mediaType === 'tv' && d.episodes) facts.push(`${d.episodes} episodes`);
  if (d.runtime) facts.push(`${d.runtime} min`);
  if (d.ratings && d.ratings.rated) facts.push(d.ratings.rated);
  if (d.directors && d.directors.length) facts.push(`${d.mediaType === 'tv' ? 'Created by' : 'Directed by'} ${d.directors.join(', ')}`);

  const ratings = [];
  if (d.score) ratings.push({ src: 'TMDB', val: `${d.score}%`, cls: scoreClass(d.score) });
  if (d.ratings) {
    if (d.ratings.rottenTomatoes !== null && d.ratings.rottenTomatoes !== undefined)
      ratings.push({ src: 'Rotten Tomatoes', val: `${d.ratings.rottenTomatoes}%`, cls: scoreClass(d.ratings.rottenTomatoes) });
    if (d.ratings.imdb) ratings.push({ src: 'IMDb', val: `${d.ratings.imdb}/10`, cls: scoreClass(d.ratings.imdb * 10) });
    if (d.ratings.metacritic) ratings.push({ src: 'Metacritic', val: `${d.ratings.metacritic}`, cls: scoreClass(d.ratings.metacritic) });
  }

  const inWatchlist = (S.state.watchlist || []).some((w) => w.key === `${d.mediaType}:${d.id}`);
  const isPinned = (S.state.pinned || []).some((p) => p.key === `${d.mediaType}:${d.id}`);
  const slimItem = { id: d.id, mediaType: d.mediaType, title: d.title, poster: d.poster, year: d.year, genreIds: d.genreIds };

  const body = el(
    'div',
    { class: 'sheet-body' },
    el(
      'div',
      { class: 'sheet-title' },
      el('div', { class: 'sheet-poster' }, posterUrl ? el('img', { src: posterUrl, alt: d.title }) : null),
      el(
        'div',
        {},
        el('h2', { text: d.title }),
        el('div', { class: 'sheet-facts' }, joinDots(facts))
      )
    ),

    ratings.length
      ? el(
          'div',
          { class: 'ratings' },
          ratings.map((r) =>
            el('div', { class: `rating ${r.cls}` }, el('span', { class: 'src', text: r.src }), el('span', { class: 'val', text: r.val }))
          )
        )
      : null,

    // ---- where to watch ------------------------------------------------
    el(
      'div',
      { class: 'sheet-section' },
      el('h3', { text: `Watch it in ${S.state.settings.region}` }),
      d.availableOn && d.availableOn.length
        ? el(
            'div',
            { class: 'watch-on' },
            d.availableOn.map((a) => {
              const svc = S.serviceById[a.serviceId];
              return el(
                'button',
                {
                  class: `watch-btn ${a.kind === 'rent' ? 'rent' : ''}`,
                  style: { background: svc.color },
                  onClick: () => launchTitle(a.serviceId, slimItem),
                },
                `Open on ${svc.name}`,
                a.kind === 'rent' ? el('small', { text: 'rent / buy' }) : null
              );
            })
          )
        : el(
            'div',
            { class: 'callout' },
            el('strong', { text: 'Not on your four services right now. ' }),
            d.providers && d.providers.link
              ? el('span', {}, 'JustWatch lists other options — ', el('a', { href: '#', 'data-ext': d.providers.link, text: 'see where it is available' }), '.')
              : 'You can still search a service manually below.'
          ),
      el(
        'div',
        { class: 'chips', style: { marginTop: '12px' } },
        S.services.map((svc) =>
          el('button', {
            class: 'chip',
            text: `Search ${svc.name}`,
            onClick: () => launchTitle(svc.id, slimItem),
          })
        )
      )
    ),

    // ---- send it to the Fire TV instead ---------------------------------
    S.state.settings.tvHost
      ? el(
          'div',
          { class: 'sheet-section' },
          el('h3', { text: 'Or send it to the Fire TV' }),
          el(
            'div',
            { class: 'chips' },
            (d.availableOn && d.availableOn.length ? d.availableOn.map((a) => a.serviceId) : S.services.map((s) => s.id)).map((sid) =>
              el('button', {
                class: 'chip',
                text: `▶ ${S.serviceById[sid].name} on TV`,
                onClick: async (e) => {
                  e.target.disabled = true;
                  const r = await call(window.hub.tv.play(sid, slimItem));
                  if (r) {
                    S.state = r.state || S.state;
                    S.homeCache = null;
                    toast(r.note || `Sent “${d.title}” to the TV.`);
                  }
                  e.target.disabled = false;
                },
              })
            ),
            el('button', {
              class: 'chip',
              text: '🔍 Search on TV',
              onClick: async () => {
                const r = await call(window.hub.tv.search(d.title));
                if (r) toast(`Searching the TV for “${d.title}”.`);
              },
            })
          )
        )
      : null,

    // ---- actions -------------------------------------------------------
    el(
      'div',
      { class: 'sheet-actions' },
      el('button', {
        class: 'btn' + (inWatchlist ? ' on' : ''),
        text: inWatchlist ? '✓ In watchlist' : '＋ Add to watchlist',
        onClick: async (e) => {
          S.state = (await call(window.hub.state.toggleWatchlist(slimItem))) || S.state;
          const now = (S.state.watchlist || []).some((w) => w.key === `${d.mediaType}:${d.id}`);
          e.target.textContent = now ? '✓ In watchlist' : '＋ Add to watchlist';
          e.target.classList.toggle('on', now);
          markActive();
        },
      }),
      el('button', {
        class: 'btn' + (isPinned ? ' on' : ''),
        text: isPinned ? '✓ In continue watching' : '📌 Pin to continue watching',
        onClick: async (e) => {
          S.state = (await call(window.hub.state.togglePinned(slimItem))) || S.state;
          const now = (S.state.pinned || []).some((p) => p.key === `${d.mediaType}:${d.id}`);
          e.target.textContent = now ? '✓ In continue watching' : '📌 Pin to continue watching';
          e.target.classList.toggle('on', now);
        },
      }),
      d.imdbId
        ? el('button', {
            class: 'btn',
            text: 'IMDb page',
            onClick: () => window.hub.open.external(`https://www.imdb.com/title/${d.imdbId}/`),
          })
        : null,
      el('button', {
        class: 'btn',
        text: 'Letterboxd page',
        onClick: () => window.hub.open.external(`https://letterboxd.com/search/${encodeURIComponent(d.title)}/`),
      })
    ),

    d.overview
      ? el('div', { class: 'sheet-section' }, el('h3', { text: 'Synopsis' }), el('p', { class: 'overview', text: d.overview }))
      : null,

    d.genres && d.genres.length
      ? el(
          'div',
          { class: 'sheet-section' },
          el('h3', { text: 'Genres' }),
          el('div', { class: 'chips' }, d.genres.map((g) => el('span', { class: 'chip', text: g })))
        )
      : null,

    d.cast && d.cast.length
      ? el(
          'div',
          { class: 'sheet-section' },
          el('h3', { text: 'Cast' }),
          el(
            'div',
            { class: 'cast' },
            d.cast.map((c) =>
              el(
                'div',
                { class: 'cast-member' },
                el('div', { class: 'pic' }, c.profile ? el('img', { src: window.hub.img(c.profile, 'w185'), alt: c.name, loading: 'lazy' }) : null),
                el('div', { class: 'n', text: c.name }),
                c.character ? el('div', { class: 'c', text: c.character }) : null
              )
            )
          )
        )
      : null,

    d.ratings && d.ratings.awards
      ? el('div', { class: 'sheet-section' }, el('h3', { text: 'Awards' }), el('p', { class: 'overview', text: d.ratings.awards }))
      : null,

    d.recommendations && d.recommendations.length
      ? el(
          'div',
          { class: 'sheet-section' },
          el('h3', { text: 'If you like this' }),
          el('div', { class: 'strip' }, d.recommendations.map((r) => card(r)))
        )
      : null
  );

  sheet.replaceChildren(
    el('button', { class: 'sheet-close', text: '✕', onClick: closeSheet }),
    backdropUrl ? el('div', { class: 'sheet-hero' }, el('img', { src: backdropUrl, alt: '' })) : el('div', { style: { height: '90px' } }),
    body
  );
  sheet.scrollTop = 0;
}

function closeSheet() {
  $('#sheet-backdrop').hidden = true;
  $('#sheet').replaceChildren();
}

function joinDots(parts) {
  const out = [];
  parts.forEach((p, i) => {
    if (i) out.push(el('span', { class: 'dot', text: '·' }));
    out.push(el('span', { text: p }));
  });
  return out;
}

/* ------------------------------------------------------------------ *
 * launching
 * ------------------------------------------------------------------ */

async function launchService(serviceId) {
  const data = await call(window.hub.open.service(serviceId));
  if (!data) return;
  S.state = data.state;
  toast(`Opening ${S.serviceById[serviceId].name} in ${titleCase(data.browser)}…`);
  markActive();
}

async function launchTitle(serviceId, item) {
  const data = await call(window.hub.open.title(serviceId, item));
  if (!data) return;
  S.state = data.state;
  S.homeCache = null; // history changed, so the "because you watched" row should refresh
  toast(`Searching ${S.serviceById[serviceId].name} for “${item.title}”…`);
  markActive();
}

function titleCase(s) {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

/* ------------------------------------------------------------------ *
 * TV & phone
 * ------------------------------------------------------------------ */

async function renderTv(host) {
  const s = S.state.settings;

  const head = el(
    'div',
    { class: 'page-head' },
    el('h1', { text: 'TV & Phone' }),
    el('p', { text: 'Send anything you find here straight to the Fire TV, and turn your phone into the remote.' })
  );

  const statusBox = el('div', { class: 'callout' }, 'Checking…');
  const remoteBox = el('div', { class: 'callout' }, 'Checking…');

  const hostInput = el('input', { type: 'text', value: s.tvHost || '', placeholder: '192.168.1.42' });
  const tokenInput = el('input', { type: 'text', value: s.tvToken || '', placeholder: 'Shown on the TV under Setup' });
  const adbInput = el('input', { type: 'text', value: s.adbPath || '', placeholder: 'Leave blank to look in the usual places' });
  const portInput = el('input', { type: 'text', value: String(s.remotePort || 7654) });

  const field = (label, input, help) =>
    el('div', { class: 'field' }, el('label', { text: label }), input, help ? el('div', { class: 'help' }, help) : null);

  async function refreshStatus() {
    statusBox.replaceChildren('Checking the TV…');
    const st = await call(window.hub.tv.status(), { silent: true });
    if (!st) {
      statusBox.replaceChildren('Could not check the TV.');
      return;
    }
    const bits = [];
    if (!st.host) {
      bits.push(el('strong', { text: 'No TV address set. ' }), 'Put your Fire TV’s IP address in the box below. You’ll find it on the TV under Settings → My Fire TV → About → Network.');
    } else if (st.tvApp && st.hasToken) {
      bits.push(el('strong', { text: '✓ Connected through the StreamHub TV app. ' }), 'Nothing else needed — this is the good path.');
    } else if (st.tvApp && !st.hasToken) {
      bits.push(
        el('strong', { text: 'The TV app is running, but you have not entered its pairing code. ' }),
        'Open StreamHub on the TV → Setup, and copy the code into the box below.'
      );
    } else if (st.adbConnected) {
      const have = Object.entries(st.services).filter(([, v]) => v).map(([k]) => S.serviceById[k].name);
      const missing = Object.entries(st.services).filter(([, v]) => !v).map(([k]) => S.serviceById[k].name);
      bits.push(el('strong', { text: '✓ Connected over adb. ' }));
      if (have.length) bits.push(`Installed on the TV: ${have.join(', ')}. `);
      if (missing.length) bits.push(`Not installed: ${missing.join(', ')}.`);
    } else if (!st.adbFound) {
      bits.push(
        el('strong', { text: 'adb not found. ' }),
        'It ships with Android Studio, and with Google’s standalone platform-tools. Install either, or point at adb.exe below.'
      );
    } else {
      bits.push(el('strong', { text: 'Not connected. ' }), 'Turn on Settings → My Fire TV → Developer Options → ADB debugging on the TV, then hit Connect.');
    }
    statusBox.replaceChildren(...bits);
  }

  async function refreshRemote() {
    const info = await call(window.hub.remote.info(), { silent: true });
    if (!info) return;
    if (!info.running) {
      remoteBox.replaceChildren(el('strong', { text: 'The phone remote is off. ' }), 'Start it, then scan the code with your phone.');
      return;
    }
    remoteBox.replaceChildren(
      el('div', { style: { display: 'flex', gap: '22px', alignItems: 'center', flexWrap: 'wrap' } },
        info.qr ? el('div', { html: info.qr, style: { width: '148px', flex: '0 0 148px', background: '#fff', padding: '8px', borderRadius: '10px' } }) : null,
        el('div', { style: { flex: '1', minWidth: '220px' } },
          el('div', { style: { fontSize: '12.5px', marginBottom: '10px', color: 'var(--text)' } }, 'Scan this, or open the address below on your phone:'),
          el('div', { style: { fontFamily: 'monospace', fontSize: '13px', marginBottom: '14px' } }, info.urls.join('  ·  ') || '—'),
          el('div', { style: { fontSize: '12px', color: 'var(--text-faint)', marginBottom: '6px' } }, 'Pairing code'),
          el('div', { style: { fontSize: '30px', fontWeight: '800', letterSpacing: '0.16em' } }, info.code || '—'),
          el('div', { style: { fontSize: '11.5px', color: 'var(--text-faint)', marginTop: '12px' } },
            info.paired ? `${info.paired} phone${info.paired === 1 ? '' : 's'} paired.` : 'No phone paired yet.')
        )
      )
    );
  }

  host.replaceChildren(
    head,
    el('div', { class: 'form' },
      el('h2', { style: { fontSize: '14px', margin: '0' } }, 'Fire TV'),
      statusBox,
      field('Fire TV IP address', hostInput, 'On the TV: Settings → My Fire TV → About → Network.'),
      field('Pairing code from the TV app', tokenInput, 'Only needed once the StreamHub TV app is installed. Open it on the TV → Setup, and copy the code it shows. Leave blank to use adb instead.'),
      field('Path to adb (optional)', adbInput, 'Only needed if it isn’t in one of the usual places. Android Studio installs it under AppData\\Local\\Android\\Sdk\\platform-tools.'),
      el('div', { class: 'sheet-actions' },
        el('button', {
          class: 'btn primary', text: 'Save & connect',
          onClick: async (e) => {
            e.target.disabled = true;
            const saved = await call(window.hub.settings.set({
              tvHost: hostInput.value.trim(),
              tvToken: tokenInput.value.trim(),
              adbPath: adbInput.value.trim(),
            }));
            if (saved) S.state.settings = saved.settings;
            const r = await call(window.hub.tv.connect());
            if (r) toast('Connected to the Fire TV.');
            await refreshStatus();
            e.target.disabled = false;
          },
        }),
        el('button', { class: 'btn', text: 'Disconnect', onClick: async () => { await call(window.hub.tv.disconnect()); await refreshStatus(); toast('Disconnected.'); } }),
        el('button', { class: 'btn', text: 'Test — open Netflix on TV', onClick: async () => { const r = await call(window.hub.tv.play('netflix', null)); if (r) toast('Sent to the TV.'); } }),
        el('button', {
          class: 'btn',
          text: 'Send my API keys to the TV',
          onClick: async (e) => {
            e.target.disabled = true;
            const r = await call(window.hub.tv.pushSetup());
            if (r) toast('Keys sent — the TV app is set up.');
            e.target.disabled = false;
          },
        })
      ),

      el('h2', { style: { fontSize: '14px', margin: '26px 0 0' } }, 'Phone remote'),
      remoteBox,
      field('Port', portInput, 'Only change this if something else already uses 7654.'),
      el('div', { class: 'sheet-actions' },
        el('button', {
          class: 'btn primary', text: 'Start remote',
          onClick: async (e) => {
            e.target.disabled = true;
            const port = Number(portInput.value) || 7654;
            await call(window.hub.settings.set({ remotePort: port }));
            const r = await call(window.hub.remote.start(port));
            if (r) toast('Phone remote is live.');
            await refreshRemote();
            e.target.disabled = false;
          },
        }),
        el('button', { class: 'btn', text: 'Stop', onClick: async () => { await call(window.hub.remote.stop()); await refreshRemote(); toast('Remote stopped.'); } }),
        el('button', { class: 'btn', text: 'Unpair all phones', onClick: async () => { await call(window.hub.remote.revoke()); await refreshRemote(); toast('All phones unpaired, new code generated.'); } })
      ),

      el('div', { class: 'callout' },
        el('strong', { text: 'How the pieces fit. ' }),
        'Your phone talks to this PC, and this PC talks to the Fire TV. That means the PC needs to be awake for the phone remote to reach the TV. Everything stays on your own network — no accounts, no cloud, nothing forwarded through your router.'
      ),
      el('div', { class: 'callout' },
        el('strong', { text: 'A word on ADB. ' }),
        'With ADB debugging on, anything already on your home network can send commands to the TV. That is fine on a network you trust, and you can switch it off on the TV when you are not using it. Once the StreamHub TV app is installed, StreamHub uses that instead and you can leave ADB off for good.'
      )
    )
  );

  refreshStatus();
  refreshRemote();
}

/* ------------------------------------------------------------------ *
 * settings
 * ------------------------------------------------------------------ */

function renderSettings(host, nudge = false) {
  const s = S.state.settings;

  const field = (label, input, help) =>
    el('div', { class: 'field' }, el('label', { text: label }), input, help ? el('div', { class: 'help' }, help) : null);

  const tmdbInput = el('input', { type: 'password', value: s.tmdbKey || '', placeholder: 'Paste your TMDB API key (v3 auth)' });
  const omdbInput = el('input', { type: 'password', value: s.omdbKey || '', placeholder: 'Optional — unlocks Rotten Tomatoes, IMDb and Metacritic' });
  const regionInput = el('input', { type: 'text', value: s.region || 'BE', placeholder: 'BE' });

  const browserSelect = el(
    'select',
    {},
    el('option', { value: 'auto', text: 'Auto-detect (Chrome, then Edge, then Brave)', selected: s.browser === 'auto' }),
    el('option', { value: 'chrome', text: 'Google Chrome', selected: s.browser === 'chrome' }),
    el('option', { value: 'edge', text: 'Microsoft Edge', selected: s.browser === 'edge' }),
    el('option', { value: 'brave', text: 'Brave', selected: s.browser === 'brave' })
  );

  const profileSelect = el(
    'select',
    {},
    el('option', { value: 'shared', text: 'Use my normal browser profile (logins already work)', selected: s.profileMode === 'shared' }),
    el('option', { value: 'isolated', text: 'Give each service its own profile (log in once per service)', selected: s.profileMode === 'isolated' })
  );

  host.replaceChildren(
    el(
      'div',
      { class: 'page-head' },
      el('h1', { text: 'Settings' }),
      el('p', { text: 'Two free keys switch on everything. Both take about a minute.' })
    ),

    nudge
      ? el(
          'div',
          { class: 'callout', style: { marginBottom: '22px' } },
          el('strong', { text: 'Add a TMDB key to get started. ' }),
          'Without it StreamHub can still open the four services, but search, artwork, cast and recommendations stay empty.'
        )
      : null,

    el(
      'div',
      { class: 'form' },
      field(
        'TMDB API key (required)',
        tmdbInput,
        el(
          'span',
          {},
          'Free. Sign up at ',
          el('a', { href: '#', 'data-ext': 'https://www.themoviedb.org/signup', text: 'themoviedb.org' }),
          ', then Settings → API → request a key. Copy the one labelled ',
          el('strong', { text: 'API Key (v3 auth)' }),
          '.'
        )
      ),
      field(
        'OMDb API key (optional)',
        omdbInput,
        el(
          'span',
          {},
          'Free tier is 1,000 lookups a day. Grab one at ',
          el('a', { href: '#', 'data-ext': 'https://www.omdbapi.com/apikey.aspx', text: 'omdbapi.com' }),
          '. This is what supplies Rotten Tomatoes, IMDb and Metacritic scores.'
        )
      ),
      field(
        'Region',
        regionInput,
        'Two-letter country code. Streaming rights differ per country — BE for Belgium, FR for France, NL for the Netherlands.'
      ),
      field('Browser used for playback', browserSelect, 'StreamHub opens the official web players in a chromeless window of this browser.'),
      field('Login handling', profileSelect, 'Separate profiles keep four independent sessions, handy if you share an account with someone.'),

      el(
        'div',
        { class: 'sheet-actions' },
        el('button', {
          class: 'btn primary',
          text: 'Save',
          onClick: async () => {
            const result = await call(
              window.hub.settings.set({
                tmdbKey: tmdbInput.value.trim(),
                omdbKey: omdbInput.value.trim(),
                region: (regionInput.value.trim() || 'BE').toUpperCase(),
                browser: browserSelect.value,
                profileMode: profileSelect.value,
              })
            );
            if (!result) return;
            S.state.settings = result.settings;
            S.browser = result.browser;
            S.homeCache = null;
            renderBrowserLine();
            renderRegionChip();
            toast('Saved.');
            setView('home');
          },
        }),
        el('button', {
          class: 'btn',
          text: 'Clear history & stats',
          onClick: async () => {
            S.state = (await call(window.hub.state.clearHistory())) || S.state;
            S.homeCache = null;
            toast('History cleared.');
          },
        })
      ),

      el(
        'div',
        { class: 'callout' },
        el('strong', { text: 'Why StreamHub opens a browser window instead of playing video itself. ' }),
        'Netflix, Disney+, HBO Max and Prime Video all require a licensed Widevine CDM, which Electron and WebView2 do not ship — and the services reject unapproved clients anyway. Opening the official player in a chromeless Chrome/Edge window is the way to get real playback without touching your credentials: your logins stay in the browser, StreamHub never sees them.'
      ),
      el(
        'div',
        { class: 'callout' },
        el('strong', { text: 'About “Continue watching”. ' }),
        'None of the four services expose an API for your real progress, so StreamHub cannot read those rows. Pinned titles are the honest substitute — pin a show and it sits at the top, one click from where you left off.'
      ),
      el(
        'div',
        { class: 'callout' },
        el('strong', { text: 'Why there are no Letterboxd ratings. ' }),
        'Letterboxd runs an invite-only API and explicitly does not grant access for personal or recommendation projects, so there is no legitimate way to pull their scores in. Rotten Tomatoes, IMDb and Metacritic come through OMDb instead, and every title has a one-click link to its Letterboxd page.'
      )
    )
  );
}

boot();
