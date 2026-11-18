plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("com.diffplug.spotless") version "8.11.0"
    id("org.openapi.generator") version "7.27.0"
}

group = "ro.pricechecker"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("com.github.crawler-commons:crawler-commons:1.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Spring interfaces and models from the contract (AD-3, C-3); generated into build/, never committed.
val openApiOutput = layout.buildDirectory.dir("generated/openapi")

tasks.openApiGenerate {
    // A renamed or removed schema must not leave stale classes behind.
    doFirst { delete(openApiOutput) }
}

openApiGenerate {
    generatorName = "spring"
    inputSpec = layout.projectDirectory.file("../api/openapi.yaml")
    outputDir = openApiOutput
    apiPackage = "ro.pricechecker.api.generated"
    modelPackage = "ro.pricechecker.api.generated.model"
    invokerPackage = "ro.pricechecker.api.generated"
    generateApiTests = false
    generateModelTests = false
    generateApiDocumentation = false
    generateModelDocumentation = false
    configOptions =
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot4" to "true",
            "useJakartaEe" to "true",
            "useTags" to "true",
            "openApiNullable" to "false",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "useBeanValidation" to "false",
            "skipDefaultInterface" to "true",
            "useJspecify" to "true",
            "hideGenerationTimestamp" to "true",
            "generateJsonIncludeAnnotations" to "false",
            "generateJsonSetterNullsAnnotations" to "false",
            "configPackage" to "ro.pricechecker.api.generated.configuration",
            "sourceFolder" to "src/main/java",
        )
}

sourceSets {
    main {
        java.srcDir(openApiOutput.map { it.dir("src/main/java") })
    }
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    options.encoding = "UTF-8"
}

// AC 1.5.1, ADR 0009: the register and retailer config ship inside the artifact, so approval
// cannot change without a build.
tasks.processResources {
    from("../docs/source-register.md") { into("registry") }
    from("../config") {
        include("*.yaml")
        into("registry")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("2.0.0")
    }
}
