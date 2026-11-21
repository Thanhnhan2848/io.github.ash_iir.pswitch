package io.github.ash_iir.pswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Keeps the last account list even when Play Store is not running. */
public class AccountsCacheReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String[] arr = intent.getStringArrayExtra("accounts");
        if (arr == null || arr.length == 0)
            return;
        Set<String> names = new HashSet<String>(Arrays.asList(arr));
        SharedPreferences.Editor e = context.getSharedPreferences("pswitch", 0).edit();
        e.putStringSet("accounts", names);
        String current = intent.getStringExtra("current");
        if (current != null)
            e.putString("hookCurrent", current);
        e.apply();
    }
}
