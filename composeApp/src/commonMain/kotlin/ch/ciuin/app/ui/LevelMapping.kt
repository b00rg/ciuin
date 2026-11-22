package ch.ciuin.app.ui

import ch.ciuin.app.ui.components.Level as UiLevel

/** The data layer rates Low / Medium / High or unknown; the design draws Calm / Moderate / Loud / No data. */
internal fun ch.ciuin.app.Level?.toUi(): UiLevel = when (this) {
    ch.ciuin.app.Level.LOW -> UiLevel.Calm
    ch.ciuin.app.Level.MEDIUM -> UiLevel.Moderate
    ch.ciuin.app.Level.HIGH -> UiLevel.Loud
    null -> UiLevel.NoData
}
