package hu.codingo.priuscan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fejegyseg bekapcsolasakor azonnal indul a service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> CanService.start(ctx)
        }
    }
}
