package com.moviesrecommender.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalStorageServiceTest {

    private lateinit var fakeDao: FakeStarDao
    private lateinit var service: LocalStorageService

    @Before
    fun setup() {
        fakeDao = FakeStarDao()
        service = LocalStorageService(fakeDao)
    }

    @Test
    fun `addStar - id appears in getStars`() = runTest {
        service.addStar(42)
        assertTrue(service.getStars().contains(42))
    }

    @Test
    fun `removeStar - id no longer in getStars`() = runTest {
        service.addStar(42)
        service.removeStar(42)
        assertFalse(service.getStars().contains(42))
    }

    @Test
    fun `addStar duplicate - no duplicate entries`() = runTest {
        service.addStar(42)
        service.addStar(42)
        assertEquals(1, service.getStars().count { it == 42 })
    }

    @Test
    fun `getStars - returns all added ids`() = runTest {
        service.addStar(1)
        service.addStar(2)
        service.addStar(3)
        assertEquals(setOf(1, 2, 3), service.getStars().toSet())
    }

    @Test
    fun `removeStar on absent id - no error, list unchanged`() = runTest {
        service.addStar(1)
        service.removeStar(99)
        assertEquals(listOf(1), service.getStars())
    }
}

private class FakeStarDao : StarDao {
    private val stars = mutableMapOf<Int, StarEntity>()

    override suspend fun insert(star: StarEntity) { stars[star.tmdbId] = star }
    override suspend fun deleteById(tmdbId: Int) { stars.remove(tmdbId) }
    override suspend fun getAll(): List<Int> = stars.keys.toList()
    override suspend fun getAllEntities(): List<StarEntity> = stars.values.toList()
}
