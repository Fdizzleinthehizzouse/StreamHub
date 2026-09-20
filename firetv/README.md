# StreamHub — your phone as a TV remote

Browse everything on Netflix, Disney+, HBO Max and Prime Video from your phone.
Tap a film and it opens on the telly.

No computer involved. Your phone talks straight to the TV over your own wifi.

---

## First, a 20-second check

On the TV: **Settings → My Fire TV → About**, and look at the software version.

- Starts with **3, 5, 6, 7, 8 or 14** — good, carry on.
- Says **"OS"** and starts with **1** — stop here. That's Amazon's newer system
  and it won't allow this. Nothing below will work, and there's no way round it.

Fire TV *televisions* like yours are almost always fine. Only two of Amazon's
recent *sticks* have the newer system.

---

## Just want it installed?

You don't need this page or a computer. There's a ready-made, signed app file
on the releases page, and **[INSTALL.md](../INSTALL.md)** walks through putting
it on the TV from the sofa, with no commands at all:

<https://github.com/Fdizzleinthehizzouse/StreamHub/releases/latest>

The rest of this page is for building it yourself, and for the two extras
(Steps 4 and 5) that do need a computer whichever way you installed.

---

## What you're going to do

Three steps. The first one is the only fiddly bit, and you only ever do it once.

1. Turn the code into an app file (on your computer, ~30 minutes)
2. Copy that file onto the TV (~5 minutes)
3. Open a web address on your phone (~2 minutes)

---

## Step 1 — make the app file

Skip this if you downloaded the ready-made file above.

### The quick way, if you have the signing key

From the `firetv` folder:

```
./gradlew assembleRelease
```

The finished file lands at:

```
firetv/app/build/outputs/apk/release/app-release.apk
```

That is the file to hand to someone, and the one attached to each release
(renamed `StreamHub.apk` there). It is signed, so a Fire TV will install it and
later versions will install over it.

It needs a JDK between 17 and 21 on `JAVA_HOME` — Gradle 8.7 can't run on the
Java 25 that current Android Studio bundles, which is also why Android Studio
can't open this project until Gradle and AGP are upgraded.

**It will stop and tell you if `firetv/keystore.properties` is missing**, rather
than hand you an unsigned file that no TV will install. See "The signing key"
below.

### The Android Studio way

Gives you an unsigned debug file, which installs fine but can never be updated
over by a real release. Use it for testing, not for giving to anyone.

1. Download **Android Studio** from <https://developer.android.com/studio> and
   install it. Accept the defaults.
2. Open it. Choose **Open** (not "New Project") and pick this `firetv` folder.
3. Wait. The first time, it downloads bits and pieces for a few minutes. You'll
   see a progress bar at the bottom. Let it finish.
4. From the menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. When it finishes, a small box appears bottom-right saying the build
   succeeded, with a **locate** link. Click it to find the file. It's called
   `app-debug.apk`.

---

## The signing key

Android identifies an app by who signed it. An update signed with a different
key **will not install** over an installed StreamHub — the only way past that is
to uninstall first, which erases the watchlist and pinned titles.

So the key that signed the release matters, and there is exactly one copy of it:

```
C:\Users\Felix\StreamHub-signing\
    streamhub-release.jks    the key itself
    password.txt             its password, alias, and this warning again
```

**Back that folder up somewhere that isn't this computer** — a password manager
as an attachment, a USB stick in a drawer, a private cloud folder. If it's lost,
no future version of StreamHub can ever update an installed one, for anyone.

It is deliberately outside the repo, and `*.jks` is git-ignored, so it can't be
committed by accident. The build finds it through `firetv/keystore.properties`
(also git-ignored) — if you move the key, edit that file's `storeFile` line.
Use forward slashes there: a `.properties` file reads `\` as an escape
character, so a Windows path with backslashes silently turns to nonsense.

**Starting fresh on another machine** (no key yet): create one with
`keytool -genkeypair -v -keystore streamhub-release.jks -storetype PKCS12
-alias streamhub -keyalg RSA -keysize 4096 -validity 10000`, then write a
`firetv/keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. Anything you build with it is a *different app* as far as every
already-installed copy is concerned.

---

## Step 2 — put it on the TV

**On the TV**, turn on two settings:

- **Settings → My Fire TV → Developer Options** → switch on **ADB debugging**
  and **Apps from Unknown Sources**
- Can't see Developer Options? Go to **About**, highlight your TV's name, and
  press the middle button seven times. It'll appear.

Also note down the TV's address: **Settings → My Fire TV → About → Network**.
It looks like `192.168.1.42`.

**Back on the computer**, open a terminal in the folder where the APK is and run:

```
adb connect 192.168.1.42
adb install app-release.apk
```

(`app-debug.apk` if you built it the Android Studio way. Going the other
direction later — release over debug, or debug over release — fails, because
they're signed differently; `adb uninstall com.felix.streamhub` first, which
erases the watchlist.)

(Use your TV's actual number. `adb` came with Android Studio.) Look at the TV
after the first command — it'll ask permission. Say yes.

StreamHub now appears on the TV under **Your Apps & Channels**.

---

## Step 3 — your phone

Open StreamHub on the TV. It shows two things: a web address and a six-character
code.

On your phone, type that address into your browser. It'll ask for the code —
type it once and you're done. Then tap the cog at the top and paste in a free
key from <https://www.themoviedb.org/signup> (sign up, then Settings → API,
copy the one labelled **API Key (v3 auth)**). That's what fetches the film
information.

Optionally add a key from <https://www.omdbapi.com/apikey.aspx> for Rotten
Tomatoes and IMDb scores.

Tip: use your phone browser's **Add to Home Screen** so it's one tap away.

---

## Step 4 (optional) — the TV helper

This one switch turns on three things:

- **Your profile, picked for you.** After pairing, your phone asks which
  **Disney+** and **Prime Video** profile is yours. When you send a title, the
  TV clicks it on the "Who's watching?" screen. It only clicks when it can see
  the picker **and** finds your exact name on it. Otherwise it does nothing and
  leaves you on the picker. Tested live on Disney+. On Prime Video it has
  only been checked against a saved copy of its picker screen, because Prime
  kept skipping the picker during testing.
- **Disney+ search filled in.** Disney+ opens its search page empty; the
  helper types the title in.
- **Back and Home** still work from the phone when the key helper (below)
  isn't running.

Netflix and HBO Max can't use any of this: their apps don't let anything else
read their screen. (HBO Max autoplay uses the key helper alone.) It acts only right after you send a title or tap a button,
only sees Disney+ and Prime Video's screens, and records nothing.

Fire TV has no settings screen for this, so it's switched on from the computer,
once. First check nothing else is using it:

```
adb shell settings get secure enabled_accessibility_services
```

If that prints `null`, run:

```
adb shell settings put secure enabled_accessibility_services com.felix.streamhub/com.felix.streamhub.picker.ProfilePickerService
adb shell settings put secure accessibility_enabled 1
adb reboot
```

If it printed something else, keep it by putting it first, followed by a colon,
so you don't switch that off:
`...enabled_accessibility_services <what it printed>:com.felix.streamhub/com.felix.streamhub.picker.ProfilePickerService`

To switch it off again: `adb shell settings delete secure enabled_accessibility_services`
and reboot. (That also switches off anything else that was listed. If
something was, put it back with the `put` command above, leaving StreamHub out.)

**When it stops working** — likely after Disney+ or Prime Video redesign that
screen — you're just back to choosing with the remote. To see what went wrong,
run `adb logcat -s StreamHubPicker` and send a title. The recognition lives in
one file, `picker/ProfilePickers.kt`.

---

## Step 5 — remote keys (power, arrows, transport, volume)

The phone's **Services** tab has a full remote: **Wake TV** and **Sleep**,
the arrows around OK, Back and Home, ⏮ ⏪ ⏯ ⏩ ⏭, and volume down / mute / up.

**Turning the TV on works without any of the setup below.** The TV stays on
your wifi while it sleeps, so **Wake TV** reaches it and the app wakes the
screen itself. Everything else needs the key helper.

**You rarely need that button.** Sending a title wakes the TV by itself when
it's asleep, and the phone says "Woke the TV" so a dark screen and a few
seconds of nothing don't look like a failure. It only wakes the TV when the TV
was actually asleep.

Fast-forward and rewind do whatever the app you're watching does with them —
on Prime Video they open its scrubbing bar and pause, and ⏯ starts it again,
exactly as with the TV's own remote. ⏮ and ⏭ move between episodes.

Apps aren't allowed to press keys on a Fire TV, and this TV also refuses ADB
from apps on the TV itself, so a small helper does the pressing. It is started
from the computer with ADB, and only StreamHub can use it (it checks who is
asking, and presses only the buttons on that remote).

**Start it:** double-click `firetv\start-key-helper.bat` and type the TV's
address. It prints `StreamHub key helper ready`.

**After the TV restarts** (power cut, system update — not standby), the helper
is gone. The phone's remote says so; run the script again. ADB debugging has
to stay switched on for this.

**After installing a new StreamHub**, run the script again too: a helper that
was already running keeps the old version's abilities (HBO Max autoplay needs
the new one). The script replaces it.

Besides those buttons, it can type a title (letters, digits and spaces
only, used for HBO Max's search) and report which app is in front and what is
playing. It never runs anything it is sent.

---

## Using it

Browse, search or pick a genre on your phone. Everything shown is on at least
one of your four services, tagged with which.

**Browsing keeps going.** A genre, or a service from the Services tab, opens a
list that loads more as you scroll — the whole catalogue TMDB knows about, not
the first screenful. Order it by **Popular**, **Newest** or **Highest rated**,
and narrow it to **Films** or **Series**. Each new batch is checked against
your services before it appears, so a long scroll gets gradually slower; that
check is remembered for half an hour.

Tap a film, tap a service, and the TV opens it. How close it gets depends on
the service (tested on a real Fire TV):

| Service | What happens |
|---|---|
| **Prime Video** | **starts playing by itself** (with the TV helper and key helper on); otherwise its search results |
| **Disney+** | **starts playing by itself**, in your profile (same); otherwise its search, filled in |
| **HBO Max** | **starts playing by itself** (key helper on); otherwise its search page, where you type |
| **Netflix** | **its search results, with the title highlighted** — you press OK on the phone. Otherwise its home screen |

"Starts by itself" means the TV finds the exact title, opens it and presses
Play, checking at every step that the highlight is on the right thing. Your
phone shows each step and says "▶ Playing" only once the TV confirms it. If
anything doesn't match — the title isn't in the results, a different title
opened, your profile isn't on the picker — it stops and tells you where.

It never starts something that isn't included in your subscription (a rental
or purchase): for those you just get the service's page.

**HBO Max is different.** Its screen can't be read, so the TV can't check
anything *before* pressing. It types the title into HBO Max's search, opens
the first result and presses Play, then checks what HBO Max says is playing.
If that isn't your title it stops it straight away and tells you. A film must
match by its exact name, so "Dune: Part Two" never passes for "Dune". The one
thing it can't catch is two different films with the very same name: HBO
Max's top result wins. Allow ~20 s (~35 s if HBO Max wasn't already open).

**Netflix stops one press short, on purpose.** It closes and reopens Netflix
(so it always starts at "Who's watching?"), picks your profile by counting
down the list, opens Netflix's search, types the title and leaves the first
result highlighted — about 50 s. Then you press OK on the phone's remote pad.

It doesn't press Play itself because Netflix gives the TV nothing to check
against: its screen can't be read, screenshots are blocked, and it reports
"playing" with no title even for the trailers on its own home screen. An
earlier version did press Play, and once started the last thing watched
instead of the title asked for, with no way to notice. One press by you is
worth more than that.

For this, tell your phone **which place in Netflix's list your profile is**
(Settings → Your profiles): its names can't be read, only counted. Without it,
Netflix just opens as before.

---

## If something's wrong

**Phone says it can't find the TV** — check they're on the same wifi. Some
routers keep guest wifi separate from the main one.

**A film says "not installed"** — that service's app isn't on the TV. Install it
from the Fire TV appstore as normal.

**Nothing loads at all** — the film-information key is probably missing or
mistyped. Cog → re-paste it.

**Someone else's phone got in** — press **New pairing code** on the TV. Every
paired phone is kicked off and you re-pair yours. The TV screen shows how many
phones are currently paired, so you can check.

**The TV rebooted** — the remote starts itself again automatically. Your phone
stays paired; you don't have to redo anything.

---

## Being straight with you about what's tested

- **On a real Fire TV** (Fire OS 7.7.1.4), driven over wifi the same way a
  phone does it:
  - it installs, finds all four services, and opens them, including as the
    second launch of the evening
  - sending a title does what the table above says, on each service
  - Disney+ picks the right profile and fills in its search
  - search and genres show real, correctly tagged results for Belgium
  - Back and Home work, and launching wakes the TV from its screensaver
- That testing found and fixed real bugs that stand-ins could never have
  shown. The biggest: sending a title used to open the **Silk web browser**
  (that's where the sportzx.org page came from), while the phone said it
  worked.
- Automated checks: Kotlin tests that run the real TV server and read real
  saved screens from your TV (`firetv/app/src/test`), plus 40+ checks of the
  phone page in a real browser.
- **Not yet tried with a real phone's browser** — everything above was driven
  from a computer on the same wifi.

---

Film information comes from TMDB. This product uses the TMDB API but is not
endorsed or certified by TMDB. What's on each service comes from JustWatch.
Ratings come from OMDb.
