package cn.soul2.imageai.update

sealed interface ReleaseNotesBlock {
    data class Heading(val level: Int, val text: String) : ReleaseNotesBlock
    data class Bullet(val text: String, val ordinal: Int? = null) : ReleaseNotesBlock
    data class Paragraph(val text: String) : ReleaseNotesBlock
}

/** A small, dependency-free subset for GitHub Release notes. */
object ReleaseNotesMarkdown {
    private val headingPattern = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    private val unorderedListPattern = Regex("^[-*+]\\s+(.+)$")
    private val orderedListPattern = Regex("^(\\d+)[.)]\\s+(.+)$")
    private val linkPattern = Regex("\\[([^]]+)]\\([^)]*\\)")

    fun parse(markdown: String): List<ReleaseNotesBlock> {
        val blocks = mutableListOf<ReleaseNotesBlock>()
        val paragraph = mutableListOf<String>()
        var inCodeFence = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += ReleaseNotesBlock.Paragraph(paragraph.joinToString(" "))
                paragraph.clear()
            }
        }

        markdown.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence
                flushParagraph()
                return@forEach
            }
            if (inCodeFence) {
                paragraph += line
                return@forEach
            }
            if (line.isBlank()) {
                flushParagraph()
                return@forEach
            }
            headingPattern.matchEntire(line)?.let { match ->
                flushParagraph()
                blocks += ReleaseNotesBlock.Heading(
                    level = match.groupValues[1].length,
                    text = inlineText(match.groupValues[2]),
                )
                return@forEach
            }
            unorderedListPattern.matchEntire(line)?.let { match ->
                flushParagraph()
                blocks += ReleaseNotesBlock.Bullet(text = inlineText(match.groupValues[1]))
                return@forEach
            }
            orderedListPattern.matchEntire(line)?.let { match ->
                flushParagraph()
                blocks += ReleaseNotesBlock.Bullet(
                    text = inlineText(match.groupValues[2]),
                    ordinal = match.groupValues[1].toIntOrNull(),
                )
                return@forEach
            }
            paragraph += inlineText(line)
        }
        flushParagraph()
        return blocks
    }

    private fun inlineText(value: String): String = value
        .replace(linkPattern, "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()
}
