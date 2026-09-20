# Installing StreamHub on a Fire TV

StreamHub turns your phone into the remote for your Fire TV. You browse
Netflix, Disney+, HBO Max and Prime Video on your phone, tap a film, and the TV
opens it.

This page is for installing it. You don't need to know anything technical, and
you won't have to type any commands. It takes about fifteen minutes, most of
which is the TV thinking.

**What you need:** the Fire TV, a phone, and both on the same home wifi.

---

## First, a 20-second check

This doesn't work on every Fire TV, and it's better to find out now.

On the TV, go to **Settings → My Fire TV → About** and look at the software
version.

- It starts with **3, 5, 6, 7, 8 or 14** → good, carry on.
- It says **"OS"** and starts with **1** → stop here. That's Amazon's newer
  system, which doesn't allow apps from outside its own store. Nothing below
  will work and there's no way around it.

Fire TV *televisions* are almost always fine. Only two of Amazon's recent
*sticks* have the newer system.

---

## Step 1 — Let the TV accept an app that isn't from Amazon

StreamHub isn't in Amazon's store, so the TV needs permission to install it.

1. On the TV: **Settings → My Fire TV → Developer Options**.

   **Can't see "Developer Options"?** It's hidden until you ask for it. Go to
   **Settings → My Fire TV → About**, move the highlight onto your TV's name,
   and press the middle button of the remote **seven times**. A message counts
   you down. Then go back — Developer Options is now there.

2. Switch on **Apps from Unknown Sources**. The TV will warn you; that warning
   is about apps in general, and saying yes is what lets StreamHub install.

3. While you're on this screen, also switch on **ADB debugging**. You don't
   need it to install StreamHub, but you will need it if you want the extra
   features in Step 6.

---

## Step 2 — Install "Downloader" on the TV

Downloader is a free app, in Amazon's own store, whose whole job is to fetch a
file from a web address. It's the normal way to put an app like this on a Fire
TV.

1. On the TV, go to **Find → Search** (the magnifying glass) and search for
   **Downloader**.
2. It's the one with the **orange icon**, by AFTVnews. Install it, then open it.
3. The first time it opens, it asks to be allowed to manage files. Say
   **Allow** — it can't install anything without that.

---

## Step 3 — Type in the download address

In Downloader you land on a **Home** screen with a single box for a web address
and a **Go** button.

Click the box, and type this in with the on-screen keyboard:

```
https://github.com/Fdizzleinthehizzouse/StreamHub/releases/latest/download/StreamHub.apk
```

It's long, and typing it with a remote is the most tedious minute of the whole
process. Two things worth knowing:

- The address is **case-sensitive** — capital S and H in StreamHub, capital F
  in Fdizzleinthehizzouse.
- It always points at the newest version, so it's worth keeping. If you ever
  reinstall or update, it's the same address.

Press **Go**. You'll see it download (about 3 MB, a few seconds), and then the
TV shows an install screen with StreamHub's name on it.

1. Choose **Install**.
2. When it says the app was installed, choose **Done** (not "Open" — it's worth
   seeing where it lives).
3. Downloader then offers to **Delete** the downloaded file. Say yes; it's
   already installed and the file is only taking up room.

---

## Step 4 — Open StreamHub and get the pairing code

On the TV, go to your apps — **Your Apps & Channels** on the home screen, then
**See All** if it isn't on the front row. StreamHub is there. Open it.

It shows one screen, with two things on it:

- a **web address**, something like `http://192.168.1.42:8723`
- a **six-character code**

Leave the TV on this screen. You need both in the next step.

---

## Step 5 — Set up your phone

Your phone doesn't need an app installed. The TV itself serves the remote to
your phone's browser.

1. On your phone, open your normal browser and type in the **web address** the
   TV is showing. It's an address on your own wifi — nothing travels over the
   internet to reach your TV.
2. The page asks for the **six-character code**. Type it in. That's the pairing
   done, once, for that phone.
3. Tap the **cog** at the top. It asks for a key that fetches film information.
   Get a free one:
   - Sign up at <https://www.themoviedb.org/signup>
   - Go to **Settings → API** and ask for a developer key (personal use is
     fine)
   - Copy the one labelled **API Key (v3 auth)** — a 32-character string, not
     the long "Read Access Token"
   - Paste it into the phone page and save.

   Without this key there's nothing for the app to show you, so this step isn't
   optional.
4. Optionally, add a key from <https://www.omdbapi.com/apikey.aspx> in the same
   place, for Rotten Tomatoes and IMDb scores.
5. Last thing: use your browser's **Add to Home Screen**, so StreamHub is one
   tap away instead of a typed address.

**That's it.** Browse on the phone, tap a title, tap a service, and it opens on
the TV.

**If someone else's phone ever gets in**, press **New pairing code** on the TV
screen. That kicks every paired phone off, and you pair yours again.

---

## Step 6 — The one step that still needs a computer

Everything above works with no computer at all. What you have now opens each
service **at your title's search results**, and you press OK — either on the TV
remote or on the phone's remote pad.

There are two extra pieces that make titles **start playing on their own**, and
both have to be switched on from a computer:

- **The TV helper** picks your Disney+ and Prime Video profile on the "Who's
  watching?" screen, and types the title into Disney+'s search.
- **The key helper** presses the remote keys the phone sends, and drives HBO
  Max and Netflix as far as they can be driven.

**This genuinely needs a computer, and there is no way around it.** Fire TV has
no settings screen for switching an accessibility service on — the only way in
is from a computer, over the TV's developer connection. It's a one-off for the
TV helper, which survives restarts. The key helper has to be started again
after the TV fully restarts, though not after ordinary standby.

The instructions, including the exact commands to paste, are in
**`firetv/README.md`, Steps 4 and 5**. They're kept off this page deliberately:
everything here can be done from the sofa, and that one can't.

**If you skip Step 6, StreamHub still works.** You get your title's search
results on each service and press OK yourself. Nothing is broken — it's one
press instead of none.

---

## If something goes wrong

**"App not installed" when Downloader tries to install it.**
Almost always **Apps from Unknown Sources**, in Step 1, didn't get switched on.
Go back and check.

**It installed, but a newer version later refuses to install over it.**
That means the newer file was signed with a different key from the one already
on the TV. Use the address in Step 3, which always points at the proper
release, rather than a file from anywhere else.

**The phone says it can't find the TV.**
They're on different wifi networks. The usual cause is one of them being on the
router's *guest* wifi, which keeps devices apart on purpose.

**Nothing loads on the phone, though it paired fine.**
The film-information key from Step 5 is missing or was mistyped. Tap the cog
and paste it again.

**A title says the service "isn't installed".**
That service's app isn't on the TV. Install it from Amazon's store as normal,
sign in once, and try again.

**The TV restarted, and the remote's buttons stopped working.**
The key helper from Step 6 doesn't survive a full restart (standby is fine).
Start it again from the computer. StreamHub itself, and your pairing, are
unaffected.

---

## Updating later

Install the new version exactly the way you installed this one — Step 3, the
same address. It installs straight over the top and keeps your watchlist, your
pinned titles, your pairing and your keys.

You never have to uninstall first, and you shouldn't: uninstalling erases the
watchlist.

---

Film information comes from TMDB. This product uses the TMDB API but is not
endorsed or certified by TMDB. What's on each service comes from JustWatch.
Ratings come from OMDb.
