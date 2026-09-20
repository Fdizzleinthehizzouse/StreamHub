# Handover

`CLAUDE.md` is the operational brief; this is the state of play, what was
learned on the real TV, and exactly where work stopped. Last updated 2026-09-19.

## Where this came from

It began as "log into all four services on my PC" and became, at Félix's
direction, **the phone is a remote for the Fire TV**. The legacy Electron app in
`src/` is from that first phase. Félix is not a developer: plain language,
lead with what he can do, be straight about what is unverified. He tests
carefully and pushes back when something is overstated — reward that.

## State

| | |
|---|---|
| Fire TV app | Builds, installed and running on the real TV. |
| Phone UI | Works against the TV (driven from a PC); **not yet used from a real phone's browser**. |
| Commits | On `master`, not pushed. |

Working on the real TV today:
- Pairing; per-phone watchlist, pinned and recommendations (keyed on a device id
  the phone generates — survives re-pairing).
- Sending a title → each service's own search (table in CLAUDE.md). Never Silk.
- Search and genre browsing show only titles on the four services, tagged.
- Profile picking on **Disney+** (verified live) and Disney+ search typing.
  **Prime Video** profile picking is tested only against a saved screen: Prime
  skipped its picker in every later live test.
- Launching wakes the TV from its screensaver. Back / Home from the phone.

**Félix's own phone has no profile names saved** (device `65112f…` answered the
profile step with nothing), so nothing gets picked for him until he fills in
Settings → Your profiles.

## The real TV

- Thomson Fire TV, model AFT6E0FA, **Fire OS 7.7.1.4 (Android 9, API 28)**, IP
  `192.168.129.76`, StreamHub at `http://192.168.129.76:8723`.
- ADB debugging is ON. The accessibility service is enabled
  (`enabled_accessibility_services = com.felix.streamhub/com.felix.streamhub.picker.ProfilePickerService`).
- Packages: `com.netflix.ninja`, `com.disney.disneyplus`, `com.hbo.hbonow`
  (TV-launcher only), `com.amazon.firebat` (the Prime UI; `com.amazon.avod` is a
  background part — never launch it).
- This PC is paired as a test "phone": device id `0000…c0de`, token in
  `dumps/.pc-token`. Its Prime profile name is the dummy "Nobody Here".

Gotchas that cost time:
- Screensaver after 5 min idle; adb `am start` does not wake it
  (`input keyevent KEYCODE_WAKEUP` does). `uiautomator dump` returns
  "null root node" just after it ends — toggling the service or waiting fixes it.
- adb over wifi drops ("device offline") every so often: `adb disconnect`, `adb connect`.
- The TV's log level hides `Log.d`; use `Log.i`+. Logcat truncates entries at ~4 KB.
- The accessibility service must request `flagIncludeNotImportantViews`, or the
  live tree lacks nodes `uiautomator dump` shows. A reinstall kept old flags
  until set at runtime (done in `onServiceConnected`).
- Disney+ sends one window event before its screen exists, then none: the
  service polls while armed instead of waiting for events.
- Request bodies without a charset were decoded as ASCII by NanoHTTPD
  ("Félix" → "F��lix"); `readBody` decodes UTF-8 itself.
- Git Bash mangles `/sdcard/...` in `adb pull`: use `//sdcard/...`.
- `dumps/tree.js file.xml` prints a uiautomator dump as an indented tree.

## Update — 2026-09-20: Netflix, as far as it can honestly go

Netflix now opens its **search results with the title highlighted**, and Félix
presses OK. Verified live end to end (Wednesday, ~50 s from the phone).

- **Its profile is picked by place, not name** (nothing on its screen can be
  read). Stored per phone like the other profile settings, as a number; the
  phone shows a "1st…5th in the list" picker. Mapped with Félix watching the
  TV: the picker is a vertical list, **Up stops at the top** (no wrap), so
  Up x5 then Down x(place-1) lands anywhere without reading a name.
- **Route to search**: from home, Left, Up x8 (reaches the top menu on "Home"),
  Left (Search), OK. Its keyboard is HBO Max's, so `SearchKeyboard` is shared,
  and Right from the last-typed key enters the first result.
- **Typing must be slow.** At full speed "wednesday" arrived as "wededy", so
  the key helper now types one character at a time (150 ms apart, v3).
- **Netflix is restarted first** (`STOPAPP`, helper v3, Netflix only): an app
  already open could be on any screen, and no screen here can be read.
- **Why it stops short — and a real failure.** The first version pressed Play.
  It started *the last thing watched* instead of the title, because the home
  screen wasn't ready and OK landed on its banner; nothing could detect that.
  Checked and rejected as signals: `nflx://…/search?q=` (lost at the profile
  pick), `android.intent.action.SEARCH` (not resolvable), the resumed activity
  and window (MainActivity for every screen, playback included), and the media
  session (`state=3`, no title, same for home-screen trailers). See constraint
  7 in CLAUDE.md. Do not "improve" this into pressing Play.

## Update — evening of 2026-09-19: all three readable/driveable services autoplay

Verified live, each from a cold start: Prime Video (Road House 2024, ~21 s),
Disney+ (The Mandalorian, ~24 s, profile picked on the way), HBO Max (House of
the Dragon ~33 s cold; Dune ~18 s warm).

- **HBO Max autoplay** (`picker/BlindPlay.kt`, rules in the HBO section of
  `ProfilePickers.kt`). Found: `play.max.com/search` + typed key events fill
  its search box; the highlight then rests on the key last typed, and Right
  from the keyboard's last column enters the first result; OK → title page with
  Watch focused; OK → plays. Cold start ~14 s to a usable page, warm ~3 s.
  Verification: media session description (`"<episode>, <show>"`, or a film's
  name alone). Films must match exactly; wrong title → Back + honest message.
  Wikidata HBO ids were not used: only ~719 items have P8298.
- **Key helper v2**: `TYPE <a-z0-9 >`, `NOWPLAYING` (JSON), `FRONT`,
  `RUNNING <pkg>`, `VERSION`. An old helper survives an app reinstall and keeps
  old code, so `start-key-helper.bat` now `pkill`s it first; StreamHub checks
  `VERSION` ≥ 2 before HBO autoplay.
- **Stale accessibility tree** (two symptoms, both fixed): Prime's title page
  opens over its hidden search results in the same window → nodes not
  `isVisibleToUser` are skipped (as `uiautomator dump` does). Disney+ swaps
  screens in one window and the service's node cache kept showing the old
  profile screen → every child is `refresh()`ed on read (~200 ms per full
  read on this TV). A `uiautomator dump` "fixes" the cache, which hides the bug
  while debugging.
- **Matching**: an exact title beats a longer one containing it ("The
  Mandalorian" vs "Disney Gallery / Star Wars: The Mandalorian").
- **Never autoplay rentals**: TMDB availability must list the title as
  included on that service (Road House 1989 is rent-only on Prime here).
- Every armed job logs `looking (...) ids=...` every 5 s under
  `StreamHubPicker`: enough to diagnose a redesign from logcat.

## Update — later on 2026-09-19: done since the notes below

- **Full remote on the phone** (arrows, OK, Back, Home, play/pause). The in-app
  ADB plan failed: Fire OS 7.7.1.4 refuses ADB from apps on the TV (every
  address; the same client from a PC works). Instead `keys/KeyServer` runs from
  StreamHub's APK via `app_process` as the shell user (started per boot with
  `firetv/start-key-helper.bat`), injects keys like `input`, listens on
  127.0.0.1:8724 (a unix socket is denied by SELinux) and serves only
  StreamHub's uid via /proc/net/tcp. It also answers `PLAYING` from
  `dumpsys media_session`.
- **Autoplay on Prime Video and Disney+**, verified live (~12 s and ~22 s from
  fully closed). Recognisers `resultTile` / `playButton` / `pageTitle` in
  `picker/ProfilePickers.kt`; every press = focus, re-read the node, OK key.
  Playing is confirmed from the media session; the phone follows `/api/autoplay`.
- **HBO Max:** `https://play.max.com/<P8298 id>` (e.g. `show/<uuid>`) opens the
  exact title page, no picker, with "Watch S1 E1" highlighted; OK plays it, and
  the media session reports the title ("…, The Last of Us"). Not built: pressing
  OK there can't be confirmed first (screen unreadable) — awaiting Félix's call.
- **Netflix:** still no route to a title; the phone's remote pad is the answer.

The sections below are the earlier state, kept for the history.

## Work in progress: autoplay + full remote

Félix wants (1) the title to **start playing** when he taps it on the phone,
without the TV remote, (2) **OK and arrow buttons** on the phone. He approved
the approach below for the remote. Nothing of it is built in the app yet; all
findings are from adb experiments.

### Key presses (the enabling piece)

Apps cannot inject keys, and accessibility clicks are ignored by Prime. But the
**adb shell user is in the `input` group**, so it can write real key events to
the virtual keyboard device `/dev/input/event9` ("amzkeyboard"):

```
sendevent /dev/input/event9 1 <code> 1; sendevent /dev/input/event9 0 0 0
sendevent /dev/input/event9 1 <code> 0; sendevent /dev/input/event9 0 0 0
```

Codes: UP 103, DOWN 108, LEFT 105, RIGHT 106, OK (SELECT) 353, BACK 158,
HOME 172, PLAYPAUSE 164. ~200 ms per press (vs 1–1.4 s for `input keyevent`).
Verified: arrows move focus, OK opens the highlighted item in Prime.

Plan agreed with Félix: StreamHub connects to its **own** adbd on
`localhost:5555` with an embedded ADB client (e.g. the `dadb` library), which
needs a one-time "Allow USB debugging?" approval on the TV and ADB debugging to
stay on. Lock the API to a fixed set of keys — never arbitrary shell. The
device path `event9` should be found by name at runtime, not hard-coded.

### Autoplay, per service

- **Prime Video — proven by hand.** Search link → results; confirm the focused
  tile's content-desc starts with the title → OK → title page; confirm focus is
  `watch_now_button` → OK → `PlaybackActivity`, media session `state=3`. Needs:
  navigating to the right tile when it isn't the first (move, re-read focus,
  bounded), and a profile pick first on cold start.
- **Disney+ — likely the same** (screens readable, search typed in); untested.
- **Netflix — unsolved.** Cold start always shows "Who's watching?", and a deep
  link given at that point is lost after the pick (lands on home).
  `nflx://www.netflix.com/watch/<id>`, `https://…/watch/<id>` and
  `…/title/<id>` all ended on home even with a profile already chosen (tested
  with Stranger Things, 80057281). Screen unreadable and screenshots blocked;
  only the media session shows whether something is playing.
- **HBO Max — untested.** Search page only; screenshots do work.
  `https://play.max.com/show/<uuid>` (from Wikidata) not yet tried.

### Content ids: Wikidata (legitimate, CC0)

TMDB id → Wikidata item via `haswbstatement:P4947=<movie>` / `P4983=<tv>`, then
`wbgetentities`. Properties: Netflix P1874, HBO Max P8298 (`show/<uuid>`),
Disney+ P7595 movie / P7596 series, Prime P8055 (ASIN, often US) / P14462 (GTI).
Coverage is partial (House of the Dragon had none). The SPARQL endpoint was very
slow; the regular API rate-limits bursts — cache and go slowly.

## What to do next

1. Build the in-app ADB client and `/api/remote` for up/down/left/right/ok/back/
   home; phone button pad. Handle the one-time approval prompt honestly.
2. Prime autoplay using key presses + the confirm-before-each-press loop; report
   "playing" only when the media session says so.
3. Try Disney+ the same way; try HBO Max show links; revisit Netflix.
4. Then "the remaining fixes" Félix mentioned (ask him for the list — it was
   sent to a side session that hit a usage limit).

## Open questions for Félix

- Does the legacy `src/` Electron app stay? It is dead weight for the TV flow.
- Push to a remote? Nothing has been pushed.
- Android Studio needs a Gradle/AGP upgrade to open the project (Java 25).
