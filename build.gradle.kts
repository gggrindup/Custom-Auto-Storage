plugins {
    id("java")
}

version = "0.2.0"

tasks.jar {
    archiveBaseName.set("CustomAutoStorage")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))

    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

val lombokVersion = "1.18.40"

dependencies {

    compileOnly(files("libs/HytaleServer.jar"))
    compileOnly(files("libs/SimpleClaims-1.0.38.jar"))

    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from("src/main/resources")

    destinationDirectory.set(
        file("C:\\Games\\Hytale\\UserData\\Mods")
    )
}

tasks.test {
    useJUnitPlatform()
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:unchecked",
            "-Xlint:deprecation"
        )
    )
}