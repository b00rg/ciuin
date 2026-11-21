package ch.ciuin.shared

import platform.UIKit.UIDevice

actual fun platformName(): String = "iOS ${UIDevice.currentDevice.systemVersion}"
