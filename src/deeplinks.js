'use strict';

/**
 * Android package names and launch intents for the four services on Fire TV.
 *
 * A note on what is and isn't possible here. Jumping to a *specific title*
 * inside Netflix or Disney+ needs that service's own internal content id -
 * Netflix's numeric id, Disney's content uuid, Amazon's ASIN. TMDB explicitly
 * does not hand those out ("this is not going to return full deep links"), and
 * there is no legitimate public source for them. So each service gets a ladder:
 *
 *   1. a real deep link, when we happen to have the service's own id
 *   2. the app's in-app search, where the app is known to accept a search URL
 *   3. the app's home screen, which always works
 *
 * and above all of those sits Fire TV's own universal search, which DOES know
 * the real ids and offers "play on Netflix / Disney+ / ..." for a given title.
 * That is usually the fastest way to land on an exact show, so it is the
 * default for title launches.
 */

const PACKAGES = {
  netflix: {
    // Fire TV / Android TV build of Netflix. The phone/tablet build is
    // com.netflix.mediaclient, which some Fire tablets carry instead.
    primary: 'com.netflix.ninja',
    alternates: ['com.netflix.mediaclient'],
    titleUrl: (id) => `https://www.netflix.com/title/${id}`,
    searchUrl: (q) => `https://www.netflix.com/search?q=${encodeURIComponent(q)}`,
  },
  disneyplus: {
    primary: 'com.disney.disneyplus',
    alternates: ['com.disney.disneyplus.androidtv'],
    titleUrl: (id) => `https://www.disneyplus.com/video/${id}`,
    searchUrl: (q) => `https://www.disneyplus.com/search?q=${encodeURIComponent(q)}`,
  },
  hbomax: {
    // WBD renamed the package when Max became HBO Max again; older installs
    // still carry the previous ids, so we try all of them.
    primary: 'com.wbd.stream',
    alternates: ['com.hbo.hbonow', 'com.hbo.max', 'com.wbd.stream.firetv'],
    titleUrl: (id) => `https://www.hbomax.com/video/watch/${id}`,
    searchUrl: (q) => `https://www.hbomax.com/search?q=${encodeURIComponent(q)}`,
  },
  primevideo: {
    // On Fire TV, Prime Video is part of the built-in Amazon video stack.
    primary: 'com.amazon.avod',
    alternates: ['com.amazon.firebat', 'com.amazon.avod.thirdpartyclient'],
    titleUrl: (asin) => `https://www.amazon.com/gp/video/detail/${asin}`,
    searchUrl: (q) => `https://www.primevideo.com/search/ref=atv_nb_sr?phrase=${encodeURIComponent(q)}`,
  },
};

/** Shell-quote a single argument for `adb shell`. */
function q(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

/**
 * Build the `am start ...` command that opens a service on the TV.
 *
 * @param {string} serviceId
 * @param {{contentId?:string, title?:string, mode?:'home'|'search'|'title'}} opts
 * @returns {{cmd:string, kind:string, packages:string[]}}
 */
function launchCommand(serviceId, opts = {}) {
  const svc = PACKAGES[serviceId];
  if (!svc) throw new Error(`Unknown service: ${serviceId}`);
  const packages = [svc.primary, ...svc.alternates];
  const { contentId, title, mode = 'home' } = opts;

  if (mode === 'title' && contentId) {
    return {
      kind: 'deeplink',
      packages,
      cmd: `am start -a android.intent.action.VIEW -d ${q(svc.titleUrl(contentId))} -p ${q(svc.primary)}`,
    };
  }

  if (mode === 'search' && title) {
    return {
      kind: 'in-app-search',
      packages,
      cmd: `am start -a android.intent.action.VIEW -d ${q(svc.searchUrl(title))} -p ${q(svc.primary)}`,
    };
  }

  return {
    kind: 'home',
    packages,
    cmd: `monkey -p ${q(svc.primary)} -c android.intent.category.LAUNCHER 1`,
  };
}

/**
 * Fire TV's own universal search. It knows the real content ids for every
 * installed app, so for "get me to this exact show" this beats guessing.
 */
function universalSearchCommand(query) {
  return {
    kind: 'universal-search',
    packages: [],
    cmd: `am start -a android.search.action.GLOBAL_SEARCH --es query ${q(query)}`,
  };
}

/** Wake the TV / bring it out of screensaver before launching anything. */
function wakeCommand() {
  return 'input keyevent KEYCODE_WAKEUP';
}

function packagesFor(serviceId) {
  const svc = PACKAGES[serviceId];
  return svc ? [svc.primary, ...svc.alternates] : [];
}

module.exports = { PACKAGES, launchCommand, universalSearchCommand, wakeCommand, packagesFor, q };
