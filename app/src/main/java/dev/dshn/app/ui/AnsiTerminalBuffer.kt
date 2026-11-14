package dev.dshn.app.ui

/**
 * Compact VT100/ANSI screen & scrollback buffer for interactive shell / vim output.
 * Parses common CSI cursor/erase/clear sequences so raw control codes do not garble text.
 */
class AnsiTerminalBuffer(
    private val cols: Int = 80,
    private val rows: Int = 28,
    private val maxScrollback: Int = 400
) {
    private val scrollback = ArrayDeque<CharArray>()
    private var screen = Array(rows) { CharArray(cols) { ' ' } }
    private var curRow = 0
    private var curCol = 0

    private enum class ParseState { NORMAL, ESC, CSI, OSC }
    private var state = ParseState.NORMAL
    private val seqBuf = StringBuilder()

    @Synchronized
    fun append(text: String) {
        for (ch in text) {
            when (state) {
                ParseState.NORMAL -> handleNormal(ch)
                ParseState.ESC -> when (ch) {
                    '[' -> {
                        state = ParseState.CSI
                        seqBuf.clear()
                    }
                    ']' -> {
                        state = ParseState.OSC
                        seqBuf.clear()
                    }
                    else -> state = ParseState.NORMAL
                }
                ParseState.CSI -> {
                    if (ch in '@'..'~') {
                        handleCsi(ch, seqBuf.toString())
                        state = ParseState.NORMAL
                    } else {
                        seqBuf.append(ch)
                        if (seqBuf.length > 64) state = ParseState.NORMAL
                    }
                }
                ParseState.OSC -> {
                    if (ch == '\u0007' || (ch == '\\' && seqBuf.endsWith('\u001B'))) {
                        state = ParseState.NORMAL
                    } else {
                        seqBuf.append(ch)
                        if (seqBuf.length > 256) state = ParseState.NORMAL
                    }
                }
            }
        }
    }

    private fun handleNormal(ch: Char) {
        when (ch) {
            '\u001B' -> state = ParseState.ESC
            '\r' -> curCol = 0
            '\n' -> newLine()
            '\b' -> if (curCol > 0) curCol -= 1
            '\t' -> {
                val next = ((curCol / 8) + 1) * 8
                curCol = next.coerceAtMost(cols - 1)
            }
            '\u0007' -> {}
            else -> if (ch >= ' ') {
                if (curCol >= cols) {
                    curCol = 0
                    newLine()
                }
                screen[curRow][curCol] = ch
                curCol += 1
            }
        }
    }

    private fun newLine() {
        if (curRow < rows - 1) {
            curRow += 1
        } else {
            if (scrollback.size >= maxScrollback) scrollback.removeFirst()
            scrollback.addLast(screen[0])
            for (r in 0 until rows - 1) screen[r] = screen[r + 1]
            screen[rows - 1] = CharArray(cols) { ' ' }
        }
    }

    private fun handleCsi(cmd: Char, rawParams: String) {
        val cleaned = rawParams.removePrefix("?")
        val params = cleaned.split(';').map { it.toIntOrNull() }
        val p1 = params.getOrNull(0)
        val p2 = params.getOrNull(1)
        when (cmd) {
            'H', 'f' -> {
                curRow = ((p1 ?: 1) - 1).coerceIn(0, rows - 1)
                curCol = ((p2 ?: 1) - 1).coerceIn(0, cols - 1)
            }
            'A' -> curRow = (curRow - (p1 ?: 1)).coerceIn(0, rows - 1)
            'B' -> curRow = (curRow + (p1 ?: 1)).coerceIn(0, rows - 1)
            'C' -> curCol = (curCol + (p1 ?: 1)).coerceIn(0, cols - 1)
            'D' -> curCol = (curCol - (p1 ?: 1)).coerceIn(0, cols - 1)
            'G' -> curCol = ((p1 ?: 1) - 1).coerceIn(0, cols - 1)
            'J' -> when (p1 ?: 0) {
                2, 3 -> {
                    for (r in 0 until rows) screen[r].fill(' ')
                    curRow = 0; curCol = 0
                }
                0 -> {
                    for (c in curCol until cols) screen[curRow][c] = ' '
                    for (r in curRow + 1 until rows) screen[r].fill(' ')
                }
                1 -> {
                    for (r in 0 until curRow) screen[r].fill(' ')
                    for (c in 0..curCol.coerceAtMost(cols - 1)) screen[curRow][c] = ' '
                }
            }
            'K' -> when (p1 ?: 0) {
                0 -> for (c in curCol until cols) screen[curRow][c] = ' '
                1 -> for (c in 0..curCol.coerceAtMost(cols - 1)) screen[curRow][c] = ' '
                2 -> screen[curRow].fill(' ')
            }
            'h', 'l' -> {
                // Handle alt-screen ?1049h / ?1049l cleanly by clearing viewport
                if (rawParams.contains("1049") || rawParams.contains("47")) {
                    for (r in 0 until rows) screen[r].fill(' ')
                    curRow = 0; curCol = 0
                }
            }
            // Ignore SGR 'm' colors safely without corrupting text
        }
    }

    @Synchronized
    fun clear() {
        scrollback.clear()
        for (r in 0 until rows) screen[r].fill(' ')
        curRow = 0
        curCol = 0
    }

    @Synchronized
    fun renderText(): String {
        val sb = StringBuilder()
        for (line in scrollback) {
            sb.appendLine(String(line).trimEnd())
        }
        val lastNonBlank = (rows - 1 downTo 0).firstOrNull { r ->
            screen[r].any { it != ' ' }
        } ?: curRow
        val endRow = maxOf(lastNonBlank, curRow)
        for (r in 0..endRow) {
            sb.append(String(screen[r]).trimEnd())
            if (r < endRow) sb.append('\n')
        }
        return sb.toString()
    }
}
