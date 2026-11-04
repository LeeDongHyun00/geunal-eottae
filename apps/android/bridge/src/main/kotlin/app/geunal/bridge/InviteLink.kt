package app.geunal.bridge

import java.net.URI
import java.net.URLDecoder

/**
 * Picks out what brought someone in with an invite: the App Link `https://<invite host>/i/<code>#n=…&c=…&i=…` (an
 * installed app) or the Play Install Referrer `invite=<code>` (the store page the link sent them to before install,
 * docs/parts/android.md §4).
 *
 * This is only a gate on what the app keeps. The core reads the code, the group's name and color and who invited
 * (`parse_invite`, protocol §15), so the link is kept whole, fragment included, and the shape checked here stays loose:
 * the code's characters and a bounded length, so that a hostile link cannot park an arbitrary blob in the app's files.
 */
object InviteLink {
    /** The referrer key the invite page puts the code under when it sends someone to the store. */
    const val REFERRER_KEY = "invite"

    // Ten base32 characters, shown with a hyphen (core/vectors/invites.json); the core folds case and look-alikes.
    private val code = Regex("[A-Za-z0-9-]{10,16}")

    // The fragment carries a group name and a person's name; a few hundred characters is plenty for both.
    private const val MAX_LINK = 1024

    /** [link] itself when it is an invite on [host], else null. */
    fun fromLink(
        link: String,
        host: String,
    ): String? {
        if (host.isEmpty() || link.length > MAX_LINK) return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme != "https" || !uri.host.equals(host, ignoreCase = true)) return null
        if (uri.port != -1 && uri.port != 443) return null
        val path = uri.path ?: return null
        if (!path.startsWith("/i/") || !code.matches(path.removePrefix("/i/").removeSuffix("/"))) return null
        return link
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
