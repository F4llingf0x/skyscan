package com.skyscan.domain;

/** Mission lifecycle states. Transitions are enforced by {@link com.skyscan.mission.MissionStateMachine}. */
public enum MissionState {
    IDLE,
    AREA_SELECTED,
    CRUDE_MISSION_UPLOADED,
    CRUDE_FLYING,
    CRUDE_LANDED,
    PREVIEW_READY,
    DETAIL_SELECTED,
    DETAIL_MISSION_UPLOADED,
    DETAIL_FLYING,
    DETAIL_LANDED,
    PROCESSING,
    COMPLETE,
    RETURN_TO_HOME,
    ABORTED,
    FAILED
}
