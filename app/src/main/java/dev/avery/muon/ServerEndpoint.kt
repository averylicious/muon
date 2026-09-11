package dev.avery.muon

import java.net.InetAddress
import java.net.URI

/** Numeric trusted-LAN origins only. DNS/VPN trust policy can be added at this boundary. */
class ServerEndpoint private constructor(val origin: String) {
    fun url(path: String): String {
        require(path.startsWith("/api1/") && !path.contains('?') && !path.contains('#'))
        return origin + path
    }
    companion object {
        fun parse(input: String): ServerEndpoint {
            val text = input.trim().let { if ("://" in it) it else "http://$it" }
            val uri = URI(text)
            require(uri.scheme in listOf("http", "https")) { "Use an http or https LAN address" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.path.orEmpty() in listOf("", "/")) { "Enter only a server address and port" }
            val host = requireNotNull(uri.host) { "Invalid server address" }.removeSurrounding("[", "]")
            require('%' !in host) { "Scoped IPv6 addresses are not supported yet" }
            val local = if (':' in host) {
                val bytes = InetAddress.getByName(host).address
                bytes.size == 16 && ((bytes[0].toInt() and 0xfe) == 0xfc ||
                    (bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()))
            } else {
                val parts = host.split('.')
                require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) }) {
                    "Enter a numeric private LAN address (for example 192.168.1.10)"
                }
                val b = parts.map { it.toInt() }
                require(b.all { it in 0..255 }) { "Invalid IPv4 address" }
                b[0] == 10 || b[0] == 127 || (b[0] == 192 && b[1] == 168) ||
                    (b[0] == 172 && b[1] in 16..31)
            }
            require(local) { "Trusted LAN only: public and VPN addresses are not enabled" }
            val port = if (uri.port == -1) 7814 else uri.port
            require(port in 1..65535) { "Invalid port" }
            val authority = if (':' in host) "[$host]" else host
            return ServerEndpoint("${uri.scheme}://$authority:$port")
        }
    }
}
