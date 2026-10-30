package app.geunal.feature.account.support

/**
 * Which build this is. M1 ships to testers as an alpha, and only alpha builds offer the test statistics consent (m1.md
 * screen 3, decision 10; protocol §14). The app module sets it from the `geunalChannel` Gradle property, so a public
 * build turns it off without a code change.
 */
enum class BuildChannel {
    ALPHA,
    PUBLIC,
    ;

    companion object {
        fun of(name: String): BuildChannel = if (name.equals("alpha", ignoreCase = true)) ALPHA else PUBLIC
    }
}
