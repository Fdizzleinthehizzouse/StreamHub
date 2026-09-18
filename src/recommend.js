'use strict';

const { SERVICES } = require('./services');

/**
 * TMDB's provider list lumps Amazon's rental storefront in with the Prime Video
 * subscription. That is right for "where can I watch this" - a rental is a real
 * answer - but wrong for browsing, where it fills "Popular on Prime Video" with
 * things that cost money. Discovery is therefore restricted to what a
 * subscription actually covers.
 */
const SUBSCRIPTION_TYPES = 'flatrate,free,ads';

/**
 * Recommendations come from two places, both legitimate:
 *  1. Your own StreamHub activity - what you opened and what you saved. That
 *     builds a genre profile we feed into TMDB discover, filtered to the four
 *     services you actually pay for.
 *  2. What the services themselves are pushing - TMDB/JustWatch knows what is
 *     on each provider, so "Popular on Netflix" is real provider data rather
 *     than a guess.
 *
 * There is no way to read your real Netflix/Disney+ "Continue watching" rows:
 * none of them expose an API, and scraping a logged-in session would mean
 * handling your credentials and breaking their terms. Pinned titles are the
 * honest substitute.
 */

function tasteProfile(store) {
  // TMDB keeps two disjoint genre vocabularies: film has 878 "Science Fiction"
  // and 28 "Action", television has 10765 "Sci-Fi & Fantasy" and 10759
  // "Action & Adventure". Pooling them and sending the result to both endpoints
  // means one of the two calls asks for ids that cannot exist there and comes
  // back empty, so the profiles are kept apart.
  const weights = { movie: new Map(), tv: new Map() };
  const seen = new Set();

  const add = (item, weight) => {
    if (!item) return;
    if (item.key) seen.add(item.key);
    const bucket = weights[item.mediaType === 'tv' ? 'tv' : 'movie'];
    for (const g of item.genreIds || []) {
      bucket.set(g, (bucket.get(g) || 0) + weight);
    }
  };

  const history = store.data.history || [];
  const watchlist = store.data.watchlist || [];
  const pinned = store.data.pinned || [];

  history.forEach((h, i) => add(h, Math.max(1, 12 - i * 0.4)));
  watchlist.forEach((w) => add(w, 6));
  pinned.forEach((p) => add(p, 8));

  const top = (map) =>
    [...map.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 4)
      .map(([id]) => id);

  return {
    topGenres: { movie: top(weights.movie), tv: top(weights.tv) },
    seen,
    // Pinning carries the heaviest weight of the three, so it has to count as
    // signal - otherwise a user who only pins gets a profile that is computed
    // and then thrown away.
    hasSignal: history.length > 0 || watchlist.length > 0 || pinned.length > 0,
  };
}

function dedupe(items, seen, limit = 20) {
  const out = [];
  const local = new Set();
  for (const it of items) {
    const key = `${it.mediaType}:${it.id}`;
    if (seen.has(key) || local.has(key)) continue;
    local.add(key);
    // Rows that share a `seen` set expect it to accumulate; without this the
    // same title shows up in two adjacent rows on the home screen.
    seen.add(key);
    out.push(it);
    if (out.length >= limit) break;
  }
  return out;
}

function interleave(a, b) {
  const out = [];
  const max = Math.max(a.length, b.length);
  for (let i = 0; i < max; i++) {
    if (a[i]) out.push(a[i]);
    if (b[i]) out.push(b[i]);
  }
  return out;
}

async function buildHome(tmdb, store) {
  const rows = [];
  const { topGenres, seen, hasSignal } = tasteProfile(store);

  let providerIds = {};
  try {
    providerIds = await tmdb.providerIds(SERVICES);
  } catch {
    providerIds = {};
  }
  const allProviderIds = Object.values(providerIds).flat();
  const allProvidersParam = allProviderIds.length ? allProviderIds.join('|') : undefined;

  // --- 1. Because you watched ... --------------------------------------
  const lastWatched = (store.data.history || []).find((h) => h.id && h.mediaType);
  if (lastWatched) {
    try {
      const detail = await tmdb.details(lastWatched.mediaType, lastWatched.id);
      const recs = dedupe(detail.recommendations || [], seen, 18);
      if (recs.length) {
        rows.push({
          id: 'because',
          title: `Because you opened ${detail.title}`,
          subtitle: 'More in the same vein',
          items: recs,
        });
      }
    } catch {
      /* non-fatal */
    }
  }

  // --- 2. Picks for you (genre profile x your services) ----------------
  if (hasSignal && (topGenres.movie.length || topGenres.tv.length) && allProvidersParam) {
    try {
      // `|` is OR, `,` is AND. Joining four genres with a comma asks TMDB for
      // titles carrying all four at once, which essentially nothing does, so
      // the row silently never rendered.
      const [movies, shows] = await Promise.all([
        topGenres.movie.length
          ? tmdb.discover('movie', {
              with_genres: topGenres.movie.join('|'),
              with_watch_providers: allProvidersParam,
              with_watch_monetization_types: SUBSCRIPTION_TYPES,
              watch_region: tmdb.region,
              sort_by: 'popularity.desc',
            })
          : [],
        topGenres.tv.length
          ? tmdb.discover('tv', {
              with_genres: topGenres.tv.join('|'),
              with_watch_providers: allProvidersParam,
              with_watch_monetization_types: SUBSCRIPTION_TYPES,
              watch_region: tmdb.region,
              sort_by: 'popularity.desc',
            })
          : [],
      ]);
      const items = dedupe(interleave(movies, shows), seen, 20);
      if (items.length) {
        rows.push({
          id: 'foryou',
          title: 'Picks for you',
          subtitle: 'Built from what you have been opening, filtered to your four services',
          items,
        });
      }
    } catch {
      /* non-fatal */
    }
  }

  // --- 3. Popular on each service --------------------------------------
  for (const svc of SERVICES) {
    const ids = providerIds[svc.id] || [];
    if (!ids.length) continue;
    try {
      const [movies, shows] = await Promise.all([
        tmdb.discover('movie', {
          with_watch_providers: ids.join('|'),
          with_watch_monetization_types: SUBSCRIPTION_TYPES,
          watch_region: tmdb.region,
          sort_by: 'popularity.desc',
        }),
        tmdb.discover('tv', {
          with_watch_providers: ids.join('|'),
          with_watch_monetization_types: SUBSCRIPTION_TYPES,
          watch_region: tmdb.region,
          sort_by: 'popularity.desc',
        }),
      ]);
      const items = dedupe(interleave(movies, shows), new Set(), 20);
      if (items.length) {
        rows.push({
          id: `popular-${svc.id}`,
          title: `Popular on ${svc.name}`,
          subtitle: null,
          service: svc.id,
          items,
        });
      }
    } catch {
      /* non-fatal */
    }
  }

  // --- 4. Recently added across your services --------------------------
  if (allProvidersParam) {
    try {
      const since = new Date(Date.now() - 1000 * 60 * 60 * 24 * 120).toISOString().slice(0, 10);
      const today = new Date().toISOString().slice(0, 10);
      const [movies, shows] = await Promise.all([
        tmdb.discover('movie', {
          with_watch_providers: allProvidersParam,
          with_watch_monetization_types: SUBSCRIPTION_TYPES,
          watch_region: tmdb.region,
          'primary_release_date.gte': since,
          'primary_release_date.lte': today,
          sort_by: 'popularity.desc',
        }),
        tmdb.discover('tv', {
          with_watch_providers: allProvidersParam,
          with_watch_monetization_types: SUBSCRIPTION_TYPES,
          watch_region: tmdb.region,
          'first_air_date.gte': since,
          'first_air_date.lte': today,
          sort_by: 'popularity.desc',
        }),
      ]);
      const items = dedupe(interleave(movies, shows), new Set(), 20);
      if (items.length) {
        rows.push({ id: 'new', title: 'New on your services', subtitle: 'Released in the last few months', items });
      }
    } catch {
      /* non-fatal */
    }
  }

  // --- 5. Trending (always useful, and the fallback for a fresh install)
  try {
    const items = dedupe(await tmdb.trending('week'), new Set(), 20);
    if (items.length) rows.push({ id: 'trending', title: 'Trending this week', subtitle: null, items });
  } catch {
    /* non-fatal */
  }

  return rows;
}

module.exports = { buildHome, tasteProfile };
