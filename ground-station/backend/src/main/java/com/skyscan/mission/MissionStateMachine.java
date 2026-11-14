package com.skyscan.mission;

import com.skyscan.domain.MissionState;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.skyscan.domain.MissionState.*;

/**
 * Hand-rolled, explicit mission state machine (clean equivalent of Spring Statemachine
 * for this size of lifecycle). All state writes go through {@link MissionService},
 * which consults this transition table; illegal transitions throw.
 */
@Component
public class MissionStateMachine {

    private static final Map<MissionState, Set<MissionState>> ALLOWED = new EnumMap<>(MissionState.class);
    static {
        ALLOWED.put(IDLE,                    EnumSet.of(AREA_SELECTED));
        ALLOWED.put(AREA_SELECTED,           EnumSet.of(CRUDE_MISSION_UPLOADED, FAILED));
        ALLOWED.put(CRUDE_MISSION_UPLOADED,  EnumSet.of(CRUDE_FLYING, FAILED, RETURN_TO_HOME));
        ALLOWED.put(CRUDE_FLYING,            EnumSet.of(CRUDE_LANDED, RETURN_TO_HOME, FAILED));
        // PROCESSING directly from CRUDE_LANDED = single-pass mission types
        // (e.g. WILDLIFE_SURVEY: animals move, so there is no detail pass).
        ALLOWED.put(CRUDE_LANDED,            EnumSet.of(PREVIEW_READY, PROCESSING, FAILED));
        // PREVIEW_READY -> CRUDE_MISSION_UPLOADED = detection-rescan loop:
        // the operator sends the drone back over a sub-area to find plants the
        // first pass missed; new detections MERGE into the existing map, and
        // the loop repeats until the operator verifies coverage.
        ALLOWED.put(PREVIEW_READY,           EnumSet.of(DETAIL_SELECTED, CRUDE_MISSION_UPLOADED, FAILED));
        ALLOWED.put(DETAIL_SELECTED,         EnumSet.of(DETAIL_MISSION_UPLOADED, FAILED));
        ALLOWED.put(DETAIL_MISSION_UPLOADED, EnumSet.of(DETAIL_FLYING, FAILED, RETURN_TO_HOME));
        ALLOWED.put(DETAIL_FLYING,           EnumSet.of(DETAIL_LANDED, RETURN_TO_HOME, FAILED));
        ALLOWED.put(DETAIL_LANDED,           EnumSet.of(PROCESSING, FAILED));
        ALLOWED.put(PROCESSING,              EnumSet.of(COMPLETE, FAILED));
        ALLOWED.put(RETURN_TO_HOME,          EnumSet.of(ABORTED, FAILED));
        ALLOWED.put(COMPLETE,                EnumSet.of(DETAIL_SELECTED));   // rescan: select a new zone on the delivered cloud
        ALLOWED.put(ABORTED,                 EnumSet.noneOf(MissionState.class));
        ALLOWED.put(FAILED,                  EnumSet.noneOf(MissionState.class));
    }

    public boolean canTransition(MissionState from, MissionState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public void assertTransition(MissionState from, MissionState to) {
        if (!canTransition(from, to))
            throw new IllegalStateException("Illegal mission transition " + from + " -> " + to);
    }

    /** Flight-abort is only meaningful from these states. */
    public boolean isAbortable(MissionState s) {
        return s == CRUDE_MISSION_UPLOADED || s == CRUDE_FLYING
            || s == DETAIL_MISSION_UPLOADED || s == DETAIL_FLYING;
    }
}
