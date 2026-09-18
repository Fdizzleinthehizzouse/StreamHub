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

## What you're going to do

Three steps. The first one is the only fiddly bit, and you only ever do it once.

1. Turn the code into an app file (on your computer, ~30 minutes)
2. Copy that file onto the TV (~5 minutes)
3. Open a web address on your phone (~2 minutes)

---

## Step 1 — make the app file

The code needs turning into something a TV can install. That's what Android
Studio does. It's free and it's a big download, but you only need it once.

1. Download **Android Studio** from <https://developer.android.com/studio> and
   install it. Accept the defaults.
2. Open it. Choose **Open** (not "New Project") and pick this `firetv` folder.
3. Wait. The first time, it downloads bits and pieces for a few minutes. You'll
   see a progress bar at the bottom. Let it finish.
4. From the menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. When it finishes, a small box appears bottom-right saying the build
   succeeded, with a **locate** link. Click it to find the file. It's called
   `app-debug.apk`.

**If it shows errors instead:** copy the red text and send it to me. That's a
normal part of this and it's usually a one-line fix. I can't test this step
myself, so you may well be the first to run it.

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
adb install app-debug.apk
```

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

## Using it

Browse or search on your phone. Tap a film. It tells you which of your four
services has it. Tap that, and the TV opens Netflix (or whichever) at that film.

**One thing to expect:** you'll still press OK once on your TV remote to
actually start playing. Netflix and Disney+ don't publish the information an
outside app would need to press play for you — nobody outside their own
partnerships can do it. So it's phone-tap, then one press on the remote.

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

- Every line of the TV app has been through a real Kotlin compiler with no
  errors, using a harness I proved works by deliberately breaking things twelve
  different ways and confirming each break was caught.
- The phone side is tested end to end in a real browser against a stand-in for
  the TV — 28 checks covering pairing, wrong codes, settings, search, sending a
  film, a service that isn't installed, and the TV being switched off.
- Two independent reviews went through the rewrite line by line and found 20
  real defects, all since fixed. The worst would have meant only the *first*
  film you picked ever played, and another would have opened Netflix's home
  screen instead of your film.
- **But I have never run this on a Fire TV**, because I don't have one. The
  build step and how the TV behaves in the room are unproven. Expect at least
  one hiccup, and send me whatever you see.

### The one I'd watch for

Android normally stops an app from opening other apps while it's in the
background. That would have meant only your first pick of the evening worked.
The app now tells Android it's built for an older version, which exempts it —
a normal thing to do for an app you install yourself. If you find the second
film of the evening doesn't open, that's the cause, and tell me.

---

Film information comes from TMDB. This product uses the TMDB API but is not
endorsed or certified by TMDB. What's on each service comes from JustWatch.
Ratings come from OMDb.
