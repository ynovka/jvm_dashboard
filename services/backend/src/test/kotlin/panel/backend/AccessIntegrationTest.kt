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
        val registered=post("/auth/register",obj("email" to "admin@example.test","name" to "Admin","password" to "password-with-twelve-chars","token" to bootstrap))
        assertEquals(HttpStatusCode.Created,registered.status,registered.bodyAsText())
        val cookie=registered.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertTrue(registered.headers[HttpHeaders.SetCookie]!!.contains("HttpOnly")); assertTrue(registered.headers[HttpHeaders.SetCookie]!!.contains("Secure"))
        val reuse=post("/auth/register",obj("email" to "other@example.test","name" to "Other","password" to "password-with-twelve-chars","token" to bootstrap))
        assertEquals(HttpStatusCode.BadRequest,reuse.status)
        assertNull(store.bootstrap())
        val me=client.get("/api/v1/auth/me") { header("Cookie",cookie) }; assertEquals(HttpStatusCode.OK,me.status)
        val data=json.parseToJsonElement(me.bodyAsText()).jsonObject; val csrf=data.str("csrf"); val ws=data["workspaces"]!!.jsonArray.first().jsonObject.str("id")
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
        val lastAdmin=post("/auth/logout",obj(),cookie,csrf); assertEquals(HttpStatusCode.OK,lastAdmin.status)
        val expired=client.get("/api/v1/auth/me") { header("Cookie",cookie) }; assertEquals(HttpStatusCode.Unauthorized,expired.status)
    }
}
