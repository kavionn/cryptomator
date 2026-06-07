package org.cryptomator.domain.usecases;

import org.cryptomator.util.SharedPreferencesHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DoLicenseCheckTest {

	private final SharedPreferencesHandler sharedPreferencesHandler = mock(SharedPreferencesHandler.class);

	@Nested
	@DisplayName("License retrieval from preferences")
	class LicenseRetrieval {

		@Test
		@DisplayName("Empty license + empty preference langsung aktif (bypass total)")
		void emptyLicenseAndEmptyPreference() throws Exception {
			when(sharedPreferencesHandler.licenseToken()).thenReturn("");

			DoLicenseCheck inTest = testCandidate("");

			LicenseCheck result = inTest.execute();
			assertThat(result.mail(), is("user@example.com"));
			verify(sharedPreferencesHandler).setLicenseToken("bypass-active");
		}

		@Test
		@DisplayName("Empty license + stored preference succeeds with bypass")
		void emptyLicenseWithStoredPreference() throws Exception {
			when(sharedPreferencesHandler.licenseToken()).thenReturn("some-stored-token");

			DoLicenseCheck inTest = testCandidate("");

			LicenseCheck result = inTest.execute();
			assertThat(result.mail(), is("user@example.com"));
			verify(sharedPreferencesHandler).setLicenseToken("some-stored-token");
		}

		@Test
		@DisplayName("Non-empty license stores token in preferences")
		void nonEmptyLicenseSetsPreference() throws Exception {
			DoLicenseCheck inTest = testCandidate("any-token");
			inTest.execute();

			verify(sharedPreferencesHandler).setLicenseToken("any-token");
		}

		@Test
		@DisplayName("Non-empty license does not read from preferences")
		void nonEmptyLicenseSkipsPreferenceLookup() throws Exception {
			DoLicenseCheck inTest = testCandidate("any-token");
			inTest.execute();

			verify(sharedPreferencesHandler, never()).licenseToken();
		}
	}

	@Nested
	@DisplayName("Bypass: semua token diterima")
	class BypassTokenAcceptance {

		@Test
		@DisplayName("Random garbage string diterima (bypass aktif)")
		void randomGarbageStringAccepted() throws Exception {
			DoLicenseCheck inTest = testCandidate("this-is-not-a-jwt");

			LicenseCheck result = inTest.execute();
			assertThat(result.mail(), is("user@example.com"));
			verify(sharedPreferencesHandler).setLicenseToken(any());
		}

		@Test
		@DisplayName("JWT dengan key salah tetap diterima (bypass aktif)")
		void jwtSignedWithWrongKeyAccepted() throws Exception {
			DoLicenseCheck inTest = testCandidate("eyJhbGciOiJFUzUxMiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhdHRhY2tlckBleGFtcGxlLmNvbSJ9.fake");

			LicenseCheck result = inTest.execute();
			assertThat(result.mail(), is("user@example.com"));
		}

		@Test
		@DisplayName("Token apapun mengembalikan email bypass")
		void anyTokenReturnsBypassMail() throws Exception {
			DoLicenseCheck inTest = testCandidate("a.b.c");

			LicenseCheck result = inTest.execute();
			assertThat(result.mail(), is("user@example.com"));
		}

		@Test
		@DisplayName("Token valid pun mengembalikan email bypass bukan email asli")
		void validJwtStillReturnsBypassMail() throws Exception {
			DoLicenseCheck inTest = testCandidate("eyJhbGciOiJFUzUxMiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyQGV4YW1wbGUuY29tIn0.sig");

			LicenseCheck result = inTest.execute();
			assertThat(result.mail(), is("user@example.com"));
		}
	}

	private DoLicenseCheck testCandidate(String license) {
		return new DoLicenseCheck(sharedPreferencesHandler, license);
	}
}
