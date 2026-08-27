plugins {
    id("java")
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val vertxVersion = "4.5.10"

dependencies {
    implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("org.example.gateway.day1.Main")
}

tasks.test {
    useJUnitPlatform()
}

// Day별로 별도 패키지(day1, day2, ...)에 독립된 Main을 두고,
// `./gradlew runDay1`, `./gradlew runDay2` 식으로 원하는 날짜의 코드만 골라 실행한다.
// 새 Day 패키지를 추가할 때마다 아래에 같은 형태로 한 줄 더 추가하면 된다.
fun registerDayRunTask(day: Int) {
    tasks.register<JavaExec>("runDay$day") {
        group = "application"
        description = "Run Day $day gateway prototype"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("org.example.gateway.day$day.Main")
    }
}

registerDayRunTask(1)
registerDayRunTask(2)