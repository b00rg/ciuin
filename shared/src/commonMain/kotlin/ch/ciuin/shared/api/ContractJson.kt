package ch.ciuin.shared.api

import kotlinx.serialization.json.Json

/**
 * The one JSON config for talking to the backend (todo.md §0). Use it in the Ktor client.
 * - encodeDefaults: always send every contract field (e.g. "quiet_room": false, "flicker_hz": null),
 *   otherwise kotlinx.serialization silently drops fields equal to their default.
 * - ignoreUnknownKeys: a newer backend adding fields must not crash older app builds.
 */
val ContractJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
