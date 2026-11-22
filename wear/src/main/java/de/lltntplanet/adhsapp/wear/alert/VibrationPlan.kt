package de.lltntplanet.adhsapp.wear.alert

/**
 * So vibriert die Uhr beim Klingeln: einzelne Stöße, die der Klingel-Dienst immer wieder neu startet. Ein
 * endloses Muster wäre nach dem Ausschalten des Bildschirms (Handfläche, Krone) für immer still; so fehlt
 * höchstens der Rest eines Stoßes.
 */
object VibrationPlan {

    /** Pause nach einem Stoß, bevor der nächste beginnt. */
    const val PAUSE_MILLIS = 1_500L

    /** Kürzeste und längste Klingelzeit (wie am Handy). */
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 600

    /** Für so viel Restzeit lohnt sich noch ein Stoß. */
    const val MIN_REST_MILLIS = 500L

    /** Ein Stoß: abwechselnd Pause und Vibration in Millisekunden, beginnend mit der Pause 0. */
    fun burst(): LongArray = longArrayOf(0, 500, 250, 500, 250, 800)

    /** Dauer eines Stoßes. */
    val burstMillis: Long get() = burst().sum()

    /** Abstand zwischen dem Beginn zweier Stöße. */
    val cycleMillis: Long get() = burstMillis + PAUSE_MILLIS

    /** Muster für den Rückfall-Kanal: derselbe Stoß mit der Pause am Ende (das System wiederholt es selbst). */
    fun channelPattern(): LongArray = burst() + PAUSE_MILLIS

    /** Klingelzeit in Millisekunden, auf einen sinnvollen Bereich begrenzt. */
    fun ringMillis(ringSeconds: Int): Long = ringSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS) * 1000L

    /** Restliche Klingelzeit (nie negativ). */
    fun remaining(now: Long, until: Long): Long = (until - now).coerceAtLeast(0L)

    /** Soll jetzt noch ein Stoß beginnen? */
    fun shouldBurst(now: Long, until: Long): Boolean = until - now >= MIN_REST_MILLIS
}
