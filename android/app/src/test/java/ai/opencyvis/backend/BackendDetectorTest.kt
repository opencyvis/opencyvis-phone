package ai.opencyvis.backend

import org.junit.Test
import org.junit.Assert.*

class BackendDetectorTest {
    @Test
    fun `system uid detected correctly`() {
        assertTrue(BackendDetector.isSystemUid(1000))
        assertFalse(BackendDetector.isSystemUid(2000))
        assertFalse(BackendDetector.isSystemUid(10142))
        assertFalse(BackendDetector.isSystemUid(0))
    }

    @Test
    fun `rooted device without a preference tries root first`() {
        assertEquals(
            listOf("root", "shizuku", "adb-direct"),
            BackendDetector.connectorOrder(preferred = null, rootLikely = true)
        )
    }

    @Test
    fun `unrooted device without a preference keeps shizuku then adb`() {
        assertEquals(
            listOf("shizuku", "adb-direct"),
            BackendDetector.connectorOrder(preferred = null, rootLikely = false)
        )
    }

    @Test
    fun `root preference is tried first even when su is hidden`() {
        assertEquals(
            listOf("root", "shizuku", "adb-direct"),
            BackendDetector.connectorOrder(preferred = "root", rootLikely = false)
        )
    }

    @Test
    fun `choosing another backend on a rooted device leaves root out`() {
        assertEquals(
            listOf("shizuku", "adb-direct"),
            BackendDetector.connectorOrder(preferred = "shizuku", rootLikely = true)
        )
        assertEquals(
            listOf("adb-direct", "shizuku"),
            BackendDetector.connectorOrder(preferred = "adb-direct", rootLikely = true)
        )
    }
}
