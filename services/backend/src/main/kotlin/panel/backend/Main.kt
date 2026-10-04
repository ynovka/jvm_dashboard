package panel.backend

import de.mkammerer.argon2.Argon2Factory
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import panel.shared.*
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class User(val id: String, val admin: Boolean, val csrf: String)
val attemptWindows = ConcurrentHashMap<String, Pair<Long,Int>>()
val sockets = ConcurrentHashMap<String, AtomicInteger>()
val passwordWorkers = Semaphore(1)

fun main(args: Array<String>) {
    val store = Store()
    if (args.firstOrNull() == "bootstrap") { store.bootstrap()?.let(::println); return }
    if (args.firstOrNull() == "recover") {
        require(args.size == 2) { "recover email (new password is read from console)" }
        val password = System.console()?.readPassword("New password: ") ?: error("TTY required")
        require(password.size >= 12)
        val ph = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).hash(3,65536,1,password); password.fill('\u0000')
        store.tx { c ->
            val u = c.one("SELECT id FROM users WHERE email=?", args[1].lowercase()) ?: error("Unknown account")
            c.execute("UPDATE users SET password_hash=?, disabled=FALSE WHERE id=?", ph,u.str("id"))
            c.execute("DELETE FROM sessions WHERE user_id=?",u.str("id")); store.audit(c,u.str("id"),"LOCAL_RECOVERY",u.str("id"),id())
        }; return
    }
    store.bootstrap()?.let { println("BOOTSTRAP_INVITATION=$it") }
    embeddedServer(Netty, host="127.0.0.1", port=env("API_PORT","8080").toInt()) { panel(store) }.start(wait=true)
}

suspend fun ApplicationCall.body(): JsonObject = withContext(Dispatchers.IO) {
    val bytes = receiveStream().use { it.readNBytes(2*1024*1024+1) }
    requireValid(bytes.size <= 2*1024*1024, "BODY_TOO_LARGE", "Запрос слишком большой",413)
    try { json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject } catch (_: Exception) { throw Problem("INVALID_JSON","Неверный JSON") }
}
fun ApplicationCall.requestId() = response.headers["X-Request-Id"] ?: id()
fun origin(call: ApplicationCall) { requireValid(call.request.headers["Origin"] == env("PUBLIC_URL").trimEnd('/'), "ORIGIN", "Недопустимый Origin",403) }
fun auth(store: Store, call: ApplicationCall, write: Boolean = false): User {
    val cookie = call.request.cookies["jvm_session"] ?: throw Problem("UNAUTHENTICATED","Войдите в панель",401)
    val u = store.tx { c ->
        val row = c.one("SELECT s.*,u.admin,u.disabled FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=?",hash(cookie))
            ?: throw Problem("UNAUTHENTICATED","Сессия завершена",401)
        requireValid(!row.bool("disabled") && now()-row.long("created") < 7*86400000L && now()-row.long("touched") < 86400000L,
            "UNAUTHENTICATED","Сессия завершена",401)
        c.execute("UPDATE sessions SET touched=? WHERE token_hash=?",now(),hash(cookie))
        User(row.str("user_id"),row.bool("admin"),row.str("csrf"))
    }
    if (write) { origin(call); requireValid(call.request.headers["X-CSRF-Token"] == u.csrf, "CSRF", "Обновите страницу и повторите запрос",403) }
    return u
}
fun admin(u: User) { requireValid(u.admin,"FORBIDDEN","Требуется администратор платформы",403) }
fun role(c: Connection, u: User, workspace: String): String {
    if (u.admin) return "OWNER"
    return c.one("SELECT role FROM memberships WHERE user_id=? AND workspace_id=?",u.id,workspace)?.str("role") ?: throw Problem("NOT_FOUND","Объект не найден",404)
}
fun app(c: Connection, u: User, appId: String, write: Boolean = false, owner: Boolean = false): JsonObject {
    val a = c.one("SELECT * FROM applications WHERE id=? AND deleted=FALSE",appId) ?: throw Problem("NOT_FOUND","Приложение не найдено",404)
    val r = role(c,u,a.str("workspace_id"))
    requireValid(r == "OWNER" || (!owner && c.one("SELECT app_id FROM application_permissions WHERE app_id=? AND user_id=?",appId,u.id) != null),"NOT_FOUND","Приложение не найдено",404)
    requireValid(!write || r in setOf("OWNER","OPERATOR"),"FORBIDDEN","Недостаточно прав",403)
    return a
}
fun session(store: Store, call: ApplicationCall, userId: String) {
    val t=token(); val csrf=token()
    store.tx { c -> c.execute("INSERT INTO sessions VALUES(?,?,?,?,?)",hash(t),userId,csrf,now(),now())
        c.execute("DELETE FROM sessions WHERE created<? OR touched<?", now()-7*86400000L,now()-86400000L) }
    val insecure = env("ALLOW_INSECURE_LOCAL","false") == "true" && env("PUBLIC_URL").matches(Regex("http://(localhost|127\\.0\\.0\\.1):[0-9]+"))
    call.response.cookies.append(Cookie("jvm_session",t,path="/",secure=!insecure,httpOnly=true,extensions=mapOf("SameSite" to "Strict")))
}
fun rate(key: String) {
    if (attemptWindows.size > 10000) attemptWindows.entries.removeIf { now()-it.value.first > 60000 }
    val count = attemptWindows.compute(key) { _,old -> if (old==null || now()-old.first>60000) now() to 1 else old.first to old.second+1 }!!
    requireValid(count.second <= 10,"RATE_LIMIT","Слишком много попыток. Подождите минуту",429)
}
fun sanitized(store: Store, row: JsonObject): JsonObject {
    val spec=json.decodeFromString<Spec>(row.str("spec"))
    return JsonObject(row.filterKeys { it != "spec" }) + obj("spec" to json.encodeToJsonElement(store.masked(spec)))
}
operator fun JsonObject.plus(other: JsonObject) = JsonObject(this.toMap()+other.toMap())
fun budget(c: Connection, spec: Spec, workspace: String, except: String = "") {
    // Fixed lock order prevents concurrent reservations exceeding either budget.
    val n=c.one("SELECT * FROM nodes WHERE id='local' FOR UPDATE")!!
    requireValid(n.bool("ready") && now()-n.long("heartbeat")<15000,"NODE_OFFLINE","Узел не готов: проверьте quota, firewall и связь",503)
    val w=c.one("SELECT * FROM workspaces WHERE id=? FOR UPDATE",workspace) ?: throw Problem("NOT_FOUND","Рабочая область не найдена",404)
    val all=c.one("SELECT COALESCE(SUM(cpu),0) cpu, COALESCE(SUM(memory_mib),0) memory_mib, (SELECT COALESCE(SUM(disk_mib),0) FROM storage_allocations WHERE app_id<>?) disk_mib FROM applications WHERE deleted=FALSE AND id<>?",except,except)!!
    val own=c.one("SELECT COALESCE(SUM(cpu),0) cpu, COALESCE(SUM(memory_mib),0) memory_mib, (SELECT COALESCE(SUM(disk_mib),0) FROM storage_allocations WHERE workspace_id=? AND app_id<>?) disk_mib FROM applications WHERE deleted=FALSE AND workspace_id=? AND id<>?",workspace,except,workspace,except)!!
    for ((used,limit) in listOf(all to n,own to w)) requireValid(used.str("cpu").toDouble()+spec.cpu<=limit.str("cpu").toDouble() && used.long("memory_mib")+spec.memoryMiB<=limit.long("memory_mib") && used.long("disk_mib")+spec.diskMiB<=limit.long("disk_mib"),"QUOTA","Недостаточно свободной квоты CPU, RAM или диска",409)
    requireValid(c.one("SELECT jdk FROM runtime_images WHERE jdk=? AND enabled=TRUE",spec.jdk)!=null,"INVALID_JDK","Этот JDK отключён")
}
fun ports(c: Connection, spec: Spec, appId: String) {
    for (p in spec.ports) {
        val existing=c.one("SELECT app_id FROM port_allocations WHERE node_id='local' AND port=? AND protocol=?",p.host,p.protocol)
        requireValid(existing==null || existing.str("app_id")==appId,"PORT_CONFLICT","Порт ${p.host}/${p.protocol} уже выделен",409)
        c.execute("INSERT IGNORE INTO port_allocations VALUES('local',?,?,?)",p.host,p.protocol,appId)
    }
}
fun enqueue(c: Connection, u: User, kind: String, appId: String?, payload: JsonObject, generation: Long, key: String?): String {
    if (key!=null) {
        requireValid(key.length in 1..80,"INVALID_KEY","Неверный Idempotency-Key")
        c.one("SELECT * FROM operations WHERE user_id=? AND idempotency_key=?",u.id,key)?.let {
            requireValid(it.str("kind")==kind && it.str("app_id")== (appId ?: "") && it.str("payload")==payload.toString(),"IDEMPOTENCY_CONFLICT","Ключ уже использован для другого запроса",409)
            return it.str("id")
        }
    }
    val operation=id()
    c.execute("INSERT INTO operations(id,app_id,node_id,user_id,kind,payload,generation,created,idempotency_key) VALUES(?,?,'local',?,?,?,?,?,?)",operation,appId,u.id,kind,payload.toString(),generation,now(),key)
    return operation
}
suspend fun executeAgent(store: Store, u: User, kind: String, appId: String?, payload: JsonObject): JsonObject {
    val op=store.tx { c -> enqueue(c,u,kind,appId,payload,0,null) }
    repeat(150) {
        val row=store.read { it.one("SELECT * FROM operations WHERE id=?",op)!! }
        if (row.str("status") in setOf("SUCCEEDED","FAILED")) {
            val result=json.parseToJsonElement(row.str("result","{}")).jsonObject
            if (row.str("status")=="FAILED") throw Problem(result.str("code","AGENT_ERROR"),result.str("message","Ошибка узла"),result.long("status",400).toInt())
            // Transfer chunks are transient; do not retain uploaded bytes in the metadata database.
            store.tx { it.execute("DELETE FROM operations WHERE id=? AND kind IN ('FILE','LOGS','FIREWALL')",op) }
            return result
        }; delay(200)
    }
    throw Problem("AGENT_TIMEOUT","Узел не ответил. Обновите состояние перед повтором",504)
}
fun internal(call: ApplicationCall) { requireValid(call.request.headers["Authorization"] == "Bearer ${env("AGENT_TOKEN")}","FORBIDDEN","Invalid node identity",403) }

fun Application.panel(store: Store) {
    install(ContentNegotiation) { json(panel.shared.json) }
    install(WebSockets) { pingPeriod=15.seconds; timeout=30.seconds; maxFrameSize=4096 }
    install(StatusPages) {
        exception<Throwable> { call,cause ->
            val p= cause as? Problem ?: Problem("INTERNAL_ERROR","Внутренняя ошибка; проверьте журнал по requestId",500)
            if (p.status==500) this@panel.log.error("request {} failed: {}",call.requestId(),cause.javaClass.simpleName)
            call.respond(HttpStatusCode.fromValue(p.status), obj("code" to p.code,"message" to p.message,"fieldErrors" to obj(),"requestId" to call.requestId()))
        }
    }
    intercept(ApplicationCallPipeline.Call) {
        call.response.header("X-Request-Id",id()); call.response.header("Cache-Control","no-store"); call.response.header("Referrer-Policy","no-referrer")
    }
    routing {
        get("/health") { call.respond(obj("status" to "ok")) }
        get("/ready") { store.read { it.one("SELECT id FROM bootstrap_state") }; call.respond(obj("status" to "ready")) }
        get("/metrics") { call.respondText("jvm_panel_api_up 1\n",ContentType.Text.Plain) }
        route("/api/v1") {
            post("/auth/register") {
                origin(call); val b=call.body(); val email=b.str("email").trim().lowercase(); val password=b.str("password").toCharArray()
                rate("register:$email"); requireValid(email.length<=254 && email.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) && password.size in 12..128 && b.str("name").length in 1..80,"INVALID_ACCOUNT","Укажите имя, email и пароль от 12 до 128 символов")
                val ph=withContext(Dispatchers.IO) { passwordWorkers.withPermit { Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).hash(3,65536,1,password) } }; password.fill('\u0000')
                val userId=store.tx { c ->
                    c.one("SELECT id FROM bootstrap_state WHERE id=1 FOR UPDATE")
                    val invite=c.one("SELECT * FROM invitations WHERE token_hash=? FOR UPDATE",hash(b.str("token"))) ?: throw Problem("INVALID_INVITATION","Приглашение недействительно")
                    requireValid(!invite.bool("used") && !invite.bool("revoked") && invite.long("expires")>now() && (invite.str("email").isEmpty() || invite.str("email")==email),"INVALID_INVITATION","Приглашение недействительно")
                    requireValid(!invite.bool("bootstrap") || c.one("SELECT COUNT(*) n FROM users")!!.long("n")==0L,"INVALID_INVITATION","Приглашение недействительно")
                    requireValid(c.one("SELECT id FROM users WHERE email=?",email)==null,"INVALID_ACCOUNT","Не удалось создать аккаунт")
                    val uid=id(); c.execute("INSERT INTO users(id,email,name,password_hash,admin) VALUES(?,?,?,?,?)",uid,email,b.str("name"),ph,invite.bool("bootstrap"))
                    val ws=if (invite.bool("bootstrap")) {
                        val wid=id(); val n=c.one("SELECT * FROM nodes WHERE id='local'")!!
                        c.execute("INSERT INTO workspaces VALUES(?,?,?,?,?)",wid,"Основная",n.str("cpu").toDouble(),n.long("memory_mib"),n.long("disk_mib")); wid
                    } else invite.str("workspace_id")
                    c.execute("INSERT INTO memberships VALUES(?,?,?)",ws,uid,invite.str("role")); c.execute("UPDATE invitations SET used=TRUE WHERE id=?",invite.str("id"))
                    store.audit(c,uid,"REGISTER",uid,call.requestId()); uid
                }; session(store,call,userId); call.respond(HttpStatusCode.Created,obj("id" to userId))
            }
            post("/auth/login") {
                origin(call); val b=call.body(); val email=b.str("email").trim().lowercase(); rate("login:$email")
                requireValid(b.str("password").length<=128,"INVALID_LOGIN","Неверный email или пароль",401)
                val row=store.read { it.one("SELECT * FROM users WHERE email=?",email) }
                val argon=Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id); val chars=b.str("password").toCharArray()
                val valid=try { withContext(Dispatchers.IO) { passwordWorkers.withPermit { row!=null && argon.verify(row.str("password_hash"),chars) && !row.bool("disabled") } } } finally { chars.fill('\u0000') }
                requireValid(valid,"INVALID_LOGIN","Неверный email или пароль",401)
                call.request.cookies["jvm_session"]?.let { t -> store.tx { it.execute("DELETE FROM sessions WHERE token_hash=?",hash(t)) } }
                session(store,call,row!!.str("id")); store.tx { store.audit(it,row.str("id"),"LOGIN",row.str("id"),call.requestId()) }; call.respond(obj("ok" to true))
            }
            post("/auth/logout") { val u=auth(store,call,true); store.tx { it.execute("DELETE FROM sessions WHERE token_hash=?",hash(call.request.cookies["jvm_session"]!!)); store.audit(it,u.id,"LOGOUT",u.id,call.requestId()) }; call.response.cookies.append(Cookie("jvm_session","",path="/",maxAge=0,secure=env("ALLOW_INSECURE_LOCAL","false")!="true",httpOnly=true,extensions=mapOf("SameSite" to "Strict"))); call.respond(obj("ok" to true)) }
            get("/auth/me") {
                val u=auth(store,call); call.respond(store.read { c -> c.one("SELECT id,email,name,admin FROM users WHERE id=?",u.id)!! + obj("csrf" to u.csrf,"workspaces" to c.rows("SELECT w.*,m.role FROM workspaces w JOIN memberships m ON m.workspace_id=w.id WHERE m.user_id=?",u.id)) })
            }
            get("/runtimes") { auth(store,call); call.respond(obj("items" to store.read { it.rows("SELECT * FROM runtime_images WHERE enabled=TRUE ORDER BY jdk") })) }
            post("/workspaces") {
                val u=auth(store,call,true); admin(u); val b=call.body(); requireValid(b.str("name").length in 1..80,"INVALID_NAME","Укажите имя")
                val wid=id(); store.tx { c -> c.execute("INSERT INTO workspaces VALUES(?,?,?,?,?)",wid,b.str("name"),b.str("cpu").toDouble(),b.long("memory_mib"),b.long("disk_mib")); c.execute("INSERT INTO memberships VALUES(?,?,'OWNER')",wid,u.id); store.audit(c,u.id,"WORKSPACE_CREATE",wid,call.requestId()) }; call.respond(HttpStatusCode.Created,obj("id" to wid))
            }
            get("/workspaces/{ws}/members") {
                val u=auth(store,call); val ws=call.parameters["ws"]!!
                call.respond(store.read { c -> requireValid(role(c,u,ws)=="OWNER","FORBIDDEN","Недостаточно прав",403); obj("items" to c.rows("SELECT u.id,u.email,u.name,u.disabled,m.role FROM users u JOIN memberships m ON u.id=m.user_id WHERE m.workspace_id=?",ws),"invitations" to c.rows("SELECT id,role,email,expires,used,revoked FROM invitations WHERE workspace_id=? ORDER BY expires DESC LIMIT 100",ws)) })
            }
            post("/workspaces/{ws}/invitations") {
                val u=auth(store,call,true); val ws=call.parameters["ws"]!!; val b=call.body(); val t=token(); val iid=id()
                requireValid(b.str("role") in setOf("OWNER","OPERATOR","VIEWER"),"INVALID_ROLE","Недопустимая роль")
                store.tx { c -> requireValid(role(c,u,ws)=="OWNER","FORBIDDEN","Недостаточно прав",403); c.execute("INSERT INTO invitations(id,token_hash,workspace_id,role,email,expires) VALUES(?,?,?,?,?,?)",iid,hash(t),ws,b.str("role"),b.str("email").trim().lowercase().ifEmpty { null },now()+86400000); store.audit(c,u.id,"INVITE",iid,call.requestId()) }
                call.respond(HttpStatusCode.Created,obj("id" to iid,"url" to "${env("PUBLIC_URL")}/register#token=$t"))
            }
            delete("/workspaces/{ws}/invitations/{invite}") {
                val u=auth(store,call,true); store.tx { c -> requireValid(role(c,u,call.parameters["ws"]!!)=="OWNER","FORBIDDEN","Недостаточно прав",403); c.execute("UPDATE invitations SET revoked=TRUE WHERE id=? AND workspace_id=?",call.parameters["invite"],call.parameters["ws"]); store.audit(c,u.id,"INVITE_REVOKE",call.parameters["invite"],call.requestId()) }; call.respond(obj("ok" to true))
            }
            patch("/workspaces/{ws}/members/{user}") {
                val u=auth(store,call,true); val b=call.body(); val ws=call.parameters["ws"]!!; val uid=call.parameters["user"]!!
                store.tx { c ->
                    c.one("SELECT id FROM bootstrap_state WHERE id=1 FOR UPDATE"); requireValid(role(c,u,ws)=="OWNER","FORBIDDEN","Недостаточно прав",403)
                    requireValid(b.str("role") in setOf("OWNER","OPERATOR","VIEWER"),"INVALID_ROLE","Недопустимая роль")
                    val previous=c.one("SELECT * FROM memberships WHERE workspace_id=? AND user_id=?",ws,uid) ?: throw Problem("NOT_FOUND","Участник не найден",404)
                    requireValid(previous.str("role")!="OWNER" || b.str("role")=="OWNER" || c.one("SELECT COUNT(*) n FROM memberships WHERE workspace_id=? AND role='OWNER'",ws)!!.long("n")>1,"LAST_OWNER","Нельзя изменить роль последнего владельца",409)
                    c.execute("UPDATE memberships SET role=? WHERE workspace_id=? AND user_id=?",b.str("role"),ws,uid)
                    c.execute("DELETE p FROM application_permissions p JOIN applications a ON a.id=p.app_id WHERE p.user_id=? AND a.workspace_id=?",uid,ws)
                    for (aid in (b["appIds"] as? JsonArray ?: JsonArray(emptyList()))) {
                        requireValid(c.one("SELECT id FROM applications WHERE id=? AND workspace_id=? AND deleted=FALSE",aid.jsonPrimitive.content,ws)!=null,"NOT_FOUND","Приложение не найдено",404)
                        c.execute("INSERT INTO application_permissions VALUES(?,?)",aid.jsonPrimitive.content,uid)
                    }
                    c.execute("DELETE FROM sessions WHERE user_id=?",uid); store.audit(c,u.id,"MEMBER_UPDATE",uid,call.requestId())
                }; call.respond(obj("ok" to true))
            }
            get("/applications") {
                val u=auth(store,call); val q=call.request.queryParameters; val ws=q["workspaceId"]; val page=(q["page"]?.toIntOrNull() ?: 0).coerceIn(0,10000); val size=(q["size"]?.toIntOrNull() ?: 25).coerceIn(1,100); val search=q["search"].orEmpty().take(80)
                val sort=if (q["sort"]=="name") "a.name,a.id" else "a.id"; val direction=if(q["direction"]=="desc") "DESC" else "ASC"
                call.respond(store.read { c ->
                    val where="a.deleted=FALSE AND (? IS NULL OR a.workspace_id=?) AND a.name LIKE ? AND (?=TRUE OR EXISTS(SELECT 1 FROM memberships m WHERE m.user_id=? AND m.workspace_id=a.workspace_id AND (m.role='OWNER' OR EXISTS(SELECT 1 FROM application_permissions p WHERE p.app_id=a.id AND p.user_id=?))))"
                    val args=arrayOf<Any?>(ws,ws,"%$search%",u.admin,u.id,u.id)
                    obj("items" to c.rows("SELECT a.* FROM applications a WHERE $where ORDER BY $sort $direction LIMIT ? OFFSET ?",*args,size,page*size).map { sanitized(store,it) },"total" to c.one("SELECT COUNT(*) n FROM applications a WHERE $where",*args)!!.long("n"))
                })
            }
            post("/applications") {
                val u=auth(store,call,true); val b=call.body(); val spec=json.decodeFromJsonElement<Spec>(b["spec"]!!); validate(spec); val wid=b.str("workspaceId"); var aid=id()
                val op=store.tx { c ->
                    requireValid(role(c,u,wid)=="OWNER","FORBIDDEN","Недостаточно прав",403); budget(c,spec,wid)
                    val key=call.request.headers["Idempotency-Key"]
                    key?.let { c.one("SELECT * FROM operations WHERE user_id=? AND idempotency_key=?",u.id,it) }?.let { previous ->
                        val existing=c.one("SELECT * FROM applications WHERE id=?",previous.str("app_id"))
                        requireValid(previous.str("kind")=="CREATE" && existing!=null && existing.str("workspace_id")==wid && store.plain(json.decodeFromString<Spec>(existing.str("spec"))).copy(image="")==spec.copy(image=""),"IDEMPOTENCY_CONFLICT","Ключ использован для другого приложения",409)
                        aid=previous.str("app_id"); return@tx previous.str("id")
                    }
                    val pinned=spec.copy(image=c.one("SELECT image FROM runtime_images WHERE jdk=?",spec.jdk)!!.str("image"))
                    val stored=json.encodeToString(store.stored(pinned))
                    c.execute("INSERT INTO applications(id,workspace_id,node_id,name,cpu,memory_mib,disk_mib,spec) VALUES(?,?,'local',?,?,?,?,?)",aid,wid,spec.name,spec.cpu,spec.memoryMiB,spec.diskMiB,stored)
                    ports(c,spec,aid); c.execute("INSERT INTO application_revisions VALUES(?,1,?,?)",aid,stored,now())
                    c.execute("INSERT INTO storage_allocations VALUES(?,?,?,FALSE)",aid,wid,spec.diskMiB)
                    val operation=enqueue(c,u,"CREATE",aid,obj(),1,call.request.headers["Idempotency-Key"])
                    c.execute("UPDATE applications SET active_operation=? WHERE id=?",operation,aid); store.audit(c,u.id,"APP_CREATE",aid,call.requestId()); operation
                }; call.respond(HttpStatusCode.Accepted,obj("id" to aid,"operationId" to op))
            }
            get("/applications/{app}") { val u=auth(store,call); call.respond(store.read { sanitized(store,app(it,u,call.parameters["app"]!!)) }) }
            patch("/applications/{app}") {
                val u=auth(store,call,true); val aid=call.parameters["app"]!!; val b=call.body(); val spec=json.decodeFromJsonElement<Spec>(b["spec"]!!); validate(spec)
                store.tx { c ->
                    c.one("SELECT id FROM nodes WHERE id='local' FOR UPDATE"); val a=app(c,u,aid,true,true)
                    requireValid(a.long("revision")==b.long("revision"),"REVISION_CONFLICT","Конфигурация изменена. Обновите страницу",409)
                    requireValid(a.str("active_operation").isEmpty(),"BUSY","Дождитесь текущей операции",409)
                    val reserve=spec.copy(cpu=maxOf(spec.cpu,a.str("cpu").toDouble()),memoryMiB=maxOf(spec.memoryMiB,a.long("memory_mib").toInt()),diskMiB=maxOf(spec.diskMiB,a.long("disk_mib").toInt()))
                    budget(c,reserve,a.str("workspace_id"),aid); val pinned=spec.copy(image=c.one("SELECT image FROM runtime_images WHERE jdk=?",spec.jdk)!!.str("image")); val stored=json.encodeToString(store.stored(pinned,json.decodeFromString(a.str("spec")))); val revision=a.long("revision")+1
                    ports(c,spec,aid); c.execute("UPDATE applications SET name=?,cpu=?,memory_mib=?,disk_mib=?,spec=?,revision=? WHERE id=?",spec.name,reserve.cpu,reserve.memoryMiB,reserve.diskMiB,stored,revision,aid)
                    c.execute("UPDATE storage_allocations SET disk_mib=? WHERE app_id=?",reserve.diskMiB,aid)
                    c.execute("INSERT INTO application_revisions VALUES(?,?,?,?)",aid,revision,stored,now()); store.audit(c,u.id,"APP_UPDATE",aid,call.requestId())
                }; call.respond(obj("ok" to true,"requiresRestart" to true))
            }
            get("/applications/{app}/revisions") { val u=auth(store,call); val aid=call.parameters["app"]!!; call.respond(store.read { c -> app(c,u,aid,false,true); obj("items" to c.rows("SELECT * FROM application_revisions WHERE app_id=? ORDER BY revision DESC LIMIT 50",aid).map { sanitized(store,it) }) }) }
            post("/applications/{app}/actions/{action}") {
                val u=auth(store,call,true); val aid=call.parameters["app"]!!; val action=call.parameters["action"]!!.uppercase(); requireValid(action in setOf("START","STOP","RESTART","APPLY"),"INVALID_ACTION","Неизвестное действие")
                val operation=store.tx { c ->
                    c.one("SELECT id FROM nodes WHERE id='local' FOR UPDATE"); val a=app(c,u,aid,true)
                    val key=call.request.headers["Idempotency-Key"]
                    val existing=key?.let { c.one("SELECT * FROM operations WHERE user_id=? AND idempotency_key=?",u.id,it) }
                    if (existing!=null) { requireValid(existing.str("app_id")==aid && existing.str("kind")==action,"IDEMPOTENCY_CONFLICT","Ключ использован для другого действия",409); return@tx existing.str("id") }
                    requireValid(a.str("active_operation").isEmpty(),"BUSY","Дождитесь текущей операции",409)
                    val n=c.one("SELECT * FROM nodes WHERE id='local'")!!; requireValid(n.bool("ready") && now()-n.long("heartbeat")<15000,"NODE_OFFLINE","Узел недоступен",503)
                    val g=a.long("generation")+1; val op=enqueue(c,u,action,aid,obj(),g,key)
                    c.execute("UPDATE applications SET desired=?,observed=?,generation=?,active_operation=? WHERE id=?",if(action=="STOP") "STOPPED" else "RUNNING",if(action=="STOP") "STOPPING" else "STARTING",g,op,aid)
                    store.audit(c,u.id,action,aid,call.requestId()); op
                }; call.respond(HttpStatusCode.Accepted,obj("operationId" to operation))
            }
            delete("/applications/{app}") {
                val u=auth(store,call,true); val aid=call.parameters["app"]!!; val keep=call.request.queryParameters["keepFiles"] != "false"
                val operation=store.tx { c -> c.one("SELECT id FROM nodes WHERE id='local' FOR UPDATE"); val a=app(c,u,aid,true,true); requireValid(a.str("active_operation").isEmpty(),"BUSY","Дождитесь текущей операции",409); val g=a.long("generation")+1; val op=enqueue(c,u,"DELETE",aid,obj("keepFiles" to keep),g,null); c.execute("UPDATE applications SET observed='DELETING',desired='STOPPED',generation=?,active_operation=? WHERE id=?",g,op,aid); store.audit(c,u.id,"APP_DELETE",aid,call.requestId()); op }; call.respond(HttpStatusCode.Accepted,obj("operationId" to operation))
            }
            get("/operations/{op}") {
                val u=auth(store,call); call.respond(store.read { c -> val o=c.one("SELECT id,app_id,user_id,kind,status,result,created FROM operations WHERE id=?",call.parameters["op"]) ?: throw Problem("NOT_FOUND","Операция не найдена",404); if(o.str("app_id").isNotEmpty()) app(c,u,o.str("app_id")) else requireValid(u.admin || o.str("user_id")==u.id,"NOT_FOUND","Операция не найдена",404); o })
            }
            get("/applications/{app}/metrics") { val u=auth(store,call); val aid=call.parameters["app"]!!; call.respond(snapshot(store,u,aid)) }
            get("/applications/{app}/history") {
                val u=auth(store,call); val aid=call.parameters["app"]!!; store.read { app(it,u,aid) }
                val hours=(call.request.queryParameters["hours"]?.toIntOrNull() ?: 1); requireValid(hours in setOf(1,6,24),"INVALID_RANGE","Доступны интервалы 1, 6 и 24 часа")
                val end=now()/1000; val start=end-hours*3600; val step=maxOf(15,hours*3600/240)
                val points=withContext(Dispatchers.IO) {
                    val client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()
                    listOf("cpu" to "jvm_app_cpu_cores","memory" to "jvm_app_memory_percent","disk" to "jvm_app_disk_bytes").associate { (name,metric) ->
                        val query=URLEncoder.encode("$metric{app=\"$aid\"}",Charsets.UTF_8)
                        val request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:9090/api/v1/query_range?query=$query&start=$start&end=$end&step=$step")).timeout(java.time.Duration.ofSeconds(5)).GET().build()
                        val response=client.send(request,HttpResponse.BodyHandlers.ofString()); requireValid(response.statusCode()==200,"METRICS_UNAVAILABLE","Prometheus недоступен",503)
                        val data=json.parseToJsonElement(response.body()).jsonObject; name to (data["data"]?.jsonObject?.get("result") ?: JsonArray(emptyList()))
                    }
                }; call.respond(obj("start" to start,"end" to end,"step" to step,"series" to JsonObject(points)))
            }
            get("/applications/{app}/logs") { val u=auth(store,call); val aid=call.parameters["app"]!!; store.read { app(it,u,aid) }; call.respond(executeAgent(store,u,"LOGS",aid,obj())) }
            post("/applications/{app}/files") {
                val b=call.body(); val write=b.str("action") !in setOf("list","read","download","trashList","uploadStatus")
                val u=auth(store,call,true); val aid=call.parameters["app"]!!; val a=store.read { app(it,u,aid,write) }
                requireValid(b.str("action") in setOf("list","read","write","mkdir","rename","copy","move","trash","trashList","restore","uploadStart","uploadChunk","uploadFinish","uploadStatus","uploadCancel","download","archive","extract"),"INVALID_ACTION","Неизвестная файловая операция")
                if(b.str("action") in setOf("write","uploadFinish","trash","rename","move") && b.str("path")==json.decodeFromString<Spec>(a.str("spec")).jar) requireValid(a.str("observed")=="STOPPED" || a.str("observed")=="FAILED","JAR_RUNNING","Остановите приложение перед заменой JAR",409)
                val result=executeAgent(store,u,"FILE",aid,b); if(write) store.tx { store.audit(it,u.id,"FILE_${b.str("action").uppercase()}",aid,call.requestId()) }; call.respond(result)
            }
            get("/nodes/local") { val u=auth(store,call); admin(u); call.respond(store.read { it.one("SELECT * FROM nodes WHERE id='local'")!! }) }
            post("/nodes/local/firewall") { val u=auth(store,call,true); admin(u); val b=call.body(); val result=executeAgent(store,u,"FIREWALL",null,b); store.tx { store.audit(it,u.id,"FIREWALL_${b.str("action")}","local",call.requestId()) }; call.respond(result) }
            get("/audit") { val u=auth(store,call); admin(u); call.respond(obj("items" to store.read { it.rows("SELECT * FROM audit_events ORDER BY created DESC LIMIT 200") })) }
            patch("/users/{user}") {
                val u=auth(store,call,true); admin(u); val b=call.body(); val uid=call.parameters["user"]!!
                store.tx { c -> c.one("SELECT id FROM bootstrap_state WHERE id=1 FOR UPDATE"); val target=c.one("SELECT * FROM users WHERE id=?",uid) ?: throw Problem("NOT_FOUND","Аккаунт не найден",404); requireValid(!target.bool("admin") || !b.bool("disabled") || c.one("SELECT COUNT(*) n FROM users WHERE admin=TRUE AND disabled=FALSE AND id<>?",uid)!!.long("n")>0,"LAST_ADMIN","Нельзя отключить последнего администратора",409); c.execute("UPDATE users SET disabled=? WHERE id=?",b.bool("disabled"),uid); c.execute("DELETE FROM sessions WHERE user_id=?",uid); store.audit(c,u.id,"USER_DISABLE",uid,call.requestId()) }; call.respond(obj("ok" to true))
            }
        }
        post("/internal/v1/heartbeat") {
            internal(call); val b=call.body(); store.tx { c ->
                val snapshot=b["snapshot"] as? JsonObject ?: obj(); c.execute("UPDATE nodes SET heartbeat=?,ready=?,snapshot=? WHERE id='local'",now(),b.bool("ready"),snapshot.toString())
                if(b.str("operationId").isNotEmpty()) c.execute("UPDATE operations SET lease_until=? WHERE id=? AND node_id='local' AND status='RUNNING'",now()+120000,b.str("operationId"))
                for((aid,value) in snapshot) { val s=value.jsonObject; c.execute("UPDATE applications SET observed=?,applied_revision=? WHERE id=? AND generation=? AND active_operation IS NULL AND deleted=FALSE",s.str("state"),s.long("appliedRevision"),aid,s.long("generation")) }
            }; call.respond(obj("ok" to true))
        }
        post("/internal/v1/intents") {
            internal(call); call.respond(obj("items" to store.read { it.rows("SELECT id,desired,generation,active_operation FROM applications WHERE node_id='local' AND deleted=FALSE") }))
        }
        post("/internal/v1/poll") {
            internal(call); val b=call.body()
            val command=store.tx { c ->
                val snapshot=b["snapshot"] as? JsonObject ?: obj(); c.execute("UPDATE nodes SET heartbeat=?,ready=?,snapshot=? WHERE id='local'",now(),b.bool("ready"),snapshot.toString())
                for ((aid,value) in snapshot) { val s=value.jsonObject; c.execute("UPDATE applications SET observed=?,applied_revision=? WHERE id=? AND generation=? AND active_operation IS NULL AND deleted=FALSE",s.str("state"),s.long("appliedRevision"),aid,s.long("generation")) }
                val o=c.one("SELECT * FROM operations WHERE node_id='local' AND (status='QUEUED' OR (status='RUNNING' AND lease_until<?)) ORDER BY created LIMIT 1 FOR UPDATE",now()) ?: return@tx obj()
                if(o.long("attempts")>=5) { c.execute("UPDATE operations SET status='FAILED',result=? WHERE id=?",obj("code" to "LEASE_EXPIRED","message" to "Узел не подтвердил результат").toString(),o.str("id")); c.execute("UPDATE applications SET active_operation=NULL,observed='FAILED' WHERE active_operation=?",o.str("id")); return@tx obj() }
                val uid=o.str("user_id"); val user=c.one("SELECT * FROM users WHERE id=? AND disabled=FALSE",uid)
                val a=o.str("app_id").takeIf { it.isNotEmpty() }?.let { c.one("SELECT * FROM applications WHERE id=?",it) }
                val authorized=try { if(user==null) false else { val au=User(uid,user.bool("admin"),""); if(a!=null) app(c,au,a.str("id"),o.str("kind") !in setOf("LOGS") && (o.str("kind")!="FILE" || json.parseToJsonElement(o.str("payload")).jsonObject.str("action") !in setOf("list","read","download","trashList","uploadStatus"))) else admin(au); true } } catch (_: Problem) { false }
                if(!authorized || (a!=null && o.long("generation")>0 && a.long("generation")!=o.long("generation"))) { c.execute("UPDATE operations SET status='FAILED',result=? WHERE id=?",obj("code" to "ACCESS_REVOKED","message" to "Доступ отозван или команда устарела").toString(),o.str("id")); c.execute("UPDATE applications SET active_operation=NULL,observed='FAILED' WHERE active_operation=?",o.str("id")); return@tx obj() }
                c.execute("UPDATE operations SET status='RUNNING',lease_until=?,attempts=attempts+1 WHERE id=?",now()+120000,o.str("id"))
                val spec=a?.let { store.plain(json.decodeFromString<Spec>(it.str("spec"))) }
                obj("id" to o.str("id"),"kind" to o.str("kind"),"appId" to o.str("app_id"),"generation" to o.long("generation"),"revision" to (a?.long("revision") ?: 0),"spec" to spec?.let { json.encodeToJsonElement(it) },"image" to spec?.image,"payload" to json.parseToJsonElement(o.str("payload")))
            }; call.respond(command)
        }
        post("/internal/v1/results/{op}") {
            internal(call); val b=call.body(); store.tx { c ->
                val o=c.one("SELECT * FROM operations WHERE id=? AND node_id='local' FOR UPDATE",call.parameters["op"]) ?: throw Problem("NOT_FOUND","Unknown operation",404)
                if(o.str("status") in setOf("SUCCEEDED","FAILED")) return@tx
                c.execute("UPDATE operations SET status=?,result=?,lease_until=0 WHERE id=?",if(b.bool("ok")) "SUCCEEDED" else "FAILED",b.toString(),o.str("id"))
                if(o.long("generation")>0) {
                    c.execute("UPDATE applications SET observed=?,applied_revision=?,active_operation=NULL WHERE id=? AND generation=? AND active_operation=?",b.str("state",if(b.bool("ok")) "STOPPED" else "FAILED"),b.long("appliedRevision"),o.str("app_id"),o.long("generation"),o.str("id"))
                    if(b.bool("ok") && o.str("kind") in setOf("START","RESTART","APPLY")) {
                        val a=c.one("SELECT * FROM applications WHERE id=? AND generation=?",o.str("app_id"),o.long("generation"))
                        if(a!=null) { val spec=json.decodeFromString<Spec>(a.str("spec")); c.execute("UPDATE applications SET cpu=?,memory_mib=?,disk_mib=? WHERE id=?",spec.cpu,spec.memoryMiB,spec.diskMiB,a.str("id")); c.execute("UPDATE storage_allocations SET disk_mib=? WHERE app_id=?",spec.diskMiB,a.str("id")); c.execute("DELETE FROM port_allocations WHERE app_id=?",a.str("id")); ports(c,spec,a.str("id")) }
                    }
                    if(o.str("kind")=="DELETE" && b.bool("ok")) {
                        c.execute("UPDATE applications SET deleted=TRUE WHERE id=?",o.str("app_id")); c.execute("DELETE FROM port_allocations WHERE app_id=?",o.str("app_id"))
                        if(json.parseToJsonElement(o.str("payload")).jsonObject.bool("keepFiles",true)) c.execute("UPDATE storage_allocations SET retained=TRUE WHERE app_id=?",o.str("app_id")) else c.execute("DELETE FROM storage_allocations WHERE app_id=?",o.str("app_id"))
                    }
                }
            }; call.respond(obj("ok" to true))
        }
        webSocket("/ws/applications/{app}") {
            origin(call); val u=auth(store,call); val aid=call.parameters["app"]!!; store.read { app(it,u,aid) }
            val count=sockets.computeIfAbsent(u.id) { AtomicInteger() }; requireValid(count.incrementAndGet()<=5,"STREAM_LIMIT","Не более пяти потоков",429)
            try { var sequence=0L; while(true) { val current=auth(store,call); send(Frame.Text((snapshot(store,current,aid)+obj("eventId" to ++sequence,"type" to "snapshot")).toString())); delay(2000) } } finally { count.decrementAndGet() }
        }
    }
}
fun snapshot(store: Store, u: User, aid: String): JsonObject = store.read { c ->
    val a=app(c,u,aid); val n=c.one("SELECT * FROM nodes WHERE id=?",a.str("node_id"))!!; val fresh=now()-n.long("heartbeat")<15000
    val snapshot=json.parseToJsonElement(n.str("snapshot")).jsonObject[aid] as? JsonObject ?: obj()
    snapshot+obj("nodeOnline" to fresh,"sampledAt" to n.long("heartbeat"),"observed" to a.str("observed"),"activeOperation" to a["active_operation"],"revision" to a.long("revision"),"appliedRevision" to a.long("applied_revision"))
}
