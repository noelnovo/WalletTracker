package win.downops.wallettracker.data.api

/** Keep the public API contract independent of its Kubernetes hosting. */
internal object ApiUrls {
    fun baseUrl(value: String): String = value.trimEnd('/') + "/"

    fun readinessUrl(base: String, version: String): String {
        require(version.matches(Regex("[0-9]+"))) { "Invalid API version" }
        return "${baseUrl(base)}api/v$version/ready"
    }
}
