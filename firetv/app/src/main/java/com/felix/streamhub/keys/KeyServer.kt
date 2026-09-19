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
 * seen on the real TV.) Each request is one key name from [KEYS]; nothing a
 * caller sends is ever executed.
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
                        val reply = if (line == PLAYING) {
                            playingPackages().joinToString(" ")
                        } else {
                            val code = KEYS[line]
                            if (code != null && press(inject, code)) "ok" else "no"
                        }
                        out.write("$reply\n".toByteArray())
                        out.flush()
                    }
                }
            }
        }
    }

    /** The one read-only query: which apps' media sessions are playing. */
    const val PLAYING = "PLAYING"

    /**
     * Apps whose media session is playing (state=3), from `dumpsys
     * media_session`. The shell user can read it; apps can't without being a
     * notification listener. It's how "it's playing" is confirmed even for
     * Netflix and HBO Max, whose screens can't be read.
     */
    private fun playingPackages(): List<String> {
        val p = ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start()
        return playingFrom(p.inputStream.bufferedReader().readLines())
    }

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
