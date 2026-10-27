package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.data.routines.MedicationKind
import de.lltntplanet.adhsapp.data.routines.MedicationSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MedicationSearchTest {

    private val catalog = MedicationSearch.parse(
        File("src/main/assets/medikamente_de.tsv").readLines().asSequence(),
    )

    @Test
    fun bundledListParsesWithStrengthsAndKinds() {
        assertTrue("Liste enthält zu wenige Einträge: ${catalog.size}", catalog.size > 200)
        val medikinet = catalog.first { it.name == "Medikinet adult" }
        assertEquals(MedicationKind.BRAND, medikinet.kind)
        assertTrue("10 mg" in medikinet.strengths)
        assertEquals(MedicationKind.SUBSTANCE, catalog.first { it.name == "Methylphenidat" }.kind)
        assertEquals(MedicationKind.SUPPLEMENT, catalog.first { it.name == "Vitamin D3" }.kind)
        assertEquals(catalog.size, catalog.map { MedicationSearch.normalize(it.name) }.toSet().size)
    }

    @Test
    fun prefixMatchesComeFirstThenWordsThenGroup() {
        val result = MedicationSearch.suggest(catalog, "medi", limit = 10)
        assertEquals("Medikinet", result.first().name)
        assertTrue(result.take(3).all { it.name.startsWith("Medikinet") })

        val adhs = MedicationSearch.suggest(catalog, "ADHS", limit = 20)
        assertTrue(adhs.isNotEmpty() && adhs.all { "ADHS" in it.group })

        val ritalin = MedicationSearch.suggest(catalog, "RITALIN")
        assertEquals("Ritalin", ritalin.first().name)
    }

    @Test
    fun shortOrUnknownQueriesGiveNothing() {
        assertTrue(MedicationSearch.suggest(catalog, "m").isEmpty())
        assertTrue(MedicationSearch.suggest(catalog, "xyzzy").isEmpty())
        assertNull(MedicationSearch.exact(catalog, "Medikinet adult 20 mg"))
        assertEquals("Medikinet adult", MedicationSearch.exact(catalog, "medikinet ADULT")?.name)
    }
}
