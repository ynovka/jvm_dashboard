package panel.shared
import kotlin.test.*
import org.junit.jupiter.api.Test
import java.util.Base64

class ValidationTest {
    @Test fun `traversal and panel internals are rejected`() {
        for (p in listOf("../a.jar", "/a.jar", "a/../b", "a\\b", ".panel-upload/x", "a//b")) assertFailsWith<Problem> { validPath(p) }
        validPath("plugins/config.json")
    }
    @Test fun `memory and environment cannot override runtime limits`() {
        assertFailsWith<Problem> { validate(Spec("demo", jvmArgs = listOf("-Xmx8G"))) }
        assertFailsWith<Problem> { validate(Spec("demo", env = listOf(Env("JAVA_TOOL_OPTIONS", "-Xmx8G")))) }
        assertFailsWith<Problem> { validate(Spec("demo", cpu = Double.NaN)) }
        assertFailsWith<Problem> { validate(Spec("demo", ports = listOf(Port(80,80)))) }
        assertEquals("-Xmx358m", javaCommand(Spec("demo"))[2])
    }
    @Test fun `secrets authenticated encryption and tamper detection`() {
        val secrets = Secrets(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        val cipher = secrets.encrypt("password")
        assertFalse(cipher.contains("password")); assertEquals("password", secrets.decrypt(cipher))
        assertNotEquals(cipher, secrets.encrypt("password"))
        assertFails { secrets.decrypt(cipher.dropLast(4) + "AAAA") }
    }
}
