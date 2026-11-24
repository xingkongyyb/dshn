package dev.dshn.app.security

/**
 * Strict structured argv allowlist for wireless ADB bridge commands.
 * Never accepts arbitrary shell strings and rejects shell wrappers/metacharacters.
 */
object AdbArgvPolicy {
    sealed interface PolicyDecision {
        data class Allowed(val argv: List<String>, val shellCommand: String) : PolicyDecision
        data class Blocked(val reason: String) : PolicyDecision
    }

    private val BLOCKED_BINARIES = setOf(
        "sh", "bash", "dash", "zsh", "ksh", "ash", "mksh",
        "toybox", "busybox", "su", "sudo", "env", "exec", "eval",
        "rm", "mv", "cp", "dd", "chmod", "chown", "mount", "umount",
        "reboot", "setprop", "stop", "start", "kill", "killall",
        "nc", "netcat", "curl", "wget", "python", "python3", "node",
        "sqlite3", "content", "service"
    )

    private val ALLOWED_DUMPSYS_SERVICES = setOf(
        "display", "window", "activity", "SurfaceFlinger", "battery", "power", "input"
    )

    private val ALLOWED_PM_SUBCOMMANDS = setOf("list", "path")
    private val ALLOWED_PM_LIST_TARGETS = setOf("packages", "features", "libraries", "users")
    private val ALLOWED_SETTINGS_NAMESPACES = setOf("global", "system", "secure")
    private val IDENTIFIER_REGEX = Regex("""^[A-Za-z0-9._:/+-]+$""")
    private val KEY_REGEX = Regex("""^[A-Za-z0-9._-]+$""")
    private val COMPONENT_REGEX = Regex("""^[A-Za-z0-9._$]+/[A-Za-z0-9._$]+$""")
    private val INT_REGEX = Regex("""^-?\d{1,6}$""")
    private val OVERLAY_DISPLAY_REGEX = Regex("""^(?:\d+x\d+/\d+(?:,[a-zA-Z0-9_-]+)*|null|)$""")
    private val SAFE_TEXT_REGEX = Regex("""^[A-Za-z0-9 _.,:@/+!?-]+$""")
    private val FORBIDDEN_CHARS = charArrayOf('\n', '\r', '\u0000', ';', '&', '|', '`', '<', '>', '(', ')', '{', '}')

    fun evaluate(rawArgv: List<String>): PolicyDecision {
        if (rawArgv.isEmpty()) {
            return blocked("Empty argv")
        }
        for (arg in rawArgv) {
            if (arg.indexOfAny(FORBIDDEN_CHARS) >= 0 || arg.contains("\$(")) {
                return blocked("Disallowed control or shell metacharacter in argument")
            }
        }

        val cmd = rawArgv[0].substringAfterLast('/')
        if (rawArgv[0].contains('/') && cmd != rawArgv[0]) {
            return blocked("Absolute or relative binary paths are not allowed: ${rawArgv[0]}")
        }
        if (cmd in BLOCKED_BINARIES) {
            return blocked("Command '$cmd' is explicitly blocked")
        }

        val ok = when (cmd) {
            "pm" -> validatePm(rawArgv)
            "dumpsys" -> validateDumpsys(rawArgv)
            "cmd" -> validateCmd(rawArgv)
            "wm" -> validateWm(rawArgv)
            "settings" -> validateSettings(rawArgv)
            "am" -> validateAm(rawArgv)
            "input" -> validateInput(rawArgv)
            "screencap" -> validateScreencap(rawArgv)
            "getprop" -> validateGetprop(rawArgv)
            else -> "Command '$cmd' is not in ADB allowlist"
        }

        return if (ok == null) {
            PolicyDecision.Allowed(rawArgv, formatShellCommand(rawArgv))
        } else {
            blocked(ok)
        }
    }

    fun formatShellCommand(argv: List<String>): String =
        argv.joinToString(" ") { arg ->
            "'" + arg.replace("'", "'\"'\"'") + "'"
        }

    private fun validatePm(argv: List<String>): String? {
        if (argv.size < 2) return "pm requires subcommand"
        val sub = argv[1]
        if (sub !in ALLOWED_PM_SUBCOMMANDS) return "pm subcommand '$sub' not allowed"
        return when (sub) {
            "list" -> {
                if (argv.size < 3 || argv[2] !in ALLOWED_PM_LIST_TARGETS) {
                    return "pm list only allows ${ALLOWED_PM_LIST_TARGETS.joinToString()}"
                }
                for (i in 3 until argv.size) {
                    val a = argv[i]
                    if (a !in setOf("-f", "-d", "-e", "-s", "-3", "-i", "-u") && !IDENTIFIER_REGEX.matches(a)) {
                        return "Disallowed pm list argument: $a"
                    }
                }
                null
            }
            "path" -> {
                if (argv.size != 3 || !KEY_REGEX.matches(argv[2])) {
                    "pm path requires a valid package name"
                } else null
            }
            else -> "Unsupported pm subcommand"
        }
    }

    private fun validateDumpsys(argv: List<String>): String? {
        if (argv.size < 2) return "dumpsys requires an explicit service name"
        val service = argv[1]
        if (service !in ALLOWED_DUMPSYS_SERVICES) {
            return "dumpsys service '$service' is not allowlisted"
        }
        if (argv.size > 4) return "Too many arguments to dumpsys"
        for (i in 2 until argv.size) {
            val a = argv[i]
            if (!IDENTIFIER_REGEX.matches(a) && a != "--help") {
                return "Invalid dumpsys argument: $a"
            }
        }
        return null
    }

    private fun validateCmd(argv: List<String>): String? {
        if (argv.size < 3 || argv[1] != "display") {
            return "cmd only allows 'cmd display <subcommand>'"
        }
        val sub = argv[2]
        if (sub !in setOf("help", "get-displays", "get-user-preferred-display-mode", "dwb-logging-enable")) {
            if (sub != "get-displays" && sub != "help" && sub != "get-user-preferred-display-mode") {
                return "cmd display subcommand '$sub' is not allowlisted"
            }
        }
        for (i in 3 until argv.size) {
            if (!IDENTIFIER_REGEX.matches(argv[i])) return "Invalid cmd display arg: ${argv[i]}"
        }
        return null
    }

    private fun validateWm(argv: List<String>): String? {
        if (argv.size != 2 || argv[1] !in setOf("size", "density")) {
            return "wm only allows read-only 'wm size' or 'wm density'"
        }
        return null
    }

    private fun validateSettings(argv: List<String>): String? {
        if (argv.size < 4) return "settings requires action, namespace, and key"
        val action = argv[1]
        val ns = argv[2]
        val key = argv[3]
        if (ns !in ALLOWED_SETTINGS_NAMESPACES) return "Unsupported settings namespace: $ns"
        if (!KEY_REGEX.matches(key)) return "Invalid settings key: $key"
        return when (action) {
            "get" -> if (argv.size == 4) null else "settings get takes 3 arguments"
            "put" -> {
                if (ns == "global" && key == "overlay_display_devices" && argv.size == 5) {
                    if (OVERLAY_DISPLAY_REGEX.matches(argv[4])) null
                    else "Invalid overlay_display_devices value"
                } else {
                    "settings put is blocked except global overlay_display_devices (P2-3 exemption)"
                }
            }
            else -> "settings action '$action' not allowed"
        }
    }

    private fun validateAm(argv: List<String>): String? {
        if (argv.size < 4 || argv[1] != "start") {
            return "am only allows 'am start'"
        }
        var hasTarget = false
        var i = 2
        while (i < argv.size) {
            val flag = argv[i]
            when (flag) {
                "-n" -> {
                    val comp = argv.getOrNull(i + 1) ?: return "Missing component after -n"
                    if (!COMPONENT_REGEX.matches(comp)) return "Invalid component: $comp"
                    hasTarget = true
                    i += 2
                }
                "-a" -> {
                    val action = argv.getOrNull(i + 1) ?: return "Missing action after -a"
                    if (!KEY_REGEX.matches(action)) return "Invalid action: $action"
                    hasTarget = true
                    i += 2
                }
                "-c" -> {
                    val cat = argv.getOrNull(i + 1) ?: return "Missing category after -c"
                    if (!KEY_REGEX.matches(cat)) return "Invalid category: $cat"
                    i += 2
                }
                "-W", "-S", "--activity-clear-top" -> i += 1
                else -> return "Disallowed am start flag: $flag"
            }
        }
        return if (hasTarget) null else "am start requires -n or -a"
    }

    private fun validateInput(argv: List<String>): String? {
        if (argv.size < 3) return "input requires subcommand and args"
        val sub = argv[1]
        return when (sub) {
            "tap" -> {
                if (argv.size == 4 && INT_REGEX.matches(argv[2]) && INT_REGEX.matches(argv[3])) null
                else "input tap requires integer x y"
            }
            "swipe" -> {
                val validCoords = argv.size in 6..7 && (2 until argv.size).all { INT_REGEX.matches(argv[it]) }
                if (validCoords) null else "input swipe requires x1 y1 x2 y2 [durationMs]"
            }
            "keyevent" -> {
                if (argv.size == 3 && (INT_REGEX.matches(argv[2]) || Regex("""^KEYCODE_[A-Z0-9_]+$""").matches(argv[2]))) null
                else "input keyevent requires numeric keycode or KEYCODE_*"
            }
            "text" -> {
                if (argv.size == 3 && argv[2].length <= 200 && SAFE_TEXT_REGEX.matches(argv[2])) null
                else "input text requires short safe ASCII text"
            }
            else -> "input subcommand '$sub' not allowed"
        }
    }

    private fun validateScreencap(argv: List<String>): String? {
        if (argv.size == 2 && argv[1] == "-p") return null
        return "screencap only allows 'screencap -p'"
    }

    private fun validateGetprop(argv: List<String>): String? {
        if (argv.size == 1) return null
        if (argv.size == 2 && KEY_REGEX.matches(argv[1])) return null
        return "getprop takes at most one property key"
    }

    private fun blocked(reason: String): PolicyDecision.Blocked =
        PolicyDecision.Blocked("[POLICY_BLOCKED] $reason")
}
