package ch.ciuin.shared

actual fun platformName(): String = "Desktop (JVM ${System.getProperty("java.version")})"
