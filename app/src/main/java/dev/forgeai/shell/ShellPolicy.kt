package dev.forgeai.shell

/**
 * Command whitelist and argument validation. No shell interpreter is ever spawned:
 * argv vectors go straight to ProcessBuilder, so metacharacters are data, not syntax.
 * All paths are forced to stay inside the working (mirror) directory.
 */
internal object ShellPolicy {

    enum class Policy { USER, AGENT }

    class PolicyException(message: String) : Exception(message)

    private val USER_COMMANDS: Map<String, Set<String>> = mapOf(
        "ls" to setOf("-l", "-a", "-h", "-R"),
        "cat" to emptySet(),
        "head" to setOf("-n"),
        "tail" to setOf("-n"),
        "wc" to setOf("-l", "-w", "-c", "-m"),
        "grep" to setOf("-c", "-n", "-i", "-F", "-q", "-H"),
        "stat" to setOf("-c"),
        "md5sum" to emptySet(),
        "sha256sum" to emptySet(),
        "sort" to setOf("-n", "-r", "-u"),
        "uniq" to setOf("-u", "-c"),
        "find" to setOf("-maxdepth", "-type", "-name", "-print"),
        "du" to setOf("-s"),
        "echo" to emptySet(),
        "date" to emptySet(),
    )

    // The agent subset is read-only: no directory traversal, no size probes beyond wc/stat.
    private val AGENT_COMMANDS: Map<String, Set<String>> = mapOf(
        "ls" to setOf("-l", "-a"),
        "cat" to emptySet(),
        "head" to setOf("-n"),
        "tail" to setOf("-n"),
        "wc" to setOf("-l", "-w", "-c"),
        "grep" to setOf("-c", "-n", "-i", "-F", "-q"),
        "md5sum" to emptySet(),
        "sha256sum" to emptySet(),
        "sort" to setOf("-n", "-r", "-u"),
        "uniq" to setOf("-u", "-c"),
    )

    const val MAX_ARGS = 24
    const val MAX_ARG_CHARS = 4096
    const val MAX_NAME_CHARS = 200

    val commandsFor = mapOf(Policy.USER to USER_COMMANDS, Policy.AGENT to AGENT_COMMANDS)

    /** Returns the validated command and argument vector, or throws PolicyException. */
    fun validate(policy: Policy, command: String, args: List<String>): Pair<String, List<String>> {
        val table = commandsFor.getValue(policy)
        val name = command.trim()
        if (name.isEmpty() || name != command.trim() || name.any { it.isWhitespace() || it == '/' || it == '\\' }) {
            throw PolicyException("Command must be a single bare name from the allowed list.")
        }
        if (name.length > 32) throw PolicyException("Command name too long.")
        if (name !in table) throw PolicyException("'$name' is not in the ${policy.name.lowercase()} allowed list.")
        if (args.size > MAX_ARGS) throw PolicyException("Too many arguments (max $MAX_ARGS).")
        val out = ArrayList<String>(args.size)
        var seenFlags = 0
        var i = 0
        while (i < args.size) {
            val raw = args[i]
            if (raw.isEmpty()) throw PolicyException("Empty argument.")
            if (raw.length > MAX_ARG_CHARS) throw PolicyException("Argument $i is too long.")
            if (raw.any { it.code == 0 || it.code in 1..8 || it.code in 14..31 }) {
                throw PolicyException("Control characters are not allowed in arguments.")
            }
            if (raw.startsWith("-")) {
                seenFlags++
                if (seenFlags > MAX_ARGS) throw PolicyException("Too many flags.")
                if (raw.startsWith("--")) throw PolicyException("Long options are not allowed.")
                val arg = raw.substringAfter('-')
                if (arg.isEmpty() || arg.any { it.isWhitespace() }) throw PolicyException("Invalid flag '$raw'.")
                if (raw !in table.getValue(name)) throw PolicyException("Flag '$raw' is not allowed for '$name'.")
                // Numeric parameter flags need their value next.
                if (name == "head" && arg == "n" || name == "tail" && arg == "n" || name == "find" && arg == "maxdepth") {
                    i++
                    if (i >= args.size) throw PolicyException("Flag '$raw' needs a numeric value.")
                    val value = args[i]
                    if (value.isEmpty() || value.length > 6 || value.any { !it.isDigit() } || value.toLong() > 999999L) {
                        throw PolicyException("Flag '$raw' needs a small positive number.")
                    }
                    out.add(raw); out.add(value)
                } else if (name == "find" && arg == "type") {
                    i++
                    if (i >= args.size || args[i] !in setOf("f", "d")) throw PolicyException("'find -type' accepts f or d.")
                    out.add(raw); out.add(args[i])
                } else if (name == "find" && arg == "name") {
                    i++
                    if (i >= args.size) throw PolicyException("'find -name' needs a pattern.")
                    val pattern = args[i]
                    if (pattern.isEmpty() || pattern.length > MAX_NAME_CHARS || pattern.startsWith("-")) {
                        throw PolicyException("Invalid 'find -name' pattern.")
                    }
                    if (pattern.any { it == '/' || it.code == 0 }) throw PolicyException("Pattern must stay in the working directory.")
                    out.add(raw); out.add(pattern)
                } else if (name == "stat" && arg == "c") {
                    i++
                    if (i >= args.size || args[i] !in setOf("%s", "%n", "%F")) throw PolicyException("'stat -c' accepts %s, %n or %F only.")
                    out.add(raw); out.add(args[i])
                } else {
                    out.add(raw)
                }
            } else {
                // Positional argument: a relative path inside the working directory only.
                if (raw.startsWith("/") || raw.startsWith("\\")) {
                    throw PolicyException("Absolute paths are not allowed; files live in the working copy.")
                }
                val parts = raw.split('/')
                if (parts.any { it == ".." }) throw PolicyException("Paths must stay inside the working copy.")
                if (raw.length > MAX_ARG_CHARS) throw PolicyException("Path too long.")
                out.add(raw)
            }
            i++
        }
        return name to out
    }

    /** Known Android toybox locations, resolved against the actual filesystem. */
    fun binaryPath(name: String): String? =
        listOf("/system/bin/$name", "/system/xbin/$name", "/system/apex/com.android.runtime/bin/$name")
            .firstOrNull { p -> java.io.File(p).let { it.isFile && it.canExecute() } }

    fun isSupported(policy: Policy, command: String) =
        command in commandsFor.getValue(policy) && binaryPath(command) != null
}
