'use strict';

/**
 * OMDb gives us the ratings TMDB does not carry: Rotten Tomatoes, IMDb and
 * Metacritic. It is optional - without a key StreamHub just shows the TMDB score.
 *
 * Letterboxd is deliberately absent: their API is invite-only and they state
 * they do not grant access for personal or recommendation projects, so there is
 * no legitimate way to pull Letterboxd ratings into an app like this.
 */

const BASE = 'https://www.omdbapi.com/';

class Omdb {
  constructor(getSettings) {
    this.getSettings = getSettings;
    this._cache = new Map();
    this.ttl = 1000 * 60 * 60 * 24;
  }

  get key() {
    return (this.getSettings().omdbKey || '').trim();
  }

  /**
   * @param {string} imdbId e.g. tt0903747
   * @returns {Promise<{rottenTomatoes:?number, imdb:?number, metacritic:?number, rated:?string, awards:?string}|null>}
   */
  async ratings(imdbId) {
    if (!this.key || !imdbId) return null;

    const hit = this._cache.get(imdbId);
    if (hit && Date.now() - hit.at < this.ttl) return hit.data;

    const url = new URL(BASE);
    url.searchParams.set('apikey', this.key);
    url.searchParams.set('i', imdbId);
    url.searchParams.set('tomatoes', 'true');

    let json;
    try {
      const res = await fetch(url.toString(), { headers: { accept: 'application/json' } });
      json = await res.json().catch(() => null);
      if (!res.ok && res.status !== 401) return null;
    } catch {
      return null; // a network blip is not worth bothering the user about
    }

    if (!json) return null;

    if (json.Response === 'False') {
      // "no ratings for this title" and "your key is wrong" are very different
      // things, and folding both into null meant a mistyped key looked like
      // every film simply having no Rotten Tomatoes score.
      const reason = String(json.Error || '');
      if (/invalid api key|no api key/i.test(reason)) {
        const e = new Error('OMDb rejected the API key. Check it in Settings — Rotten Tomatoes and IMDb scores are off until it works.');
        e.code = 'BAD_OMDB_KEY';
        throw e;
      }
      if (/request limit reached/i.test(reason)) {
        const e = new Error('OMDb daily limit reached (1,000/day on the free tier). Ratings will come back tomorrow.');
        e.code = 'OMDB_LIMIT';
        throw e;
      }
      return null;
    }

    const find = (source) => {
      const hitRating = (json.Ratings || []).find((r) => r.Source === source);
      return hitRating ? hitRating.Value : null;
    };

    const rtRaw = find('Rotten Tomatoes');
    const imdbRaw = find('Internet Movie Database') || (json.imdbRating !== 'N/A' ? `${json.imdbRating}/10` : null);
    const mcRaw = find('Metacritic') || (json.Metascore !== 'N/A' ? `${json.Metascore}/100` : null);

    const data = {
      rottenTomatoes: rtRaw ? parseInt(rtRaw, 10) : null,
      imdb: imdbRaw ? parseFloat(imdbRaw) : null,
      metacritic: mcRaw ? parseInt(mcRaw, 10) : null,
      rated: json.Rated && json.Rated !== 'N/A' ? json.Rated : null,
      awards: json.Awards && json.Awards !== 'N/A' ? json.Awards : null,
      boxOffice: json.BoxOffice && json.BoxOffice !== 'N/A' ? json.BoxOffice : null,
    };

    this._cache.set(imdbId, { at: Date.now(), data });
    return data;
  }
}

module.exports = { Omdb };
