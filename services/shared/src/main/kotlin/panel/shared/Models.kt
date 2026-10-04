package panel.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
fun obj(vararg values: Pair<String, Any?>): JsonObject = JsonObject(values.associate { (k, v) -> k to element(v) })
fun element(v: Any?): JsonElement = when (v) {
    null -> JsonNull; is JsonElement -> v; is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v)
    is Iterable<*> -> JsonArray(v.map(::element)); else -> JsonPrimitive(v.toString())
}
fun JsonObject.str(key: String, default: String = ""): String = this[key]?.jsonPrimitive?.contentOrNull ?: default
fun JsonObject.long(key: String, default: Long = 0): Long = this[key]?.jsonPrimitive?.longOrNull ?: default
fun JsonObject.bool(key: String, default: Boolean = false): Boolean = this[key]?.jsonPrimitive?.let { it.booleanOrNull ?: (it.content == "1") } ?: default
fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

class Problem(val code: String, override val message: String, val status: Int = 400) : RuntimeException(message)
fun requireValid(value: Boolean, code: String, message: String, status: Int = 400) { if (!value) throw Problem(code, message, status) }

@Serializable data class Env(val key: String, val value: String, val secret: Boolean = false)
@Serializable data class Port(val host: Int, val container: Int, val protocol: String = "tcp", val public: Boolean = false)
@Serializable data class Spec(
    val name: String, val jdk: Int = 21, val cpu: Double = 1.0, val memoryMiB: Int = 512,
    val diskMiB: Int = 1024, val jar: String = "app.jar", val jvmArgs: List<String> = emptyList(),
    val appArgs: List<String> = emptyList(), val env: List<Env> = emptyList(), val ports: List<Port> = emptyList(),
    val autostart: Boolean = false, val crashRestart: Boolean = false, val stopSeconds: Int = 30,
    val healthPort: Int? = null, val image: String = ""
)

fun validPath(path: String, root: Boolean = false) {
    requireValid((root && path.isEmpty()) || (path.isNotEmpty() && path.length <= 1024 &&
        !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000') &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".panel-") }),
        "INVALID_PATH", "Путь должен находиться внутри каталога приложения")
}
fun validate(spec: Spec, minPort: Int = 10000, maxPort: Int = 60000) {
    requireValid(spec.name.isNotBlank() && spec.name.length <= 80, "INVALID_NAME", "Имя: от 1 до 80 символов")
    requireValid(spec.jdk in setOf(8,11,17,21,25), "INVALID_JDK", "Недоступная версия JDK")
    requireValid(spec.cpu.isFinite() && spec.cpu in 0.25..64.0 && spec.memoryMiB in 256..262144 && spec.diskMiB in 128..1048576,
        "INVALID_RESOURCES", "CPU ≥ 0.25, RAM ≥ 256 MiB, диск ≥ 128 MiB")
    validPath(spec.jar)
    requireValid(spec.jar.endsWith(".jar"), "INVALID_JAR", "Выберите файл .jar")
    requireValid(spec.stopSeconds in 1..120, "INVALID_TIMEOUT", "Остановка: 1–120 секунд")
    requireValid(spec.ports.size <= 10 && spec.ports.distinctBy { it.host to it.protocol }.size == spec.ports.size &&
        spec.ports.all { it.host in minPort..maxPort && it.container in 1..65535 && it.protocol in setOf("tcp","udp") },
        "INVALID_PORT", "Недопустимый или повторяющийся порт")
    requireValid(spec.healthPort == null || spec.ports.any { it.container == spec.healthPort && it.protocol == "tcp" },
        "INVALID_HEALTH", "Health port должен совпадать с TCP-портом приложения")
    val protectedEnv = setOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "LD_PRELOAD", "LD_LIBRARY_PATH")
    requireValid(spec.env.size <= 100 && spec.env.distinctBy { it.key }.size == spec.env.size && spec.env.all {
        it.key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) && it.key !in protectedEnv && it.value.length <= 16384 && !it.value.contains('\u0000')
    }, "INVALID_ENV", "Проверьте ключи ENV, дубли и защищённые Java options")
    // Memory and executable-loading options are owned by the panel. No shell is involved.
    requireValid(spec.jvmArgs.size <= 32 && spec.jvmArgs.all {
        it.length in 1..512 && !it.contains('\u0000') &&
        (it.matches(Regex("-D[A-Za-z0-9_.-]+=[^\\r\\n]*")) || it in setOf("-XX:+UseG1GC", "-XX:+UseSerialGC", "-ea", "-da"))
    }, "INVALID_JVM_ARGS", "Разрешены -Dkey=value, -ea, -da и выбранные GC; heap задаётся панелью")
    requireValid(spec.appArgs.size <= 64 && spec.appArgs.all { it.length <= 2048 && !it.contains('\u0000') },
        "INVALID_APP_ARGS", "Слишком большие аргументы приложения")
}
fun javaCommand(spec: Spec): List<String> = listOf("java", "-Xms${minOf(128, spec.memoryMiB / 4)}m", "-Xmx${spec.memoryMiB * 7 / 10}m",
    "-Djava.io.tmpdir=/data/.panel-tmp") + spec.jvmArgs + listOf("-jar", "/data/${spec.jar}") + spec.appArgs

class Secrets(encodedKey: String) {
    private val key = SecretKeySpec(Base64.getDecoder().decode(encodedKey).also { require(it.size == 32) }, "AES")
    fun encrypt(value: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return "enc:v1:" + Base64.getEncoder().encodeToString(nonce + c.doFinal(value.toByteArray()))
    }
    fun decrypt(value: String): String {
        require(value.startsWith("enc:v1:"))
        val bytes = Base64.getDecoder().decode(value.removePrefix("enc:v1:"))
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        return String(c.doFinal(bytes.copyOfRange(12, bytes.size)))
    }
}
