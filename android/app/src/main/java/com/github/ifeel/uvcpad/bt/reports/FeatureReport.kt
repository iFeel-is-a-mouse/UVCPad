package com.github.ifeel.uvcpad.bt.reports

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
class FeatureReport(
    val bytes: ByteArray = ByteArray(1) { 0 }
) {

    var wheelResolutionMultiplier: Boolean
        get() = (bytes[0].toInt() and 0b1) != 0
        set(value) {
            bytes[0] = if (value)
                (bytes[0].toInt() or 0b1).toByte()
            else
                (bytes[0].toInt() and 0b1110).toByte()
        }

    var acPanResolutionMultiplier: Boolean
        get() = (bytes[0].toInt() and 0b100) != 0
        set(value) {
            bytes[0] = if (value)
                (bytes[0].toInt() or 0b100).toByte()
            else
                (bytes[0].toInt() and 0b1011).toByte()
        }

    fun reset() = bytes.fill(0)

    companion object {
        const val ID: Byte = 6
    }
}
