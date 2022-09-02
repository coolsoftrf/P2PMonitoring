package ru.coolsoft.common.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.DialogFragment;

public class ConfirmationDialogFragment extends DialogFragment {
    public static final String DIALOG_CONFIRMATION = "confirm";

    private static final String KEY = "key";
    private static final String TITLE = "title";
    private static final String MESSAGE = "message";

    public ConfirmationDialogFragment(@StringRes int title, @StringRes int message, String key) {
        makeArguments(title, message, key);
    }

    public ConfirmationDialogFragment(@StringRes int title, @StringRes int message) {
        makeArguments(title, message, null);
    }

    private void makeArguments(@StringRes int title, @StringRes int message, String key){
        Bundle args = new Bundle();
        args.putInt(TITLE, title);
        args.putInt(MESSAGE, message);
        args.putString(KEY, key);
        setArguments(args);
    }

    private void setResult(Boolean resultValue) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(Boolean.class.getSimpleName(), resultValue);

        String key = requireArguments().getString(KEY);
        if (key == null) {
            key = DIALOG_CONFIRMATION;
        }
        getParentFragmentManager().setFragmentResult(key, bundle);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        return new AlertDialog.Builder(requireContext())
                .setTitle(args.getInt(TITLE))
                .setMessage(args.getInt(MESSAGE))
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> setResult(true))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> setResult(false))
                .create();
    }
}
