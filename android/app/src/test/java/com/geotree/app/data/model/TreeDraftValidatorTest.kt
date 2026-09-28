package com.geotree.app.data.model

import com.geotree.app.testing.gpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeDraftValidatorTest {

    private fun draft(code: String = "GEO-TAM-003", fix: com.geotree.app.core.location.GpsFix? = gpsFix(), details: TreeDetails = TreeDetails()) =
        TreeDraft(code, localImagePath = null, fix = fix, details = details)

    @Test
    fun `valid draft passes without requiring optional details or image`() {
        val result = TreeDraftValidator.validate(draft())
        assertTrue(result.isValid)
        assertFalse(result.lowAccuracy)
    }

    @Test
    fun `blank tree code is rejected`() {
        assertEquals(setOf(DraftError.TREE_CODE_BLANK), TreeDraftValidator.validate(draft(code = "   ")).errors)
    }

    @Test
    fun `malformed tree code is rejected`() {
        assertEquals(setOf(DraftError.TREE_CODE_FORMAT), TreeDraftValidator.validate(draft(code = "GEO TAM/003")).errors)
        assertEquals(setOf(DraftError.TREE_CODE_FORMAT), TreeDraftValidator.validate(draft(code = "-GEO")).errors)
    }

    @Test
    fun `tree code is normalized to upper case`() {
        assertEquals("GEO-TAM-003", TreeCode.normalize("  geo-tam-003 "))
        assertTrue(TreeDraftValidator.validate(draft(code = "psau-tam-003")).isValid)
    }

    @Test
    fun `missing location blocks save`() {
        assertEquals(setOf(DraftError.LOCATION_MISSING), TreeDraftValidator.validate(draft(fix = null)).errors)
    }

    @Test
    fun `out of range coordinates are rejected`() {
        val errors = TreeDraftValidator.validate(draft(fix = gpsFix(latitude = 90.5, longitude = -180.01))).errors
        assertEquals(setOf(DraftError.LATITUDE_RANGE, DraftError.LONGITUDE_RANGE), errors)
        assertEquals(setOf(DraftError.LATITUDE_RANGE), TreeDraftValidator.validate(draft(fix = gpsFix(latitude = Double.NaN))).errors)
    }

    @Test
    fun `boundary coordinates are valid`() {
        assertTrue(TreeDraftValidator.validate(draft(fix = gpsFix(latitude = -90.0, longitude = 180.0))).isValid)
    }

    @Test
    fun `negative accuracy is rejected`() {
        assertEquals(setOf(DraftError.ACCURACY_INVALID), TreeDraftValidator.validate(draft(fix = gpsFix(accuracy = -1f))).errors)
    }

    @Test
    fun `low accuracy is valid but flagged for confirmation`() {
        val result = TreeDraftValidator.validate(draft(fix = gpsFix(accuracy = 18f)))
        assertTrue(result.isValid)
        assertTrue(result.lowAccuracy)
    }

    @Test
    fun `negative optional values are rejected`() {
        val errors = TreeDraftValidator.validate(draft(details = TreeDetails(age = -1, yearlyYield = -2.0))).errors
        assertEquals(setOf(DraftError.AGE_INVALID, DraftError.YIELD_INVALID), errors)
    }
}
