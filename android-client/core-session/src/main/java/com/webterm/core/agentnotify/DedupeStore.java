package com.webterm.core.agentnotify;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** connectionKey+event_id 去重集合：带 TTL、容量上限，并持久化到磁盘，
 * 使重连重放（相同 event_id）在进程重启后仍只产生一次告警。 */
public final class DedupeStore {
    public static final long DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1000;
    public static final int DEFAULT_MAX_ENTRIES = 1024;

    public interface Clock {
        long nowMillis();
    }

    private final File file;
    private final long ttlMillis;
    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<String, Long> entries = new LinkedHashMap<>();

    public DedupeStore(File file, long ttlMillis, int maxEntries, Clock clock) {
        this.file = file;
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        this.clock = clock;
        load();
    }

    /** @return true 表示已存在（重复事件）；false 表示新事件（已记录并持久化）。 */
    public synchronized boolean seenOrAdd(String key) {
        long now = clock.nowMillis();
        pruneLocked(now);
        if (entries.containsKey(key)) {
            return true;
        }
        entries.put(key, now + ttlMillis);
        evictOverflowLocked();
        persist();
        return false;
    }

    public synchronized int size() {
        pruneLocked(clock.nowMillis());
        return entries.size();
    }

    private void pruneLocked(long now) {
        Iterator<Map.Entry<String, Long>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    private void evictOverflowLocked() {
        while (entries.size() > maxEntries) {
            String first = entries.keySet().iterator().next();
            entries.remove(first);
        }
    }

    private void load() {
        if (file == null || !file.exists()) return;
        byte[] data;
        try (InputStream in = new FileInputStream(file)) {
            data = readAll(in);
        } catch (IOException e) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(new String(data, StandardCharsets.UTF_8));
            long now = clock.nowMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String k = o.optString("k", "");
                long e = o.optLong("e", 0L);
                if (!k.isEmpty() && e > now) {
                    entries.put(k, e);
                }
            }
            evictOverflowLocked();
        } catch (JSONException ignored) {
            // 文件损坏则按空集合处理，下次写入会覆盖。
        }
    }

    private void persist() {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, Long> e : entries.entrySet()) {
            JSONObject o = new JSONObject();
            try {
                o.put("k", e.getKey());
                o.put("e", e.getValue());
            } catch (JSONException ignored) {
                continue;
            }
            arr.put(o);
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            // 持久化失败不影响本次内存去重，下次仍会尝试。
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) {
            // Windows/部分 FS rename 可能因目标存在失败；删目标后重试一次。
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
