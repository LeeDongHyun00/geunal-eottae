package app.geunal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Backups and device transfers carry the app's own preferences file by name and nothing else, so a library's
 * preferences (the Kakao SDK keeps its own) never travel (docs/security/app-review.md §1 "백업에서 빠짐").
 */
class BackupRulesTest {
    @Test
    fun carriesOnlyTheAppsOwnPreferencesByName() {
        // Unit tests run in the module's directory.
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = File("src/main/res/xml/$name").readText()
            val included =
                Regex("""<include\s+domain="([^"]+)"\s+path="([^"]+)"""")
                    .findAll(xml)
                    .map { it.groupValues[1] to it.groupValues[2] }
                    .toSet()
            assertEquals(name, setOf("sharedpref" to "calendar.xml"), included)
        }
    }
}
