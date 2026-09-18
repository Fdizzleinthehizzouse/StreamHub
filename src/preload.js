'use strict';

const { contextBridge, ipcRenderer } = require('electron');

const call = (channel, payload) => ipcRenderer.invoke(channel, payload);

contextBridge.exposeInMainWorld('hub', {
  bootstrap: () => call('app:bootstrap'),

  settings: {
    get: () => call('settings:get'),
    set: (patch) => call('settings:set', patch),
  },

  state: {
    get: () => call('state:get'),
    toggleWatchlist: (item) => call('watchlist:toggle', item),
    togglePinned: (item) => call('pinned:toggle', item),
    clearHistory: () => call('history:clear'),
  },

  open: {
    service: (serviceId) => call('open:service', serviceId),
    title: (serviceId, item) => call('open:title', { serviceId, item }),
    external: (url) => call('open:external', url),
  },

  data: {
    search: (query, page) => call('tmdb:search', { query, page }),
    details: (mediaType, id) => call('tmdb:details', { mediaType, id }),
    home: () => call('tmdb:home'),
    browseService: (serviceId) => call('tmdb:browseService', serviceId),
  },

  tv: {
    status: () => call('tv:status'),
    connect: () => call('tv:connect'),
    disconnect: () => call('tv:disconnect'),
    play: (serviceId, item, contentId) => call('tv:play', { serviceId, item, contentId }),
    search: (query) => call('tv:search', query),
    pushSetup: () => call('tv:pushSetup'),
    key: (key) => call('tv:key', key),
  },

  remote: {
    info: () => call('remote:info'),
    start: (port) => call('remote:start', port),
    stop: () => call('remote:stop'),
    revoke: () => call('remote:revoke'),
  },

  /** The phone can change the watchlist; this lets the desktop UI keep up. */
  onStateChanged: (fn) => {
    const listener = (_evt, state) => fn(state);
    ipcRenderer.on('state:changed', listener);
    return () => ipcRenderer.removeListener('state:changed', listener);
  },

  img: (pathname, size = 'w342') => (pathname ? `https://image.tmdb.org/t/p/${size}${pathname}` : null),
});
