package ru.coolsoft.p2pmonitor;

import static android.media.MediaFormat.KEY_HEIGHT;
import static android.media.MediaFormat.KEY_WIDTH;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static ru.coolsoft.common.Constants.AUTH_DENIED_NOT_ALLOWED;
import static ru.coolsoft.common.Constants.AUTH_DENIED_SERVER_ERROR;
import static ru.coolsoft.common.Constants.AUTH_DENIED_WRONG_CREDENTIALS;
import static ru.coolsoft.common.Constants.CAMERA_AVAILABLE;
import static ru.coolsoft.common.Protocol.MEDIA_BUFFER_SIZE;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ru.coolsoft.common.Command;
import ru.coolsoft.common.Flashlight;
import ru.coolsoft.p2pmonitor.databinding.ActivityMainBinding;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class MainActivity extends AppCompatActivity {
    /**
     * Some older devices need a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;
    private static final String LOG_TAG = "P2PMonitor";
    private final DateFormat datetimeFormat;

    {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG);
        if (format instanceof SimpleDateFormat) {
            String pattern = ((SimpleDateFormat) format).toLocalizedPattern();
            datetimeFormat = new SimpleDateFormat(pattern.replace("ss", "ss.SSS"), Locale.getDefault());
        } else {
            datetimeFormat = format;
        }
    }

    private final Handler mHideHandler = new Handler();
    private TextureView mTextureView;
    private float textureRotation = 0;

    private MediaCodec mCodec = null;
    private final ByteArrayOutputStream mediaStream = new ByteArrayOutputStream(MEDIA_BUFFER_SIZE);

    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mTextureView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
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

    private View mAuthControls;
    private EditText mLogin;
    private EditText mPassword;

    private View mCameraControls;
    private ViewGroup mAvailabilityDependentControls;
    private Button mFlashButton;
    private TextView mTimestamp;

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
    private final Runnable mHideRunnable = this::hideConnectionControls;

    private EditText mAddressEdit;
    private final TextWatcher addressTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

        }

        @Override
        public void afterTextChanged(Editable s) {
            mConnectButton.setEnabled(s.length() > 0);
        }
    };

    private StreamingClient client;

    private final View.OnKeyListener onPasswordEditKeyListener = (v, keyCode, event) -> {
        if (keyCode != KEYCODE_ENTER) {
            return false;
        }
        mPassword.setOnKeyListener(null);

        String login = mLogin.getText().toString();
        String pwd = mPassword.getText().toString();
        for (String str : Arrays.asList(login, pwd)) {
            if (str.isEmpty()) {
                Toast.makeText(this, R.string.empty_credentials, Toast.LENGTH_LONG).show();
                return true;
            }
        }

        client.logIn(login, pwd);
        return true;
    };

    private final StreamingClient.EventListener eventListener = new StreamingClient.EventListener() {

        @Override
        public void onConnected() {
            runOnUiThread(() -> {
                mAuthControls.setVisibility(View.VISIBLE);
                mPassword.setOnKeyListener(onPasswordEditKeyListener);
            });
        }

        @Override
        public void onDisconnected() {
            runOnUiThread(() -> {
                mAuthControls.setVisibility(View.GONE);
                mCameraControls.setVisibility(View.INVISIBLE);
                client = null;
                stopCodec();
                restoreConnectControls();
                showConnectionControls();
            });
        }

        @Override
        public void onAuthorized() {
            runOnUiThread(() -> {
                hideConnectionControls();
                restoreConnectControls();
                mAuthControls.setVisibility(View.GONE);
                mCameraControls.setVisibility(View.VISIBLE);
            });
            client.sendCommand(Command.CAPS, null);
        }

        @Override
        public void onFormat(List<byte[]> csdBuffers) {
            stopCodec();
            startDecoder(csdBuffers);
        }

        @Override
        public void onMedia(byte[] data) {
            ByteBuffer dataBuffer = ByteBuffer.wrap(data);
            Date now = new Date(dataBuffer.getLong());
            MainActivity.this.runOnUiThread(() -> mTimestamp.setText(datetimeFormat.format(now)));
            synchronized (mediaStream) {
                mediaStream.write(data, dataBuffer.position(), dataBuffer.remaining());
                Log.v(LOG_TAG, String.format("written %d bytes of media", data.length));
            }
        }

        @Override
        public void onCommand(Command command, byte[] data) {
            runOnUiThread(() -> {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                int len;

                switch (command) {
                    case FLASHLIGHT:
                        if (checkDataLen(command, null, 1, data)) {
                            int txt;
                            Flashlight mode = Flashlight.byId(data[0]);
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
                            setCameraControlAvailability(mFlashButton, mode != Flashlight.UNAVAILABLE);
                            mFlashButton.setText(txt);
                        }
                        break;
                    case AVAILABILITY:
                        if (!checkDataLen(command, 4, null, data)) {
                            break;
                        }
                        len = buffer.getInt();
                        if (checkDataLen(command, null, 4 + len + 1, data)) {
                            byte[] idBytes = new byte[len];
                            buffer.get(idBytes);
                            String cameraId = new String(idBytes, StandardCharsets.UTF_8);
                            //ToDo: process availability by id
                            setCameraControlsAvailability(buffer.get() == CAMERA_AVAILABLE);
                        }
                        break;
                    case FORMAT: {
                        List<byte[]> csdBuffers = new ArrayList<>();
                        while (buffer.hasRemaining()) {
                            len = buffer.getInt();
                            byte[] csd = new byte[len];
                            buffer.get(csd);
                            csdBuffers.add(csd);
                        }
                        onFormat(csdBuffers);
                        break;
                    }
                    case UNDEFINED:
                        Toast.makeText(MainActivity.this,
                                getString(R.string.unknown_command, data[0]),
                                Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onError(Error situation, Byte aux, Throwable e) {
            String message;
            if (situation == Error.AUTH_ERROR) {
                switch (aux) {
                    case AUTH_DENIED_WRONG_CREDENTIALS:
                        message = getString(R.string.wrong_credentials);
                        break;
                    case AUTH_DENIED_NOT_ALLOWED:
                        message = getString(R.string.access_denied);
                        break;
                    case AUTH_DENIED_SERVER_ERROR:
                    default:
                        message = getString(R.string.sww);
                        break;
                }
            } else {
                message = situation.toString() + ": " + e.getMessage();
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
            switch (situation) {
                case HOST_UNRESOLVED_ERROR:
                case SOCKET_INITIALIZATION_ERROR:
                    terminateSession();
            }
        }

        private boolean checkDataLen(Command command, Integer countAtLeast, Integer countExact, byte[] data) {
            if (data == null
                    || (countAtLeast != null && data.length < countAtLeast)
                    || (countExact != null && data.length != countExact)) {
                Toast.makeText(MainActivity.this,
                        getString(R.string.invalid_command,
                                command.toString(),
                                data == null ? "null" : Integer.toString(data.length)),
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        private void stopCodec() {
            if (mCodec != null) {
                mCodec.stop();
                mCodec.reset();
                mCodec.release();
                mCodec = null;
            }
        }
    };

    private void setCameraControlAvailability(View control, boolean available) {
        control.setTag(R.id.TAG_KEY_AVAILABLE, available);
        if (mAvailabilityDependentControls.isEnabled()) {
            control.setEnabled(available);
        }
    }

    private void setCameraControlsAvailability(boolean available) {
        setViewGroupControlsEnabled(available, mAvailabilityDependentControls);
    }

    private void setViewGroupControlsEnabled(boolean enable, ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof ViewGroup) {
                setViewGroupControlsEnabled(enable, (ViewGroup) child);
            } else {
                if (Boolean.TRUE.equals(child.getTag(R.id.TAG_KEY_AVAILABLE))) {
                    child.setEnabled(enable);
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mVisible = true;
        mControlsView = binding.connectionControls;

        mAddressEdit = binding.addressEdit;
        mAddressEdit.addTextChangedListener(addressTextWatcher);
        mConnectionProgress = binding.connectionProgress;
        mConnectButton = binding.connectButton;

        mAuthControls = binding.authControls;
        mLogin = binding.login;
        mPassword = binding.password;

        mCameraControls = binding.cameraControls;
        mFlashButton = binding.flashButton;
        mAvailabilityDependentControls = binding.availabilityDependentControls;
        mTimestamp = binding.timestamp;

        mTextureView = binding.fullscreenContent;
        ((View) mTextureView.getParent()).addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateTextureLayout(right - left, bottom - top));

        // Set up the user interaction to manually show or hide the system UI.
        mTextureView.setOnClickListener(view -> toggle());
    }

    @Override
    protected void onDestroy() {
        terminateSession();
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

    private void setConnectionControlsVisibility(boolean visible) {
        runOnUiThread(() -> {
            mConnectButton.setVisibility(visible ? View.VISIBLE : View.GONE);
            mConnectionProgress.setVisibility(visible ? View.GONE : View.VISIBLE);
            mAddressEdit.setEnabled(visible);
        });
    }

    private void updateTextureLayout(int parentWidth, int parentHeight) {
        float width, height;
        boolean swap = textureRotation % 180 == 90;
        if (mCodec != null) {
            float w = mCodec.getInputFormat().getInteger(KEY_WIDTH);
            float h = mCodec.getInputFormat().getInteger(KEY_HEIGHT);
            if (swap) {
                width = h;
                height = w;
            } else {
                width = w;
                height = h;
            }
        } else {
            width = mTextureView.getMeasuredWidth();
            height = mTextureView.getMeasuredHeight();
        }

        ViewGroup.LayoutParams layoutParams = mTextureView.getLayoutParams();
        boolean layoutUpdateRequired;
        if ((float) parentWidth / (float) parentHeight > width / height) {
            layoutUpdateRequired = setLayoutParams(layoutParams, (int) (parentHeight / height * width), parentHeight, swap);
        } else {
            layoutUpdateRequired = setLayoutParams(layoutParams, parentWidth, (int) (parentWidth / width * height), swap);
        }
        if (layoutUpdateRequired) {
            mTextureView.setLayoutParams(layoutParams);
            mTextureView.setRotation(textureRotation);
        }
    }

    private boolean setLayoutParams(ViewGroup.LayoutParams params, int width, int height, boolean swap) {
        boolean result = false;
        int w, h;
        if (swap) {
            w = height;
            h = width;
        } else {
            w = width;
            h = height;
        }
        if (params.width != w) {
            params.width = w;
            result = true;
        }
        if (params.height != h) {
            params.height = h;
            result = true;
        }
        return result;
    }

    private synchronized void startDecoder(List<byte[]> csdBuffers) {
        try {
            mCodec = MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC);
        } catch (IOException e) {
            Log.d(LOG_TAG, "Codec missing", e);
            return;
        }
        prepareDecoder(csdBuffers);
    }

    private void prepareDecoder(List<byte[]> csdBuffers) {
        if (mTextureView.isAvailable()) {
            configureCodec(csdBuffers);
        } else {
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    configureCodec(csdBuffers);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                }
            });
        }
    }

    private void configureCodec(List<byte[]> csdBuffers) {
        int width = 640;
        int height = 480;

        MediaFormat format = MediaFormat.createVideoFormat(MIMETYPE_VIDEO_AVC, width, height);
        for (int i = 0; i < csdBuffers.size(); i++) {
            format.setByteBuffer("csd-" + i, ByteBuffer.wrap(csdBuffers.get(i)));
        }

        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        texture.setDefaultBufferSize(width, height);

        Surface decoderSurface = new Surface(texture);
        mCodec.configure(format, decoderSurface, null, 0);

        //texture view isn't resized by MediaCodec, so do it manually
        View textureParent = (View) (mTextureView.getParent());
        updateTextureLayout(textureParent.getMeasuredWidth(), textureParent.getMeasuredHeight());

        mCodec.setCallback(mDecoderCallback);
        mCodec.start();
        Log.i(LOG_TAG, "decoder started");
    }

    private final MediaCodec.Callback mDecoderCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            byte[] b;
            ByteBuffer inputBuffer = mCodec.getInputBuffer(index);
            inputBuffer.clear();
            synchronized (mediaStream) {
                b = mediaStream.toByteArray();
                mediaStream.reset();
            }
            inputBuffer.put(b);

            Log.v(LOG_TAG, String.format("processing %d bytes of media to buffer #%d", b.length, index));
            mCodec.queueInputBuffer(index, 0, b.length, 0, 0);
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            codec.releaseOutputBuffer(index, true);
            Log.v(LOG_TAG, "output buffer released to surface");
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.e(LOG_TAG, "decoder error", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

        }
    };

    private void toggle() {
        if (mVisible) {
            hideConnectionControls();
        } else {
            showConnectionControls();
        }
    }

    private void hideConnectionControls() {
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

    private void showConnectionControls() {
        // Show the system bar
        mTextureView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
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
        String address = mAddressEdit.getText().toString().trim();
        if (client == null) {
            if (address.length() > 0) {
                setConnectionControlsVisibility(false);
                client = new StreamingClient(address, eventListener);
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
        terminateSession();
    }

    public void onCancelConnectionClicked(View view) {
        terminateSession();
    }

    public void onCwClicked(View view) {
        textureRotation = (textureRotation + 90) % 360;
        mTextureView.getParent().requestLayout();
    }

    public void onCcwClicked(View view) {
        textureRotation = (360 + textureRotation - 90) % 360;
        mTextureView.getParent().requestLayout();
    }

    private void restoreConnectControls() {
        runOnUiThread(() -> setConnectionControlsVisibility(true));
    }

    private void terminateSession() {
        if (client != null) {
            client.terminate();
        }
    }
}