package dev.dshn.feature.bridge

/**
 * Parses `host:port` (or bracketed IPv6 `[host]:port`) for Wireless ADB endpoints.
 * Malformed input always yields port = -1 so the caller surfaces a clear validation error.
 */
fun parseHostPort(raw: String): Pair<String, Int> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "" to -1
    if (trimmed.startsWith("[")) {
        val close = trimmed.indexOf("]:")
        if (close <= 1) return trimmed to -1
        val host = trimmed.substring(1, close).trim()
        val portStr = trimmed.substring(close + 2)
        val port = if (portStr.isNotEmpty() && portStr.all { it.isDigit() }) portStr.toIntOrNull() ?: -1 else -1
        return host to port
    }
    val firstColon = trimmed.indexOf(':')
    val lastColon = trimmed.lastIndexOf(':')
    if (firstColon <= 0 || firstColon != lastColon) {
        return trimmed to -1
    }
    val host = trimmed.substring(0, firstColon).trim()
    val portStr = trimmed.substring(firstColon + 1)
    val port = if (portStr.isNotEmpty() && portStr.all { it.isDigit() }) portStr.toIntOrNull() ?: -1 else -1
    return host to port
}
