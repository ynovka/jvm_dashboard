package panel.agent

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import panel.shared.*
import java.io.File
import java.net.URI
import java.net.Socket
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

fun env(name: String, fallback: String? = null) = System.getenv(name) ?: fallback ?: error("Missing $name")
val processes=Executors.newCachedThreadPool()
fun run(args: List<String>, input: String? = null, timeout: Long = 30, allowFailure: Boolean = false): String {
    val p=ProcessBuilder(args).redirectErrorStream(true).start()
    val output=processes.submit<String> { p.inputStream.use { String(it.readNBytes(4*1024*1024),Charsets.UTF_8) } }
    p.outputStream.use { if(input!=null) it.write(input.toByteArray()) }
    if(!p.waitFor(timeout,TimeUnit.SECONDS)) { p.destroyForcibly(); throw Problem("COMMAND_TIMEOUT","Узел: операция превысила время ожидания",504) }
    val text=output.get(5,TimeUnit.SECONDS)
    if(p.exitValue()!=0 && !allowFailure) throw Problem("NODE_COMMAND","${args.first()}: ${text.take(1000)}")
    return text
}
@Serializable data class Runtime(val project: Int, val spec: Spec, val image: String, val generation: Long, val revision: Long,
    val appliedRevision: Long = 0, val desired: String = "STOPPED", val boot: String = "", val restarts: Int = 0, val lastRestart: Long = 0,
    val state: String = "STOPPED", val operationId: String = "", val deleted: Boolean = false)
@Serializable data class Journal(val apps: Map<String,Runtime> = emptyMap(), val results: Map<String,JsonObject> = emptyMap())

class Agent {
    private val statePath=Paths.get(env("AGENT_STATE","/opt/jvm_dashboard/data/agent/journal"))
    private val root=Paths.get(env("APP_STORAGE","/opt/jvm_dashboard/data/apps")).toAbsolutePath()
    private val helpers=env("AGENT_HELPERS","/opt/jvm_dashboard/current/deployment/helpers")
    private val secrets=Secrets(env("ENCRYPTION_KEY"))
    private val boot=Files.readString(Paths.get("/proc/sys/kernel/random/boot_id")).trim()
    @Volatile var activeOperation=""
    @Volatile var ready=false
    @Volatile var snapshot=obj()
    @Volatile private var synchronizedBoot=false
    private var journal=if(Files.exists(statePath)) json.decodeFromString<Journal>(secrets.decrypt(Files.readString(statePath))) else Journal()
    private val http=createHttp()
    private fun createHttp(): HttpClient {
        val password=env("NODE_KEYSTORE_PASSWORD").toCharArray()
        val key=KeyStore.getInstance("PKCS12").apply { File(env("NODE_KEYSTORE")).inputStream().use { load(it,password) } }
        val trust=KeyStore.getInstance("PKCS12").apply { File(env("NODE_TRUSTSTORE")).inputStream().use { load(it,password) } }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(key,password) }
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return HttpClient.newBuilder().sslContext(SSLContext.getInstance("TLS").apply { init(km.keyManagers,tm.trustManagers,null) }).connectTimeout(Duration.ofSeconds(5)).build()
    }
    fun post(path: String, body: JsonObject): JsonObject {
        val request=HttpRequest.newBuilder(URI.create(env("AGENT_API_URL","https://localhost:8444")+path))
            .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Authorization","Bearer ${env("AGENT_TOKEN")}")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response=http.send(request,HttpResponse.BodyHandlers.ofString())
        requireValid(response.statusCode() in 200..299,"API_UNAVAILABLE","API returned ${response.statusCode()}",503)
        return json.parseToJsonElement(response.body()).jsonObject
    }
    @Synchronized private fun save() {
        Files.createDirectories(statePath.parent)
        val tmp=statePath.resolveSibling("journal.tmp")
        Files.writeString(tmp,secrets.encrypt(json.encodeToString(journal)),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)
        Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"))
        FileChannel.open(tmp,StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(tmp,statePath,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        FileChannel.open(statePath.parent,StandardOpenOption.READ).use { it.force(true) }
    }
    @Synchronized private fun set(aid: String, runtime: Runtime) { journal=journal.copy(apps=journal.apps+(aid to runtime)); save() }
    private fun directory(aid: String): Path {
        requireValid(aid.matches(Regex("[a-f0-9-]{36}")),"INVALID_ID","Invalid application id")
        return root.resolve(aid)
    }
    private fun container(aid: String)="jvm-$aid"
    private fun docker(vararg args: String, allowFailure: Boolean=false, timeout: Long=30)=run(listOf("docker")+args,timeout=timeout,allowFailure=allowFailure)
    private fun firewall(action: JsonObject): JsonObject = json.parseToJsonElement(run(listOf("python3","$helpers/firewall.py"),action.toString())).jsonObject.also {
        if(!it.bool("ok",true)) throw Problem(it.str("code","FIREWALL_ERROR"),it.str("message","Firewall failed"))
    }
    fun preflight() {
        require(Files.isDirectory(root) && !Files.isSymbolicLink(root)) { "Storage missing" }
        require(run(listOf("findmnt","-n","-o","FSTYPE,OPTIONS","--target",root.toString())).let { it.contains("xfs") && (it.contains("prjquota") || it.contains("pquota")) }) { "XFS project quotas required" }
        require(run(listOf("xfs_quota","-x","-c","state -p",root.toString())).contains("Enforcement: ON")) { "Project quota enforcement must be enabled" }
        require(docker("info","--format","{{.CgroupVersion}} {{.OSType}}").trim()=="2 linux") { "cgroups v2 Linux required" }
        require(Files.isReadable(Paths.get("/sys/fs/cgroup/cgroup.controllers")) && Files.readString(Paths.get("/sys/fs/cgroup/cgroup.controllers")).let { it.contains("cpu") && it.contains("memory") })
        firewall(obj("action" to "reconcile","apps" to JsonObject(journal.apps.filterValues { !it.deleted }.mapValues { (_,r) -> json.encodeToJsonElement(r.spec.ports) })))
        ready=true
    }
    private fun quota(aid: String, runtime: Runtime) {
        val dir=directory(aid)
        if(!Files.exists(dir)) Files.createDirectory(dir)
        require(!Files.isSymbolicLink(dir))
        // Project ids and per-app host UIDs are journalled before provisioning.
        run(listOf("xfs_quota","-x","-c","project -s -p $dir ${runtime.project}",root.toString()))
        val report=run(listOf("xfs_quota","-x","-c","quota -p -b -N ${runtime.project}",root.toString()))
        val used=Regex("(?:^|\\s)#?${runtime.project}\\s+(\\d+)").find(report)?.groupValues?.get(1)?.toLong() ?: throw Problem("QUOTA_REPORT","Cannot read XFS quota usage")
        requireValid(used<=runtime.spec.diskMiB*1024L,"QUOTA_IN_USE","Новая квота меньше занятого объёма",409)
        run(listOf("xfs_quota","-x","-c","limit -p bsoft=${runtime.spec.diskMiB}m bhard=${runtime.spec.diskMiB}m isoft=100000 ihard=100000 ${runtime.project}",root.toString()))
        val uid=2000000+runtime.project
        run(listOf("chown","$uid:$uid",dir.toString())); run(listOf("chmod","0700",dir.toString()))
        for (sub in listOf(".panel-tmp",".panel-upload",".panel-trash")) {
            val p=dir.resolve(sub); if(!Files.exists(p)) Files.createDirectory(p)
            require(!Files.isSymbolicLink(p)); run(listOf("chown","$uid:$uid",p.toString())); run(listOf("chmod","0700",p.toString()))
        }
    }
    private fun inspect(aid: String): JsonObject? = try { json.parseToJsonElement(docker("inspect",container(aid))).jsonArray.first().jsonObject } catch (_: Problem) { null }
    private fun stop(aid: String, runtime: Runtime) { if(inspect(aid)!=null) docker("stop","--time",runtime.spec.stopSeconds.toString(),container(aid),timeout=runtime.spec.stopSeconds+10L) }
    private fun file(aid: String, payload: JsonObject): JsonObject {
        val runtime=journal.apps[aid] ?: throw Problem("NOT_FOUND","Volume не найден",404)
        val action=payload.str("action")
        if(action in setOf("write","uploadFinish","trash","rename","move","copy","archive","extract","restore")) {
            val path=if(action=="uploadFinish") file(aid,obj("action" to "uploadStatus","id" to payload.str("id"))).str("path") else payload.str("path")
            val jar=runtime.spec.jar
            val touchesJar=path==jar || (path.isNotEmpty() && jar.startsWith("$path/")) || payload.str("target")==jar || action in setOf("extract","restore")
            requireValid(!touchesJar || inspect(aid)?.get("State")?.jsonObject?.bool("Running")!=true,"JAR_RUNNING","Остановите приложение перед заменой JAR",409)
        }
        val uid=(2000000+runtime.project).toString()
        // Separate UID, no network namespace, no Docker socket, and only this volume writable.
        val command=listOf("bwrap","--unshare-pid","--unshare-net","--unshare-uts","--unshare-ipc","--unshare-cgroup","--die-with-parent","--new-session","--cap-drop","ALL","--cap-add","CAP_SETUID","--cap-add","CAP_SETGID","--cap-add","CAP_SETPCAP",
            "--ro-bind","/usr","/usr","--ro-bind","/lib","/lib","--ro-bind","/lib64","/lib64","--proc","/proc","--dev","/dev",
            "--bind",directory(aid).toString(),"/data","--ro-bind","$helpers/files.py","/worker.py","--chdir","/","setpriv","--reuid",uid,"--regid",uid,"--clear-groups","--bounding-set=-all","--inh-caps=-all","--ambient-caps=-all","--no-new-privs","python3","/worker.py")
        val result=json.parseToJsonElement(run(command,payload.toString(),60)).jsonObject
        if(!result.bool("ok",true)) throw Problem(result.str("code","FILE_ERROR"),result.str("message","Файловая операция не выполнена"),result.long("status",400).toInt())
        return result
    }
    private fun start(aid: String, runtime: Runtime) {
        validate(runtime.spec)
        requireValid(runtime.image.matches(Regex("eclipse-temurin@sha256:[a-f0-9]{64}")),"INVALID_IMAGE","Image is not pinned")
        // Stop before root quota/setup operations; an active JVM must not race setup with symlinks.
        stop(aid,runtime)
        quota(aid,runtime); file(aid,obj("action" to "read","path" to runtime.spec.jar,"metadataOnly" to true))
        val network="jvm-net-$aid"
        if(docker("network","ls","--filter","name=^$network$","--format","{{.Name}}").trim().isEmpty())
            docker("network","create","--label","panel.app=$aid","--opt","com.docker.network.bridge.enable_icc=false",network)
        val networkInfo=json.parseToJsonElement(docker("network","inspect",network)).jsonArray.first().jsonObject
        val subnet=networkInfo["IPAM"]!!.jsonObject["Config"]!!.jsonArray.first().jsonObject.str("Subnet")
        firewall(obj("action" to "app","id" to aid,"subnet" to subnet,"ports" to json.encodeToJsonElement(runtime.spec.ports)))
        val current=inspect(aid)
        if(current!=null) { stop(aid,runtime); docker("rm",container(aid)) }
        for(p in runtime.spec.ports) {
            if(p.protocol=="tcp") try { java.net.ServerSocket().use { it.reuseAddress=false; it.bind(InetSocketAddress("0.0.0.0",p.host)) } } catch (_: Exception) { throw Problem("PORT_CONFLICT","Порт ${p.host}/tcp занят",409) }
            else try { java.net.DatagramSocket(null).use { it.reuseAddress=false; it.bind(InetSocketAddress("0.0.0.0",p.host)) } } catch (_: Exception) { throw Problem("PORT_CONFLICT","Порт ${p.host}/udp занят",409) }
        }
        docker("pull",runtime.image,timeout=90)
        val envFile=Files.createTempFile(statePath.parent,"env-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            requireValid(runtime.spec.env.all { !it.value.contains('\n') && !it.value.contains('\r') },"INVALID_ENV","Многострочные ENV пока не поддерживаются")
            Files.writeString(envFile,runtime.spec.env.joinToString("\n") { "${it.key}=${it.value}" })
            val args=mutableListOf("docker","create","--name",container(aid),"--label","panel.app=$aid","--label","panel.generation=${runtime.generation}",
                "--label","panel.revision=${runtime.revision}","--restart","no","--user","${2000000+runtime.project}:${2000000+runtime.project}","--cpus",runtime.spec.cpu.toString(),
                "--memory","${runtime.spec.memoryMiB}m","--memory-swap","${runtime.spec.memoryMiB}m","--pids-limit","256","--ulimit","nofile=4096:4096",
                "--cap-drop","ALL","--security-opt","no-new-privileges","--read-only","--network",network,"--workdir","/data",
                "--mount","type=bind,src=${directory(aid)},dst=/data","--tmpfs","/tmp:rw,noexec,nosuid,size=16m","--log-driver","json-file","--log-opt","max-size=10m","--log-opt","max-file=3",
                "--env-file",envFile.toString(),"--entrypoint","java")
            for(p in runtime.spec.ports) args+=listOf("--publish","${if(p.public) "0.0.0.0" else "127.0.0.1"}:${p.host}:${p.container}/${p.protocol}")
            args+=runtime.image; args+=javaCommand(runtime.spec).drop(1)
            run(args,timeout=30); docker("start",container(aid))
            Thread.sleep(1000)
            val state=inspect(aid)!!["State"]!!.jsonObject
            requireValid(state.bool("Running"),"START_FAILED",if(state.bool("OOMKilled")) "Превышен лимит RAM" else "JVM завершилась с кодом ${state.long("ExitCode")}. Откройте консоль",409)
        } finally { Files.deleteIfExists(envFile) }
    }
    @Synchronized fun command(c: JsonObject): JsonObject {
        val op=c.str("id"); journal.results[op]?.let { return it }
        val kind=c.str("kind"); val aid=c.str("appId"); activeOperation=op
        val result=try {
            when(kind) {
                "FILE" -> file(aid,c["payload"]!!.jsonObject)+obj("ok" to true)
                "LOGS" -> {
                    requireValid(journal.apps.containsKey(aid),"NOT_FOUND","Приложение не найдено",404)
                    obj("ok" to true,"text" to docker("logs","--timestamps","--tail","300",container(aid),allowFailure=true).takeLast(131072).replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"),"").filter { it=='\n' || it=='\t' || it>=' ' })
                }
                "FIREWALL" -> firewall(c["payload"]!!.jsonObject)
                else -> {
                    val spec=json.decodeFromJsonElement<Spec>(c["spec"]!!); validate(spec); val previous=journal.apps[aid]
                    val generation=c.long("generation")
                    requireValid(previous==null || generation>=previous.generation,"STALE_GENERATION","Устаревшая команда",409)
                    var r=Runtime(previous?.project ?: ((journal.apps.values.maxOfOrNull { it.project } ?: 1000)+1),spec,c.str("image"),generation,c.long("revision"),previous?.appliedRevision ?: 0,if(kind in setOf("START","RESTART","APPLY")) "RUNNING" else "STOPPED",boot,operationId=op)
                    // Persist intent before side effects. A repeated delivery can safely reconcile Docker labels.
                    set(aid,r)
                    when(kind) {
                        "CREATE" -> quota(aid,r)
                        "START","RESTART","APPLY" -> {
                            val labels=inspect(aid)?.get("Config")?.jsonObject?.get("Labels")?.jsonObject
                            if(labels?.str("panel.generation")!=generation.toString() || inspect(aid)?.get("State")?.jsonObject?.bool("Running")!=true) start(aid,r)
                            r=r.copy(appliedRevision=r.revision,state="RUNNING")
                        }
                        "STOP" -> { stop(aid,r); r=r.copy(state="STOPPED") }
                        "DELETE" -> {
                            stop(aid,r); if(inspect(aid)!=null) docker("rm",container(aid)); docker("network","rm","jvm-net-$aid",allowFailure=true)
                            firewall(obj("action" to "removeApp","id" to aid)); if(!c["payload"]!!.jsonObject.bool("keepFiles",true)) file(aid,obj("action" to "purge"))
                            r=r.copy(deleted=true,state="STOPPED")
                        }
                        else -> throw Problem("UNKNOWN_COMMAND","Unknown command")
                    }; set(aid,r); obj("ok" to true,"state" to r.state,"appliedRevision" to r.appliedRevision)
                }
            }
        } catch(p: Problem) { obj("ok" to false,"code" to p.code,"message" to p.message,"status" to p.status,"state" to "FAILED") }
          catch(_: Exception) { obj("ok" to false,"code" to "AGENT_ERROR","message" to "Ошибка агента; проверьте журнал службы","state" to "FAILED") }
        if(kind !in setOf("LOGS") && result.toString().length<16384) { journal=journal.copy(results=(journal.results+(op to result)).entries.toList().takeLast(256).associate { it.toPair() }); save() }
        activeOperation=""; return result
    }
    @Synchronized fun observe(): JsonObject {
        if(!synchronizedBoot) {
            val intents=post("/internal/v1/intents",obj())["items"]!!.jsonArray
            for(value in intents) { val a=value.jsonObject; val r=journal.apps[a.str("id")] ?: continue
                // Respect durable manual Stop before attempting boot autostart.
                if(a.str("desired")=="STOPPED") set(a.str("id"),r.copy(desired="STOPPED"))
                if(a.str("active_operation").isNotEmpty() && r.boot!=boot) set(a.str("id"),journal.apps[a.str("id")]!!.copy(boot=boot))
            }; synchronizedBoot=true
        }
        val rows=mutableMapOf<String,JsonElement>()
        for((aid,initial) in journal.apps.toMap().filterValues { !it.deleted }) {
            var r=initial; var inspected=inspect(aid)
            if(r.boot!=boot) {
                if(r.desired=="RUNNING" && r.spec.autostart) {
                    try { start(aid,r); r=r.copy(state="RUNNING",boot=boot,appliedRevision=r.revision) } catch(_: Exception) { r=r.copy(state="FAILED",boot=boot) }
                } else { stop(aid,r); r=r.copy(state="STOPPED",desired="STOPPED",boot=boot) }
                set(aid,r); inspected=inspect(aid)
            }
            val state=inspected?.get("State")?.jsonObject
            if(r.desired=="RUNNING" && state?.bool("Running")!=true && activeOperation.isEmpty() && r.spec.crashRestart && r.restarts<3 && System.currentTimeMillis()-r.lastRestart>30000L*(r.restarts+1)) {
                r=r.copy(restarts=r.restarts+1,lastRestart=System.currentTimeMillis()); set(aid,r)
                try { start(aid,r); r=r.copy(state="RUNNING",appliedRevision=r.revision); set(aid,r); inspected=inspect(aid) } catch(_: Exception) { r=r.copy(state="FAILED"); set(aid,r) }
            }
            val s=inspected?.get("State")?.jsonObject; val running=s?.bool("Running")==true
            val stats=if(running) try { json.parseToJsonElement(docker("stats","--no-stream","--format","{{json .}}",container(aid),timeout=5)).jsonObject } catch(_: Exception) { obj() } else obj()
            val disk=try { run(listOf("xfs_quota","-x","-c","quota -p -b -N ${r.project}",root.toString())).trim() } catch(_: Exception) { "" }
            val started=s?.str("StartedAt"); val uptime=if(running && started!=null) try { Duration.between(Instant.parse(started),Instant.now()).seconds } catch(_: Exception) { 0 } else 0
            val stateName=if(running) "RUNNING" else if(r.desired=="RUNNING") "FAILED" else "STOPPED"
            val cpu=stats.str("CPUPerc").removeSuffix("%").toDoubleOrNull()?.div(100) ?: 0.0
            val ip=inspected?.get("NetworkSettings")?.jsonObject?.get("Networks")?.jsonObject?.values?.firstOrNull()?.jsonObject?.str("IPAddress")
            val healthy=if(running && r.spec.healthPort!=null && !ip.isNullOrEmpty()) try { Socket().use { it.connect(InetSocketAddress(ip,r.spec.healthPort!!),1000) }; true } catch(_: Exception) { false } else null
            val usedBytes=Regex("(?:^|\\s)#?${r.project}\\s+(\\d+)").find(disk)?.groupValues?.get(1)?.toLong()?.times(1024)
            rows[aid]=obj("state" to stateName,"generation" to r.generation,"appliedRevision" to r.appliedRevision,"uptimeSeconds" to uptime,"cpuCores" to cpu,"cpuPercent" to cpu/r.spec.cpu*100,"diskUsedBytes" to usedBytes,
                "memory" to stats.str("MemUsage"),"memoryPercent" to stats.str("MemPerc").removeSuffix("%").toDoubleOrNull(),"diskReport" to disk,"diskMiB" to r.spec.diskMiB,"oomKilled" to (s?.bool("OOMKilled") ?: false),"exitCode" to s?.long("ExitCode"),"healthy" to healthy,"io" to stats.str("BlockIO"),"network" to stats.str("NetIO"),"restarts" to r.restarts)
        }; snapshot=JsonObject(rows); return snapshot
    }
    fun metrics(): String = buildString {
        append("jvm_panel_agent_up ${if(ready) 1 else 0}\n")
        for((aid,s) in snapshot) { val o=s.jsonObject; val labels="app=\"$aid\",generation=\"${o.long("generation")}\""; append("jvm_app_cpu_cores{$labels} ${o.str("cpuCores","0")}\n"); append("jvm_app_memory_percent{$labels} ${o["memoryPercent"]?.jsonPrimitive?.doubleOrNull ?: 0.0}\n"); append("jvm_app_uptime_seconds{$labels} ${o.long("uptimeSeconds")}\n"); append("jvm_app_disk_bytes{$labels} ${o.long("diskUsedBytes")}\n") }
    }
}
operator fun JsonObject.plus(other: JsonObject)=JsonObject(toMap()+other.toMap())

fun main() = runBlocking {
    val agent=Agent(); agent.preflight()
    val engine=embeddedServer(Netty,host="127.0.0.1",port=env("AGENT_METRICS_PORT","8302").toInt()) {
        routing { get("/metrics") { call.respondText(agent.metrics()) }; get("/ready") { call.respondText(if(agent.ready) "ready" else "unavailable",status=if(agent.ready) io.ktor.http.HttpStatusCode.OK else io.ktor.http.HttpStatusCode.ServiceUnavailable) } }
    }.start()
    launch(Dispatchers.IO) { while(isActive) { try { agent.observe() } catch(_: Exception) { System.err.println("Agent observation failed; retrying") }; delay(2000) } }
    launch(Dispatchers.IO) {
        while(isActive) { try { agent.post("/internal/v1/heartbeat",obj("ready" to agent.ready,"snapshot" to agent.snapshot,"operationId" to agent.activeOperation)) } catch(_: Exception) { System.err.println("Agent heartbeat failed; retrying") }; delay(2000) }
    }
    withContext(Dispatchers.IO) {
        while(isActive) {
            try {
                val c=agent.post("/internal/v1/poll",obj("ready" to agent.ready,"snapshot" to agent.snapshot))
                if(c.str("id").isNotEmpty()) { val r=agent.command(c); agent.post("/internal/v1/results/${c.str("id")}",r) }
            } catch(_: Exception) { System.err.println("Agent command delivery failed; retrying") }; delay(300)
        }
    }; engine.stop()
}
