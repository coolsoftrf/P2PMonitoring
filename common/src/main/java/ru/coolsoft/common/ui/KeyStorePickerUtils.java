package ru.coolsoft.common.ui;

import static ru.coolsoft.common.ui.GetContentMultipleMimeType.MIME_TYPE_DELIMITER;

import android.net.Uri;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.fragment.app.Fragment;

public class KeyStorePickerUtils {
    public static ActivityResultLauncher<String> registerKeyStorePickerForResult(
            Fragment fragment, ActivityResultCallback<Uri> callback) {
        return fragment.registerForActivityResult(new GetContentMultipleMimeType(), callback);
    }

    public static void launchKeyStorePickerForResult(ActivityResultLauncher<String> launcher) {
        launcher.launch(String.join(MIME_TYPE_DELIMITER, "application/x-pem-file", "application/x-pkcs12"));
    }
}
