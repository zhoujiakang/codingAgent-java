plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.agentjava.AgentJava"
}

repositories {
    mavenCentral()
}

dependencies {
    // 终端 I/O：TUI 框架的底层驱动（raw mode、按键读取、光标控制）
    implementation("org.jline:jline:3.28.0")

    // Markdown 终端渲染（回复结束后整段美化）
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")

    // LLM SDK：Anthropic Messages API 与 OpenAI Chat Completions API
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.openai:openai-java:4.37.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // 配置与 JSON
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")

    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "agentjava"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}
