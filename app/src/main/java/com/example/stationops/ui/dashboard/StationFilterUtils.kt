package com.example.stationops.ui.dashboard

import com.example.stationops.data.model.Station

/**
 * Filters [stations] by [query] against the station name.
 *
 * - Matching is case-insensitive and ignores leading/trailing whitespace.
 * - Returns the original list unchanged when [query] is blank, so the
 *   caller never needs a null-check or an empty-string guard.
 *
 * Kept as a top-level function (not inside the ViewModel) so it can be
 * unit-tested without any Android framework dependencies.
 */
fun filterStationsByQuery(stations: List<Station>, query: String): List<Station> {
    if (query.isBlank()) return stations
    val trimmed = query.trim()
    return stations.filter { station ->
        station.name.contains(trimmed, ignoreCase = true)
    }
}