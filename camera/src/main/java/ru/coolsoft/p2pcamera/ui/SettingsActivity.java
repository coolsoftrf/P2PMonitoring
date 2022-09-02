package ru.coolsoft.p2pcamera.ui;

import static ru.coolsoft.common.ui.KeyStorePickerUtils.launchKeyStorePickerForResult;
import static ru.coolsoft.common.ui.KeyStorePickerUtils.registerKeyStorePickerForResult;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentResultListener;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Objects;

import ru.coolsoft.common.Constants;
import ru.coolsoft.common.ui.ConfirmationDialogFragment;
import ru.coolsoft.p2pcamera.R;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
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

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final String LOG_TAG = SettingsFragment.class.getSimpleName();

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
                    updatePrivateKeyPreference(KeyLoadingResult.OK);
                    //ToDo: ask user to restart the server («all clients will be disconnected»)
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

        private final FragmentResultListener privateKeyRemovalConfirmation = (requestKey, result) -> {
            if (!(requestKey.equals(ConfirmationDialogFragment.DIALOG_CONFIRMATION)
                    && result.getBoolean(Boolean.class.getSimpleName()))) {
                return;
            }

            try {
                KeyStore ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
                ksAndroid.load(null);
                ksAndroid.deleteEntry(Constants.ALIAS_MONITORING);
                keyImported = false;
                updatePrivateKeyPreference(KeyLoadingResult.OK);
                //ToDo: ask user to restart the server («all clients will be disconnected»)
            } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                Log.w(LOG_TAG, e);
                updatePrivateKeyPreference(KeyLoadingResult.ErrorRemovingKey);
            }
        };

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
            getParentFragmentManager().setFragmentResultListener(ConfirmationDialogFragment.DIALOG_CONFIRMATION, this, privateKeyRemovalConfirmation);

            try {
                KeyStore ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
                ksAndroid.load(null);
                keyImported = ksAndroid.isKeyEntry(Constants.ALIAS_MONITORING);
            } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                Log.w(LOG_TAG, e);
            }

            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            privateKeyPreference = Objects.requireNonNull(findPreference(getString(R.string.pref_key_private_key)));
            privateKeyPreference.setOnPreferenceClickListener(preference -> {
                if (keyImported) {
                    new ConfirmationDialogFragment(R.string.title_private_key_removal_request, R.string.message_private_key_removal_request)
                            .show(getParentFragmentManager(), null);
                } else {
                    launchKeyStorePickerForResult(mPrivateKeyPicker);
                }
                return true;
            });
            updatePrivateKeyPreference(KeyLoadingResult.OK);
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
}