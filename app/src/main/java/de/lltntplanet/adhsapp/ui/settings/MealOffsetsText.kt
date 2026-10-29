package de.lltntplanet.adhsapp.ui.settings

import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.reminders.ReminderPlanner

// Texte rund um die Vorwarnzeiten vor Essens-Terminen – ohne Compose, damit sie sich einfach testen lassen.

/** Vorgeschlagene Vorwarnzeiten vor Essens-Terminen (Minuten). Eigene Zeiten kommen dazu. */
internal val MEAL_OFFSET_CHOICES = listOf(0, 5, 10, 15, 20, 30, 45, 60)

/** „Zu Beginn“, „10 Min.“, „1 Std.“, „1 Std. 30 Min.“ – kurz für die Auswahl-Chips. */
internal fun offsetChipLabel(m: Int): String = when {
    m == 0 -> "Zu Beginn"
    m < 60 -> "$m Min."
    m % 60 == 0 -> "${m / 60} Std."
    else -> "${m / 60} Std. ${m % 60} Min."
}

/**
 * Was vor Essens-Terminen passiert, z. B. „Erinnert 30 Min. vorher und 10 Min. vorher – statt der Vorwarnungen oben
 * und ohne Erinnerungen aus Google Kalender. Ohne Vorwarnung, weil oben bei „Keine Erinnerung“: „Frühstück“, „Mittagessen“.“
 */
internal fun mealOffsetsSummary(s: AppSettings): String {
    val parts = s.mealOffsets.sortedDescending().map { if (it == 0) "zu Beginn" else "${offsetChipLabel(it)} vorher" }
    val list = when (parts.size) {
        0 -> null
        1 -> parts.single()
        else -> parts.dropLast(1).joinToString(", ") + " und " + parts.last()
    }
    if (list == null) return "Keine Vorwarnung vor Essens-Terminen – auch keine allgemeine und keine aus Google Kalender."
    val main = "Erinnert $list – statt der Vorwarnungen oben und ohne Erinnerungen aus Google Kalender."
    val silent = s.ignoredEventTitles.filter { ReminderPlanner.isMealTitle(it, s.mealEventTitles) }.sorted()
    return if (silent.isEmpty()) main
    else "$main Ohne Vorwarnung, weil oben bei „Keine Erinnerung“: ${silent.joinToString(", ") { "„$it“" }}."
}
