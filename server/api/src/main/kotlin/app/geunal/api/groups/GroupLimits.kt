package app.geunal.api.groups

import app.geunal.api.common.ApiException
import org.springframework.http.HttpStatus

/** The product's group sizes (kickoff §2): 20 groups an account, 20 members a group. */
object GroupLimits {
    const val GROUPS_PER_ACCOUNT = 20
    const val MEMBERS_PER_GROUP = 20

    fun tooManyGroups() = ApiException(HttpStatus.CONFLICT, "group_limit", "an account is in at most $GROUPS_PER_ACCOUNT groups")

    fun full() = ApiException(HttpStatus.CONFLICT, "group_full", "a group has at most $MEMBERS_PER_GROUP members")
}
