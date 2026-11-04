package app.geunal.bridge

import java.net.URI
import java.net.URLDecoder

/**
 * Reads the invite code out of what brought someone in: the App Link `https://<invite host>/i/<code>` (an installed app)
 * or the Play Install Referrer `invite=<code>` (the store page the link sent them to before install, docs/parts/android.md
 * §4). Anything else is not an invite, and the code is checked here so that a malformed one never reaches the server.
 */
object InviteLink {
    /** The referrer key the invite page puts the code under when it sends someone to the store. */
    const val REFERRER_KEY = "invite"

    // The server's codes are base32 of at least 50 bits (docs/parts/api-server.md group_invites); the upper bound only
    // keeps a hostile link from carrying an arbitrary blob into the app's files.
    private val code = Regex("[A-Za-z0-9]{10,64}")

    /** The code in [link] when it is an invite on [host], else null. The fragment (group name and color) is not read. */
    fun fromLink(
        link: String,
        host: String,
    ): String? {
        if (host.isEmpty()) return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme != "https" || !uri.host.equals(host, ignoreCase = true)) return null
        if (uri.port != -1 && uri.port != 443) return null
        val path = uri.path ?: return null
        val candidate = path.removePrefix("/i/").removeSuffix("/")
        return candidate.takeIf { path.startsWith("/i/") && code.matches(it) }
    }

    /** The code in a Play Install Referrer string (`invite=<code>` among `&`-separated pairs), else null. */
    fun fromReferrer(referrer: String): String? =
        referrer
            .split('&')
            .firstNotNullOfOrNull { pair ->
                val key = pair.substringBefore('=')
                if (pair.contains('=') && key == REFERRER_KEY) decode(pair.substringAfter('=')) else null
            }?.takeIf { code.matches(it) }

    private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
}
