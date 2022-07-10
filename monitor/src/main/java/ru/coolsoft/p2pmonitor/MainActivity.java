package ru.coolsoft.p2pmonitor;

import static android.media.MediaFormat.KEY_HEIGHT;
import static android.media.MediaFormat.KEY_WIDTH;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static ru.coolsoft.common.Protocol.MEDIA_BUFFER_SIZE;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
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
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    private View mCameraControls;
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
                hideConnectionControls();
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
                stopCodec();
                showConnectionControls();
            });
        }

        private void stopCodec() {
            if (mCodec != null) {
                mCodec.stop();
                mCodec.reset();
                mCodec.release();
                mCodec = null;
            }
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
                    case FORMAT:
                        ByteBuffer buffer = ByteBuffer.wrap(data);
                        List<byte[]> csdBuffers = new ArrayList<>();
                        while (buffer.hasRemaining()) {
                            int len = buffer.getInt();
                            byte[] csd = new byte[len];
                            buffer.get(csd);
                            csdBuffers.add(csd);
                        }
                        onFormat(csdBuffers);
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

    private void updateTextureLayout(int parentWidth, int parentHeight) {
        float width, height;
        if (mCodec != null) {
            width = mCodec.getInputFormat().getInteger(KEY_WIDTH);
            height = mCodec.getInputFormat().getInteger(KEY_HEIGHT);
        } else {
            width = mTextureView.getMeasuredWidth();
            height = mTextureView.getMeasuredHeight();
        }

        ViewGroup.LayoutParams layoutParams = mTextureView.getLayoutParams();
        if ((float) parentWidth / (float) parentHeight > width / height) {
            layoutParams.height = MATCH_PARENT;
            layoutParams.width = (int) (parentHeight / height * width);
        } else {
            layoutParams.width = MATCH_PARENT;
            layoutParams.height = (int) (parentWidth / width * height);
        }
        mTextureView.setLayoutParams(layoutParams);
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
                    return false;
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
        //ToDo: add a UI control to rotate the texture

        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        texture.setDefaultBufferSize(width, height);

        Surface decoderSurface = new Surface(texture);
        mCodec.configure(format, decoderSurface, null, 0);

        //texture view isn't resized by MediaCodec, so do it manually
        View textureParent = (View) (mTextureView.getParent());
        updateTextureLayout(textureParent.getMeasuredWidth(), textureParent.getMeasuredHeight());

        mCodec.setCallback(new DecoderCallback());
        mCodec.start();
        Log.i(LOG_TAG, "decoder started");
    }

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
        //ToDo: handle text editing to enable/disable Connect button
        String address = mAddressEdit.getText().toString().trim();
        if (client == null) {
            if (address.length() > 0) {
                mConnectButton.setVisibility(View.GONE);
                mConnectionProgress.setVisibility(View.VISIBLE);
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
        client.terminate();
    }

    private class DecoderCallback extends MediaCodec.Callback {
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
    }
}