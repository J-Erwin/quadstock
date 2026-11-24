plugins {
    java
}

group = "com.thaalur"
version = "1.0.0"
// CI gives development builds distinct versions without editing the release baseline.
providers.gradleProperty("buildVersion").orNull?.let { version = it }

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    compileOnly("io.papermc.paper:paper-api:26.3.build.211-stable")
    // Vault API - compileOnly (do NOT shade)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.papermc.paper:paper-api:26.3.build.211-stable")
}

val javaFormatter = configurations.create("javaFormatter")
dependencies { add(javaFormatter.name, "com.google.googlejavaformat:google-java-format:1.35.0") }

fun JavaExec.configureFormatter() {
    classpath = javaFormatter
    mainClass.set("com.google.googlejavaformat.java.Main")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
}

tasks.register<JavaExec>("formatJava") {
    group = "formatting"
    description = "Format product and test Java sources consistently."
    configureFormatter()
    args("--replace")
    args(fileTree("src") { include("**/*.java") }.files.sorted().map { it.absolutePath })
}

val checkJavaFormat = tasks.register<JavaExec>("checkJavaFormat") {
    group = "verification"
    description = "Fail when Java sources need formatting."
    configureFormatter()
    args("--dry-run", "--set-exit-if-changed")
    args(fileTree("src") { include("**/*.java") }.files.sorted().map { it.absolutePath })
}

tasks.test {
    useJUnit()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Generate an implementation reference, not a promise of a public plugin SDK.
tasks.javadoc {
    title = "QuadStock ${project.version} — Developer Reference"
    isFailOnError = true
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        memberLevel = JavadocMemberLevel.PRIVATE
        overview = file("docs/development/overview.html").absolutePath
        // Validate links, HTML and references. Trivial accessors and implementation
        // fields do not require repetitive @param/@return boilerplate.
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
    }
}

// Documentation must remain buildable as the implementation changes.
tasks.check { dependsOn(tasks.javadoc, checkJavaFormat) }

tasks.processResources {
    from(listOf("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    from("licenses") { into("META-INF/licenses") }
    from("assets") { include("cli-message.txt") }
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.named("checkJavaFormat") {
    dependsOn("formatJava")
}

// Explicit allowlist keeps local server data, credentials and agent tooling out of releases.
val releaseSources = tasks.register<Zip>("releaseSources") {
    group = "build"
    description = "Package matching QuadStock source, build files and licensing notices."
    archiveFileName.set("quadstock-${project.version}-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(projectDir) {
        include("src/**", "licenses/**", "gradle/wrapper/**")
        include(".github/workflows/**", ".github/scripts/*.py", ".github/tests/*.py")
        include("gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts")
        include("LICENSE", "THIRD_PARTY_NOTICES.md", "README.md", "CHANGELOG.md")
        include("assets/cli-message.txt")
        include("docs/admin-guide.md", "docs/trading.md")
        include("docs/development/README.md", "docs/development/commands.md")
        include("docs/development/overview.html")
        exclude("**/build/**")
    }
}
tasks.assemble { dependsOn(releaseSources) }
