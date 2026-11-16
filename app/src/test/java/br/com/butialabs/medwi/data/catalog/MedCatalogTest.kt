package br.com.butialabs.medwi.data.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bundled public catalogs: language order, fallback and accent-insensitive matching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedCatalogTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun portuguese_finds_anvisa_names_ignoring_accents() = runTest {
        val results = MedCatalog(ctx) { "pt" }.search("losartana potass")
        assertTrue(results.toString(), results.any { it.startsWith("Losartana Pot") })
    }

    @Test
    fun english_finds_openfda_names() = runTest {
        val results = MedCatalog(ctx) { "en" }.search("lisinopril")
        assertEquals("Lisinopril", results.first())
    }

    @Test
    fun falls_back_to_the_other_catalog() = runTest {
        // A U.S.-only brand searched with a Portuguese device.
        val results = MedCatalog(ctx) { "pt" }.search("zestoretic")
        assertTrue(results.toString(), results.any { it.equals("Zestoretic", ignoreCase = true) })
    }

    @Test
    fun blank_query_returns_nothing() = runTest {
        assertTrue(MedCatalog(ctx).search("  ").isEmpty())
    }
}
