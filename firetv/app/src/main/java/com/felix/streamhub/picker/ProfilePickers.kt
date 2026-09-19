package com.felix.streamhub.picker

import java.util.Locale

/**
 * How to recognise each service's "who's watching" screen and find one named
 * profile on it. EXPECT THIS FILE TO BREAK when a service updates its app:
 * everything here is read off real screen dumps (Fire TV AFT6E0FA, Fire OS
 * 7.7.1.4, September 2026), not any published contract.
 *
 * To fix a break: open the app to its picker, run
 *   adb shell uiautomator dump /sdcard/p.xml && adb pull /sdcard/p.xml
 * or read the tree ProfilePickerService logs under the tag StreamHubPicker,
 * then update that service's section below.
 *
 * Netflix and HBO Max are absent on purpose. Both draw their whole screen
 * themselves and expose no text to accessibility, so there is nothing to read.
 * (HBO Max also skipped its picker on that TV.)
 *
 * Rules every section follows: the screen must positively look like the
 * picker, exactly one profile must match the name, and the node clicked must
 * be the clickable element of that profile's own tile. Anything less is
 * NoSuchProfile or NotPicker, and nothing gets clicked.
 */
object ProfilePickers {

    /** A node on screen, reduced to what recognition needs. */
    interface Node {
        val text: String
        val desc: String
        /** e.g. "com.amazon.firebat:id/profile_name", or "" */
        val viewId: String
        val clickable: Boolean
        /** left, top, right, bottom */
        val bounds: List<Int>
        val parent: Node?
        val children: List<Node>
    }

    sealed class Outcome {
        /** Not the picker (yet). Keep waiting. */
        object NotPicker : Outcome()
        /** The picker is showing but that name is not on it, or is ambiguous. */
        object NoSuchProfile : Outcome()
        data class Found(val node: Node) : Outcome()
    }

    /**
     * Turns a stream of per-look outcomes into a decision. "Not on the picker"
     * must hold for [needed] looks in a row before giving up: on a real TV,
     * Disney+ drew its heading before its tiles, and a single look concluded
     * the profile was missing and gave up for good.
     */
    class Settle(private val needed: Int = 4) {
        private var misses = 0

        sealed class Decision {
            object KeepLooking : Decision()
            object GiveUp : Decision()
            data class Click(val node: Node) : Decision()
        }

        fun next(outcome: Outcome): Decision = when (outcome) {
            is Outcome.Found -> Decision.Click(outcome.node)
            Outcome.NotPicker -> { misses = 0; Decision.KeepLooking }
            Outcome.NoSuchProfile -> if (++misses >= needed) Decision.GiveUp else Decision.KeepLooking
        }
    }

    class Picker(
        val serviceId: String,
        val packages: Set<String>,
        /** The search page's text box, for services whose search link leaves it empty. */
        val searchBox: ((root: Node) -> Node?)? = null,
        val recognise: (root: Node, name: String) -> Outcome
    )

    val ALL: List<Picker> = listOf(disneyPlus(), primeVideo())

    fun forService(serviceId: String): Picker? = ALL.firstOrNull { it.serviceId == serviceId }

    fun forPackage(pkg: String): Picker? = ALL.firstOrNull { pkg in it.packages }

    // ---- Disney+ ---------------------------------------------------------
    //
    // Jetpack Compose, so almost no view ids. The picker has the view id
    // profilesContent and a "Who's watching?" heading (profilesContent alone
    // also backs the Edit Profiles screen). Each profile is a clickable tile
    // whose single child carries the description "Access <name>'s profile",
    // with identical bounds. Disney always appends 's, even to names ending
    // in s ("Access Peters's profile"). English UI only: the heading and
    // description are localised text.

    //
    // Search: the link opens a page whose EditText has the view id
    // searchEditText ("Search by title, genre, team or league"). Exactly one
    // such box must be on screen.

    private fun disneyPlus() = Picker(
        serviceId = "disneyplus",
        packages = setOf("com.disney.disneyplus", "com.disney.disneyplus.androidtv"),
        searchBox = { root -> root.all { it.viewId.endsWith(":id/searchEditText") }.singleOrNull() }
    ) { root, name ->
        val onPicker = root.any { it.viewId.endsWith(":id/profilesContent") } &&
            root.any { it.text == "Who's watching?" }
        if (!onPicker) return@Picker Outcome.NotPicker

        val wanted = "access ${fold(name)}'s profile"
        val labels = root.all { fold(it.desc) == wanted }
        val tiles = labels.mapNotNull { label ->
            label.parent?.takeIf { it.clickable && it.bounds == label.bounds }
        }
        if (labels.size == 1 && tiles.size == 1) Outcome.Found(tiles[0]) else Outcome.NoSuchProfile
    }

    // ---- Prime Video -----------------------------------------------------
    //
    // Classic Android views with stable ids. The picker is marked by
    // whos_watching_heading; each profile's name is a clickable profile_name
    // TextView. The "New" tile (add a profile) is also a profile_name, but
    // its tile holds a profile_add_icon, so it is excluded: someone whose
    // profile is literally called "New" must not create a profile instead.

    private fun primeVideo() = Picker(
        serviceId = "primevideo",
        packages = setOf("com.amazon.firebat", "com.amazon.avod", "com.amazon.avod.thirdpartyclient")
    ) { root, name ->
        if (!root.any { it.viewId.endsWith(":id/whos_watching_heading") }) return@Picker Outcome.NotPicker

        val matches = root.all {
            it.viewId.endsWith(":id/profile_name") && it.clickable && fold(it.text) == fold(name)
        }.filterNot { label ->
            label.parent?.any { it.viewId.endsWith(":id/profile_add_icon") } ?: true
        }
        if (matches.size == 1) Outcome.Found(matches[0]) else Outcome.NoSuchProfile
    }

    // ---- helpers ---------------------------------------------------------

    /** Names are typed on a phone, so case and stray spaces are forgiven. */
    private fun fold(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun Node.all(pred: (Node) -> Boolean): List<Node> {
        val out = mutableListOf<Node>()
        fun walk(n: Node) {
            if (pred(n)) out += n
            n.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    private fun Node.any(pred: (Node) -> Boolean): Boolean = all(pred).isNotEmpty()
}
