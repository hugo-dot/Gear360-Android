package io.github.teccheck.gear360app.transport.nativegear360.sap

object SapCrc {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0) { "offset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        require(offset + length <= data.size) { "offset + length exceeds data size" }

        var checksum = 0
        for (index in offset until offset + length) {
            checksum = (checksum ushr 8) xor table[(data[index].toInt() xor checksum) and 0xff]
        }
        return checksum and 0xffff
    }

    fun readUInt16(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < data.size) { "uint16 offset out of bounds" }
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    fun writeUInt16(value: Int, target: ByteArray, offset: Int) {
        require(value in 0..0xffff) { "uint16 value out of range: $value" }
        require(offset >= 0 && offset + 1 < target.size) { "uint16 offset out of bounds" }
        target[offset] = ((value ushr 8) and 0xff).toByte()
        target[offset + 1] = (value and 0xff).toByte()
    }

    fun uint16(value: Int): ByteArray {
        val bytes = ByteArray(2)
        writeUInt16(value, bytes, 0)
        return bytes
    }

    private val table: IntArray = IntArray(256) { value ->
        var crc = value
        repeat(8) {
            crc = if ((crc and 1) != 0) {
                (crc ushr 1) xor 0xa001
            } else {
                crc ushr 1
            }
        }
        crc and 0xffff
    }
}
