plugins {
    kotlin("jvm")
    `java-library`
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> { useJUnit() }
