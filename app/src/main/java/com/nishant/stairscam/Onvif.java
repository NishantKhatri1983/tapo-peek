package com.nishant.stairscam;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/** Minimal ONVIF/SOAP client: just enough for a PullPoint event subscription (no libraries). */
final class Onvif {
    private Onvif() {}

    private static final String NS = "xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
            + "xmlns:tev=\"http://www.onvif.org/ver10/events/wsdl\"";

    static String eventsUrl(Context c) {
        return "http://" + Prefs.ip(c) + ":2020/onvif/service";
    }

    /** POSTs a SOAP body (WS-Security UsernameToken digest added) and returns the response XML. */
    static String post(String url, Context c, String body, int readTimeoutMs) throws Exception {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope " + NS + ">"
                + header(Prefs.user(c), Prefs.pass(c)) + body + "</s:Envelope>";
        URL u = new URL(url);
        byte[] payload = envelope.getBytes(StandardCharsets.UTF_8);
        try (Socket s = new Socket()) {
            int port = u.getPort() > 0 ? u.getPort() : 80;
            s.connect(new InetSocketAddress(u.getHost(), port), 4000);
            s.setSoTimeout(readTimeoutMs);
            OutputStream out = s.getOutputStream();
            String head = "POST " + u.getPath() + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port
                    + "\r\nContent-Type: application/soap+xml; charset=utf-8\r\nContent-Length: "
                    + payload.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String all = bos.toString("UTF-8");
            int i = all.indexOf("\r\n\r\n");
            return i >= 0 ? all.substring(i + 4) : all;
        }
    }

    private static String header(String user, String pass) throws Exception {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String created = f.format(new Date());
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(nonce);
        sha.update(created.getBytes(StandardCharsets.UTF_8));
        sha.update(pass.getBytes(StandardCharsets.UTF_8));
        String digest = Base64.encodeToString(sha.digest(), Base64.NO_WRAP);
        return "<s:Header><Security xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" "
                + "s:mustUnderstand=\"1\"><UsernameToken><Username>" + user + "</Username>"
                + "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">"
                + digest + "</Password><Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + Base64.encodeToString(nonce, Base64.NO_WRAP) + "</Nonce>"
                + "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">"
                + created + "</Created></UsernameToken></Security></s:Header>";
    }
}
