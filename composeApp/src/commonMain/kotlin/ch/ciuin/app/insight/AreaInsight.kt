package ch.ciuin.app.insight

import ch.ciuin.app.Category
import ch.ciuin.app.Level
import ch.ciuin.app.heat.AccessShares
import ch.ciuin.app.heat.BreakdownSection
import ch.ciuin.app.heat.HeatSample
import ch.ciuin.app.heat.ViewBounds
import ch.ciuin.app.heat.summarise
import ch.ciuin.shared.api.NoisePeriod
import ch.ciuin.shared.model.HeatPoint
import ch.ciuin.shared.model.Place

data class RatedMean(val mean: Double, val level: Level)

sealed interface MeasuredInsight {
    data object NoData : MeasuredInsight
    data class Data(val mean: Double, val level: Level, val measuredPlaces: Int) : MeasuredInsight
}

data class PersonalInsight(
    val overwhelm: RatedMean?,
    val meanDb: Double?,
    val lightLevel: Level?,
    val crowdLevel: Level?,
)

data class SoundInsight(
    val noise: RatedMean?,
    val roadMeanDb: Double?,
    val railMeanDb: Double?,
    val measuredSoundTypes: BreakdownSection? = null,
)
data class AccessInsight(val shares: AccessShares?, val taggedPlaces: Int)

data class AreaInsight(
    val personal: PersonalInsight,
    val sound: SoundInsight,
    val light: MeasuredInsight,
    val crowd: MeasuredInsight,
    val access: AccessInsight,
    val meanFlickerIndex: Double? = null,
)

/** Pass the grid for [noisePeriod]. Null [bounds] summarises the entire supplied city. */
fun areaInsight(
    places: List<Place>,
    grid: List<HeatPoint>,
    bounds: ViewBounds? = null,
    personalScores: Map<String, Double> = emptyMap(),
    noisePeriod: NoisePeriod = NoisePeriod.DAY,
    lightGrid: List<HeatSample>? = null,
    crowdGrid: List<HeatSample>? = null,
    flickerGrid: List<HeatSample>? = null,
): AreaInsight {
    val placesIn = bounds?.let { view -> places.filter { view.containsLocation(it.lat, it.lon) } } ?: places
    val gridIn = bounds?.let { view -> grid.filter { view.containsLocation(it.lat, it.lon) } } ?: grid
    fun measured(category: Category, gridSamples: List<HeatSample>?): MeasuredInsight {
        if (gridSamples != null) {
            val cells = bounds?.let { view -> gridSamples.filter { it in view } } ?: gridSamples
            val mean = measuredMean(cells) ?: return MeasuredInsight.NoData
            return MeasuredInsight.Data(mean, category.levelOf(mean), cells.size)
        }
        val summary = summarise(category, placesIn, emptyList(), null)
        val mean = summary.mean ?: return MeasuredInsight.NoData
        return MeasuredInsight.Data(mean, category.levelOf(mean), summary.sampleCount)
    }

    val sound = summarise(Category.SOUND, placesIn, gridIn, null, noisePeriod = noisePeriod)
    val light = measured(Category.LIGHT, lightGrid)
    val crowd = measured(Category.CROWD, crowdGrid)
    val access = summarise(Category.ACCESS, placesIn, emptyList(), null)
    val personal = summarise(Category.PERSONAL, placesIn, emptyList(), null, personalScores)
    return AreaInsight(
        personal = PersonalInsight(
            overwhelm = personal.mean?.let { RatedMean(it, Category.PERSONAL.levelOf(it)) },
            meanDb = sound.mean,
            lightLevel = (light as? MeasuredInsight.Data)?.level,
            crowdLevel = (crowd as? MeasuredInsight.Data)?.level,
        ),
        sound = SoundInsight(
            noise = sound.mean?.let { RatedMean(it, Category.SOUND.levelOf(it)) },
            roadMeanDb = sound.roadMeanDb,
            railMeanDb = sound.railMeanDb,
            measuredSoundTypes = sound.measuredSoundTypes,
        ),
        light = light,
        crowd = crowd,
        access = AccessInsight(access.accessShares, access.sampleCount),
        meanFlickerIndex = if (flickerGrid != null) {
            measuredMean(bounds?.let { view -> flickerGrid.filter { it in view } } ?: flickerGrid)
        } else {
            val readings = placesIn.mapNotNull { it.measured?.features?.flickerIndex }
            readings.takeIf { it.isNotEmpty() }?.average()
        },
    )
}

private fun measuredMean(samples: List<HeatSample>): Double? {
    val count = samples.sumOf { it.count.toLong() }
    return if (count > 0) samples.sumOf { it.value * it.count } / count else null
}
