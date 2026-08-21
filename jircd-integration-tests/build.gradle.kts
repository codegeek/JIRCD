dependencies {
    testImplementation(project(":jircd-server"))
    testImplementation(project(":jircd-core"))
    testImplementation(project(":jircd-protocol"))
    // Only to hash a real test password into administratorCredentials YAML — never to reference
    // jircd-server-extensions:admin directly, which stays runtime-only/ServiceLoader-discovered.
    testImplementation(rootProject.libs.password4j)
}
