package app.irlsupreme.engine.ingest

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/** Marker for AMF0 "undefined" (distinct from null). */
object Undefined

/**
 * The AMF0 subset RTMP publishers use: numbers, booleans, strings, objects, null/undefined, ECMA and strict arrays,
 * dates (as their millisecond value) and long strings. Decodes to Double / Boolean / String / Map / List / null.
 */
object Amf0 {
    fun decodeAll(bytes: ByteArray): List<Any?> {
        val input = DataInputStream(bytes.inputStream())
        val out = ArrayList<Any?>()
        while (input.available() > 0) out += read(input)
        return out
    }

    fun read(input: DataInputStream): Any? = when (val type = input.readUnsignedByte()) {
        0 -> input.readDouble()
        1 -> input.readUnsignedByte() != 0
        2 -> readString(input, input.readUnsignedShort())
        3 -> readProperties(input)
        5 -> null
        6 -> Undefined
        8 -> { input.readInt(); readProperties(input) } // ECMA array: count is advisory
        10 -> List(input.readInt()) { read(input) }
        11 -> input.readDouble().also { input.readShort() } // date: ms + timezone
        12 -> readString(input, input.readInt())
        else -> throw IllegalArgumentException("unsupported AMF0 type $type")
    }

    private fun readString(input: DataInputStream, len: Int): String {
        val b = ByteArray(len)
        input.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun readProperties(input: DataInputStream): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        while (true) {
            val key = readString(input, input.readUnsignedShort())
            if (key.isEmpty()) {
                if (input.readUnsignedByte() != 9) throw EOFException("bad AMF0 object end")
                return m
            }
            m[key] = read(input)
        }
    }

    fun encode(vararg values: Any?): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        values.forEach { write(out, it) }
        return bytes.toByteArray()
    }

    private fun write(out: DataOutputStream, v: Any?) {
        when (v) {
            null -> out.writeByte(5)
            Undefined -> out.writeByte(6)
            is Number -> { out.writeByte(0); out.writeDouble(v.toDouble()) }
            is Boolean -> { out.writeByte(1); out.writeByte(if (v) 1 else 0) }
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                if (b.size <= 0xFFFF) { out.writeByte(2); out.writeShort(b.size) } else { out.writeByte(12); out.writeInt(b.size) }
                out.write(b)
            }
            is Map<*, *> -> {
                out.writeByte(3)
                for ((k, value) in v) {
                    val kb = k.toString().toByteArray(Charsets.UTF_8)
                    out.writeShort(kb.size); out.write(kb)
                    write(out, value)
                }
                out.writeShort(0); out.writeByte(9)
            }
            is List<*> -> { out.writeByte(10); out.writeInt(v.size); v.forEach { write(out, it) } }
            else -> throw IllegalArgumentException("can't encode ${v::class.simpleName} as AMF0")
        }
    }
}
