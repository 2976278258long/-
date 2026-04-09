package com.example.srsdemo;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Config {
    private static final Map<String, String> defaults = new HashMap<>();
    private static volatile Map<String, String> kv = new HashMap<>();
    private static volatile long lastModified = 0L;
    static {
        loadClasspath("app-config.txt");
        ensureExternalTemplate("config.txt", "app-config.txt");
        reloadExternal("config.txt");
        startWatcher("config.txt");
    }
    private static void loadClasspath(String name) {
        try {
            InputStream is = Config.class.getClassLoader().getResourceAsStream(name);
            if (is == null) return;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf('=');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    defaults.put(k, v);
                }
            }
            Map<String,String> m = new HashMap<>(defaults);
            kv = m;
        } catch (Exception ignored) {}
    }
    private static void reloadExternal(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) {
                kv = new HashMap<>(defaults);
                return;
            }
            long lm = f.lastModified();
            lastModified = lm;
            Map<String,String> m = new HashMap<>(defaults);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf('=');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    m.put(k, v);
                }
            }
            kv = m;
        } catch (Exception ignored) {}
    }
    private static void ensureExternalTemplate(String target, String templateOnClasspath) {
        try {
            File f = new File(target);
            if (f.exists()) return;
            InputStream is = Config.class.getClassLoader().getResourceAsStream(templateOnClasspath);
            if (is == null) return;
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
        } catch (Exception ignored) {}
    }
    private static void startWatcher(String path) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    File f = new File(path);
                    if (f.exists()) {
                        long lm = f.lastModified();
                        if (lm > lastModified) {
                            reloadExternal(path);
                        }
                    }
                    Thread.sleep(2000);
                } catch (Exception ignored) {}
            }
        }, "Config-Watcher");
        t.setDaemon(true);
        t.start();
    }
    public static String getString(String key, String def) {
        String v = kv.get(key);
        return v == null || v.isEmpty() ? def : v;
    }
    public static int getInt(String key, int def) {
        try {
            String v = kv.get(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) { return def; }
    }
    public static boolean getBool(String key, boolean def) {
        String v = kv.get(key);
        if (v == null) return def;
        v = v.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("yes")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("no")) return false;
        return def;
    }
}
