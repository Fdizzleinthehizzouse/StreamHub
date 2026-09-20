package com.felix.streamhub.keys

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The key-press helper. NOT part of the app process: it is started from a PC,
 * once per TV boot, with the adb shell user's rights:
 *
 *   adb shell "CLASSPATH=\$(pm path com.felix.streamhub | cut -d: -f2) \
 *     nohup app_process /system/bin com.felix.streamhub.keys.KeyServer >/dev/null 2>&1 &"
 *
 * Why: apps cannot inject key events, accessibility clicks are ignored by
 * Prime Video, and Fire OS (7.7.1.4) refuses ADB connections from apps on the
 * TV itself - so StreamHub cannot reach adbd. The shell user holds
 * INJECT_EVENTS, so a process started as shell can inject real remote-control
 * presses, the same way the `input` command does, without its 1 s start-up.
 *
 * It listens on 127.0.0.1:[PORT] and serves only StreamHub's uid, looked up
 * in /proc/net/tcp for the caller's connection. (A unix socket would give peer
 * credentials directly, but SELinux denies apps `connectto` a shell socket -
 * seen on the real TV.) Each request is one key name from [KEYS], a short
 * title to type ([TYPE], letters, digits and spaces only), or one of the
 * read-only queries below; nothing a caller sends is ever executed.
 */
object KeyServer {

    const val PORT = 8724

    val KEYS = mapOf(
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "OK" to KeyEvent.KEYCODE_DPAD_CENTER,
        "BACK" to KeyEvent.KEYCODE_BACK,
        "HOME" to KeyEvent.KEYCODE_HOME,
        "PLAYPAUSE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val allowedUid = streamHubUid() ?: run {
            System.err.println("StreamHub is not installed")
            return
        }
        val inject = injector()
        val server = try {
            ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: java.net.BindException) {
            println("StreamHub key helper is already running")
            return
        }
        println("StreamHub key helper ready on 127.0.0.1:$PORT (serving uid $allowedUid)")
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            runCatching {
                client.use { s ->
                    if (callerUid(s.port) != allowedUid) return@use
                    s.soTimeout = 10_000
                    val reader = s.inputStream.bufferedReader()
                    val out = s.outputStream
                    // One connection may carry several requests, one per line.
                    while (true) {
                        val line = reader.readLine()?.trim() ?: break
                        val reply = when {
                            line == VERSION -> "$HELPER_VERSION"
                            line == PLAYING -> playingPackages().joinToString(" ")
                            line == NOW_PLAYING -> nowPlaying()
                            line == FRONT -> frontFrom(dumpsys("window", "windows")) ?: ""
                            line.startsWith("$RUNNING ") -> running(line.removePrefix("$RUNNING "))
                            line.startsWith("$STOP_APP ") -> stopApp(line.removePrefix("$STOP_APP "))
                            line.startsWith("$TYPE ") -> if (type(inject, line.removePrefix("$TYPE "))) "ok" else "no"
                            else -> {
                                val code = KEYS[line]
                                if (code != null && press(inject, code)) "ok" else "no"
                            }
                        }
                        out.write("$reply\n".toByteArray())
                        out.flush()
                    }
                }
            }
        }
    }

    /**
     * Read-only: this helper's version. A helper started before StreamHub was
     * updated keeps running the old code (and answers "no" to this), so
     * StreamHub checks before relying on anything newer than key presses.
     * 2 added typing, NOWPLAYING, FRONT and RUNNING. 3 types slowly and adds
     * STOPAPP (Netflix only).
     */
    const val VERSION = "VERSION"
    const val HELPER_VERSION = 3
    /** "STOPAPP <package>": force-stops a package in [STOPPABLE]. */
    const val STOP_APP = "STOPAPP"

    /** Read-only: which apps' media sessions are playing. */
    const val PLAYING = "PLAYING"
    /** Read-only: the playing sessions with what they say is playing, as JSON. */
    const val NOW_PLAYING = "NOWPLAYING"
    /** Read-only: the package whose window has focus. */
    const val FRONT = "FRONT"
    /** Read-only: "RUNNING <package>" -> "yes" / "no". */
    const val RUNNING = "RUNNING"
    /**
     * "TYPE <text>": types into whatever has focus, as a keyboard would. Only
     * lower-case letters, digits and spaces - all HBO Max's search keyboard
     * has - and a title's length, so it can't be used to enter anything else.
     */
    const val TYPE = "TYPE"
    val TYPEABLE = Regex("^[a-z0-9 ]{1,60}$")

    /**
     * Apps whose media session is playing (state=3), from `dumpsys
     * media_session`. The shell user can read it; apps can't without being a
     * notification listener. It's how "it's playing" is confirmed even for
     * Netflix and HBO Max, whose screens can't be read.
     */
    private fun playingPackages(): List<String> = playingFrom(dumpsys("media_session"))

    private fun nowPlaying(): String {
        val arr = org.json.JSONArray()
        nowPlayingFrom(dumpsys("media_session")).forEach { (pkg, title) ->
            arr.put(org.json.JSONObject().put("package", pkg).put("title", title))
        }
        return arr.toString()
    }

    private fun dumpsys(vararg args: String): List<String> {
        val p = ProcessBuilder(listOf("dumpsys") + args).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readLines()
    }

    private fun running(pkg: String): String {
        if (!Regex("^[\\w.]{1,100}$").matches(pkg)) return "no"
        val p = ProcessBuilder("pidof", pkg).redirectErrorStream(true).start()
        return if (p.inputStream.bufferedReader().readText().trim().isNotEmpty()) "yes" else "no"
    }

    /**
     * One character at a time, with a pause: Netflix dropped letters typed at
     * full speed ("wednesday" arrived as "wededy" on the real TV).
     */
    private fun type(inject: (InputEvent) -> Boolean, text: String): Boolean {
        if (!TYPEABLE.matches(text)) return false
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (c in text) {
            val events = map.getEvents(charArrayOf(c)) ?: return false
            // As the `input text` command does: fresh timestamps, from a keyboard.
            val now = SystemClock.uptimeMillis()
            if (!events.all { e -> inject(KeyEvent.changeTimeRepeat(e, now, 0).apply { source = InputDevice.SOURCE_KEYBOARD }) }) return false
            Thread.sleep(TYPE_GAP_MS)
        }
        return true
    }

    private const val TYPE_GAP_MS = 150L

    /**
     * Closes Netflix, so the next launch starts from its profile screen: its
     * screen can't be read, and an app already open could be anywhere. Only
     * Netflix may be closed this way.
     */
    val STOPPABLE = setOf("com.netflix.ninja")

    private fun stopApp(pkg: String): String {
        if (pkg !in STOPPABLE) return "no"
        ProcessBuilder("am", "force-stop", pkg).redirectErrorStream(true).start().waitFor()
        return "ok"
    }

    /**
     * Pure, for testing: playing sessions and their description's first
     * part, from blocks like
     *   package=com.hbo.hbonow ... state=PlaybackState {state=3, ...
     *   metadata:size=5, description=When You're Lost in the Darkness, The Last of Us, null
     * The description is "title, subtitle, description" joined by ", ", and
     * a title may itself hold commas, so the trailing ", null"s are dropped
     * and the rest kept whole.
     */
    fun nowPlayingFrom(lines: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var pkg: String? = null
        var playing = false
        var desc = ""
        fun flush() { pkg?.let { if (playing) out += it to desc } }
        for (l in lines) {
            val p = Regex("\\bpackage=([\\w.]+)").find(l)
            if (p != null) { flush(); pkg = p.groupValues[1]; playing = false; desc = "" }
            if (l.contains("state=PlaybackState {state=3,")) playing = true
            Regex("metadata:size=\\d+, description=(.*)$").find(l)?.let {
                // A film reads "Dune, , null": empty and null parts both go.
                desc = it.groupValues[1].replace(Regex("(,\\s*(null)?)+$"), "").let { d -> if (d == "null") "" else d }
            }
        }
        flush()
        return out
    }

    /** Pure, for testing: "mCurrentFocus=Window{ee73170 u0 com.hbo.hbonow/...}" -> the package. */
    fun frontFrom(lines: List<String>): String? =
        lines.firstNotNullOfOrNull { Regex("mCurrentFocus=Window\\{\\S+ \\S+ ([\\w.]+)/").find(it)?.groupValues?.get(1) }

    /** Pure, for testing: `package=X` followed (within its block) by `state=PlaybackState {state=3,`. */
    fun playingFrom(lines: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        var pkg: String? = null
        for (l in lines) {
            Regex("\\bpackage=([\\w.]+)").find(l)?.let { pkg = it.groupValues[1] }
            if (pkg != null && l.contains("state=PlaybackState {state=3,")) out += pkg!!
        }
        return out.toList()
    }

    /**
     * Uid owning the client end of a connection to us, from the kernel's TCP
     * tables: the row whose local port is the caller's and remote port ours.
     */
    fun callerUid(clientPort: Int): Int? {
        for (table in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val rows = runCatching { java.io.File(table).readLines().drop(1) }.getOrDefault(emptyList())
            uidFor(rows, clientPort, PORT)?.let { return it }
        }
        return null
    }

    /** Pure, for testing: columns are sl, local, remote, state, ..., uid (8th). */
    fun uidFor(rows: List<String>, localPort: Int, remotePort: Int): Int? {
        for (row in rows) {
            val f = row.trim().split(Regex("\\s+"))
            if (f.size < 8) continue
            val lp = f[1].substringAfterLast(':').toIntOrNull(16)
            val rp = f[2].substringAfterLast(':').toIntOrNull(16)
            if (lp == localPort && rp == remotePort && f[3] == "01") return f[7].toIntOrNull()
        }
        return null
    }

    /** Down then up, as a remote would send them. */
    private fun press(inject: (InputEvent) -> Boolean, code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return inject(event(now, KeyEvent.ACTION_DOWN, code)) && inject(event(now, KeyEvent.ACTION_UP, code))
    }

    private fun event(time: Long, action: Int, code: Int) = KeyEvent(
        time, time, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
    )

    /** InputManager.injectInputEvent is hidden API; this is what `input` itself calls. */
    private fun injector(): (InputEvent) -> Boolean {
        val cls = Class.forName("android.hardware.input.InputManager")
        val im = cls.getMethod("getInstance").invoke(null)
        val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        return { e -> m.invoke(im, e, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */) as Boolean }
    }

    private fun streamHubUid(): Int? {
        val p = ProcessBuilder("pm", "list", "packages", "-U", "com.felix.streamhub").start()
        val out = p.inputStream.bufferedReader().readText()
        return Regex("package:com\\.felix\\.streamhub uid:(\\d+)").find(out)?.groupValues?.get(1)?.toInt()
    }
}
