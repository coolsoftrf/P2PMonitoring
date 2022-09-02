package ru.coolsoft.p2pcamera.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import ru.coolsoft.p2pcamera.R;

public class PasswordInputDialogFragment extends DialogFragment {
    public static final String RESULT_TAG = "private_key_password";
    public static final String PASSWORD_KEY = "password";

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = getLayoutInflater();
        ViewGroup inputLayout = (ViewGroup) inflater.inflate(R.layout.password_input, null);
        EditText input = (EditText) inputLayout.getChildAt(0);

        return new AlertDialog.Builder(requireContext())
                .setTitle(R.string.title_private_key_password_request)
                .setView(inputLayout)
                .setNegativeButton(android.R.string.cancel, ((dialog, which) -> dialog.cancel()))
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                {
                    Bundle result = new Bundle(1);
                    result.putString(PASSWORD_KEY, input.getText().toString());
                    getParentFragmentManager().setFragmentResult(RESULT_TAG, result);
                })
                .create();
    }
}
