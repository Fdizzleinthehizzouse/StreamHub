# StreamHub

One window for Netflix, Disney+, HBO Max and Prime Video — with a shared
watchlist, cross-service search, full metadata and recommendations.

---

## Setup (about 3 minutes)

### 1. Install it

Unzip `StreamHub-Installer.zip` somewhere (Downloads is fine) and double-click
**Install StreamHub.bat**.

It downloads the official Electron runtime straight from GitHub (~110 MB, once),
checks it against a known SHA-256, assembles the app in
`%LOCALAPPDATA%\StreamHub`, and puts a shortcut on your desktop and in the Start
menu. No Node, no admin rights, nothing written outside your user folder.

Windows will likely warn you before running the `.bat` — the script isn't
code-signed (a certificate costs a few hundred euros a year). Click **More info →
Run anyway**. The whole script is plain text in `Install-StreamHub.ps1` if you
want to read it first; it's about 120 lines.

To uninstall: delete `%LOCALAPPDATA%\StreamHub` and the two shortcuts.

### 2. Get a TMDB key (required, free)

1. Sign up at <https://www.themoviedb.org/signup>
2. Go to **Settings → API** and request a developer key (personal use is fine)
3. Copy the value labelled **API Key (v3 auth)** — a 32-character string, *not*
   the long "Read Access Token"
4. Paste it into StreamHub's Settings page and hit Save

This drives search, artwork, cast, genres and — through JustWatch — which of your
four services actually carries a title in Belgium.

### 3. Get an OMDb key (optional, free)

<https://www.omdbapi.com/apikey.aspx> — pick the free tier (1,000 lookups/day),
confirm the email they send, paste the key into Settings.

This is what adds **Rotten Tomatoes, IMDb and Metacritic** scores to every title.
Skip it and you still get TMDB's own score.

---

## How it works

**StreamHub does not play video itself, and that is deliberate.** Netflix,
Disney+, HBO Max and Prime Video all require a licensed Widevine CDM to decrypt
their streams. Electron doesn't ship one, WebView2 doesn't support Widevine at
all, and the services reject unapproved clients regardless. Any app claiming to
embed all four players is either broken or doing something that will get your
accounts banned.

So instead, clicking a service opens the **official web player** in a chromeless
Chrome/Edge window — no tabs, no address bar, just the app. Widevine is present,
playback works at full quality, and your credentials stay in your browser where
they belong. StreamHub never sees a password.

You get two login modes in Settings:

| Mode | What happens |
|---|---|
| **Normal profile** (default) | Uses your existing browser profile, so services you're already logged into just work |
| **Separate profiles** | Each service gets its own isolated session — useful if you share an account, or want a second Netflix profile permanently open |

---

## What's in it

**Home** — service tiles, your pinned "continue watching" titles, and rows built
from what you've actually been opening.

**Search** — one box across all four services. Results show where each title
streams in your region; one click opens it there.

**Title view** — year, runtime, seasons/episodes, director or creator, top-billed
cast with photos, genres, synopsis, awards, and ratings from TMDB, Rotten
Tomatoes, IMDb and Metacritic side by side. Plus a "if you like this" row.

**Watchlist** — one saved list spanning all four services, instead of four
separate ones.

**Per-service pages** — popular films, popular series, recently released and
highest-rated, for each service individually.

**Send to TV** — every title has a one-press handoff to the Fire TV, and your
phone can do the same (see below).

**Recommendations** come from two places: your own StreamHub activity (genres you
open and save, fed into TMDB's discover API, filtered to the four services you
pay for), and what the providers themselves are pushing (real JustWatch
provider data, not a guess).

---

## Two honest limitations

**"Continue watching" is manual.** None of the four services expose an API for
your real playback progress, and scraping a logged-in session would mean handling
your credentials and breaking their terms of service. Pinning a show is the
honest substitute: pinned titles sit at the top of Home, one click from where you
left off.

**There are no Letterboxd ratings.** Letterboxd runs an invite-only API and
states plainly that they don't grant access for personal or recommendation
projects, so there's no legitimate way to pull their scores in. Rotten Tomatoes,
IMDb and Metacritic cover the same ground via OMDb, and every title has a
one-click link out to its Letterboxd page.

---

## Your phone as the remote

**TV & Phone → Start remote.** Scan the QR with your phone, type the six-digit
code once, and you're paired.

What you get is the full StreamHub — home rows, search, watchlist, every title's
metadata and ratings — on a device with a real keyboard and a touchscreen,
instead of pecking at a D-pad. Tap a service and it opens on the Fire TV. Tap a
title and it goes to the TV. There's a D-pad remote in there too.

On the phone, use your browser's **Add to Home Screen**. It then opens
fullscreen with its own icon, and behaves like any other app.

The chain is phone → this PC → Fire TV, so **the PC needs to be awake** for the
phone to reach the TV. Everything stays on your own network: no accounts, no
cloud, nothing forwarded through your router. The pairing code exists so a
housemate on the same wifi can't drive your TV by guessing the URL; **Unpair all
phones** rotates it.

## The Fire TV

Two ways to reach the TV, and StreamHub prefers whichever is available:

**The StreamHub TV app** (in the `firetv` folder — see its own README). Once
it's installed, the PC talks straight to it over your network. No developer
mode, no ADB, and it launches things with normal Android intents. This is the
good path. Enter the TV's IP *and* the pairing code it shows under Setup, then
press **Send my API keys to the TV** so you never type an API key with a remote.

**ADB**, if you'd rather not install anything on the TV. Turn on
Settings → My Fire TV → Developer Options → ADB debugging, put the TV's IP into
**TV & Phone**, and connect. StreamHub needs the `adb` command for this — it
ships with Android Studio, and with Google's standalone platform-tools. Worth
knowing: with ADB debugging on, anything already on your home network can send
commands to that TV. Fine on a network you trust, and you can switch it off
when you're not using it.

Either way, sending a specific title to the TV usually goes through Fire TV's
own universal search rather than a direct deep link — the reason is in the
`firetv` README, and it comes down to Netflix and Disney+ not publishing the
content ids you'd need.

## What it costs to run

Measured, not estimated:

| | |
|---|---|
| Disk, installed | **263 MB** |
| Memory, idle | **~300 MB** (PSS, six processes) |
| CPU, idle | **0.1%** of one core |

That memory number is Electron's floor, not StreamHub's code — the app itself
is a few hundred KB. It's roughly what Chrome costs with a handful of tabs
open, and it's the price of the approach: a real Chromium is what makes DRM
playback work at all. If you want that back, quit StreamHub when you're not
using it; the browser windows it opened keep playing on their own.

CPU is the part that matters for a laptop, and at 0.1% idle it is genuinely
asleep between clicks. Nothing polls on the desktop side. The phone remote
polls the PC, but only while the remote is actually on screen — put the phone
in your pocket and it stops entirely.

## Region

Streaming rights differ by country, so Settings has a region code. It defaults to
**BE**. Change it to `FR`, `NL`, `GB`, `US` and so on if you're travelling or
using a VPN — availability updates immediately.

---

## Building from source

```bash
npm install
npm start                       # run it
npm test                        # 139 automated checks
npx electron-builder --win zip  # rebuild the Windows package
```

Your data lives in `%APPDATA%\StreamHub\streamhub.json` — settings, watchlist,
pins and history. Delete it to reset. Nothing is sent anywhere except TMDB and
OMDb.

---

## Attribution

This product uses the TMDB API but is not endorsed or certified by TMDB.
Streaming availability data is provided by JustWatch. Ratings are provided by OMDb.
