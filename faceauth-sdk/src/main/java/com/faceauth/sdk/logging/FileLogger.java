package com.faceauth.sdk.logging;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent file logger for FaceAuth pipeline. Writes JSONL to app-private external storage
 * so logs can be pulled via USB (MTP) or adb pull. Does not log embeddings or raw images.
 * <p>
 * Retrieve logs:
 * - MTP: Android/data/&lt;package&gt;/files/logs/
 * - ADB: adb pull /sdcard/Android/data/&lt;package&gt;/files/logs/ ./logs
 */
public final class FileLogger {

    private static final String TAG_PREFIX = "FaceAuth/";
    private static final String LOG_DIR = "logs";
    private static final String FILE_PREFIX = "faceauth_";
    private static final String FILE_SUFFIX = ".log";
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024; // 5MB
    private static final int KEEP_FILES = 10;
    private static final int BATCH_SIZE = 25;
    private static final long FLUSH_INTERVAL_MS = 2000L;

    private static volatile FileLogger INSTANCE;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private final File logDir;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FileLogger-Writer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object flushSignal = new Object();

    private volatile File currentFile;
    private volatile long currentFileLength;
    private volatile int currentDayIndex; // for same-day rotation (_0, _1, ...)
    private volatile long lastFlushMs;

    private FileLogger(File dir) {
        this.logDir = dir;
        if (!dir.exists()) dir.mkdirs();
        lastFlushMs = System.currentTimeMillis();
    }

    /**
     * Initialize the file logger. Call once from Application or FaceAuthSdk.initialize.
     * Uses context.getExternalFilesDir("logs").
     */
    public static void init(Context context) {
        if (INITIALIZED.get()) return;
        synchronized (FileLogger.class) {
            if (INITIALIZED.get()) return;
            File dir = context.getExternalFilesDir(LOG_DIR);
            if (dir == null) return;
            INSTANCE = new FileLogger(dir);
            INSTANCE.deleteOldFilesKeeping(KEEP_FILES);
            INITIALIZED.set(true);
            INSTANCE.logInternal(TAG_PREFIX + "FileLogger", "I", "FileLogger initialized, dir=" + dir.getAbsolutePath());
        }
    }

    public static boolean isInitialized() {
        return INITIALIZED.get() && INSTANCE != null && !INSTANCE.closed.get();
    }

    /**
     * Enqueue a log line. Written asynchronously by background thread (non-blocking for analyzer).
     */
    public static void log(String tag, String level, String message) {
        if (!isInitialized() || message == null) return;
        INSTANCE.enqueue(tag != null ? tag : "", level != null ? level : "I", message);
    }

    public static void flush() {
        if (!isInitialized()) return;
        INSTANCE.flushInternal();
    }

    public static void close() {
        if (!INITIALIZED.get() || INSTANCE == null) return;
        synchronized (FileLogger.class) {
            if (INSTANCE.closed.getAndSet(true)) return;
            INSTANCE.flushInternal();
            INSTANCE.writer.shutdown();
            try {
                INSTANCE.writer.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            INSTANCE = null;
            INITIALIZED.set(false);
        }
    }

    private void enqueue(String tag, String level, String message) {
        long ts = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        String line = jsonLine(ts, threadName, tag, level, message);
        queue.offer(line);
        scheduleFlush();
    }

    private void logInternal(String tag, String level, String message) {
        long ts = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        queue.offer(jsonLine(ts, threadName, tag, level, message));
    }

    private static String jsonLine(long ts, String threadName, String tag, String level, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ts\":").append(ts);
        sb.append(",\"tn\":\"").append(escape(threadName)).append("\"");
        sb.append(",\"tag\":\"").append(escape(tag)).append("\"");
        sb.append(",\"lvl\":\"").append(escape(level)).append("\"");
        sb.append(",\"msg\":\"").append(escape(message)).append("\"}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c); break;
            }
        }
        return sb.toString();
    }

    private void scheduleFlush() {
        writer.execute(this::drainAndWrite);
    }

    private void flushInternal() {
        synchronized (flushSignal) {
            flushSignal.notifyAll();
        }
        writer.execute(this::drainAndWrite);
    }

    private void drainAndWrite() {
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        long now = System.currentTimeMillis();
        boolean forceFlush = (now - lastFlushMs >= FLUSH_INTERVAL_MS);

        while (queue.peek() != null && batch.size() < BATCH_SIZE) {
            String line = queue.poll();
            if (line != null) batch.add(line);
        }
        if (batch.isEmpty() && !forceFlush) return;

        lastFlushMs = now;
        if (batch.isEmpty()) return;

        try {
            ensureCurrentFile();
            if (currentFile == null) return;
            try (FileOutputStream os = new FileOutputStream(currentFile, true)) {
                byte[] utf8 = StandardCharsets.UTF_8;
                for (String line : batch) {
                    byte[] b = (line + "\n").getBytes(utf8);
                    os.write(b);
                    currentFileLength += b.length;
                }
            }
            if (currentFileLength >= MAX_FILE_BYTES) {
                currentFile = null;
                currentFileLength = 0;
                currentDayIndex++;
            }
        } catch (IOException e) {
            android.util.Log.w(TAG_PREFIX + "FileLogger", "FileLogger write failed", e);
        }
    }

    private void ensureCurrentFile() throws IOException {
        if (currentFile != null && currentFile.exists()) return;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        String dateStr = sdf.format(new Date());
        String baseName = FILE_PREFIX + dateStr;
        if (currentDayIndex > 0) {
            currentFile = new File(logDir, baseName + "_" + currentDayIndex + FILE_SUFFIX);
        } else {
            currentFile = new File(logDir, baseName + FILE_SUFFIX);
        }
        currentFileLength = currentFile.exists() ? currentFile.length() : 0;
        if (currentFileLength >= MAX_FILE_BYTES) {
            currentFile = null;
            currentDayIndex++;
            ensureCurrentFile();
        }
    }

    private void deleteOldFilesKeeping(int keep) {
        File[] files = logDir.listFiles((d, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
        if (files == null || files.length <= keep) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < files.length; i++) {
            if (files[i].equals(currentFile)) continue;
            files[i].delete();
        }
    }

    /** Returns the log directory path for documentation / adb pull. */
    public static String getLogDirectoryPath(Context context) {
        File dir = context != null ? context.getExternalFilesDir(LOG_DIR) : null;
        return dir != null ? dir.getAbsolutePath() : "";
    }
}
