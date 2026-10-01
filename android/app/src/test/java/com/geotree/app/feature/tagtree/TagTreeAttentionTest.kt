package com.geotree.app.feature.tagtree

import com.geotree.app.data.model.DraftError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which field a failed save scrolls to (and focuses), in on-screen order. */
class TagTreeAttentionTest {
    @Test
    fun `tree code problems come first`() {
        assertEquals(TagTreeField.TREE_CODE, firstFieldNeedingAttention(setOf(DraftError.TREE_CODE_BLANK, DraftError.LOCATION_MISSING)))
        assertEquals(TagTreeField.TREE_CODE, firstFieldNeedingAttention(setOf(DraftError.TREE_CODE_FORMAT)))
    }

    @Test
    fun `optional details come before GPS because they are above it`() {
        assertEquals(TagTreeField.DETAILS, firstFieldNeedingAttention(setOf(DraftError.AGE_INVALID, DraftError.LOCATION_MISSING)))
        assertEquals(TagTreeField.DETAILS, firstFieldNeedingAttention(setOf(DraftError.YIELD_INVALID)))
        assertEquals(TagTreeField.DETAILS, firstFieldNeedingAttention(emptySet(), detailsInvalid = true))
    }

    @Test
    fun `location problems point at the GPS card`() {
        assertEquals(TagTreeField.LOCATION, firstFieldNeedingAttention(setOf(DraftError.LOCATION_MISSING)))
        assertEquals(TagTreeField.LOCATION, firstFieldNeedingAttention(setOf(DraftError.LATITUDE_RANGE)))
        assertEquals(TagTreeField.LOCATION, firstFieldNeedingAttention(setOf(DraftError.LONGITUDE_RANGE)))
        assertEquals(TagTreeField.LOCATION, firstFieldNeedingAttention(setOf(DraftError.ACCURACY_INVALID)))
    }

    @Test
    fun `nothing to show when the form is valid`() {
        assertNull(firstFieldNeedingAttention(emptySet()))
    }
}
