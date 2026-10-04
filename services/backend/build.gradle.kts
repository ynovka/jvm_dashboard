plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
application { mainClass.set("panel.backend.MainKt") }
dependencies {
    implementation(project(":services:shared"))
    implementation("io.ktor:ktor-server-netty:3.3.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.1")
    implementation("io.ktor:ktor-server-status-pages:3.3.1")
    implementation("io.ktor:ktor-server-websockets:3.3.1")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.liquibase:liquibase-core:4.33.0")
    implementation("de.mkammerer:argon2-jvm:2.12")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.3.1")
    testImplementation("io.ktor:ktor-client-websockets:3.3.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
