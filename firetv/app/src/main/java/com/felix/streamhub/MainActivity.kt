package com.felix.streamhub

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.felix.streamhub.data.Store
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The only screen this app has.
 *
 * StreamHub on the TV is a receiver, not something you browse. You pick what to
 * watch on your phone; this app takes the request and opens Netflix (or
 * whichever) at the right title. So all this screen does is tell you the web
 * address to open on your phone, and the code to type in once.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        if (store.controlEnabled) ControlService.start(this)

        render()

        findViewById<Button>(R.id.btn_new_code).setOnClickListener {
            store.rotateControlToken()
            render()
            Toast.makeText(this, "New code. Re-pair your phone.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btn_toggle).setOnClickListener {
            store.controlEnabled = !store.controlEnabled
            if (store.controlEnabled) ControlService.start(this) else ControlService.stop(this)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val ip = localIpAddress()
        val on = store.controlEnabled

        findViewById<TextView>(R.id.address).text =
            if (!on) "Remote is switched off."
            else if (ip == null) "Could not find this TV's network address.\nCheck Settings → My Fire TV → About → Network."
            else "http://$ip:${ControlService.PORT}"

        findViewById<TextView>(R.id.code).text = if (on) store.ensureControlToken() else "—"

        findViewById<TextView>(R.id.hint).text = if (on) {
            "On your phone, open that address in your browser and type the code once.\n" +
                "Your phone and this TV need to be on the same wifi."
        } else {
            "Turn the remote back on to use your phone with this TV."
        }

        findViewById<TextView>(R.id.status).text = buildString {
            append("Installed here: ")
            val present = com.felix.streamhub.data.Services.ALL
                .filter { AppLauncher.isInstalled(this@MainActivity, it.id) }
                .joinToString(", ") { it.name }
            append(if (present.isBlank()) "none of the four services found" else present)
            append("\nSetup key: ")
            append(if (store.tmdbKey.isBlank()) "not set yet — add it on your phone" else "done")
            append("\nPhones paired: ")
            append(store.sessionCount())
        }

        findViewById<Button>(R.id.btn_toggle).text = if (on) "Switch remote off" else "Switch remote on"
    }

    /** Prefer the wifi address, but fall back to walking the interfaces. */
    private fun localIpAddress(): String? {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
                )
            }
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.getOrNull()
    }
}
