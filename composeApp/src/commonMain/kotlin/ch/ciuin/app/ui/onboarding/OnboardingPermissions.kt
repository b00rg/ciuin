package ch.ciuin.app.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ciuin.app.core.AppEnvironment
import ch.ciuin.app.core.AppPermission
import ch.ciuin.app.core.PermissionStatus
import ch.ciuin.app.ui.characters.Sheep
import ch.ciuin.app.ui.characters.SheepPose
import ch.ciuin.app.ui.components.ListRow
import ch.ciuin.app.ui.components.PermissionRow
import ch.ciuin.app.ui.components.TitleBlock
import ch.ciuin.app.ui.components.WoolCard
import ch.ciuin.app.ui.components.WoolCardStyle
import ch.ciuin.app.ui.icons.CiuinIcon
import ch.ciuin.app.ui.icons.CiuinIcons
import ch.ciuin.app.ui.theme.CiuinColors
import ch.ciuin.app.ui.theme.CiuinType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OnboardingPermissions(environment: AppEnvironment) {
    val scope = rememberCoroutineScope()
    val permissions = environment.permissions
    var requesting by remember { mutableStateOf<AppPermission?>(null) }
    var requestFailed by remember { mutableStateOf<AppPermission?>(null) }
    Text("Measuring happens on your phone.", Modifier.semantics { heading() }, style = CiuinType.ScreenTitle)
    WoolCard(WoolCardStyle.Privacy) {
        Sheep(SheepPose.Rest, Modifier.size(96.dp, 88.dp), contentDescription = null, zzz = false)
        TitleBlock(
            title = "Share readings from your phone",
            modifier = Modifier.weight(1f),
            subtitle = "Ciúin shares numeric readings and where they were taken. Audio, pictures and your taps stay here.",
            titleStyle = CiuinType.Subtitle,
            gap = 4.dp,
        )
    }
    Column {
        for ((index, row) in PermissionRows.withIndex()) {
            val status = permissions.status(row.permission)
            val divider = index != PermissionRows.lastIndex
            if (status == PermissionStatus.UNAVAILABLE || requesting == row.permission) {
                PermissionStatusRow(row, divider, if (status == PermissionStatus.UNAVAILABLE) "Unavailable" else "Waiting…")
            } else {
                PermissionRow(
                    row.icon,
                    row.title,
                    when {
                        requestFailed == row.permission -> "Couldn't request access. Try again."
                        status == PermissionStatus.DENIED -> "You can allow access in system settings."
                        else -> row.subtitle
                    },
                    granted = status == PermissionStatus.GRANTED,
                    onAllow = {
                        if (requesting == null) {
                            requesting = row.permission
                            requestFailed = null
                            scope.launch {
                                try {
                                    permissions.request(row.permission)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    requestFailed = row.permission
                                } finally {
                                    requesting = null
                                }
                            }
                        }
                    },
                    divider = divider,
                )
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(row: PermissionCopy, divider: Boolean, status: String) {
    ListRow(
        verticalPadding = 12.dp,
        gap = 14.dp,
        divider = divider,
        dividerColor = CiuinColors.HairlineStrong,
        leadingLane = 40.dp,
        leading = { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { CiuinIcon(row.icon, null) } },
        trailing = {
            Box(Modifier.widthIn(min = 92.dp), contentAlignment = Alignment.Center) {
                Text(status, style = CiuinType.Chip, color = CiuinColors.SecondaryText)
            }
        },
    ) {
        TitleBlock(row.title, subtitle = row.subtitle, titleStyle = CiuinType.RowTitle, subtitleStyle = CiuinType.Caption)
    }
}

private data class PermissionCopy(
    val permission: AppPermission,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

private val PermissionRows = listOf(
    PermissionCopy(AppPermission.MICROPHONE, CiuinIcons.Mic01, "Microphone", "Loudness, on this phone."),
    PermissionCopy(AppPermission.CAMERA, CiuinIcons.Camera01, "Camera", "Light and flicker."),
    PermissionCopy(AppPermission.LOCATION, CiuinIcons.Location01, "Location", "Quiet places near you."),
)
