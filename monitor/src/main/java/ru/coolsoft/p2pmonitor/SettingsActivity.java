package ru.coolsoft.p2pmonitor;

import static ru.coolsoft.common.ui.KeyStorePickerUtils.launchKeyStorePickerForResult;
import static ru.coolsoft.common.ui.KeyStorePickerUtils.registerKeyStorePickerForResult;

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
import java.security.cert.CertificateException;
import java.util.Objects;

import ru.coolsoft.common.Constants;
import ru.coolsoft.common.ui.ConfirmationDialogFragment;

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

        private boolean certificateImported;

        private Preference certificatePreference;

        private final FragmentResultListener certificateRemovalConfirmation = (requestKey, result) -> {
            if (!(requestKey.equals(ConfirmationDialogFragment.DIALOG_CONFIRMATION)
                    && result.getBoolean(Boolean.class.getSimpleName()))) {
                return;
            }

            try {
                KeyStore ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
                ksAndroid.load(null);
                ksAndroid.deleteEntry(Constants.ALIAS_MONITORING);
                certificateImported = false;
                updateCertificatePreference(KeyLoadingResult.OK);
            } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                Log.w(LOG_TAG, e);
                updateCertificatePreference(KeyLoadingResult.ErrorRemovingKey);
            }
        };

        private void updateCertificatePreference(KeyLoadingResult keyLoadingResult) {
            switch (keyLoadingResult) {
                case InvalidFile:
                    certificatePreference.setSummary(R.string.certificate_summary_invalid);
                    break;
                case FileNotFound:
                    certificatePreference.setSummary(R.string.certificate_summary_not_found);
                    break;
                case UnexpectedError:
                    certificatePreference.setSummary(R.string.certificate_summary_error);
                    break;
                case ErrorSavingKey:
                    certificatePreference.setSummary(R.string.certificate_summary_storing);
                    break;
                case ErrorRemovingKey:
                    certificatePreference.setSummary(R.string.certificate_summary_removing);
                    break;
                case OK:
                    certificatePreference.setSummary(certificateImported
                            ? R.string.certificate_summary_imported
                            : R.string.certificate_summary_click_to_load);
            }
        }

        private final ActivityResultLauncher<String> mPrivateKeyPicker = registerKeyStorePickerForResult(this,
                uri -> {
                    try {
                        if (uri == null) {
                            //user cancelled the operation
                            return;
                        }

                        KeyStore inputKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                        inputKeyStore.load(requireContext().getContentResolver().openInputStream(uri), null);
                        if (!inputKeyStore.isKeyEntry(Constants.ALIAS_MONITORING)) {
                            updateCertificatePreference(KeyLoadingResult.InvalidFile);
                        } else {
                            KeyStore ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
                            ksAndroid.load(null);

                            try {
                                ksAndroid.setCertificateEntry(Constants.ALIAS_MONITORING,
                                        inputKeyStore.getCertificate(Constants.ALIAS_MONITORING));
                                certificateImported = true;
                                updateCertificatePreference(KeyLoadingResult.OK);
                            } catch (KeyStoreException e) {
                                updateCertificatePreference(KeyLoadingResult.ErrorSavingKey);
                                Log.w(LOG_TAG, e);
                            }
                        }
                    } catch (FileNotFoundException e) {
                        updateCertificatePreference(KeyLoadingResult.FileNotFound);
                        Log.w(LOG_TAG, e);
                    } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                        updateCertificatePreference(KeyLoadingResult.UnexpectedError);
                        Log.w(LOG_TAG, e);
                    }
                }
        );

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getParentFragmentManager().setFragmentResultListener(ConfirmationDialogFragment.DIALOG_CONFIRMATION, this, certificateRemovalConfirmation);

            try {
                KeyStore ksAndroid = KeyStore.getInstance(Constants.ANDROID_KEY_STORE);
                ksAndroid.load(null);
                certificateImported = ksAndroid.isCertificateEntry(Constants.ALIAS_MONITORING);
            } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
                Log.w(LOG_TAG, e);
            }

            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            //ToDo: add an ability to import several certificates and list them by user-defined names (physical server locations)
            certificatePreference = Objects.requireNonNull(findPreference(getString(R.string.pref_key_certificate)));
            certificatePreference.setOnPreferenceClickListener(preference -> {
                if (certificateImported) {
                    new ConfirmationDialogFragment(R.string.title_certificate_removal_request, R.string.message_certificate_removal_request)
                            .show(getParentFragmentManager(), null);
                } else {
                    launchKeyStorePickerForResult(mPrivateKeyPicker);
                }
                return true;
            });
            updateCertificatePreference(KeyLoadingResult.OK);
        }

        private enum KeyLoadingResult {
            OK,
            FileNotFound,
            InvalidFile,
            UnexpectedError,
            ErrorSavingKey,
            ErrorRemovingKey
        }
    }
}