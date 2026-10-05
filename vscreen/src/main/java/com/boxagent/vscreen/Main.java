package com.boxagent.vscreen;

import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Shell-uid companion to boxagentd: owns one logical virtual display and
 * injects input onto it. Runs under `app_process`; speaks the same frame
 * format as the daemon (4-byte BE length + JSON) on a loopback TCP port.
 *
 * Why this exists: a usable virtual screen needs a *logical* display —
 * `am start --display`, accessibility windows and input routing all key
 * off DisplayManager ids, so `DisplayManagerGlobal.createVirtualDisplay`
 * is the only path. It's a hidden API, so everything there is reflection.
 *
 * Why TCP: SELinux on user builds denies apps `connectto` on unix sockets
 * owned by the shell domain, so an abstract socket only works on
 * permissive builds. The token keeps other local apps out.
 */
public final class Main {

    private static final String VERSION = "3";
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int AUTH_TIMEOUT_MS = 5_000;

    private static final int INJECT_ASYNC = 0;   // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
    private static final int IME_POLICY_HIDE = 2; // WindowManager.DISPLAY_IME_POLICY_HIDE

    private static volatile int displayId = -1;
    private static int displayW, displayH, displayDpi;
    private static Object vdObject; // android.hardware.display.VirtualDisplay — release() frees it
    private static android.media.ImageReader imageReader;
    /** Which optional behaviours the display actually got (reported in `info`). */
    private static boolean trusted, ownFocus, destroysContent, imeHidden;

    // Newest composited frame. The reader is drained as frames arrive —
    // left alone its queue fills and later frames are dropped, so an
    // on-demand acquire would hand back a stale, mid-animation frame.
    private static final Object frameLock = new Object();
    private static android.media.Image latestFrame;
    private static long frameSeq;
    private static HandlerThread frameThread;

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
        System.exit(1);
    }

    private static void run(String[] args) throws Exception {
        int port = -1;
        String token = "";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
            if (args[i].equals("--token")) token = args[i + 1];
        }
        if (port <= 0 || token.isEmpty()) throw new IllegalArgumentException("--port and --token required");
        final String tok = token;
        Workarounds.prepare();

        // Loopback only. A port that is taken means another host (or
        // anything else) owns it — exit rather than fight over it.
        ServerSocket server = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));

        // Shell can't enumerate our process (restricted /proc view), so
        // the manager stops us through this pid file instead of pkill.
        try {
            java.nio.file.Files.write(
                    new java.io.File("/data/local/tmp/boxagent-vscreen.pid").toPath(),
                    String.valueOf(android.os.Process.myPid()).getBytes());
        } catch (Throwable t) {
            log("pid file: " + t);
        }
        log("vscreen host v" + VERSION + " up on 127.0.0.1:" + port);
        try {
            while (true) {
                Socket conn = server.accept();
                conn.setTcpNoDelay(true);
                new Thread(() -> serve(conn, tok), "vscreen-conn").start();
            }
        } finally {
            destroyDisplay();
        }
    }

    // ------------------------------------------------------------------
    // Framing + dispatch

    private static void serve(Socket conn, String token) {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(conn.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(conn.getOutputStream()));
            // Any local app can reach the port: a peer that won't
            // authenticate promptly is dropped.
            conn.setSoTimeout(AUTH_TIMEOUT_MS);
            JSONObject hello = readFrame(in);
            if (hello == null || !"auth".equals(hello.optString("op"))
                    || !token.equals(hello.optString("token"))) {
                send(out, new JSONObject().put("ok", false).put("error", "bad auth"));
                conn.close();
                return;
            }
            conn.setSoTimeout(0);
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
                return info();
            case "create": {
                int w = req.optInt("w", 1080), h = req.optInt("h", 2400);
                int dpi = req.optInt("dpi", 420);
                ensureDisplay(w, h, dpi);
                return info();
            }
            case "destroy":
                new Thread(() -> {
                    sleep(150); // let the reply reach the client first
                    destroyDisplay(); // drops our tasks, releases the display
                    System.exit(0);
                }).start();
                return ok();
            case "tap":
                requireDisplay();
                injectPress(req.getDouble("x"), req.getDouble("y"), 50);
                return ok();
            case "long_press":
                requireDisplay();
                injectPress(req.getDouble("x"), req.getDouble("y"),
                        Math.max(500, req.optLong("duration_ms", 800)));
                return ok();
            case "swipe":
                requireDisplay();
                injectSwipe(
                        req.getDouble("x1"), req.getDouble("y1"),
                        req.getDouble("x2"), req.getDouble("y2"),
                        req.optLong("duration_ms", 300));
                return ok();
            case "drag":
                requireDisplay();
                injectDrag(
                        req.getDouble("x1"), req.getDouble("y1"),
                        req.getDouble("x2"), req.getDouble("y2"),
                        req.optLong("hold_ms", 350),
                        req.optLong("duration_ms", 500));
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
            case "screenshot":
                requireDisplay();
                return screenshot(
                        req.optString("format", "png"),
                        req.optInt("quality", 80),
                        req.optInt("max_side", 0));
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

    private static synchronized JSONObject info() throws Exception {
        long seq;
        synchronized (frameLock) { seq = frameSeq; }
        return ok()
                .put("display_id", displayId)
                .put("w", displayW).put("h", displayH).put("dpi", displayDpi)
                .put("trusted", trusted)
                .put("own_focus", ownFocus)
                .put("destroys_content", destroysContent)
                .put("ime_hidden", imeHidden)
                .put("frame_seq", seq)
                .put("version", VERSION);
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
     *  positions moved across API levels, never hardcode them. 0 when the
     *  flag doesn't exist on this build. */
    private static int vdFlag(Class<?> dmCls, String name) {
        try {
            return dmCls.getDeclaredField(name).getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static synchronized void ensureDisplay(int w, int h, int dpi) throws Exception {
        if (displayId >= 0) {
            if (w != displayW || h != displayH || dpi != displayDpi) resizeDisplay(w, h, dpi);
            return;
        }
        Class<?> dm = Class.forName("android.hardware.display.DisplayManager");
        // Minimal: what the agent can't work without. OWN_CONTENT_ONLY is
        // deliberately absent — it hides the display's windows from
        // accessibility services, and a11y is how the agent sees.
        int minimal = vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_PUBLIC")
                | vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        // Permission-free improvements:
        //  DESTROY_CONTENT_ON_REMOVAL — on teardown the display's tasks are
        //    destroyed instead of migrating onto the user's screen;
        //  TOUCH_FEEDBACK_DISABLED — agent taps don't buzz the phone.
        int base = minimal
                | vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")
                | vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED");
        // Trusted (shell holds ADD_TRUSTED_DISPLAY on stock builds):
        //  OWN_FOCUS (14+) — the display keeps its own focused window, so
        //    keys/text reach the agent's app without stealing focus (and
        //    the IME connection) from whatever the user is doing;
        //  STEAL_TOP_FOCUS_DISABLED (15+) — touching it doesn't make it
        //    the top-focused display either.
        int trustedFlags = base
                | vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_TRUSTED")
                | vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
                | vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED");

        android.media.ImageReader reader = newReader(w, h);
        // Current before the display exists: its very first frame may be
        // the only one for a while (static content).
        synchronized (frameLock) { imageReader = reader; }
        Throwable last = null;
        for (int flags : new int[]{trustedFlags, base, minimal}) {
            try {
                Object vd = createVirtualDisplay(w, h, dpi, flags, reader.getSurface());
                int id = displayIdOf(vd);
                if (id <= 0) throw new IllegalStateException("no display id");
                vdObject = vd;
                displayId = id;
                displayW = w;
                displayH = h;
                displayDpi = dpi;
                trusted = (flags & vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_TRUSTED")) != 0;
                ownFocus = (flags & vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")) != 0;
                destroysContent =
                        (flags & vdFlag(dm, "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")) != 0;
                log("created display " + id + " " + w + "x" + h + "@" + dpi
                        + " flags=0x" + Integer.toHexString(flags));
                // Agent text goes in through accessibility (set-text); a
                // soft keyboard here would fall back to the default display
                // and pop up on the user's screen.
                imeHidden = hideIme(id);
                return;
            } catch (Throwable t) {
                last = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                        ? t.getCause() : t;
                log("createVirtualDisplay flags=0x" + Integer.toHexString(flags) + " failed: " + last);
            }
        }
        synchronized (frameLock) { imageReader = null; }
        reader.close();
        throw new IllegalStateException("createVirtualDisplay failed: "
                + (last != null ? String.valueOf(last.getMessage()) : "no compatible signature"));
    }

    private static Object createVirtualDisplay(int w, int h, int dpi, int flags, Surface surface)
            throws Exception {
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
        call(builder, "setSurface", new Class<?>[]{Surface.class}, surface);
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
            // Android 11+: (Context, MediaProjection, VirtualDisplayConfig,
            // Callback, Handler|Executor) — Context gets ours, rest nullable.
            Class<?>[] types = m.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean usable = false;
            for (int i = 0; i < types.length; i++) {
                String n = types[i].getName();
                if (n.endsWith("VirtualDisplayConfig")) { args[i] = config; usable = true; }
                else if (n.equals("android.content.Context")) args[i] = ctx;
                else if (types[i].isPrimitive()) { usable = false; break; }
                else args[i] = null;
            }
            if (!usable) continue;
            try {
                m.setAccessible(true);
                Object vd = m.invoke(dmg, args);
                if (vd != null) return vd;
            } catch (java.lang.reflect.InvocationTargetException t) {
                // SecurityException: a flag we may not use — the caller
                // retries with a smaller set. Anything else: maybe just
                // the wrong overload, try the next one.
                if (t.getCause() instanceof SecurityException) throw t;
                last = t.getCause() != null ? t.getCause() : t;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("no compatible createVirtualDisplay"
                + (last != null ? ": " + last : ""));
    }

    private static android.media.ImageReader newReader(int w, int h) {
        if (frameThread == null) {
            frameThread = new HandlerThread("vscreen-frames");
            frameThread.start();
        }
        android.media.ImageReader r = android.media.ImageReader.newInstance(
                w, h, android.graphics.PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(reader -> {
            android.media.Image img;
            try {
                img = reader.acquireLatestImage();
            } catch (Throwable t) {
                return; // all buffers held; the next callback catches up
            }
            if (img == null) return;
            synchronized (frameLock) {
                if (reader != imageReader) { img.close(); return; } // resized away
                if (latestFrame != null) latestFrame.close();
                latestFrame = img;
                frameSeq++;
            }
        }, new Handler(frameThread.getLooper()));
        return r;
    }

    /** New size: point the display at a fresh reader, then resize it —
     *  both public VirtualDisplay calls, no recreation (tasks survive). */
    private static void resizeDisplay(int w, int h, int dpi) throws Exception {
        android.media.ImageReader old = imageReader;
        android.media.ImageReader fresh = newReader(w, h);
        synchronized (frameLock) {
            imageReader = fresh;
            if (latestFrame != null) { latestFrame.close(); latestFrame = null; }
        }
        call(vdObject, "setSurface", new Class<?>[]{Surface.class}, fresh.getSurface());
        call(vdObject, "resize", new Class<?>[]{int.class, int.class, int.class}, w, h, dpi);
        displayW = w;
        displayH = h;
        displayDpi = dpi;
        if (old != null) try { old.close(); } catch (Throwable ignored) {}
        log("resized display " + displayId + " to " + w + "x" + h + "@" + dpi);
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

    /** IWindowManager.setDisplayImePolicy(id, HIDE) (12+), or
     *  setShouldShowIme(id, false) on 11. Shell holds the permission on
     *  stock builds (scrcpy's --display-ime-policy relies on it). */
    private static boolean hideIme(int id) {
        try {
            Object wm = service("window", "android.view.IWindowManager$Stub");
            try {
                wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class)
                        .invoke(wm, id, IME_POLICY_HIDE);
            } catch (NoSuchMethodException e) {
                wm.getClass().getMethod("setShouldShowIme", int.class, boolean.class)
                        .invoke(wm, id, false);
            }
            return true;
        } catch (Throwable t) {
            log("ime policy: " + (t.getCause() != null ? t.getCause() : t));
            return false;
        }
    }

    private static Object service(String name, String stub) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    private static synchronized void destroyDisplay() {
        int id = displayId;
        // With DESTROY_CONTENT_ON_REMOVAL the framework finishes the
        // display's tasks itself. Without it they would migrate onto the
        // user's screen — remove exactly those root tasks first.
        if (id >= 0 && !destroysContent) removeTasksOnDisplay(id);
        if (vdObject != null) {
            try {
                vdObject.getClass().getMethod("release").invoke(vdObject);
            } catch (Throwable ignored) {}
            vdObject = null;
        }
        displayId = -1;
        synchronized (frameLock) {
            if (latestFrame != null) { latestFrame.close(); latestFrame = null; }
            if (imageReader != null) try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
    }

    /**
     * Fallback teardown for builds without DESTROY_CONTENT_ON_REMOVAL:
     * remove the root tasks `am stack list` places on this display, by
     * id. Never force-stops packages — that would also kill the user's
     * own copy of the app on the physical screen.
     */
    private static void removeTasksOnDisplay(int displayId) {
        try {
            String dump = sh("am stack list");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:RootTask|Stack) id=(\\d+)[^\\n]*?displayId=(\\d+)")
                    .matcher(dump);
            while (m.find()) {
                if (Integer.parseInt(m.group(2)) != displayId) continue;
                log("remove root task " + m.group(1) + " (display " + displayId + " teardown)");
                sh("am stack remove " + m.group(1));
            }
        } catch (Throwable t) {
            log("removeTasksOnDisplay failed: " + t);
        }
    }

    private static String sh(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }

    // ------------------------------------------------------------------
    // Frame capture

    /**
     * Newest composited frame, optionally downscaled to [maxSide] and
     * JPEG-encoded — the vision path wants ~1024px JPEG, and doing that
     * here beats shipping a full-size PNG over the socket to re-encode.
     * Returns data=null when nothing has rendered yet.
     */
    private static JSONObject screenshot(String format, int quality, int maxSide) throws Exception {
        android.graphics.Bitmap bmp;
        long seq;
        synchronized (frameLock) {
            if (latestFrame == null) {
                return ok().put("data_b64", JSONObject.NULL).put("frame_seq", frameSeq);
            }
            bmp = toBitmap(latestFrame);
            seq = frameSeq;
        }
        int srcW = bmp.getWidth(), srcH = bmp.getHeight();
        if (maxSide > 0 && Math.max(srcW, srcH) > maxSide) {
            float s = maxSide / (float) Math.max(srcW, srcH);
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                    bmp, Math.max(1, Math.round(srcW * s)), Math.max(1, Math.round(srcH * s)), true);
            bmp.recycle();
            bmp = scaled;
        }
        boolean jpeg = "jpeg".equals(format) || "jpg".equals(format);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bmp.compress(jpeg ? android.graphics.Bitmap.CompressFormat.JPEG
                        : android.graphics.Bitmap.CompressFormat.PNG,
                Math.max(1, Math.min(100, quality)), out);
        JSONObject r = ok()
                .put("data_b64", android.util.Base64.encodeToString(
                        out.toByteArray(), android.util.Base64.NO_WRAP))
                .put("mime", jpeg ? "image/jpeg" : "image/png")
                .put("w", bmp.getWidth()).put("h", bmp.getHeight())
                .put("src_w", srcW).put("src_h", srcH)
                .put("frame_seq", seq);
        bmp.recycle();
        return r;
    }

    /** RGBA_8888 image → ARGB bitmap. Rows may be padded: copy the padded
     *  buffer whole and crop, instead of a per-row pixel loop. */
    private static android.graphics.Bitmap toBitmap(android.media.Image img) {
        android.media.Image.Plane p = img.getPlanes()[0];
        java.nio.ByteBuffer buf = p.getBuffer();
        buf.rewind();
        int w = img.getWidth(), h = img.getHeight();
        int px = p.getPixelStride(), stride = p.getRowStride();
        int padded = stride / px;
        if (buf.remaining() < stride * h) {
            // The last row usually isn't padded — pad the copy so the
            // whole-buffer copy below has stride*h bytes to read.
            java.nio.ByteBuffer tmp = java.nio.ByteBuffer.allocate(stride * h);
            tmp.put(buf);
            tmp.rewind();
            buf = tmp;
        }
        android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(
                padded, h, android.graphics.Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buf);
        if (padded == w) return full;
        android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(full, 0, 0, w, h);
        full.recycle();
        return cropped;
    }

    private static void requireDisplay() {
        if (displayId < 0) throw new IllegalStateException("no virtual display — create first");
    }

    // ------------------------------------------------------------------
    // Input injection (all events stamped with the virtual display id).
    //
    // Events go out in real time: views decide long-press, fling and
    // drag-vs-tap from wall-clock gaps between deliveries, so injecting a
    // whole gesture at once (future timestamps) turns a long press into
    // a tap and a scroll into a jump.

    private static void injectPress(double x, double y, long durationMs) {
        long down = SystemClock.uptimeMillis();
        touchEvent(down, MotionEvent.ACTION_DOWN, f(x), f(y), new int[]{0});
        sleep(durationMs);
        touchEvent(down, MotionEvent.ACTION_UP, f(x), f(y), new int[]{0});
    }

    private static void injectSwipe(double x1, double y1, double x2, double y2, long durationMs) {
        durationMs = Math.max(50, Math.min(durationMs, 10_000));
        long down = SystemClock.uptimeMillis();
        touchEvent(down, MotionEvent.ACTION_DOWN, f(x1), f(y1), new int[]{0});
        int steps = Math.max(2, (int) (durationMs / 16));
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            sleepUntil(down + (long) (t * durationMs));
            touchEvent(down, MotionEvent.ACTION_MOVE,
                    f(x1 + (x2 - x1) * t), f(y1 + (y2 - y1) * t), new int[]{0});
        }
        touchEvent(down, MotionEvent.ACTION_UP, f(x2), f(y2), new int[]{0});
    }

    /** Down, hold in place (long-press registers the drag), then move to
     *  the target and release. */
    private static void injectDrag(double x1, double y1, double x2, double y2,
                                   long holdMs, long durationMs) {
        holdMs = Math.max(0, Math.min(holdMs, 5_000));
        durationMs = Math.max(50, Math.min(durationMs, 10_000));
        long down = SystemClock.uptimeMillis();
        touchEvent(down, MotionEvent.ACTION_DOWN, f(x1), f(y1), new int[]{0});
        sleep(holdMs);
        int steps = Math.max(2, (int) (durationMs / 16));
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            sleepUntil(down + holdMs + (long) (t * durationMs));
            touchEvent(down, MotionEvent.ACTION_MOVE,
                    f(x1 + (x2 - x1) * t), f(y1 + (y2 - y1) * t), new int[]{0});
        }
        touchEvent(down, MotionEvent.ACTION_UP, f(x2), f(y2), new int[]{0});
    }

    /** Two fingers converging (zoom_in=false) or diverging, over 300 ms. */
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
        sleep(10);
        touchEvent(down, secondPointerDown,
                new float[]{p1x, p2x}, new float[]{(float) cy, (float) cy}, new int[]{0, 1});
        int steps = 16;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float d = from + (to - from) * t;
            sleepUntil(down + 10 + (long) (t * 290));
            touchEvent(down, MotionEvent.ACTION_MOVE,
                    new float[]{(float) cx - d, (float) cx + d},
                    new float[]{(float) cy, (float) cy}, new int[]{0, 1});
        }
        touchEvent(down, secondPointerUp,
                new float[]{(float) cx - to, (float) cx + to},
                new float[]{(float) cy, (float) cy}, new int[]{0, 1});
        sleep(10);
        touchEvent(down, MotionEvent.ACTION_UP,
                new float[]{(float) cx - to}, new float[]{(float) cy}, new int[]{0});
    }

    private static void injectKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        inject(key(now, KeyEvent.ACTION_DOWN, keyCode));
        inject(key(now, KeyEvent.ACTION_UP, keyCode));
    }

    private static KeyEvent key(long time, int action, int code) {
        // Same shape `input keyevent` sends: virtual keyboard, keyboard source.
        return new KeyEvent(time, time, action, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
    }

    /** Key events for [text]; all-or-nothing — a character without a key
     *  mapping (CJK, emoji…) fails the op so the client can fall back to
     *  accessibility set-text instead of typing half a string. */
    private static void injectText(String text) {
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] events = kcm.getEvents(text.toCharArray());
        if (events == null) throw new IllegalStateException("text not typeable as key events");
        for (KeyEvent e : events) {
            e.setSource(InputDevice.SOURCE_KEYBOARD);
            inject(e);
        }
    }

    private static void touchEvent(long downTime, int action, float[] xs, float[] ys, int[] ids) {
        int pointerCount = xs.length;
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
        try {
            inject(ev);
        } finally {
            ev.recycle();
        }
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
        } catch (Throwable t) {
            // Without a display id the event would land on the user's
            // physical screen — refuse instead.
            throw new IllegalStateException("cannot target display: " + t);
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
        Method m = target.getClass().getMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void sleepUntil(long uptimeMs) {
        sleep(uptimeMs - SystemClock.uptimeMillis());
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
