package com.felix.streamhub

import com.felix.streamhub.keys.KeyServer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real remote-control key presses, via the key helper ([KeyServer]).
 *
 * An app cannot inject keys itself, accessibility clicks are not key presses
 * (Prime Video ignores them), and Fire OS refuses ADB connections from apps on
 * the TV - tried over 127.0.0.1, ::1 and every one of the TV's own addresses;
 * adbd hangs up at the handshake. So the helper, started once per boot from a
 * PC, does the pressing, and this is StreamHub's side of that: a plain TCP
 * connection to 127.0.0.1, one key name per line, "ok" back per key.
 */
object TvKeys {

    enum class Key {
        UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, PLAYPAUSE,
        VOLUMEUP, VOLUMEDOWN, MUTE, FORWARD, REWIND, NEXT, PREVIOUS, WAKE, SLEEP;
        companion object {
            fun of(name: String?): Key? = values().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    sealed class Result {
        object Pressed : Result()
        data class Unavailable(val reason: String) : Result()
    }

    const val HELPER_NOT_RUNNING =
        "The TV’s key helper isn’t running (it stops when the TV restarts). Start it once from the computer: see “Remote keys” in the README."

    @Synchronized
    fun press(vararg keys: Key): Result = pressAll(keys.toList())

    @Synchronized
    fun pressAll(keys: List<Key>): Result {
        if (keys.isEmpty()) return Result.Pressed
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", KeyServer.PORT), 1_000)
            socket.soTimeout = 3_000
            val out = socket.outputStream
            val replies = socket.inputStream.bufferedReader()
            for (k in keys) {
                out.write("${k.name}\n".toByteArray())
                out.flush()
                if (replies.readLine() != "ok") return Result.Unavailable("The TV didn’t take the ${k.name.lowercase()} press.")
            }
            Result.Pressed
        } catch (e: java.io.IOException) {
            Result.Unavailable(HELPER_NOT_RUNNING)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Packages whose media session is playing, or null if the helper isn't running. */
    fun playing(): List<String>? = ask(KeyServer.PLAYING)?.split(' ')?.filter { it.isNotBlank() }

    /** The running helper's version: 1 for one started before versions existed, 0 if none is running. */
    fun helperVersion(): Int = when (val r = ask(KeyServer.VERSION)) {
        null -> 0
        else -> r.toIntOrNull() ?: 1
    }

    const val HELPER_OUTDATED =
        "The TV’s key helper is from before StreamHub was updated. Run start-key-helper.bat again from the computer."

    /** Playing apps with what their media session names, or null if the helper isn't running. */
    fun nowPlaying(): List<Pair<String, String>>? = ask(KeyServer.NOW_PLAYING)?.let { reply ->
        runCatching {
            val arr = org.json.JSONArray(reply)
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.optString("package") to o.optString("title") } }
        }.getOrNull()
    }

    /** The package in front, "" if unknown, or null if the helper isn't running. */
    fun front(): String? = ask(KeyServer.FRONT)

    /** Whether [pkg] has a process, or null if the helper isn't running. */
    fun isRunning(pkg: String): Boolean? = ask("${KeyServer.RUNNING} $pkg")?.let { it == "yes" }

    /** Types [text] into whatever has focus. Only what [KeyServer.TYPEABLE] allows. */
    fun type(text: String): Result = when (ask("${KeyServer.TYPE} $text")) {
        null -> Result.Unavailable(HELPER_NOT_RUNNING)
        "ok" -> Result.Pressed
        else -> Result.Unavailable("The TV didn’t take the typing.")
    }

    /** Closes [pkg] (Netflix only, see KeyServer.STOPPABLE). */
    fun stopApp(pkg: String): Boolean = ask("${KeyServer.STOP_APP} $pkg") == "ok"

    /** One request, one reply line; null if the helper isn't there. */
    @Synchronized
    private fun ask(line: String): String? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", KeyServer.PORT), 1_000)
            socket.soTimeout = 5_000
            socket.outputStream.apply { write("$line\n".toByteArray()); flush() }
            socket.inputStream.bufferedReader().readLine()
        } catch (e: java.io.IOException) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Whether the helper is up right now (cheap: a connect, no key sent). */
    fun helperRunning(): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", KeyServer.PORT), 1_000); true
        } catch (e: java.io.IOException) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
