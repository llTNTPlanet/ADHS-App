package de.lltntplanet.adhsapp.shared

/** Wo Erinnerungen klingeln (Einstellung in der Handy-App). */
enum class RingTarget(val label: String, val description: String) {
    PHONE("Nur Handy", "Die Uhr bleibt still."),
    WATCH("Nur Uhr", "Das Handy zeigt nur eine stille Benachrichtigung. Ist die Uhr nicht erreichbar, klingelt das Handy."),
    BOTH("Handy und Uhr", "Beide klingeln. Bestätigst du auf einem Gerät, hört das andere auf."),
    WATCH_IF_WORN(
        "Uhr, wenn ich sie trage – sonst Handy",
        "Liegt die Uhr ab oder ist sie nicht erreichbar, klingelt das Handy.",
    ),
    ;

    companion object {
        /** Vorgabe: wie bisher klingelt das Handy, die Uhr zusätzlich (sobald die Uhr-App installiert ist). */
        val DEFAULT = BOTH

        fun fromName(name: String?): RingTarget = entries.find { it.name == name } ?: DEFAULT
    }
}

/** Was aus der Anfrage an die Uhr geworden ist. */
enum class WatchOutcome {
    /** Die Uhr wurde gar nicht gefragt (Einstellung „Nur Handy“ oder nichts zu zeigen). */
    NOT_REQUESTED,

    /** Die Uhr hat bestätigt, dass sie die Erinnerung anzeigt und klingelt. */
    SHOWN,

    /**
     * Die Uhr zeigt die Erinnerung an, kann aber nicht feststellen, ob sie getragen wird („Uhr, wenn ich sie
     * trage“). Das Handy klingelt dann sicherheitshalber mit.
     */
    SHOWN_UNSURE,

    /** Die Uhr hat geantwortet, wird aber gerade nicht getragen (und zeigt deshalb nichts). */
    NOT_WORN,

    /** Keine Uhr verbunden, Uhr-App fehlt, keine Antwort in der Wartezeit oder ein Fehler. */
    UNREACHABLE,
}

/** Reine Logik der Klingel-Weiche – ohne Android, damit sie sich vollständig testen lässt. */
object RingRouting {

    /** Wird die Uhr überhaupt gefragt? */
    fun asksWatch(target: RingTarget): Boolean = target != RingTarget.PHONE

    /** Soll die Uhr nur klingeln, wenn sie getragen wird? */
    fun onlyIfWorn(target: RingTarget): Boolean = target == RingTarget.WATCH_IF_WORN

    /**
     * Muss das Handy erst die Antwort der Uhr abwarten, bevor es entscheidet? Bei „Handy und Uhr“ klingelt das
     * Handy sofort, die Uhr wird nebenher benachrichtigt.
     */
    fun waitsForWatch(target: RingTarget): Boolean = target == RingTarget.WATCH || target == RingTarget.WATCH_IF_WORN

    /**
     * Klingelt das Handy (Vollbild, Ton, Vibration)? Grundsatz: Eine Erinnerung darf nie verloren gehen – nur
     * wenn die Uhr ausdrücklich bestätigt hat, dass sie anzeigt (und getragen wird, falls verlangt), bleibt das
     * Handy still.
     */
    fun phoneRings(target: RingTarget, outcome: WatchOutcome): Boolean = when (target) {
        RingTarget.PHONE, RingTarget.BOTH -> true
        RingTarget.WATCH, RingTarget.WATCH_IF_WORN -> outcome != WatchOutcome.SHOWN
    }

    /**
     * Zeigt die Uhr-App die Erinnerung selbst an? Dann soll die Handy-Benachrichtigung nicht zusätzlich auf die
     * Uhr gespiegelt werden (sonst käme sie dort doppelt).
     */
    fun watchShows(outcome: WatchOutcome): Boolean = outcome == WatchOutcome.SHOWN || outcome == WatchOutcome.SHOWN_UNSURE

    /** Ergebnis aus der Antwort der Uhr; `null` = keine Antwort in der Wartezeit. */
    fun outcome(ack: RingAck?): WatchOutcome = when {
        ack == null -> WatchOutcome.UNREACHABLE
        ack.shown -> WatchOutcome.SHOWN
        ack.displayed -> WatchOutcome.SHOWN_UNSURE
        ack.worn == false -> WatchOutcome.NOT_WORN
        else -> WatchOutcome.UNREACHABLE
    }

    /**
     * Entscheidung auf der Uhr: anzeigen oder nicht? [worn] ist `null`, wenn die Uhr es nicht feststellen kann.
     * Nur wenn „nur wenn getragen“ verlangt ist und die Uhr sicher abgelegt ist, bleibt sie still.
     */
    fun watchShouldShow(onlyIfWorn: Boolean, worn: Boolean?): Boolean = !onlyIfWorn || worn != false

    /**
     * Antwort der Uhr. [displayed] = die Erinnerung wurde auf der Uhr wirklich angezeigt. Bei „nur wenn getragen“
     * und unbekanntem Trage-Zustand zeigt die Uhr an, meldet aber `shown = false` – das Handy klingelt dann mit.
     */
    fun ackFor(requestId: String, onlyIfWorn: Boolean, worn: Boolean?, displayed: Boolean): RingAck = RingAck(
        requestId = requestId,
        shown = displayed && (!onlyIfWorn || worn == true),
        displayed = displayed,
        worn = worn,
        reason = when {
            !displayed && onlyIfWorn && worn == false -> "Uhr wird nicht getragen"
            !displayed -> "Anzeige auf der Uhr nicht möglich"
            onlyIfWorn && worn == null -> "Trage-Zustand unbekannt"
            else -> null
        },
    )
}
