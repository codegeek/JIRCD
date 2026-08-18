import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.spotbugs) apply false
    alias(libs.plugins.sonarqube)
}

subprojects {
    plugins.apply("java")
    plugins.apply("com.diffplug.spotless")
    plugins.apply("com.github.spotbugs")
    plugins.apply("pmd")
    plugins.apply("jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj.core)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            excludeTags("load")
        }
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    val testSourceSet = extensions.getByType<SourceSetContainer>().named("test")

    tasks.register<Test>("loadTest") {
        group = "verification"
        description = "Runs tests tagged \"load\" only (timing-sensitive Success Criteria)."
        useJUnitPlatform {
            includeTags("load")
        }
        testClassesDirs = testSourceSet.get().output.classesDirs
        classpath = testSourceSet.get().runtimeClasspath
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat()
            licenseHeaderFile(rootProject.file("gradle/license-header.txt"))
            target("src/*/java/**/*.java")
        }
    }

    configure<com.github.spotbugs.snom.SpotBugsExtension> {
        ignoreFailures.set(false)
        showStackTraces.set(false)
        // Pin a SpotBugs core version that can parse Java 25 (class file major
        // version 69) bytecode — the version bundled with older plugin releases
        // cannot.
        toolVersion.set(rootProject.libs.versions.spotbugsTool)
        excludeFilter.set(rootProject.file("spotbugs-exclude.xml"))
        // MAX effort (deeper analysis, same confidence bar) — LOW confidence was
        // tried and reverted: it mostly surfaces style noise (e.g. flagging every
        // test method that declares `throws Exception`) without adding real
        // findings; empirically it still doesn't catch custom-AutoCloseable
        // leaks either — see pmd-ruleset.xml's CloseResource rule for that.
        effort.set(com.github.spotbugs.snom.Effort.MAX)
    }

    tasks.withType<SpotBugsTask>().configureEach {
        reports.create("html") {
            required.set(true)
        }
    }

    configure<PmdExtension> {
        toolVersion = "7.26.0"
        ruleSetFiles = rootProject.files("pmd-ruleset.xml")
        ruleSets = emptyList()
        isConsoleOutput = true
    }

    configure<JacocoPluginExtension> {
        // Latest release — same Java-25-bytecode story as SpotBugs/PMD above;
        // the version Gradle 9.7 bundles by default is older and can't
        // instrument/read Java 25 class files reliably.
        toolVersion = "0.8.15"
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    // Every module's own `check` always regenerates its own coverage report —
    // per-module numbers are useful on their own, but jircd-core/jircd-protocol's
    // *real* coverage also includes what jircd-integration-tests exercises
    // against them; see the root-level `testCodeCoverageReport` aggregate below
    // for that fuller picture.
    tasks.withType<Test>().configureEach {
        finalizedBy(tasks.withType<JacocoReport>())
    }
}

// Aggregates coverage across every module — including code in jircd-core/
// jircd-protocol/jircd-server that only jircd-integration-tests's real-socket
// tests actually exercise, which any single module's own report would
// understate. Hand-rolled rather than the `jacoco-report-aggregation` plugin:
// that plugin's variant-attribute wiring didn't resolve cleanly against this
// Gradle/plugin version combination ("Providers passed to attributeProvider
// must always be present when queried"), and this direct approach is the
// long-standing, well-understood pattern it was meant to replace.
val coverageTargets =
    listOf(
        "jircd-protocol",
        "jircd-core",
        "jircd-server",
        "jircd-capabilities:message-tags",
        "jircd-capabilities:server-time",
        "jircd-capabilities:echo-message",
        "jircd-server-extensions:cloak",
        "jircd-server-extensions:admin",
    ).map { project(":$it") }

// jircd-integration-tests contributes execution data (it exercises the targets
// above over real sockets) but has no `main` source of its own to measure.
val executionDataSources = coverageTargets + project(":jircd-integration-tests")

plugins.apply("jacoco")

configure<JacocoPluginExtension> {
    toolVersion = "0.8.15"
}

tasks.register<JacocoReport>("testCodeCoverageReport") {
    group = "verification"
    description =
        "Aggregates coverage across every module, including what jircd-integration-tests exercises."
    dependsOn(executionDataSources.map { it.tasks.named("test") })

    executionData.setFrom(
        executionDataSources.map {
            it.tasks.named<Test>("test").map { test ->
                test.extensions.getByType<JacocoTaskExtension>().destinationFile
            }
        }
    )

    coverageTargets.forEach { target ->
        val mainSourceSet = target.extensions.getByType<SourceSetContainer>().named("main")
        sourceDirectories.from(mainSourceSet.map { it.allJava.srcDirs })
        classDirectories.from(mainSourceSet.map { it.output.classesDirs })
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

sonar {
    properties {
        property("sonar.projectKey", "codegeek_JIRCD")
        property("sonar.organization", "codegeek")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory
                .file("reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml")
                .get()
                .asFile
                .path
        )
    }
}

// The scanner reads whatever XML is on disk; without this it happily reports
// zero coverage instead of failing, which is what "coverage doesn't show up"
// silently means in practice.
tasks.named("sonar") {
    dependsOn("testCodeCoverageReport")
}
