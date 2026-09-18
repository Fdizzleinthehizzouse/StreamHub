'use strict';

/**
 * The four services StreamHub knows about.
 *
 * `home`        - opened when you click the service in the sidebar
 * `search`      - template used to jump to a specific title (%s = url-encoded title)
 * `tmdbNames`   - names TMDB/JustWatch may use for this provider, in order of
 *                 preference. Matched case-insensitively against the provider
 *                 list TMDB returns for your region, so we never hardcode IDs
 *                 (they change when services rebrand).
 * `deepLink`    - optional function turning a service-specific id into a direct URL
 */

const SERVICES = [
  {
    id: 'netflix',
    name: 'Netflix',
    short: 'N',
    color: '#e50914',
    accent: '#ff2d3a',
    home: 'https://www.netflix.com/browse',
    search: 'https://www.netflix.com/search?q=%s',
    tmdbNames: ['Netflix', 'Netflix basic with Ads', 'Netflix Standard with Ads'],
  },
  {
    id: 'disneyplus',
    name: 'Disney+',
    short: 'D+',
    color: '#0e2a6e',
    accent: '#2f7bff',
    home: 'https://www.disneyplus.com/home',
    search: 'https://www.disneyplus.com/search?q=%s',
    tmdbNames: ['Disney Plus', 'Disney+', 'Disney Plus Standard with Ads'],
  },
  {
    id: 'hbomax',
    name: 'HBO Max',
    short: 'HBO',
    color: '#4b1fa8',
    accent: '#8b5cf6',
    home: 'https://www.hbomax.com/',
    search: 'https://www.hbomax.com/search?q=%s',
    tmdbNames: ['HBO Max', 'Max', 'Max Amazon Channel', 'HBO Max Amazon Channel'],
  },
  {
    id: 'primevideo',
    name: 'Prime Video',
    short: 'PV',
    color: '#0f4c81',
    accent: '#00a8e1',
    home: 'https://www.primevideo.com/',
    search: 'https://www.primevideo.com/search/ref=atv_nb_sr?phrase=%s',
    tmdbNames: ['Amazon Prime Video', 'Prime Video', 'Amazon Video'],
  },
];

function byId(id) {
  return SERVICES.find((s) => s.id === id) || null;
}

/** Build the URL that jumps to a title inside a service. */
function titleUrl(serviceId, title) {
  const svc = byId(serviceId);
  if (!svc) return null;
  if (!title) return svc.home;
  return svc.search.replace('%s', encodeURIComponent(title));
}

module.exports = { SERVICES, byId, titleUrl };
