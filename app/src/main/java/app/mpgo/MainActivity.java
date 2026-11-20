package app.mpgo;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.File;

public final class MainActivity extends Activity {
    private static final int FILES = 11, FOLDER = 12, INSTALL_PERMISSION = 13;
    private static final int BG = 0xff0b0b10, PANEL = 0xff1b1b24, WHITE = 0xfff6f5f7;
    private static final int MUTED = 0xffa6a3ad, ACCENT = 0xffff345e;
    private LibraryStore store;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String tab = "Player", playlistId, importTarget;
    private LinearLayout body, root;
    private PlayerView playerView;
    private SeekBar seek;
    private TextView elapsed, total, title, subtitle;
    private Button playButton;
    private UpdateManager.Release availableUpdate;
    private String updateMessage = "Tap Check updates to search GitHub Releases.";
    private boolean updateBusy;
    private File downloadedUpdate;
    private boolean dragging;
    private final Runnable clock = new Runnable() {
        @Override public void run() { updatePlayer(); handler.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        store = LibraryStore.get(this);
        render();
    }
    @Override public void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new android.content.ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            if (controllerFuture == null) return;
            try {
                controller = controllerFuture.get();
                controller.addListener(listener);
                render();
            } catch (Exception e) { toast("Playback unavailable: " + e.getMessage()); }
        }, command -> handler.post(command));
        handler.post(clock);
    }
    @Override public void onStop() {
        handler.removeCallbacks(clock);
        if (playerView != null) playerView.setPlayer(null);
        if (controller != null) { controller.removeListener(listener); controller = null; }
        if (controllerFuture != null) { MediaController.releaseFuture(controllerFuture); controllerFuture = null; }
        super.onStop();
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) {
            updatePlayer();
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) render();
        }
    };

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private Button button(String value, boolean primary, View.OnClickListener click) {
        Button b = new Button(this); b.setText(value); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(WHITE); b.setBackground(shape(primary ? ACCENT : PANEL, 18));
        b.setOnClickListener(click); return b;
    }
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL); return r;
    }
    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }
    private void space(LinearLayout box, int height) { box.addView(new View(this), lp(1, height)); }

    private void render() {
        if (playerView != null) { playerView.setPlayer(null); playerView = null; }
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(8), dp(18), dp(8));
        setContentView(root);
        LinearLayout header = row();
        TextView logo = text("MpGo", 28, WHITE, true);
        header.addView(logo, new LinearLayout.LayoutParams(0, dp(56), 1));
        TextView section = text(tab.equals("Player") ? "NOW PLAYING" : tab.equals("Library") ? "YOUR MUSIC" : "APP UPDATES", 11, ACCENT, true);
        header.addView(section); root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab.equals("Player")) renderPlayer(); else if (tab.equals("Library")) renderLibrary(); else renderUpdates();
        space(root, 8);
        LinearLayout nav = row(); nav.setBackground(shape(PANEL, 22)); nav.setPadding(dp(4), dp(4), dp(4), dp(4));
        Button playerTab = button("♫  Player", tab.equals("Player"), v -> { tab = "Player"; render(); });
        Button libraryTab = button("▤  Library", tab.equals("Library"), v -> { tab = "Library"; render(); });
        Button updatesTab = button("↻  Updates", tab.equals("Updates"), v -> { tab = "Updates"; render(); });
        nav.addView(playerTab, new LinearLayout.LayoutParams(0, dp(52), 1));
        nav.addView(libraryTab, new LinearLayout.LayoutParams(0, dp(52), 1));
        nav.addView(updatesTab, new LinearLayout.LayoutParams(0, dp(52), 1)); root.addView(nav);
    }

    private void renderUpdates() {
        space(body, 28);
        body.addView(text("Updates", 26, WHITE, true)); space(body, 18);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18)); card.setBackground(shape(PANEL, 20));
        card.addView(text("CURRENT VERSION", 12, ACCENT, true)); space(card, 8);
        card.addView(text("v" + UpdateManager.currentVersion(this), 22, WHITE, true));
        body.addView(card, lp(-1, -2)); space(body, 22);
        TextView status = text(updateMessage, 14, MUTED, false);
        body.addView(status); space(body, 18);
        String label = updateBusy ? "Working…" : availableUpdate == null ? "Check updates" : "Install update " + availableUpdate.version;
        Button action = button(label, true, v -> {
            if (availableUpdate == null) checkUpdates(); else installUpdate();
        });
        action.setEnabled(!updateBusy);
        body.addView(action, lp(-1, 54));
    }

    private void checkUpdates() {
        updateBusy = true; updateMessage = "Checking GitHub…"; render();
        new Thread(() -> {
            try {
                UpdateManager.Release latest = UpdateManager.latest();
                runOnUiThread(() -> {
                    if (UpdateManager.isNewer(latest.version, UpdateManager.currentVersion(this))) {
                        availableUpdate = latest;
                        downloadedUpdate = null;
                        updateMessage = "Version " + latest.version + " is available.";
                    } else {
                        availableUpdate = null;
                        downloadedUpdate = null;
                        updateMessage = "You have the latest version (" + latest.version + ").";
                    }
                    updateBusy = false; if (tab.equals("Updates")) render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateBusy = false; availableUpdate = null;
                    updateMessage = "Could not check updates: " + error.getMessage();
                    if (tab.equals("Updates")) render();
                });
            }
        }).start();
    }

    private void installUpdate() {
        if (availableUpdate == null || updateBusy) return;
        if (downloadedUpdate != null && downloadedUpdate.exists()) { launchInstaller(downloadedUpdate); return; }
        UpdateManager.Release release = availableUpdate;
        updateBusy = true; updateMessage = "Downloading " + release.version + "…"; render();
        new Thread(() -> {
            try {
                File apk = UpdateManager.download(this, release, percent -> runOnUiThread(() -> {
                    updateMessage = "Downloading " + release.version + "… " + percent + "%";
                    if (tab.equals("Updates")) render();
                }));
                boolean sameSigner = UpdateManager.hasSameSigner(this, apk, release);
                runOnUiThread(() -> {
                    updateBusy = false;
                    if (!sameSigner) {
                        updateMessage = "This release uses a different signing key. A one-time reinstall is required.";
                        new AlertDialog.Builder(this).setTitle("One-time reinstall required")
                                .setMessage("Your installed MpGo uses an older signing key. Android cannot install this update over it. Uninstall the old app, then install the new APK from GitHub. Uninstalling removes saved playlists and queue; your media files stay in their original folders.")
                                .setPositiveButton("Open release", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/broaks23/mp-go/releases/tag/" + release.version))))
                                .setNegativeButton("Cancel", null).show();
                    } else {
                        downloadedUpdate = apk;
                        updateMessage = "Download ready. Confirm installation in Android.";
                        launchInstaller(apk);
                    }
                    if (tab.equals("Updates")) render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateBusy = false;
                    updateMessage = "Could not install update: " + error.getMessage();
                    if (tab.equals("Updates")) render();
                });
            }
        }).start();
    }

    private void launchInstaller(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            updateMessage = "Allow MpGo to install apps, then return to finish.";
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            try { startActivityForResult(settings, INSTALL_PERMISSION); }
            catch (Exception e) { updateMessage = "Open Android settings and allow installs from MpGo."; }
            if (tab.equals("Updates")) render();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception e) {
            updateMessage = "Android could not open the installer: " + e.getMessage();
            if (tab.equals("Updates")) render();
        }
    }

    private void renderPlayer() {
        FrameLayout artwork = new FrameLayout(this); artwork.setBackground(shape(PANEL, 24));
        TextView note = text("♫", 112, 0xff474450, true); note.setGravity(Gravity.CENTER);
        artwork.addView(note, new FrameLayout.LayoutParams(-1, -1));
        playerView = new PlayerView(this); playerView.setUseController(false); playerView.setBackgroundColor(Color.TRANSPARENT);
        playerView.setPlayer(controller);
        artwork.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        body.addView(artwork, lp(-1, 240)); space(body, 22);
        title = text("Nothing selected", 23, WHITE, true); body.addView(title);
        subtitle = text("Choose a song from Library", 13, MUTED, false); body.addView(subtitle); space(body, 16);
        seek = new SeekBar(this); seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int value, boolean fromUser) { if (fromUser && controller != null) elapsed.setText(time(controller.getDuration() * value / 1000)); }
            public void onStartTrackingTouch(SeekBar b) { dragging = true; }
            public void onStopTrackingTouch(SeekBar b) {
                if (controller != null && controller.getDuration() > 0) controller.seekTo(controller.getDuration() * b.getProgress() / 1000);
                dragging = false;
            }
        }); body.addView(seek, lp(-1, 40));
        LinearLayout times = row(); elapsed = text("0:00", 12, MUTED, false); total = text("0:00", 12, MUTED, false);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, dp(22), 1)); times.addView(total); body.addView(times); space(body, 14);
        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER);
        Button prev = button("⏮", false, v -> { if (controller != null) controller.seekToPreviousMediaItem(); });
        playButton = button("▶", true, v -> {
            if (controller == null || controller.getMediaItemCount() == 0) { tab = "Library"; render(); return; }
            if (controller.isPlaying()) controller.pause(); else controller.play(); updatePlayer();
        });
        Button next = button("⏭", false, v -> { if (controller != null) controller.seekToNextMediaItem(); });
        Button stop = button("■", false, v -> {
            if (controller == null) return;
            controller.stop(); controller.clearMediaItems();
            store.setQueue(new ArrayList<>(), 0); render();
        });
        controls.addView(prev, new LinearLayout.LayoutParams(0, dp(56), 1));
        controls.addView(playButton, new LinearLayout.LayoutParams(0, dp(64), 1));
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1));
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(56), 1));
        body.addView(controls); space(body, 26);
        body.addView(text("UP NEXT", 12, ACCENT, true)); space(body, 8);
        if (controller == null || controller.getMediaItemCount() == 0) {
            body.addView(text("Your play queue will appear here.", 14, MUTED, false));
        } else {
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                final int index = i;
                MediaItem item = controller.getMediaItemAt(i);
                String name = item.mediaMetadata.title == null ? "Untitled" : item.mediaMetadata.title.toString();
                LinearLayout line = row(); line.setBackground(shape(i == controller.getCurrentMediaItemIndex() ? 0xff35212c : PANEL, 14));
                line.setPadding(dp(8), dp(3), dp(8), dp(3));
                TextView label = text((i + 1) + ".  " + name, 14, WHITE, false);
                line.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1));
                label.setOnClickListener(v -> controller.seekToDefaultPosition(index));
                if (i > 0) line.addView(small("↑", v -> controller.moveMediaItem(index, index - 1)));
                if (i < controller.getMediaItemCount() - 1) line.addView(small("↓", v -> controller.moveMediaItem(index, index + 1)));
                body.addView(line); space(body, 5);
            }
        }
        updatePlayer();
    }

    private Button small(String label, View.OnClickListener action) {
        Button b = button(label, false, action); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0); b.setTextSize(18); b.setTextColor(ACCENT);
        b.setLayoutParams(lp(38, 38)); return b;
    }
    private void updatePlayer() {
        if (!tab.equals("Player") || title == null || controller == null) return;
        MediaItem item = controller.getCurrentMediaItem();
        title.setText(item == null || item.mediaMetadata.title == null ? "Nothing selected" : item.mediaMetadata.title);
        subtitle.setText(item == null ? "Choose a song from Library" : "Playing from your queue");
        playButton.setText(controller.isPlaying() ? "❚❚" : "▶");
        long duration = Math.max(0, controller.getDuration()), position = Math.max(0, controller.getCurrentPosition());
        elapsed.setText(time(position)); total.setText(time(duration));
        if (!dragging) seek.setProgress(duration > 0 ? (int) Math.min(1000, position * 1000 / duration) : 0);
    }
    private String time(long ms) {
        long sec = ms / 1000;
        return sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private void renderLibrary() {
        LinearLayout heading = row();
        if (playlistId != null) {
            Button back = small("‹", v -> { playlistId = null; render(); }); heading.addView(back);
        }
        String name = playlistId == null ? "Library" : playlistName(playlistId);
        heading.addView(text(name, 23, WHITE, true), new LinearLayout.LayoutParams(0, dp(55), 1));
        body.addView(heading);
        LinearLayout actions = row();
        Button add = button("+ Add songs", true, v -> chooseTarget());
        actions.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
        if (playlistId == null) {
            Button create = button("New playlist", false, v -> createPlaylist(null));
            actions.addView(create, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        body.addView(actions); space(body, 18);
        if (playlistId == null) {
            body.addView(text("PLAYLISTS", 12, ACCENT, true)); space(body, 8);
            for (LibraryStore.Playlist p : store.playlists()) {
                LinearLayout line = row(); line.setBackground(shape(PANEL, 14)); line.setPadding(dp(10), dp(4), dp(8), dp(4));
                TextView label = text("▤  " + p.name + "  ·  " + store.list(p.id).size() + " songs", 15, WHITE, true);
                line.addView(label, new LinearLayout.LayoutParams(0, dp(55), 1));
                label.setOnClickListener(v -> { playlistId = p.id; render(); });
                line.addView(small("▶", v -> startList(p.id, 0)));
                body.addView(line); space(body, 6);
            }
            space(body, 14); body.addView(text("SONGS", 12, ACCENT, true)); space(body, 8);
        } else {
            Button playAll = button("▶  Play playlist", false, v -> startList(playlistId, 0));
            body.addView(playAll, lp(-1, 48)); space(body, 14);
        }
        List<LibraryStore.Song> songs = store.list(playlistId);
        if (songs.isEmpty()) body.addView(text("No songs yet. Tap Add songs to choose files or a folder.", 14, MUTED, false));
        for (int i = 0; i < songs.size(); i++) {
            final int index = i;
            LinearLayout line = row(); line.setBackground(shape(PANEL, 14)); line.setPadding(dp(10), dp(4), dp(8), dp(4));
            TextView label = text("♫  " + songs.get(i).title, 15, WHITE, false);
            label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setOnClickListener(v -> startList(playlistId, index));
            line.addView(label, new LinearLayout.LayoutParams(0, dp(52), 1));
            if (i > 0) line.addView(small("↑", v -> { store.move(playlistId, index, index - 1); render(); }));
            if (i < songs.size() - 1) line.addView(small("↓", v -> { store.move(playlistId, index, index + 1); render(); }));
            body.addView(line); space(body, 5);
        }
    }
    private String playlistName(String id) {
        for (LibraryStore.Playlist p : store.playlists()) if (p.id.equals(id)) return p.name;
        return "Playlist";
    }
    private void createPlaylist(Runnable after) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setHint("Playlist name");
        new AlertDialog.Builder(this).setTitle("New playlist").setView(field)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) { toast("Enter a playlist name"); return; }
                    importTarget = store.createPlaylist(name);
                    if (after != null) after.run(); else render();
                }).show();
    }
    private void chooseTarget() {
        List<LibraryStore.Playlist> ps = store.playlists();
        List<String> choices = new ArrayList<>();
        choices.add("Library (root)");
        for (LibraryStore.Playlist p : ps) choices.add(p.name);
        choices.add("+ New playlist");
        int selected = 0;
        if (playlistId != null) for (int i = 0; i < ps.size(); i++) if (playlistId.equals(ps.get(i).id)) selected = i + 1;
        new AlertDialog.Builder(this).setTitle("Add songs to").setSingleChoiceItems(choices.toArray(new String[0]), selected, (dialog, which) -> {
            dialog.dismiss();
            if (which == choices.size() - 1) createPlaylist(this::chooseSource);
            else { importTarget = which == 0 ? null : ps.get(which - 1).id; chooseSource(); }
        }).setNegativeButton("Cancel", null).show();
    }
    private void chooseSource() {
        new AlertDialog.Builder(this).setTitle("Choose source")
                .setItems(new String[]{"Select files", "Select folder (includes subfolders)"}, (d, which) -> {
                    Intent intent = new Intent(which == 0 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    if (which == 0) {
                        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    try { startActivityForResult(intent, which == 0 ? FILES : FOLDER); }
                    catch (Exception e) { toast("No file picker available"); }
                }).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL_PERMISSION) {
            if (downloadedUpdate != null && downloadedUpdate.exists() &&
                    (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()))
                launchInstaller(downloadedUpdate);
            else { updateMessage = "Install permission is needed to finish the update."; if (tab.equals("Updates")) render(); }
            return;
        }
        if (result != RESULT_OK || data == null) return;
        String target = importTarget;
        if (request == FOLDER && data.getData() != null) {
            Uri tree = data.getData(); persist(tree);
            toast("Scanning folder…");
            new Thread(() -> {
                int count = scan(tree, DocumentsContract.getTreeDocumentId(tree), target, 0);
                runOnUiThread(() -> { toast("Added " + count + " songs"); render(); });
            }).start();
        } else if (request == FILES) {
            int count = 0;
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri(); persist(uri); count += addDocument(target, uri);
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData(); persist(uri); count += addDocument(target, uri);
            }
            toast("Added " + count + " songs"); render();
        }
    }
    private void persist(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
    }
    private boolean supported(String name, String mime) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".mp4") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac")
                || lower.endsWith(".wav") || mime.startsWith("audio/") || mime.equals("video/mp4");
    }
    private int addDocument(String target, Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null || !c.moveToFirst()) return 0;
            String name = c.getString(0), mime = c.getString(1);
            if (name == null || mime == null || !supported(name, mime)) return 0;
            return store.add(target, name, uri.toString());
        } catch (Exception ignored) { return 0; }
    }
    private int scan(Uri tree, String parentId, String target, int depth) {
        if (depth > 32) return 0;
        int count = 0;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return 0;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (id == null || name == null || mime == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) count += scan(tree, id, target, depth + 1);
                else if (supported(name, mime)) count += store.add(target, name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
            }
        } catch (Exception ignored) { }
        return count;
    }
    private void startList(String id, int index) {
        if (controller == null) { toast("Player is connecting…"); return; }
        List<LibraryStore.Song> songs = store.list(id);
        if (songs.isEmpty()) { toast("This list is empty"); return; }
        List<MediaItem> items = new ArrayList<>(); List<String> ids = new ArrayList<>();
        for (LibraryStore.Song song : songs) { items.add(song.mediaItem()); ids.add(song.id); }
        store.setQueue(ids, index);
        controller.setMediaItems(items, index, 0); controller.prepare(); controller.play();
        tab = "Player"; render();
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
