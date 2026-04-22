// Compiled against the latest build of Paper 26.1.2. Requires JDK 25 runtime.

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.4.1"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven("https://repo.eclipse.org/content/groups/releases/") {
        name = "eclipse-jgit"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.19-alpha")

    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.http.apache:6.10.1.202505221210-r")
    implementation("com.cronutils:cron-utils:9.2.1")

    testImplementation("io.papermc.paper:paper-api:26.1.2.build.19-alpha")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.processResources {
    val tokens = mapOf("project" to mapOf("version" to project.version))
    inputs.property("project.version", project.version)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(tokens) }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")

    val shaded = "com.skycryck.tickstatssync.shaded"
    relocate("org.eclipse.jgit", "$shaded.jgit")
    relocate("com.cronutils", "$shaded.cronutils")
    relocate("org.apache.http", "$shaded.httpclient")
    relocate("org.apache.hc", "$shaded.hc")
    relocate("org.slf4j", "$shaded.slf4j")
    relocate("org.bouncycastle", "$shaded.bc")

    exclude("com/jcraft/jsch/**")
    exclude("org/eclipse/jgit/transport/sshd/**")
    exclude("org/eclipse/jgit/transport/ssh/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    enabled = false
}
