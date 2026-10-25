package app.geunal.api.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * `GEUNAL_AUTH_STUB` lets local runs and CI sign in without Kakao or Apple (infra.md §4). A production deployment that
 * carries it would be an open door, so the API refuses to start instead.
 */
@Component
class AuthStubGuard(
    environment: Environment,
    @Value("\${geunal.auth.stub:false}") stub: Boolean,
) {
    init {
        check(!(stub && environment.matchesProfiles("prod"))) {
            "geunal.auth.stub (GEUNAL_AUTH_STUB) must not be set when the prod profile is active"
        }
    }
}
