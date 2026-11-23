package app.mpgo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.os.Build;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.io.File;

public final class MainActivity extends Activity {
    private static final int FILES = 11, FOLDER = 12, INSTALL_PERMISSION = 13;
    private static final int BG = 0xff0b100d, PANEL = 0xff19231c, WHITE = 0xfff2f8f3;
    private static final int MUTED = 0xffa7b7ab, ACCENT = 0xff37d477, HOVER = 0xff294934;
    private LibraryStore store;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String tab = "Player", playlistId, importTarget;
    private LinearLayout body, root;
    private ScrollView listScroll;
    private PlayerView playerView;
    private SeekBar seek;
    private TextView elapsed, total, title, subtitle;
    private Button playButton;
    private UpdateManager.Release availableUpdate;
    private String updateMessage = "Tap Check updates to search GitHub Releases.";
    private boolean updateBusy;
    private File downloadedUpdate;
    private boolean dragging;
    private interface MoveAction { void move(int from, int to); }
    private interface ChoiceAction { void choose(int which); }
    private interface NameAction { void accept(String value); }
    private static final class DragEntry {
        final String scope; final int index;
        boolean dropped;
        DragEntry(String scope, int index) { this.scope = scope; this.index = index; }
    }
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
        b.setTextColor(primary ? BG : WHITE);
        b.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(primary ? 0x55000000 : 0x6637d477),
                shape(primary ? ACCENT : PANEL, 18), null));
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
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
    private void renderKeepingScroll() {
        int oldY = listScroll == null ? 0 : listScroll.getScrollY();
        render();
        ScrollView current = listScroll;
        if (current != null) current.post(() -> current.scrollTo(0, oldY));
    }
    private void addWeighted(LinearLayout box, View view, int height, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(height), 1);
        p.setMargins(dp(margin), 0, dp(margin), 0); box.addView(view, p);
    }
    private LinearLayout cardRow(int color) {
        LinearLayout line = row(); line.setBackground(shape(color, 16));
        line.setPadding(dp(12), dp(4), dp(8), dp(4)); return line;
    }

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
        listScroll = scroll;
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab.equals("Player")) renderPlayer(); else if (tab.equals("Library")) renderLibrary(); else renderUpdates();
        space(root, 8);
        LinearLayout nav = row(); nav.setBackground(shape(PANEL, 22)); nav.setPadding(dp(4), dp(4), dp(4), dp(4));
        Button playerTab = button("♫  Player", tab.equals("Player"), v -> { tab = "Player"; render(); });
        Button libraryTab = button("▤  Library", tab.equals("Library"), v -> { tab = "Library"; render(); });
        Button updatesTab = button("↻  Updates", tab.equals("Updates"), v -> { tab = "Updates"; render(); });
        addWeighted(nav, playerTab, 52, 3);
        addWeighted(nav, libraryTab, 52, 3);
        addWeighted(nav, updatesTab, 52, 3); root.addView(nav);
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
        TextView note = text("♫", 112, 0xff42654b, true); note.setGravity(Gravity.CENTER);
        artwork.addView(note, new FrameLayout.LayoutParams(-1, -1));
        playerView = new PlayerView(this); playerView.setUseController(false); playerView.setBackgroundColor(Color.TRANSPARENT);
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
        playerView.setDefaultArtwork(getDrawable(R.drawable.mpgo_icon));
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
        addWeighted(controls, prev, 56, 4);
        addWeighted(controls, playButton, 64, 4);
        addWeighted(controls, next, 56, 4);
        addWeighted(controls, stop, 56, 4);
        body.addView(controls); space(body, 26);
        body.addView(text("UP NEXT", 12, ACCENT, true)); space(body, 4);
        body.addView(text("Hold and drag a song to change play order", 12, MUTED, false)); space(body, 10);
        if (controller == null || controller.getMediaItemCount() == 0) {
            body.addView(text("Your play queue will appear here.", 14, MUTED, false));
        } else {
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                final int index = i;
                MediaItem item = controller.getMediaItemAt(i);
                String name = item.mediaMetadata.title == null ? "Untitled" : item.mediaMetadata.title.toString();
                int color = i == controller.getCurrentMediaItemIndex() ? HOVER : PANEL;
                LinearLayout line = cardRow(color);
                TextView label = text("≡   " + (i + 1) + ".  " + name, 14, WHITE, false);
                label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                line.addView(label, new LinearLayout.LayoutParams(0, dp(54), 1));
                View.OnClickListener playFromQueue = v -> {
                    if (controller == null) return;
                    controller.seekToDefaultPosition(index);
                    controller.play();
                };
                line.setOnClickListener(playFromQueue);
                label.setOnClickListener(playFromQueue);
                line.addView(more(v -> showQueueMenu(v, index, item)));
                bindDrag(line, label, "queue", index, color,
                        (from, to) -> { if (controller != null) controller.moveMediaItem(from, to); });
                body.addView(line); space(body, 8);
            }
        }
        updatePlayer();
    }

    private Button small(String label, View.OnClickListener action) {
        Button b = button(label, false, action); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0); b.setTextSize(20); b.setTextColor(ACCENT);
        b.setLayoutParams(lp(44, 44)); return b;
    }
    private Button more(View.OnClickListener action) {
        Button button = small("⋮", action);
        button.setContentDescription("More options");
        return button;
    }
    private void bindDrag(LinearLayout row, TextView label, String scope, int index,
                          int normalColor, MoveAction action) {
        View.OnLongClickListener start = view -> row.startDragAndDrop(
                ClipData.newPlainText("MpGo item", scope), new View.DragShadowBuilder(row),
                new DragEntry(scope, index), 0);
        row.setOnLongClickListener(start);
        label.setOnLongClickListener(start);
        label.setContentDescription(label.getText() + ". Hold and drag to reorder");
        View.OnDragListener drop = (view, event) -> {
            if (!(event.getLocalState() instanceof DragEntry)) return false;
            DragEntry item = (DragEntry) event.getLocalState();
            if (!scope.equals(item.scope)) return false;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    if (item.index != index) row.setBackground(shape(HOVER, 16));
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    autoScroll(view, event.getY()); return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    row.setBackground(shape(normalColor, 16)); return true;
                case DragEvent.ACTION_DROP:
                    if (!item.dropped && item.index != index) {
                        item.dropped = true; action.move(item.index, index); renderKeepingScroll();
                    }
                    return true;
                default: return true;
            }
        };
        row.setOnDragListener(drop);
        label.setOnDragListener(drop);
    }
    private void autoScroll(View target, float localY) {
        if (listScroll == null) return;
        int[] scrollAt = new int[2], rowAt = new int[2];
        listScroll.getLocationOnScreen(scrollAt); target.getLocationOnScreen(rowAt);
        float y = rowAt[1] + localY;
        if (y < scrollAt[1] + dp(60)) listScroll.scrollBy(0, -dp(18));
        else if (y > scrollAt[1] + listScroll.getHeight() - dp(60)) listScroll.scrollBy(0, dp(18));
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
            Button back = small("‹", v -> { playlistId = null; render(); });
            heading.addView(back); heading.addView(new View(this), lp(8, 1));
        }
        String name = playlistId == null ? "Library" : playlistName(playlistId);
        heading.addView(text(name, 23, WHITE, true), new LinearLayout.LayoutParams(0, dp(55), 1));
        if (playlistId != null) {
            String currentId = playlistId;
            heading.addView(more(v -> showPlaylistMenu(v, currentId)));
        }
        body.addView(heading);
        space(body, 8);
        LinearLayout actions = row();
        Button add = button("+ Add songs", true, v -> chooseTarget());
        addWeighted(actions, add, 52, 4);
        if (playlistId == null) {
            Button create = button("New playlist", false, v -> createPlaylist(null));
            addWeighted(actions, create, 52, 4);
        }
        body.addView(actions); space(body, 22);
        if (playlistId == null) {
            body.addView(text("PLAYLISTS", 12, ACCENT, true)); space(body, 3);
            body.addView(text("Hold and drag to reorder", 12, MUTED, false)); space(body, 10);
            List<LibraryStore.Playlist> playlists = store.playlists();
            if (playlists.isEmpty()) body.addView(text("Create a playlist to organize your music.", 14, MUTED, false));
            for (int i = 0; i < playlists.size(); i++) {
                final int index = i;
                LibraryStore.Playlist p = playlists.get(i);
                LinearLayout line = cardRow(PANEL);
                TextView label = text("≡  ▤  " + p.name + "  ·  " + store.list(p.id).size() + " songs", 15, WHITE, true);
                label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                line.addView(label, new LinearLayout.LayoutParams(0, dp(58), 1));
                label.setOnClickListener(v -> { playlistId = p.id; render(); });
                line.addView(small("▶", v -> startList(p.id, 0)));
                line.addView(more(v -> showPlaylistMenu(v, p.id)));
                bindDrag(line, label, "playlists", index, PANEL,
                        (from, to) -> store.movePlaylist(from, to));
                body.addView(line); space(body, 8);
            }
            space(body, 16); body.addView(text("SONGS", 12, ACCENT, true));
        } else {
            Button playAll = button("▶  Play playlist", false, v -> startList(playlistId, 0));
            body.addView(playAll, lp(-1, 52)); space(body, 20);
            body.addView(text("SONGS", 12, ACCENT, true));
        }
        space(body, 3); body.addView(text("Hold and drag to reorder", 12, MUTED, false)); space(body, 10);
        List<LibraryStore.Song> songs = store.list(playlistId);
        if (songs.isEmpty()) body.addView(text("No songs yet. Tap Add songs to choose files or a folder.", 14, MUTED, false));
        for (int i = 0; i < songs.size(); i++) {
            final int index = i;
            LibraryStore.Song song = songs.get(i);
            LinearLayout line = cardRow(PANEL);
            TextView label = text("≡  ♫  " + song.title, 15, WHITE, false);
            label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String listId = playlistId;
            View.OnClickListener playFromList = v -> startList(listId, index);
            line.setOnClickListener(playFromList);
            label.setOnClickListener(playFromList);
            line.addView(label, new LinearLayout.LayoutParams(0, dp(56), 1));
            line.addView(more(v -> showSongMenu(v, listId, song)));
            bindDrag(line, label, "songs:" + (listId == null ? "root" : listId), index, PANEL,
                    (from, to) -> store.move(listId, from, to));
            body.addView(line); space(body, 8);
        }
    }
    private String playlistName(String id) {
        for (LibraryStore.Playlist p : store.playlists()) if (p.id.equals(id)) return p.name;
        return "Playlist";
    }
    private void showSongMenu(View anchor, String listId, LibraryStore.Song song) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Play next");
        menu.getMenu().add(0, 2, 1, listId == null ? "Remove from library" : "Remove from playlist");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) playNext(song.mediaItem());
            else { store.removeSong(listId, song.id); renderKeepingScroll(); toast("Removed from list"); }
            return true;
        });
        menu.show();
    }
    private void showQueueMenu(View anchor, int index, MediaItem mediaItem) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Play next");
        menu.getMenu().add(0, 2, 1, "Remove from queue");
        menu.setOnMenuItemClickListener(item -> {
            if (controller == null) return true;
            if (item.getItemId() == 1) playNext(mediaItem);
            else controller.removeMediaItem(index);
            return true;
        });
        menu.show();
    }
    private void showPlaylistMenu(View anchor, String id) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Shuffle playlist");
        menu.getMenu().add(0, 2, 1, "Rename");
        menu.getMenu().add(0, 3, 2, "Remove playlist");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                List<LibraryStore.Song> shuffled = new ArrayList<>(store.list(id));
                Collections.shuffle(shuffled); startSongs(shuffled, 0);
            } else if (item.getItemId() == 2) {
                promptPlaylistName("Rename playlist", playlistName(id), "Save", value -> {
                    store.renamePlaylist(id, value); render();
                });
            } else {
                new AlertDialog.Builder(this, R.style.MpGoDialogTheme)
                        .setTitle("Remove playlist?")
                        .setMessage("The songs stay on your device and in any other lists.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (dialog, which) -> {
                            store.removePlaylist(id);
                            if (id.equals(playlistId)) playlistId = null;
                            render();
                        }).show();
            }
            return true;
        });
        menu.show();
    }
    private void playNext(MediaItem mediaItem) {
        if (controller == null) { toast("Player is connecting…"); return; }
        if (controller.getMediaItemCount() == 0) {
            controller.setMediaItem(mediaItem); controller.prepare(); controller.play();
            tab = "Player"; render();
        } else {
            int next = Math.max(0, controller.getCurrentMediaItemIndex() + 1);
            controller.addMediaItem(next, mediaItem);
            toast("Added to play next");
        }
    }
    private void createPlaylist(Runnable after) {
        promptPlaylistName("New playlist", "", "Create", name -> {
            importTarget = store.createPlaylist(name);
            playlistId = importTarget;
            render();
            if (after != null) after.run();
        });
    }
    private void promptPlaylistName(String title, String initial, String actionLabel, NameAction action) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(12));
        form.addView(text("PLAYLIST NAME", 12, ACCENT, true)); space(form, 9);
        EditText field = new EditText(this);
        field.setSingleLine(true); field.setTextSize(16); field.setTextColor(WHITE);
        field.setHintTextColor(MUTED); field.setHint("Give it a name");
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(shape(0xff26382b, 12));
        field.setText(initial); field.setSelection(field.length());
        form.addView(field, lp(-1, 54));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.MpGoDialogTheme)
                .setTitle(title).setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton(actionLabel, null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = field.getText().toString().trim();
                if (value.isEmpty()) { field.setError("Enter a name"); return; }
                dialog.dismiss(); action.accept(value);
            });
            field.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }
    private void showChoices(String title, List<String> options, ChoiceAction action) {
        LinearLayout items = new LinearLayout(this); items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(18), dp(8), dp(18), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(items);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.MpGoDialogTheme)
                .setTitle(title).setView(scroll).setNegativeButton("Cancel", null).create();
        for (int i = 0; i < options.size(); i++) {
            final int index = i;
            TextView option = text(options.get(i), 16, WHITE, false);
            option.setPadding(dp(16), 0, dp(12), 0);
            option.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x6637d477),
                    shape(0xff26382b, 14), null));
            LinearLayout.LayoutParams params = lp(-1, 54); params.bottomMargin = dp(8);
            items.addView(option, params);
            option.setOnClickListener(view -> { dialog.dismiss(); action.choose(index); });
        }
        dialog.show();
    }
    private void chooseTarget() {
        List<LibraryStore.Playlist> ps = store.playlists();
        List<String> choices = new ArrayList<>(), targets = new ArrayList<>();
        if (playlistId != null) {
            choices.add("Current playlist  ·  " + playlistName(playlistId)); targets.add(playlistId);
        }
        choices.add("Library  ·  all songs"); targets.add(null);
        for (LibraryStore.Playlist playlist : ps) {
            if (!playlist.id.equals(playlistId)) {
                choices.add("▤  " + playlist.name); targets.add(playlist.id);
            }
        }
        choices.add("+  Create new playlist");
        showChoices("Add songs to", choices, which -> {
            if (which == choices.size() - 1) createPlaylist(this::chooseSource);
            else { importTarget = targets.get(which); chooseSource(); }
        });
    }
    private void chooseSource() {
        List<String> sources = new ArrayList<>();
        sources.add("♫  Select files"); sources.add("▤  Select folder and subfolders");
        showChoices("Choose songs", sources, which -> {
                    Intent intent = new Intent(which == 0 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    if (which == 0) {
                        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    try { startActivityForResult(intent, which == 0 ? FILES : FOLDER); }
                    catch (Exception e) { toast("No file picker available"); }
                });
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
                runOnUiThread(() -> { playlistId = target; toast("Added " + count + " songs"); render(); });
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
            playlistId = target; toast("Added " + count + " songs"); render();
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
        startSongs(store.list(id), index);
    }
    private void startSongs(List<LibraryStore.Song> songs, int index) {
        if (controller == null) { toast("Player is connecting…"); return; }
        if (songs.isEmpty()) { toast("This list is empty"); return; }
        List<MediaItem> items = new ArrayList<>(); List<String> ids = new ArrayList<>();
        for (LibraryStore.Song song : songs) { items.add(song.mediaItem()); ids.add(song.id); }
        store.setQueue(ids, index);
        controller.setMediaItems(items, index, 0); controller.prepare(); controller.play();
        tab = "Player"; render();
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
