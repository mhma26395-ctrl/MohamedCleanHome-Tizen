package com.mohamed.cleanhome.tizen

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Conservative SDB diagnostics and read-only capability query. */
object SdbHandshakeProbe {
    data class Result(val outcome: String, val detail: String)
    private const val PORT = 26101
    private const val VERSION = 0x00100000
    private const val MAX_DATA = 256 * 1024
    private const val HEADER_SIZE = 24
    private const val MAX_REPLY = 4096
    private const val LOCAL_ID = 1

    fun probe(host: String, timeoutMs: Int = 3500): Result {
        if (!validHost(host)) return Result("INVALID_HOST", "Enter a valid TV IP address or host name.")
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), timeoutMs)
                socket.soTimeout = timeoutMs
                sendCnxn(socket)
                val packet = readPacket(socket.getInputStream()) ?: return Result("NO_RESPONSE", "No complete SDB response.")
                if (packet.command != "CNXN") return Result(packet.command, "Unexpected packet after CNXN: ${packet.command}")
                Result("CNXN", "SDB handshake response observed. Device banner: ${packet.payload.toString(Charsets.UTF_8).replace("\\u0000", "").take(160)}. Session not yet opened.")
            }
        } catch (e: SocketTimeoutException) {
            Result("TIMEOUT", "SDB handshake timed out.")
        } catch (e: SocketException) {
            Result("RESET", "SocketException: ${e.message ?: "socket error"}")
        } catch (e: Exception) {
            Result("FAILED", "${e.javaClass.simpleName}: ${e.message ?: "no details"}")
        }
    }

    /** Opens only the read-only capability service; no shell, settings, package or Home commands. */
    fun readCapabilities(host: String, timeoutMs: Int = 4500): Result {
        if (!validHost(host)) return Result("INVALID_HOST", "Enter a valid TV IP address or host name.")
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), timeoutMs)
                socket.soTimeout = timeoutMs
                sendCnxn(socket)
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val cnxn = readPacket(input) ?: return Result("NO_RESPONSE", "No complete SDB handshake reply.")
                if (cnxn.command != "CNXN") return Result(cnxn.command, "Expected CNXN; received ${cnxn.command}.")
                sendPacket(output, "OPEN", LOCAL_ID, 0, "capability:\\u0000".toByteArray(Charsets.UTF_8))
                val response = readPacket(input) ?: return Result("NO_RESPONSE", "No reply to capability service request.")
                if (response.command == "CLSE") return Result("REFUSED", "TV closed the read-only capability stream.")
                if (response.command != "OKAY") return Result(response.command, "Expected stream OKAY; received ${response.command}.")
                val remoteId = response.arg0
                val dataPacket = readPacket(input) ?: return Result("NO_RESPONSE", "Stream opened but no capability payload arrived.")
                if (dataPacket.command == "CLSE") {
                    sendPacket(output, "CLSE", LOCAL_ID, remoteId, byteArrayOf())
                    return Result("REFUSED", "TV closed capability stream before sending data.")
                }
                if (dataPacket.command != "WRTE" || dataPacket.arg1 != LOCAL_ID) return Result(dataPacket.command, "Unexpected capability stream packet.")
                sendPacket(output, "OKAY", LOCAL_ID, remoteId, byteArrayOf())
                val framed = dataPacket.payload
                if (framed.size < 2) return Result("INVALID_DATA", "Capability payload lacked its length prefix.")
                val len = ByteBuffer.wrap(framed, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                if (len > framed.size - 2) return Result("INCOMPLETE", "Capability length prefix exceeded received payload.")
                val decoded = framed.copyOfRange(2, 2 + len).toString(Charsets.UTF_8).trim()
                sendPacket(output, "CLSE", LOCAL_ID, remoteId, byteArrayOf())
                if (decoded.isBlank()) Result("EMPTY", "Capability service returned an empty payload.")
                else Result("CAPABILITY", "Read-only capability data:\\n$decoded")
            }
        } catch (e: SocketTimeoutException) {
            Result("TIMEOUT", "Capability read timed out.")
        } catch (e: SocketException) {
            Result("RESET", "SocketException: ${e.message ?: "socket error"}")
        } catch (e: Exception) {
            Result("FAILED", "${e.javaClass.simpleName}: ${e.message ?: "no details"}")
        }
    }

    private data class Packet(val command: String, val arg0: Int, val arg1: Int, val payload: ByteArray)
    private fun validHost(host: String) = host.isNotBlank() && host.length <= 253 && !host.any { it.isWhitespace() }
    private fun sendCnxn(socket: Socket) = sendPacket(socket.getOutputStream(), "CNXN", VERSION, MAX_DATA, "host::\\u0000".toByteArray(Charsets.UTF_8))
    private fun sendPacket(output: java.io.OutputStream, command: String, arg0: Int, arg1: Int, payload: ByteArray) {
        val cmd = commandInt(command)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(checksum(payload)).putInt(cmd xor -1).array()
        output.write(header)
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }
    private fun readPacket(input: InputStream): Packet? {
        val header = readFully(input, HEADER_SIZE) ?: return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmdValue = bb.int; val arg0 = bb.int; val arg1 = bb.int; val length = bb.int; val sum = bb.int; val magic = bb.int
        if (magic != (cmdValue xor -1)) throw IllegalStateException("SDB header magic invalid")
        if (length < 0 || length > MAX_REPLY) throw IllegalStateException("SDB payload length invalid")
        val payload = if (length == 0) byteArrayOf() else readFully(input, length) ?: throw IllegalStateException("Incomplete SDB payload")
        if (checksum(payload) != sum) throw IllegalStateException("SDB payload checksum mismatch")
        return Packet(intToCommand(cmdValue), arg0, arg1, payload)
    }
    private fun checksum(bytes: ByteArray): Int = bytes.sumOf { it.toInt() and 0xff }
    private fun readFully(input: InputStream, size: Int): ByteArray? {
        val out = ByteArray(size); var offset = 0
        while (offset < size) { val count = input.read(out, offset, size - offset); if (count < 0) return null; offset += count }
        return out
    }
    private fun commandInt(command: String): Int = ByteBuffer.wrap(command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
    private fun intToCommand(value: Int): String = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array().toString(Charsets.US_ASCII)
}
