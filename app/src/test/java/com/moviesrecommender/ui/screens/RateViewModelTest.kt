package com.moviesrecommender.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateViewModelTest {

    @Test
    fun testIsTitleInList_ExactMatch() {
        val listBody = """
            RATING: 8
            - Inception (2010)
            - The Matrix (1999)
        """.trimIndent()
        
        assertTrue(RateViewModel.isTitleInList("Inception", 2010, listBody))
        assertTrue(RateViewModel.isTitleInList("The Matrix", 1999, listBody))
    }

    @Test
    fun testIsTitleInList_CaseInsensitive() {
        val listBody = """
            RATING: 8
            - Inception (2010)
        """.trimIndent()
        
        assertTrue(RateViewModel.isTitleInList("inception", 2010, listBody))
    }

    @Test
    fun testIsTitleInList_WithExtraInfoInList() {
        val listBody = """
            RATING: 8
            - Inception (2010) [Awesome]
        """.trimIndent()
        
        assertTrue(RateViewModel.isTitleInList("Inception", 2010, listBody))
    }

    @Test
    fun testIsTitleInList_NotPresent() {
        val listBody = """
            RATING: 8
            - Inception (2010)
        """.trimIndent()
        
        assertFalse(RateViewModel.isTitleInList("Interstellar", 2014, listBody))
    }

    @Test
    fun testIsTitleInList_DifferentYear() {
        val listBody = """
            RATING: 8
            - Inception (2010)
        """.trimIndent()
        
        // Should match even if year is slightly off
        assertTrue(RateViewModel.isTitleInList("Inception", 2009, listBody))
    }

    @Test
    fun testIsTitleInList_NoYearInList() {
        val listBody = """
            RATING: 8
            - Inception
        """.trimIndent()
        
        // Should match title even if list has no year
        assertTrue(RateViewModel.isTitleInList("Inception", 2010, listBody))
    }

    @Test
    fun testParseRateTitles_StandardFormat() {
        val response = """
            1. Inception (2010)
            2. The Matrix (1999)
            3. Interstellar (2014)
            4. The Prestige (2006)
            5. Memento (2000)
        """.trimIndent()
        
        val titles = RateViewModel.parseRateTitles(response)
        assertEquals(5, titles.size)
        assertEquals("Inception", titles[0].first)
        assertEquals(2010, titles[0].second)
    }

    @Test
    fun testParseRateTitles_WithMarkdown() {
        val response = """
            1. **Inception** (2010)
            2. *The Matrix* (1999)
            3. __Interstellar__ (2014)
            4. _The Prestige_ (2006)
            5. Memento (2000)
        """.trimIndent()
        
        val titles = RateViewModel.parseRateTitles(response)
        assertEquals(5, titles.size)
        assertEquals("Inception", titles[0].first)
        assertEquals(2010, titles[0].second)
    }

    @Test
    fun testParseRateTitles_Incomplete() {
        val response = """
            1. Inception (2010)
            2. The Matrix (1999)
        """.trimIndent()
        
        val titles = RateViewModel.parseRateTitles(response)
        assertEquals(2, titles.size)
    }
}
