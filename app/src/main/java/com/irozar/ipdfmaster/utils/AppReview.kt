package com.irozar.ipdfmaster.utils

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Lightweight in-app review "engine". Call [recordSuccess] after a positive action
 * (a PDF saved, scanned, or exported). After a couple of successes it triggers Google
 * Play's official in-app review flow once, so we ask happy users at a good moment
 * without ever nagging.
 */
object AppReview {
    private const val PREFS = "app_review_prefs"
    private const val KEY_COUNT = "success_count"
    private const val KEY_DONE = "review_requested"
    private const val THRESHOLD = 2   // ask after the 2nd successful action

    fun recordSuccess(activity: Activity?) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        val count = prefs.getInt(KEY_COUNT, 0) + 1
        prefs.edit().putInt(KEY_COUNT, count).apply()
        if (count < THRESHOLD) return

        val manager = ReviewManagerFactory.create(act)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // Whether or not Play actually shows the card (it has quotas), we asked — don't repeat.
                manager.launchReviewFlow(act, task.result).addOnCompleteListener {
                    prefs.edit().putBoolean(KEY_DONE, true).apply()
                }
            }
        }
    }
}
