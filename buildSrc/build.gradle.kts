plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(localGroovy())
    testImplementation(gradleTestKit())
    // DebControlTest — the .deb control rewrite is only exercised for real on a Linux
    // runner, so its string transform is unit-tested here instead.
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // buildSrc is a separate build, so the root build.gradle.kts guard does not reach it: fail on a
    // @Test JUnit will not execute (a value-returning one is skipped with a warning otherwise).
    // CI runs these tests in the dedicated `./gradlew -p buildSrc test` step of build.yml.
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
}
