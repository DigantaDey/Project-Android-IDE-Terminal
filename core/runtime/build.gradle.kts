plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api(project(":core:sandbox"))
    api(project(":core:debug"))
    api("org.mozilla:rhino:1.7.15")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
