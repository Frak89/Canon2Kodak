package com.ampy.canonprint;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only CCAPI client. Does not alter camera settings or card contents. */
public final class CanonClient {
    public interface Progress { void onBytes(long bytes, long total); }
    private final String base;
    private final String user;
    private final String password;
    private final URI origin;
    private static final int MAX_JSON = 3 * 1024 * 1024;
    private static final Pattern MEDIA = Pattern.compile("(?i)\\.(jpe?g|cr3|hif|heif)$");

    public CanonClient(String address, String user, String password) throws Exception {
        String input = address.trim();
        if (!input.startsWith("http://")) input = "http://" + input;
        URI uri = new URI(input);
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null ||
                (uri.getPath() != null && !uri.getPath().equals("/") && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("Indica solo http://IP:porta, senza percorsi o credenziali.");
        }
        String host = uri.getHost();
        if (!isPrivateIPv4(host)) {
            throw new IllegalArgumentException("Per sicurezza è richiesto un indirizzo IPv4 privato della Canon.");
        }
        int port = uri.getPort() == -1 ? 8080 : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Porta non valida");
        this.origin = new URI("http", null, host, port, null, null, null);
        this.base = "http://" + host + ":" + port;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    static boolean isPrivateIPv4(String ip) {
        if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] chunks = ip.split("\\.");
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            try { n[i] = Integer.parseInt(chunks[i]); } catch (Exception e) { return false; }
            if (n[i] < 0 || n[i] > 255) return false;
        }
        return n[0] == 10 || (n[0] == 172 && n[1] >= 16 && n[1] <= 31) ||
                (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254) ||
                (n[0] == 127);
    }

    public String checkConnection() throws Exception {
        try {
            JSONObject json = getJson("/ccapi/ver100/deviceinformation");
            return json.optString("productname", json.optString("name", "Fotocamera CCAPI connessa"));
        } catch (IOException e) {
            // Some models expose only /ccapi as the top-level discovery endpoint.
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                getJson("/ccapi");
                return "Connessione CCAPI attiva";
            }
            throw e;
        }
    }

    public List<String> listMedia() throws Exception {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node("/ccapi/ver100/contents", 0));
        Set<String> seen = new HashSet<>();
        LinkedHashSet<String> images = new LinkedHashSet<>();
        int queries = 0;
        while (!queue.isEmpty() && queries < 160 && images.size() < 2000) {
            Node node = queue.removeFirst();
            String path = normalizePath(node.path);
            if (path == null || !seen.add(path) || node.depth > 6) continue;
            JSONObject json;
            try {
                queries++;
                json = getJson(path);
            } catch (IOException ex) {
                if (node.depth == 0) {
                    // A camera may expose the contents API at a later advertised version.
                    for (int version : new int[]{110, 120, 130, 140})
                        queue.add(new Node("/ccapi/ver" + version + "/contents", 0));
                }
                continue;
            }
            LinkedHashSet<String> links = new LinkedHashSet<>();
            collectLinks(json, links);
            for (String link : links) {
                String candidate = normalizePath(link);
                if (candidate == null || !candidate.contains("/contents/")) continue;
                if (MEDIA.matcher(candidate).find()) images.add(candidate);
                else if (node.depth < 6 && !seen.contains(candidate)) queue.add(new Node(candidate, node.depth + 1));
            }
        }
        ArrayList<String> result = new ArrayList<>(images);
        Collections.sort(result, Collections.reverseOrder());
        return result;
    }

    private static class Node {
        final String path; final int depth;
        Node(String path, int depth) { this.path = path; this.depth = depth; }
    }

    private void collectLinks(Object object, Set<String> result) throws JSONException {
        if (object instanceof JSONObject) {
            JSONObject obj = (JSONObject) object;
            JSONArray names = obj.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) collectLinks(obj.get(names.getString(i)), result);
        } else if (object instanceof JSONArray) {
            JSONArray array = (JSONArray) object;
            for (int i = 0; i < array.length(); i++) collectLinks(array.get(i), result);
        } else if (object instanceof String) {
            String s = (String) object;
            if (s.startsWith("/ccapi/") || s.startsWith(base + "/ccapi/")) result.add(s);
        }
    }

    private String normalizePath(String incoming) {
        try {
            URI u = new URI(incoming);
            String path;
            if (u.isAbsolute()) {
                if (!u.getScheme().equalsIgnoreCase(origin.getScheme()) ||
                    !u.getHost().equalsIgnoreCase(origin.getHost()) ||
                    (u.getPort() == -1 ? 80 : u.getPort()) != origin.getPort()) return null;
                path = u.getRawPath();
            } else {
                path = u.getRawPath();
            }
            if (path == null || !path.startsWith("/ccapi/") || path.contains("..") ||
                path.contains("%2e") || path.contains("%2E")) return null;
            return path;
        } catch (Exception ignored) { return null; }
    }

    public JSONObject getJson(String path) throws Exception {
        HttpURLConnection conn = connect(path);
        try {
            byte[] bytes = readLimited(conn.getInputStream(), MAX_JSON);
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } finally { conn.disconnect(); }
    }

    public byte[] preview(String path) throws Exception {
        // Canon returns an embedded JPEG for CR3 files on supported models.
        Exception first = null;
        for (String kind : new String[]{"display", "thumbnail"}) {
            try {
                HttpURLConnection conn = connect(path + "?kind=" + kind);
                try { return readLimited(conn.getInputStream(), 12 * 1024 * 1024); }
                finally { conn.disconnect(); }
            } catch (Exception e) { if (first == null) first = e; }
        }
        throw new IOException("Anteprima non disponibile: " + (first == null ? "" : first.getMessage()));
    }

    public void download(String path, OutputStream dest, Progress progress) throws Exception {
        if (normalizePath(path) == null || !MEDIA.matcher(path).find())
            throw new IOException("Percorso fotografico non valido");
        HttpURLConnection conn = connect(path);
        try (InputStream input = conn.getInputStream()) {
            long total = conn.getContentLengthLong();
            long done = 0;
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                dest.write(buffer, 0, count);
                done += count;
                if (progress != null) progress.onBytes(done, total);
            }
            dest.flush();
            if (total >= 0 && done != total) throw new IOException("Trasferimento incompleto: " + done + "/" + total);
        } finally { conn.disconnect(); }
    }

    private HttpURLConnection connect(String pathAndQuery) throws Exception {
        int queryIndex = pathAndQuery.indexOf('?');
        String rawPath = queryIndex < 0 ? pathAndQuery : pathAndQuery.substring(0, queryIndex);
        if (normalizePath(rawPath) == null) throw new IOException("URL CCAPI non sicuro");
        String query = queryIndex < 0 ? "" : pathAndQuery.substring(queryIndex + 1);
        if (!query.isEmpty() && !query.matches("kind=(display|thumbnail)"))
            throw new IOException("Parametro CCAPI non valido");
        URL url = new URL(base + pathAndQuery);
        HttpURLConnection conn = open(url, null);
        if (conn.getResponseCode() == 401 && !user.isEmpty()) {
            String challenge = conn.getHeaderField("WWW-Authenticate");
            conn.disconnect();
            String authorization = challenge != null && challenge.toLowerCase(Locale.ROOT).startsWith("digest ")
                    ? digestAuth(challenge, pathAndQuery) : basicAuth();
            conn = open(url, authorization);
        }
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            conn.disconnect();
            if (status == 401) throw new IOException("HTTP 401: verifica utente/password CCAPI");
            throw new IOException("HTTP " + status + " su " + rawPath);
        }
        return conn;
    }

    private HttpURLConnection open(URL url, String authorization) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(6500);
        c.setReadTimeout(23000);
        c.setRequestProperty("Accept", "application/json, image/jpeg, application/octet-stream, */*");
        if (authorization != null) c.setRequestProperty("Authorization", authorization);
        return c;
    }
    private String basicAuth() {
        String value = user + ":" + password;
        return "Basic " + Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
    private String digestAuth(String challenge, String path) throws Exception {
        String realm = extract(challenge, "realm");
        String nonce = extract(challenge, "nonce");
        String opaque = extract(challenge, "opaque");
        String algorithm = extract(challenge, "algorithm");
        String qop = extract(challenge, "qop");
        if (realm == null || nonce == null) return basicAuth();
        if (algorithm != null && !algorithm.equalsIgnoreCase("MD5"))
            throw new IOException("Digest " + algorithm + " non supportato");
        String cnonce = Long.toHexString(new SecureRandom().nextLong());
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5("GET:" + path);
        boolean auth = qop != null && qop.contains("auth");
        String response = auth ? md5(ha1 + ":" + nonce + ":00000001:" + cnonce + ":auth:" + ha2)
                : md5(ha1 + ":" + nonce + ":" + ha2);
        StringBuilder header = new StringBuilder("Digest username=\"").append(user.replace("\"", ""))
                .append("\", realm=\"").append(realm).append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(path).append("\", response=\"").append(response).append('"');
        if (opaque != null) header.append(", opaque=\"").append(opaque).append('"');
        if (auth) header.append(", qop=auth, nc=00000001, cnonce=\"").append(cnonce).append('"');
        return header.toString();
    }
    private static String extract(String challenge, String key) {
        Matcher m = Pattern.compile("(?i)(?:^|[,\\s])" + Pattern.quote(key) + "\\s*=\\s*(?:\"([^\"]*)\"|([^, ]+))").matcher(challenge);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }
    private static String md5(String s) throws Exception {
        byte[] data = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte v : data) b.append(String.format(Locale.ROOT, "%02x", v & 0xFF));
        return b.toString();
    }
    private static byte[] readLimited(InputStream stream, int limit) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (bytes.size() + read > limit) throw new IOException("Risposta troppo grande");
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        }
    }
}
