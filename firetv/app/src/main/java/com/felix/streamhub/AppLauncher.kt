package com.felix.streamhub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import com.felix.streamhub.data.Service
import com.felix.streamhub.data.Services

/**
 * Hands a title over to the service that actually plays it.
 *
 * Landing on an *exact* title needs the service's own content id, which TMDB
 * does not hand out. So there is a ladder, every rung aimed at the service's
 * own app so nothing can fall through to a browser:
 *
 *   1. a real deep link, when a content id was supplied
 *   2. the service's own search (see Services.search)
 *   3. the app's home screen
 *
 * Fire TV's universal search is NOT a rung. On a real Fire TV (Fire OS
 * 7.7.1.4) it is locked to Amazon's own apps, and the generic Android search
 * intents landed in the Silk browser, showing whatever page it last had open.
 */
object AppLauncher {

    sealed class Result {
        data class Launched(val kind: String, val pkg: String?) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * What a launch did, as told to the phone: "deeplink" (at the title),
     * "search" (results for the title), "search-page" (search open, title not
     * filled in), "home" (the app's home screen).
     */
    fun searchKind(search: com.felix.streamhub.data.Search): String =
        if (search.carriesQuery) "search" else "search-page"

    /** The package of this service that the TV's own home screen would open. */
    fun installedPackage(context: Context, svc: Service): String? {
        val pm = context.packageManager
        return pickPackage(
            svc.packages,
            hasTvLauncher = { runCatching { pm.getLeanbackLaunchIntentForPackage(it) != null }.getOrDefault(false) },
            hasLauncher = { runCatching { pm.getLaunchIntentForPackage(it) != null }.getOrDefault(false) }
        )
    }

    /**
     * TV launcher first, across all of a service's packages, then the phone
     * launcher. getLaunchIntentForPackage alone misses TV-only apps: on a real
     * Fire TV, HBO Max (com.hbo.hbonow) and Prime Video (com.amazon.firebat)
     * register only LEANBACK_LAUNCHER, so HBO read as "not installed", and
     * Prime resolved to com.amazon.avod, a background part of Prime with no
     * TV entry point.
     */
    fun pickPackage(
        packages: List<String>,
        hasTvLauncher: (String) -> Boolean,
        hasLauncher: (String) -> Boolean
    ): String? = packages.firstOrNull(hasTvLauncher) ?: packages.firstOrNull(hasLauncher)

    private fun homeIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        return runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) }.getOrNull()
            ?: runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
    }

    fun isInstalled(context: Context, serviceId: String): Boolean {
        val svc = Services.byId(serviceId) ?: return false
        return installedPackage(context, svc) != null
    }

    /**
     * End the screensaver before opening anything. On a real Fire TV, an app
     * started while the screensaver ran opened *behind* it: the phone said
     * "opened" and the TV showed nothing. ACQUIRE_CAUSES_WAKEUP takes the
     * system from dreaming back to awake, which dismisses the screensaver.
     */
    fun wakeScreen(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "streamhub:wake")
                .acquire(3_000)
        }
    }

    fun launch(
        context: Context,
        serviceId: String,
        title: String? = null,
        contentId: String? = null
    ): Result {
        val svc = Services.byId(serviceId) ?: return Result.Failed("Unknown service.")
        val pkg = installedPackage(context, svc)
            ?: return Result.Failed("${svc.name} is not installed on this TV.")

        // 1. real deep link
        if (!contentId.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(svc.titleUrl(contentId))).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, intent)) return Result.Launched("deeplink", pkg)
        }

        // 2. the service's own search. setPackage is what keeps a link the app
        // does not claim from being handed to a browser instead.
        val search = svc.search
        if (!title.isNullOrBlank() && search != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(search.link(title))).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, intent)) return Result.Launched(searchKind(search), pkg)
        }

        // 3. the app's home screen
        val launch = homeIntent(context, pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, launch)) return Result.Launched("home", pkg)
        }

        return Result.Failed("Could not start ${svc.name}.")
    }

    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }.getOrDefault(false)
}
