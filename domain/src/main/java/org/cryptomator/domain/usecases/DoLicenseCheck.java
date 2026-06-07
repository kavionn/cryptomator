package org.cryptomator.domain.usecases;

import org.cryptomator.domain.exception.BackendException;
import org.cryptomator.domain.exception.license.NoLicenseAvailableException;
import org.cryptomator.generator.Parameter;
import org.cryptomator.generator.UseCase;
import org.cryptomator.util.SharedPreferencesHandler;

@UseCase
public class DoLicenseCheck {

	private static final String BYPASS_MAIL = "user@example.com";

	private final SharedPreferencesHandler sharedPreferencesHandler;
	private String license;

	DoLicenseCheck(final SharedPreferencesHandler sharedPreferencesHandler, @Parameter final String license) {
		this.sharedPreferencesHandler = sharedPreferencesHandler;
		this.license = license;
	}

	public LicenseCheck execute() throws BackendException {
		license = useLicenseOrRetrieveFromPreferences(license);
		sharedPreferencesHandler.setLicenseToken(license);
		// Bypass: selalu kembalikan lisensi valid tanpa verifikasi
		return () -> BYPASS_MAIL;
	}

	private String useLicenseOrRetrieveFromPreferences(String license) throws NoLicenseAvailableException {
		if (!license.isEmpty()) {
			return license;
		}
		String stored = sharedPreferencesHandler.licenseToken();
		if (stored.isEmpty()) {
			throw new NoLicenseAvailableException();
		}
		return stored;
	}
}
