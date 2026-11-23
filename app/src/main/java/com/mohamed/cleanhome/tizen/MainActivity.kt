package com.mohamed.cleanhome.tizen

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var ipField: EditText
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var probeButton: Button
    private lateinit var sdbButton: Button
    private lateinit var recoveryButton: Button
    private lateinit var profileButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val prefs = getSharedPreferences("cleanhome", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(0xff101820.toInt())
        }
        val title = TextView(this).apply {
            text = "Mohamed CleanHome – Tizen"; textSize = 26f
            setTextColor(0xffffffff.toInt()); gravity = Gravity.CENTER
            setPadding(0, 12, 0, 6)
        }
        val subtitle = TextView(this).apply {
            text = "Samsung Tizen • Phase 1 diagnostics"
            textSize = 15f; setTextColor(0xffa9c0d0.toInt())
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        }
        ipField = EditText(this).apply {
            hint = "TV IP address (e.g. 192.168.1.50)"
            setSingleLine(); setTextColor(-1); setHintTextColor(0xff9aaab6.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("last_ip", "") ?: "")
        }
        probeButton = Button(this).apply {
            text = "Run connection checks"
            setOnClickListener { probe() }
        }
        status = TextView(this).apply {
            text = "Status: Not connected"; setTextColor(0xffd4e4ef.toInt())
            textSize = 16f; setPadding(0, 18, 0, 12)
        }
        val checks = TextView(this).apply {
            text = "Checks in this build:\n• Host/IP resolution\n• Basic reachability attempt\n• TCP port 26101 check (not an SDB session)\n• No pairing, commands, or system writes"
            setTextColor(0xffc4d4df.toInt()); textSize = 14f; setPadding(0, 8, 0, 16)
        }
        val recoveryTitle = TextView(this).apply {
            text = "Original Tizen Home recovery requirement"
            textSize = 18f; setTextColor(0xff8fd3ff.toInt()); setPadding(0, 12, 0, 6)
        }
        val recoveryInfo = TextView(this).apply {
            text = "The app is designed to include a clear return-to-original-Home option. It remains disabled until the supported recovery method is validated for a specific TV/firmware profile. No Home settings are changed in Phase 1."
            textSize = 14f; setTextColor(0xffd4e4ef.toInt()); setPadding(0, 4, 0, 12)
        }
        sdbButton = Button(this).apply {
            text = "Try experimental SDB handshake (read-only)"
            setOnClickListener { sdbProbe() }
        }
        recoveryButton = Button(this).apply {
            text = "Return to original Tizen Home — unavailable in Phase 1"
            isEnabled = false
        }
        profileButton = Button(this).apply {
            text = "Compatibility & recovery checklist"
            setOnClickListener { showCompatibilityChecklist() }
        }
        log = TextView(this).apply {
            text = "Diagnostic log\nReady."
            setTextColor(0xffc2d2de.toInt()); textSize = 14f; setPadding(0, 18, 0, 0)
        }
        root.addView(title); root.addView(subtitle); root.addView(ipField)
        root.addView(probeButton); root.addView(status); root.addView(checks)
        root.addView(sdbButton); root.addView(profileButton); root.addView(recoveryTitle); root.addView(recoveryInfo); root.addView(recoveryButton); root.addView(log)
        setContentView(root)
    }

    private fun sdbProbe() {
        val host = ipField.text.toString().trim()
        if (host.isBlank()) { status.text = "Status: Enter the TV IP address"; return }
        sdbButton.isEnabled = false
        status.text = "Status: Attempting SDB handshake…"
        log.text = "Diagnostic log\nSending only an experimental CNXN handshake."
        executor.execute {
            val result = SdbHandshakeProbe.probe(host)
            runOnUiThread {
                status.text = "Status: SDB probe ${result.outcome}"
                log.text = "Diagnostic log\n${result.detail}\nNo settings or Home configuration were changed."
                sdbButton.isEnabled = true
            }
        }
    }

    private fun probe() {
        val host = ipField.text.toString().trim()
        if (host.isBlank()) { status.text = "Status: Enter the TV IP address"; return }
        probeButton.isEnabled = false
        status.text = "Status: Checking…"
        val prefs = getSharedPreferences("cleanhome", MODE_PRIVATE)
        prefs.edit().putString("last_ip", host).apply()
        log.text = "Diagnostic log\nChecking $host"
        executor.execute {
            val lines = mutableListOf<String>()
            try {
                val address = InetAddress.getByName(host)
                lines += "Resolved address: ${address.hostAddress}"
                try { lines += "Basic reachability: ${address.isReachable(2500)} (not definitive)" }
                catch (e: Exception) { lines += "Reachability check: ${e.javaClass.simpleName}" }
                var portOpen = false
                try {
                    Socket().use { it.connect(InetSocketAddress(address, 26101), 2500); portOpen = true }
                    lines += "TCP 26101: connection accepted"
                } catch (e: Exception) {
                    lines += "TCP 26101: not confirmed (${e.javaClass.simpleName})"
                }
                lines += if (portOpen) "Interpretation: a TCP connection was accepted; this does not establish SDB protocol support." else "Interpretation: no TCP connection was confirmed. TV/network/firewall/protocol status remains unknown."
                lines += "No pairing, SDB commands, settings reads, or writes were attempted."
            } catch (e: Exception) {
                lines += "Host check failed: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            }
            val result = lines.joinToString("\n")
            runOnUiThread {
                status.text = "Status: Checks complete — no TV session established"
                log.text = "Diagnostic log\n$result"
                probeButton.isEnabled = true
            }
        }
    }

    private fun showCompatibilityChecklist() {
        val report = """CLEAN HOME MOBILE — COMPATIBILITY CHECK

TV address: ${ipField.text.toString().trim().ifBlank { "Not entered" }}

Evidence required before any Home change:
[ ] Exact TV model confirmed
[ ] Firmware/version confirmed
[ ] Architecture/runtime verified
[ ] Read-only native inspection reviewed
[ ] Original Home/startup state recorded on this exact TV
[ ] Restoration to Samsung Tizen Home rehearsed successfully
[ ] Physical Home-change rehearsal completed and independently reviewed

STATUS: BLOCKED — checklist items are not verified by this app.
A network connection or SDB handshake is not compatibility approval.
No Home settings have been changed by this app.

Recovery is mandatory and must be validated for the exact TV/firmware.
"""
        val box = TextView(this).apply { text = report; textSize = 14f; setPadding(24,24,24,24) }
        AlertDialog.Builder(this)
            .setTitle("Compatibility gate")
            .setMessage(report)
            .setPositiveButton("Share report") { _, _ ->
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, report)
                }
                startActivity(android.content.Intent.createChooser(send, "Share diagnostic checklist"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
