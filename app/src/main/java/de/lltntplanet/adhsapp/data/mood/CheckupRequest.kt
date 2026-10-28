package de.lltntplanet.adhsapp.data.mood

/** Wunsch, einen bestimmten Checkup zu öffnen (aus einer Erinnerung heraus). */
data class CheckupRequest(val routineId: Long, val atMillis: Long)
