package app.geunal.api.support

import com.sun.net.httpserver.HttpServer
import org.springframework.test.context.DynamicPropertyRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** KASI's 특일 정보 API (`getRestDeInfo`) as the holiday sync calls it: one answer per `solYear`, set by the tests. */
object FakeKasi {
    const val SERVICE_KEY = "test+key/with==signs"

    /** The JSON body to answer for a year; a year without one gets KASI's "no data" answer. */
    val answers = ConcurrentHashMap<Int, String>()

    /** The decoded query parameters of every request. */
    val requests = ConcurrentLinkedQueue<Map<String, String>>()

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/getRestDeInfo") { exchange ->
                val query =
                    exchange.requestURI.rawQuery
                        .orEmpty()
                        .split("&")
                        .filter { it.isNotEmpty() }
                        .associate { part ->
                            val (name, value) = part.split("=", limit = 2) + ""
                            name to URLDecoder.decode(value, Charsets.UTF_8)
                        }
                requests += query
                val body = (answers[query["solYear"]?.toIntOrNull()] ?: items(emptyList())).toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("geunal.holidays.service-key") { SERVICE_KEY }
        registry.add("geunal.holidays.uri") { "http://127.0.0.1:${server.address.port}/getRestDeInfo" }
    }

    /** KASI's JSON for [days] (yyyyMMdd to name): `item` is an array, one object, or an empty string, as KASI sends. */
    fun items(
        days: List<Pair<String, String>>,
        resultCode: String = "00",
    ): String {
        val item =
            days.map { (day, name) -> """{"dateKind":"01","dateName":"$name","isHoliday":"Y","locdate":$day,"seq":1}""" }
        val items =
            when (item.size) {
                0 -> "\"\""
                1 -> """{"item":${item.single()}}"""
                else -> """{"item":[${item.joinToString(",")}]}"""
            }
        return """{"response":{"header":{"resultCode":"$resultCode","resultMsg":"NORMAL SERVICE."},""" +
            """"body":{"items":$items,"numOfRows":100,"pageNo":1,"totalCount":${item.size}}}}"""
    }
}
