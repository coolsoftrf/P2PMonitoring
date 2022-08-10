package ru.coolsoft.p2pcamera;

import static android.content.DialogInterface.BUTTON_NEGATIVE;
import static android.content.DialogInterface.BUTTON_NEUTRAL;
import static android.content.DialogInterface.BUTTON_POSITIVE;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.net.InetSocketAddress;

public class AuthorizationDialogFragment extends DialogFragment {
    public static final String TAG_PREFIX = "autorize.";
    public static final String RESULT_TAG = "authorization";
    public static final String USER_KEY = "user";
    public static final String ADDRESS_KEY = "address";
    public static final String RESULT_KEY = "decision";

    private String userName;
    private InetSocketAddress address;

    private final DialogInterface.OnClickListener onButtonClickListener = (dialog, which) -> {
        Decision decision;
        switch (which) {
            case BUTTON_POSITIVE:
                decision = Decision.ALLOW_ALWAYS;
                break;
            case BUTTON_NEUTRAL:
                decision = Decision.ALLOW;
                break;
            case BUTTON_NEGATIVE:
                decision = Decision.DENY_ALWAYS;
                break;
            default:
                return;
        }
        handleDecision(decision);
    };

    public AuthorizationDialogFragment(String user, InetSocketAddress clientAddress) {
        userName = user;
        address = clientAddress;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            userName = savedInstanceState.getString(USER_KEY);
            address = savedInstanceState.getParcelable(ADDRESS_KEY);
        }
        return new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.authorization_request, userName))
                .setPositiveButton(R.string.allow_always_button, onButtonClickListener)
                .setNeutralButton(R.string.allow_button, onButtonClickListener)
                .setNegativeButton(R.string.deny_always_button, onButtonClickListener)
                .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        handleDecision(Decision.DENY);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString(USER_KEY, userName);
        outState.putSerializable(ADDRESS_KEY, address);
        super.onSaveInstanceState(outState);
    }

    private void handleDecision(Decision decision) {
        Bundle result = new Bundle(1);
        result.putString(USER_KEY, userName);
        result.putSerializable(ADDRESS_KEY, address);
        result.putSerializable(RESULT_KEY, decision);
        getParentFragmentManager().setFragmentResult(RESULT_TAG, result);

        dismiss();
    }

    public enum Decision {
        ALLOW,
        ALLOW_ALWAYS,
        DENY,
        DENY_ALWAYS
    }
}
