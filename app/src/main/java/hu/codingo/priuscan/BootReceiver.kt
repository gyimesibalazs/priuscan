package hu.codingo.priuscan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The service starts immediately when the head unit powers on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // only BOOT_COMPLETED: LOCKED_BOOT_COMPLETED would need directBootAware +
        // device-protected storage (Prefs is credential-encrypted), so that branch
        // was dead anyway; after unlock this starts reliably
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CanService.start(ctx)
        }
    }
}
