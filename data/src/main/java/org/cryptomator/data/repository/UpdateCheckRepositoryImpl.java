package org.cryptomator.data.repository;

import android.content.Context;
import android.net.Uri;

import com.google.common.base.Optional;

import org.apache.commons.codec.binary.Hex;
import org.cryptomator.data.db.Database;
import org.cryptomator.data.db.entities.UpdateCheckEntity;
import org.cryptomator.data.util.UserAgentInterceptor;
import org.cryptomator.domain.exception.BackendException;
import org.cryptomator.domain.exception.update.GeneralUpdateErrorException;
import org.cryptomator.domain.exception.update.HashMismatchUpdateCheckException;
import org.cryptomator.domain.repository.UpdateCheckRepository;
import org.cryptomator.domain.usecases.UpdateCheck;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

@Singleton
public class UpdateCheckRepositoryImpl implements UpdateCheckRepository {

	// GitHub Releases API — mengambil informasi rilis terbaru dari repo ini
	private static final String GITHUB_RELEASES_API_URL = "https://api.github.com/repos/kavionn/cryptomator/releases/latest";

	private final Database database;
	private final OkHttpClient httpClient;
	private final Context context;

	@Inject
	UpdateCheckRepositoryImpl(Database database, Context context) {
		this.httpClient = httpClient();
		this.database = database;
		this.context = context;
	}

	private OkHttpClient httpClient() {
		return new OkHttpClient //
				.Builder().addInterceptor(new UserAgentInterceptor()) //
				.build();
	}

	@Override
	public Optional<UpdateCheck> getUpdateCheck(final String appVersion) throws BackendException {
		LatestVersion latestVersion = loadLatestVersion();

		// Bandingkan versi. Tag GitHub seperti "v1.10.0" sudah di-strip prefix "v"-nya.
		if (appVersion.equals(latestVersion.version)) {
			return Optional.absent();
		}

		final UpdateCheckEntity entity = database.load(UpdateCheckEntity.class, 1L);

		// Jika versi yang tersimpan di DB sama dengan versi terbaru, kembalikan cache
		if (entity.getVersion() != null && entity.getVersion().equals(latestVersion.version) && entity.getApkSha256() != null) {
			return Optional.of(new UpdateCheckImpl("", entity));
		}

		UpdateCheck updateCheck = buildUpdateCheck(latestVersion);
		entity.setUrlToApk(updateCheck.getUrlApk());
		entity.setVersion(updateCheck.getVersion());
		entity.setApkSha256(updateCheck.getApkSha256());

		database.store(entity);

		return Optional.of(updateCheck);
	}

	@Override
	public void update(File file) throws GeneralUpdateErrorException {
		try {
			final UpdateCheckEntity entity = database.load(UpdateCheckEntity.class, 1L);

			final Request request = new Request //
					.Builder() //
					.url(entity.getUrlToApk()).build();

			final Response response = httpClient.newCall(request).execute();

			if (response.isSuccessful() && response.body() != null) {
				try (BufferedSource source = response.body().source(); BufferedSink sink = Okio.buffer(Okio.sink(file))) {
					sink.writeAll(source);
					sink.flush();

					// Verifikasi hash SHA-256 APK yang diunduh
					if (entity.getApkSha256() != null && !entity.getApkSha256().isEmpty()) {
						String apkSha256 = calculateSha256(file);
						if (!apkSha256.equalsIgnoreCase(entity.getApkSha256())) {
							file.delete();
							throw new HashMismatchUpdateCheckException(String.format( //
									"Sha of calculated hash (%s) doesn't match the specified one (%s)", //
									apkSha256, //
									entity.getApkSha256()));
						}
					}
				}
			} else {
				throw new GeneralUpdateErrorException("Failed to load update file, status code is not correct: " + response.code());
			}
		} catch (IOException e) {
			throw new GeneralUpdateErrorException("Failed to load update. General error occurred.", e);
		}
	}

	private String calculateSha256(File file) throws GeneralUpdateErrorException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (DigestInputStream digestInputStream = new DigestInputStream(context.getContentResolver().openInputStream(Uri.fromFile(file)), digest)) {
				byte[] buffer = new byte[8192];
				while (digestInputStream.read(buffer) > -1) {
				}
			}
			return new String(Hex.encodeHex(digest.digest()));
		} catch (NoSuchAlgorithmException | IOException e) {
			throw new GeneralUpdateErrorException(e);
		}
	}

	/**
	 * Mengambil informasi rilis terbaru dari GitHub Releases API.
	 * Mengharapkan aset rilis berupa:
	 *   - cryptomator-<version>.apk           (file APK)
	 *   - cryptomator-<version>.apk.sha256    (file hash, berisi hex SHA-256)
	 */
	private LatestVersion loadLatestVersion() throws BackendException {
		try {
			// 1. Ambil JSON rilis terbaru dari GitHub API
			final Request request = new Request.Builder()
					.url(GITHUB_RELEASES_API_URL)
					.header("Accept", "application/vnd.github+json")
					.header("X-GitHub-Api-Version", "2022-11-28")
					.build();

			final Response response = httpClient.newCall(request).execute();
			if (!response.isSuccessful() || response.body() == null) {
				throw new GeneralUpdateErrorException("Failed to get latest release from GitHub. Status: " + response.code());
			}

			final JSONObject json = new JSONObject(response.body().string());

			// Tag seperti "v1.10.0" → strip prefix "v" agar bisa dibandingkan dengan versionName app
			final String version = json.getString("tag_name").replaceFirst("^[vV]", "");
			final String releasePageUrl = json.getString("html_url");
			// Catatan rilis dalam format Markdown dari body rilis GitHub
			final String releaseBody = json.optString("body", "");

			// 2. Cari URL APK dan file SHA256 di daftar aset rilis
			final JSONArray assets = json.getJSONArray("assets");
			String apkUrl = null;
			String sha256FileUrl = null;

			for (int i = 0; i < assets.length(); i++) {
				final JSONObject asset = assets.getJSONObject(i);
				final String name = asset.getString("name");
				final String downloadUrl = asset.getString("browser_download_url");

				if (name.endsWith(".apk")) {
					apkUrl = downloadUrl;
				} else if (name.endsWith(".sha256")) {
					sha256FileUrl = downloadUrl;
				}
			}

			if (apkUrl == null) {
				throw new GeneralUpdateErrorException("No APK asset found in the latest GitHub release.");
			}

			// 3. Unduh konten file .sha256 jika tersedia
			String apkSha256 = null;
			if (sha256FileUrl != null) {
				apkSha256 = fetchSha256FileContent(sha256FileUrl);
			}

			return new LatestVersion(version, apkUrl, apkSha256, releasePageUrl, releaseBody);

		} catch (IOException | JSONException e) {
			throw new GeneralUpdateErrorException("Failed to update. General error occurred.", e);
		}
	}

	/**
	 * Mengunduh konten file .sha256 dan mengekstrak hash-nya.
	 * File boleh berisi hanya hash ("abc123...") atau format standar ("abc123... filename.apk").
	 */
	private String fetchSha256FileContent(String sha256FileUrl) throws IOException {
		final Request request = new Request.Builder().url(sha256FileUrl).build();
		final Response response = httpClient.newCall(request).execute();
		if (response.isSuccessful() && response.body() != null) {
			// Ambil token pertama saja (hash hex), abaikan nama file di belakangnya
			return response.body().string().trim().split("\\s+")[0];
		}
		return null;
	}

	private UpdateCheck buildUpdateCheck(LatestVersion latestVersion) {
		return new UpdateCheckImpl(latestVersion.releaseBody, latestVersion);
	}

	// -------------------------------------------------------------------------
	// Inner classes
	// -------------------------------------------------------------------------

	private static class UpdateCheckImpl implements UpdateCheck {

		private final String releaseNote;
		private final String version;
		private final String urlApk;
		private final String apkSha256;
		private final String urlReleaseNote;

		private UpdateCheckImpl(String releaseNote, LatestVersion latestVersion) {
			this.releaseNote = releaseNote;
			this.version = latestVersion.version;
			this.urlApk = latestVersion.urlApk;
			this.apkSha256 = latestVersion.apkSha256;
			this.urlReleaseNote = latestVersion.urlReleaseNote;
		}

		private UpdateCheckImpl(String releaseNote, UpdateCheckEntity updateCheckEntity) {
			this.releaseNote = releaseNote;
			this.version = updateCheckEntity.getVersion();
			this.urlApk = updateCheckEntity.getUrlToApk();
			this.apkSha256 = updateCheckEntity.getApkSha256();
			this.urlReleaseNote = updateCheckEntity.getUrlToReleaseNote();
		}

		@Override
		public String releaseNote() {
			return releaseNote;
		}

		@Override
		public String getVersion() {
			return version;
		}

		@Override
		public String getUrlApk() {
			return urlApk;
		}

		@Override
		public String getApkSha256() {
			return apkSha256;
		}

		@Override
		public String getUrlReleaseNote() {
			return urlReleaseNote;
		}
	}

	private static class LatestVersion {

		private final String version;
		private final String urlApk;
		private final String apkSha256;
		private final String urlReleaseNote;
		private final String releaseBody;

		LatestVersion(String version, String urlApk, String apkSha256, String urlReleaseNote, String releaseBody) {
			this.version = version;
			this.urlApk = urlApk;
			this.apkSha256 = apkSha256;
			this.urlReleaseNote = urlReleaseNote;
			this.releaseBody = releaseBody;
		}
	}
}
