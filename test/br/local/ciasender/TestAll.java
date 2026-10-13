package br.local.ciasender;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java tests for everything that does not need Android: the FBI protocol, the HTTP
 * file server, file sources, IP validation and the translated texts.
 *
 * Run (from the project root):
 *   javac -d /tmp/t src/br/local/ciasender/{Protocol,FileServer,FileSource,Msg}.java \
 *         test/br/local/ciasender/TestAll.java && java -cp /tmp/t br.local.ciasender.TestAll
 */
public class TestAll {
    private static int fails = 0;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "OK    " : "FAIL  ") + name);
        if (!ok) fails++;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** Sends a raw HTTP request and returns the whole response as text. */
    private static String raw(int port, String request) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.getOutputStream().write(request.getBytes("ISO-8859-1"));
        s.getOutputStream().flush();
        String r = new String(readAll(s.getInputStream()), "ISO-8859-1");
        s.close();
        return r;
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    public static void main(String[] args) throws Exception {
        testProtocol();
        testIpValidation();
        testMessages();
        testFileSource();
        testServer();
        System.out.println(fails == 0 ? "\nALL OK" : "\n" + fails + " FAILURE(S)");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ protocol

    private static void testProtocol() throws Exception {
        final ServerSocket fake3ds = new ServerSocket(0);
        final byte[][] got = new byte[1][];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Socket c = fake3ds.accept();
                    DataInputStream in = new DataInputStream(c.getInputStream());
                    int len = in.readInt();   // big-endian, like FBI's ntohl
                    byte[] body = new byte[len];
                    in.readFully(body);
                    got[0] = body;
                    c.getOutputStream().write(0);   // ack
                    c.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();
        String u1 = Protocol.urlFor("192.168.0.60", 8080, "Test Game (BR) [x].cia");
        String u2 = Protocol.urlFor("192.168.0.60", 8080, "a\u00e7a\u00ed.cia");
        Protocol.send("127.0.0.1", fake3ds.getLocalPort(), Arrays.asList(u1, u2));
        t.join(3000);
        check("payload arrives intact (big-endian length + URLs separated by \\n)",
                got[0] != null && new String(got[0], "UTF-8").equals(u1 + "\n" + u2 + "\n"));
        check("spaces and brackets are percent-encoded",
                u1.endsWith("/Test%20Game%20%28BR%29%20%5Bx%5D.cia"));
        byte[] pl = Protocol.buildPayload(Arrays.asList("http://a/b"));
        check("4-byte length header", pl.length == 4 + 11 && pl[0] == 0 && pl[1] == 0 && pl[2] == 0 && pl[3] == 11);

        final ServerSocket noAck = new ServerSocket(0);
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                try {
                    Socket c = noAck.accept();
                    readAll(c.getInputStream());
                    c.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        });
        t2.start();
        boolean noErr = true;
        try {
            Protocol.send("127.0.0.1", noAck.getLocalPort(), Arrays.asList("http://x/y"));
        } catch (IOException e) {
            noErr = false;
        }
        check("works when the 3DS sends no ack", noErr);

        boolean refused = false;
        try {
            Protocol.send("127.0.0.1", 1, Arrays.asList("http://x/y"));
        } catch (IOException e) {
            refused = true;
        }
        check("clear error when nothing listens on the 3DS port", refused);
    }

    // ------------------------------------------------------------------ IP validation

    private static void testIpValidation() {
        check("valid: 192.168.0.50", Protocol.isValidIpv4("192.168.0.50"));
        check("valid: 0.0.0.0 and 255.255.255.255",
                Protocol.isValidIpv4("0.0.0.0") && Protocol.isValidIpv4("255.255.255.255"));
        check("invalid: too few parts", !Protocol.isValidIpv4("192.168.0"));
        check("invalid: too many parts", !Protocol.isValidIpv4("192.168.0.1.5"));
        check("invalid: part above 255", !Protocol.isValidIpv4("192.168.0.256"));
        check("invalid: empty part", !Protocol.isValidIpv4("192..0.1"));
        check("invalid: trailing dot", !Protocol.isValidIpv4("192.168.0.1."));
        check("invalid: letters", !Protocol.isValidIpv4("a.b.c.d"));
        check("invalid: empty and null", !Protocol.isValidIpv4("") && !Protocol.isValidIpv4(null));
        check("invalid: 4-digit part", !Protocol.isValidIpv4("1.2.3.0001"));
    }

    // ------------------------------------------------------------------ texts

    private static void testMessages() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("en", "US"));
            check("English by default", Msg.SEND.get().equals("Send to 3DS"));
            Locale.setDefault(new Locale("pt", "BR"));
            check("Portuguese (Brazil)", Msg.SEND.get().equals("Enviar para o 3DS"));
            Locale.setDefault(new Locale("pt", "PT"));
            check("Portuguese (Portugal)", Msg.SEND.get().equals("Enviar para o 3DS"));
            Locale.setDefault(new Locale("fr", "FR"));
            check("other languages fall back to English", Msg.SEND.get().equals("Send to 3DS"));
            Locale.setDefault(new Locale("pt", "BR"));
            check("\"this device\" wording (pt)", Msg.DEVICE_IP.get("1.2.3.4:8080").equals("IP deste dispositivo: 1.2.3.4:8080"));
            Locale.setDefault(new Locale("en", "US"));
            check("\"this device\" wording (en)", Msg.DEVICE_IP.get("1.2.3.4:8080").startsWith("This device"));
            check("3DS IP label has no \"(shown in FBI)\" hint",
                    Msg.LABEL_3DS_IP.get().equals("3DS IP:")
                            && !Msg.LABEL_3DS_IP.raw(true).toLowerCase(Locale.ROOT).contains("fbi"));
        } finally {
            Locale.setDefault(saved);
        }

        // Both languages must use the same placeholders in the same order.
        Pattern ph = Pattern.compile("%[sd]");
        boolean same = true;
        boolean formats = true;
        for (Msg m : Msg.values()) {
            List<String> en = new ArrayList<String>();
            List<String> pt = new ArrayList<String>();
            Matcher a = ph.matcher(m.raw(false));
            while (a.find()) en.add(a.group());
            Matcher b = ph.matcher(m.raw(true));
            while (b.find()) pt.add(b.group());
            if (!en.equals(pt)) {
                same = false;
                System.out.println("      placeholder mismatch in " + m);
            }
            Object[] sample = new Object[en.size()];
            for (int i = 0; i < sample.length; i++) sample[i] = en.get(i).equals("%d") ? (Object) 7 : "x";
            try {
                m.get(sample);
            } catch (RuntimeException e) {
                formats = false;
                System.out.println("      cannot format " + m + ": " + e);
            }
        }
        check("every text has matching placeholders in English and Portuguese", same);
        check("every text formats without errors", formats);
    }

    // ------------------------------------------------------------------ file source

    private static void testFileSource() throws Exception {
        File f = File.createTempFile("src", ".cia");
        byte[] data = new byte[100000];
        new Random(1).nextBytes(data);
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
        FileSource src = new FileSource(f);
        InputStream in = src.open(12345);
        byte[] got = readAll(in);
        in.close();
        check("FileSource.open(offset) starts at the offset",
                Arrays.equals(got, Arrays.copyOfRange(data, 12345, data.length)));
        check("FileSource reports name and length", src.getName().equals(f.getName()) && src.length() == 100000);
        f.delete();
    }

    // ------------------------------------------------------------------ HTTP server

    /** A source whose behaviour is configurable, to test error handling. */
    private static final class FakeSource implements FileServer.Source {
        private final long length;
        private final boolean failOpen;

        FakeSource(long length, boolean failOpen) {
            this.length = length;
            this.failOpen = failOpen;
        }

        public String getName() { return "fake.cia"; }
        public long length() { return length; }
        public InputStream open(long offset) throws IOException {
            if (failOpen) throw new IOException("boom");
            return new java.io.ByteArrayInputStream(new byte[(int) length]);
        }
    }

    private static void testServer() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ciatest" + System.nanoTime());
        dir.mkdirs();
        final byte[] data = new byte[1500000];
        new Random(42).nextBytes(data);
        final File f = new File(dir, "Test Game (BR) [x].cia");
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
        File unregistered = new File(dir, "not-registered.cia");
        o = new FileOutputStream(unregistered);
        o.write("private".getBytes("UTF-8"));
        o.close();
        File secret = new File(dir.getParentFile(), "secret-" + dir.getName() + ".txt");
        o = new FileOutputStream(secret);
        o.write("secret".getBytes("UTF-8"));
        o.close();

        final Map<String, FileServer.Source> served = new ConcurrentHashMap<String, FileServer.Source>();
        served.put(f.getName(), new FileSource(f));
        served.put("fake.cia", new FakeSource(10, false));
        served.put("badopen.cia", new FakeSource(10, true));
        served.put("nosize.cia", new FakeSource(-1, false));
        // A bigger file to test dropped connections.
        final File big = new File(dir, "big.cia");
        o = new FileOutputStream(big);
        byte[] chunk = new byte[1 << 20];
        for (int i = 0; i < 48; i++) o.write(chunk);
        o.close();
        served.put(big.getName(), new FileSource(big));

        final List<String> served_ = Collections.synchronizedList(new ArrayList<String>());
        final List<String> interrupted = Collections.synchronizedList(new ArrayList<String>());
        final int port = 18080;
        FileServer srv = new FileServer(new FileServer.Resolver() {
            public FileServer.Source find(String name) { return served.get(name); }
        }, port, new FileServer.Listener() {
            public void onServed(String name) { served_.add(name); }
            public void onInterrupted(String name) { interrupted.add(name); }
        });
        srv.start();

        URL url = new URL(Protocol.urlFor("127.0.0.1", port, f.getName()));
        HttpURLConnection h = (HttpURLConnection) url.openConnection();
        byte[] body = readAll(h.getInputStream());
        check("full GET (1.5 MB) is identical", h.getResponseCode() == 200 && Arrays.equals(body, data));

        h = (HttpURLConnection) url.openConnection();
        h.setRequestProperty("Range", "bytes=1000-1999");
        body = readAll(h.getInputStream());
        check("Range 1000-1999 -> 206 with the right bytes", h.getResponseCode() == 206
                && Arrays.equals(body, Arrays.copyOfRange(data, 1000, 2000))
                && h.getHeaderField("Content-Range").equals("bytes 1000-1999/1500000"));

        h = (HttpURLConnection) url.openConnection();
        h.setRequestProperty("Range", "bytes=1499000-");
        body = readAll(h.getInputStream());
        check("open-ended Range", Arrays.equals(body, Arrays.copyOfRange(data, 1499000, 1500000)));

        h = (HttpURLConnection) url.openConnection();
        h.setRequestProperty("Range", "bytes=-500");
        body = readAll(h.getInputStream());
        check("suffix Range (-500)", Arrays.equals(body, Arrays.copyOfRange(data, 1499500, 1500000)));

        h = (HttpURLConnection) url.openConnection();
        h.setRequestMethod("HEAD");
        check("HEAD returns Content-Length", h.getResponseCode() == 200 && h.getContentLength() == 1500000);

        String enc = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
        check("unknown name -> 404",
                raw(port, "GET /nope.cia HTTP/1.1\r\nHost: a\r\n\r\n").startsWith("HTTP/1.1 404"));
        check("file in the folder but not registered -> 404",
                raw(port, "GET /not-registered.cia HTTP/1.1\r\nHost: a\r\n\r\n").startsWith("HTTP/1.1 404"));
        check("path traversal (..%2F) -> 404",
                raw(port, "GET /..%2F" + secret.getName() + " HTTP/1.1\r\nHost: a\r\n\r\n").startsWith("HTTP/1.1 404"));
        check("path traversal (../) -> 404",
                raw(port, "GET /../" + secret.getName() + " HTTP/1.1\r\nHost: a\r\n\r\n").startsWith("HTTP/1.1 404"));
        check("POST -> 405", raw(port, "POST /a.cia HTTP/1.1\r\nHost: a\r\n\r\n").startsWith("HTTP/1.1 405"));
        check("Range outside the file -> 416",
                raw(port, "GET /" + enc + " HTTP/1.1\r\nRange: bytes=9999999-\r\n\r\n").startsWith("HTTP/1.1 416"));
        check("source that cannot be opened -> 500",
                raw(port, "GET /badopen.cia HTTP/1.1\r\nHost: a\r\n\r\n").startsWith("HTTP/1.1 500"));
        check("source with unknown size -> 500",
                raw(port, "GET /nosize.cia HTTP/1.1\r\nHost: a\r\n\r\n").startsWith("HTTP/1.1 500"));
        check("custom Source is served", raw(port, "GET /fake.cia HTTP/1.1\r\nHost: a\r\n\r\n").contains("Content-Length: 10"));

        // Several simultaneous downloads (FBI may open more than one connection).
        final boolean[] allOk = { true };
        List<Thread> ts = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            Thread w = new Thread(new Runnable() {
                public void run() {
                    try {
                        HttpURLConnection c = (HttpURLConnection)
                                new URL(Protocol.urlFor("127.0.0.1", port, f.getName())).openConnection();
                        if (!Arrays.equals(readAll(c.getInputStream()), data)) allOk[0] = false;
                    } catch (IOException e) {
                        allOk[0] = false;
                    }
                }
            });
            ts.add(w);
            w.start();
        }
        for (Thread w : ts) w.join();
        check("4 simultaneous downloads are intact", allOk[0]);

        Thread.sleep(300);
        check("listener reports a finished download", served_.contains(f.getName()));

        // The client drops the connection after a few KB of a big file.
        Socket s = new Socket("127.0.0.1", port);
        s.getOutputStream().write("GET /big.cia HTTP/1.1\r\nHost: a\r\n\r\n".getBytes("ISO-8859-1"));
        s.getOutputStream().flush();
        byte[] first = new byte[4096];
        int read = s.getInputStream().read(first);
        s.setSoLinger(true, 0);   // reset instead of a clean close
        s.close();
        boolean seen = false;
        for (int i = 0; i < 50 && !seen; i++) {
            Thread.sleep(100);
            seen = interrupted.contains("big.cia");
        }
        check("listener reports an interrupted download", read > 0 && seen);
        check("an interrupted download is not reported as finished", !served_.contains("big.cia"));

        srv.stop();
        f.delete();
        big.delete();
        unregistered.delete();
        secret.delete();
        dir.delete();
    }
}
