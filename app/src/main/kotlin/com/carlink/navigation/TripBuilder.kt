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

        // Current step
        val maneuver = ManeuverMapper.buildManeuver(state, context)
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuver)
        composeCue(
            state.maneuverType,
            state.turnSide,
            state.roadName,
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

        // Next step — from firmware double-maneuver burst
        if (state.hasNextStep) {
            val nextManeuver =
                ManeuverMapper.buildManeuverForType(
                    state.nextManeuverType!!,
                    state.turnSide,
                    context,
                    exitAngle = state.nextExitAngle,
                )
            val nextStepBuilder = Step.Builder()
            nextStepBuilder.setManeuver(nextManeuver)
            // Next-step is the maneuver AFTER the imminent one — always farther than the
            // 0.5 mi prefix window — so it shows the plain road name (no direction prefix).
            state.nextRoadName?.let { nextStepBuilder.setCue(it) }

            // No meaningful distance to the next-next maneuver — use destination estimate
            // as a placeholder. The cluster primarily shows the current step's distance;
            // the next step is a preview (icon + road name).
            val nextStepEstimate =
                TravelEstimate
                    .Builder(
                        DistanceFormatter.toDistance(state.distanceToDestination),
                        eta,
                    ).build()

            tripBuilder.addStep(nextStepBuilder.build(), nextStepEstimate)

            logNavi {
                "[TRIP] Next step added: maneuver=${state.nextManeuverType}, road=${state.nextRoadName}"
            }
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
