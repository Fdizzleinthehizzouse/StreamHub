# StreamHub — operational brief

Read this first. It is the working context for anyone (human or Claude Code)
picking this up.

## What it is

A personal remote control for a **Thomson Fire TV**. Félix browses everything
across Netflix, Disney+, HBO Max and Prime Video **on his phone**, taps a title,
and the TV opens that service at that title. He does not want to watch on a PC
or a phone — the phone is a menu, the TV is the screen.

The Fire TV app is the whole product. It serves the phone's web UI from its own
assets and answers its API directly, so **the phone talks only to the TV**. A
computer is needed exactly once, to build the APK.

```
phone browser  ──HTTP over home wifi──▶  Fire TV app  ──Android intent──▶  Netflix / Disney+ / HBO Max / Prime Video
   (the UI)                              (server + launcher)                    (the actual player)
```

## Three constraints that shaped everything

Do not "fix" these — they were investigated and they are real.

1. **We cannot play video.** All four services need a licensed Widevine CDM.
   Electron doesn't ship one, WebView2 has no Widevine, and the services reject
   unapproved clients. Hence: hand off to the official app, never embed a player.
2. **We cannot deep-link to an exact title.** That needs each service's internal
   content id (Netflix's numeric id, Disney's uuid, Amazon's ASIN). TMDB
   explicitly does not expose them and there is no legitimate source. So
   `AppLauncher` hands the title to **Fire TV's universal search**, which does
   know the real ids. The user presses OK once on the TV remote. If a real
   `contentId` ever arrives, the deep-link path is already there and is tried first.
3. **We cannot read "continue watching".** No service exposes playback progress,
   and scraping a signed-in session means handling credentials and breaking
   terms. Pinned titles are the honest substitute.

## Layout

```
firetv/                  THE PRODUCT — Android app, Kotlin
  app/src/main/
    java/com/felix/streamhub/
      MainActivity.kt      one screen: pairing code + status. Nothing to browse.
      ControlService.kt    foreground service wrapper
      ControlServer.kt     NanoHTTPD: serves the phone UI + the whole JSON API
      AppLauncher.kt       the handoff ladder (deeplink → universal search → app home)
      BootReceiver.kt      restarts the remote after a TV reboot
      data/                Tmdb, Omdb, Store, Recommender, Models, Services, Http
    assets/remote/         THE PHONE UI (html/css/js) — served by ControlServer
    res/layout/            one layout, activity_main.xml

src/                     Legacy Electron desktop app. Superseded by the above;
                         kept because it still works and shares the phone UI's
                         design. Not part of the phone→TV flow.
installer/               Windows installer for the legacy desktop app.
test/                    Node test suites (see below)
```

## Running the checks

```bash
npm install
npx playwright install chromium      # first time only
npm test                             # 167 checks
```

Individually:

| | |
|---|---|
| `node test/tv-remote.js` | **the important one** — drives the real phone UI against a mock of the TV's API |
| `node test/regressions.js` | one test per defect that shipped once |
| `node test/firetv.js` | ADB command construction (legacy path) |
| `node test/smoke.js` / `remote.js` / `electron-boot.js` | legacy desktop app |

`test/electron-boot.js` needs a display; on headless Linux prefix with `xvfb-run -a`.

The Android side: `cd firetv && ./gradlew assembleDebug`. **This has never been
run.** Everything Kotlin was type-checked against hand-written Java stubs of the
Android SDK (that harness lived in the cloud sandbox and is not in this repo —
with a real SDK installed it is obsolete, just build properly).

## Conventions

- Comments explain **why**, especially where the code looks odd. Several
  oddities are load-bearing; check the comment before simplifying.
- Every bug fixed gets a test in `test/regressions.js` that would have caught it.
- Error paths must be honest: never report success for something that did not
  happen. Two shipped bugs were exactly this.
- The phone UI builds DOM via the `el()` helper and `textContent`. Do not
  introduce `innerHTML` for anything that touches API data.
- Pairing tokens go in the **header** (`x-streamhub-token`), never the body —
  body tokens make the endpoint CORS-reachable from any web page.

## Known risks, in priority order

1. **The Gradle build has never run.** Expect dependency-version friction first.
2. **Background activity launch.** `targetSdk = 28` is deliberate: from API 29
   Android blocks a background app from starting activities, and once we hand
   off to Netflix we *are* the background app. Without this only the first
   launch of a session works. If the second title of an evening silently fails,
   this is why — and raising targetSdk will reintroduce it.
3. **NanoHTTPD spawns a thread per connection.** Bounded by a 24-permit
   semaphore in `serve()`. A proper `AsyncRunner` was attempted and reverted
   because it could not be compiled against the real library in the sandbox.
4. **Nothing has run on real hardware.** Deep links, the universal-search
   intent, focus behaviour and performance on a ~1.5 GB TV are all unobserved.

## Attribution (required, keep it)

TMDB for metadata ("this product uses the TMDB API but is not endorsed or
certified by TMDB"), JustWatch for availability, OMDb for ratings.
