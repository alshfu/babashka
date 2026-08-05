/**
 * LanAgent — on-device агент управления без USB/ADB.
 *
 * Работает под app_process от shell (UID 2000): принимает JSON-lines команды по TCP
 * и исполняет их локально: инъекция касаний через InputManager.injectInputEvent
 * (тот же путь, что у scrcpy — BankID принимает как физический тач), дамп UI через
 * uiautomator, скриншоты через screencap, произвольные shell-команды.
 *
 * Протокол (по одной команде на соединение, одна строка запрос → одна строка ответ):
 *   {"t":"tap","x":0.5,"y":0.75}            координаты — доли экрана 0..1
 *   {"t":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"ms":250}
 *   {"t":"texts"}                            тексты с экрана (uiautomator)
 *   {"t":"wait-text","text":"Säkerhetskod","timeoutMs":20000}
 *   {"t":"shot"}                             {"png_b64": "..."}  (BankID чёрный — FLAG_SECURE)
 *   {"t":"shell","command":"wm size"}
 *   {"t":"focus"}
 *   {"t":"nav","action":"back|home|recents"}
 *   {"t":"bankid-login","pin":"<PIN>"}    confirm → PIN → identifiera
 *
 * Дополнительно на порту (port+1) поднят TCP→localabstract релей: каждое входящее
 * TCP-соединение пробрасывается в abstract-сокет "scrcpy" (scrcpy-server 4.0 слушает
 * только localabstract). По нему scrcpy-lan.mjs получает video/audio/control сокеты
 * scrcpy-сервера по LAN — инъекция выполняется самим scrcpy-server, которую BankID
 * принимает (собственная инъекция LanAgent BankID отклоняет после ввода PIN).
 *
 * Запуск:  setsid sh -c 'CLASSPATH=/data/local/tmp/lanagent.dex app_process / LanAgent 47201 \
 *            </dev/null >/sdcard/lanagent.log 2>&1 &'
 */
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LanAgent {
    static int W = 720, H = 1600;
    static Object inputManager;
    static Method injectMethod;
    static final Object CMD_LOCK = new Object();
    static long bootMs = System.currentTimeMillis();

    public static void main(String[] args) {
        try {
            final int port = args.length > 0 ? Integer.parseInt(args[0]) : 47201;

            String size = exec("wm size", 5000);
            Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(size);
            if (m.find()) { W = Integer.parseInt(m.group(1)); H = Integer.parseInt(m.group(2)); }

            // InputManager.getInstance() требует main-Looper — в app_process его нет,
            // готовим сами (scrcpy делает то же самое).
            Class<?> looperCls = Class.forName("android.os.Looper");
            Method prep = looperCls.getDeclaredMethod("prepareMainLooper");
            prep.invoke(null);

            Object im = null;
            String path = null;
            // Android 14+: InputManagerGlobal; старее: InputManager.getInstance()
            for (String cn : new String[]{"android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager"}) {
                try {
                    Class<?> cls = Class.forName(cn);
                    Method getInstance = cls.getDeclaredMethod("getInstance");
                    im = getInstance.invoke(null);
                    if (im != null) { path = cn; break; }
                } catch (Throwable e) {
                    log("getInstance via " + cn + " failed: " + stack(e));
                }
            }
            if (im == null) throw new IllegalStateException("no InputManager");
            inputManager = im;
            injectMethod = im.getClass().getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            injectMethod.setAccessible(true);
            log("input path: " + path);

            log("ready " + W + "x" + H + " port " + port);
            startRelay(port + 1, "scrcpy");
            Thread server = new Thread(() -> {
                try {
                    ServerSocket ss = new ServerSocket(port);
                    while (true) {
                        final Socket s = ss.accept();
                        Thread t = new Thread(() -> handle(s));
                        t.setDaemon(true);
                        t.start();
                    }
                } catch (Throwable e) {
                    log("server fatal " + e);
                    System.exit(1);
                }
            });
            server.setDaemon(false);
            server.start();

            // качаем main-Looper: InputManager шлёт колбэки через него
            Method loop = looperCls.getDeclaredMethod("loop");
            loop.invoke(null);
        } catch (Throwable e) {
            log("fatal " + stack(e));
            System.exit(1);
        }
    }

    static String stack(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) sb.append(t).append(" <- ");
        return sb.toString();
    }

    static void handle(Socket s) {
        try {
            s.setSoTimeout(120000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
            String line = r.readLine();
            JSONObject res;
            if (line == null) return;
            JSONObject msg;
            try { msg = new JSONObject(line); }
            catch (Exception e) { w.write("{\"ok\":false,\"err\":\"bad json\"}\n"); w.flush(); return; }
            long started = System.currentTimeMillis();
            synchronized (CMD_LOCK) {
                res = dispatch(msg);
            }
            res.put("id", msg.optInt("id", 0));
            res.put("ms", System.currentTimeMillis() - started);
            w.write(res.toString() + "\n");
            w.flush();
        } catch (Throwable e) {
            log("conn err " + e);
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    static JSONObject dispatch(JSONObject msg) {
        try {
            String t = msg.optString("t", "");
            switch (t) {
                case "ping":
                    return ok().put("uptimeMs", System.currentTimeMillis() - bootMs);
                case "tap": {
                    tap((float) (msg.getDouble("x") * W), (float) (msg.getDouble("y") * H));
                    return ok();
                }
                case "swipe": {
                    swipe((float) (msg.getDouble("x1") * W), (float) (msg.getDouble("y1") * H),
                          (float) (msg.getDouble("x2") * W), (float) (msg.getDouble("y2") * H),
                          msg.optLong("ms", 250));
                    return ok();
                }
                case "texts":
                    return ok().put("texts", new JSONArray(currentTexts(30)));
                case "wait-text": {
                    List<String> r = waitText(msg.getString("text"), msg.optLong("timeoutMs", 20000));
                    JSONObject o = ok().put("texts", new JSONArray(r.size() > 30 ? r.subList(0, 30) : r));
                    if (r.isEmpty()) return err("timeout waiting text").put("texts", o.get("texts"));
                    return o;
                }
                case "shot": {
                    String b64 = screenshotB64();
                    if (b64 == null) return err("screencap failed");
                    return ok().put("png_b64", b64);
                }
                case "shell":
                    return ok().put("out", cut(exec(msg.optString("command", ""), 30000), 2000));
                case "focus":
                    return ok().put("out", cut(exec("dumpsys window | grep mCurrentFocus", 15000), 500));
                case "nav": {
                    String key;
                    switch (msg.optString("action", "")) {
                        case "back": key = "KEYCODE_BACK"; break;
                        case "home": key = "KEYCODE_HOME"; break;
                        case "recents": key = "KEYCODE_APP_SWITCH"; break;
                        default: return err("unknown nav action");
                    }
                    return ok().put("out", exec("input keyevent " + key, 5000));
                }
                case "bankid-login":
                    return bankIdLogin(msg.getString("pin"));
                default:
                    return err("unknown type");
            }
        } catch (Throwable e) {
            return err(String.valueOf(e));
        }
    }

    // ── TCP → localabstract релей (LAN-доступ к scrcpy-server) ─────────────

    static void startRelay(final int port, final String socketName) {
        Thread t = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(port);
                log("relay :" + port + " -> localabstract:" + socketName);
                while (true) {
                    final Socket client = ss.accept();
                    Thread h = new Thread(() -> relayOne(client, socketName));
                    h.setDaemon(true);
                    h.start();
                }
            } catch (Throwable e) {
                log("relay fatal " + e);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    static void relayOne(Socket client, String socketName) {
        LocalSocket local = new LocalSocket();
        try {
            local.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
        } catch (Throwable e) {
            log("relay connect fail: " + e);
            try { client.close(); } catch (Throwable ignored) {}
            return;
        }
        final LocalSocket l = local;
        Thread a = new Thread(() -> pump(client, l, true));   // client -> scrcpy
        Thread b = new Thread(() -> pump(client, l, false));  // scrcpy -> client
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
    }

    static void pump(Socket c, LocalSocket l, boolean clientToLocal) {
        try {
            java.io.InputStream in = clientToLocal ? c.getInputStream() : l.getInputStream();
            java.io.OutputStream out = clientToLocal ? l.getOutputStream() : c.getOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            try { c.close(); } catch (Throwable ignored) {}
            try { l.close(); } catch (Throwable ignored) {}
        }
    }

    // ── инъекция касаний (точная семантика scrcpy Controller) ───────────────

    static long gestureDownTime = 0;

    static void injectTouch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN || gestureDownTime == 0) gestureDownTime = now;

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = action == MotionEvent.ACTION_UP ? 0f : 1f;
        coords[0].size = 1f;

        // как у scrcpy: BUTTON_PRIMARY на DOWN/MOVE, 0 на UP; downTime общий для жеста
        int buttonState = action == MotionEvent.ACTION_UP ? 0 : 1;
        MotionEvent ev = MotionEvent.obtain(gestureDownTime, now, action, 1, props, coords,
                0, buttonState, 1f, 1f, 0, 0, 0x00001002, 0);
        try {
            injectMethod.invoke(inputManager, ev, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Throwable e) {
            log("inject err " + e);
        } finally {
            ev.recycle();
        }
        if (action == MotionEvent.ACTION_UP) gestureDownTime = 0;
    }

    static void tap(float x, float y) {
        injectTouch(MotionEvent.ACTION_DOWN, x, y);
        injectTouch(MotionEvent.ACTION_UP, x, y);
    }

    static void swipe(float x1, float y1, float x2, float y2, long ms) throws InterruptedException {
        int steps = (int) Math.max(2, ms / 16);
        injectTouch(MotionEvent.ACTION_DOWN, x1, y1);
        for (int i = 1; i <= steps; i++) {
            injectTouch(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps);
            Thread.sleep(ms / steps);
        }
        injectTouch(MotionEvent.ACTION_UP, x2, y2);
    }

    // ── BankID: та же раскладка, что в scrcpy-direct.mjs ────────────────────

    static final double[][] KEYS = {
        /*1*/ {0.167, 0.629}, /*2*/ {0.501, 0.629}, /*3*/ {0.835, 0.629},
        /*4*/ {0.167, 0.719}, /*5*/ {0.501, 0.719}, /*6*/ {0.835, 0.719},
        /*7*/ {0.167, 0.809}, /*8*/ {0.501, 0.809}, /*9*/ {0.835, 0.809},
        /*0*/ {0.501, 0.898},
    };
    static final double[] IDENTIFIERA = {0.835, 0.898};

    static void keyTap(double[] k) {
        double jx = (Math.random() - 0.5) * 0.01, jy = (Math.random() - 0.5) * 0.01;
        float x = (float) (Math.min(0.99, Math.max(0.01, k[0] + jx)) * W);
        float y = (float) (Math.min(0.99, Math.max(0.01, k[1] + jy)) * H);
        tap(x, y);
    }

    static void randomSleep(long minMs, long maxMs) throws InterruptedException {
        Thread.sleep(minMs + (long) (Math.random() * (maxMs - minMs)));
    }

    static JSONObject bankIdLogin(String pinRaw) {
        String pin = pinRaw.replaceAll("\\D", "");
        if (pin.isEmpty()) return err("empty pin");
        try {
            keyTap(KEYS[9]);                 // кнопка подтверждения совпадает с позицией «0»
            randomSleep(1800, 2600);         // ждём PIN-pad
            for (int i = 0; i < pin.length(); i++) {
                keyTap(KEYS[pin.charAt(i) - '0']);
                if (i < pin.length() - 1) randomSleep(700, 2200);
            }
            randomSleep(800, 2000);
            keyTap(IDENTIFIERA);
            return ok();
        } catch (Throwable e) {
            return err(String.valueOf(e));
        }
    }

    // ── наблюдение за экраном ────────────────────────────────────────────────

    static List<String> currentTexts(int limit) {
        List<String> out = new ArrayList<>();
        try {
            exec("uiautomator dump /sdcard/_lan_ui.xml", 20000);
            String xml = readFile("/sdcard/_lan_ui.xml", 512 * 1024);
            if (xml == null) return out;
            Matcher m = Pattern.compile("text=\"([^\"]*)\"").matcher(xml);
            while (m.find() && out.size() < limit) {
                String s = m.group(1);
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable e) {
            log("texts err " + e);
        }
        return out;
    }

    static List<String> waitText(String needle, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<String> last;
        do {
            last = currentTexts(64);
            for (String s : last) if (s.contains(needle)) return last;
            Thread.sleep(700);
        } while (System.currentTimeMillis() < deadline);
        return new ArrayList<>();
    }

    static String screenshotB64() {
        try {
            exec("screencap -p /sdcard/_lan_shot.png", 20000);
            File f = new File("/sdcard/_lan_shot.png");
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            if (off == 0) return null;
            return Base64.encodeToString(buf, 0, off, Base64.NO_WRAP);
        } catch (Throwable e) {
            log("shot err " + e);
            return null;
        }
    }

    // ── утилиты ──────────────────────────────────────────────────────────────

    static String exec(String cmd, long timeoutMs) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            Thread reader = new Thread(() -> {
                try { String l; while ((l = r.readLine()) != null) out.append(l).append('\n'); }
                catch (Throwable ignored) {}
            });
            reader.setDaemon(true);
            reader.start();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try { p.exitValue(); break; } catch (IllegalThreadStateException ignored) { Thread.sleep(50); }
            }
            try { p.destroy(); } catch (Throwable ignored) {}
            reader.join(300);
        } catch (Throwable e) {
            out.append("ERR ").append(e);
        }
        return out.toString().trim();
    }

    static String readFile(String path, int max) {
        try {
            File f = new File(path);
            byte[] buf = new byte[(int) Math.min(max, f.length())];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    static String cut(String s, int max) { return s == null ? "" : s.substring(0, Math.min(max, s.length())); }

    static JSONObject ok() {
        try { return new JSONObject().put("ok", true).put("err", ""); }
        catch (Throwable e) { return new JSONObject(); }
    }
    static JSONObject err(String m) {
        try { return new JSONObject().put("ok", false).put("err", m); }
        catch (Throwable e) { return new JSONObject(); }
    }

    static void log(String s) {
        System.out.println("[LanAgent] " + s);
    }
}
