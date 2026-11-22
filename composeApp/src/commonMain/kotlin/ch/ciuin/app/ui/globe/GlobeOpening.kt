package ch.ciuin.app.ui.globe

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.ciuin.app.core.AppEnvironment

/** The opening screen: a dotted globe with a spike per measured cell. [onEnterMap] after "Fly to Lausanne". */
@Composable
internal fun GlobeOpening(environment: AppEnvironment, onEnterMap: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier)
}
