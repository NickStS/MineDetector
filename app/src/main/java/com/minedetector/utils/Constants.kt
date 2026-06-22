package com.minedetector.utils

object Constants {
    const val PREFS_NAME = "mine_detector_prefs"
    const val KEY_DISCLAIMER_SHOWN = "disclaimer_shown"
    const val KEY_DETECTION_ENABLED = "detection_enabled"

    const val DATABASE_NAME = "mine_detector_db"
    const val DATABASE_VERSION = 1

    const val NOTIFICATION_CHANNEL_ID = "mine_detector_channel"
    const val NOTIFICATION_CHANNEL_NAME = "Mine Detector Notifications"

    const val MAX_DETECTIONS_IN_MEMORY = 1000

    // Mine types (matching labels.txt order)
    const val MINE_MON50 = 0   // MON-50
    const val MINE_PFM1 = 1   // PFM-1
    const val MINE_PMN1 = 2   // PMN-1
    const val MINE_PMN2 = 3   // PMN-2
    const val MINE_POM3 = 4   // POM-3
    const val MINE_TM62 = 5   // TM-62

    // Labels for display
    val MINE_LABELS = listOf("MON-50", "PFM-1", "PMN-1", "PMN-2", "POM-3", "TM-62")
}
