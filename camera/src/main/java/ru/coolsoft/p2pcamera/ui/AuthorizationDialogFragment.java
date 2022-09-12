package ru.coolsoft.p2pcamera.ui;

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

import ru.coolsoft.p2pcamera.R;

public class AuthorizationDialogFragment extends DialogFragment {
    public static final String TAG_PREFIX = "autorize.";
    public static final String RESULT_TAG = "authorization";
    public static final String USER_KEY = "user";
    public static final String ADDRESS_KEY = "address";
    public static final String RESULT_KEY = "decision";

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
        Bundle args = new Bundle(2);
        args.putString(USER_KEY, user);
        args.putSerializable(ADDRESS_KEY, clientAddress);
        setArguments(args);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.authorization_request, requireArguments().getString(USER_KEY)))
                .setPositiveButton(R.string.allow_always_button, onButtonClickListener)
                .setNeutralButton(R.string.allow_button, onButtonClickListener)
                .setNegativeButton(R.string.deny_always_button, onButtonClickListener)
                .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        handleDecision(Decision.DENY);
    }

    private void handleDecision(Decision decision) {
        Bundle result = requireArguments();
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
