package br.local.ciasender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Client side of FBI's "Remote Install > Receive URLs over the network" (3DS).
 *
 * Wire format: a 4-byte big-endian length, followed by that many bytes of UTF-8
 * text containing the URLs separated by '\n'. The 3DS then downloads each URL
 * itself over HTTP.
 */
public final class Protocol {
    public static final int FBI_PORT = 5000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Protocol() {}

    /** Builds http://ip:port/percent-encoded-name for a file name. */
    public static String urlFor(String ip, int port, String fileName) {
        String enc;
        try {
            enc = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return "http://" + ip + ":" + port + "/" + enc;
    }

    /** Length prefix (4 bytes, big-endian) followed by the URLs, one per line. */
    public static byte[] buildPayload(List<String> urls) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String u : urls) sb.append(u).append('\n');
        byte[] text = sb.toString().getBytes(UTF8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(text.length + 4);
        bos.write((text.length >>> 24) & 0xFF);
        bos.write((text.length >>> 16) & 0xFF);
        bos.write((text.length >>> 8) & 0xFF);
        bos.write(text.length & 0xFF);
        bos.write(text);
        return bos.toByteArray();
    }

    /** Connects to FBI and sends the list. Waits briefly for the 1-byte ack, if any. */
    public static void send(String host, int port, List<String> urls) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            OutputStream out = s.getOutputStream();
            out.write(buildPayload(urls));
            out.flush();
            s.setSoTimeout(3000);
            try {
                s.getInputStream().read();
            } catch (IOException ignored) {
                // No ack is fine.
            }
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /** True for a dotted-quad IPv4 address such as 192.168.0.50 (each part 0-255). */
    public static boolean isValidIpv4(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.length() == 0 || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }
}
