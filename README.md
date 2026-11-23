# MpGo

MpGo plays local MP3, MP4 and common audio files on Android 7.0 and newer. It opens on Player; Library holds songs and playlists without copying the media files. Playback continues in the background and on the lock screen.

## Use

1. Open **Library → Add songs**. Choose the root library, a playlist or a new playlist, then select files or a folder. Folder import includes subfolders.
2. Tap a song in Library to play its list from that item, or tap a song in Player to play it from the current queue. Tap **▶** beside a playlist to play it from the beginning.
3. Hold a song or playlist row, then drag and drop it to change the order. Reordering in Player changes only the current queue. MpGo saves the queue and playback position.
4. Tap **⋮** beside a song for **Play next** or to remove it from the current list. Playlist menus offer **Shuffle playlist**, **Rename** and **Remove playlist**. Removing a list entry leaves the media file on your device.
5. Open **Updates** to see the installed version. Tap **Check updates** to query the public GitHub Releases API. If a newer release has an APK, tap **Install update vX.Y.Z** to download it and open Android's installer. Internet access and Android's permission to install apps from MpGo are required.

Removing MpGo from Android's Recents screen stops playback and removes its notification. The queue and position remain saved for the next launch.

The installer checks the APK's package, version and signing certificate. Android asks you to confirm installation. MpGo does not install silently.

## Builds and releases

The [GitHub Actions workflow](.github/workflows/android.yml) builds a signed APK on every push to `main`, uploads it as an Actions artifact, and attaches it to a GitHub Release when a `vX.Y.Z` tag is pushed. A release tag must match `versionName` in [app/build.gradle](app/build.gradle); increase `versionCode` for each new version. The computer pushing code does not need Android tools.

Releases from `v1.1.0` onward use one signing key stored in GitHub Actions secrets. The local backup is in the ignored `.signing/` folder; back up that folder securely because GitHub secrets cannot be read back. The public certificate SHA-256 fingerprint is `418B3E57AF86D7A91FCB0DA178AE99F19C4CFA1F845C81D89BDC3C60E6FB00DC`.

**One-time migration:** `v1.0.0` and `v1.0.1` were built with temporary debug keys. Android cannot upgrade those APKs in place to `v1.1.0`. Uninstall the old app before installing the new release; this removes saved playlists and queue, but it does not remove the media files in their original folders. Later releases signed with the same key can install over `v1.1.0` and keep app data.
