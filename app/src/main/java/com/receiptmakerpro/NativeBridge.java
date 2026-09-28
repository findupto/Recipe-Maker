package com.receiptmakerpro;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class NativeBridge {
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Activity activity;
    private final WebView web;
    private BluetoothSocket socket;
    private BluetoothDevice connectedDevice;
    private SpeechRecognizer speechRecognizer;
    private BroadcastReceiver bluetoothReceiver;
    private final Set<String> discoveredAddresses = new HashSet<>();

    public NativeBridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    private void js(String script) {
        activity.runOnUiThread(() -> web.evaluateJavascript(script, null));
    }

    private boolean btPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean scanPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface
    public void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            ArrayList<String> p = new ArrayList<>();
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                p.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                p.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!p.isEmpty()) activity.requestPermissions(p.toArray(new String[0]), 41);
        }
    }

    @JavascriptInterface
    public String listPairedPrinters() {
        try {
            if (!btPermission()) return "[]";
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return "[]";
            JSONArray out = new JSONArray();
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                JSONObject x = new JSONObject();
                x.put("name", d.getName() == null ? "Bluetooth printer" : d.getName());
                x.put("address", d.getAddress());
                out.put(x);
            }
            return out.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public void discoverPrinters() {
        activity.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 31 && (!btPermission() || !scanPermission())) {
                requestBluetoothPermissions();
                js("window.__printerStatus && window.__printerStatus('permission','Allow Bluetooth access, then tap Printer again.');");
                return;
            }
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) { js("window.__printerStatus && window.__printerStatus('error','Bluetooth is not available on this device.');"); return; }
                if (!adapter.isEnabled()) { js("window.__printerStatus && window.__printerStatus('error','Bluetooth is turned off.');"); openBluetoothSettings(); return; }
                unregisterBluetoothReceiver();
                discoveredAddresses.clear();
                JSONArray initial = new JSONArray();
                for (BluetoothDevice d : adapter.getBondedDevices()) { discoveredAddresses.add(d.getAddress()); initial.put(deviceJson(d)); }
                js("window.__printerList && window.__printerList(" + initial.toString() + ");");
                js("window.__printerStatus && window.__printerStatus('scanning','Searching for nearby Bluetooth devices…');");
                bluetoothReceiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                            if (d != null && d.getAddress() != null && discoveredAddresses.add(d.getAddress())) {
                                try { js("window.__printerFound && window.__printerFound(" + deviceJson(d).toString() + ");"); } catch (Exception ignored) {}
                            }
                        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                            js("window.__printerStatus && window.__printerStatus('scan_complete','Bluetooth scan complete.');");
                        }
                    }
                };
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else activity.registerReceiver(bluetoothReceiver, filter);
                adapter.cancelDiscovery();
                adapter.startDiscovery();
            } catch (SecurityException e) {
                js("window.__printerStatus && window.__printerStatus('error','Bluetooth permission was denied.');");
            } catch (Exception e) {
                js("window.__printerStatus && window.__printerStatus('error'," + JSONObject.quote(String.valueOf(e.getMessage())) + ");");
            }
        });
    }

    private JSONObject deviceJson(BluetoothDevice d) throws Exception {
        JSONObject x = new JSONObject();
        x.put("name", d.getName() == null ? "Bluetooth printer" : d.getName());
        x.put("address", d.getAddress());
        x.put("paired", d.getBondState() == BluetoothDevice.BOND_BONDED);
        return x;
    }

    private void unregisterBluetoothReceiver() {
        if (bluetoothReceiver != null) {
            try { activity.unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
            bluetoothReceiver = null;
        }
    }

    @JavascriptInterface
    public void openBluetoothSettings() {
        activity.startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    @JavascriptInterface
    public void connectPrinter(String address) {
        new Thread(() -> {
            try {
                if (!btPermission()) {
                    js("window.__printerStatus && window.__printerStatus('permission','');");
                    return;
                }
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice device = adapter.getRemoteDevice(address);
                closeSocket();
                adapter.cancelDiscovery();
                BluetoothSocket candidate = device.createRfcommSocketToServiceRecord(SPP_UUID);
                try { candidate.connect(); } catch (Exception first) {
                    try { candidate.close(); } catch (Exception ignored) {}
                    candidate = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                    candidate.connect();
                }
                socket = candidate;
                connectedDevice = device;
                String name = device.getName() == null ? address : device.getName();
                js("window.__printerStatus && window.__printerStatus('connected'," + JSONObject.quote(name) + ");");
            } catch (Exception e) {
                closeSocket();
                js("window.__printerStatus && window.__printerStatus('error'," + JSONObject.quote(String.valueOf(e.getMessage())) + ");");
            }
        }).start();
    }

    @JavascriptInterface
    public void connectFirstPairedPrinter() {
        try {
            if (!btPermission()) {
                requestBluetoothPermissions();
                js("window.__printerStatus && window.__printerStatus('permission','Allow Bluetooth access, then try again.');");
                return;
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                js("window.__printerStatus && window.__printerStatus('error','Bluetooth is turned off.');");
                return;
            }
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                connectPrinter(d.getAddress());
                return;
            }
            js("window.__printerStatus && window.__printerStatus('error','No paired Bluetooth printer found. Pair the printer first.');");
        } catch (Exception e) {
            js("window.__printerStatus && window.__printerStatus('error'," + JSONObject.quote(String.valueOf(e.getMessage())) + ");");
        }
    }

    @JavascriptInterface
    public void disconnectPrinter() {
        closeSocket();
        js("window.__printerStatus && window.__printerStatus('disconnected','');");
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        connectedDevice = null;
    }

    @JavascriptInterface
    public void printReceipt(String receiptJson) {
        new Thread(() -> {
            try {
                if (socket == null || !socket.isConnected()) {
                    js("window.__printerStatus && window.__printerStatus('not_connected','');");
                    return;
                }
                JSONObject r = new JSONObject(receiptJson);
                OutputStream out = socket.getOutputStream();
                final int W = 48;
                String theme = r.optString("receiptTheme", "modern").toLowerCase(java.util.Locale.US);
                String footer = r.optString("footer", "Thank you for your business.");
                StringBuilder text = new StringBuilder();

                text.append("\u001B\u0040");
                text.append(boldOn());
                text.append(center(truncate(r.optString("business"), W))).append("\n");
                text.append(boldOff());
                if ("luxe".equals(theme) || "elegant".equals(theme)) {
                    text.append(center("✦  PREMIUM RECEIPT  ✦")).append("\n");
                } else if ("bold".equals(theme)) {
                    text.append(center("RECEIPT")).append("\n");
                }
                if (!"minimal".equals(theme)) {
                    text.append(center(truncate(r.optString("address"), W))).append("\n");
                    text.append(center(truncate(r.optString("phone"), W))).append("\n");
                }
                text.append(separator(theme, W)).append("\n");
                text.append("Receipt #").append(r.optString("id")).append("\n");
                text.append(truncate(r.optString("date"), W)).append("\n");
                text.append("Customer: ").append(truncate(r.optString("customer"), W - 10)).append("\n");
                text.append("Payment: ").append(r.optString("payment", "Cash")).append("\n");
                text.append(separator(theme, W)).append("\n");
                text.append(boldOn());
                text.append(row("ITEM", "QTY", "AMOUNT", W)).append("\n");
                text.append(boldOff());

                JSONArray items = r.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String name = item.optString("name");
                        int qty = item.optInt("qty", 1);
                        double price = item.optDouble("price", 0);
                        text.append(row(name, String.valueOf(qty),
                                String.format(java.util.Locale.US, "%.2f", price * qty), W)).append("\n");
                    }
                }

                text.append(separator(theme, W)).append("\n");
                double subtotal = r.optDouble("subtotal", r.optDouble("total", 0));
                double discount = r.optDouble("discountAmount", 0);
                double tax = r.optDouble("tax", 0);
                double tip = r.optDouble("tipAmount", 0);
                text.append(twoCol("Subtotal", String.format(java.util.Locale.US, "%.2f", subtotal), W)).append("\n");
                if (discount > 0) text.append(twoCol("Discount", "-"+String.format(java.util.Locale.US, "%.2f", discount), W)).append("\n");
                text.append(twoCol("Tax", String.format(java.util.Locale.US, "%.2f", tax), W)).append("\n");
                if (tip > 0) text.append(twoCol("Tip", String.format(java.util.Locale.US, "%.2f", tip), W)).append("\n");
                text.append(boldOn());
                text.append(twoCol("TOTAL", String.format(java.util.Locale.US, "%.2f", r.optDouble("total", 0)), W)).append("\n");
                text.append(boldOff());
                text.append(separator(theme, W)).append("\n");
                text.append("\n");
                text.append(center(wrapFooter(footer, W))).append("\n");
                text.append("\n\n\n");

                out.write(text.toString().getBytes(StandardCharsets.UTF_8));
                out.write(new byte[]{0x1D, 0x56, 0x00});
                out.flush();
                js("window.__printerStatus && window.__printerStatus('printed','');");
            } catch (Exception e) {
                js("window.__printerStatus && window.__printerStatus('error'," + JSONObject.quote(String.valueOf(e.getMessage())) + ");");
            }
        }).start();
    }


    private String center(String s) {
        if (s == null) return "";
        String[] lines = s.split("\\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = truncate(lines[i], 48);
            int pad = Math.max(0, (48 - line.length()) / 2);
            out.append(repeat(" ", pad)).append(line);
            if (i < lines.length - 1) out.append("\n");
        }
        return out.toString();
    }

    private String separator(String theme, int w) {
        if ("luxe".equals(theme)) return repeat("═", w);
        if ("elegant".equals(theme)) return repeat("·", w);
        if ("bold".equals(theme)) return repeat("=", w);
        if ("minimal".equals(theme)) return repeat("-", 24);
        return repeat("-", w);
    }

    private String row(String name, String qty, String amount, int w) {
        String q = truncate(qty, 4);
        String a = truncate(amount, 12);
        int nameW = Math.max(12, w - 4 - 12 - 2);
        String n = truncate(name, nameW);
        return padRight(n, nameW) + " " + padLeft(q, 4) + " " + padLeft(a, 12);
    }

    private String twoCol(String left, String right, int w) {
        return padRight(truncate(left, Math.max(1, w - right.length() - 1)), Math.max(1, w - right.length() - 1)) + " " + right;
    }

    private String wrapFooter(String footer, int w) {
        if (footer == null || footer.trim().isEmpty()) return "Thank you for your business.";
        String s = footer.trim().replace("\n", " ");
        return truncate(s, w);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String padRight(String s, int n) {
        StringBuilder b = new StringBuilder(s == null ? "" : s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private String padLeft(String s, int n) {
        String x = s == null ? "" : s;
        if (x.length() >= n) return x.substring(x.length() - n);
        return repeat(" ", n - x.length()) + x;
    }

    private String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private String boldOn() { return "\u001B\u0045\u0001"; }
    private String boldOff() { return "\u001B\u0045\u0000"; }

    @JavascriptInterface
    public void startVoice() {
        activity.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
                js("window.__nativeVoiceStatus && window.__nativeVoiceStatus('permission','Microphone permission requested.');");
                return;
            }
            startVoiceInternal();
        });
    }

    private void startVoiceInternal() {
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                js("window.__nativeVoiceStatus && window.__nativeVoiceStatus('error','Speech recognition is not available on this device');");
                return;
            }
            stopVoiceInternal();
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(android.os.Bundle b) { js("window.__nativeVoiceStatus && window.__nativeVoiceStatus('listening','');"); }
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float rms) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() { js("window.__nativeVoiceStatus && window.__nativeVoiceStatus('processing','');"); }
                public void onError(int error) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        js("window.__nativeVoiceStatus && window.__nativeVoiceStatus('listening','');");
                        try { restartListening(); } catch (Exception ignored) {}
                    } else {
                        js("window.__nativeVoiceStatus && window.__nativeVoiceStatus('error'," + JSONObject.quote(errorText(error)) + ");");
                    }
                }
                public void onResults(android.os.Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        org.json.JSONArray choices = new org.json.JSONArray();
                        for (String match : matches) choices.put(match);
                        js("window.__nativeVoiceResult && window.__nativeVoiceResult(" + choices.toString() + ");");
                    }
                    js("window.__nativeVoiceStatus && window.__nativeVoiceStatus('done','');");
                    try { restartListening(); } catch (Exception ignored) {}
                }
                public void onPartialResults(android.os.Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty())
                        js("window.__nativeVoicePartial && window.__nativeVoicePartial(" + JSONObject.quote(matches.get(0)) + ");");
                }
                public void onEvent(int t, android.os.Bundle b) {}
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-PK");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-PK");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            speechRecognizer.startListening(i);
    }

    public void onPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 10) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startVoiceInternal();
            else js("window.__nativeVoiceStatus && window.__nativeVoiceStatus('error','Microphone permission was denied.');");
        } else if (requestCode == 41) {
            boolean granted = true; for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            js("window.__printerStatus && window.__printerStatus(" + JSONObject.quote(granted ? "permission_granted" : "error") + "," + JSONObject.quote(granted ? "Bluetooth permission granted — tap Printer again." : "Bluetooth permission was denied.") + ");");
        }
    }

    @JavascriptInterface
    public void stopVoice() {
        activity.runOnUiThread(this::stopVoiceInternal);
    }

    private void stopVoiceInternal() {
        if (speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void restartListening() {
        try {
            if (speechRecognizer == null) return;
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-PK");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            speechRecognizer.startListening(i);
        } catch (Exception ignored) {}
    }

    private String errorText(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_AUDIO: return "Microphone audio error";
            case SpeechRecognizer.ERROR_CLIENT: return "Voice recognition client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission denied";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Voice recognition network error";
            case SpeechRecognizer.ERROR_NO_MATCH: return "I could not understand that command";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Voice recognition is busy";
            case SpeechRecognizer.ERROR_SERVER: return "Voice recognition server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech detected";
            default: return "Voice recognition error";
        }
    }

    public void destroy() {
        stopVoiceInternal();
        unregisterBluetoothReceiver();
        closeSocket();
    }
}
