package ru.coolsoft.p2pmonitor;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.Flashlight;
import ru.coolsoft.p2pmonitor.databinding.ActivityMainBinding;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class MainActivity extends AppCompatActivity {
    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;
    private final Handler mHideHandler = new Handler();
    private View mContentView;

    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };
    private View mControlsView;
    private View mConnectButton;
    private View mConnectionProgress;
    private View mCameraControls;
    private Button mFlashButton;
    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
            if (client == null) {
                mControlsView.setVisibility(View.VISIBLE);
            }
        }
    };
    private boolean mVisible;
    private final Runnable mHideRunnable = this::hide;

    private EditText mAddressEdit;
    private StreamingClient client;
    private final StreamingClient.EventListener eventListener = new StreamingClient.EventListener() {
        private void restoreConnectControls() {
            runOnUiThread(() -> {
                mConnectButton.setVisibility(View.VISIBLE);
                mConnectionProgress.setVisibility(View.GONE);
            });
        }

        @Override
        public void onConnected() {
            runOnUiThread(() -> {
                hide();
                restoreConnectControls();
                mCameraControls.setVisibility(View.VISIBLE);
                client.sendCommand(Command.CAPS, null);
            });
        }

        @Override
        public void onDisconnected() {
            runOnUiThread(() -> {
                mCameraControls.setVisibility(View.INVISIBLE);
                client = null;
                show();
            });
        }

        @Override
        public void onCommand(Command command, byte[] data) {
            runOnUiThread(() -> {
                switch (command) {
                    case FLASHLIGHT:
                        if (data == null || data.length != 1) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.invalid_command,
                                            command.toString(),
                                            data == null ? "null" : Integer.toString(data.length)),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int txt;
                        Flashlight mode = Flashlight.getById(data[0]);
                        switch (mode) {
                            case OFF:
                                txt = R.string.torch_off_button;
                                break;
                            case ON:
                                txt = R.string.torch_on_button;
                                break;
                            case UNAVAILABLE:
                                txt = R.string.torch_unavailable;
                                break;
                            default:
                                Toast.makeText(MainActivity.this,
                                        getString(R.string.malformed_command,
                                                command.toString(),
                                                Integer.toString(data[0])),
                                        Toast.LENGTH_SHORT).show();
                                return;
                        }
                        mFlashButton.setEnabled(mode != Flashlight.UNAVAILABLE);
                        mFlashButton.setText(txt);
                        break;
                    default:
                        Toast.makeText(MainActivity.this, getString(R.string.unknown_command, command.aux), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onError(Error situation, Throwable e) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, situation.toString() + ": " + e.getMessage(), Toast.LENGTH_LONG).show());
            switch (situation) {
                case HOST_UNRESOLVED_ERROR:
                case SOCKET_INITIALIZATION_ERROR:
                    restoreConnectControls();
                    client.terminate();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mVisible = true;
        mControlsView = binding.connectionControls;

        mAddressEdit = binding.addressEdit;
        mConnectionProgress = binding.connectionProgress;
        mConnectButton = binding.connectButton;

        mCameraControls = binding.cameraControls;
        mFlashButton = binding.flashButton;

        mContentView = binding.fullscreenContent;

        // Set up the user interaction to manually show or hide the system UI.
        mContentView.setOnClickListener(view -> toggle());
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.terminate();
        }
        super.onDestroy();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide();
    }

    private void toggle() {
        if (mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        if (client != null) {
            mControlsView.setVisibility(View.GONE);
        }
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    private void show() {
        // Show the system bar
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in delay milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide() {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, 100);
    }

    public void onConnectClicked(View view) {
        //ToDo: handle text editing to enable/disable Connect button
        String address = mAddressEdit.getText().toString().trim();
        if (client == null) {
            if (address.length() > 0) {
                mConnectButton.setVisibility(View.GONE);
                mConnectionProgress.setVisibility(View.VISIBLE);
                client = new StreamingClient(address, /*getMainLooper(),*/ eventListener);
                client.start();
            } else {
                Toast.makeText(this, R.string.address_null, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, R.string.already_running, Toast.LENGTH_SHORT).show();
        }
    }

    public void onFlashClicked(View view) {
        client.sendCommand(Command.FLASHLIGHT, null);
    }

    public void onDisconnectClicked(View view) {
        client.terminate();
    }
}