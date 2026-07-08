package com.moviesrecommender.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListEntryParserTest {

    private val sampleList = """
        RATING: 4
        - Archer Seasons 1-5 (2010-2014)

        RATING: 3
        - South Park season 1-10 (1997-2006)
        - Modern Family seasons 1-3 (2009)
        - Inception (2010)

        RATING: 1
        - South Park later seasons (2007-2023)
    """.trimIndent()

    // --- headerTier ---

    @Test
    fun `headerTier recognizes TIER headers`() {
        assertEquals(40, ListEntryParser.headerTier("TIER 4"))
        assertEquals(30, ListEntryParser.headerTier("TIER 3"))
    }

    @Test
    fun `headerTier encodes a trailing plus as a distinct value between its neighbors`() {
        assertEquals(25, ListEntryParser.headerTier("TIER 2+"))
        assertEquals(20, ListEntryParser.headerTier("TIER 2"))
    }

    @Test
    fun `headerTier still recognizes the legacy RATING alias`() {
        assertEquals(30, ListEntryParser.headerTier("RATING: 3"))
    }

    @Test
    fun `headerTier returns null for a non-header line`() {
        assertNull(ListEntryParser.headerTier("- Inception (2010)"))
    }

    @Test
    fun `tierLabel formats base and plus tiers`() {
        assertEquals("4", ListEntryParser.tierLabel(40))
        assertEquals("2+", ListEntryParser.tierLabel(25))
        assertEquals("2", ListEntryParser.tierLabel(20))
        assertEquals("1", ListEntryParser.tierLabel(10))
    }

    @Test
    fun `parseSegments works against real TIER-style headers, including a plus tier`() {
        val tierList = """
            TIER 4
            - Archer Seasons 1-5 (2010-2014)

            TIER 2+
            - Daredevil Seasons 1-3 (2015-2018)
        """.trimIndent()
        assertEquals(40, ListEntryParser.parseSegments(tierList, "Archer").single().tier)
        assertEquals(25, ListEntryParser.parseSegments(tierList, "Daredevil").single().tier)
    }

    // --- extractBaseTitle ---

    @Test
    fun `extractBaseTitle strips a clean season range suffix`() {
        assertEquals("Archer", ListEntryParser.extractBaseTitle("Archer Seasons 1-5"))
    }

    @Test
    fun `extractBaseTitle strips a singular season suffix, case-insensitive`() {
        assertEquals("South Park", ListEntryParser.extractBaseTitle("South Park season 1-10"))
    }

    @Test
    fun `extractBaseTitle leaves a whole-series title untouched`() {
        assertEquals("Inception", ListEntryParser.extractBaseTitle("Inception"))
    }

    // --- parseSegments ---

    @Test
    fun `parseSegments finds every tier for a show split across sections`() {
        val segments = ListEntryParser.parseSegments(sampleList, "South Park")
        assertEquals(2, segments.size)
        assertEquals(30, segments[0].tier)
        assertEquals(1, segments[0].seasonStart)
        assertEquals(10, segments[0].seasonEnd)
        assertEquals(10, segments[1].tier)
        assertNull(segments[1].seasonStart)
        assertTrue(segments[1].label.contains("later seasons"))
    }

    @Test
    fun `parseSegments parses a clean numeric range`() {
        val segments = ListEntryParser.parseSegments(sampleList, "Archer")
        assertEquals(1, segments.size)
        assertEquals(40, segments[0].tier)
        assertEquals(1, segments[0].seasonStart)
        assertEquals(5, segments[0].seasonEnd)
        assertEquals("Seasons 1-5 (2010-2014)", segments[0].label)
    }

    @Test
    fun `parseSegments matches a whole-series entry with no suffix`() {
        val segments = ListEntryParser.parseSegments(sampleList, "Inception")
        assertEquals(1, segments.size)
        assertNull(segments[0].seasonStart)
        assertEquals(30, segments[0].tier)
    }

    @Test
    fun `parseSegments returns nothing for an unrated title`() {
        assertTrue(ListEntryParser.parseSegments(sampleList, "The Matrix").isEmpty())
    }

    @Test
    fun `parseSegments does not match an unrelated show`() {
        assertTrue(ListEntryParser.parseSegments(sampleList, "The Wire").isEmpty())
    }

    @Test
    fun `parseSegments does not let a clean numeric entry fall through to fuzzy prefix matching`() {
        // "South" is a literal text-prefix of "South Park season 1-10", but that entry has a
        // clean, fully-resolved base title ("South Park") and must not match a different show
        // via the fuzzy fallback meant only for unparseable free-text suffixes.
        val cleanOnly = "RATING: 3\n- South Park season 1-10 (1997-2006)"
        assertTrue(ListEntryParser.parseSegments(cleanOnly, "South").isEmpty())
    }

    // --- formatSegmentEntry ---

    @Test
    fun `formatSegmentEntry formats a whole-series entry`() {
        assertEquals("- Inception (2010)", ListEntryParser.formatSegmentEntry("Inception", null, null, 2010, null))
    }

    @Test
    fun `formatSegmentEntry formats a single season`() {
        assertEquals(
            "- The Mandalorian Season 2 (2020)",
            ListEntryParser.formatSegmentEntry("The Mandalorian", 2, 2, 2020, 2020)
        )
    }

    @Test
    fun `formatSegmentEntry formats a season range`() {
        assertEquals(
            "- Archer Seasons 1-5 (2010-2014)",
            ListEntryParser.formatSegmentEntry("Archer", 1, 5, 2010, 2014)
        )
    }

    @Test
    fun `formatSegmentEntry then parseSegments round-trips the same range and tier`() {
        val line = ListEntryParser.formatSegmentEntry("Fargo", 2, 4, 2015, 2020)
        val content = "RATING: 2\n$line"
        val segments = ListEntryParser.parseSegments(content, "Fargo")
        assertEquals(1, segments.size)
        assertEquals(2, segments[0].seasonStart)
        assertEquals(4, segments[0].seasonEnd)
        assertEquals(20, segments[0].tier)
    }

    // --- upsertSegment / removeLine ---

    @Test
    fun `upsertSegment inserts under an existing tier section`() {
        val updated = ListEntryParser.upsertSegment(sampleList, 30, "- Whiplash (2014)")
        val section3 = updated.lineSequence().dropWhile { it.trim() != "RATING: 3" }
            .drop(1).takeWhile { it.isNotBlank() }.toList()
        assertTrue(section3.any { it.trim() == "- Whiplash (2014)" })
    }

    @Test
    fun `upsertSegment creates a new TIER section when absent`() {
        val updated = ListEntryParser.upsertSegment(sampleList, 20, "- Heat (1995)")
        assertTrue(updated.lines().any { it.trim() == "TIER 2" })
        val segments = ListEntryParser.parseSegments(updated, "Heat")
        assertEquals(20, segments.single().tier)
    }

    @Test
    fun `upsertSegment creates a new TIER section with a plus label`() {
        val updated = ListEntryParser.upsertSegment(sampleList, 25, "- Heat (1995)")
        assertTrue(updated.lines().any { it.trim() == "TIER 2+" })
    }

    @Test
    fun `upsertSegment routes tier 2 to the plain TIER 2 section, not TIER 2 plus`() {
        val tierList = """
            TIER 2+
            - Daredevil Seasons 1-3 (2015-2018)

            TIER 2
            - Heat (1995)
        """.trimIndent()
        val updated = ListEntryParser.upsertSegment(tierList, 20, "- Sherlock (2010-2017)")
        val plusSection = updated.lineSequence().dropWhile { it.trim() != "TIER 2+" }
            .drop(1).takeWhile { it.isNotBlank() }.toList()
        assertFalse(plusSection.any { it.trim() == "- Sherlock (2010-2017)" })
        val plainSection = updated.lineSequence().dropWhile { it.trim() != "TIER 2" }
            .drop(1).takeWhile { it.isNotBlank() }.toList()
        assertTrue(plainSection.any { it.trim() == "- Sherlock (2010-2017)" })
    }

    @Test
    fun `upsertSegment replaces the given raw lines when editing`() {
        val oldLine = "- Archer Seasons 1-5 (2010-2014)"
        val newLine = "- Archer Seasons 1-3 (2010-2012)"
        val updated = ListEntryParser.upsertSegment(sampleList, 40, newLine, replacingRawLines = listOf(oldLine))
        assertFalse(updated.contains(oldLine))
        assertTrue(updated.contains(newLine))
    }

    @Test
    fun `removeLine deletes only the exact matching line`() {
        val updated = ListEntryParser.removeLine(sampleList, "- Inception (2010)")
        assertFalse(updated.contains("- Inception (2010)"))
        assertTrue(updated.contains("- Modern Family seasons 1-3 (2009)"))
    }

    // --- overlaps ---

    @Test
    fun `overlaps is true for intersecting numeric ranges`() {
        val segment = ShowSegment(tier = 3, seasonStart = 1, seasonEnd = 5, label = "", rawLine = "")
        assertTrue(ListEntryParser.overlaps(segment, 4, 8))
    }

    @Test
    fun `overlaps is false for disjoint numeric ranges`() {
        val segment = ShowSegment(tier = 3, seasonStart = 1, seasonEnd = 5, label = "", rawLine = "")
        assertFalse(ListEntryParser.overlaps(segment, 6, 8))
    }

    @Test
    fun `overlaps is true when the existing segment has no parsed range`() {
        val segment = ShowSegment(tier = 1, seasonStart = null, seasonEnd = null, label = "later seasons", rawLine = "")
        assertTrue(ListEntryParser.overlaps(segment, 6, 8))
    }
}
