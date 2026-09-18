# Handover

Written for whoever picks this up next in Claude Code. `CLAUDE.md` is the
operational brief; this is the history and the honest state of things.

## Where this came from

It started as "an app on my PC where I can log into all four services and watch
my shows in one place" and arrived, over several turns, at something quite
different: **the PC is gone, and the phone is a remote for the Fire TV.** That
change came from Félix, not from me, and it was right. Worth knowing because
there is a large legacy Electron app in `src/` that is no longer the point.

What he actually wants, in his words: *"essentially, there is no actual data
being processed, more of a fancy remote control."*

He is not a developer. Explanations should be in plain language, and a wall of
technical detail is worse than a short answer. He has been patient with a lot of
it already.

## State

| | |
|---|---|
| Fire TV app (`firetv/`) | Complete. Type-checked, never compiled with a real SDK, never run. |
| Phone UI (`firetv/app/src/main/assets/remote/`) | Complete, 28 browser checks passing against a mocked TV. |
| Legacy desktop app (`src/`) | Works. 139 checks. Not part of the phone→TV flow. |
| Overall | **167 automated checks passing. Zero Kotlin compile errors.** |

## The immediate job

**Get `./gradlew assembleDebug` to succeed**, then install on the TV and find
out what reality says. Everything else is secondary — the code is as good as it
can get without hardware.

Suggested first moves:

1. `cd firetv && ./gradlew assembleDebug` and work through whatever it says.
   Dependency versions in `app/build.gradle.kts` were current when written and
   are the most likely first failure.
2. Run `npm test` to confirm the JS side still passes on a real machine (the
   tests were sandbox-bound until recently; the Chromium path is now resolved
   via Playwright with a `CHROME_BIN` override).
3. Once it installs: the four things to observe are whether the universal-search
   intent actually fires, whether the *second* launch of a session works (see
   the `targetSdk = 28` note), whether the phone reaches the TV over his wifi,
   and how the app feels on a low-memory TV.

## What was already found and fixed — don't reintroduce these

Two rounds of adversarial review found 41 real defects across the project. The
ones most likely to creep back:

- **`preferUniversalSearch = false`** made the TV open Netflix's *home screen*
  instead of the title. Without a `contentId`, universal search is the only path
  that reaches a specific title. `test/tv-remote.js` guards the call shape.
- **`targetSdk` above 28** reintroduces the background-activity-launch block, so
  only the first title of a session opens. Silent failure.
- **Returning HTTP 200 with `{ok:false}`** and having the client not check it.
  Two endpoints did this; a missing app reported as a success.
- **`Title.toJson()` dropping `key`.** The phone matches watchlist entries on
  `key`; without it every "is this saved?" check silently answered no and
  tapping twice deleted what you just added.
- **In-memory pairing tokens.** Fire TV kills backgrounded apps constantly, so
  sessions live in `Store` (SharedPreferences), not a field.
- **Genres joined with `,` instead of `|`** in TMDB discover. `,` is AND, so
  "Picks for you" asked for titles carrying all four genres at once and silently
  never rendered.

`test/regressions.js` has one test per defect. If you change behaviour there,
read the comment first — each one is a scar.

## Open questions for Félix

1. **Has he checked the TV's Fire OS version?** Asked several times, never
   answered. `Settings → My Fire TV → About`: a version starting 3/5/6/7/8/14 is
   fine; one labelled "OS" starting with 1 is Vega, and the whole approach is
   dead. Worth confirming before he installs Android Studio.
2. **Does the legacy `src/` Electron app stay?** It is dead weight for what he
   wants now. Deleting it would halve the repo. It was left in only because it
   works and he may want the desk-browsing surface one day.
3. **Is one extra press on the TV remote acceptable?** He has been told, and
   accepted it, but it is the main gap between this and what he first pictured.

## Things I could not verify, and you should not assume

- No Fire TV hardware, ever. Nothing in `firetv/` has executed.
- `NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE` could not be confirmed to exist
  in 2.3.1, so the body-size refusal uses `BAD_REQUEST`. If you confirm it is
  there, 413 is more correct.
- The `AsyncRunner` interface shape for bounding the thread pool. Attempted,
  reverted, replaced with a semaphore. See the comment in `ControlServer.serve`.
- Whether a plain-HTTP LAN origin lets the phone install the UI as a proper
  standalone web app. Service workers need a secure context, so expect a browser
  shortcut rather than a true installed app. He was told this after I had
  initially over-claimed it.

## Tone note

When reporting back to him: lead with what he can do, keep the mechanism brief,
and be straight about what is unverified. He asked for a "comprehensive debug"
twice and both times it found something serious, so he is right to be
sceptical — reward that rather than reassuring him.
