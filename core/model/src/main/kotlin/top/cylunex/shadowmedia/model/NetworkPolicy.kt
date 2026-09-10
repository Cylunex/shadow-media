package top.cylunex.shadowmedia.model

/** Process policy is checked before transport, including background refreshes and sync. */
object NetworkPolicy { @Volatile var offlineOnly: Boolean = false }
