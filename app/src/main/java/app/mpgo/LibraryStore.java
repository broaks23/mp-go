package app.mpgo;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small local catalogue. Media bytes stay at their original document URIs. */
final class LibraryStore {
    private static LibraryStore instance;
    static synchronized LibraryStore get(Context context) {
        if (instance == null) instance = new LibraryStore(context);
        return instance;
    }
    static final class Song {
        final String id, title, uri;
        Song(String id, String title, String uri) { this.id = id; this.title = title; this.uri = uri; }
        MediaItem mediaItem() {
            return new MediaItem.Builder().setMediaId(id).setUri(Uri.parse(uri))
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build()).build();
        }
    }
    static final class Playlist {
        final String id, name;
        Playlist(String id, String name) { this.id = id; this.name = name; }
    }

    private final android.content.SharedPreferences prefs;
    private JSONObject data;

    private LibraryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("library", Context.MODE_PRIVATE);
        try { data = new JSONObject(prefs.getString("data", "{}")); }
        catch (Exception e) { data = new JSONObject(); }
        ensure("songs"); ensure("playlists"); ensure("root"); ensure("queue");
    }
    private void ensure(String key) { if (data.optJSONArray(key) == null) put(data, key, new JSONArray()); }
    private static void put(JSONObject o, String key, Object value) {
        try { o.put(key, value); } catch (Exception ignored) { }
    }
    private void save() { prefs.edit().putString("data", data.toString()).commit(); }
    private JSONArray array(String key) { return data.optJSONArray(key); }
    private JSONArray entries(String playlistId) {
        if (playlistId == null) return array("root");
        for (int i = 0; i < array("playlists").length(); i++) {
            JSONObject p = array("playlists").optJSONObject(i);
            if (p != null && playlistId.equals(p.optString("id"))) return p.optJSONArray("items");
        }
        return new JSONArray();
    }
    synchronized List<Playlist> playlists() {
        List<Playlist> result = new ArrayList<>();
        JSONArray ps = array("playlists");
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null) result.add(new Playlist(p.optString("id"), p.optString("name")));
        }
        return result;
    }
    synchronized String createPlaylist(String name) {
        String id = UUID.randomUUID().toString();
        JSONObject p = new JSONObject();
        put(p, "id", id); put(p, "name", name.trim()); put(p, "items", new JSONArray());
        array("playlists").put(p); save(); return id;
    }
    synchronized void renamePlaylist(String id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        JSONArray playlists = array("playlists");
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist != null && id.equals(playlist.optString("id"))) {
                put(playlist, "name", name.trim()); save(); return;
            }
        }
    }
    synchronized void removePlaylist(String id) {
        JSONArray playlists = array("playlists");
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist != null && id.equals(playlist.optString("id"))) {
                playlists.remove(i); save(); return;
            }
        }
    }
    synchronized void movePlaylist(int from, int to) {
        JSONArray playlists = array("playlists");
        if (from < 0 || to < 0 || from >= playlists.length() || to >= playlists.length() || from == to) return;
        List<JSONObject> reordered = new ArrayList<>();
        for (int i = 0; i < playlists.length(); i++) reordered.add(playlists.optJSONObject(i));
        JSONObject selected = reordered.remove(from);
        reordered.add(to, selected);
        JSONArray next = new JSONArray();
        for (JSONObject playlist : reordered) next.put(playlist);
        put(data, "playlists", next); save();
    }
    synchronized Song song(String id) {
        JSONArray songs = array("songs");
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s != null && id.equals(s.optString("id")))
                return new Song(id, s.optString("title"), s.optString("uri"));
        }
        return null;
    }
    synchronized List<Song> list(String playlistId) {
        List<Song> result = new ArrayList<>();
        JSONArray ids = entries(playlistId);
        for (int i = 0; i < ids.length(); i++) {
            Song s = song(ids.optString(i));
            if (s != null) result.add(s);
        }
        return result;
    }
    synchronized int add(String playlistId, String title, String uri) {
        String id = null;
        JSONArray songs = array("songs");
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s != null && uri.equals(s.optString("uri"))) { id = s.optString("id"); break; }
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
            JSONObject s = new JSONObject();
            put(s, "id", id); put(s, "title", title); put(s, "uri", uri);
            songs.put(s);
        }
        JSONArray target = entries(playlistId);
        for (int i = 0; i < target.length(); i++) if (id.equals(target.optString(i))) return 0;
        target.put(id); save(); return 1;
    }
    synchronized void move(String playlistId, int from, int to) {
        JSONArray old = entries(playlistId);
        if (from < 0 || to < 0 || from >= old.length() || to >= old.length()) return;
        List<String> ids = strings(old);
        String id = ids.remove(from); ids.add(to, id);
        JSONArray next = toArray(ids);
        if (playlistId == null) put(data, "root", next);
        else for (int i = 0; i < array("playlists").length(); i++) {
            JSONObject p = array("playlists").optJSONObject(i);
            if (p != null && playlistId.equals(p.optString("id"))) put(p, "items", next);
        }
        save();
    }
    synchronized void removeSong(String playlistId, String songId) {
        JSONArray ids = entries(playlistId);
        for (int i = 0; i < ids.length(); i++) {
            if (songId.equals(ids.optString(i))) { ids.remove(i); save(); return; }
        }
    }
    synchronized List<String> queueIds() { return strings(array("queue")); }
    synchronized int queueIndex() { return data.optInt("queueIndex", 0); }
    synchronized long position() { return data.optLong("position", 0); }
    synchronized void setQueue(List<String> ids, int index) {
        put(data, "queue", toArray(ids)); put(data, "queueIndex", index); put(data, "position", 0); save();
    }
    synchronized void savePlayback(List<String> ids, int index, long position) {
        put(data, "queue", toArray(ids));
        put(data, "queueIndex", Math.max(0, index));
        put(data, "position", Math.max(0, position)); save();
    }
    private static List<String> strings(JSONArray a) {
        List<String> ids = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) ids.add(a.optString(i));
        return ids;
    }
    private static JSONArray toArray(List<String> ids) {
        JSONArray a = new JSONArray(); for (String id : ids) a.put(id); return a;
    }
}
