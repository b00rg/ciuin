package ch.ciuin.app.route

import android.content.Context
import android.content.Intent
import android.os.Bundle
import ch.ciuin.app.MainActivity

private const val ROUTE_EXTRA = "ch.ciuin.app.extra.QUIET_PLACE_ROUTE"
private const val ROUTE_ACTION = "ch.ciuin.app.action.SHOW_QUIET_PLACE_ROUTE"
private const val ROUTE_ENTRY = "ch.ciuin.app.QuietPlaceRouteEntry"

internal fun Context.quietPlaceAlertIntent(route: QuietPlaceRoute?): Intent =
    Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (route != null) {
            setClassName(packageName, ROUTE_ENTRY)
            action = ROUTE_ACTION
            putExtra(ROUTE_EXTRA, route.toBundle())
        }
    }

internal fun Intent.quietPlaceRoute(): QuietPlaceRoute? {
    if (action != ROUTE_ACTION || component?.className != ROUTE_ENTRY) return null
    return runCatching { extras?.quietPlaceRoute() }.getOrNull()
}

internal fun Bundle.saveQuietPlaceRoute(route: QuietPlaceRoute?) {
    putBundle(ROUTE_EXTRA, route?.toBundle())
}

internal fun Bundle.quietPlaceRoute(): QuietPlaceRoute? {
    val route = getBundle(ROUTE_EXTRA) ?: return null
    val origin = route.point("origin_lat", "origin_lon") ?: return null
    val destination = route.point("destination_lat", "destination_lon") ?: return null
    val id = route.getString("destination_id")?.takeIf { it.isNotBlank() } ?: return null
    val name = route.getString("destination_name")?.take(512)?.takeIf { it.isNotBlank() } ?: return null
    val distance = distanceMeters(origin, destination)
    if (distance !in 0.0..QUIET_PLACE_RADIUS_METERS.toDouble()) return null
    return QuietPlaceRoute(origin, destination, id, name, distance)
}

private fun QuietPlaceRoute.toBundle(): Bundle = Bundle().apply {
    putDouble("origin_lat", origin.lat)
    putDouble("origin_lon", origin.lon)
    putDouble("destination_lat", destination.lat)
    putDouble("destination_lon", destination.lon)
    putString("destination_id", destinationId)
    putString("destination_name", destinationName.take(512))
}

private fun Bundle.point(latitudeKey: String, longitudeKey: String): RoutePoint? =
    RoutePoint(getDouble(latitudeKey, Double.NaN), getDouble(longitudeKey, Double.NaN)).takeIf { it.isValid }
