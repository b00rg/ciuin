package ch.ciuin.app.core

import ch.ciuin.app.measure.MeasurementController
import ch.ciuin.app.personal.PersonalModelController
import ch.ciuin.app.route.WalkingRouter
import ch.ciuin.shared.api.PlacesApi

/** The app's state and platform capabilities, shared across its screens. */
internal class AppEnvironment(
    val api: PlacesApi,
    val personalModel: PersonalModelController,
    val walkingRouter: WalkingRouter?,
    val settings: SettingsController,
    val permissions: AppPermissions,
    val location: LocationSource,
    val listening: ListeningControl?,
    val alerts: AlertCenter,
    val routes: RouteController,
    val measurement: MeasurementController?,
    val models: ModelCatalog,
)
