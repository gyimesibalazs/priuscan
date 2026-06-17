package hu.codingo.priuscan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fejegyseg bekapcsolasakor azonnal indul a service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // csak BOOT_COMPLETED: a LOCKED_BOOT_COMPLETED-hez directBootAware +
        // device-protected storage kellene (a Prefs credential-encrypted), igy
        // az ag ugyis halott volt; feloldas utan ez megbizhatoan elindit
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CanService.start(ctx)
        }
    }
}
