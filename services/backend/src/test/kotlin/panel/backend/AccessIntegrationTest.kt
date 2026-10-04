package panel.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import panel.shared.*
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame

class AccessIntegrationTest {
    @Test fun `real MariaDB invitation session CSRF and workspace isolation`() = testApplication {
        assumeTrue(System.getenv("RUN_DB_TESTS")=="true", "Set RUN_DB_TESTS and real MariaDB credentials")
        java.sql.DriverManager.getConnection(env("DATABASE_URL"),env("DATABASE_USER"),env("DATABASE_PASSWORD")).use { c -> c.execute("ALTER DATABASE jvm_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci") }
        val store=Store()
        store.tx { c ->
            c.execute("SET FOREIGN_KEY_CHECKS=0")
            for(t in listOf("audit_events","operations","port_allocations","application_revisions","application_permissions","storage_allocations","applications","sessions","invitations","memberships","workspaces","users")) c.execute("TRUNCATE TABLE $t")
            c.execute("SET FOREIGN_KEY_CHECKS=1")
        }
        application { panel(store) }
        val link=store.bootstrap()!!; val bootstrap=link.substringAfter("token=")
        val origin=env("PUBLIC_URL")
        suspend fun post(path: String, b: JsonObject, cookie: String?=null, csrf: String?=null) = client.post("/api/v1$path") {
            header("Origin",origin); contentType(ContentType.Application.Json); setBody(b.toString())
            if(cookie!=null) header("Cookie",cookie)
            if(csrf!=null) header("X-CSRF-Token",csrf)
        }
        val attempts=coroutineScope { listOf("admin@example.test","racing@example.test").map { email -> async { post("/auth/register",obj("email" to email,"name" to "Admin","password" to "password-with-twelve-chars","token" to bootstrap)) } }.map { it.await() } }
        assertEquals(1,attempts.count { it.status==HttpStatusCode.Created })
        assertEquals(1,attempts.count { it.status==HttpStatusCode.BadRequest })
        val registered=attempts.first { it.status==HttpStatusCode.Created }
        assertEquals(HttpStatusCode.Created,registered.status,registered.bodyAsText())
        val cookie=registered.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertTrue(registered.headers[HttpHeaders.SetCookie]!!.contains("HttpOnly")); assertTrue(registered.headers[HttpHeaders.SetCookie]!!.contains("Secure"))
        val reuse=post("/auth/register",obj("email" to "other@example.test","name" to "Other","password" to "password-with-twelve-chars","token" to bootstrap))
        assertEquals(HttpStatusCode.BadRequest,reuse.status)
        assertNull(store.bootstrap())
        val me=client.get("/api/v1/auth/me") { header("Cookie",cookie) }; assertEquals(HttpStatusCode.OK,me.status)
        val data=json.parseToJsonElement(me.bodyAsText()).jsonObject; val csrf=data.str("csrf"); val ws=data["workspaces"]!!.jsonArray.first().jsonObject.str("id")
        store.tx { it.execute("UPDATE nodes SET ready=TRUE,heartbeat=?,cpu=0.5,memory_mib=256,disk_mib=128 WHERE id='local'",now()); it.execute("INSERT IGNORE INTO runtime_images(jdk,image) VALUES(21,?)","eclipse-temurin@sha256:"+"a".repeat(64)) }
        val spec=Spec("quota-test",cpu=0.5,memoryMiB=256,diskMiB=128)
        suspend fun create(key:String)=client.post("/api/v1/applications") { header("Origin",origin); header("Cookie",cookie); header("X-CSRF-Token",csrf); header("Idempotency-Key",key); contentType(ContentType.Application.Json); setBody(obj("workspaceId" to ws,"spec" to json.encodeToJsonElement(spec)).toString()) }
        val created=create("same-create"); assertEquals(HttpStatusCode.Accepted,created.status,created.bodyAsText())
        val repeated=create("same-create"); assertEquals(HttpStatusCode.Accepted,repeated.status,repeated.bodyAsText()); assertEquals(created.bodyAsText(),repeated.bodyAsText())
        val exhausted=create("other-create"); assertEquals(HttpStatusCode.Conflict,exhausted.status)
        val invalid=post("/workspaces",obj("name" to "Invalid","cpu" to -1,"memory_mib" to 256,"disk_mib" to 128),cookie,csrf); assertEquals(HttpStatusCode.BadRequest,invalid.status)
        val lastAdmin=client.patch("/api/v1/users/${data.str("id")}") { header("Origin",origin); header("Cookie",cookie); header("X-CSRF-Token",csrf); contentType(ContentType.Application.Json); setBody(obj("admin" to false).toString()) }; assertEquals(HttpStatusCode.Conflict,lastAdmin.status)
        val denied=post("/workspaces/$ws/invitations",obj("role" to "VIEWER"),cookie)
        assertEquals(HttpStatusCode.Forbidden,denied.status)
        val invite=post("/workspaces/$ws/invitations",obj("role" to "VIEWER","email" to "viewer@example.test"),cookie,csrf)
        assertEquals(HttpStatusCode.Created,invite.status,invite.bodyAsText()); val token=json.parseToJsonElement(invite.bodyAsText()).jsonObject.str("url").substringAfter("token=")
        val wrongEmail=post("/auth/register",obj("email" to "wrong@example.test","name" to "Wrong","password" to "password-with-twelve-chars","token" to token))
        assertEquals(HttpStatusCode.BadRequest,wrongEmail.status)
        val viewer=post("/auth/register",obj("email" to "viewer@example.test","name" to "Viewer","password" to "password-with-twelve-chars","token" to token))
        assertEquals(HttpStatusCode.Created,viewer.status); val viewerCookie=viewer.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        // A viewer without assignments cannot discover applications or admin data.
        val list=client.get("/api/v1/applications") { header("Cookie",viewerCookie) }; assertEquals(0,json.parseToJsonElement(list.bodyAsText()).jsonObject.long("total"))
        val admin=client.get("/api/v1/nodes/local") { header("Cookie",viewerCookie) }; assertEquals(HttpStatusCode.Forbidden,admin.status)
        val aid=json.parseToJsonElement(created.bodyAsText()).jsonObject.str("id")
        val member=json.parseToJsonElement(viewer.bodyAsText()).jsonObject.str("id")
        val assigned=client.patch("/api/v1/workspaces/$ws/members/$member") { header("Origin",origin); header("Cookie",cookie); header("X-CSRF-Token",csrf); contentType(ContentType.Application.Json); setBody(obj("role" to "VIEWER","appIds" to listOf(aid)).toString()) }
        assertEquals(HttpStatusCode.OK,assigned.status,assigned.bodyAsText())
        val oldSession=client.get("/api/v1/auth/me") { header("Cookie",viewerCookie) }; assertEquals(HttpStatusCode.Unauthorized,oldSession.status)
        val login=post("/auth/login",obj("email" to "viewer@example.test","password" to "password-with-twelve-chars"))
        val activeCookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val viewerMe=client.get("/api/v1/auth/me") { header("Cookie",activeCookie) }; val viewerCsrf=json.parseToJsonElement(viewerMe.bodyAsText()).jsonObject.str("csrf")
        val assignedApp=client.get("/api/v1/applications/$aid") { header("Cookie",activeCookie) }; assertEquals(HttpStatusCode.OK,assignedApp.status)
        val forbiddenStart=post("/applications/$aid/actions/start",obj(),activeCookie,viewerCsrf); assertEquals(HttpStatusCode.Forbidden,forbiddenStart.status)
        val socketClient=client.config { install(io.ktor.client.plugins.websocket.WebSockets) }
        socketClient.webSocket("/ws/applications/$aid",request={ header("Origin",origin); header("Cookie",activeCookie) }) {
            assertTrue(withTimeout(10000) { incoming.receive() } is Frame.Text)
            val disabled=client.patch("/api/v1/users/$member") { header("Origin",origin); header("Cookie",cookie); header("X-CSRF-Token",csrf); contentType(ContentType.Application.Json); setBody(obj("disabled" to true).toString()) }; assertEquals(HttpStatusCode.OK,disabled.status)
            val closed=withTimeout(10000) { incoming.receiveCatching() }
            assertTrue(closed.isClosed || closed.getOrNull() is Frame.Close,"Revoked session must close its active socket")
        }
        socketClient.close()
        val logout=post("/auth/logout",obj(),cookie,csrf); assertEquals(HttpStatusCode.OK,logout.status)
        val expired=client.get("/api/v1/auth/me") { header("Cookie",cookie) }; assertEquals(HttpStatusCode.Unauthorized,expired.status)
    }
}
