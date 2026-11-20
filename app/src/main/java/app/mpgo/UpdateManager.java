package app.mpgo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Reads only this project's public GitHub releases; downloads the full APK for Android to install. */
final class UpdateManager {
    static final class Release {
        final String version, apkUrl, digest;
        Release(String version, String apkUrl, String digest) {
            this.version = version; this.apkUrl = apkUrl; this.digest = digest;
        }
    }
    interface Progress { void update(int percent); }
    private static final String API = "https://api.github.com/repos/broaks23/mp-go/releases/latest";
    private static final long MAX_APK = 200L * 1024 * 1024;

    static String currentVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception e) { return "unknown"; }
    }
    static boolean isNewer(String candidate, String installed) {
        int[] a = parts(candidate), b = parts(installed);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }
    private static int[] parts(String version) {
        if (version == null || !version.matches("v?\\d+\\.\\d+\\.\\d+")) return null;
        String[] values = version.replaceFirst("^v", "").split("\\.");
        try { return new int[]{Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2])}; }
        catch (NumberFormatException e) { return null; }
    }
    private static HttpURLConnection connect(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "MpGo-Android");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
        int status = c.getResponseCode();
        if (status != 200) { c.disconnect(); throw new Exception("GitHub returned HTTP " + status); }
        return c;
    }
    static Release latest() throws Exception {
        HttpURLConnection c = connect(API);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 1024 * 1024) throw new Exception("Release response is too large");
                out.write(buffer, 0, n);
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            String version = json.optString("tag_name");
            if (parts(version) == null) throw new Exception("Latest release has no valid version");
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null || !asset.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                String url = asset.optString("browser_download_url");
                if (url.startsWith("https://github.com/broaks23/mp-go/releases/download/"))
                    return new Release(version, url, asset.optString("digest"));
            }
            throw new Exception("Latest release has no APK");
        } finally { c.disconnect(); }
    }
    static File download(Context context, Release release, Progress progress) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) throw new Exception("Cannot create download folder");
        File partial = new File(directory, "mpgo.part"), apk = new File(directory, "mpgo.apk");
        HttpURLConnection c = connect(release.apkUrl);
        try {
            if (!"https".equalsIgnoreCase(c.getURL().getProtocol())) throw new Exception("Insecure download URL");
            long length = c.getContentLengthLong();
            if (length > MAX_APK) throw new Exception("APK is too large");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long read = 0; int lastPercent = -1;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    read += n;
                    if (read > MAX_APK) throw new Exception("APK is too large");
                    sha.update(buffer, 0, n); out.write(buffer, 0, n);
                    if (length > 0) {
                        int percent = (int) (read * 100 / length);
                        if (percent != lastPercent) { progress.update(percent); lastPercent = percent; }
                    }
                }
            }
            if (read == 0 || (length > 0 && read != length)) throw new Exception("Incomplete APK download");
            if (release.digest.startsWith("sha256:")) {
                StringBuilder hash = new StringBuilder();
                for (byte b : sha.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                if (!hash.toString().equalsIgnoreCase(release.digest.substring(7))) throw new Exception("APK checksum mismatch");
            }
            if (apk.exists() && !apk.delete()) throw new Exception("Cannot replace old download");
            if (!partial.renameTo(apk)) throw new Exception("Cannot finish APK download");
            return apk;
        } finally { c.disconnect(); if (partial.exists()) partial.delete(); }
    }
    static boolean hasSameSigner(Context context, File apk, Release release) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)
                || !release.version.replaceFirst("^v", "").equals(candidate.versionName))
            throw new Exception("Downloaded APK is not the expected MpGo version");
        Signature[] a = Build.VERSION.SDK_INT >= 28 ? candidate.signingInfo.getApkContentsSigners() : candidate.signatures;
        Signature[] b = Build.VERSION.SDK_INT >= 28 ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        return a != null && b != null && Arrays.equals(a, b);
    }
}
