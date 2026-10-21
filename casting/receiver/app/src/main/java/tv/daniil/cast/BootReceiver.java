package tv.daniil.cast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts listening for casts when the TV turns on, so the app doesn't need opening first. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            CastService.start(context);
        }
    }
}
