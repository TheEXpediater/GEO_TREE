package com.geotree.app.core.location

/** UI-facing states of a user-initiated location capture. */
sealed interface LocationState {
    /** [denied] is true after the user refused the system dialog. */
    data class PermissionRequired(val denied: Boolean = false) : LocationState
    data object LocationDisabled : LocationState
    data object Idle : LocationState
    data object Acquiring : LocationState
    /** [approximateOnly] means the user granted only coarse location. */
    data class Ready(val fix: GpsFix, val approximateOnly: Boolean) : LocationState
    data class Error(val message: String) : LocationState
}
