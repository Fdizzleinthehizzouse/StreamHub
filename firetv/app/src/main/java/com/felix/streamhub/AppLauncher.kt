package com.felix.streamhub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import com.felix.streamhub.data.Service
import com.felix.streamhub.data.Services

/**
 * Hands a title over to the service that actually plays it.
 *
 * The honest constraint: landing on an *exact* title inside Netflix or Disney+
 * needs that service's own internal content id, and TMDB does not hand those
 * out. So there is a ladder:
 *
 *   1. a real deep link, when a content id was supplied (e.g. pushed from the PC)
 *   2. Fire TV's universal search, which does know the real ids and offers
 *      "play on <service>" for the title - usually the fastest route
 *   3. the app's own home screen, which always works
 */
object AppLauncher {

    sealed class Result {
        data class Launched(val kind: String, val pkg: String?) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** The first package of this service that is actually installed. */
    fun installedPackage(context: Context, svc: Service): String? {
        val pm = context.packageManager
        return svc.packages.firstOrNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
    }

    fun isInstalled(context: Context, serviceId: String): Boolean {
        val svc = Services.byId(serviceId) ?: return false
        return installedPackage(context, svc) != null
    }

    fun launch(
        context: Context,
        serviceId: String,
        title: String? = null,
        contentId: String? = null,
        preferUniversalSearch: Boolean = true
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

        // 2. Fire TV universal search
        if (!title.isNullOrBlank() && preferUniversalSearch) {
            if (universalSearch(context, title)) return Result.Launched("universal-search", pkg)
        }

        // 3. the app's home screen
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, launch)) return Result.Launched("home", pkg)
        }

        return Result.Failed("Could not start ${svc.name}.")
    }

    /** Fire TV's own search, which spans every installed app. */
    fun universalSearch(context: Context, query: String): Boolean {
        val candidates = listOf(
            Intent(Intent.ACTION_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent("android.search.action.GLOBAL_SEARCH").apply {
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return candidates.any { tryStart(context, it) }
    }

    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }.getOrDefault(false)
}
