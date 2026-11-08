package com.shilapi.xcertplay.update

internal object UpdateVersion {
    internal data class Parsed(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val buildDate: Int = 0,
        val buildSequence: Int = 0,
    )

    private val VERSION = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:-build(\\d{8})-(\\d{3}))?")

    internal fun parse(value: String): Parsed? =
        VERSION.find(value)?.let { match ->
            Parsed(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                buildDate = match.groupValues[4].toIntOrNull() ?: 0,
                buildSequence = match.groupValues[5].toIntOrNull() ?: 0,
            )
        }

    internal fun isNewer(remote: String, installed: String): Boolean {
        val target = parse(remote) ?: return false
        val current = parse(installed) ?: return true
        return compareValuesBy(
            target,
            current,
            Parsed::major,
            Parsed::minor,
            Parsed::patch,
            Parsed::buildDate,
            Parsed::buildSequence,
        ) > 0
    }
}
