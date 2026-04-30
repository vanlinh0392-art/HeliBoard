package helium314.keyboard.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubReleaseCheckerTest {
    @Test
    fun compareReleaseVersion_handlesSemanticVersionTags() {
        assertTrue(GitHubReleaseChecker.compareReleaseVersion("v3.6.1", "3.6", 3603) > 0)
        assertEquals(0, GitHubReleaseChecker.compareReleaseVersion("v3.6.0", "3.6", 3603))
        assertTrue(GitHubReleaseChecker.compareReleaseVersion("v3.5.9", "3.6", 3603) < 0)
    }

    @Test
    fun compareReleaseVersion_handlesVersionCodes() {
        assertTrue(GitHubReleaseChecker.compareReleaseVersion("3604", "3.6", 3603) > 0)
        assertEquals(0, GitHubReleaseChecker.compareReleaseVersion("3603", "3.6", 3603))
        assertTrue(GitHubReleaseChecker.compareReleaseVersion("3602", "3.6", 3603) < 0)
    }

    @Test
    fun extractVersionParts_ignoresNonNumericText() {
        assertEquals(listOf(2026, 4, 30), GitHubReleaseChecker.extractVersionParts("release-2026-04-30"))
        assertFalse(GitHubReleaseChecker.extractVersionParts("stable").isNotEmpty())
    }
}
