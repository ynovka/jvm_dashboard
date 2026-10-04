pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "jvm-dashboard"
include("services:shared", "services:backend", "services:agent")
