package app.geunal.api.support

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** One running API with the auth stub on, shared by every test class that extends this. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["geunal.auth.stub=true"])
abstract class ApiTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun stack(registry: DynamicPropertyRegistry) = TestStack.register(registry)
    }

    @Autowired
    lateinit var mapper: JsonMapper

    @Autowired
    lateinit var jdbc: JdbcClient

    @Value("\${local.server.port}")
    var port: Int = 0

    val api: Api by lazy { Api(port, mapper) }

    /** A fresh account with its own device, signed in. */
    fun signIn(
        subject: String = "user-${UUID.randomUUID()}",
        platform: String = "ios",
    ): Client = Client(api, subject).also { it.login(platform) }

    /** Listens on the Redis channels of these devices, as the gateway would. */
    fun inbox(vararg deviceIds: UUID): Inbox = Inbox(mapper, deviceIds.map { "d:$it" })

    /** A group owned by [owner] with the epoch-0 GroupInfo published and [others] added by one commit each. */
    fun groupWith(
        owner: Client,
        vararg others: Client,
    ): UUID {
        val groupId = owner.post("/groups").expect(201).uuid("id")
        owner.put("/groups/$groupId/group-info", mapOf("epoch" to 0, "group_info" to Mls.groupInfo(groupId, 0))).expect(204)
        others.forEachIndexed { index, member -> owner.addMember(groupId, index.toLong(), member) }
        return groupId
    }
}

fun TestStack.register(registry: DynamicPropertyRegistry) {
    registry.add("spring.datasource.url") { postgres.jdbcUrl }
    registry.add("spring.datasource.username") { postgres.username }
    registry.add("spring.datasource.password") { postgres.password }
    registry.add("spring.data.redis.host") { redisHost }
    registry.add("spring.data.redis.port") { redisPort }
}
