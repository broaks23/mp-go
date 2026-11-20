# MpGo

MpGo is a small Android player for local MP3, MP4 and common audio files. It opens on the Player tab, with Library at the bottom. An MP4 video is shown in the player while the app is open; audio continues when the app is in the background or the screen is locked.

## Use

1. Open **Library → Add songs**. Choose the root library, an existing playlist or a new playlist, then select files or a folder. Folder import includes subfolders.
2. Tap a song to play that list from the selected item. Tap **▶** beside a playlist to play it from the beginning.
3. Use **↑** and **↓** to change the stored order in Library. The same buttons on Player change only the current queue. The queue and playback position are saved between app launches.
4. Use the square **Stop** button to end playback and remove the media notification. Android keeps an active media notification visible while playback is running; its play/pause, previous and next controls also appear on the lock screen.

MpGo stores playlists, queue and file references in app preferences. It does not copy media into app storage. Android's document picker grants lasting access; moving or deleting a source file can make that entry unplayable.

## GitHub build and release

The [Android workflow](.github/workflows/android.yml) runs on every push to `main` and can be started manually. Each run uploads an installable `MpGo-debug-apk` artifact under **Actions → run → Artifacts**. Pushing a tag such as `v1.0.0` also creates a GitHub Release with the APK attached.

```bash
git init
git add .
git commit -m "Build MpGo Android player"
git branch -M main
git remote add origin <your-github-repository-url>
git push -u origin main
git tag v1.0.0
git push origin v1.0.0
```

The workflow uses a debug signing key generated on each runner. Android may require uninstalling a previous CI APK before installing one from a later run. For seamless upgrades, replace debug signing with a private release signing key stored in GitHub Actions secrets.

The project targets Android 7.0 and newer. No Android SDK is needed on your computer; GitHub Actions installs the SDK and Gradle.
