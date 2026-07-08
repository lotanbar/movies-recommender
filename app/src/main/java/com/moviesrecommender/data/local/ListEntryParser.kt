package com.moviesrecommender.data.local

/**
 * One rated line belonging to a given title, e.g. "- Archer Seasons 1-5 (2010-2014)"
 * under a "TIER 3" section. [seasonStart]/[seasonEnd] are null for whole-series
 * entries and for legacy free-text suffixes that don't parse as a numeric range
 * (e.g. "South Park later seasons") — those are still surfaced via [label] but are
 * not editable through the season-range picker.
 */
data class ShowSegment(
    val tier: Int,
    val seasonStart: Int?,
    val seasonEnd: Int?,
    val label: String,
    val rawLine: String
)

object ListEntryParser {

    // Matches "TIER 4", "TIER 2+", "RATING: 4" alike. The list file's real section headers are
    // "TIER N" (with an occasional "+", e.g. "TIER 2+"); "RATING: N" is kept as a recognized
    // alias since older app code and the bundled system prompt both used that name.
    private val HEADER_REGEX = Regex("""^(?:RATING:|TIER)\s*(\d+)(\+)?""", RegexOption.IGNORE_CASE)
    private val SEASON_SUFFIX_REGEX = Regex(
        """^(.*?)\s+seasons?\s+(\d+)\s*(?:-\s*(\d+))?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Every tier the app understands, best to worst. A "+" tier is encoded as its base number
     * times 10 plus 5 (e.g. "2+" -> 25) so it sorts strictly between its neighbors (2 -> 20,
     * 3 -> 30) while staying a plain Int everywhere else in the app.
     */
    val TIERS: List<Int> = listOf(40, 30, 25, 20, 10)

    /** Human-readable label for an encoded tier value, e.g. 25 -> "2+", 40 -> "4". */
    fun tierLabel(tier: Int): String {
        val base = tier / 10
        return if (tier % 10 == 5) "$base+" else "$base"
    }

    /** The tier value of a section header line ("TIER 4", "TIER 2+", "RATING: 4", …), or null. */
    fun headerTier(line: String): Int? {
        val match = HEADER_REGEX.find(line.trim()) ?: return null
        val base = match.groupValues[1].toIntOrNull() ?: return null
        val isPlus = match.groupValues[2] == "+"
        return base * 10 + if (isPlus) 5 else 0
    }

    private data class TitleParts(val base: String, val seasonStart: Int?, val seasonEnd: Int?)

    private fun splitTitlePart(titlePart: String): TitleParts {
        val trimmed = titlePart.trim()
        val match = SEASON_SUFFIX_REGEX.find(trimmed)
        if (match != null) {
            val base = match.groupValues[1].trim()
            val start = match.groupValues[2].toIntOrNull()
            val end = match.groupValues[3].toIntOrNull() ?: start
            return TitleParts(base, start, end)
        }
        return TitleParts(trimmed, null, null)
    }

    /** Base title with any trailing "Season(s) N(-M)" suffix stripped. */
    fun extractBaseTitle(titlePart: String): String = splitTitlePart(titlePart).base

    /** Parses every rated entry in [listContent] whose base title matches [tmdbTitle], across all tiers. */
    fun parseSegments(listContent: String, tmdbTitle: String): List<ShowSegment> {
        val normalizedTarget = tmdbTitle.trim().lowercase()
        var currentTier = -1
        val segments = mutableListOf<ShowSegment>()
        for (line in listContent.lines()) {
            val trimmed = line.trim()
            val tier = headerTier(trimmed)
            if (tier != null) {
                currentTier = tier
                continue
            }
            if (currentTier !in TIERS || !trimmed.startsWith("- ")) continue

            val content = trimmed.removePrefix("- ").trim()
            val titlePart = content.substringBefore("(").trim()
            val yearPart = content.substringAfter("(", "").substringBefore(")").trim()
            val parts = splitTitlePart(titlePart)

            val isWholeTitleMatch = parts.base.lowercase() == normalizedTarget
            // Only fall back to a fuzzy prefix match for free-text suffixes the regex couldn't
            // parse (e.g. "later seasons") — clean numeric entries are already fully resolved
            // by isWholeTitleMatch above, so this never has to compete with it.
            val isPrefixMatch = !isWholeTitleMatch && parts.seasonStart == null &&
                titlePart.lowercase().startsWith("$normalizedTarget ")
            if (!isWholeTitleMatch && !isPrefixMatch) continue

            val label = when {
                parts.seasonStart != null && parts.seasonStart == parts.seasonEnd ->
                    "Season ${parts.seasonStart} ($yearPart)"
                parts.seasonStart != null ->
                    "Seasons ${parts.seasonStart}-${parts.seasonEnd} ($yearPart)"
                isPrefixMatch ->
                    "${titlePart.substring(minOf(tmdbTitle.length, titlePart.length)).trim()} ($yearPart)"
                else -> "Whole series ($yearPart)"
            }
            segments.add(
                ShowSegment(
                    tier = currentTier,
                    seasonStart = parts.seasonStart,
                    seasonEnd = parts.seasonEnd,
                    label = label,
                    rawLine = line
                )
            )
        }
        return segments
    }

    /** Builds a "- Title[ Season(s) N(-M)] (year[-year])" line, matching the app's existing entry format. */
    fun formatSegmentEntry(
        tmdbTitle: String,
        seasonStart: Int?,
        seasonEnd: Int?,
        yearStart: Int,
        yearEnd: Int?
    ): String {
        val yearText = if (yearEnd != null && yearEnd != yearStart) "$yearStart-$yearEnd" else "$yearStart"
        val seasonText = when {
            seasonStart == null -> ""
            seasonStart == seasonEnd -> " Season $seasonStart"
            else -> " Seasons $seasonStart-$seasonEnd"
        }
        return "- $tmdbTitle$seasonText ($yearText)"
    }

    /**
     * Inserts [newLine] under the "TIER $tier" section, first removing any lines in
     * [replacingRawLines] (used when editing a segment in place or resolving an overlap).
     */
    fun upsertSegment(
        content: String,
        tier: Int,
        newLine: String,
        replacingRawLines: List<String> = emptyList()
    ): String {
        val lines = content.lines()
            .filter { line -> replacingRawLines.none { it.trim() == line.trim() } }
            .toMutableList()
        val headerIdx = lines.indexOfFirst { headerTier(it) == tier }
        if (headerIdx >= 0) {
            var insertIdx = headerIdx + 1
            while (insertIdx < lines.size &&
                lines[insertIdx].isNotBlank() &&
                headerTier(lines[insertIdx]) == null
            ) {
                insertIdx++
            }
            lines.add(insertIdx, newLine)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("TIER ${tierLabel(tier)}")
            lines.add(newLine)
        }
        return lines.joinToString("\n")
    }

    fun removeLine(content: String, rawLine: String): String =
        content.lines().filter { it.trim() != rawLine.trim() }.joinToString("\n")

    /**
     * True if a new pick of [newStart]-[newEnd] would overlap [segment]. A null bound on
     * either side (whole-series entries, or legacy free-text suffixes we can't parse into
     * numbers) is treated as covering everything, so it always counts as an overlap.
     */
    fun overlaps(segment: ShowSegment, newStart: Int, newEnd: Int): Boolean {
        val existingStart = segment.seasonStart ?: return true
        val existingEnd = segment.seasonEnd ?: return true
        return newStart <= existingEnd && existingStart <= newEnd
    }
}
