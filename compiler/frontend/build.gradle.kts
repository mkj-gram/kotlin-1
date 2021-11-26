plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    api(project(":kotlin-annotations-jvm"))
    api(project(":core:descriptors"))
    api(project(":core:deserialization"))
    api(project(":compiler:util"))
    api(project(":compiler:config"))
    api(project(":compiler:container"))
    api(project(":compiler:resolution"))
    api(project(":compiler:psi"))
    api(project(":compiler:frontend.common"))
    api(project(":compiler:frontend.common-psi"))
    api(project(":kotlin-script-runtime"))
    api(commonDep("io.javaslang","javaslang"))
    compileOnly(intellijCore())
    compileOnly(intellijDependency("trove4j"))
    compileOnly(intellijDependency("guava"))
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}
