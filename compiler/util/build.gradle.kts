plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    api(kotlinStdlib())
    api(project(":compiler:compiler.version"))

    compileOnly(intellijCore())
    compileOnly(intellijDependency("log4j"))
    compileOnly(intellijDependency("asm-all"))
    compileOnly("com.jetbrains.intellij.platform:jps-model:${rootProject.extra["versions.intellijSdk"]}")
    compileOnly("com.jetbrains.intellij.platform:jps-model-impl:${rootProject.extra["versions.intellijSdk"]}")
}

sourceSets {
    "main" {
        projectDefault()
        resources.srcDir(File(rootDir, "resources"))
    }
    "test" {}
}
