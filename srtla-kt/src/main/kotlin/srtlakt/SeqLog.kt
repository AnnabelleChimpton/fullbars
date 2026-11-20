// SPDX-License-Identifier: Apache-2.0
package srtlakt

/**
 * An insertion-ordered map from long keys to long values that never allocates after construction: the per-packet
 * logs (one entry per data packet, hundreds a second per link) used to box every key and value and allocate a map
 * entry per packet on the sender thread.
 *
 * Insertion order matters to its users and is kept exactly like a LinkedHashMap's: [put] updates a present key in
 * place, [putLast] moves it to the tail (a retransmission re-entering the in-flight log). Deliberately not a ring
 * indexed by sequence number, which would lose that order.
 *
 * Holds at most [maxEntries]: adding one more drops the oldest entry, as the logs always did. Entries are addressed by
 * slot numbers ([first], [next], [key], [value]) so callers can walk and prune without an iterator object; a slot is
 * only valid until the entry is removed.
 */
internal class SeqLog(private val maxEntries: Int) {
    private val keys = LongArray(maxEntries)
    private val vals = LongArray(maxEntries)
    private val nxt = IntArray(maxEntries)
    private val prv = IntArray(maxEntries)
    /** Open-addressing index (linear probing): slot + 1, or 0 for an empty cell. At most half full. */
    private val table: IntArray
    private val mask: Int
    private var free = NIL
    private var head = NIL
    private var tail = NIL
    var size = 0
        private set

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        var n = 2
        while (n < maxEntries * 2) n = n shl 1
        table = IntArray(n)
        mask = n - 1
        resetFreeList()
    }

    private fun resetFreeList() {
        for (i in 0 until maxEntries) nxt[i] = if (i + 1 < maxEntries) i + 1 else NIL
        free = 0
    }

    private fun home(k: Long): Int {
        val h = k * -7046029254386353131L // Fibonacci hashing spreads consecutive sequence numbers
        return (h xor (h ushr 29)).toInt() and mask
    }

    private fun cellOf(k: Long): Int {
        var i = home(k)
        while (true) {
            val s = table[i]
            if (s == 0) return NIL
            if (keys[s - 1] == k) return i
            i = (i + 1) and mask
        }
    }

    /** The slot holding [k], or [NIL]. */
    fun slotOf(k: Long): Int {
        val c = cellOf(k)
        return if (c == NIL) NIL else table[c] - 1
    }

    operator fun contains(k: Long): Boolean = cellOf(k) != NIL

    fun get(k: Long, default: Long): Long {
        val s = slotOf(k)
        return if (s == NIL) default else vals[s]
    }

    /** Sets [k] to [v], keeping its place if present, else adding it at the tail. */
    fun put(k: Long, v: Long) {
        val s = slotOf(k)
        if (s != NIL) vals[s] = v else append(k, v)
    }

    /** Sets [k] to [v] at the tail, moving it there if it was already present. */
    fun putLast(k: Long, v: Long) {
        val s = slotOf(k)
        if (s != NIL) removeSlot(s)
        append(k, v)
    }

    /** Removes [k]; returns its value, or [default] when it wasn't there. */
    fun remove(k: Long, default: Long): Long {
        val c = cellOf(k)
        if (c == NIL) return default
        val s = table[c] - 1
        val v = vals[s]
        unlinkAndFree(s, c)
        return v
    }

    fun first(): Int = head
    fun next(slot: Int): Int = nxt[slot]
    fun key(slot: Int): Long = keys[slot]
    fun value(slot: Int): Long = vals[slot]
    fun setValue(slot: Int, v: Long) { vals[slot] = v }

    /** Removes the entry in [slot]; returns the slot after it (or [NIL]) so a walk can continue. */
    fun removeSlot(slot: Int): Int {
        val after = nxt[slot]
        unlinkAndFree(slot, cellOf(keys[slot]))
        return after
    }

    fun removeFirst() {
        if (head != NIL) removeSlot(head)
    }

    fun clear() {
        if (size == 0) return
        table.fill(0)
        head = NIL; tail = NIL; size = 0
        resetFreeList()
    }

    private fun append(k: Long, v: Long) {
        if (size == maxEntries) removeSlot(head)
        val s = free
        free = nxt[s]
        keys[s] = k; vals[s] = v
        nxt[s] = NIL; prv[s] = tail
        if (tail == NIL) head = s else nxt[tail] = s
        tail = s
        size++
        var i = home(k)
        while (table[i] != 0) i = (i + 1) and mask
        table[i] = s + 1
    }

    private fun unlinkAndFree(s: Int, cell: Int) {
        val p = prv[s]
        val n = nxt[s]
        if (p == NIL) head = n else nxt[p] = n
        if (n == NIL) tail = p else prv[n] = p
        size--
        nxt[s] = free
        free = s
        deleteCell(cell)
    }

    /** Backward-shift deletion keeps every probe chain unbroken without tombstones. */
    private fun deleteCell(cell: Int) {
        var hole = cell
        var j = (cell + 1) and mask
        while (true) {
            val s = table[j]
            if (s == 0) break
            val h = home(keys[s - 1])
            // The entry at j may move into the hole unless its home lies cyclically in (hole, j].
            val stays = if (hole <= j) h in (hole + 1)..j else h > hole || h <= j
            if (!stays) {
                table[hole] = s
                hole = j
            }
            j = (j + 1) and mask
        }
        table[hole] = 0
    }

    companion object {
        const val NIL = -1
    }
}
