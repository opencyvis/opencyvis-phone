package ai.opencyvis.backend

import org.junit.Assert.*
import org.junit.Test

class RootConnectorTest {
    @Test
    fun `finds Magisk su under system_ext`() {
        val su = RootConnector.findSuBinary(pathEnv = "") { it == "/system_ext/bin/su" }
        assertEquals("/system_ext/bin/su", su)
    }

    @Test
    fun `finds su on PATH outside the well-known locations`() {
        val su = RootConnector.findSuBinary(pathEnv = "/apex/foo/bin:/odm/bin") { it == "/odm/bin/su" }
        assertEquals("/odm/bin/su", su)
    }

    @Test
    fun `no su means not found`() {
        assertNull(RootConnector.findSuBinary(pathEnv = "/system/bin") { false })
    }

    @Test
    fun `launch script execs the privileged service with handshake args`() {
        val script = RootConnector.buildLaunchScript(
            apkPath = "/data/app/~~a==/ai.opencyvis-b==/base.apk",
            token = "tok",
            authority = "ai.opencyvis.binder_exchange",
            userId = 10,
        )
        val lines = script.trimEnd().lines()
        assertEquals("export CLASSPATH='/data/app/~~a==/ai.opencyvis-b==/base.apk'", lines[0])
        assertTrue(lines[1].startsWith("exec /system/bin/app_process /system/bin "))
        assertTrue(lines[1].contains(" ai.opencyvis.backend.PrivilegedServiceMain "))
        assertTrue(lines[1].endsWith("--token=tok --authority=ai.opencyvis.binder_exchange --user=10"))
        assertTrue("script must end with a newline so the shell runs it", script.endsWith("\n"))
    }
}
