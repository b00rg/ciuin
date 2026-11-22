package ch.ciuin.shared.api

import ch.ciuin.shared.model.Coverage

private val bundledCoverage = mapOf(
    1.0 to lazy { ContractJson.decodeFromString<Coverage>(europeCoverageJson()) },
    2.0 to lazy { ContractJson.decodeFromString<Coverage>(globalCoverageJson()) },
)

fun readBundledCoverage(resDeg: Double): Coverage =
    bundledCoverage[resDeg]?.value ?: Coverage(resDeg, emptyList())
