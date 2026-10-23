package dev.avery.muon.entryprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Disposable foreign-UID fixture. It refuses an exported target instead of operating a vulnerable app. */
public final class ProbeActivity extends Activity {
    private static final String TARGET = "dev.avery.muon";
    private static final ComponentName SERVICE = new ComponentName(TARGET, "dev.avery.muon.PlaybackService");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView output;
    private Button run;
    private ServiceConnection connection;
    private MediaBrowser browser;
    private boolean bindingAttempted;
    private boolean active;
    private final Runnable finish = () -> {
        if (active) {
            emit("window.complete; no callback is not a proven denial");
            cleanup();
            run.setEnabled(true);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad + (int) (32 * getResources().getDisplayMetrics().density), pad, pad);
        output = new TextView(this);
        output.setTextSize(16);
        output.setText("Muon QA probe — private Canary service only.\nNo network, storage or elevated permissions.\n");
        run = new Button(this);
        run.setText("Run private-service checks");
        run.setOnClickListener(v -> probe());
        layout.addView(run);
        layout.addView(output);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private void emit(String message) {
        Log.i("MuonEntryProbe", message);
        output.append(message + "\n");
    }

    private void probe() {
        cleanup();
        output.setText("Muon QA probe\n");
        try {
            ServiceInfo target = getPackageManager().getServiceInfo(SERVICE, 0);
            int ownUid = getApplicationInfo().uid;
            emit("uid.helper=" + ownUid + "; uid.target=" + target.applicationInfo.uid
                    + "; exported=" + target.exported + "; targetSdk=" + target.applicationInfo.targetSdkVersion);
            if (target.exported || !target.enabled || !target.applicationInfo.enabled
                    || ownUid == target.applicationInfo.uid) {
                emit("REFUSED: requires enabled private target and distinct UID");
                return;
            }
        } catch (Exception error) {
            emit("REFUSED: target inspection " + error.getClass().getSimpleName());
            return;
        }
        active = true;
        run.setEnabled(false);
        Intent media = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(SERVICE);
        media.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        try {
            ComponentName started = startService(media);
            emit("start.REACHED=" + (started != null) + "; unexpected result needs investigation");
        } catch (SecurityException error) {
            emit("start.SecurityException: " + error.getMessage());
        } catch (RuntimeException error) {
            emit("start.INCONCLUSIVE: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (active) emit("bind.CONNECTED: unexpected result");
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (active) emit("bind.disconnected");
            }
            @Override public void onNullBinding(ComponentName name) {
                if (active) emit("bind.nullBinding: not proof of private-service denial");
            }
            @Override public void onBindingDied(ComponentName name) {
                if (active) emit("bind.died: not proof of private-service denial");
            }
        };
        try {
            bindingAttempted = true;
            boolean accepted = bindService(new Intent().setComponent(SERVICE), connection, BIND_AUTO_CREATE);
            emit("bind.return=" + accepted + "; false alone is inconclusive");
        } catch (SecurityException error) {
            emit("bind.SecurityException: " + error.getMessage());
        } catch (RuntimeException error) {
            emit("bind.INCONCLUSIVE: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        browser = new MediaBrowser(this, SERVICE, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                if (active) emit("browser.CONNECTED: unexpected result; no transport commands sent");
            }
            @Override public void onConnectionFailed() {
                if (active) emit("browser.connectionFailed: attribution requires platform logs");
            }
            @Override public void onConnectionSuspended() {
                if (active) emit("browser.suspended: inconclusive");
            }
        }, null);
        try { browser.connect(); }
        catch (SecurityException error) { emit("browser.SecurityException: " + error.getMessage()); }
        catch (RuntimeException error) { emit("browser.INCONCLUSIVE: " + error.getClass().getSimpleName()); }
        handler.postDelayed(finish, 3000);
    }

    private void cleanup() {
        active = false;
        handler.removeCallbacks(finish);
        if (browser != null) {
            browser.disconnect();
            browser = null;
        }
        if (connection != null && bindingAttempted) {
            // Context requires unbind even after a false return; a thrown bind may have no registration.
            try { unbindService(connection); }
            catch (IllegalArgumentException expected) { Log.i("MuonEntryProbe", "cleanup.noBindingRegistration"); }
        }
        connection = null;
        bindingAttempted = false;
    }

    @Override public void onStop() {
        cleanup();
        if (run != null) run.setEnabled(true);
        super.onStop();
    }
}
