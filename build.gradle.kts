plugins {
    java
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.pipeline"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // JPA + MySQL (Phase 4: Outbox)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Jackson
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Monitoring — Prometheus 메트릭 수집
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
