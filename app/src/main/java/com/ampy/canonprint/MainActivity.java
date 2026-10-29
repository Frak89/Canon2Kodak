package com.ampy.canonprint;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AmpyCanon Print rev2. All camera operations are read-only. */
public class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 501;
    private static final String RAW_EDITOR_PACKAGE = "raw_editor_component";
    private static final String RAW_EDITOR_ACTION = "raw_editor_action";
    private static final String RAW_EDITOR_MIME = "raw_editor_mime";
    private boolean openingRawEditor;
    private final ExecutorService tasks = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText address, user, password;
    private TextView status, selectedName, log;
    private ImageView preview;
    private ListView listView;
    private CanonClient client;
    private final List<String> allPaths = new ArrayList<>();
    private final List<String> visiblePaths = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private Uri localUri;
    private String selectedPath;
    private Uri savedUri;
    private String savedPath;
    private String filter = "TUTTI";
    private long lastProgress;

    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_main);
        View root = findViewById(android.R.id.content);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets ins = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(ins.left, ins.top, ins.right, ins.bottom);
                return insets;
            });
        }
        address = findViewById(R.id.address);
        user = findViewById(R.id.username);
        password = findViewById(R.id.password);
        status = findViewById(R.id.status);
        selectedName = findViewById(R.id.selectedName);
        log = findViewById(R.id.log);
        preview = findViewById(R.id.preview);
        listView = findViewById(R.id.files);
        address.setText(getPreferences(0).getString("camera_url", "http://192.168.1.2:8080"));

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.rgb(225, 237, 250));
                view.setTextSize(13f);
                view.setPadding(12, 15, 8, 15);
                return view;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            localUri = null;
            selectedPath = visiblePaths.get(position);
            savedUri = null;
            savedPath = null;
            selectedName.setText(selectedPath + (isRaw(selectedPath) ? "  ·  RAW originale" : ""));
            showCameraPreview(selectedPath);
        });
        findViewById(R.id.connect).setOnClickListener(v -> connect());
        findViewById(R.id.load).setOnClickListener(v -> loadFiles());
        findViewById(R.id.filterAll).setOnClickListener(v -> applyFilter("TUTTI"));
        findViewById(R.id.filterJpg).setOnClickListener(v -> applyFilter("JPEG"));
        findViewById(R.id.filterRaw).setOnClickListener(v -> applyFilter("RAW"));
        findViewById(R.id.importLocal).setOnClickListener(v -> openDocument());
        findViewById(R.id.download).setOnClickListener(v -> save());
        findViewById(R.id.share).setOnClickListener(v -> share());
        findViewById(R.id.rawToJpeg).setOnClickListener(v -> exportRawPreview());
        findViewById(R.id.openKodak).setOnClickListener(v -> openKodak());
        findViewById(R.id.openRawEditor).setOnClickListener(v -> openRawEditor(false));
        findViewById(R.id.chooseRawEditor).setOnClickListener(v -> openRawEditor(true));
        log("Avvio Rev2 · JPEG, RAW CR3 e editor RAW esterno");
    }

    private void bindToWifi() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                cm.bindProcessToNetwork(network);
                return;
            }
        }
    }

    private void connect() {
        String host = address.getText().toString();
        String username = user.getText().toString();
        String pass = password.getText().toString();
        status.setText("Connessione…");
        tasks.execute(() -> {
            try {
                CanonClient camera = new CanonClient(host, username, pass);
                bindToWifi();
                String identity = camera.checkConnection();
                client = camera;
                getPreferences(0).edit().putString("camera_url", host).apply();
                main.post(() -> {
                    status.setText("✓ Connessa · " + identity);
                    log("CCAPI operativa. Puoi caricare i file dalle SD.");
                });
            } catch (Exception error) { fail("Connessione", error); }
        });
    }

    private void loadFiles() {
        CanonClient camera = client;
        if (camera == null) { toast("Prima connettiti alla Canon."); return; }
        status.setText("Lettura delle schede SD…");
        tasks.execute(() -> {
            try {
                List<String> paths = camera.listMedia();
                main.post(() -> {
                    allPaths.clear();
                    allPaths.addAll(paths);
                    applyFilter(filter);
                    status.setText("✓ Trovati " + paths.size() + " file JPEG / CR3 / HEIF");
                    log("Letti " + paths.size() + " percorsi fotografici.");
                    if (paths.isEmpty()) log("Se la scheda contiene immagini, verifica CCAPI e le autorizzazioni.");
                });
            } catch (Exception error) { fail("Lettura SD", error); }
        });
    }

    private void applyFilter(String mode) {
        filter = mode;
        visiblePaths.clear();
        adapter.clear();
        for (String path : allPaths) {
            boolean raw = isRaw(path);
            boolean jpeg = isJpeg(path);
            if (mode.equals("TUTTI") || (mode.equals("RAW") && raw) || (mode.equals("JPEG") && jpeg)) {
                visiblePaths.add(path);
                String slot = path.contains("/card2/") ? "SD 2" : path.contains("/card1/") ? "SD 1" : "SD";
                String extension = raw ? "RAW" : jpeg ? "JPEG" : "HEIF";
                adapter.add("[" + extension + "] " + fileName(path) + " · " + slot);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void showCameraPreview(String path) {
        CanonClient camera = client;
        if (camera == null) return;
        preview.setImageDrawable(null);
        log("Caricamento anteprima " + fileName(path));
        tasks.execute(() -> {
            try {
                byte[] encoded = camera.preview(path);
                Bitmap image = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
                if (image == null) throw new IOException("Formato anteprima non JPEG");
                main.post(() -> {
                    if (path.equals(selectedPath)) preview.setImageBitmap(image);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (path.equals(selectedPath)) log("Anteprima non disponibile; originale scaricabile: " + e.getMessage());
                });
            }
        });
    }

    private void openDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/x-canon-cr3", "image/x-canon-cr2", "image/jpeg", "image/heif", "image/x-adobe-dng", "application/octet-stream", "*/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        String name = queryName(uri);
        if (!(isRaw(name) || isJpeg(name) || isHeif(name) || name.toLowerCase(Locale.ROOT).endsWith(".dng") || name.toLowerCase(Locale.ROOT).endsWith(".cr2"))) {
            toast("Seleziona un file RAW (.CR3/.CR2/.DNG), JPEG o HEIF.");
            return;
        }
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        localUri = uri;
        selectedPath = null;
        savedUri = null;
        savedPath = null;
        selectedName.setText("File locale: " + name + " · " + (isRaw(name) ? "RAW" : "immagine"));
        preview.setImageDrawable(null);
        if (!isRaw(name) && !name.toLowerCase(Locale.ROOT).endsWith(".dng")) {
            try { preview.setImageBitmap(getContentResolver().loadThumbnail(uri, new android.util.Size(1100, 900), null)); }
            catch (Exception ignored) { log("Anteprima locale non disponibile."); }
        } else log("RAW locale importato senza alterazioni. Conversione RAW→JPEG non inclusa.");
        toast("File importato: " + name);
    }

    private String queryName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "immagine.cr3" : last;
    }

    private void save() {
        if (localUri != null) { toast("Il file locale è già sul telefono: usa Condividi."); return; }
        if (selectedPath == null) { toast("Seleziona prima una fotografia."); return; }
        String path = selectedPath;
        tasks.execute(() -> {
            try {
                Uri saved = saveFromCanon(path);
                main.post(() -> {
                    savedUri = saved;
                    savedPath = path;
                    log("✓ Salvato: " + fileName(path) + " (originale, non convertito)");
                    toast("Salvato in " + (isJpeg(path) ? "Immagini" : "Download") + "/AmpyCanonPrint");
                });
            } catch (Exception e) { fail("Salvataggio", e); }
        });
    }

    private Uri saveFromCanon(String path) throws Exception {
        CanonClient camera = client;
        if (camera == null) throw new IOException("Canon non connessa");
        boolean jpeg = isJpeg(path);
        String mime = mimeFor(path);
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(path));
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (jpeg ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS) + "/AmpyCanonPrint");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = jpeg ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri output = getContentResolver().insert(collection, cv);
        if (output == null) throw new IOException("Impossibile creare il file");
        try {
            try (OutputStream out = getContentResolver().openOutputStream(output, "w")) {
                if (out == null) throw new IOException("Impossibile aprire il file destinazione");
                lastProgress = -1;
                camera.download(path, out, (done, total) -> {
                    long bucket = done / (2 * 1024 * 1024);
                    if (bucket != lastProgress) {
                        lastProgress = bucket;
                        main.post(() -> status.setText("Scaricati " + (done / (1024 * 1024)) + " MB" +
                                (total >= 0 ? " / " + (total / (1024 * 1024)) + " MB" : "")));
                    }
                });
            }
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(output, cv, null, null);
            main.post(() -> status.setText("✓ File originale salvato"));
            return output;
        } catch (Exception e) {
            getContentResolver().delete(output, null, null);
            throw e;
        }
    }

    private void exportRawPreview() {
        if (selectedPath == null || !isRaw(selectedPath) || localUri != null) {
            toast("Seleziona un file .CR3 dalla Canon per esportare la sua anteprima.");
            return;
        }
        final String path = selectedPath;
        final CanonClient camera = client;
        tasks.execute(() -> {
            Uri output = null;
            try {
                byte[] bytes = camera.preview(path);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) throw new IOException("L'anteprima Canon non è decodificabile.");
                String filename = fileName(path).replaceAll("(?i)\\.cr3$", "_KODAK.jpg");
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AmpyCanonPrint");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                output = getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                if (output == null) throw new IOException("Impossibile creare il JPEG");
                try (OutputStream out = getContentResolver().openOutputStream(output, "w")) {
                    if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                        throw new IOException("Esportazione JPEG non riuscita");
                }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(output, values, null, null);
                final Uri completed = output;
                main.post(() -> {
                    log("✓ Anteprima RAW salvata come JPEG " + bitmap.getWidth() + "×" + bitmap.getHeight() + " px.");
                    log("Non equivale allo sviluppo RAW; l'originale .CR3 resta intatto.");
                    shareUri(completed, filename);
                });
            } catch (Exception e) {
                if (output != null) getContentResolver().delete(output, null, null);
                fail("Anteprima RAW→JPEG", e);
            }
        });
    }

    private void share() {
        if (localUri != null) { shareUri(localUri, queryName(localUri)); return; }
        if (selectedPath == null) { toast("Seleziona prima un file."); return; }
        final String path = selectedPath;
        if (savedUri != null && path.equals(savedPath)) { shareUri(savedUri, fileName(path)); return; }
        log("Prima salvo il file originale, poi apro la condivisione Android…");
        tasks.execute(() -> {
            try {
                Uri uri = saveFromCanon(path);
                main.post(() -> {
                    savedUri = uri;
                    savedPath = path;
                    shareUri(uri, fileName(path));
                });
            } catch (Exception e) { fail("Condivisione", e); }
        });
    }

    private void shareUri(Uri uri, String name) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mimeFor(name));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newUri(getContentResolver(), "Foto Canon", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(send, "Invia a Kodak o altra applicazione")); }
        catch (Exception e) { toast("Nessuna applicazione compatibile disponibile."); }
    }

    /** Open the untouched CR3/CR2/DNG in an installed editor. Never send a camera URL to another app. */
    private void openRawEditor(boolean choose) {
        if (openingRawEditor) { toast("Attendi il trasferimento RAW in corso."); return; }
        final Uri selected;
        final String name;
        if (localUri != null) {
            selected = localUri;
            name = queryName(localUri);
        } else if (selectedPath != null) {
            selected = savedPath != null && savedPath.equals(selectedPath) ? savedUri : null;
            name = fileName(selectedPath);
        } else {
            toast("Seleziona prima un RAW dalla Canon o dal telefono.");
            return;
        }
        if (!isSupportedRaw(name)) {
            toast("Apri editor RAW: seleziona un file .CR3, .CR2 o .DNG.");
            return;
        }
        if (selected != null) {
            launchRawEditor(selected, name, choose);
            return;
        }
        final String path = selectedPath;
        openingRawEditor = true;
        status.setText("Trasferisco RAW originale per aprirlo nell'editor…");
        log("Trasferimento RAW richiesto: " + name);
        tasks.execute(() -> {
            try {
                Uri uri = saveFromCanon(path);
                main.post(() -> {
                    openingRawEditor = false;
                    if (path.equals(selectedPath)) { savedUri = uri; savedPath = path; }
                    log("✓ RAW originale scaricato: " + name);
                    launchRawEditor(uri, name, choose);
                });
            } catch (Exception e) {
                main.post(() -> openingRawEditor = false);
                fail("Apertura editor RAW", e);
            }
        });
    }

    private static boolean isSupportedRaw(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".cr3") || n.endsWith(".cr2") || n.endsWith(".dng");
    }

    private static class EditorTarget {
        final ComponentName component;
        final String action;
        final String mime;
        final String label;
        EditorTarget(ComponentName component, String action, String mime, String label) {
            this.component = component; this.action = action; this.mime = mime; this.label = label;
        }
    }

    private List<EditorTarget> findRawEditors(Uri uri, String fileName) {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, EditorTarget> matches = new LinkedHashMap<>();
        String[] mimes = new String[]{mimeFor(fileName), "image/*", "application/octet-stream"};
        for (String action : new String[]{Intent.ACTION_EDIT, Intent.ACTION_VIEW}) {
            for (String mime : mimes) {
                Intent test = new Intent(action);
                test.addCategory(Intent.CATEGORY_DEFAULT);
                test.setDataAndType(uri, mime);
                test.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                for (ResolveInfo info : pm.queryIntentActivities(test, PackageManager.MATCH_DEFAULT_ONLY)) {
                    if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
                    ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                    String key = component.flattenToString();
                    if (!matches.containsKey(key)) {
                        String label = String.valueOf(info.loadLabel(pm));
                        matches.put(key, new EditorTarget(component, action, mime, label));
                    }
                }
            }
        }
        ArrayList<EditorTarget> result = new ArrayList<>(matches.values());
        result.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    private void launchRawEditor(Uri uri, String name, boolean choose) {
        if (!choose) {
            String componentStr = getPreferences(0).getString(RAW_EDITOR_PACKAGE, "");
            if (!componentStr.isEmpty()) {
                ComponentName comp = ComponentName.unflattenFromString(componentStr);
                if (comp != null) {
                    EditorTarget saved = new EditorTarget(comp,
                            getPreferences(0).getString(RAW_EDITOR_ACTION, Intent.ACTION_VIEW),
                            getPreferences(0).getString(RAW_EDITOR_MIME, mimeFor(name)), "");
                    if (startRawTarget(saved, uri, false)) return;
                }
                getPreferences(0).edit().remove(RAW_EDITOR_PACKAGE)
                        .remove(RAW_EDITOR_ACTION).remove(RAW_EDITOR_MIME).apply();
                log("Editor precedentemente selezionato non disponibile: scegli un'altra app.");
            }
        }
        List<EditorTarget> choices = findRawEditors(uri, name);
        if (choices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Nessun editor RAW compatibile")
                    .setMessage("Non è stata trovata un'app installata che accetti il RAW. " +
                            "Installa un editor compatibile con " + name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) +
                            " e riprova. Il file originale è già sul telefono.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        CharSequence[] labels = new CharSequence[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Scegli editor RAW preferito")
                .setMessage("Scegli una delle app installate. Potrai cambiarla dal pulsante SCEGLI APP EDITOR RAW.")
                .setItems(labels, (dialog, which) -> {
                    EditorTarget selected = choices.get(which);
                    if (startRawTarget(selected, uri, true)) {
                        getPreferences(0).edit()
                                .putString(RAW_EDITOR_PACKAGE, selected.component.flattenToString())
                                .putString(RAW_EDITOR_ACTION, selected.action)
                                .putString(RAW_EDITOR_MIME, selected.mime).apply();
                        log("Editor RAW preferito: " + selected.label);
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private boolean startRawTarget(EditorTarget target, Uri uri, boolean verbose) {
        try {
            Intent intent = new Intent(target.action);
            intent.setComponent(target.component);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.setDataAndType(uri, target.mime);
            intent.setClipData(ClipData.newUri(getContentResolver(), "RAW Canon originale", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            if (verbose) toast("Apertura editor RAW");
            return true;
        } catch (Exception e) {
            log("Editor non disponibile: " + e.getClass().getSimpleName() + " · " + e.getMessage());
            return false;
        }
    }

    private void openKodak() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.prinics.kodak.photoprinter");
        if (launch != null) { startActivity(launch); return; }
        Intent market = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/search?q=Kodak%20Photo%20Printer&c=apps"));
        try { startActivity(market); }
        catch (Exception e) { toast("Apri manualmente Kodak Photo Printer."); }
    }

    private static String fileName(String path) {
        int last = path.lastIndexOf('/');
        String name = last < 0 ? path : path.substring(last + 1);
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
    private static boolean isRaw(String name) { return name.toLowerCase(Locale.ROOT).endsWith(".cr3"); }
    private static boolean isJpeg(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }
    private static boolean isHeif(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".hif") || n.endsWith(".heif");
    }
    private static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (isJpeg(n)) return "image/jpeg";
        if (n.endsWith(".cr3")) return "image/x-canon-cr3";
        if (n.endsWith(".cr2")) return "image/x-canon-cr2";
        if (n.endsWith(".dng")) return "image/x-adobe-dng";
        if (isHeif(n)) return "image/heif";
        return "application/octet-stream";
    }
    private void log(String message) {
        String old = log.getText().toString();
        if (old.length() > 7000) old = old.substring(0, 5000);
        log.setText(message + "\n" + old);
    }
    private void fail(String action, Exception error) {
        main.post(() -> {
            status.setText("✕ " + action + ": " + error.getMessage());
            log(action + " → " + error.getClass().getSimpleName() + ": " + error.getMessage());
        });
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() {
        tasks.shutdownNow();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.bindProcessToNetwork(null);
        super.onDestroy();
    }
}
