# StreamHub — operational brief

Read this first. It is the working context for anyone (human or Claude Code)
picking this up. `HANDOVER.md` has the history and the work in progress.

## What it is

A personal remote control for a **Thomson Fire TV**. Félix browses everything
across Netflix, Disney+, HBO Max and Prime Video **on his phone**, taps a title,
and the TV opens that service at that title. He does not want to watch on a PC
or a phone — the phone is a menu, the TV is the screen. His stated goal is that
the title **starts playing** without touching the TV remote (see HANDOVER.md for
how far that has got).

The Fire TV app is the whole product. It serves the phone's web UI from its own
assets and answers its API directly, so **the phone talks only to the TV**.

```
phone browser  ──HTTP over home wifi──▶  Fire TV app  ──Android intent──▶  Netflix / Disney+ / HBO Max / Prime Video
   (the UI)                              (server + launcher                     (the actual player)
                                          + accessibility helper)
```

## Constraints that shaped everything

Do not "fix" these — they were investigated, most of them on the real TV.

1. **We cannot play video.** All four services need a licensed Widevine CDM and
   reject unapproved clients. Hand off to the official app; never embed a player.
2. **Fire TV's universal search cannot be used.** It is locked to Amazon's own
   apps (`SHOW_TEXT_SEARCH` permission), and the generic Android search intents
   landed in the **Silk browser** on the real TV. Each service's own search is
   used instead, always pinned to that app's package (`setPackage`) so nothing
   can fall through to a browser. What each does, verified on the TV:

   | Service | Route | Result |
   |---|---|---|
   | Prime Video | `https://app.primevideo.com/search?phrase=…` | search results for the title |
   | Disney+ | `disneyplus://www.disneyplus.com/search` | empty search page; the accessibility helper types the title into `searchEditText` |
   | HBO Max | `https://play.max.com/search` | empty search page; `BlindPlay` types the title via the key helper (screen not readable) |
   | Netflix | app home only | every search/title/watch link tried lands on home |

3. **Exact-title links need each service's content id.** TMDB does not expose
   them. Wikidata does, for part of the catalogue (see HANDOVER.md) — not wired in.
4. **We cannot read "continue watching".** Pinned titles are the honest substitute.
5. **Netflix and HBO Max cannot be read or clicked.** Both draw their whole UI on
   one surface: no accessibility nodes. Netflix also blocks screenshots. The only
   signal from them is Android's media session (`dumpsys media_session`,
   `state=3` = playing, and `description=` names what plays). HBO Max autoplay
   (`picker/BlindPlay.kt`) is therefore blind key presses, verified afterwards:
   the media session must name the title, else Back and report.
6. **Accessibility clicks are not remote presses.** Prime Video ignored
   `ACTION_CLICK` on its highlighted tile (reported as delivered). Real key
   events work — see "Key presses" in HANDOVER.md.

## Layout

```
firetv/                  THE PRODUCT — Android app, Kotlin
  app/src/main/
    java/com/felix/streamhub/
      MainActivity.kt      one screen: pairing code + status
      ControlService.kt    foreground service wrapper
      ControlServer.kt     NanoHTTPD: serves the phone UI + the JSON API
      AppLauncher.kt       deeplink → service search → app home; wakes the screen
      picker/
        ProfilePickers.kt  ALL screen recognition, one section per service
        ProfilePickerService.kt  accessibility service: profile pick, Disney+ typing, Back/Home
        BlindPlay.kt       HBO Max autoplay: type, step, OK, verify via media session
      data/                Tmdb, Omdb, Store, Recommender, Models, Services, Genres, Http
    assets/remote/         THE PHONE UI (html/css/js)
    res/xml/profile_picker_service.xml   accessibility config (package-limited)
  app/src/test/            JVM tests: real ControlServer over HTTP, real screen dumps
    resources/pickers/     uiautomator dumps from the real TV, names replaced

src/, installer/         Legacy Electron desktop app. Not part of the phone→TV flow.
test/                    Node suites; regressions.js also runs the JVM tests
dumps/                   (git-ignored) raw TV screen dumps — contain real profile names
```

## Running the checks

```bash
npm install && npx playwright install chromium   # first time
node test/tv-remote.js      # the phone UI against a mock of the TV's API
node test/regressions.js    # one check per shipped defect; runs the Kotlin JVM tests
cd firetv && ./gradlew assembleDebug :app:testDebugUnitTest
```

- `JAVA_HOME` must be a JDK 17–21 (set for this Windows user to
  `%LOCALAPPDATA%\StreamHub-build-tools\jdk17`). Gradle 8.7 cannot run on the
  Java 25 that Android Studio 2026.1 bundles — so Android Studio itself can't
  open the project until Gradle/AGP are upgraded.
- `test/firetv.js` fails on Windows (it fakes `adb` with a shell script). It
  tests the legacy desktop path; not a regression.

## Conventions

- Comments explain **why**, especially where the code looks odd. Several
  oddities are load-bearing (and cite what the real TV did); read before simplifying.
- Every bug fixed gets a check that would have caught it. Kotlin bugs get a JVM
  test in `firetv/app/src/test/` wired into `test/regressions.js`; prove it fails
  against the old code.
- **Verify on the real TV** before calling anything done. Stand-ins missed most
  of the bugs found so far.
- Error paths must be honest: never report success for something that did not
  happen. The phone's toast says exactly what the TV did (`playMessage` in app.js).
- Never click or press blind on the user's behalf: confirm the screen, then act,
  then confirm again. On a miss, do nothing and say so.
- Phone UI builds DOM via `el()` + `textContent`; no `innerHTML` on API data.
- Pairing tokens go in the **header** (`x-streamhub-token`), never the body. No
  token, device id or API key in any response body (tested).
- Plain language when reporting to Félix; he is not a developer.

## Known risks

1. **Screen recognition breaks when a service redesigns.** Everything is in
   `picker/ProfilePickers.kt`; `adb logcat -s StreamHubPicker` shows the screen
   line by line when it gives up. Add the new dump to the tests.
2. **`targetSdk = 28` is deliberate** (background activity launch). Verified: the
   second launch of a session works. Raising it will break that.
3. **NanoHTTPD thread per connection**, bounded by a 24-permit semaphore.

## Attribution (required, keep it)

TMDB for metadata ("this product uses the TMDB API but is not endorsed or
certified by TMDB"), JustWatch for availability, OMDb for ratings.
