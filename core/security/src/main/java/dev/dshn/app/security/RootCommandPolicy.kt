package dev.dshn.app.security

import kotlinx.serialization.Serializable

@Serializable
enum class RootPolicyMode {
    /** Strict structured argv allowlist (recommended default). */
    ALLOWLIST_ONLY,

    /** Permits broader structured root commands, ALWAYS requiring per-call user confirmation and honoring hard safety blocks. */
    CUSTOM_WITH_CONFIRM
}

/**
 * Structured command policy for privileged Android host `su` execution (`device_root_exec`).
 */
object RootCommandPolicy {
    sealed interface PolicyDecision {
        data class Allowed(
            val argv: List<String>,
            val shellCommand: String,
            val requiresMandatoryConfirmation: Boolean
        ) : PolicyDecision

        data class Blocked(val reason: String) : PolicyDecision
    }

    private val HARD_CONTROL_CHARS = charArrayOf('\n', '\r', '\u0000')
    private val ALLOWLIST_META_CHARS = charArrayOf(';', '&', '|', '`', '<', '>', '(', ')', '{', '}')

    private val PROTECTED_DSHN_PATH_MARKERS = listOf(
        "dev.dshn.app/files/security",
        "secrets.enc",
        "adb_identity.enc",
        "audit.jsonl"
    )

    private val CATASTROPHIC_BINARIES = setOf(
        "mkfs", "mke2fs", "mkfs.ext4", "mkfs.f2fs", "mkfs.vfat",
        "flash_image", "erase_image", "fastboot", "wipe", "format",
        "reboot", "poweroff", "halt", "shutdown"
    )

    private val ALLOWLIST_BLOCKED_BINARIES = setOf(
        "sh", "bash", "dash", "zsh", "ksh", "ash", "mksh",
        "su", "sudo", "toybox", "busybox", "env", "exec", "eval",
        "rm", "mv", "cp", "dd", "chmod", "chown", "chcon", "mount", "umount",
        "insmod", "rmmod", "modprobe", "kill", "killall", "pkill",
        "nc", "netcat", "curl", "wget", "python", "python3", "node", "sqlite3"
    )

    private val ALLOWED_DUMPSYS_SERVICES = setOf(
        "display", "window", "activity", "SurfaceFlinger", "battery", "power",
        "input", "meminfo", "cpuinfo", "thermalservice", "package", "netstats", "wifi"
    )

    private val ALLOWED_PM_SUBCOMMANDS = setOf("list", "path", "dump", "grant", "revoke", "enable", "disable-user")
    private val ALLOWED_PM_LIST_TARGETS = setOf("packages", "features", "libraries", "users", "permissions")
    private val ALLOWED_SETTINGS_NAMESPACES = setOf("global", "system", "secure")
    private val ALLOWED_CMD_SERVICES = setOf("display", "package", "activity", "power", "thermalservice", "notification", "appops")
    private val ALLOWED_READ_PROC_EXACT = setOf(
        "/proc/version", "/proc/meminfo", "/proc/cpuinfo", "/proc/loadavg", "/proc/uptime", "/proc/stat"
    )
    private val ALLOWED_READ_SYS_PREFIXES = listOf(
        "/sys/devices/system/cpu/",
        "/sys/class/thermal/",
        "/sys/class/kgsl/",
        "/sys/class/power_supply/",
        "/sys/kernel/debug/clk/"
    )

    private val IDENTIFIER_REGEX = Regex("""^[A-Za-z0-9._:/+-]+$""")
    private val KEY_REGEX = Regex("""^[A-Za-z0-9._-]+$""")
    private val PKG_REGEX = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val PERM_REGEX = Regex("""^[A-Za-z0-9._]+$""")
    private val COMPONENT_REGEX = Regex("""^[A-Za-z0-9._$]+/[A-Za-z0-9._$]+$""")
    private val INT_REGEX = Regex("""^-?\d{1,10}$""")
    private val SAFE_TEXT_REGEX = Regex("""^[A-Za-z0-9 _.,:@/+!?-]+$""")
    private val SAFE_PATH_REGEX = Regex("""^/[A-Za-z0-9._/@:+-]+$""")

    fun evaluate(
        rawArgv: List<String>,
        mode: RootPolicyMode = RootPolicyMode.ALLOWLIST_ONLY
    ): PolicyDecision {
        if (rawArgv.isEmpty()) {
            return blocked("Empty root argv")
        }
        if (rawArgv.size > 32) {
            return blocked("Too many arguments (${rawArgv.size} > 32)")
        }

        for (arg in rawArgv) {
            if (arg.indexOfAny(HARD_CONTROL_CHARS) >= 0) {
                return blocked("Disallowed newline or NUL control character in root argument")
            }
            val lower = arg.lowercase()
            for (marker in PROTECTED_DSHN_PATH_MARKERS) {
                if (lower.contains(marker)) {
                    return blocked("Access to DSHN protected security/audit path '$marker' is strictly forbidden")
                }
            }
        }

        val rawBin = rawArgv[0]
        val cmd = rawBin.substringAfterLast('/')
        if (cmd.isBlank()) {
            return blocked("Empty command binary name")
        }
        if (cmd in CATASTROPHIC_BINARIES) {
            return blocked("Destructive/reboot command '$cmd' is permanently blocked")
        }

        if (cmd == "rm" && isCatastrophicRm(rawArgv)) {
            return blocked("Catastrophic recursive root/system/data deletion is permanently blocked")
        }
        if (cmd == "dd" && rawArgv.any { it.startsWith("of=/dev/block") || it.startsWith("of=/dev/sd") }) {
            return blocked("Raw block-device write via dd is permanently blocked")
        }

        if (mode == RootPolicyMode.CUSTOM_WITH_CONFIRM) {
            for (arg in rawArgv) {
                if (arg.indexOfAny(ALLOWLIST_META_CHARS) >= 0 || arg.contains("\$(")) {
                    return blocked("Shell metacharacters are disallowed in structured root argv")
                }
            }
            if (cmd in setOf("sh", "bash", "dash", "ash", "mksh", "su", "sudo", "eval", "exec")) {
                return blocked("Nested shell wrapper '$cmd' is disallowed; pass structured command argv directly")
            }
            return PolicyDecision.Allowed(
                argv = rawArgv,
                shellCommand = formatShellCommand(rawArgv),
                requiresMandatoryConfirmation = true
            )
        }

        for (arg in rawArgv) {
            if (arg.indexOfAny(ALLOWLIST_META_CHARS) >= 0 || arg.contains("\$(")) {
                return blocked("Disallowed shell metacharacter in root argument")
            }
        }
        if (rawBin.contains('/') && rawBin != "/system/bin/device_config") {
            return blocked("Explicit binary path is not allowed in allowlist mode: $rawBin")
        }
        if (cmd in ALLOWLIST_BLOCKED_BINARIES) {
            return blocked("Command '$cmd' is blocked in root allowlist mode")
        }

        val err = when (cmd) {
            "id", "whoami", "getenforce", "uptime" -> if (rawArgv.size == 1) null else "$cmd takes no arguments"
            "uname" -> validateUname(rawArgv)
            "getprop" -> validateGetprop(rawArgv)
            "df", "free" -> validateSimpleFlags(rawArgv, setOf("-h", "-m", "-k"))
            "ps" -> validateSimpleFlags(rawArgv, setOf("-A", "-e", "-f", "-o", "PID,UID,NAME,CMDLINE", "PID,USER,ARGS"))
            "top" -> validateTop(rawArgv)
            "dmesg" -> validateSimpleFlags(rawArgv, setOf("-T", "-t", "-l", "err,warn", "err", "warn"))
            "dumpsys" -> validateDumpsys(rawArgv)
            "pm" -> validatePm(rawArgv)
            "am" -> validateAm(rawArgv)
            "wm" -> validateWm(rawArgv)
            "settings" -> validateSettings(rawArgv)
            "device_config" -> validateDeviceConfig(rawArgv)
            "cmd" -> validateCmd(rawArgv)
            "input" -> validateInput(rawArgv)
            "screencap" -> if (rawArgv.size == 2 && rawArgv[1] == "-p") null else "screencap only allows 'screencap -p'"
            "cat" -> validateCat(rawArgv)
            "ls" -> validateLs(rawArgv)
            "ip" -> validateIp(rawArgv)
            "ss" -> validateSimpleFlags(rawArgv, setOf("-t", "-u", "-l", "-n", "-p", "-tuln", "-tulnp"))
            "sysctl" -> validateSysctl(rawArgv)
            "setenforce" -> if (rawArgv.size == 2 && rawArgv[1] in setOf("0", "1")) null else "setenforce only accepts 0 or 1"
            else -> "Command '$cmd' is not in root allowlist (switch to Custom+Confirm mode in Settings if needed)"
        }

        return if (err == null) {
            val mustConfirm = cmd in setOf("setenforce", "pm", "am", "settings", "device_config", "sysctl", "input")
            PolicyDecision.Allowed(
                argv = rawArgv,
                shellCommand = formatShellCommand(rawArgv),
                requiresMandatoryConfirmation = mustConfirm
            )
        } else {
            blocked(err)
        }
    }

    fun formatShellCommand(argv: List<String>): String =
        argv.joinToString(" ") { arg ->
            "'" + arg.replace("'", "'\"'\"'") + "'"
        }

    private fun isCatastrophicRm(argv: List<String>): Boolean {
        val protectedRoots = setOf("/", "/system", "/vendor", "/data", "/boot", "/product", "/apex", "/etc", "/proc", "/sys")
        return argv.drop(1).any { arg ->
            val norm = arg.trimEnd('/').ifEmpty { "/" }
            norm in protectedRoots || arg == "/*"
        }
    }

    private fun validateUname(argv: List<String>): String? {
        if (argv.size == 1) return null
        if (argv.size == 2 && argv[1] in setOf("-a", "-r", "-m", "-s", "-v")) return null
        return "uname only allows -a, -r, -m, -s, -v"
    }

    private fun validateGetprop(argv: List<String>): String? {
        if (argv.size == 1) return null
        if (argv.size == 2 && KEY_REGEX.matches(argv[1])) return null
        return "getprop takes at most one property key"
    }

    private fun validateSimpleFlags(argv: List<String>, allowed: Set<String>): String? {
        for (i in 1 until argv.size) {
            if (argv[i] !in allowed) return "Disallowed argument '${argv[i]}' for ${argv[0]}"
        }
        return null
    }

    private fun validateTop(argv: List<String>): String? {
        var i = 1
        var hasBatch = false
        var hasSingleIter = false
        while (i < argv.size) {
            when (val a = argv[i]) {
                "-b" -> { hasBatch = true; i += 1 }
                "-n" -> {
                    if (argv.getOrNull(i + 1) != "1") return "top only allows '-n 1'"
                    hasSingleIter = true; i += 2
                }
                "-m" -> {
                    val m = argv.getOrNull(i + 1)?.toIntOrNull()
                    if (m == null || m !in 1..50) return "top -m must be 1..50"
                    i += 2
                }
                else -> return "Disallowed top argument: $a"
            }
        }
        return if (hasBatch && hasSingleIter) null else "top requires '-b -n 1'"
    }

    private fun validateDumpsys(argv: List<String>): String? {
        if (argv.size < 2) return "dumpsys requires an explicit service name"
        if (argv[1] !in ALLOWED_DUMPSYS_SERVICES) return "dumpsys service '${argv[1]}' is not allowlisted"
        if (argv.size > 4) return "Too many dumpsys arguments"
        for (i in 2 until argv.size) {
            if (!IDENTIFIER_REGEX.matches(argv[i]) && argv[i] != "--help") return "Invalid dumpsys arg: ${argv[i]}"
        }
        return null
    }

    private fun validatePm(argv: List<String>): String? {
        if (argv.size < 2) return "pm requires subcommand"
        val sub = argv[1]
        if (sub !in ALLOWED_PM_SUBCOMMANDS) return "pm subcommand '$sub' is not allowed"
        return when (sub) {
            "list" -> {
                if (argv.size < 3 || argv[2] !in ALLOWED_PM_LIST_TARGETS) {
                    return "pm list only allows ${ALLOWED_PM_LIST_TARGETS.joinToString()}"
                }
                for (i in 3 until argv.size) {
                    val a = argv[i]
                    if (a !in setOf("-f", "-d", "-e", "-s", "-3", "-i", "-u") && !IDENTIFIER_REGEX.matches(a)) {
                        return "Disallowed pm list arg: $a"
                    }
                }
                null
            }
            "path", "dump", "enable" -> {
                if (argv.size == 3 && PKG_REGEX.matches(argv[2])) null
                else "pm $sub requires a valid package name"
            }
            "disable-user" -> {
                when {
                    argv.size == 3 && PKG_REGEX.matches(argv[2]) -> null
                    argv.size == 5 && argv[2] == "--user" && INT_REGEX.matches(argv[3]) && PKG_REGEX.matches(argv[4]) -> null
                    else -> "pm disable-user requires [--user <uid>] <package>"
                }
            }
            "grant", "revoke" -> {
                if (argv.size == 4 && PKG_REGEX.matches(argv[2]) && PERM_REGEX.matches(argv[3])) null
                else "pm $sub requires <package> <permission>"
            }
            else -> "Unsupported pm subcommand"
        }
    }

    private fun validateAm(argv: List<String>): String? {
        if (argv.size < 3) return "am requires subcommand"
        return when (argv[1]) {
            "force-stop" -> if (argv.size == 3 && PKG_REGEX.matches(argv[2])) null else "am force-stop requires package name"
            "start" -> {
                var hasTarget = false
                var i = 2
                while (i < argv.size) {
                    when (val flag = argv[i]) {
                        "-n" -> {
                            val comp = argv.getOrNull(i + 1) ?: return "Missing component after -n"
                            if (!COMPONENT_REGEX.matches(comp)) return "Invalid component: $comp"
                            hasTarget = true; i += 2
                        }
                        "-a" -> {
                            val act = argv.getOrNull(i + 1) ?: return "Missing action after -a"
                            if (!KEY_REGEX.matches(act)) return "Invalid action: $act"
                            hasTarget = true; i += 2
                        }
                        "-W", "-S", "--activity-clear-top" -> i += 1
                        else -> return "Disallowed am start flag: $flag"
                    }
                }
                if (hasTarget) null else "am start requires -n or -a"
            }
            else -> "am only allows 'start' or 'force-stop'"
        }
    }

    private fun validateWm(argv: List<String>): String? =
        if (argv.size == 2 && argv[1] in setOf("size", "density")) null
        else "wm only allows 'wm size' or 'wm density'"

    private fun validateSettings(argv: List<String>): String? {
        if (argv.size < 3) return "settings requires action and namespace"
        val action = argv[1]
        val ns = argv[2]
        if (ns !in ALLOWED_SETTINGS_NAMESPACES) return "Unsupported settings namespace: $ns"
        return when (action) {
            "list" -> if (argv.size == 3) null else "settings list takes namespace only"
            "get", "delete" -> if (argv.size == 4 && KEY_REGEX.matches(argv[3])) null else "settings $action requires valid key"
            "put" -> {
                if (argv.size == 5 && KEY_REGEX.matches(argv[3]) && argv[4].length <= 256 && SAFE_TEXT_REGEX.matches(argv[4])) null
                else "settings put requires <ns> <key> <safe_value>"
            }
            else -> "settings action '$action' not allowed"
        }
    }

    private fun validateDeviceConfig(argv: List<String>): String? {
        if (argv.size < 3) return "device_config requires action and namespace"
        val action = argv[1]
        val ns = argv[2]
        if (!KEY_REGEX.matches(ns)) return "Invalid device_config namespace: $ns"
        return when (action) {
            "list" -> if (argv.size == 3) null else "device_config list takes namespace only"
            "get" -> if (argv.size == 4 && KEY_REGEX.matches(argv[3])) null else "device_config get requires <ns> <key>"
            "put" -> {
                if (argv.size == 5 && KEY_REGEX.matches(argv[3]) && IDENTIFIER_REGEX.matches(argv[4])) null
                else "device_config put requires <ns> <key> <value>"
            }
            else -> "device_config action '$action' not allowed"
        }
    }

    private fun validateCmd(argv: List<String>): String? {
        if (argv.size < 3 || argv[1] !in ALLOWED_CMD_SERVICES) {
            return "cmd only allows services: ${ALLOWED_CMD_SERVICES.joinToString()}"
        }
        for (i in 2 until argv.size) {
            if (!IDENTIFIER_REGEX.matches(argv[i])) return "Invalid cmd arg: ${argv[i]}"
        }
        return null
    }

    private fun validateInput(argv: List<String>): String? {
        if (argv.size < 3) return "input requires subcommand"
        return when (argv[1]) {
            "tap" -> if (argv.size == 4 && INT_REGEX.matches(argv[2]) && INT_REGEX.matches(argv[3])) null else "input tap requires int x y"
            "swipe" -> if (argv.size in 6..7 && (2 until argv.size).all { INT_REGEX.matches(argv[it]) }) null else "input swipe requires x1 y1 x2 y2 [ms]"
            "keyevent" -> if (argv.size == 3 && (INT_REGEX.matches(argv[2]) || Regex("""^KEYCODE_[A-Z0-9_]+$""").matches(argv[2]))) null else "input keyevent requires code"
            "text" -> if (argv.size == 3 && argv[2].length <= 200 && SAFE_TEXT_REGEX.matches(argv[2])) null else "input text requires safe ASCII text"
            else -> "input subcommand '${argv[1]}' not allowed"
        }
    }

    private fun validateCat(argv: List<String>): String? {
        if (argv.size != 2) return "cat takes exactly one allowlisted /proc or /sys file path"
        val path = argv[1]
        if (path.contains("..") || !SAFE_PATH_REGEX.matches(path)) return "Invalid path: $path"
        if (path in ALLOWED_READ_PROC_EXACT) return null
        if (ALLOWED_READ_SYS_PREFIXES.any { path.startsWith(it) }) return null
        return "cat path '$path' is outside allowlisted /proc and /sys telemetry paths"
    }

    private fun validateLs(argv: List<String>): String? {
        if (argv.size > 3) return "Too many arguments to ls"
        val pathArgs = argv.drop(1).filterNot { it in setOf("-l", "-a", "-la", "-al", "-h") }
        if (pathArgs.size > 1) return "ls allows at most one path"
        val target = pathArgs.firstOrNull() ?: return null
        if (target.contains("..") || !SAFE_PATH_REGEX.matches(target)) return "Invalid ls path: $target"
        val forbiddenPrefixes = listOf("/data/misc/adb", "/data/adb", "/data/data", "/data/user")
        if (forbiddenPrefixes.any { target == it || target.startsWith("$it/") }) {
            return "ls path '$target' is restricted in allowlist mode"
        }
        return null
    }

    private fun validateIp(argv: List<String>): String? {
        if (argv.size in 2..3 && argv[1] in setOf("addr", "a", "route", "r", "link")) {
            if (argv.size == 2 || argv[2] == "show") return null
        }
        return "ip only allows read-only addr/route/link show"
    }

    private fun validateSysctl(argv: List<String>): String? {
        if (argv.size == 2 && (argv[1] == "-a" || KEY_REGEX.matches(argv[1]))) return null
        if (argv.size == 3 && argv[1] == "-w" && argv[2].startsWith("kernel.sched_") && Regex("""^kernel\.sched_[A-Za-z0-9_]+=[0-9]+$""").matches(argv[2])) {
            return null
        }
        return "sysctl only allows read queries or '-w kernel.sched_*=<int>'"
    }

    private fun blocked(reason: String): PolicyDecision.Blocked =
        PolicyDecision.Blocked("[POLICY_BLOCKED] $reason")
}
