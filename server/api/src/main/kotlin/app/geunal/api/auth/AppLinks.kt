package app.geunal.api.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.time.Duration

/**
 * `geunal.app-links.*`: the Android app that may open this domain's links. The fingerprint is the Play app signing
 * certificate's SHA-256 in use (infra.md §10); a debug build signed with another key adds its own on a test stack.
 */
@ConfigurationProperties("geunal.app-links")
data class AppLinksProperties(
    val androidPackage: String = "app.geunal",
    val androidFingerprints: List<String> = emptyList(),
)

data class AssetLink(
    val relation: List<String>,
    val target: AssetTarget,
)

data class AssetTarget(
    val namespace: String,
    val packageName: String,
    val sha256CertFingerprints: List<String>,
)

/**
 * The domain's App Links (api-server.md §7): `assetlinks.json` lets the Android app own `https://<domain>/auth/apple`,
 * where Android's Sign in with Apple comes back (protocol §13), and from M2 the invite links.
 */
@RestController
class AppLinksController(
    private val appLinks: AppLinksProperties,
    private val auth: AuthProperties,
) {
    @GetMapping("/.well-known/assetlinks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun assetLinks(): ResponseEntity<List<AssetLink>> {
        val target = AssetTarget("android_app", appLinks.androidPackage, appLinks.androidFingerprints)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(listOf(AssetLink(listOf("delegate_permission/common.handle_all_urls"), target)))
    }

    /**
     * Apple's `form_post` from Android's web sign-in. Nothing is checked or kept here: the fields go on to the App Link
     * in the fragment, which never reaches a server, percent-encoded and with oversized values left out. The app checks
     * `state` and sends `id_token` to `POST /auth/apple`; `code` it keeps only for an account deletion.
     */
    @PostMapping("/auth/apple/callback", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun appleCallback(
        @RequestParam params: Map<String, String>,
    ): ResponseEntity<Void> {
        if (!auth.apple.enabled) throw ProviderTokens.disabled(Provider.APPLE)
        val fragment =
            CALLBACK_FIELDS
                .mapNotNull { (name, limit) ->
                    params[name]?.takeIf { it.length <= limit }?.let { "$name=${URLEncoder.encode(it, Charsets.UTF_8)}" }
                }.joinToString("&")
        return ResponseEntity
            .status(HttpStatus.SEE_OTHER)
            .location(URI.create("${auth.apple.appLink}#$fragment"))
            .cacheControl(CacheControl.noStore())
            .header(REFERRER_POLICY, "no-referrer")
            .build()
    }

    /**
     * What the App Link address shows when the app is not installed: a fixed page that runs no script, so nothing reads
     * or draws the fragment that carries the code and the token (protocol §13).
     */
    @GetMapping("/auth/apple", produces = [MediaType.TEXT_HTML_VALUE])
    fun appleLanding(): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header(CONTENT_SECURITY_POLICY, "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
            .header(REFERRER_POLICY, "no-referrer")
            .body(LANDING)

    private companion object {
        const val REFERRER_POLICY = "Referrer-Policy"
        const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"

        /** What Apple posts, each with the longest value passed on: codes and states are short, `user` holds a name. */
        val CALLBACK_FIELDS =
            listOf("code" to 512, "id_token" to 4096, "state" to 256, "user" to 1024, "error" to 128)

        val LANDING =
            """
            <!doctype html>
            <html lang="ko">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>그날어때</title></head>
            <body><p>그날어때 앱에서 로그인을 이어 가세요. 앱이 열리지 않으면 앱을 설치하거나 업데이트한 뒤 다시 시도해 주세요.</p></body>
            </html>
            """.trimIndent()
    }
}
