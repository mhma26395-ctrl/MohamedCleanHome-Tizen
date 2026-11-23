package com.mohamed.cleanhome.tizen

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sends a Samsung/Tizen SDB-compatible CNXN frame only. No authentication or commands. */
object SdbHandshakeProbe {
    data class Result(val outcome: String, val detail: String)

    private const val PORT = 26101
    // Tizen/Samsung SDB version, rather than generic ADB version.
    private const val VERSION = 0x00100000
    // Advertise host receive capacity: 256 KiB.
    private const val MAX_DATA = 256 * 1024
    private const val HEADER_SIZE = 24
    private const val MAX_REPLY = 4096

    fun probe(host: String, timeoutMs: Int = 3500): Result {
        if (host.isBlank() || host.length > 253 || host.any { it.isWhitespace() }) {
            return Result("INVALID_HOST", "Enter a valid TV IP address or host name.")
        }
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), timeoutMs)
                socket.soTimeout = timeoutMs
                val payload = "host::\u0000".toByteArray(Charsets.UTF_8)
                val cmd = commandInt("CNXN")
                val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(cmd).putInt(VERSION).putInt(MAX_DATA).putInt(payload.size)
                    .putInt(checksum(payload)).putInt(cmd xor -1).array()
                socket.getOutputStream().apply { write(header); write(payload); flush() }

                val responseHeader = readFully(socket.getInputStream(), HEADER_SIZE)
                    ?: return Result("NO_RESPONSE", "TCP succeeded, but no complete SDB protocol header was received.")
                val bb = ByteBuffer.wrap(responseHeader).order(ByteOrder.LITTLE_ENDIAN)
                val commandValue = bb.int
                val arg0 = bb.int
                val arg1 = bb.int
                val length = bb.int
                val sum = bb.int
                val magic = bb.int
                if (magic != (commandValue xor -1)) return Result("INVALID_HEADER", "SDB header magic validation failed.")
                if (length < 0 || length > MAX_REPLY) return Result("INVALID_LENGTH", "Reply exceeded the diagnostic payload limit.")
                val responsePayload = if (length == 0) byteArrayOf() else readFully(socket.getInputStream(), length)
                    ?: return Result("INCOMPLETE", "Header received, but its payload was incomplete.")
                if (checksum(responsePayload) != sum) return Result("CHECKSUM_MISMATCH", "Reply payload checksum did not match.")
                val command = intToCommand(commandValue)
                val note = when (command) {
                    "CNXN" -> "SDB CNXN reply observed; handshake response only, not proof of a usable session."
                    "AUTH" -> "Peer requested AUTH; this diagnostic does not authenticate or continue."
                    else -> "SDB packet observed; no additional session commands were sent."
                }
                Result(command, note + " (arg0=" + arg0 + ", arg1=" + arg1 + ", payload=" + responsePayload.toString(Charsets.UTF_8).replace("\u0000", "").take(120) + ")")
            }
        } catch (e: SocketTimeoutException) {
            Result("TIMEOUT", "SDB handshake timed out: " + (e.message ?: "no details"))
        } catch (e: SocketException) {
            val msg = e.message ?: "socket error"
            if (msg.contains("reset", ignoreCase = true)) Result("RESET", "Peer reset the SDB connection: " + msg)
            else Result("FAILED", "SocketException: " + msg)
        } catch (e: Exception) {
            Result("FAILED", e.javaClass.simpleName + ": " + (e.message ?: "no details"))
        }
    }

    private fun checksum(bytes: ByteArray): Int = bytes.sumOf { it.toInt() and 0xff }
    private fun readFully(input: InputStream, size: Int): ByteArray? {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(out, offset, size - offset)
            if (count < 0) return null
            offset += count
        }
        return out
    }
    private fun commandInt(command: String): Int =
        ByteBuffer.wrap(command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
    private fun intToCommand(value: Int): String =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array().toString(Charsets.US_ASCII)
}
