package ru.coolsoft.p2pcamera.ui;

import static ru.coolsoft.common.ui.KeyStorePickerUtils.launchKeyStorePickerForResult;
import static ru.coolsoft.common.ui.KeyStorePickerUtils.registerKeyStorePickerForResult;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentResultListener;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ru.coolsoft.common.Constants;
import ru.coolsoft.common.ui.ConfirmationDialogFragment;
import ru.coolsoft.p2pcamera.R;
import ru.coolsoft.p2pcamera.SettingsManager;

public class SettingsActivity extends AppCompatActivity {
    private boolean serverRestartRequired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        FragmentManager fm = getSupportFragmentManager();
        fm.setFragmentResultListener(SettingsFragment.REQUEST_RESTART, this, (requestKey, result) ->
                serverRestartRequired = true
        );

        if (savedInstanceState == null) {
            fm.beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (serverRestartRequired) {
            new RestartAlertDialogFragment().show(getSupportFragmentManager(), null);
        } else {
            super.onBackPressed();
        }
    }

    private void onSuperBackPressed(DialogInterface dialog, int which) {
        super.onBackPressed();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        public static final String REQUEST_RESTART = "restart";

        private static final String LOG_TAG = SettingsFragment.class.getSimpleName();
        private static final String CONFIRM_KEY_REMOVAL = "key";
        private static final String CONFIRM_USER_REMOVAL = "user";

        private boolean keyImported = false;
        private KeyStore inputKeyStore;
        private Uri keyStoreUri;

        private Preference privateKeyPreference;

        private final FragmentResultListener privateKeyPasswordListener = (@NonNull String requestKey, @NonNull Bundle result) -> {
            if (!requestKey.equals(PasswordInputDialogFragment.RESULT_TAG)) {
                return;
            }

            String password = result.getString(PasswordInputDialogFragment.PASSWORD_KEY);

            try {
                KeyStore ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
                ksAndroid.load(null);
                if (inputKeyStore == null) {
                    //the fragment had been restored after the app shutdown
                    inputKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                    inputKeyStore.load(requireContext().getContentResolver().openInputStream(keyStoreUri), null);
                }
                try {
                    ksAndroid.setKeyEntry(Constants.ALIAS_MONITORING,
                            inputKeyStore.getKey(Constants.ALIAS_MONITORING, password.toCharArray()),
                            null,
                            inputKeyStore.getCertificateChain(Constants.ALIAS_MONITORING));
                    keyImported = true;
                    setRestartRequired();
                    updatePrivateKeyPreference(KeyLoadingResult.OK);
                } catch (KeyStoreException e) {
                    updatePrivateKeyPreference(KeyLoadingResult.ErrorSavingKey);
                    Log.w(LOG_TAG, e);
                }
            } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                updatePrivateKeyPreference(KeyLoadingResult.UnexpectedError);
                Log.w(LOG_TAG, e);
            } catch (UnrecoverableKeyException e) {
                updatePrivateKeyPreference(KeyLoadingResult.WrongPassword);
                Log.w(LOG_TAG, e);
            }
        };

        private final FragmentResultListener removalConfirmation = (requestKey, result) -> {
            if (!result.getBoolean(Boolean.class.getSimpleName())) {
                return;
            }
            switch (requestKey) {
                case CONFIRM_KEY_REMOVAL:
                    try {
                        KeyStore ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
                        ksAndroid.load(null);
                        ksAndroid.deleteEntry(Constants.ALIAS_MONITORING);
                        keyImported = false;
                        updatePrivateKeyPreference(KeyLoadingResult.OK);
                        setRestartRequired();
                    } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                        Log.w(LOG_TAG, e);
                        updatePrivateKeyPreference(KeyLoadingResult.ErrorRemovingKey);
                    }
                    break;
                case CONFIRM_USER_REMOVAL:
                    SettingsManager.getInstance(requireContext()).removeUser(result.getString(String.class.getSimpleName()));
                    refreshUserListPreferences();
                    break;
            }
        };

        private void setRestartRequired() {
            getParentFragmentManager().setFragmentResult(REQUEST_RESTART, Bundle.EMPTY);
        }

        private final ActivityResultLauncher<String> mPrivateKeyPicker = registerKeyStorePickerForResult(this,
                uri -> {
                    if (uri == null) {
                        //user cancelled the operation
                        return;
                    }

                    keyStoreUri = uri;
                    try {
                        inputKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                        inputKeyStore.load(requireContext().getContentResolver().openInputStream(uri), null);
                        if (!inputKeyStore.isKeyEntry(Constants.ALIAS_MONITORING)) {
                            updatePrivateKeyPreference(KeyLoadingResult.InvalidFile);
                        } else {
                            new PasswordInputDialogFragment().show(getParentFragmentManager(), null);
                        }
                    } catch (FileNotFoundException e) {
                        updatePrivateKeyPreference(KeyLoadingResult.FileNotFound);
                        Log.w(LOG_TAG, e);
                    } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                        updatePrivateKeyPreference(KeyLoadingResult.UnexpectedError);
                        Log.w(LOG_TAG, e);
                    }
                }
        );

        private void updatePrivateKeyPreference(KeyLoadingResult keyLoadingResult) {
            switch (keyLoadingResult) {
                case InvalidFile:
                    privateKeyPreference.setSummary(R.string.private_key_summary_invalid);
                    break;
                case FileNotFound:
                    privateKeyPreference.setSummary(R.string.private_key_summary_not_found);
                    break;
                case UnexpectedError:
                    privateKeyPreference.setSummary(R.string.private_key_summary_error);
                    break;
                case WrongPassword:
                    privateKeyPreference.setSummary(R.string.private_key_summary_password);
                    break;
                case ErrorSavingKey:
                    privateKeyPreference.setSummary(R.string.private_key_summary_storing);
                    break;
                case ErrorRemovingKey:
                    privateKeyPreference.setSummary(R.string.private_key_summary_removing);
                    break;
                case OK:
                    privateKeyPreference.setSummary(keyImported
                            ? R.string.private_key_summary_imported
                            : R.string.private_key_summary_click_to_load);
            }
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            if (savedInstanceState != null) {
                keyStoreUri = savedInstanceState.getParcelable(Uri.class.getSimpleName());
            }

            getParentFragmentManager().setFragmentResultListener(PasswordInputDialogFragment.RESULT_TAG, this, privateKeyPasswordListener);
            getParentFragmentManager().setFragmentResultListener(CONFIRM_KEY_REMOVAL, this, removalConfirmation);
            getParentFragmentManager().setFragmentResultListener(CONFIRM_USER_REMOVAL, this, removalConfirmation);

            try {
                KeyStore ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
                ksAndroid.load(null);
                keyImported = ksAndroid.isKeyEntry(Constants.ALIAS_MONITORING);
            } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                Log.w(LOG_TAG, e);
            }

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            Preference portPreference = Objects.requireNonNull(findPreference(getString(R.string.pref_key_port)));
            portPreference.setSummaryProvider(this::getPortPreferenceSummary);
            portPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    int val = Integer.parseInt((String) newValue);
                    if (val > 0 && val < 65536) {
                        setRestartRequired();
                        return true;
                    }
                } catch (NumberFormatException e) {
                    Log.i(LOG_TAG, "user entered non-numeric port", e);
                }
                Toast.makeText(requireContext(), R.string.port_error, Toast.LENGTH_LONG).show();
                return false;
            });

            privateKeyPreference = Objects.requireNonNull(findPreference(getString(R.string.pref_key_private_key)));
            privateKeyPreference.setOnPreferenceClickListener(preference -> {
                if (keyImported) {
                    new ConfirmationDialogFragment(
                            R.string.title_private_key_removal_request,
                            R.string.message_private_key_removal_request,
                            CONFIRM_KEY_REMOVAL
                    ).show(getParentFragmentManager(), null);
                } else {
                    launchKeyStorePickerForResult(mPrivateKeyPicker);
                }
                return true;
            });
            updatePrivateKeyPreference(KeyLoadingResult.OK);

            refreshUserListPreferences();
        }

        private void refreshUserListPreferences() {
            @StringRes final int[] userLists = new int[]{R.string.pref_key_black_list, R.string.pref_key_white_list, R.string.pref_key_onetimers};
            final List<List<String>> accessLists = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            SettingsManager.getInstance(requireContext()).getUserAccessList(accessLists.get(0), accessLists.get(1), accessLists.get(2));
            for (int i = 0; i < userLists.length; i++) {
                ListPreference userListPreference = Objects.requireNonNull(findPreference(getString(userLists[i])));
                userListPreference.setEntries(accessLists.get(i).toArray(new String[0]));
                userListPreference.setEntryValues(accessLists.get(i).toArray(new String[0]));

                userListPreference.setOnPreferenceChangeListener(this::confirmUserRemoval);
            }
        }

        private boolean confirmUserRemoval(Preference preference, Object user) {
            ConfirmationDialogFragment confirmation = new ConfirmationDialogFragment(
                    R.string.title_remove_from_list_confirmation,
                    R.string.message_remove_from_list_confirmation,
                    CONFIRM_USER_REMOVAL);
            confirmation.requireArguments().putString(String.class.getSimpleName(), (String) user);
            confirmation.show(getParentFragmentManager(), null);
            return false;
        }

        private <T extends Preference> CharSequence getPortPreferenceSummary(T preference) {
            SettingsManager sm = SettingsManager.getInstance(requireContext());
            String port = String.valueOf(sm.getPort());
            if (sm.isPortDefault()) {
                port += getString(R.string.port_default);
            }

            return port;
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            outState.putParcelable(Uri.class.getSimpleName(), keyStoreUri);
            super.onSaveInstanceState(outState);
        }

        private enum KeyLoadingResult {
            OK,
            FileNotFound,
            InvalidFile,
            WrongPassword,
            UnexpectedError,
            ErrorSavingKey,
            ErrorRemovingKey
        }
    }

    public static class RestartAlertDialogFragment extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            return new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.title_restart_required)
                    .setMessage(R.string.message_restart_required)
                    .setPositiveButton(android.R.string.ok, ((SettingsActivity) requireActivity())::onSuperBackPressed)
                    .create();
        }
    }
}