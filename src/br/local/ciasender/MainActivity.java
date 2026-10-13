package br.local.ciasender;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-screen UI: pick a .cia file (or a folder of them), enter the 3DS IP address and
 * send. The app serves the chosen files over HTTP and tells FBI on the 3DS where to
 * download them from.
 */
public class MainActivity extends Activity {
    private static final int HTTP_PORT = 8080;

    private static final int REQ_STORAGE = 1;
    private static final int REQ_PICK_FILE = 2;
    private static final int REQ_PICK_FOLDER = 3;

    private static final int ID_MODE_FILE = 1001;
    private static final int ID_MODE_FOLDER = 1002;

    private static final String PREF_IP = "ip";
    private static final String PREF_MODE = "mode";
    private static final String PREF_FILE = "file";
    private static final String PREF_FOLDER = "folder";

    private EditText etIp;
    private EditText etFile;
    private EditText etFolder;
    private TextView tvDevice;
    private TextView tvStatus;
    private RadioButton rbFile;
    private RadioButton rbFolder;
    private View filePanel;
    private View folderPanel;
    private ListView listView;
    private ArrayAdapter<String> adapter;

    /** Entries of the folder list; same order as {@link #names}. */
    private final ArrayList<FileServer.Source> entries = new ArrayList<FileServer.Source>();
    private final ArrayList<String> names = new ArrayList<String>();

    /** Set when the user chose something with the system picker (instead of typing a path). */
    private FileServer.Source pickedFile;
    private Uri pickedTree;
    private boolean settingText;

    /** Everything the HTTP server is allowed to serve, by file name. */
    private final Map<String, FileServer.Source> served = new ConcurrentHashMap<String, FileServer.Source>();

    private SharedPreferences prefs;
    private FileServer server;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("cia", MODE_PRIVATE);

        int p = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        tvDevice = new TextView(this);
        tvDevice.setTextSize(16);
        root.addView(tvDevice);

        // --- 3DS IP address ---
        root.addView(label(Msg.LABEL_3DS_IP.get()));
        etIp = new EditText(this);
        etIp.setSingleLine(true);
        etIp.setHint("192.168.0.50");
        etIp.setKeyListener(new IpKeyListener());   // numeric keypad, any number of dots
        etIp.setText(prefs.getString(PREF_IP, ""));
        root.addView(etIp);

        // --- Mode: file or folder ---
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        rbFile = new RadioButton(this);
        rbFile.setId(ID_MODE_FILE);
        rbFile.setText(Msg.MODE_FILE.get());
        rbFolder = new RadioButton(this);
        rbFolder.setId(ID_MODE_FOLDER);
        rbFolder.setText(Msg.MODE_FOLDER.get());
        modes.addView(rbFile, new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modes.addView(rbFolder, new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams modesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modesLp.topMargin = dp(8);
        root.addView(modes, modesLp);

        // --- File panel: path field + Browse ---
        LinearLayout fp = new LinearLayout(this);
        fp.setOrientation(LinearLayout.HORIZONTAL);
        etFile = new EditText(this);
        etFile.setSingleLine(true);
        etFile.setHint(Msg.HINT_FILE_PATH.get());
        etFile.setText(prefs.getString(PREF_FILE, ""));
        etFile.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int bf, int c) {}
            public void afterTextChanged(Editable e) {
                if (!settingText) pickedFile = null;   // the user typed a path instead
            }
        });
        fp.addView(etFile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnBrowseFile = new Button(this);
        btnBrowseFile.setText(Msg.BROWSE.get());
        btnBrowseFile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { browseFile(); }
        });
        fp.addView(btnBrowseFile, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        filePanel = fp;
        root.addView(filePanel);

        // --- Folder panel: path field + Browse (Android 5.0+), refresh button, list ---
        LinearLayout folderLayout = new LinearLayout(this);
        folderLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout folderRow = new LinearLayout(this);
        folderRow.setOrientation(LinearLayout.HORIZONTAL);
        etFolder = new EditText(this);
        etFolder.setSingleLine(true);
        etFolder.setHint(Msg.HINT_FOLDER_PATH.get());
        String defaultDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download";
        etFolder.setText(prefs.getString(PREF_FOLDER, defaultDir));
        etFolder.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int bf, int c) {}
            public void afterTextChanged(Editable e) {
                if (!settingText) pickedTree = null;
            }
        });
        folderRow.addView(etFolder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnBrowseFolder = new Button(this);
        btnBrowseFolder.setText(Msg.BROWSE.get());
        btnBrowseFolder.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { browseFolder(); }
        });
        if (Build.VERSION.SDK_INT < 21) btnBrowseFolder.setVisibility(View.GONE);   // needs the folder picker
        folderRow.addView(btnBrowseFolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        folderLayout.addView(folderRow);

        Button btnRefresh = new Button(this);
        btnRefresh.setText(Msg.REFRESH.get());
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshList(); }
        });
        folderLayout.addView(btnRefresh);

        listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, names);
        listView.setAdapter(adapter);
        folderLayout.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        folderPanel = folderLayout;
        root.addView(folderPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Send + status ---
        Button btnSend = new Button(this);
        btnSend.setText(Msg.SEND.get());
        btnSend.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendToFbi(); }
        });
        root.addView(btnSend);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(6), 0, 0);
        root.addView(tvStatus);

        setContentView(root);

        // Restore the last mode and react to changes.
        modes.check("folder".equals(prefs.getString(PREF_MODE, "file")) ? ID_MODE_FOLDER : ID_MODE_FILE);
        modes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup group, int checkedId) { applyMode(); }
        });

        server = new FileServer(new FileServer.Resolver() {
            public FileServer.Source find(String name) { return served.get(name); }
        }, HTTP_PORT, new FileServer.Listener() {
            public void onServed(String name) { status(Msg.SERVED.get(name)); }
            public void onInterrupted(String name) { status(Msg.INTERRUPTED.get(name)); }
        });
        try {
            server.start();
        } catch (IOException e) {
            tvStatus.setText(Msg.PORT_FAILED.get(HTTP_PORT, e.getMessage()));
        }

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_STORAGE);
        }
        applyMode();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (rbFolder.isChecked()) refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showDeviceIp();
    }

    @Override
    protected void onPause() {
        savePrefs();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    /** Shows a message in the status line; safe to call from any thread. */
    private void status(final String m) {
        runOnUiThread(new Runnable() {
            public void run() { tvStatus.setText(m); }
        });
    }

    /** Sets a field's text without making it count as "typed by the user". */
    private void setFieldText(EditText et, String text) {
        settingText = true;
        try {
            et.setText(text);
        } finally {
            settingText = false;
        }
    }

    private void savePrefs() {
        prefs.edit()
                .putString(PREF_IP, etIp.getText().toString().trim())
                .putString(PREF_MODE, rbFolder.isChecked() ? "folder" : "file")
                .putString(PREF_FILE, etFile.getText().toString().trim())
                .putString(PREF_FOLDER, etFolder.getText().toString().trim())
                .commit();
    }

    private void applyMode() {
        boolean fileMode = rbFile.isChecked();
        filePanel.setVisibility(fileMode ? View.VISIBLE : View.GONE);
        folderPanel.setVisibility(fileMode ? View.GONE : View.VISIBLE);
        showDeviceIp();
        if (fileMode) tvStatus.setText(""); else refreshList();
    }

    private void showDeviceIp() {
        String ip = localIp();
        tvDevice.setText(ip == null ? Msg.NO_WIFI.get() : Msg.DEVICE_IP.get(ip + ":" + HTTP_PORT));
    }

    /** This device's IPv4 address on the local network, preferring Wi-Fi/hotspot over mobile data. */
    private String localIp() {
        String preferred = null;
        String fallback = null;
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                boolean wifiLike = name.startsWith("wlan") || name.startsWith("ap")
                        || name.startsWith("swlan") || name.startsWith("eth");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        if (wifiLike) { if (preferred == null) preferred = a.getHostAddress(); }
                        else if (fallback == null) fallback = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // No usable network interface.
        }
        return preferred != null ? preferred : fallback;
    }

    // ------------------------------------------------------------------ pickers

    /** Opens the system file picker (ACTION_OPEN_DOCUMENT on 4.4+, ACTION_GET_CONTENT before). */
    private void browseFile() {
        Intent i = new Intent(Build.VERSION.SDK_INT >= 19
                ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_FILE);
        } catch (ActivityNotFoundException e) {
            tvStatus.setText(Msg.NO_PICKER.get());
        }
    }

    /** Opens the system folder picker (Android 5.0+; the button is hidden on older versions). */
    private void browseFolder() {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            tvStatus.setText(Msg.NO_PICKER.get());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_FILE) {
            pickedFile = Saf.describe(getContentResolver(), uri);
            String path = Saf.displayPath(this, uri, false);
            setFieldText(etFile, path != null ? path : pickedFile.getName());
            tvStatus.setText("");
        } else if (requestCode == REQ_PICK_FOLDER) {
            pickedTree = uri;
            String path = Saf.displayPath(this, uri, true);
            setFieldText(etFolder, path != null ? path : String.valueOf(uri.getLastPathSegment()));
            refreshList();
        }
    }

    // ------------------------------------------------------------------ folder list

    private void refreshList() {
        entries.clear();
        names.clear();
        boolean ok = true;
        try {
            if (pickedTree != null) {
                entries.addAll(Saf.listTree(getContentResolver(), pickedTree));
            } else {
                File[] found = new File(etFolder.getText().toString().trim()).listFiles(new FilenameFilter() {
                    public boolean accept(File d, String n) {
                        return n.toLowerCase(Locale.ROOT).endsWith(".cia");
                    }
                });
                if (found == null) {
                    ok = false;
                } else {
                    Arrays.sort(found);
                    for (File f : found) entries.add(new FileSource(f));
                }
            }
        } catch (Exception e) {
            ok = false;
            entries.clear();
        }
        for (FileServer.Source s : entries) names.add(s.getName());
        adapter.notifyDataSetChanged();
        if (!ok) {
            tvStatus.setText(Msg.FOLDER_UNREADABLE.get());
            return;
        }
        for (int i = 0; i < names.size(); i++) listView.setItemChecked(i, true);
        tvStatus.setText(Msg.FILES_FOUND.get(names.size()));
    }

    // ------------------------------------------------------------------ sending

    private void sendToFbi() {
        final String ip3ds = etIp.getText().toString().trim();
        if (ip3ds.length() == 0) { tvStatus.setText(Msg.ENTER_3DS_IP.get()); return; }
        if (!Protocol.isValidIpv4(ip3ds)) { tvStatus.setText(Msg.INVALID_IP.get()); return; }
        String device = localIp();
        if (device == null) { tvStatus.setText(Msg.NO_WIFI.get()); return; }

        List<FileServer.Source> chosen = new ArrayList<FileServer.Source>();
        if (rbFile.isChecked()) {
            FileServer.Source s = pickedFile;
            if (s == null) {
                String path = etFile.getText().toString().trim();
                if (path.length() == 0) { tvStatus.setText(Msg.SELECT_A_FILE.get()); return; }
                File f = new File(path);
                if (!f.isFile() || !f.canRead()) {
                    tvStatus.setText(Msg.FILE_NOT_FOUND.get(path));
                    return;
                }
                s = new FileSource(f);
            }
            chosen.add(s);
        } else {
            for (int i = 0; i < entries.size(); i++) {
                if (listView.isItemChecked(i)) chosen.add(entries.get(i));
            }
            if (chosen.isEmpty()) { tvStatus.setText(Msg.CHECK_AT_LEAST_ONE.get()); return; }
        }

        final ArrayList<String> urls = new ArrayList<String>();
        for (FileServer.Source s : chosen) {
            if (s.length() < 0) { tvStatus.setText(Msg.SIZE_UNKNOWN.get()); return; }
            served.put(s.getName(), s);
            urls.add(Protocol.urlFor(device, HTTP_PORT, s.getName()));
        }
        savePrefs();

        tvStatus.setText(Msg.SENDING.get(ip3ds));
        new Thread(new Runnable() {
            public void run() {
                try {
                    Protocol.send(ip3ds, Protocol.FBI_PORT, urls);
                    status(Msg.SENT.get(urls.size()));
                } catch (IOException e) {
                    status(Msg.SEND_FAILED.get(String.valueOf(e.getMessage())));
                }
            }
        }).start();
    }
}
