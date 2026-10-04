package com.boxagent.vscreen;

import android.hardware.input.InputManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Shell-uid companion to boxagentd: owns one logical virtual display and
 * injects input onto it. Runs under `app_process`; speaks the same frame
 * format as the daemon (4-byte BE length + JSON) on an abstract socket.
 *
 * Why this exists: a usable virtual screen needs a *logical* display —
 * `am start --display`, accessibility windows and `screencap -d` all key
 * off DisplayManager ids, so `DisplayManagerGlobal.createVirtualDisplay`
 * is the only path. It's a hidden API, so everything there is reflection.
 */
public final class Main {

    private static final String VERSION = "1";
    private static final int MAX_FRAME = 4 * 1024 * 1024;

    private static final int INJECT_ASYNC = 0;   // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
    private static final int INJECT_SYNC = 2;    // INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH

    private static int displayId = -1;
    private static int displayW, displayH, displayDpi;
    private static Object vdObject; // android.hardware.display.VirtualDisplay — release() frees it
    private static android.media.ImageReader imageReader;
    private static Surface sinkSurface;

    public static void main(String[] args) {
        // RuntimeInit swallows main() failures quietly; make them visible.
        try {
            run(args);
        } catch (Throwable t) {
            try {
                java.io.FileWriter w = new java.io.FileWriter("/data/local/tmp/vscreen.fatal", true);
                w.write(t + "\n");
                for (StackTraceElement e : t.getStackTrace()) w.write("  at " + e + "\n");
                w.close();
            } catch (Throwable ignored) {}
            t.printStackTrace();
        }
    }

    private static void run(String[] args) throws Exception {
        String socket = "boxagent.vscreen";
        String token = "";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--socket")) socket = args[i + 1];
            if (args[i].equals("--token")) token = args[i + 1];
        }
        final String tok = token;
        Workarounds.prepare();

        // Shell can't enumerate our process (restricted /proc view), so
        // the manager stops us through this pid file instead of pkill.
        try {
            java.nio.file.Files.write(
                    new java.io.File("/data/local/tmp/boxagent-vscreen.pid").toPath(),
                    String.valueOf(android.os.Process.myPid()).getBytes());
        } catch (Throwable t) {
            log("pid file: " + t);
        }

        // Abstract socket names are not exclusive on Linux — a second
        // instance would bind the same name and split the client pool.
        // Refuse to start while another host still answers.
        try {
            LocalSocket probe = new LocalSocket();
            probe.connect(new LocalSocketAddress(socket, LocalSocketAddress.Namespace.ABSTRACT));
            probe.close();
            log("another host is already listening — exiting");
            return;
        } catch (Throwable ignored) {
        }

        LocalServerSocket server = new LocalServerSocket(socket);
        log("vscreen host up on " + socket);
        try {
            while (true) {
                LocalSocket conn = server.accept();
                new Thread(() -> serve(conn, tok), "vscreen-conn").start();
            }
        } finally {
            destroyDisplay();
        }
    }

    // ------------------------------------------------------------------
    // Framing + dispatch

    private static void serve(LocalSocket conn, String token) {
        try {
            DataInputStream in = new DataInputStream(conn.getInputStream());
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            JSONObject hello = readFrame(in);
            if (hello == null || !"auth".equals(hello.optString("op"))
                    || !token.equals(hello.optString("token"))) {
                send(out, new JSONObject().put("ok", false).put("error", "bad auth"));
                conn.close();
                return;
            }
            send(out, new JSONObject().put("ok", true).put("version", VERSION));
            while (true) {
                JSONObject req = readFrame(in);
                if (req == null) break;
                try {
                    send(out, dispatch(req));
                } catch (Throwable t) {
                    send(out, err(t));
                }
            }
        } catch (EOFException ignored) {
        } catch (Throwable t) {
            log("conn died: " + t);
        } finally {
            try { conn.close(); } catch (Throwable ignored) {}
        }
    }

    private static JSONObject dispatch(JSONObject req) throws Exception {
        switch (req.optString("op")) {
            case "ping":
                return ok();
            case "info":
                return ok()
                        .put("display_id", displayId)
                        .put("w", displayW).put("h", displayH).put("dpi", displayDpi)
                        .put("version", VERSION);
            case "create": {
                int w = req.optInt("w", 1080), h = req.optInt("h", 2400);
                int dpi = req.optInt("dpi", 420);
                return ok().put("display_id", ensureDisplay(w, h, dpi));
            }
            case "destroy":
                new Thread(() -> {
                    sleep(150); // let the reply reach the client first
                    destroyDisplay(); // kills our tasks, releases the display
                    System.exit(0);
                }).start();
                return ok();
            case "tap":
                requireDisplay();
                injectTap(req.getDouble("x"), req.getDouble("y"), 60);
                return ok();
            case "long_press":
                requireDisplay();
                injectTap(req.getDouble("x"), req.getDouble("y"), req.optLong("duration_ms", 800));
                return ok();
            case "swipe":
                requireDisplay();
                injectSwipe(
                        req.getDouble("x1"), req.getDouble("y1"),
                        req.getDouble("x2"), req.getDouble("y2"),
                        req.optLong("duration_ms", 300));
                return ok();
            case "pinch":
                requireDisplay();
                injectPinch(
                        req.getDouble("cx"), req.getDouble("cy"),
                        req.optBoolean("zoom_in", true), req.optInt("percent", 50));
                return ok();
            case "key":
                requireDisplay();
                injectKey(req.getInt("code"));
                return ok();
            case "screenshot": {
                requireDisplay();
                byte[] png = capturePng();
                return ok().put("png", png == null ? JSONObject.NULL
                        : android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP));
            }
            case "text":
                requireDisplay();
                injectText(req.getString("text"));
                return ok();
            case "touch": {  // primitive: action,x,y for custom sequences
                requireDisplay();
                int action = req.getInt("action");
                long dt = req.optLong("down_time", SystemClock.uptimeMillis());
                touchEvent(dt, action, new float[]{(float) req.getDouble("x")},
                        new float[]{(float) req.getDouble("y")}, new int[]{0});
                return ok();
            }
            default:
                return new JSONObject().put("ok", false).put("error", "unknown op");
        }
    }

    private static JSONObject readFrame(DataInputStream in) throws Exception {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (len <= 0 || len > MAX_FRAME) throw new java.io.IOException("bad frame len " + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new JSONObject(new String(buf, StandardCharsets.UTF_8));
    }

    private static void send(DataOutputStream out, JSONObject v) throws Exception {
        byte[] p = v.toString().getBytes(StandardCharsets.UTF_8);
        out.writeInt(p.length);
        out.write(p);
        out.flush();
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject err(Throwable t) throws Exception {
        return new JSONObject().put("ok", false)
                .put("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
    }

    // ------------------------------------------------------------------
    // Virtual display lifecycle

    /** Reads a VIRTUAL_DISPLAY_FLAG_* constant from DisplayManager — bit
     *  positions moved across API levels, never hardcode them. */
    private static int vdFlag(Class<?> dmCls, String name) {
        try {
            return dmCls.getDeclaredField(name).getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static synchronized int ensureDisplay(int w, int h, int dpi) throws Exception {
        if (displayId >= 0) return displayId;

        Class<?> dmCls = Class.forName("android.hardware.display.DisplayManager");
        int flags = vdFlag(dmCls, "VIRTUAL_DISPLAY_FLAG_PUBLIC")
                | vdFlag(dmCls, "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
                | vdFlag(dmCls, "VIRTUAL_DISPLAY_FLAG_SUPPORT_TOUCH")
                | vdFlag(dmCls, "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        // TRUSTED / OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED excluded — each
        // needs an internal permission shell uid does not hold (server-
        // side check in DMS). OWN_CONTENT_ONLY is also out: it makes WMS
        // hide the display's windows from accessibility services, and
        // without a11y windows the agent can't see or act here.
        log("vd flags=0x" + Integer.toHexString(flags));

        Class<?> builderCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Object builder = builderCls
                .getDeclaredConstructor(String.class, int.class, int.class, int.class)
                .newInstance("boxagent", w, h, dpi);
        call(builder, "setFlags", new Class<?>[]{int.class}, flags);
        try {
            call(builder, "setUniqueId", new Class<?>[]{String.class}, "boxagent:vscreen");
        } catch (Throwable ignored) {}
        // Display output lands in our ImageReader — the only reliable way to
        // read frames back (SurfaceFlinger has no display device for virtual
        // displays on some builds, so `screencap -d` can't see it).
        imageReader = android.media.ImageReader.newInstance(
                w, h, android.graphics.PixelFormat.RGBA_8888, 3);
        sinkSurface = imageReader.getSurface();
        call(builder, "setSurface", new Class<?>[]{Surface.class}, sinkSurface);
        Object config = call(builder, "build", new Class<?>[]{});

        Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object dmg = dmgCls.getMethod("getInstance").invoke(null);
        // The config-based overload calls context.getPackageName() directly —
        // a Context is mandatory even though we have no ActivityThread.
        // FakeContext (scrcpy's trick): a ContextWrapper over null that only
        // answers the handful of methods the display path touches.
        Object ctx = new FakeContext();

        Throwable last = null;
        for (Method m : dmgCls.getDeclaredMethods()) {
            if (!m.getName().equals("createVirtualDisplay")) continue;
            // Android 15+: (Context, MediaProjection, VirtualDisplayConfig,
            // Callback, Executor) — Context gets the real one, rest nullable.
            Class<?>[] types = m.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean usable = true;
            for (int i = 0; i < types.length; i++) {
                String n = types[i].getName();
                if (n.endsWith("VirtualDisplayConfig")) args[i] = config;
                else if (n.equals("android.content.Context")) args[i] = ctx;
                else if (types[i].isPrimitive()) { usable = false; break; }
                else args[i] = null;
            }
            if (!usable) continue;
            try {
                m.setAccessible(true);
                Object vd = m.invoke(dmg, args);
                int id = displayIdOf(vd);
                if (id > 0) {
                    vdObject = vd;
                    displayId = id;
                    displayW = w;
                    displayH = h;
                    displayDpi = dpi;
                    log("created display " + id + " " + w + "x" + h + "@" + dpi);
                    return id;
                }
            } catch (Throwable t) {
                last = t;
                log("createVirtualDisplay threw " + t);
                for (StackTraceElement e : t.getStackTrace()) log("  at " + e);
                if (t.getCause() != null) {
                    log("  cause: " + t.getCause());
                    for (StackTraceElement e : t.getCause().getStackTrace())
                        log("    at " + e);
                }
            }
        }
        throw new IllegalStateException("createVirtualDisplay failed: "
                + (last != null ? String.valueOf(last.getMessage()) : "no compatible signature"));
    }

    private static int displayIdOf(Object vd) {
        if (vd == null) return -1;
        try {
            Object d = vd.getClass().getMethod("getDisplay").invoke(vd);
            return (Integer) d.getClass().getMethod("getDisplayId").invoke(d);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static synchronized void destroyDisplay() {
        int id = displayId;
        if (id >= 0) removeTasksOnDisplay(id);
        if (vdObject != null) {
            try {
                vdObject.getClass().getMethod("release").invoke(vdObject);
            } catch (Throwable ignored) {}
            vdObject = null;
        }
        displayId = -1;
        lastPng = null;
        if (sinkSurface != null) try { sinkSurface.release(); } catch (Throwable ignored) {}
        if (imageReader != null) try { imageReader.close(); } catch (Throwable ignored) {}
    }

    /**
     * Destroying a display while its tasks live migrates them onto the
     * default display — the agent's apps would spill onto the user's
     * screen. Force-stop the packages whose tasks sit on this display
     * first instead. Task data comes from `dumpsys activity activities`:
     * the IActivityTaskManager binder exposes no getTasks to reflect.
     */
    private static void removeTasksOnDisplay(int displayId) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/sh", "-c", "dumpsys activity activities"});
            String dump = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            // Collect package candidates from the display's section:
            // component forms `I=com.pkg/.Cls` and `ActivityRecord{... pkg/.Cls}`
            // are exact; `A=uid:affinity` may carry a task-affinity suffix.
            java.util.Set<String> cands = new java.util.HashSet<>();
            java.util.regex.Matcher comp = java.util.regex.Pattern
                    .compile("(?:I=|ActivityRecord\\{[^\\n]*? u\\d+ )([\\w.]+)/")
                    .matcher("");
            java.util.regex.Matcher aff = java.util.regex.Pattern
                    .compile("A=\\d+:([\\w.]+)").matcher("");
            boolean onDisplay = false;
            for (String line : dump.split("\n")) {
                // Display sections and top-level dump headings sit at col 0;
                // everything inside a display's block is indented. Trailing
                // recap lines (mFocusedApp etc.) live outside — ignore them.
                if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                    onDisplay = line.contains("Display #" + displayId + " ");
                }
                if (!onDisplay) continue;
                comp.reset(line);
                while (comp.find()) cands.add(comp.group(1));
                aff.reset(line);
                while (aff.find()) cands.add(aff.group(1));
            }
            java.util.Set<String> pkgs = new java.util.HashSet<>();
            for (String cand : cands) {
                String pkg = resolvePackage(cand);
                if (pkg != null && !pkg.startsWith("com.boxagent.")) pkgs.add(pkg);
            }
            for (String pkg : pkgs) {
                log("force-stop " + pkg + " (display " + displayId + " teardown)");
                try {
                    Runtime.getRuntime().exec(new String[]{
                            "/system/bin/sh", "-c", "am force-stop " + pkg})
                            .waitFor();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            log("removeTasksOnDisplay failed: " + t);
        }
    }

    /** `pm path` probe: shrink an affinity-ish candidate to a real package. */
    private static String resolvePackage(String cand) {
        for (String s = cand; s != null && s.contains("."); s = s.substring(0, s.lastIndexOf('.'))) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "/system/bin/sh", "-c", "pm path " + s});
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor();
                if (out.contains("package:")) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Frame capture

    private static byte[] lastPng;

    /** Latest composited frame as a PNG. Frames arrive on change only, so we
     *  cache the last encode — a screenshot between changes returns it. */
    private static byte[] capturePng() {
        if (imageReader == null) throw new IllegalStateException("no display");
        android.media.Image img = imageReader.acquireLatestImage();
        if (img == null) return lastPng; // nothing new rendered
        try {
            android.media.Image.Plane p = img.getPlanes()[0];
            java.nio.ByteBuffer buf = p.getBuffer();
            int stride = p.getRowStride(), px = p.getPixelStride();
            int w = img.getWidth(), h = img.getHeight();
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            // RGBA_8888 rows may be padded — copy row-wise when stride differs.
            if (stride == w * px) {
                bmp.copyPixelsFromBuffer(buf);
            } else {
                byte[] row = new byte[w * px];
                for (int y = 0; y < h; y++) {
                    buf.position(y * stride);
                    buf.get(row, 0, row.length);
                    bmp.setPixels(intsOf(row), 0, w, 0, y, w, 1);
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            lastPng = out.toByteArray();
            return lastPng;
        } finally {
            img.close();
        }
    }

    private static int[] intsOf(byte[] rgba) {
        int[] px = new int[rgba.length / 4];
        for (int i = 0; i < px.length; i++) {
            int o = i * 4;
            px[i] = (rgba[o] & 0xff) << 16 | (rgba[o + 1] & 0xff) << 8
                    | (rgba[o + 2] & 0xff) | 0xff000000 | ((rgba[o + 3] & 0xff) << 24);
        }
        return px;
    }

    private static void requireDisplay() {
        if (displayId < 0) throw new IllegalStateException("no virtual display — create first");
    }

    // ------------------------------------------------------------------
    // Input injection (all events stamped with the virtual display id)

    private static void injectTap(double x, double y, long durationMs) {
        long down = SystemClock.uptimeMillis();
        touchEvent(down, MotionEvent.ACTION_DOWN, f(x), f(y), new int[]{0});
        touchEvent(down + durationMs, MotionEvent.ACTION_UP, f(x), f(y), new int[]{0});
    }

    private static void injectSwipe(double x1, double y1, double x2, double y2, long durationMs) {
        long down = SystemClock.uptimeMillis();
        touchEvent(down, MotionEvent.ACTION_DOWN, f(x1), f(y1), new int[]{0});
        int steps = Math.max(2, (int) (durationMs / 16));
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            touchEvent(down + (long) (t * durationMs), MotionEvent.ACTION_MOVE,
                    f(x1 + (x2 - x1) * t), f(y1 + (y2 - y1) * t), new int[]{0});
        }
        touchEvent(down + durationMs, MotionEvent.ACTION_UP, f(x2), f(y2), new int[]{0});
    }

    /** Two fingers converging (zoom_in) or diverging, over 300 ms. */
    private static void injectPinch(double cx, double cy, boolean zoomIn, int percent) {
        float span = (Math.min(100, Math.max(1, percent)) / 100f) * 400f;
        float from = zoomIn ? 60f : span, to = zoomIn ? span : 60f;
        long down = SystemClock.uptimeMillis();
        float p1x = (float) cx - from, p2x = (float) cx + from;
        int secondPointerDown = MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        int secondPointerUp = MotionEvent.ACTION_POINTER_UP
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        touchEvent(down, MotionEvent.ACTION_DOWN,
                new float[]{p1x}, new float[]{(float) cy}, new int[]{0});
        touchEvent(down + 10, secondPointerDown,
                new float[]{p1x, p2x}, new float[]{(float) cy, (float) cy}, new int[]{0, 1});
        int steps = 16;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float d = from + (to - from) * t;
            touchEvent(down + 10 + (long) (t * 290), MotionEvent.ACTION_MOVE,
                    new float[]{(float) cx - d, (float) cx + d},
                    new float[]{(float) cy, (float) cy}, new int[]{0, 1});
        }
        long end = down + 300;
        touchEvent(end, secondPointerUp,
                new float[]{(float) cx - to, (float) cx + to},
                new float[]{(float) cy, (float) cy}, new int[]{0, 1});
        touchEvent(end + 10, MotionEvent.ACTION_UP,
                new float[]{(float) cx - to}, new float[]{(float) cy}, new int[]{0});
    }

    private static void injectKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        inject(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    /** Best-effort per-character key events; characters without a keymap
     *  entry are skipped (the a11y set-text path carries real text anyway). */
    private static void injectText(String text) {
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (char c : text.toCharArray()) {
            KeyEvent[] events = kcm.getEvents(new char[]{c});
            if (events == null) continue;
            for (KeyEvent e : events) inject(e);
        }
    }

    private static void touchEvent(long downTime, int action, float[] xs, float[] ys, int[] ids) {
        touchEvent(downTime, action, xs, ys, ids, xs.length);
    }

    private static void touchEvent(long downTime, int action, float[] xs, float[] ys,
                                   int[] ids, int pointerCount) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = ids[i];
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = ys[i];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        MotionEvent ev = MotionEvent.obtain(
                downTime, Math.max(now, downTime), action, pointerCount,
                props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        inject(ev);
    }

    private static Method setDisplayIdM;
    private static Object inputManager;
    private static Method injectM;

    private static void inject(InputEvent ev) {
        try {
            if (setDisplayIdM == null) {
                setDisplayIdM = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
                setDisplayIdM.setAccessible(true);
            }
            setDisplayIdM.invoke(ev, displayId);
        } catch (Throwable ignored) {
            // Older API: events without a display id land on the default
            // display — caller sees ok but the physical screen moves; the
            // manager only targets devices where the call exists.
        }
        try {
            if (injectM == null) {
                Object im = null;
                Method m = null;
                // InputManagerGlobal is the singleton on modern Android; the
                // legacy InputManager.getInstance() can throw on new builds.
                try {
                    Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
                    im = g.getDeclaredMethod("getInstance").invoke(null);
                    m = g.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
                } catch (Throwable ignored) {}
                if (im == null || m == null) {
                    im = InputManager.class.getDeclaredMethod("getInstance").invoke(null);
                    m = InputManager.class.getDeclaredMethod(
                            "injectInputEvent", InputEvent.class, int.class);
                }
                inputManager = im;
                injectM = m;
                log("input target " + im);
            }
            injectM.invoke(inputManager, ev, INJECT_ASYNC);
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException
                    ? t.getCause() : t;
            log("inject failed: " + c);
            throw new IllegalStateException("inject failed: "
                    + (c != null && c.getMessage() != null
                            ? c.getMessage() : String.valueOf(c)));
        }
    }

    // ------------------------------------------------------------------

    private static float[] f(double... v) {
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) v[i];
        return out;
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void log(String s) {
        System.out.println("[vscreen] " + s);
        System.out.flush();
    }

    private Main() {}

    /** Minimal Context for createVirtualDisplay — overrides only what the
     *  display path calls; everything else would hit the null base and throw,
     *  which surfaces in the log if a new API touches another method. */
    static final class FakeContext extends android.content.ContextWrapper {
        FakeContext() { super(null); }

        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public String getAttributionTag() { return null; }
        @Override public int getDeviceId() { return 0; }
        // Flag validation for trusted displays calls these on our context —
        // the authoritative check happens server-side against our binder
        // uid (shell), so answering granted locally is honest.
        @Override public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkCallingPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public android.content.res.Resources getResources() {
            return android.content.res.Resources.getSystem();
        }
        @Override public android.content.Context getApplicationContext() { return this; }
        @Override public android.content.Context createPackageContext(String pkg, int flags) {
            return this;
        }
    }

    /** Hidden-API access for the shell app_process process — mirrors the
     *  workaround every app_process helper needs on modern Android. */
    static final class Workarounds {
        static void prepare() {
            try {
                Object runtime = Class.forName("dalvik.system.VMRuntime")
                        .getDeclaredMethod("getRuntime").invoke(null);
                Method setExemptions = Class.forName("dalvik.system.VMRuntime")
                        .getDeclaredMethod("setHiddenApiExemptions", String[].class);
                setExemptions.invoke(runtime, (Object) new String[]{"L"});
            } catch (Throwable t) {
                log("hidden-api exemptions failed: " + t.getMessage());
            }
        }

        private Workarounds() {}
    }
}
