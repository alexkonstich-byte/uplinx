package dev.uplinx

import dev.uplinx.core.LatencyTester
import dev.uplinx.core.Ping
import dev.uplinx.data.Storage
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostic: real-delay probe of the first N servers of a state.json, through a throwaway Xray bound to
 * the physical adapter. Nothing on the system is changed. Run with -Duplinx.home=<copy of the data folder>.
 */
object PingCheck {
    @JvmStatic fun main(args: Array<String>) {
        val n = (System.getProperty("ping.count") ?: "12").toInt()
        val st = Storage.load()
        val nodes = st.nodes.take(n)
        println("physical interface: ${LatencyTester.physicalInterface()}")
        println("handshakes faked by a TUN: ${Ping.handshakesFaked()}")
        val res = ConcurrentHashMap<String, Int>()
        val targets = nodes.flatMap { LatencyTester.targets(it, st.settings) }
        runBlocking { LatencyTester.probe(targets, st.settings.pingUrl) { k, ms -> res[k] = ms } }
        nodes.forEachIndexed { i, node -> println("server #${i + 1}: real ${res[node.id] ?: -1} ms   (plain tcp ${Ping.tcp(node)} ms)") }
        System.exit(0)
    }
}
