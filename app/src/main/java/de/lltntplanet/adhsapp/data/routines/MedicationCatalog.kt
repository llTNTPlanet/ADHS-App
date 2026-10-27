package de.lltntplanet.adhsapp.data.routines

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class MedicationKind(val label: String) {
    /** Wirkstoff, z. B. Methylphenidat. */
    SUBSTANCE("Wirkstoff"),
    /** Handelsname, z. B. Medikinet. */
    BRAND("Handelsname"),
    /** Nahrungsergänzung, z. B. Vitamin D3. */
    SUPPLEMENT("Nahrungsergänzung"),
}

/** Ein Eintrag aus der eingebauten Medikamenten-Liste. */
data class Medication(
    val name: String,
    val kind: MedicationKind,
    /** Übliche Stärken bzw. Dosiereinheiten, z. B. „10 mg“, „1 Tablette“. */
    val strengths: List<String>,
    /** Gruppe/Anwendung, z. B. „ADHS · Methylphenidat retard“. */
    val group: String,
)

/** Reine Logik: Liste einlesen und Vorschläge zu einer Eingabe finden. */
object MedicationSearch {

    /** Zeilenformat: Name ⇥ Art (W/H/N) ⇥ Stärken (mit | getrennt) ⇥ Gruppe. Kommentare mit # werden übersprungen. */
    fun parse(lines: Sequence<String>): List<Medication> = lines
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
            Medication(
                name = parts[0].trim(),
                kind = when (parts[1].trim().uppercase()) {
                    "H" -> MedicationKind.BRAND
                    "N" -> MedicationKind.SUPPLEMENT
                    else -> MedicationKind.SUBSTANCE
                },
                strengths = parts.getOrNull(2).orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() },
                group = parts.getOrNull(3).orEmpty().trim(),
            )
        }
        .toList()

    /**
     * Vorschläge zu [query]: zuerst Namen, die so beginnen, dann Wörter im Namen, dann Treffer irgendwo im Namen
     * oder in der Gruppe (z. B. „adhs“ findet alle ADHS-Mittel). Groß-/Kleinschreibung ist egal.
     */
    fun suggest(all: List<Medication>, query: String, limit: Int = 6): List<Medication> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        return all
            .mapNotNull { m ->
                val name = normalize(m.name)
                val rank = when {
                    name == q -> 0
                    name.startsWith(q) -> 1
                    name.split(' ', '-', '(', '/').any { it.startsWith(q) } -> 2
                    q in name -> 3
                    q in normalize(m.group) -> 4
                    else -> return@mapNotNull null
                }
                Triple(rank, name.length, m)
            }
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third.name }))
            .map { it.third }
            .take(limit)
    }

    /** Der Eintrag, der genau so heißt wie die Eingabe (ohne Stärke), falls vorhanden. */
    fun exact(all: List<Medication>, query: String): Medication? {
        val q = normalize(query)
        return all.firstOrNull { normalize(it.name) == q }
    }

    /** Kleinbuchstaben und typische Vereinfachungen (ß→ss, ä→ae …), damit „medikinet“ und „Medikinet“ gleich sind. */
    fun normalize(s: String): String = s.trim().lowercase()
        .replace("ß", "ss").replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
}

/** Lädt die mitgelieferte Liste gängiger Medikamente (Deutschland) einmalig aus den Assets. */
class MedicationCatalog(private val context: Context) {
    private var cache: List<Medication>? = null
    private val mutex = Mutex()

    suspend fun all(): List<Medication> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(ASSET).bufferedReader().useLines { MedicationSearch.parse(it) }
            }.onFailure { Log.w(TAG, "Medikamenten-Liste konnte nicht gelesen werden", it) }
                .getOrDefault(emptyList())
        }.also { cache = it }
    }

    suspend fun suggest(query: String, limit: Int = 6): List<Medication> = MedicationSearch.suggest(all(), query, limit)

    suspend fun exact(query: String): Medication? = MedicationSearch.exact(all(), query)

    companion object {
        private const val TAG = "MedicationCatalog"
        const val ASSET = "medikamente_de.tsv"
    }
}
