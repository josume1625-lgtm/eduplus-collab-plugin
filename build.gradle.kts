plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = property("pluginGroup").toString()
version = property("pluginVersion").toString()

repositories {
    mavenCentral()
}

dependencies {
    // 嵌入式 Web 容器与 WebSocket
    implementation("org.eclipse.jetty:jetty-server:11.0.20")
    implementation("org.eclipse.jetty.websocket:websocket-jetty-server:11.0.20")
    implementation("jakarta.servlet:jakarta.servlet-api:5.0.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

    // JSON 序列化 (Jackson & Gson)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("com.google.code.gson:gson:2.10.1")

    // 文本协同差分比对库 (Diff-Match-Patch)
    implementation("org.bitbucket.cowwoc:diff-match-patch:1.2")

    // 测试套件
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.23")
}

// IntelliJ Platform 配置
intellij {
    version.set(property("platformVersion").toString())
    type.set(property("platformType").toString())
    downloadSources.set(true)
    updateSinceUntilBuild.set(true)

    plugins.set(listOf(
        // 可选依赖插件，如 git4idea 等
    ))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
        kotlinOptions.freeCompilerArgs = listOf("-Xjsr305=strict")
    }

    patchPluginXml {
        sinceBuild.set(property("pluginSinceBuild").toString())
        untilBuild.set(property("pluginUntilBuild").toString())
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    runIde {
        // 开发调试分配内存，防 OOM
        jvmArgs("-Xmx2048m", "-XX:+UseG1GC")
    }
}
