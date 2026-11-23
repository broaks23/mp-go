package app.mpgo;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/** Owns playback so it survives leaving the activity and locking the screen. */
public final class PlaybackService extends MediaSessionService {
    private static final String ACTION_DISMISSED = "app.mpgo.NOTIFICATION_DISMISSED";
    private ExoPlayer player;
    private MediaSession session;
    private LibraryStore store;
    private boolean closingTask;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() { saveState(); handler.postDelayed(this, 3000); }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = LibraryStore.get(this);
        DefaultMediaNotificationProvider defaultProvider = new DefaultMediaNotificationProvider(this);
        PendingIntent dismissIntent = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_DISMISSED),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        setMediaNotificationProvider(new MediaNotification.Provider() {
            @Override public MediaNotification createNotification(MediaSession mediaSession,
                    ImmutableList<CommandButton> buttons, MediaNotification.ActionFactory actionFactory,
                    MediaNotification.Provider.Callback callback) {
                MediaNotification notification = defaultProvider.createNotification(mediaSession, buttons, actionFactory, callback);
                notification.notification.deleteIntent = dismissIntent;
                return notification;
            }
            @Override public NotificationChannelInfo getNotificationChannelInfo() {
                return defaultProvider.getNotificationChannelInfo();
            }
            @Override public boolean handleCustomCommand(MediaSession mediaSession, String action, Bundle extras) {
                return defaultProvider.handleCustomCommand(mediaSession, action, extras);
            }
        });
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
        if (player == null || closingTask) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < player.getMediaItemCount(); i++) ids.add(player.getMediaItemAt(i).mediaId);
        store.savePlayback(ids, player.getCurrentMediaItemIndex(), player.getCurrentPosition());
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISMISSED.equals(intent.getAction())) {
            if (player != null) { player.stop(); player.clearMediaItems(); }
            store.setQueue(new ArrayList<>(), 0);
            stopSelf();
            return START_NOT_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Save the resume point before clearing the player, which removes the media notification.
        saveState();
        closingTask = true;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        pauseAllPlayersAndStopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(checkpoint);
        saveState();
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
