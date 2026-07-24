package com.carlink.navigation

import android.content.Context
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import com.carlink.logging.logNavi
import com.carlink.ui.settings.AdapterConfigPreference
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Shared Trip builder for cluster navigation display.
 *
 * Builds a [Trip] with the current maneuver step and, when the adapter firmware sends
 * a double-maneuver burst (see NavigationStateManager burst-detection window,
 * BURST_THRESHOLD_MS = 50), an additional next step. Trip steps are ordered — the first
 * is what the driver needs to do now, the second is what comes after.
 *
 * Used by both cluster session implementations (ClusterMainSession for GM AAOS,
 * CarlinkClusterSession for non-GM AAOS) so the Trip payload handed to
 * NavigationManager.updateTrip is identical across platforms.
 *
 * ETA caveat: the adapter exposes only a single timeToDestination (seconds to final
 * destination). The same computed `eta` is reused for per-step TravelEstimates, which
 * is semantically incorrect (step ETAs ≠ destination ETA) but accepted because the
 * cluster UIs surface step DISTANCE, not step ETA. See the inline note at the first
 * TravelEstimate construction.
 *
 * No tests. Add coverage if step/ETA semantics change.
 */
object TripBuilder {
    /** Separator between the direction phrase and the road name in the cluster cue. */
    private const val CUE_SEPARATOR = " · "

    /** Feet → meters (remainDistance is in meters; the user-facing threshold is in feet). */
    private const val FEET_TO_METERS = 0.3048

    /**
     * Compose the cluster cue text. When the direction-cue feature is [enabled] and the
     * maneuver is within [thresholdMeters], prepend a short turn-direction phrase (e.g.
     * "Turn left") to the road name so the DIRECTION is conveyed even when the maneuver icon
     * can't reach the cluster (Play builds — see ManeuverMapper.directionText KDoc). Otherwise
     * the road name is shown unmodified.
     *
     * - Feature disabled, or distance above threshold → just the road name (no prefix).
     * - No direction phrase for this maneuver → just the road name.
     * - No road name → just the direction phrase.
     * - Road already starts with the phrase → don't duplicate (guards against firmware that
     *   embeds instruction text in NaviRoadName).
     */
    private fun composeCue(
        cpType: Int,
        turnSide: Int,
        roadName: String?,
        remainDistanceMeters: Int,
        enabled: Boolean,
        thresholdMeters: Int,
    ): String? {
        val road = roadName?.takeIf { it.isNotEmpty() }
        // Only prepend the direction when enabled and the turn is imminent (< threshold).
        if (!enabled || remainDistanceMeters !in 0..thresholdMeters) return road
        // Never prepend when the cue is already an arrival instruction (e.g. Apple Maps'
        // "Arrive on your left/right") — our prefix would be redundant with what the
        // projection already shows for the final maneuver.
        if (road != null && road.trimStart().startsWith("arrive", ignoreCase = true)) return road
        val direction = ManeuverMapper.directionText(cpType, turnSide)
        return when {
            direction == null -> road
            road == null -> direction
            road.startsWith(direction, ignoreCase = true) -> road
            else -> "$direction$CUE_SEPARATOR$road"
        }
    }

    fun buildTrip(
        state: NavigationState,
        context: Context,
    ): Trip {
        val tripBuilder = Trip.Builder()

        // Direction-cue settings — read live from the sync cache (cheap; this runs at most a
        // few times/sec, debounced, off the hot video path). Toggling the setting takes effect
        // on the next cluster update with no reinit.
        val cuePrefs = AdapterConfigPreference.getInstance(context)
        val directionCueEnabled = cuePrefs.getDirectionCueEnabledSync()
        val directionThresholdMeters =
            (cuePrefs.getDirectionCueThresholdFeetSync() * FEET_TO_METERS).roundToInt()

        // Single ETA reused across the current step, the next step, and the destination.
        // Only the destination estimate is actually "arrival time" — step ETAs are the
        // same value because the adapter doesn't expose per-step timing. See class KDoc.
        val eta = ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong()))

        // Primary step = the IMMINENT turn — the maneuver the driver is APPROACHING, not the road
        // they are currently on. state.maneuverType/roadName describe the CURRENT segment; the turn
        // being approached is in state.nextManeuverType/nextRoadName, and state.remainDistance is
        // already the distance to it. Building the primary step from the current segment made the
        // cluster's maneuver text (and icon) lag one maneuver behind the — correct — distance
        // (log-confirmed 2026-07-23: cluster showed the current road "Madison Main NW" while the
        // driver was 9 m from turning onto "Collier Trace NW"; the imminent turn was already in the
        // nextRoadName field). We do NOT shift state.maneuverType itself — ClusterMainSession's
        // arrival detection keys on it, and moving it would fire the arrival timeout a segment
        // early. Fall back to the current maneuver only when there is no upcoming turn (final leg /
        // malformed state), which preserves arrival rendering.
        val hasImminentTurn = state.hasNextStep
        val primaryType = if (hasImminentTurn) state.nextManeuverType!! else state.maneuverType
        val primaryRoad = if (hasImminentTurn) state.nextRoadName else state.roadName
        val primaryExitAngle = if (hasImminentTurn) state.nextExitAngle else state.exitAngle

        val composedIcon =
            com.carlink.navigation.compose.ComposedIconStore.lookup(primaryType, primaryRoad)
        val maneuver =
            ManeuverMapper.buildManeuverForType(
                cpType = primaryType,
                turnSide = state.turnSide,
                context = context,
                composedIcon = composedIcon,
                exitAngle = primaryExitAngle,
            )
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuver)
        composeCue(
            primaryType,
            state.turnSide,
            primaryRoad,
            state.remainDistance,
            directionCueEnabled,
            directionThresholdMeters,
        )?.let { stepBuilder.setCue(it) }

        val stepEstimate =
            TravelEstimate
                .Builder(
                    DistanceFormatter.toDistance(state.remainDistance),
                    eta,
                ).build()

        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        logNavi {
            "[TRIP] Primary step (imminent turn): maneuver=$primaryType, road=$primaryRoad, " +
                "dist=${state.remainDistance}m fallbackToCurrent=${!hasImminentTurn}"
        }

        if (state.destinationName != null || state.distanceToDestination > 0) {
            // Destination.Builder().setName is optional — if destinationName is null
            // we still add a nameless Destination when distanceToDestination > 0 so
            // the cluster has something to render (typical display: "— 0.3 mi").
            val destBuilder = Destination.Builder()
            state.destinationName?.let { destBuilder.setName(it) }

            val destEstimate =
                TravelEstimate
                    .Builder(
                        DistanceFormatter.toDistance(state.distanceToDestination),
                        eta,
                    ).build()

            tripBuilder.addDestination(destBuilder.build(), destEstimate)
        }

        // Not ceremonial: GM's OnStarTurnByTurnManager renders a spinner instead of
        // the turn-card while a Trip is in the loading state. Always set false here.
        tripBuilder.setLoading(false)

        return tripBuilder.build()
    }
}
