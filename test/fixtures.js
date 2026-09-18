/* Fake data shaped exactly like what the main process returns, so the
   renderer can be exercised headlessly without any API keys. */

window.__FIXTURES__ = (() => {
  const SERVICES = [
    { id: 'netflix', name: 'Netflix', short: 'N', color: '#e50914', accent: '#ff2d3a' },
    { id: 'disneyplus', name: 'Disney+', short: 'D+', color: '#0e2a6e', accent: '#2f7bff' },
    { id: 'hbomax', name: 'HBO Max', short: 'HBO', color: '#4b1fa8', accent: '#8b5cf6' },
    { id: 'primevideo', name: 'Prime Video', short: 'PV', color: '#0f4c81', accent: '#00a8e1' },
  ];

  const mk = (id, title, year, type, score) => ({
    id,
    mediaType: type,
    title,
    year,
    date: `${year}-01-01`,
    poster: null,
    backdrop: null,
    overview: 'A placeholder synopsis used only by the smoke test.',
    score,
    votes: 900,
    genreIds: [18, 80],
    key: `${type}:${id}`,
  });

  const items = [
    mk(1396, 'Breaking Bad', 2008, 'tv', 92),
    mk(136315, 'Dune', 2021, 'movie', 78),
    mk(94605, 'Arcane', 2021, 'tv', 88),
    mk(496243, 'Parasite', 2019, 'movie', 85),
    mk(60625, 'Rick and Morty', 2013, 'tv', 42),
  ];

  const state = {
    settings: {
      tmdbKey: 'FAKEKEY',
      omdbKey: 'FAKEOMDB',
      region: 'BE',
      language: 'en-US',
      browser: 'auto',
      profileMode: 'shared',
      windowSize: '1600,900',
    },
    watchlist: [items[1], items[3]],
    pinned: [items[0]],
    history: [items[0]],
    serviceOpens: { netflix: 3 },
  };

  return { SERVICES, items, state };
})();
