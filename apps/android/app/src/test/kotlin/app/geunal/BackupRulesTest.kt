package app.geunal

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Backups and device transfers carry the app's own preferences file by name and nothing else, so a library's
 * preferences (the Kakao SDK keeps its own) never travel (docs/security/app-review.md §1 "백업에서 빠짐").
 */
class BackupRulesTest {
    @Test
    fun autoBackupCarriesOnlyTheAppsOwnPreferences() {
        val rules = parse("backup_rules.xml")

        assertEquals("full-backup-content", rules.tagName)
        assertEquals(setOf(APP_PREFERENCES), includes(rules))
    }

    /**
     * Each section on its own: a section without an include takes everything, so one that lost its line would move the
     * whole app's data while a check over the file as one set still passed.
     */
    @Test
    fun cloudBackupAndDeviceTransferEachCarryOnlyTheAppsOwnPreferences() {
        val rules = parse("data_extraction_rules.xml")

        for (name in listOf("cloud-backup", "device-transfer")) {
            val sections = rules.getElementsByTagName(name)
            assertEquals(name, 1, sections.length)
            assertEquals(name, setOf(APP_PREFERENCES), includes(sections.item(0) as Element))
        }
    }

    // Unit tests run in the module's directory.
    private fun parse(name: String): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/xml/$name"))
            .documentElement

    private fun includes(section: Element): Set<Pair<String, String>> {
        val nodes = section.getElementsByTagName("include")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()
    }

    private companion object {
        val APP_PREFERENCES = "sharedpref" to "calendar.xml"
    }
}
