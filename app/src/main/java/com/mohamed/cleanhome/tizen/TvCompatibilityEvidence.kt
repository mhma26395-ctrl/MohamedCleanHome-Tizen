package com.mohamed.cleanhome.tizen

/**
 * Conservative compatibility-profile gate.
 * A profile is not considered approved merely because network/SDB handshake succeeds.
 */
data class TvCompatibilityEvidence(
    val modelName: String?,
    val firmware: String?,
    val architecture: String?,
    val accountIdentity: String?,
    val nativeInspectionVerified: Boolean,
    val restorationRehearsed: Boolean,
    val physicalHomeChangeRehearsed: Boolean
) {
    fun eligibilitySummary(): String {
        val missing = mutableListOf<String>()
        if (modelName.isNullOrBlank()) missing += "TV model"
        if (firmware.isNullOrBlank()) missing += "firmware"
        if (architecture.isNullOrBlank()) missing += "architecture"
        if (accountIdentity.isNullOrBlank()) missing += "account identity"
        if (!nativeInspectionVerified) missing += "verified read-only native inspection"
        if (!restorationRehearsed) missing += "tested recovery/restoration"
        if (!physicalHomeChangeRehearsed) missing += "physical-TV Home-change rehearsal"
        return if (missing.isEmpty()) {
            "Evidence checklist complete. This is not automatic authorization; profile review is still required."
        } else {
            "Home-change eligibility is blocked. Missing evidence: " + missing.joinToString(", ")
        }
    }
}
