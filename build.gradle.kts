plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.14"
}

group = "vn.treo"
version = "2.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Đổi số phiên bản này cho khớp server của bạn
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Cấu trúc phẳng: file .java và plugin.yml nằm ngay thư mục gốc
sourceSets {
    main {
        java {
            setSrcDirs(listOf("."))
            include("*.java")
        }
        resources {
            setSrcDirs(listOf("."))
            include("plugin.yml")
        }
    }
}

// Paper 1.20.5+ chạy mojang-mapped nên không cần reobf
paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
