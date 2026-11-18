plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api(project(":core:util"))
    api(project(":core:editor"))
    api(project(":core:project"))
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
