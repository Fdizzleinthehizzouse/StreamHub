'use strict';

/**
 * Minimal TMDB v3 client.
 *
 * Attribution (required by TMDB):
 *   This product uses the TMDB API but is not endorsed or certified by TMDB.
 * Watch-provider availability comes from JustWatch via TMDB and must be
 * attributed to JustWatch - StreamHub shows that credit in the footer.
 */

const BASE = 'https://api.themoviedb.org/3';
const IMG = 'https://image.tmdb.org/t/p';

class Tmdb {
  constructor(getSettings) {
    this.getSettings = getSettings;
    this._providerCache = new Map(); // `${type}:${region}` -> [{provider_id, provider_name}]
    this._cache = new Map(); // url -> { at, data }
    this.ttl = 1000 * 60 * 30;
  }

  get key() {
    return (this.getSettings().tmdbKey || '').trim();
  }

  get region() {
    return (this.getSettings().region || 'BE').toUpperCase();
  }

  get language() {
    return this.getSettings().language || 'en-US';
  }

  async _get(pathname, params = {}, { cache = true } = {}) {
    if (!this.key) {
      const e = new Error('No TMDB API key set. Open Settings and paste your key.');
      e.code = 'NO_KEY';
      throw e;
    }
    const url = new URL(BASE + pathname);
    url.searchParams.set('api_key', this.key);
    if (!('language' in params)) url.searchParams.set('language', this.language);
    for (const [k, v] of Object.entries(params)) {
      if (v === undefined || v === null || v === '') continue;
      url.searchParams.set(k, String(v));
    }
    const href = url.toString();

    if (cache) {
      const hit = this._cache.get(href);
      if (hit && Date.now() - hit.at < this.ttl) return hit.data;
    }

    let res;
    try {
      res = await fetch(href, { headers: { accept: 'application/json' } });
    } catch (netErr) {
      const e = new Error('Could not reach TMDB. Check your internet connection and try again.');
      e.code = 'OFFLINE';
      e.cause = netErr;
      throw e;
    }

    if (!res.ok) {
      let detail = '';
      try {
        const body = await res.json();
        detail = body.status_message || '';
      } catch {
        /* ignore */
      }
      // TMDB answers 401 for a bad key; a proxy or a suspended key can surface
      // as 403. Both mean "your key is not working", so say so plainly.
      const keyProblem = res.status === 401 || res.status === 403;
      const e = new Error(
        keyProblem
          ? `TMDB rejected the API key (HTTP ${res.status}). Check it in Settings — it should be the "API Key (v3 auth)" value.`
          : res.status === 429
            ? 'TMDB is rate-limiting you. Wait a moment and try again.'
            : `TMDB request failed (${res.status})${detail ? ': ' + detail : ''}`
      );
      e.code = keyProblem ? 'BAD_KEY' : 'HTTP_' + res.status;
      throw e;
    }
    const data = await res.json();
    if (cache) {
      // Bounded: every distinct search query is a new key, so an unbounded Map
      // grows until the process dies.
      if (this._cache.size > 300) this._cache.clear();
      this._cache.set(href, { at: Date.now(), data });
    }
    return data;
  }

  // ---- providers ------------------------------------------------------

  /** Provider list for the region, so we can match services by name not by id. */
  async providers(type = 'movie') {
    const cacheKey = `${type}:${this.region}`;
    if (this._providerCache.has(cacheKey)) return this._providerCache.get(cacheKey);
    const data = await this._get(`/watch/providers/${type}`, { watch_region: this.region });
    const list = data.results || [];
    this._providerCache.set(cacheKey, list);
    return list;
  }

  /**
   * Resolve our four services to the provider ids TMDB uses in this region.
   * @returns {Promise<Record<string, number[]>>} serviceId -> [providerId]
   */
  async providerIds(services) {
    const [movieList, tvList] = await Promise.all([this.providers('movie'), this.providers('tv')]);
    const all = [...movieList, ...tvList];
    const out = {};
    for (const svc of services) {
      const ids = new Set();
      for (const wanted of svc.tmdbNames) {
        for (const p of all) {
          if (String(p.provider_name).toLowerCase() === wanted.toLowerCase()) ids.add(p.provider_id);
        }
      }
      // Loose second pass: "Netflix Standard with Ads" style variants.
      if (ids.size === 0) {
        const primary = svc.tmdbNames[0].toLowerCase();
        for (const p of all) {
          if (String(p.provider_name).toLowerCase().startsWith(primary)) ids.add(p.provider_id);
        }
      }
      out[svc.id] = [...ids];
    }
    return out;
  }

  // ---- search & details ----------------------------------------------

  async searchMulti(query, page = 1) {
    const data = await this._get('/search/multi', { query, page, include_adult: false });
    return {
      page: data.page,
      totalPages: data.total_pages,
      results: (data.results || [])
        .filter((r) => r.media_type === 'movie' || r.media_type === 'tv')
        .map(normalise),
    };
  }

  async details(mediaType, id) {
    const append =
      mediaType === 'tv'
        ? 'credits,watch/providers,recommendations,external_ids,content_ratings'
        : 'credits,watch/providers,recommendations,external_ids,release_dates';
    const data = await this._get(`/${mediaType}/${id}`, { append_to_response: append });

    const providers = (data['watch/providers'] && data['watch/providers'].results && data['watch/providers'].results[this.region]) || {};

    const crew = (data.credits && data.credits.crew) || [];
    let directors = crew.filter((c) => c.job === 'Director').map((c) => c.name);
    if (!directors.length && Array.isArray(data.created_by)) {
      directors = data.created_by.map((c) => c.name);
    }

    const cast = ((data.credits && data.credits.cast) || []).slice(0, 8).map((c) => ({
      name: c.name,
      character: c.character,
      profile: c.profile_path,
    }));

    return {
      ...normalise({ ...data, media_type: mediaType }),
      overview: data.overview || '',
      runtime: data.runtime || (Array.isArray(data.episode_run_time) ? data.episode_run_time[0] : null),
      seasons: data.number_of_seasons || null,
      episodes: data.number_of_episodes || null,
      status: data.status || null,
      tagline: data.tagline || '',
      genres: (data.genres || []).map((g) => g.name),
      genreIds: (data.genres || []).map((g) => g.id),
      directors,
      cast,
      imdbId: (data.external_ids && data.external_ids.imdb_id) || data.imdb_id || null,
      homepage: data.homepage || null,
      providers: {
        flatrate: (providers.flatrate || []).map((p) => ({ id: p.provider_id, name: p.provider_name, logo: p.logo_path })),
        rent: (providers.rent || []).map((p) => ({ id: p.provider_id, name: p.provider_name, logo: p.logo_path })),
        buy: (providers.buy || []).map((p) => ({ id: p.provider_id, name: p.provider_name, logo: p.logo_path })),
        link: providers.link || null,
      },
      recommendations: ((data.recommendations && data.recommendations.results) || []).slice(0, 18).map(normalise),
    };
  }

  // ---- discovery ------------------------------------------------------

  async discover(mediaType, params = {}) {
    const data = await this._get(`/discover/${mediaType}`, {
      watch_region: this.region,
      include_adult: false,
      'vote_count.gte': 25,
      sort_by: 'popularity.desc',
      ...params,
    });
    return (data.results || []).map((r) => normalise({ ...r, media_type: mediaType }));
  }

  async trending(window = 'week') {
    const data = await this._get(`/trending/all/${window}`, {});
    return (data.results || []).filter((r) => r.media_type === 'movie' || r.media_type === 'tv').map(normalise);
  }

  async genreMap(mediaType) {
    const data = await this._get(`/genre/${mediaType}/list`, {});
    const map = {};
    for (const g of data.genres || []) map[g.id] = g.name;
    return map;
  }

  imageUrl(pathname, size = 'w342') {
    if (!pathname) return null;
    return `${IMG}/${size}${pathname}`;
  }
}

function normalise(r) {
  const mediaType = r.media_type || (r.first_air_date || r.name ? 'tv' : 'movie');
  const title = r.title || r.name || '';
  const date = r.release_date || r.first_air_date || '';
  return {
    id: r.id,
    mediaType,
    title,
    originalTitle: r.original_title || r.original_name || '',
    year: date ? Number(date.slice(0, 4)) : null,
    date,
    poster: r.poster_path || null,
    backdrop: r.backdrop_path || null,
    overview: r.overview || '',
    score: typeof r.vote_average === 'number' ? Math.round(r.vote_average * 10) : null,
    votes: r.vote_count || 0,
    popularity: r.popularity || 0,
    genreIds: r.genre_ids || (r.genres || []).map((g) => g.id),
  };
}

module.exports = { Tmdb, normalise };
