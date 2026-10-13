package br.local.ciasender;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/**
 * Minimal HTTP/1.1 server (GET and HEAD, with Range support) that serves only the
 * items a {@link Resolver} knows about. It never touches the file system by itself,
 * so there is no way to request anything the app did not explicitly register.
 */
public final class FileServer {

    /** Something that can be downloaded: a plain file, a content:// URI, etc. */
    public interface Source {
        String getName();

        /** Size in bytes, or a negative number if unknown (the request then fails with 500). */
        long length();

        /** Opens a stream positioned at {@code offset}. The caller closes it. */
        InputStream open(long offset) throws IOException;
    }

    /** Maps a requested (decoded) file name to a source, or null if not served. */
    public interface Resolver {
        Source find(String name);
    }

    /** Download notifications. Called from server threads. */
    public interface Listener {
        /** The whole file was sent. */
        void onServed(String name);

        /** The connection dropped before the response was complete. */
        void onInterrupted(String name);
    }

    private final int port;
    private final Resolver resolver;
    private final Listener listener;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public FileServer(Resolver resolver, int port, Listener listener) {
        this.resolver = resolver;
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        final ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        serverSocket = ss;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop(ss);
            }
        }, "fileserver-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                final Socket c = ss.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        handle(c);
                    }
                }, "fileserver-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    /** Decodes "/some%20name.cia" into "some name.cia"; null if the path is not acceptable. */
    private static String decodeName(String target) {
        int q = target.indexOf('?');
        if (q >= 0) target = target.substring(0, q);
        if (!target.startsWith("/")) return null;
        String name;
        try {
            name = URLDecoder.decode(target.substring(1).replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return null;
        }
        if (name.length() == 0 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.equals(".") || name.equals("..")) {
            return null;
        }
        return name;
    }

    private static void simple(OutputStream out, int code, String text, String extraHeaders)
            throws IOException {
        String h = "HTTP/1.1 " + code + " " + text + "\r\n"
                + (extraHeaders == null ? "" : extraHeaders)
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("ISO-8859-1"));
        out.flush();
    }

    private void handle(Socket c) {
        String shownName = null;
        boolean complete = false;
        try {
            c.setSoTimeout(30000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "ISO-8859-1"));
            OutputStream out = new BufferedOutputStream(c.getOutputStream(), 65536);

            String reqLine = r.readLine();
            if (reqLine == null) return;
            String rangeHeader = null;
            String h;
            int n = 0;
            while ((h = r.readLine()) != null && h.length() > 0 && n++ < 100) {
                if (h.regionMatches(true, 0, "range:", 0, 6)) rangeHeader = h.substring(6).trim();
            }

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) { simple(out, 400, "Bad Request", null); return; }
            String method = parts[0];
            if (!method.equals("GET") && !method.equals("HEAD")) {
                simple(out, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n");
                return;
            }
            String name = decodeName(parts[1]);
            Source src = (name == null) ? null : resolver.find(name);
            if (src == null) { simple(out, 404, "Not Found", null); return; }
            shownName = src.getName();

            long len = src.length();
            if (len < 0) { simple(out, 500, "Internal Server Error", null); return; }

            // Parse "Range: bytes=a-b", "bytes=a-" or "bytes=-n" (a single range only).
            long start = 0, end = len - 1;
            boolean partial = false;
            if (len > 0 && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                try {
                    String spec = rangeHeader.substring(6);
                    int comma = spec.indexOf(',');
                    if (comma >= 0) spec = spec.substring(0, comma);
                    int dash = spec.indexOf('-');
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (a.length() == 0) {
                        long suffix = Long.parseLong(b);
                        start = Math.max(0, len - suffix);
                        end = len - 1;
                    } else {
                        start = Long.parseLong(a);
                        end = (b.length() == 0) ? len - 1 : Math.min(Long.parseLong(b), len - 1);
                    }
                    if (start > end || start >= len) {
                        simple(out, 416, "Range Not Satisfiable", "Content-Range: bytes */" + len + "\r\n");
                        return;
                    }
                    partial = true;
                } catch (RuntimeException ignored) {
                    // Malformed Range header: ignore it and send the whole file.
                    start = 0;
                    end = len - 1;
                    partial = false;
                }
            }

            long count = (len == 0) ? 0 : (end - start + 1);
            boolean sendBody = method.equals("GET") && count > 0;

            // Open the stream before sending headers so a failure can still become a 500.
            InputStream in = null;
            if (sendBody) {
                try {
                    in = src.open(start);
                } catch (IOException e) {
                    simple(out, 500, "Internal Server Error", null);
                    return;
                }
            }
            try {
                StringBuilder head = new StringBuilder();
                head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                head.append("Content-Type: application/octet-stream\r\n");
                head.append("Accept-Ranges: bytes\r\n");
                if (partial) {
                    head.append("Content-Range: bytes ").append(start).append('-').append(end)
                            .append('/').append(len).append("\r\n");
                }
                head.append("Content-Length: ").append(count).append("\r\n");
                head.append("Connection: close\r\n\r\n");
                out.write(head.toString().getBytes("ISO-8859-1"));

                if (in != null) {
                    byte[] buf = new byte[65536];
                    long remaining = count;
                    while (remaining > 0) {
                        int rd = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (rd < 0) break;
                        out.write(buf, 0, rd);
                        remaining -= rd;
                    }
                    complete = (remaining == 0);
                }
                out.flush();
            } finally {
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) {}
                }
            }
            if (sendBody && complete && end == len - 1 && listener != null) {
                listener.onServed(shownName);
            }
        } catch (IOException e) {
            if (shownName != null && !complete && listener != null) listener.onInterrupted(shownName);
        } finally {
            try { c.close(); } catch (IOException ignored) {}
        }
    }
}
