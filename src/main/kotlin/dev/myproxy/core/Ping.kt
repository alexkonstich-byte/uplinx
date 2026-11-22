package dev.myproxy.core

import dev.myproxy.data.ProxyNode
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object Ping {
    /** TCP handshake time to the server, ms. -1 = timeout/unreachable. Does not touch any proxy settings. */
    fun tcp(node: ProxyNode, timeoutMs: Int = 3000): Int = runCatching {
        Socket().use { s ->
            val addr = InetSocketAddress(node.address, node.port)
            val t0 = System.nanoTime()
            s.connect(addr, timeoutMs)
            ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
        }
    }.getOrDefault(-1)

    /** Real delay of an HTTP request through the running local proxy. Only meaningful while connected. */
    fun throughProxy(httpPort: Int, url: String): Int = runCatching {
        val client = HttpClient.newBuilder()
            .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", httpPort)))
            .connectTimeout(Duration.ofSeconds(5)).build()
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).GET().build()
        client.send(req, HttpResponse.BodyHandlers.discarding()) // warm up the connection
        val t0 = System.nanoTime()
        client.send(req, HttpResponse.BodyHandlers.discarding())
        ((System.nanoTime() - t0) / 1_000_000).toInt()
    }.getOrDefault(-1)
}
