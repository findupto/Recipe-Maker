package com.receiptmakerpro;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.pm.PackageManager;

public class MainActivity extends Activity {
  private WebView web;
  @Override public void onCreate(Bundle b){ super.onCreate(b); web=new WebView(this); setContentView(web);
    WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false); s.setAllowFileAccess(true); s.setAllowContentAccess(true);
    web.setWebViewClient(new WebViewClient()); web.setWebChromeClient(new WebChromeClient(){ @Override public void onPermissionRequest(final PermissionRequest r){ runOnUiThread(()->{ for(String x:r.getResources()) if(x.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)){ if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); } } r.grant(r.getResources()); }); }});
    web.loadUrl("file:///android_asset/www/index.html");
  }
  @Override public void onBackPressed(){ if(web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
