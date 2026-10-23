package com.iri.controlelg;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends Activity {

    private WebView web;
    private OkHttpClient client;
    private WebSocket ws;
    private WebSocket ptr;
    private int wsGen = 0;
    private int ptrGen = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#14161A"));
        w.setNavigationBarColor(Color.parseColor("#14161A"));

        client = buildClient();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#14161A"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        closeAll();
        super.onDestroy();
    }

    /** Cliente que aceita o certificado próprio da TV (só é usado para falar com a TV). */
    private OkHttpClient buildClient() {
        try {
            final X509TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(ctx.getSocketFactory(), tm)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectionSpecs(Arrays.asList(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
        } catch (Exception e) {
            return new OkHttpClient();
        }
    }

    private void closeAll() {
        wsGen++;
        ptrGen++;
        if (ws != null) {
            ws.cancel();
            ws = null;
        }
        if (ptr != null) {
            ptr.cancel();
            ptr = null;
        }
    }

    private void emit(final String type, final String data) {
        final String js = "window.nativeEvent(" + JSONObject.quote(type) + ","
                + JSONObject.quote(data == null ? "" : data) + ")";
        runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript(js, null);
            }
        });
    }

    /** Funções que a página (index.html) pode chamar. */
    public class Bridge {

        @JavascriptInterface
        public void connect(String ip, boolean secure) {
            closeAll();
            final int gen = wsGen;
            String url = secure ? "wss://" + ip + ":3001" : "ws://" + ip + ":3000";
            try {
                Request req = new Request.Builder().url(url).build();
                ws = client.newWebSocket(req, new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket s, Response r) {
                        if (gen == wsGen) emit("open", "");
                    }

                    @Override
                    public void onMessage(WebSocket s, String text) {
                        if (gen == wsGen) emit("message", text);
                    }

                    @Override
                    public void onClosing(WebSocket s, int code, String reason) {
                        s.close(1000, null);
                    }

                    @Override
                    public void onClosed(WebSocket s, int code, String reason) {
                        if (gen == wsGen) emit("close", "");
                    }

                    @Override
                    public void onFailure(WebSocket s, Throwable t, Response r) {
                        if (gen == wsGen) emit("close", String.valueOf(t.getMessage()));
                    }
                });
            } catch (Exception e) {
                emit("close", String.valueOf(e.getMessage()));
            }
        }

        @JavascriptInterface
        public boolean send(String text) {
            WebSocket s = ws;
            return s != null && s.send(text);
        }

        @JavascriptInterface
        public void closeWs() {
            closeAll();
        }

        @JavascriptInterface
        public void openPointer(String url) {
            if (ptr != null) {
                ptrGen++;
                ptr.cancel();
                ptr = null;
            }
            final int gen = ++ptrGen;
            try {
                Request req = new Request.Builder().url(url).build();
                ptr = client.newWebSocket(req, new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket s, Response r) {
                        if (gen == ptrGen) emit("ptropen", "");
                    }

                    @Override
                    public void onClosing(WebSocket s, int code, String reason) {
                        s.close(1000, null);
                    }

                    @Override
                    public void onClosed(WebSocket s, int code, String reason) {
                        if (gen == ptrGen) emit("ptrclose", "");
                    }

                    @Override
                    public void onFailure(WebSocket s, Throwable t, Response r) {
                        if (gen == ptrGen) emit("ptrclose", String.valueOf(t.getMessage()));
                    }
                });
            } catch (Exception e) {
                emit("ptrclose", String.valueOf(e.getMessage()));
            }
        }

        @JavascriptInterface
        public boolean sendPointer(String text) {
            WebSocket p = ptr;
            return p != null && p.send(text);
        }

        /** Procura TVs LG webOS na rede (SSDP). */
        @JavascriptInterface
        public void discover() {
            new Thread(() -> {
                WifiManager.MulticastLock lock = null;
                DatagramSocket sock = null;
                try {
                    WifiManager wm = (WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        lock = wm.createMulticastLock("controlelg");
                        lock.setReferenceCounted(false);
                        lock.acquire();
                    }
                    sock = new DatagramSocket();
                    sock.setSoTimeout(1000);

                    String msg = "M-SEARCH * HTTP/1.1\r\n"
                            + "HOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\n"
                            + "MX: 3\r\n"
                            + "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n";
                    byte[] data = msg.getBytes("UTF-8");
                    InetAddress group = InetAddress.getByName("239.255.255.250");
                    for (int i = 0; i < 3; i++) {
                        sock.send(new DatagramPacket(data, data.length, group, 1900));
                        Thread.sleep(200);
                    }

                    Set<String> seen = new HashSet<>();
                    byte[] buf = new byte[2048];
                    long end = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < end) {
                        try {
                            DatagramPacket pk = new DatagramPacket(buf, buf.length);
                            sock.receive(pk);
                            String ip = pk.getAddress().getHostAddress();
                            if (seen.add(ip)) emit("found", ip);
                        } catch (SocketTimeoutException ignored) {
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (sock != null) sock.close();
                    if (lock != null && lock.isHeld()) lock.release();
                    emit("discoverEnd", "");
                }
            }).start();
        }

        /** Liga a TV enviando o pacote Wake-on-LAN. */
        @JavascriptInterface
        public void wol(final String mac, final String ip) {
            new Thread(() -> {
                DatagramSocket s = null;
                try {
                    String[] parts = mac.trim().replace("-", ":").split(":");
                    if (parts.length != 6) {
                        emit("wol", "erro");
                        return;
                    }
                    byte[] m = new byte[6];
                    for (int i = 0; i < 6; i++) {
                        m[i] = (byte) Integer.parseInt(parts[i].trim(), 16);
                    }
                    byte[] pkt = new byte[102];
                    for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
                    for (int i = 1; i <= 16; i++) System.arraycopy(m, 0, pkt, i * 6, 6);

                    s = new DatagramSocket();
                    s.setBroadcast(true);
                    String[] alvos;
                    int ponto = ip == null ? -1 : ip.lastIndexOf('.');
                    if (ponto > 0) {
                        alvos = new String[]{"255.255.255.255", ip.substring(0, ponto) + ".255"};
                    } else {
                        alvos = new String[]{"255.255.255.255"};
                    }
                    for (String alvo : alvos) {
                        InetAddress addr = InetAddress.getByName(alvo);
                        for (int porta : new int[]{9, 7}) {
                            s.send(new DatagramPacket(pkt, pkt.length, addr, porta));
                        }
                    }
                    emit("wol", "ok");
                } catch (Exception e) {
                    emit("wol", "erro");
                } finally {
                    if (s != null) s.close();
                }
            }).start();
        }
    }
}
