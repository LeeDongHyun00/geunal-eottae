package app.geunal.api.support

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Subscribes to device channels on Redis the way the gateway does and collects what the API publishes there. */
class Inbox(
    private val mapper: JsonMapper,
    channels: List<String>,
) : AutoCloseable {
    data class Signal(
        val channel: String,
        val event: JsonNode,
    ) {
        val type: String get() = event.path("t").stringValue()
    }

    private val client = RedisClient.create(RedisURI.create(TestStack.redisHost, TestStack.redisPort))
    private val connection: StatefulRedisPubSubConnection<String, String> = client.connectPubSub()
    private val signals = LinkedBlockingQueue<Signal>()

    init {
        connection.addListener(
            object : RedisPubSubAdapter<String, String>() {
                override fun message(
                    channel: String,
                    message: String,
                ) {
                    signals.add(Signal(channel, mapper.readTree(message)))
                }
            },
        )
        connection.sync().subscribe(*channels.toTypedArray())
    }

    fun next(timeout: Duration = Duration.ofSeconds(5)): Signal =
        signals.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) ?: throw AssertionError("no signal within $timeout")

    /** Everything that arrives until nothing more comes for [quiet]. */
    fun drain(quiet: Duration = Duration.ofMillis(500)): List<Signal> {
        val all = mutableListOf<Signal>()
        while (true) {
            all += signals.poll(quiet.toMillis(), TimeUnit.MILLISECONDS) ?: return all
        }
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }
}
