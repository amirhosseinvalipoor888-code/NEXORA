package com.nexora.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.webkit.*;
import android.view.View;
import android.content.Context;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private WebView webView;
    private ServerSocket server;
    private Socket peer;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread serverThread, discoveryThread, readThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private WifiManager.MulticastLock multicastLock;
    private final int TCP_PORT=47821, UDP_PORT=47822;

    private void js(String function, String value) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript("window."+function+" && window."+function+"("+JSONObject.quote(value)+")", null);
        });
    }
    private void status(String s) { js("onLanStatus", s); }

    public class LanBridge {
        @JavascriptInterface public void host(String name) { startHost(name == null ? "NEXORA player" : name); }
        @JavascriptInterface public void scan() { scanLan(); }
        @JavascriptInterface public void join(String ip) { connectTo(ip); }
        @JavascriptInterface public void send(String data) { sendLine(data); }
        @JavascriptInterface public void close() { closeLan(); }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(9,13,27));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(9,13,27));
        webView = new WebView(this);
        WebSettings settings=webView.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true); settings.setAllowContentAccess(true);
        webView.addJavascriptInterface(new LanBridge(), "NativeLan");
        webView.setWebViewClient(new WebViewClient()); webView.setWebChromeClient(new WebChromeClient());
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wm!=null){multicastLock=wm.createMulticastLock("nexora-discovery");multicastLock.setReferenceCounted(false);multicastLock.acquire();}
        setContentView(webView); webView.loadUrl("file:///android_asset/web/index.html");
    }

    private synchronized void startHost(String name) {
        closeLan(); running.set(true);
        serverThread=new Thread(() -> {
            try {
                server=new ServerSocket(TCP_PORT);
                status("اتاق فعال است؛ منتظر بازیکن در شبکه Wi‑Fi…");
                while(running.get()) {
                    Socket s=server.accept();
                    synchronized(this){ if(peer!=null) try{peer.close();}catch(Exception ignored){} peer=s; setupStreams(s); }
                    status("بازیکن به اتاق متصل شد.");
                    sendLine("{\"kind\":\"connected\",\"role\":\"host\"}");
                }
            } catch(Exception e){ if(running.get())status("خطای ساخت اتاق: "+e.getMessage()); }
        },"nexora-host"); serverThread.start();
        discoveryThread=new Thread(() -> {
            try(DatagramSocket ds=new DatagramSocket(UDP_PORT,InetAddress.getByName("0.0.0.0"))) {
                ds.setBroadcast(true); byte[] buf=new byte[256];
                while(running.get()) {
                    DatagramPacket p=new DatagramPacket(buf,buf.length); ds.receive(p);
                    String msg=new String(p.getData(),0,p.getLength(),"UTF-8");
                    if("NEXORA_DISCOVER".equals(msg)) {
                        String answer="NEXORA|"+name.replace("|"," ")+"|"+InetAddress.getLocalHost().getHostAddress();
                        byte[] out=answer.getBytes("UTF-8");
                        ds.send(new DatagramPacket(out,out.length,p.getAddress(),p.getPort()));
                    }
                }
            } catch(Exception ignored) {}
        },"nexora-discovery-host"); discoveryThread.start();
    }

    private void scanLan() {
        new Thread(() -> {
            try(DatagramSocket ds=new DatagramSocket()) {
                ds.setBroadcast(true); ds.setSoTimeout(650);
                byte[] data="NEXORA_DISCOVER".getBytes("UTF-8");
                DatagramPacket request=new DatagramPacket(data,data.length,InetAddress.getByName("255.255.255.255"),UDP_PORT);
                ds.send(request); long end=System.currentTimeMillis()+2600;
                while(System.currentTimeMillis()<end) {
                    try {
                        byte[] buf=new byte[512]; DatagramPacket p=new DatagramPacket(buf,buf.length); ds.receive(p);
                        String[] parts=new String(p.getData(),0,p.getLength(),"UTF-8").split("\\|",3);
                        if(parts.length>=2 && "NEXORA".equals(parts[0])) js("onLanFound",parts[1]+"|"+p.getAddress().getHostAddress());
                    } catch(SocketTimeoutException timeout) {}
                }
                status("جستجو تمام شد؛ اگر کسی پیدا نشد IP میزبان را دستی وارد کن.");
            } catch(Exception e){status("جستجوی Wi‑Fi ناموفق بود: "+e.getMessage());}
        },"nexora-scan").start();
    }

    private void connectTo(String ip) {
        new Thread(() -> {
            try {
                Socket s=new Socket(); s.connect(new InetSocketAddress(ip,TCP_PORT),5000);
                synchronized(this){peer=s;setupStreams(s);}
                status("به میزبان متصل شدی.");
                sendLine("{\"kind\":\"connected\",\"role\":\"guest\"}");
                runOnUiThread(() -> webView.evaluateJavascript("if(window.miniLan){startMiniNetworkGame('guest')}else{startNetworkGame('guest')}",null));
            } catch(Exception e){status("اتصال برقرار نشد: "+e.getMessage());}
        },"nexora-connect").start();
    }

    private synchronized void setupStreams(Socket s) throws IOException {
        reader=new BufferedReader(new InputStreamReader(s.getInputStream(),"UTF-8"));
        writer=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),"UTF-8"),true);
        readThread=new Thread(() -> {
            try { String line; while((line=reader.readLine())!=null) js("onLanMessage",line); }
            catch(Exception ignored) {} finally { status("ارتباط با بازیکن قطع شد."); }
        },"nexora-reader"); readThread.start();
    }
    private synchronized void sendLine(String line) {
        if(writer!=null){writer.println(line);writer.flush();}
    }
    private synchronized void closeLan() {
        running.set(false);
        try{if(peer!=null)peer.close();}catch(Exception ignored){}
        try{if(server!=null)server.close();}catch(Exception ignored){}
        peer=null;server=null;writer=null;reader=null;
    }
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){closeLan();if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();super.onDestroy();}
}