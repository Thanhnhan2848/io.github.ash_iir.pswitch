package io.github.ash_iir.pswitch;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String VENDING = "com.android.vending";
    private static final String ACTION_SET = "io.github.ash_iir.pswitch.SET_ACCOUNT";
    private static final String ACTION_REQUEST = "io.github.ash_iir.pswitch.REQUEST_ACCOUNTS";
    private static final String ACTION_ACCOUNTS = "io.github.ash_iir.pswitch.ACCOUNTS";
    private static final String PREFS = "pswitch";

    private final List<String> emails = new ArrayList<String>();
    private ArrayAdapter<String> adapter;
    private TextView status;
    private String current;
    private boolean receiverRegistered;

    private final BroadcastReceiver accountsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String[] arr = intent.getStringArrayExtra("accounts");
            emails.clear();
            if (arr != null) {
                for (String s : arr)
                    if (s != null && s.indexOf('@') > 0 && !emails.contains(s))
                        emails.add(s);
            }
            String fromHook = intent.getStringExtra("current");
            if (fromHook != null && getSharedPreferences(PREFS, 0).getString("target", null) == null)
                current = fromHook;
            updateDisplay();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("PSwitch — Play Store Account");

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(24, 22, 24, 6);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(0xFF666666);
        hint.setPadding(24, 0, 24, 12);
        hint.setText("Tap any account to use it for Play Store + in-app purchases.\n"
                + "No root and no restart needed.");

        Button refresh = new Button(this);
        refresh.setText("Refresh accounts");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
                Toast.makeText(MainActivity.this, "List refreshed", Toast.LENGTH_SHORT).show();
            }
        });

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, new ArrayList<String>()) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                View v = super.getView(pos, convertView, parent);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setTextSize(16);
                    boolean isReset = pos == 0;
                    boolean isUsed = !isReset && emails.size() > pos - 1
                            && emails.get(pos - 1).equals(current);
                    tv.setTextColor(isReset ? 0xFF888888
                            : (isUsed ? 0xFF0B8043 : 0xFF222222));
                }
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                apply(pos == 0 ? null : emails.get(pos - 1));
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.addView(status);
        root.addView(hint);
        // Button above the list so it never hides behind the navigation bar.
        root.addView(refresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (!hasPermission(Manifest.permission.GET_ACCOUNTS))
            askPermission(Manifest.permission.GET_ACCOUNTS);

        registerAccountsReceiver();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(accountsReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private void registerAccountsReceiver() {
        try {
            IntentFilter f = new IntentFilter(ACTION_ACCOUNTS);
            try {
                java.lang.reflect.Method m = Activity.class.getMethod(
                        "registerReceiver", BroadcastReceiver.class,
                        IntentFilter.class, int.class);
                m.invoke(this, accountsReceiver, f, 2); // RECEIVER_EXPORTED
            } catch (Throwable old) {
                registerReceiver(accountsReceiver, f);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // Called by the framework on API 23+; kept reflection-friendly for the
    // old compile-time android jar used by this build.
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        refresh();
    }

    private boolean hasPermission(String perm) {
        try {
            java.lang.reflect.Method m = Activity.class.getMethod("checkSelfPermission", String.class);
            return ((Integer) m.invoke(this, perm)) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    private void askPermission(String perm) {
        try {
            java.lang.reflect.Method m = Activity.class.getMethod(
                    "requestPermissions", String[].class, int.class);
            m.invoke(this, new Object[]{new String[]{perm}, 1});
        } catch (Throwable ignored) {
        }
    }

    /** Asks the Play Store hook for the real account list (root-free). */
    private void refresh() {
        SharedPreferences sp = getSharedPreferences(PREFS, 0);
        String saved = sp.getString("target", null);
        if (saved != null)
            current = saved;
        else if (current == null)
            current = sp.getString("hookCurrent", null);

        // Last list cached by the hook receiver shows instantly, even if
        // Play Store was killed in the meantime.
        if (emails.isEmpty()) {
            Set<String> cached = sp.getStringSet("accounts", null);
            if (cached != null)
                emails.addAll(cached);
        }

        // Local API fallback; Play Store hook normally supplies the full list.
        if (emails.isEmpty()) {
            Set<String> found = new LinkedHashSet<String>();
            for (Account a : AccountManager.get(this).getAccountsByType("com.google"))
                found.add(a.name);
            emails.addAll(found);
        }

        Intent req = new Intent(ACTION_REQUEST);
        req.setPackage(VENDING);
        sendBroadcast(req);

        updateDisplay();
    }

    private void updateDisplay() {
        if (current != null && !emails.contains(current))
            emails.add(0, current);

        status.setText(current == null
                ? "Using now: Play Store default"
                : "Using now: " + current);

        final List<String> display = new ArrayList<String>();
        display.add("↺  Reset — use Play Store default");
        for (String e : emails)
            display.add((e.equals(current) ? "✓  " : "•  ") + e);
        adapter.clear();
        adapter.addAll(display);
        adapter.notifyDataSetChanged();
    }

    private void apply(String email) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, 0).edit();
        if (email == null)
            e.remove("target");
        else
            e.putString("target", email);
        e.putLong("version", System.currentTimeMillis());
        e.apply();

        // Instant update for a running Play Store process.
        Intent i = new Intent(ACTION_SET);
        i.setPackage(VENDING);
        i.putExtra("email", email);
        sendBroadcast(i);

        Toast.makeText(this, email == null
                        ? "Reset to Play Store default"
                        : "Now using " + email,
                Toast.LENGTH_SHORT).show();
        refresh();
    }
}
