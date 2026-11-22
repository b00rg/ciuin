package ch.ciuin.app.ui.measure

import ch.ciuin.app.Category
import ch.ciuin.app.insight.DistanceUnit
import ch.ciuin.app.insight.categoryLabel
import ch.ciuin.app.insight.distanceTokens
import ch.ciuin.app.measure.MeasurementReview
import ch.ciuin.app.ui.components.Level
import ch.ciuin.app.ui.toUi
import ch.ciuin.shared.camera.FlickerResult
import ch.ciuin.shared.model.Place
import ch.ciuin.shared.model.SoundClass
import kotlin.math.roundToInt

/*
 * The words and numbers of the Measure tab, kept apart from the layouts so they can be tested and so
 * every line stays true to what the phone measures: crowd is a count of nearby Bluetooth devices, the
 * camera only measures light and flicker, and an iPhone's sound level is uncalibrated dBFS that never
 * leaves the phone.
 */

/** "Café · 10 m · 4 measurements". [distanceMeters] is null while the location is unknown. */
internal fun placeCardSubtitle(place: Place, distanceMeters: Double?): String = buildList {
    add(categoryLabel(place.category))
    if (distanceMeters != null && distanceMeters.isFinite() && distanceMeters >= 0) add(distanceText(distanceMeters))
    add(measurementCount(place.measured?.n ?: 0))
}.joinToString(" · ")

internal fun distanceText(meters: Double): String {
    val tokens = distanceTokens(meters)
    return when (tokens.unit) {
        DistanceUnit.METRES -> "${tokens.value} m"
        DistanceUnit.KILOMETRES -> "${tokens.value} km"
    }
}

internal fun measurementCount(n: Int): String = when {
    n <= 0 -> "not measured yet"
    n == 1 -> "1 measurement"
    else -> "$n measurements"
}

internal fun secondsLeft(remaining: Int): String = when {
    remaining <= 0 -> "Almost done"
    remaining == 1 -> "1 second left"
    else -> "$remaining seconds left"
}

/** A whole number, with a real minus sign for dBFS. */
internal fun whole(value: Double): String {
    val n = value.roundToInt()
    return if (n < 0) "−${-n}" else n.toString()
}

internal fun luxText(lux: Double): String {
    val n = lux.roundToInt()
    return if (n >= 10_000) "${n / 1_000}k lux" else "$n lux"
}

internal fun devicesText(n: Int): String = if (n == 1) "1 device" else "$n devices"

/** The dominant sound as a plain phrase, or null when the classifier did not run. */
internal fun soundPhrase(classes: Map<String, Double>?): String? {
    if (classes == null) return null
    val top = classes.maxByOrNull { it.value }?.takeIf { it.value > 0 } ?: return "Mostly quiet"
    return when (SoundClass.fromLabel(top.key)) {
        SoundClass.SPEECH_CROWD -> "Mostly people talking"
        SoundClass.MUSIC -> "Mostly music"
        SoundClass.TRAFFIC -> "Mostly traffic"
        SoundClass.SIREN -> "Mostly sirens"
        SoundClass.CONSTRUCTION -> "Mostly building work"
        SoundClass.ALARM_BEEP -> "Mostly alarms and beeps"
        SoundClass.DOG -> "Mostly dogs barking"
        SoundClass.MACHINERY_HUM -> "Mostly machine hum"
        SoundClass.OTHER, null -> "A mix of sounds"
    }
}

/** One line of the review: what [ch.ciuin.app.ui.components.ReadingRow] draws. */
internal data class ReadingLine(
    val level: Level,
    val title: String,
    val subtitle: String,
    val value: String,
    val valueNote: String? = null,
)

internal const val NO_VALUE = "–"

internal fun soundLine(review: MeasurementReview): ReadingLine {
    val features = review.measurement.features
    val phrase = soundPhrase(features.soundClasses)
    val mean = features.dbMean
    val dbfs = review.dbfs
    return when {
        mean != null -> {
            val level = Category.SOUND.levelOf(mean)
            ReadingLine(
                level = level.toUi(),
                title = "Sound · ${Category.SOUND.levelName(level)}",
                subtitle = phrase?.let { "$it · Estimated loudness" } ?: "Estimated loudness",
                value = "${whole(mean)} dB(A)",
                valueNote = features.dbPeak?.let { "peak ${whole(it)}" },
            )
        }
        // An iPhone's microphone level is uncalibrated: shown here only, never rated or sent.
        dbfs != null -> ReadingLine(
            level = Level.NoData,
            title = "Sound · Local level",
            subtitle = listOfNotNull(
                phrase?.let { "$it · ${review.measurement.modelVariant}" },
                "Uncalibrated level stays here",
            ).joinToString(" · "),
            value = "${whole(dbfs.mean)} dBFS",
            valueNote = "peak ${whole(dbfs.peak)}",
        )
        !review.permissions.microphone -> ReadingLine(Level.NoData, "Sound · No data", "The microphone was off", NO_VALUE)
        phrase != null -> ReadingLine(Level.NoData, "Sound · No level", phrase, NO_VALUE)
        else -> ReadingLine(Level.NoData, "Sound · No data", "Not enough sound to measure", NO_VALUE)
    }
}

internal fun lightLine(review: MeasurementReview): ReadingLine {
    val features = review.measurement.features
    val lux = features.luxMean
    val flicker = review.flicker
    val flickerPhrase = when {
        flicker != null -> flickerCaption(flicker)
        review.permissions.camera -> "Flicker could not be checked"
        else -> "The camera was off, so no flicker check"
    }
    return when {
        lux != null -> {
            val level = Category.LIGHT.levelOf(lux)
            ReadingLine(level.toUi(), "Light · ${Category.LIGHT.levelName(level)}", flickerPhrase, luxText(lux))
        }
        flicker != null -> ReadingLine(
            Level.NoData,
            "Light · Flicker only",
            flickerPhrase,
            NO_VALUE,
            valueNote = if (review.canMeasureLux) null else "no light sensor",
        )
        else -> ReadingLine(
            Level.NoData,
            "Light · No data",
            if (review.canMeasureLux) flickerPhrase else "No light sensor on this phone",
            NO_VALUE,
        )
    }
}

private fun flickerCaption(flicker: FlickerResult): String {
    val peak = flicker.dominantHz?.let { "Flicker at ${flickerDecimal(it, 1)} Hz" } ?: "No periodic flicker detected"
    val index = flickerDecimal(flicker.flickerIndex, 3)
    val range = "${flickerDecimal(flicker.minHz, 1)}–${flickerDecimal(flicker.maxHz, 1)} Hz"
    val framesPerSecond = flickerDecimal(flicker.sampleRateHz, 1)
    return "$peak · Index $index\nDetectable range $range at $framesPerSecond fps. Faster flicker may appear slower."
}

private fun flickerDecimal(value: Double, decimalPlaces: Int): String {
    val scale = if (decimalPlaces == 1) 10 else 1_000
    val scaled = (value * scale).roundToInt()
    return "${scaled / scale}.${(scaled % scale).toString().padStart(decimalPlaces, '0')}"
}

internal fun crowdLine(review: MeasurementReview): ReadingLine {
    val devices = review.measurement.features.nearbyDevices
    return if (devices != null) {
        val level = Category.CROWD.levelOf(devices.toDouble())
        ReadingLine(
            level.toUi(),
            "Crowd · ${Category.CROWD.levelName(level)}",
            "Nearby Bluetooth device count",
            devicesText(devices),
        )
    } else {
        ReadingLine(Level.NoData, "Crowd · No data", "Nearby device count unavailable", NO_VALUE)
    }
}

/** "57 dB · 82 lux · 3 devices": the numbers that were sent, for the tile after sharing. */
internal fun sharedSummary(review: MeasurementReview): String? {
    val features = review.measurement.features
    val parts = buildList {
        features.dbMean?.let { add("${whole(it)} dB") }
        features.luxMean?.let { add(luxText(it)) }
        features.nearbyDevices?.let { add(devicesText(it)) }
        if (isEmpty() && features.flickerIndex != null) {
            add(review.measurement.flickerHz?.let { "${whole(it)} Hz flicker" } ?: "Flicker reading")
        }
        if (isEmpty() && features.soundClasses != null) add("Sound categories")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The duck card's line under "Share it and a duck joins the pond". */
internal fun rewardLine(place: Place?, review: MeasurementReview): String {
    val count = place?.let { "This will be measurement ${(it.measured?.n ?: 0) + 1} here. " }.orEmpty()
    val localSound = if (review.dbfs != null) " The dBFS level stays here." else ""
    return count + "The map gets the readings and their location." + localSound
}
