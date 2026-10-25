package app.geunal.api.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/** The images infra/docker-compose.yml runs, started once for every test class and reused across Spring contexts. */
object TestStack {
    val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16.15").also { it.start() }

    val redis: GenericContainer<*> = GenericContainer("redis:7.4.11").withExposedPorts(REDIS_PORT).also { it.start() }

    const val REDIS_PORT = 6379

    val redisHost: String get() = redis.host
    val redisPort: Int get() = redis.getMappedPort(REDIS_PORT)
}
