package app.geunal.api.auth

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * The provider side of an account deletion (protocol §13): Kakao's unlink and the revocation of the Apple tokens the
 * deletion's code was exchanged for. Each is queued in the deletion's transaction, tried right after it commits, and
 * retried with backoff until the provider takes it; the row, and an Apple token with it, goes as soon as a call succeeds.
 */
@Component
class ProviderRevocations(
    private val jdbc: JdbcClient,
    private val kakao: KakaoClient,
    private val apple: AppleClient,
    transactions: PlatformTransactionManager,
) {
    /**
     * [attempt] runs after the deletion commits, while that transaction's connection is still bound; its writes need a
     * transaction of their own to be committed at all.
     */
    private val own = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    private data class Pending(
        val id: Long,
        val provider: String,
        val target: String,
        val clientId: String?,
        val attempts: Int,
    )

    /** Queues Kakao's unlink and the Apple revocation of a deleted account; returns the ids to [attempt] after commit. */
    fun queue(
        kakaoSub: String?,
        apple: AppleTokens?,
    ): List<Long> {
        val ids = mutableListOf<Long>()
        if (kakaoSub != null) ids += insert("kakao", kakaoSub, null)
        val token = apple?.token
        if (token != null) ids += insert("apple", "${apple.tokenType}:$token", apple.clientId)
        return ids
    }

    /** Tries the queued calls now; what fails waits for [retry]. */
    fun attempt(ids: List<Long>) {
        if (ids.isEmpty()) return
        val rows =
            own
                .execute {
                    jdbc
                        .sql(
                            "SELECT id, provider::text AS provider, target, client_id, attempts FROM provider_revocations WHERE id IN (:ids)",
                        ).param("ids", ids)
                        .query(::pending)
                        .list()
                }.orEmpty()
        rows.forEach(::run)
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    fun retry() {
        jdbc
            .sql(
                """
                SELECT id, provider::text AS provider, target, client_id, attempts FROM provider_revocations
                WHERE next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 100
                """,
            ).query(::pending)
            .list()
            .forEach(::run)
    }

    private fun run(row: Pending) {
        val outcome =
            when (row.provider) {
                "kakao" -> kakao.unlink(row.target)
                else -> apple.revoke(row.clientId.orEmpty(), row.target.substringAfter(':'), row.target.substringBefore(':'))
            }
        own.executeWithoutResult {
            when {
                outcome == Outcome.DONE -> delete(row.id)
                outcome == Outcome.GIVE_UP || row.attempts + 1 >= MAX_ATTEMPTS -> {
                    log.warn("gave up the {} revocation {} after {} attempts", row.provider, row.id, row.attempts + 1)
                    delete(row.id)
                }
                else ->
                    jdbc
                        .sql(
                            """
                            UPDATE provider_revocations SET attempts = attempts + 1,
                              next_attempt_at = now() + least(interval '1 minute' * power(2, attempts), interval '1 day')
                            WHERE id = :id
                            """,
                        ).param("id", row.id)
                        .update()
            }
        }
    }

    private fun pending(
        rs: java.sql.ResultSet,
        @Suppress("unused") index: Int,
    ) = Pending(rs.getLong("id"), rs.getString("provider"), rs.getString("target"), rs.getString("client_id"), rs.getInt("attempts"))

    private fun insert(
        provider: String,
        target: String,
        clientId: String?,
    ): Long =
        jdbc
            .sql(
                "INSERT INTO provider_revocations (provider, target, client_id) VALUES (:provider::provider_kind, :target, :client) RETURNING id",
            ).param("provider", provider)
            .param("target", target)
            .param("client", clientId)
            .query(Long::class.java)
            .single()

    private fun delete(id: Long) {
        jdbc.sql("DELETE FROM provider_revocations WHERE id = :id").param("id", id).update()
    }

    private companion object {
        /** About two weeks of daily tries after the first hours. */
        const val MAX_ATTEMPTS = 25
        val log = LoggerFactory.getLogger(ProviderRevocations::class.java)
    }
}
