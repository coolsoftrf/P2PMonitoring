package ru.coolsoft.common.ui;

import android.content.Context;
import android.content.Intent;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

public class GetContentMultipleMimeType extends ActivityResultContracts.GetContent {
    public static final String MIME_TYPE_DELIMITER = ",";

    @NonNull
    public Intent createIntent(@NonNull Context context, @NonNull String input) {
        return super.createIntent(context, "*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, input.split(MIME_TYPE_DELIMITER));
    }
}
