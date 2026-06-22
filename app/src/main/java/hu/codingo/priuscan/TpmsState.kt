package hu.codingo.priuscan

/** TPMS wheel position (POS byte of the 55 AA frame). */
enum class Wheel(val pos: Int) {
    FL(0x00), FR(0x01), RL(0x10), RR(0x11), SPARE(0x05);

    companion object {
        // 0x08 data frames carry the wheel-position byte (0x00/0x01/0x10/0x11/0x05)
        fun fromPos(p: Int): Wheel? = values().firstOrNull { it.pos == p }
        // 0x09 ID-query responses index sensors by SLOT 1..5 (NOT the wheel-pos byte):
        // 1=FL 2=FR 3=RL 4=RR 5=SPARE (slot 5 == SPARE's 0x05 pos confirms the order).
        fun fromSlot(slot: Int): Wheel? = when (slot) {
            1 -> FL; 2 -> FR; 3 -> RL; 4 -> RR; 5 -> SPARE; else -> null
        }
    }
}

/** One tyre reading decoded from a 0x08 data frame. */
data class TireReading(
    val bar: Float,   // D0 * 3.44 / 100
    val tempC: Int,   // D1 - 50
    val flags: Int,   // D2 bitfield (see TPMS_PROTOCOL.md)
    val ts: Long,     // last update (ms)
) {
    /** A reading is stale if older than 30 s (the receiver sends ~1 frame/s/position). */
    fun stale(now: Long): Boolean = now - ts > STALE_MS

    /** Low sensor battery: D2 bit4 (0x10) per the protocol doc. NOTE bit5 (0x20) is a
     *  wake/movement flag, NOT an alarm - so we mask ONLY bit4. (Doc-based, medium
     *  confidence; confirm against a known-weak sensor.) */
    val lowBatt: Boolean get() = (flags and 0x10) != 0

    companion object { const val STALE_MS = 30_000L }
}

/**
 * TPMS protocol helpers (see TPMS_PROTOCOL.md). Frame: 55 AA | CMD | POS | D0 D1 D2 | XOR,
 * XOR over all preceding bytes. Commands are built with the same XOR checksum.
 */
object Tpms {
    fun bar(d0: Int): Float = d0 * 3.44f / 100f
    fun tempC(d1: Int): Int = d1 - 50

    /** Build a host->receiver frame: 55 AA + payload + XOR(all). payload starts at CMD. */
    fun build(vararg payload: Int): ByteArray {
        val body = intArrayOf(0x55, 0xAA, *payload)
        var x = 0
        for (b in body) x = x xor (b and 0xFF)
        return (body + x).map { it.toByte() }.toByteArray()
    }

    // NB: frames are 3 payload bytes (06 CMD PARAM) — the old 4-byte QUERY (extra 0x00) got
    // NO response from the receiver; verified on the bench that "55 AA 06 07 00 <xor>" returns
    // all sensor IDs as 0x09 frames. pair/PAIR_STOP fixed to the same 3-byte shape.
    val QUERY: ByteArray get() = build(0x06, 0x07, 0x00)                // request all sensor IDs (slots 1..5)
    fun pair(pos: Int): ByteArray = build(0x06, 0x01, pos)             // enter learn mode for a position
    val PAIR_STOP: ByteArray get() = build(0x06, 0x06, 0x00)           // leave learning mode
    val HEARTBEAT: ByteArray get() = build(0x06, 0x19, 0x00)            // keep-alive
}
