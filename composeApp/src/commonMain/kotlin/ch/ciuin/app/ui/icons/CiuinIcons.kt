package ch.ciuin.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The 35 Hugeicons (free set, MIT) the design uses: stroke rounded, drawn on a 24 grid with a 1.5 stroke.
 * Path data is copied from the "Icons" board in Paper and keeps the board's names.
 *
 * Draw them with [CiuinIcon], which tints the stroke; the vectors themselves are black.
 */
object CiuinIcons {
    val ArrowLeft01: ImageVector by lazy { hugeicon("ArrowLeft01", "M15 6C15 6 9 10.4 9 12C9 13.6 15 18 15 18") }
    val ArrowRight01: ImageVector by lazy { hugeicon("ArrowRight01", "M9 6C9 6 15 10.4 15 12C15 13.6 9 18 9 18") }
    val ArrowUp01: ImageVector by lazy { hugeicon("ArrowUp01", "M2.5 14.5C2.5 14.5 10.4 9.5 12 9.5C13.6 9.5 21.5 14.5 21.5 14.5") }
    val ArrowDown01: ImageVector by lazy { hugeicon("ArrowDown01", "M2.5 9.5C2.5 9.5 10.4 14.5 12 14.5C13.6 14.5 21.5 9.5 21.5 9.5") }
    val Search01: ImageVector by lazy {
        hugeicon("Search01", "M17 17L21 21M19 11C19 6.6 15.4 3 11 3C6.6 3 3 6.6 3 11C3 15.4 6.6 19 11 19C15.4 19 19 15.4 19 11Z")
    }
    val Mic01: ImageVector by lazy {
        hugeicon(
            "Mic01",
            "M7 6.5C7 4 9 2 11.5 2C14 2 16 4 16 6.5V11.5C16 14 14 16 11.5 16C9 16 7 14 7 11.5V6.5ZM11.5 19H11.1C7.6 19 4.6 16.5 4 13M11.5 19H11.9C15.4 19 18.4 16.5 19 13M11.5 19V22",
        )
    }
    val Navigation03: ImageVector by lazy {
        hugeicon(
            "Navigation03",
            "M11.9 4.8C16.7 3.2 19.1 2.3 20.4 3.6C21.7 4.9 20.8 7.3 19.2 12.1L18.1 15.3C16.9 19 16.2 20.8 15.2 21C14.9 21 14.6 21 14.4 20.9C13.3 20.6 12.8 18.6 11.7 14.8C11.5 13.9 11.4 13.5 11.1 13.2C11 13.1 10.9 13 10.8 12.9C10.5 12.6 10.1 12.5 9.2 12.3C5.4 11.2 3.4 10.7 3.1 9.6C3 9.4 3 9.1 3 8.8C3.2 7.8 5 7.1 8.7 5.9L11.9 4.8Z",
        )
    }
    val Gps01: ImageVector by lazy {
        hugeicon(
            "Gps01",
            "M20.5 12C20.5 16.7 16.7 20.5 12 20.5C7.3 20.5 3.5 16.7 3.5 12C3.5 7.3 7.3 3.5 12 3.5C16.7 3.5 20.5 7.3 20.5 12ZM22.5 12H20.5M3.5 12H1.5M12 1.5V3.5M12 20.5V22.5M15 12C15 13.7 13.7 15 12 15C10.3 15 9 13.7 9 12C9 10.3 10.3 9 12 9C13.7 9 15 10.3 15 12Z",
        )
    }
    val Cancel01: ImageVector by lazy { hugeicon("Cancel01", "M19 19L5 5M19 5L5 19") }
    val Tick02: ImageVector by lazy { hugeicon("Tick02", "M5 14L8.5 17.5L19 6.5") }
    val Add01: ImageVector by lazy { hugeicon("Add01", "M12 4V20M20 12L4 12") }
    val SquareLock02: ImageVector by lazy {
        hugeicon(
            "SquareLock02",
            "M12 14.5V17.5M13 14.5C13 15.1 12.6 15.5 12 15.5C11.4 15.5 11 15.1 11 14.5C11 13.9 11.4 13.5 12 13.5C12.6 13.5 13 13.9 13 14.5ZM16.5 9V6.5C16.5 4 14.5 2 12 2C9.5 2 7.5 4 7.5 6.5V9M4.3 18.8C4.5 20.5 5.9 21.8 7.6 21.9C9 22 10.4 22 12 22C13.6 22 15 22 16.400 21.9C18.1 21.8 19.5 20.5 19.7 18.8C19.9 17.8 20 16.6 20 15.5C20 14.4 19.9 13.2 19.7 12.2C19.5 10.5 18.1 9.2 16.400 9.1C15 9 13.6 9 12 9C10.4 9 9 9 7.6 9.1C5.9 9.2 4.5 10.5 4.3 12.2C4.1 13.2 4 14.4 4 15.5C4 16.6 4.1 17.8 4.3 18.8Z",
        )
    }
    val Camera01: ImageVector by lazy {
        hugeicon(
            "Camera01",
            "M12.7 3.5H11.3C10.6 3.5 10.2 3.5 9.9 3.6C9.7 3.7 9.5 3.8 9.4 3.9C9.1 4.1 8.9 4.4 8.5 5C8.3 5.3 8 5.8 7.9 5.9C7.6 6.2 7.2 6.4 6.8 6.5C6.6 6.5 6.4 6.5 6.1 6.5C5.1 6.5 4.6 6.5 4.2 6.6C3.2 6.9 2.4 7.7 2.1 8.7C2 9.1 2 9.6 2 10.6V14.5C2 17.3 2 18.7 2.9 19.6C3.8 20.5 5.2 20.5 8 20.5H16C18.8 20.5 20.2 20.5 21.1 19.6C22 18.7 22 17.3 22 14.5V10.6C22 9.6 22 9.1 21.9 8.7C21.6 7.7 20.8 6.9 19.8 6.6C19.4 6.5 18.9 6.5 17.9 6.5C17.6 6.5 17.4 6.5 17.2 6.5C16.8 6.4 16.400 6.2 16.1 5.9C16 5.8 15.7 5.3 15.5 5C15.1 4.4 14.9 4.1 14.6 3.9C14.5 3.8 14.3 3.7 14.1 3.6C13.8 3.5 13.4 3.5 12.7 3.5ZM16 13C16 15.2 14.2 17 12 17C9.8 17 8 15.2 8 13C8 10.8 9.8 9 12 9C14.2 9 16 10.8 16 13ZM19.1 9.5H19",
        )
    }
    val Location01: ImageVector by lazy {
        hugeicon(
            "Location01",
            "M13.6 21.4C13.2 21.8 12.6 22 12 22C11.4 22 10.8 21.8 10.4 21.4C6.4 17.6 1.1 13.4 3.7 7.4C5.1 4.1 8.5 2 12 2C15.5 2 18.9 4.1 20.3 7.4C22.9 13.4 17.6 17.6 13.6 21.4ZM15.5 11C15.5 12.9 13.9 14.5 12 14.5C10.1 14.5 8.5 12.9 8.5 11C8.5 9.1 10.1 7.5 12 7.5C13.9 7.5 15.5 9.1 15.5 11Z",
        )
    }
    val Notification02: ImageVector by lazy {
        hugeicon(
            "Notification02",
            "M19 18V9.5C19 5.6 15.9 2.5 12 2.5C8.1 2.5 5 5.6 5 9.5V18M20.5 18H3.5M13.5 20C13.5 20.8 12.8 21.5 12 21.5M10.5 20C10.5 20.8 11.2 21.5 12 21.5M12 21.5V20",
        )
    }
    val Maps: ImageVector by lazy {
        hugeicon(
            "Maps",
            "M5.3 4.2L4 4.9C3 5.5 2.5 5.8 2.3 6.2C2 6.7 2 7.3 2 8.5V16.6C2 18.2 2 18.9 2.3 19.3C2.6 19.6 2.9 19.8 3.2 19.9C3.8 20 4.4 19.6 5.7 18.8C6.6 18.3 7.5 17.8 8.5 17.9C9 18 9.4 18.2 10.4 18.7L14.2 20.6C15 21 15 21 15.9 21H18C19.9 21 20.8 21 21.4 20.4C22 19.8 22 18.8 22 16.9V10.2C22 8.2 22 7.3 21.4 6.7C20.8 6.1 19.9 6.1 18 6.1H15.9C15 6.1 15 6.1 14.2 5.7L10.8 4C9.4 3.3 8.8 3 8 3C7.3 3 6.6 3.4 5.3 4.2ZM8 3V17.5M15 6.5V20.5",
        )
    }
    val AudioWave01: ImageVector by lazy { hugeicon("AudioWave01", "M9 3V21M6 7V17M12 6V18M15 9V15M18 7V17M21 11V13M3 11V13") }
    val User: ImageVector by lazy {
        hugeicon(
            "User",
            "M20 21C19.7 17.3 16.7 14.3 13 14.1L12 14C11.6 14 11.3 14 11 14C7.3 14.2 4.3 17.3 4 21M16 7C16 9.2 14.2 11 12 11C9.8 11 8 9.2 8 7C8 4.8 9.8 3 12 3C14.2 3 16 4.8 16 7Z",
        )
    }
    val SlidersHorizontal: ImageVector by lazy {
        hugeicon("SlidersHorizontal", "M3 12H11M14 5H21M12 19H21M3 5H10M10 5V3M10 5V7M3 19H8M8 19V17M8 19V21M15 12H21M15 12V10M15 12V14")
    }
    val VolumeHigh: ImageVector by lazy {
        hugeicon(
            "VolumeHigh",
            "M14 14.8V9.2C14 6 14 4.5 13.1 4.1C12.1 3.7 11.1 4.8 8.9 7C7.8 8.2 7.1 8.4 5.5 8.4C4.1 8.4 3.4 8.4 2.9 8.8C1.9 9.5 2 10.9 2 12C2 13.1 1.9 14.5 2.9 15.2C3.4 15.6 4.1 15.6 5.5 15.6C7.1 15.6 7.8 15.8 8.9 17C11.1 19.2 12.1 20.3 13.1 19.9C14 19.5 14 18 14 14.8ZM17 9C17.6 9.8 18 10.9 18 12C18 13.1 17.6 14.2 17 15M20 7C21.3 8.4 22 10.1 22 12C22 13.9 21.3 15.6 20 17",
        )
    }
    val Sun03: ImageVector by lazy {
        hugeicon(
            "Sun03",
            "M17 12C17 14.8 14.8 17 12 17C9.2 17 7 14.8 7 12C7 9.2 9.2 7 12 7C14.8 7 17 9.2 17 12ZM12 2V3.5M12 20.5V22M19.1 19.1L18 18M6 6L4.9 4.9M22 12H20.5M3.5 12H2M19.1 4.9L18 6M6 18L4.9 19.1",
        )
    }
    val UserGroup: ImageVector by lazy {
        hugeicon(
            "UserGroup",
            "M18.5 20.5C18.3 17.6 15.8 15.2 12.8 15.1L12 15C11.7 15 11.4 15 11.2 15C8.2 15.2 5.7 17.6 5.5 20.5M15.2 9.2C15.2 11 13.8 12.5 12 12.5C10.2 12.5 8.7 11 8.7 9.2C8.7 7.5 10.2 6 12 6C13.8 6 15.2 7.5 15.2 9.2ZM5.5 8.5C5.2 8 5 7.4 5 6.8C5 5 6.4 3.5 8.2 3.5C8.7 3.5 9.1 3.6 9.5 3.8M18.5 8.5C18.8 8 19 7.4 19 6.8C19 5 17.6 3.5 15.8 3.5C15.3 3.5 14.9 3.6 14.5 3.8M22 18C21.8 15.7 20 13.5 18 13M2 18C2.2 15.7 4 13.5 6 13",
        )
    }
    val Wheelchair: ImageVector by lazy {
        hugeicon(
            "Wheelchair",
            "M13 16.5C13 19.5 10.5 22 7.5 22C4.5 22 2 19.5 2 16.5C2 13.5 4.5 11 7.5 11C10.5 11 13 13.5 13 16.5ZM22 20C22 21.1 21.1 22 20 22C18.9 22 18 21.1 18 20C18 18.9 18.9 18 20 18C21.1 18 22 18.9 22 20ZM13 14H16C16.9 14 17.4 14 17.8 14.1C18.8 14.4 19.6 15.2 19.9 16.2C20 16.6 20 17.1 20 18M5 11V5C5 4.1 5 3.6 4.8 3.2C4.6 2.7 4.3 2.4 3.8 2.2C3.4 2 2.9 2 2 2M5 7H18M16 7V14",
        )
    }
    val Globe02: ImageVector by lazy {
        hugeicon(
            "Globe02",
            "M8 12C8 18 12 22 12 22C12 22 16 18 16 12C16 6 12 2 12 2C12 2 8 6 8 12ZM21 15H3M21 9H3M22 12C22 17.5 17.5 22 12 22C6.5 22 2 17.5 2 12C2 6.5 6.5 2 12 2C17.5 2 22 6.5 22 12Z",
        )
    }
    val WifiOff01: ImageVector by lazy {
        hugeicon(
            "WifiOff01",
            "M8.5 14.5C9.6 13.4 11 12.9 12.5 13M18.5 11.5C17 10.2 15.3 9.3 13.5 9M5.5 11.5C6.6 10.6 7.8 9.9 9 9.5M22 8.5C18.1 5.2 13.7 3.9 9.5 4.7M2 8.5C3.2 7.5 4.5 6.5 5.5 6M2 2L22 22M13.5 18C13.5 18.8 12.8 19.5 12 19.5C11.2 19.5 10.5 18.8 10.5 18C10.5 17.2 11.2 16.5 12 16.5C12.8 16.5 13.5 17.2 13.5 18Z",
        )
    }
    val Clock01: ImageVector by lazy {
        hugeicon("Clock01", "M12 8V12L14 14M22 12C22 17.5 17.5 22 12 22C6.5 22 2 17.5 2 12C2 6.5 6.5 2 12 2C17.5 2 22 6.5 22 12Z")
    }
    val Chip: ImageVector by lazy {
        hugeicon(
            "Chip",
            "M13 20H11C7.7 20 6.1 20 5 19C4 17.9 4 16.3 4 13V11C4 7.7 4 6.1 5 5C6.1 4 7.7 4 11 4H13C16.3 4 17.9 4 19 5C20 6.1 20 7.7 20 11V13C20 16.3 20 17.9 19 19C17.9 20 16.3 20 13 20ZM22 9H20M4 9H2M20 15H22M4 15H2M9 2V4M9 20V22M15 4V2M15 20V22M12 16C13.9 16 14.8 16 15.4 15.4C16 14.8 16 13.9 16 12C16 10.1 16 9.2 15.4 8.6C14.8 8 13.9 8 12 8C10.1 8 9.2 8 8.6 8.6C8 9.2 8 10.1 8 12C8 13.9 8 14.8 8.6 15.4C9.2 16 10.1 16 12 16Z",
        )
    }
    val Shield01: ImageVector by lazy {
        hugeicon(
            "Shield01",
            "M18.7 3.5C16.8 2.6 14.5 2 12 2C9.5 2 7.2 2.6 5.3 3.5C4.4 4 3.9 4.2 3.4 4.9C3 5.6 3 6.3 3 7.7V11.2C3 16.9 7.5 20.1 10.2 21.4C10.9 21.8 11.3 22 12 22C12.7 22 13.1 21.8 13.8 21.4C16.5 20.1 21 16.9 21 11.2V7.7C21 6.3 21 5.6 20.6 4.9C20.1 4.2 19.6 4 18.7 3.5Z",
        )
    }
    val Delete02: ImageVector by lazy {
        hugeicon(
            "Delete02",
            "M18.9 16.4L19.5 6H4.5L5.1 16.4C5.3 19 5.4 20.4 6.3 21.2C7.1 22 8.5 22 11.1 22H12.9C15.5 22 16.9 22 17.7 21.2C18.6 20.4 18.7 19 18.9 16.4ZM16 6H21M16 6L15.4 4.3C15 3.3 14.8 2.8 14.4 2.5C14.3 2.4 14.2 2.3 14.1 2.2C13.6 2 13.1 2 12 2C10.9 2 10.4 2 9.9 2.2C9.8 2.3 9.7 2.4 9.6 2.5C9.2 2.8 9 3.3 8.6 4.3L8 6M16 6H8M3 6H8M9.5 11V17M14.5 11V17",
        )
    }
    val Refresh: ImageVector by lazy {
        hugeicon("Refresh", "M20 2V5.1C20 5.4 19.6 5.6 19.5 5.3C17.6 3.3 15 2 12 2C6.5 2 2 6.5 2 12C2 17.5 6.5 22 12 22C17.5 22 22 17.5 22 12")
    }
    val Walking: ImageVector by lazy {
        hugeicon(
            "Walking",
            "M6 12.5L7.7 9.9C7.9 9.6 8.1 9.4 8.4 9.3L10.6 8C11.2 7.7 11.8 7.7 12.4 8C13.1 8.3 13.5 9 13.7 9.7C14.2 10.8 15.4 12 18 12M13 9L11.8 14.6M10.5 8.5L9.8 11.8C9.6 12.5 9.9 13.3 10.5 13.8L14 16.5L15.5 21M9.5 16L9 17.5L6.5 20.5M15 4.5C15 5.3 14.3 6 13.5 6C12.7 6 12 5.3 12 4.5C12 3.7 12.7 3 13.5 3C14.3 3 15 3.7 15 4.5Z",
        )
    }
    val Timer01: ImageVector by lazy {
        hugeicon(
            "Timer01",
            "M11.1 13.2L8 7L13.4 11.3C14.2 11.9 14.2 13 13.4 13.7C12.7 14.3 11.5 14 11.1 13.2ZM5 4.8C3.1 6.6 2 9.2 2 12C2 17.5 6.5 22 12 22C17.5 22 22 17.5 22 12C22 7.1 18.6 3.1 14 2.2C13.2 2 12.7 1.9 12.4 2.2C12 2.5 12 3 12 4V5",
        )
    }
    val BatteryFull: ImageVector by lazy {
        hugeicon(
            "BatteryFull",
            "M2 12C2 9.2 2 7.8 2.9 6.9C3.8 6 5.2 6 8 6H13C15.8 6 17.2 6 18.1 6.9C19 7.8 19 9.2 19 12C19 14.8 19 16.2 18.1 17.1C17.2 18 15.8 18 13 18H8C5.2 18 3.8 18 2.9 17.1C2 16.2 2 14.8 2 12ZM19 9.5L20 9.7C20.7 9.8 21 9.8 21.3 10C21.6 10.2 21.8 10.4 21.9 10.7C22 11 22 11.3 22 12C22 12.7 22 13 21.9 13.3C21.8 13.6 21.6 13.8 21.3 14C21 14.2 20.7 14.2 20 14.3L19 14.5M6 10V14M9 10V14M12 10V14M15 10V14",
        )
    }
    val Vibrate: ImageVector by lazy {
        hugeicon(
            "Vibrate",
            "M17 15V9C17 6.6 17 5.5 16.3 4.7C15.5 4 14.4 4 12 4C9.6 4 8.5 4 7.7 4.7C7 5.5 7 6.6 7 9V15C7 17.4 7 18.5 7.7 19.3C8.5 20 9.6 20 12 20C14.4 20 15.5 20 16.3 19.3C17 18.5 17 17.4 17 15ZM22 8L20.4 9.2C20.1 9.4 20 9.7 20 9.9C20 10.3 20.2 10.6 20.5 10.7L21.6 11.3C21.8 11.4 22 11.7 22 12C22 12.3 21.8 12.6 21.6 12.7L20.5 13.3C20.2 13.4 20 13.7 20 14.1C20 14.3 20.1 14.6 20.4 14.8L22 16M2 8L3.6 9.2C3.9 9.4 4 9.7 4 9.9C4 10.3 3.8 10.6 3.5 10.7L2.4 11.3C2.2 11.4 2 11.7 2 12C2 12.3 2.2 12.6 2.4 12.7L3.5 13.3C3.8 13.4 4 13.7 4 14.1C4 14.3 3.9 14.6 3.6 14.8L2 16",
        )
    }
    val InformationCircle: ImageVector by lazy {
        hugeicon("InformationCircle", "M12 16V12M12.1 8.2H12M22 12C22 17.5 17.5 22 12 22C6.5 22 2 17.5 2 12C2 6.5 6.5 2 12 2C17.5 2 22 6.5 22 12Z")
    }
    val Alert02: ImageVector by lazy {
        hugeicon(
            "Alert02",
            "M13.9 21H10.1C5.4 21 3.1 21 2.3 19.5C1.4 18 2.6 16 5 12L6.9 8.8C9.2 4.9 10.3 3 12 3C13.7 3 14.8 4.9 17.1 8.8L19 12C21.4 16 22.6 18 21.7 19.5C20.9 21 18.6 21 13.9 21ZM12 9V13M12.1 16.8H12",
        )
    }
    val Flash: ImageVector by lazy {
        hugeicon(
            "Flash",
            "M5.2 11.3L12.2 2.3C12.8 1.6 13.8 2.1 13.8 3V10C13.8 10.5 14.2 11 14.7 11H18.1C18.9 11 19.3 12 18.8 12.7L11.8 21.7C11.2 22.4 10.2 21.9 10.2 21V14C10.2 13.5 9.8 13 9.3 13H5.9C5.1 13 4.7 12 5.2 11.3Z",
        )
    }

    /** Every icon with its board name, in board order. */
    val all: List<Pair<String, ImageVector>> by lazy {
        listOf(
            "ArrowLeft01" to ArrowLeft01, "ArrowRight01" to ArrowRight01, "Search01" to Search01, "Mic01" to Mic01,
            "Navigation03" to Navigation03, "Gps01" to Gps01, "Cancel01" to Cancel01, "Tick02" to Tick02,
            "Add01" to Add01, "SquareLock02" to SquareLock02, "Camera01" to Camera01, "Location01" to Location01,
            "Notification02" to Notification02, "Maps" to Maps, "AudioWave01" to AudioWave01, "User" to User,
            "SlidersHorizontal" to SlidersHorizontal, "VolumeHigh" to VolumeHigh, "Sun03" to Sun03,
            "UserGroup" to UserGroup, "Wheelchair" to Wheelchair, "Globe02" to Globe02, "WifiOff01" to WifiOff01,
            "Clock01" to Clock01, "Chip" to Chip, "Shield01" to Shield01, "Delete02" to Delete02, "Refresh" to Refresh,
            "Walking" to Walking, "Timer01" to Timer01, "BatteryFull" to BatteryFull, "Vibrate" to Vibrate,
            "InformationCircle" to InformationCircle, "Alert02" to Alert02, "Flash" to Flash,
        )
    }
}

private fun hugeicon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
