package studio.zavi.uniremote.core.androidtv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Hand-rolled protobuf encoding for the two small message sets the Android TV
 * Remote protocol uses (pairing and remote). Field numbers come from polo.proto
 * and remotemessage.proto in the open-source androidtvremote2 project.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        writeRawVarint(out, (field.toLong() shl 3) or 0L)
        writeRawVarint(out, value)
        return this
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        writeRawVarint(out, (field.toLong() shl 3) or 2L)
        writeRawVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun writeRawVarint(out: OutputStream, value: Long) {
            var v = value
            while (true) {
                if ((v and 0x7FL.inv()) == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }
}

/** A decoded message: field number -> values (Long for varints, ByteArray for length-delimited). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun has(field: Int): Boolean = fields.containsKey(field)

    fun long(field: Int, default: Long = 0): Long = (fields[field]?.lastOrNull() as? Long) ?: default

    fun int(field: Int, default: Int = 0): Int = long(field, default.toLong()).toInt()

    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    val fieldNumbers: Set<Int> get() = fields.keys

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("Varint too long")
                }
            }
            while (pos < data.size) {
                val tag = readVarint()
                val field = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> fields.getOrPut(field) { mutableListOf() }.add(readVarint())
                    1 -> { if (pos + 8 > data.size) throw IOException("Truncated fixed64"); pos += 8 }
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw IOException("Truncated field $field")
                        fields.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    5 -> { if (pos + 4 > data.size) throw IOException("Truncated fixed32"); pos += 4 }
                    else -> throw IOException("Unsupported wire type in field $field")
                }
            }
            return ProtoMessage(fields)
        }
    }
}

/** Messages on the wire are prefixed with their length as a varint. */
object ProtoFraming {
    fun write(out: OutputStream, message: ByteArray) {
        val buf = ByteArrayOutputStream(message.size + 5)
        ProtoWriter.writeRawVarint(buf, message.size.toLong())
        buf.write(message)
        out.write(buf.toByteArray())
        out.flush()
    }

    fun read(input: InputStream, maxSize: Int = 1 shl 20): ByteArray {
        var len = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed by TV")
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IOException("Bad message length")
        }
        if (len > maxSize) throw IOException("Message too large: $len")
        val data = ByteArray(len.toInt())
        var off = 0
        while (off < data.size) {
            val r = input.read(data, off, data.size - off)
            if (r < 0) throw EOFException("Connection closed by TV")
            off += r
        }
        return data
    }
}
