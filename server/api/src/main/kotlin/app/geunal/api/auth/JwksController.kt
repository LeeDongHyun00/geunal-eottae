package app.geunal.api.auth

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** The public keys the gateway verifies access tokens with (gateway.md §3). */
@RestController
class JwksController(
    private val keys: JwtKeys,
) {
    @GetMapping("/.well-known/jwks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jwks(): String = keys.public.toString()
}
