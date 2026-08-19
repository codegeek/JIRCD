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

// Every module that has `main` source worth measuring coverage for — used both by the root
// aggregate HTML report below and by each module's own per-module Sonar coverage wiring
// (subprojects block), since a module's own `test` task alone understates coverage for anything
// jircd-integration-tests exercises instead (research.md "Message fan-out concurrency model").
val coverageTargetPaths =
    listOf(
        "jircd-protocol",
        "jircd-core",
        "jircd-server",
        "jircd-capabilities:message-tags",
        "jircd-capabilities:server-time",
        "jircd-capabilities:echo-message",
        "jircd-server-extensions:cloak",
        "jircd-server-extensions:admin",
    )
val coverageTargets = coverageTargetPaths.map { project(":$it") }

// jircd-integration-tests contributes execution data (it exercises the targets above over real
// sockets) but has no `main` source of its own to measure.
val executionDataSources = coverageTargets + project(":jircd-integration-tests")

subprojects {
    plugins.apply("java")
    plugins.apply("com.diffplug.spotless")
    plugins.apply("com.github.spotbugs")
    plugins.apply("pmd")
    plugins.apply("jacoco")
    // Applying only at root left the SonarCloud JaCoCo XML importer unable to match coverage data
    // back to source files in the top-level subprojects (jircd-core, jircd-protocol, jircd-server)
    // — every one of their classes logged "File 'X.java' not found in project sources" and showed
    // 0% coverage, while jircd-capabilities/*'s nested modules matched correctly. Applying the
    // plugin to each subproject too is SonarSource's documented fix for this multi-module symptom.
    plugins.apply("org.sonarqube")

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

    // Scoped to the built-in "test" task specifically, not tasks.withType<Test>().configureEach —
    // that would also reach the "loadTest" task registered below and conflict with its own
    // includeTags("load"), which JUnit Platform resolves by excluding the tag from both, silently
    // leaving "loadTest" with nothing to run.
    tasks.withType<Test>().configureEach {
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("load")
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

    if (project.path in coverageTargetPaths.map { ":$it" }) {
        // Reconfigure this module's own default jacocoTestReport (not the root aggregate) to
        // fold in every source's execution data, then point Sonar at that single-module XML
        // instead of the shared root aggregate — pointing every subproject's inherited
        // sonar.coverage.jacoco.xmlReportPaths at the SAME multi-package aggregate file left
        // jircd-core/jircd-protocol/jircd-server's coverage stuck at 0% (every one of their
        // classes logged "File 'X.java' not found in project sources" and SonarCloud kept
        // reporting 0% even after confirming the aggregate XML itself had correct data), while
        // jircd-capabilities/*'s single-package reports matched fine — a per-module report is
        // the standard, unambiguous shape Sonar's JaCoCo importer expects for each module scope.
        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(executionDataSources.map { it.tasks.named("test") })
            executionData.setFrom(
                executionDataSources.map {
                    it.tasks.named<Test>("test").map { test ->
                        test.extensions.getByType<JacocoTaskExtension>().destinationFile
                    }
                }
            )
        }
        configure<org.sonarqube.gradle.SonarExtension> {
            properties {
                property(
                    "sonar.coverage.jacoco.xmlReportPaths",
                    layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path
                )
            }
        }
    }
}

// Aggregates coverage across every module — including code in jircd-core/
// jircd-protocol/jircd-server that only jircd-integration-tests's real-socket
// tests actually exercise, which any single module's own report would
// understate. Hand-rolled rather than the `jacoco-report-aggregation` plugin:
// that plugin's variant-attribute wiring didn't resolve cleanly against this
// Gradle/plugin version combination ("Providers passed to attributeProvider
// must always be present when queried"), and this direct approach is the
// long-standing, well-understood pattern it was meant to replace. This is a
// human-facing HTML/XML artifact only now — Sonar reads each module's own
// jacocoTestReport.xml instead (see the subprojects block above).
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
    }
}

// The single root `sonar` task reads every subproject's inherited properties at analysis
// time — it must run after each coverage-bearing module's own jacocoTestReport, or it reads
// stale/absent XML instead of failing outright, which is what "coverage doesn't show up"
// silently means in practice.
tasks.named("sonar") {
    dependsOn(coverageTargets.map { it.tasks.named("jacocoTestReport") })
}
