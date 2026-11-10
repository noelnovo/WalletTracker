plugins {
    id("org.cyclonedx.bom") version "3.5.1"
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Resolve vulnerable transitive libraries to compatible patched release lines.
// Applies to compiler/UTP/test configurations too; CycloneDX keeps its full scope.
val securityVersions = mapOf(
    "netty" to libs.versions.security.netty.get(),
    "bouncycastle" to libs.versions.security.bouncycastle.get(),
    "opentelemetry" to libs.versions.security.opentelemetry.get(),
    "guava" to libs.versions.security.guava.get(),
    "protobuf" to libs.versions.security.protobuf.get(),
    "commons-lang3" to libs.versions.security.commons.lang3.get(),
    "httpclient" to libs.versions.security.httpclient.get(),
)

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val patchedVersion = when {
                requested.group == "io.netty" && requested.name.startsWith("netty-") &&
                    !requested.name.startsWith("netty-tcnative") -> securityVersions.getValue("netty")
                requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on") ->
                    securityVersions.getValue("bouncycastle")
                requested.group == "io.opentelemetry" &&
                    requested.name in setOf("opentelemetry-api", "opentelemetry-context") ->
                    securityVersions.getValue("opentelemetry")
                requested.group == "com.google.guava" && requested.name == "guava" ->
                    securityVersions.getValue("guava") +
                        if (requested.version?.endsWith("-android") == true) "-android" else "-jre"
                requested.group == "com.google.protobuf" &&
                    requested.name in setOf("protobuf-java", "protobuf-kotlin", "protobuf-java-util") ->
                    securityVersions.getValue("protobuf")
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" ->
                    securityVersions.getValue("commons-lang3")
                requested.group == "org.apache.httpcomponents" &&
                    requested.name in setOf("httpclient", "httpmime") -> securityVersions.getValue("httpclient")
                else -> null
            }
            if (patchedVersion != null) {
                useVersion(patchedVersion)
                because("Resolve published security fixes without excluding dependencies from the SBOM")
            }
        }
    }
}
