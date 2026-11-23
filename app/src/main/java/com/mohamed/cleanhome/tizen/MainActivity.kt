package com.mohamed.cleanhome.tizen

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors\nimport okhttp3.OkHttpClient\nimport okhttp3.Request\nimport okhttp3.WebSocket\nimport okhttp3.WebSocketListener

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var ipField: EditText
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var probeButton: Button
    private lateinit var sdbButton: Button
    private lateinit var recoveryButton: Button

    private val bg = 0xff101820.toInt()
    private val panel = 0xff1b2833.toInt()
    private val textColor = 0xfff3f7fa.toInt()
    private val muted = 0xffb7c7d3.toInt()
    private val accent = 0xff62c7b4.toInt()

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setBackgroundColor(panel)
    }
    private fun label(value: String, size: Float, color: Int = textColor, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun addGap(parent: LinearLayout, height: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }

    private fun buildUi() {
        val prefs = getSharedPreferences("cleanhome", MODE_PRIVATE)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,0,0,dp(8)) }

        val header = card()
        header.addView(label("Mohamed CleanHome", 24f, textColor, true))
        header.addView(label("Samsung TV network control discovery", 14f, muted))
        addGap(header,8)
        header.addView(label("Checking Samsung TV control endpoints. No TV settings are changed.", 13f, muted))
        root.addView(header); addGap(root)

        val connection = card()
        connection.addView(label("TV CONNECTION", 13f, accent, true))
        addGap(connection,6)
        ipField = EditText(this).apply {
            hint = "TV IP address (192.168.1.50)"
            setSingleLine(); setTextColor(textColor); setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("last_ip", "") ?: "")
            textSize = 16f
        }
        connection.addView(ipField)
        probeButton = Button(this).apply { text = "Run connection checks"; setOnClickListener { probe() } }
        connection.addView(probeButton)
        val setupButton = Button(this).apply {
            text = "Samsung TV developer-mode setup"
            setOnClickListener { showDeveloperSetup() }
        }
        connection.addView(setupButton)
        val samsungButton = Button(this).apply { text = "Check Samsung control ports"; setOnClickListener { samsungPorts() } }
        connection.addView(samsungButton)
        status = label("Status: Ready", 15f, textColor, true)
        status.setPadding(0,dp(12),0,dp(4))
        connection.addView(status)
        root.addView(connection); addGap(root)

        val details = card()
        details.addView(label("WHAT THIS CHECKS", 13f, accent, true))
        addGap(details,8)
        details.addView(label("• IP/host resolution\n• Basic reachability attempt\n• TCP port 26101 reachability\n• SDB handshake response validation\n• No authentication or TV commands", 14f, muted))
        root.addView(details); addGap(root)

        val tools = card()
        tools.addView(label("NETWORK & TV CONTROL", 13f, accent, true))
        addGap(tools,6)
        sdbButton = Button(this).apply {
            text = "SDB handshake (legacy test)"
            setOnClickListener { sdbProbe() }
        }
        tools.addView(sdbButton)
        val capabilityButton = Button(this).apply {
            text = "Read TV capabilities (read-only)"
            setOnClickListener { capabilityProbe() }
        }
        tools.addView(capabilityButton)
        profileButtonSetup(tools)
        root.addView(tools); addGap(root)

        val recovery = card()
        recovery.addView(label("SAFE RETURN TO ORIGINAL HOME", 15f, textColor, true))
        addGap(recovery,6)
        recovery.addView(label("The return-to-original-Home function is not enabled. A recovery method must be verified on the exact TV model and firmware before any Home modification.", 14f, muted))
        recoveryButton = Button(this).apply { text = "Original Tizen Home recovery — not yet available"; isEnabled = false }
        recovery.addView(recoveryButton)
        root.addView(recovery); addGap(root)

        val output = card()
        output.addView(label("DIAGNOSTIC RESULTS", 13f, accent, true))
        addGap(output,6)
        log = label("Ready. Run a connection check to view results.", 14f, muted)
        output.addView(log)
        root.addView(output)

        scroll.addView(root)
        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
    }

    private fun profileButtonSetup(parent: LinearLayout) {
        val button = Button(this).apply {
            text = "Compatibility & recovery checklist"
            setOnClickListener { showCompatibilityChecklist() }
        }
        parent.addView(button)
    }

    private fun showDeveloperSetup() {
        val guide = """SAMSUNG TV — DEVELOPER MODE CHECKLIST

1. TV and phone should be on the same local Wi-Fi/LAN.
2. On the Samsung TV, open Apps.
3. In Apps, open App Settings (location varies by model).
4. Enter the developer-mode activation sequence where supported.
5. Turn Developer Mode ON.
6. Enter the PHONE's IP address as the developer/host IP if the TV asks for it.
7. Confirm and reboot the TV.
8. After reboot, run the connection checks again.

Important:
• The TV may not expose SDB on every model/firmware.
• Network reachability is not proof of SDB support.
• Do not enter a different host IP by mistake.
• This app currently performs a diagnostic handshake only; it does not pair/authenticate or change TV settings."""
        AlertDialog.Builder(this).setTitle("Developer Mode setup")
            .setMessage(guide).setPositiveButton("Close", null).show()
    }

    private fun samsungPorts() {
        val host = ipField.text.toString().trim()
        if (host.isBlank()) { status.text = "Status: Enter the TV IP address"; return }
        status.text = "Status: Checking Samsung endpoints…"
        log.text = "Testing common Samsung TV control ports."
        executor.execute {
            val ports = listOf(8001, 8002, 7676, 26101)
            val lines = ports.map { port ->
                val result = try {
                    Socket().use { it.connect(InetSocketAddress(host, port), 1800); "OPEN" }
                } catch (e: Exception) { "closed/unconfirmed" }
                "$port: $result"
            }
            runOnUiThread {
                status.text = "Status: Endpoint scan complete"
                log.text = "Samsung endpoint scan\n" + lines.joinToString("\n") + "\nPorts alone do not prove control compatibility."
            }
        }
    }


    private fun websocketProbe() {
        val host = ipField.text.toString().trim()
        if (host.isBlank()) { status.text = "Status: Enter the TV IP address"; return }
        status.text = "Status: Testing WebSocket…"
        log.text = "Opening Samsung remote-control WebSocket on port 8001 (no key command sent)."
        val encodedName = android.util.Base64.encodeToString("MohamedCleanHome".toByteArray(), android.util.Base64.NO_WRAP)
        val request = Request.Builder().url("ws://$host:8001/api/v2/channels/samsung.remote.control?name=$encodedName").build()
        val client = OkHttpClient()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                runOnUiThread {
                    status.text = "Status: WebSocket connection established"
                    log.text = "SUCCESS: Samsung remote WebSocket opened on port 8001.\nHTTP status: ${response.code}\nNo remote key or TV command was sent. This confirms protocol access only."
                }
                webSocket.close(1000, "Diagnostic complete")
                client.dispatcher.executorService.shutdown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                runOnUiThread {
                    status.text = "Status: WebSocket test failed"
                    log.text = "WebSocket failed: ${t.javaClass.simpleName}: ${t.message ?: "no details"}\nHTTP status: ${response?.code ?: "unavailable"}\nCheck TV permissions, network, and model/firmware."
                }
                client.dispatcher.executorService.shutdown()
            }
        })
    }
\n    private fun capabilityProbe() {
        val host = ipField.text.toString().trim()
        if (host.isBlank()) { status.text = "Status: Enter the TV IP address"; return }
        status.text = "Status: Reading read-only capabilities…"
        log.text = "Opening only the SDB capability service."
        executor.execute {
            val result = SdbHandshakeProbe.readCapabilities(host)
            runOnUiThread {
                status.text = "Status: Capability probe ${result.outcome}"
                log.text = "Read-only diagnostic\n${result.detail}\nNo settings or Home configuration were changed."
            }
        }
    }

    private fun sdbProbe() {
        val host = ipField.text.toString().trim()
        if (host.isBlank()) { status.text = "Status: Enter the TV IP address"; return }
        sdbButton.isEnabled = false
        status.text = "Status: Attempting handshake…"
        log.text = "Sending only an experimental CNXN handshake."
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
        status.text = "Status: Checking network and SDB…"
        getSharedPreferences("cleanhome", MODE_PRIVATE).edit().putString("last_ip", host).apply()
        log.text = "Testing host and protocol…"
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
                if (portOpen) {
                    val sdb = SdbHandshakeProbe.probe(host, 3500)
                    lines += "SDB handshake result: ${sdb.outcome}"
                    lines += sdb.detail
                    lines += when (sdb.outcome) {
                        "CNXN" -> "Interpretation: protocol handshake response observed. Full session/authentication is not implemented."
                        "AUTH" -> "Interpretation: TV appears to request authentication; this build does not authenticate."
                        "RESET" -> "Interpretation: peer reset the socket after the handshake attempt. Verify Developer Mode and protocol compatibility."
                        "TIMEOUT" -> "Interpretation: no complete protocol reply arrived before timeout."
                        "NO_RESPONSE" -> "Interpretation: connection opened, but no complete protocol header arrived."
                        "INVALID_HEADER", "INVALID_LENGTH", "CHECKSUM_MISMATCH", "INCOMPLETE" -> "Interpretation: reply data was received but failed protocol validation."
                        else -> "Interpretation: response is not yet verified as a usable SDB session."
                    }
                } else {
                    lines += "SDB handshake skipped because TCP port 26101 was not confirmed."
                    lines += "Check Developer Mode, host IP entered on TV, reboot, network isolation, and model/firmware."
                }
                lines += "No authentication, session commands, settings reads, or writes were attempted."
            } catch (e: Exception) {
                lines += "Host check failed: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
            }
            val result = lines.joinToString("\n")
            runOnUiThread {
                status.text = "Status: Diagnostics complete"
                log.text = result
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
[ ] Original Home/startup state recorded
[ ] Restoration to Samsung Tizen Home rehearsed successfully
[ ] Home-change rehearsal completed and independently reviewed

STATUS: BLOCKED — checklist items are not verified.
A network connection or SDB handshake is not compatibility approval.
No Home settings have been changed by this app.
Recovery must be validated for the exact TV/firmware."""
        AlertDialog.Builder(this).setTitle("Compatibility gate").setMessage(report)
            .setPositiveButton("Share report") { _, _ ->
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, report) }
                startActivity(android.content.Intent.createChooser(send, "Share diagnostic checklist"))
            }.setNegativeButton("Close", null).show()
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
