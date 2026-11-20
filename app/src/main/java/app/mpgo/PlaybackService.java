package app.mpgo;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import java.util.ArrayList;
import java.util.List;

/** Owns playback so it survives leaving the activity and locking the screen. */
public final class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession session;
    private LibraryStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() { saveState(); handler.postDelayed(this, 3000); }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = LibraryStore.get(this);
        player = new ExoPlayer.Builder(this).build();
        List<MediaItem> items = new ArrayList<>();
        for (String id : store.queueIds()) {
            LibraryStore.Song song = store.song(id);
            if (song != null) items.add(song.mediaItem());
        }
        if (!items.isEmpty()) {
            int index = Math.min(store.queueIndex(), items.size() - 1);
            player.setMediaItems(items, index, store.position());
            player.prepare();
        }
        player.addListener(new Player.Listener() {
            @Override public void onEvents(Player ignored, Player.Events events) { saveState(); }
        });
        session = new MediaSession.Builder(this, player).build();
        handler.postDelayed(checkpoint, 3000);
    }

    private void saveState() {
        if (player == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < player.getMediaItemCount(); i++) ids.add(player.getMediaItemAt(i).mediaId);
        store.savePlayback(ids, player.getCurrentMediaItemIndex(), player.getCurrentPosition());
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (player != null && player.isPlaying()) return;
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(checkpoint);
        saveState();
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
