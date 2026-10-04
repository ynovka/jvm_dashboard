package panel.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import kotlinx.serialization.json.*
import panel.shared.*
import java.sql.Connection
import java.util.UUID

fun id(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()
fun env(name: String, fallback: String? = null): String = System.getenv(name) ?: fallback ?: error("Missing $name")
fun Connection.execute(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
    args.forEachIndexed { i, v -> s.setObject(i+1, v) }; s.executeUpdate()
}
fun Connection.rows(sql: String, vararg args: Any?): List<JsonObject> = prepareStatement(sql).use { s ->
    args.forEachIndexed { i,v -> s.setObject(i+1,v) }; s.executeQuery().use { r ->
        buildList { while (r.next()) add(JsonObject((1..r.metaData.columnCount).associate { i -> r.metaData.getColumnLabel(i) to element(r.getObject(i)) })) }
    }
}
fun Connection.one(sql: String, vararg args: Any?): JsonObject? = rows(sql, *args).firstOrNull()

class Store {
    val secrets = Secrets(env("ENCRYPTION_KEY"))
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = env("DATABASE_URL", "jdbc:mariadb://127.0.0.1:3306/jvm_dashboard?allowLocalInfile=false")
        username = env("DATABASE_USER", "jvm_dashboard"); password = env("DATABASE_PASSWORD")
        maximumPoolSize = 8; connectionTimeout = 5000
    })
    init {
        pool.connection.use { c ->
            val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(c))
            Liquibase("db/changelog.sql", ClassLoaderResourceAccessor(), database).use { it.update("") }
        }
        tx { c ->
            c.execute("INSERT IGNORE INTO nodes(id,cpu,memory_mib,disk_mib,snapshot) VALUES(?,?,?,?,?)", "local", env("NODE_CPU","2").toDouble(), env("NODE_MEMORY_MIB","2048").toLong(), env("NODE_DISK_MIB","16384").toLong(), "{}")
            val images = json.parseToJsonElement(env("JDK_IMAGES_JSON", "{}" )).jsonObject
            for ((jdk,image) in images) {
                require(image.jsonPrimitive.content.matches(Regex("eclipse-temurin@sha256:[a-f0-9]{64}"))) { "JDK image must be pinned by digest" }
                c.execute("INSERT IGNORE INTO runtime_images(jdk,image) VALUES(?,?)", jdk.toInt(), image.jsonPrimitive.content)
            }
        }
    }
    fun <T> tx(block: (Connection) -> T): T = pool.connection.use { c ->
        c.autoCommit = false
        try { val result = block(c); c.commit(); result } catch (e: Throwable) { c.rollback(); throw e }
    }
    fun <T> read(block: (Connection) -> T): T = pool.connection.use(block)
    fun audit(c: Connection, user: String?, action: String, target: String?, requestId: String) {
        c.execute("INSERT INTO audit_events VALUES(?,?,?,?,?,?)", id(), user, action, target, now(), requestId)
    }
    fun bootstrap(): String? = tx { c ->
        c.one("SELECT id FROM bootstrap_state WHERE id=1 FOR UPDATE")
        if (c.one("SELECT COUNT(*) AS n FROM users")!!.long("n") != 0L) return@tx null
        c.execute("UPDATE invitations SET revoked=TRUE WHERE bootstrap=TRUE AND used=FALSE")
        val t = token()
        c.execute("INSERT INTO invitations(id,token_hash,role,expires,bootstrap) VALUES(?,?,'OWNER',?,TRUE)", id(), hash(t), now()+86400000)
        "${env("PUBLIC_URL").trimEnd('/')}/register#token=$t"
    }
    fun stored(spec: Spec, previous: Spec? = null): Spec = spec.copy(env = spec.env.map { e ->
        if (!e.secret) e else if (e.value.isEmpty()) {
            previous?.env?.firstOrNull { it.key == e.key && it.secret } ?: e.copy(value = secrets.encrypt(""))
        } else e.copy(value = secrets.encrypt(e.value))
    })
    fun plain(spec: Spec): Spec = spec.copy(env = spec.env.map { if (it.secret) it.copy(value=secrets.decrypt(it.value)) else it })
    fun masked(spec: Spec): Spec = spec.copy(env = spec.env.map { if (it.secret) it.copy(value="") else it })
}
