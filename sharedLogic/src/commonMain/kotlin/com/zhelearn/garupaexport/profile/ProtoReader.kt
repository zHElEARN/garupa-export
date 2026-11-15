package com.zhelearn.garupaexport.profile

/** Reads only protobuf wire framing; unknown fields are skipped without copying their payloads. */
internal class ProtoReader(
    private val bytes: ByteArray,
    private var position: Int = 0,
    private val end: Int = bytes.size,
    private val depth: Int = 0,
) {
    data class Tag(val number: Int, val wireType: Int)

    fun nextTag(): Tag? {
        if (position == end) return null
        val value = varint()
        require(value <= UInt.MAX_VALUE.toULong()) { "Protobuf tag exceeds 32 bits" }
        val number = (value shr 3).toInt()
        val wire = (value and 7uL).toInt()
        require(number > 0 && wire in setOf(0, 1, 2, 5)) { "Invalid or unsupported protobuf tag" }
        return Tag(number, wire)
    }

    fun int(tag: Tag): Int {
        require(tag.wireType == 0) { "Expected integer at protobuf field ${tag.number}" }
        val value = varint()
        require(value <= Int.MAX_VALUE.toULong()) { "Integer out of range at protobuf field ${tag.number}" }
        return value.toInt()
    }

    fun string(tag: Tag): String {
        require(tag.wireType == 2) { "Expected string at protobuf field ${tag.number}" }
        val length = length()
        val start = position
        advance(length)
        return bytes.decodeToString(start, position, throwOnInvalidSequence = true)
    }

    fun message(tag: Tag): ProtoReader {
        require(tag.wireType == 2) { "Expected message at protobuf field ${tag.number}" }
        require(depth < 16) { "Protobuf nesting exceeds supported depth" }
        val length = length()
        val start = position
        advance(length)
        return ProtoReader(bytes, start, position, depth + 1)
    }

    fun skip(tag: Tag) {
        when (tag.wireType) {
            0 -> varint()
            1 -> advance(8)
            2 -> advance(length())
            5 -> advance(4)
        }
    }

    private fun length(): Int {
        val value = varint()
        require(value <= (end - position).toULong()) { "Truncated protobuf length-delimited field" }
        return value.toInt()
    }

    private fun advance(count: Int) {
        require(count >= 0 && count <= end - position) { "Truncated protobuf field" }
        position += count
    }

    private fun varint(): ULong {
        var value = 0uL
        for (index in 0 until 10) {
            require(position < end) { "Truncated protobuf varint" }
            val byte = bytes[position++].toInt() and 0xff
            require(index != 9 || byte <= 1) { "Protobuf varint exceeds 64 bits" }
            value = value or ((byte and 0x7f).toULong() shl (index * 7))
            if (byte and 0x80 == 0) return value
        }
        error("Invalid protobuf varint")
    }
}
