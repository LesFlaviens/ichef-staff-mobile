package ch.ichef.staff;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coquille Android minimale pour l'espace collaborateur iCHEF Staff.
 * NE valide aucun PIN, ne génère aucune session et n'ajoute AUCUN pont JS natif.
 * Toutes les autorisations et les activations restent côté serveur iCHEF.
 */
public class MainActivity extends Activity {
    private static final String HOME = "https://os.ichef.ch/portail-staff.html";
    private static final String TRUSTED_HOST = "os.ichef.ch";
    private static final int FILE_REQUEST = 1001;
    private static final int MEDIA_PERMISSIONS = 1002;

    private WebView webView;
    private ProgressBar progress;
    private TextView offlineText;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMediaRequest;
    private String[] pendingMediaResources;
    private boolean firstLoad = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // L'outil WebView de production ne doit jamais exposer ses outils de debug.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            WebView.setWebContentsDebuggingEnabled(false);
        }
        makeInterface();
        configureWebView();

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            firstLoad = false;
        } else {
            openIncomingLink(getIntent());
        }
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void makeInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(5, 8, 10));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), 0, dp(8), 0);
        bar.setBackgroundColor(Color.rgb(8, 11, 14));

        TextView name = new TextView(this);
        name.setText("iCHEF  STAFF");
        name.setTextColor(Color.rgb(230, 184, 89));
        name.setTextSize(16);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(name, new LinearLayout.LayoutParams(0, dp(44), 1));
        name.setGravity(Gravity.CENTER_VERTICAL);

        Button more = new Button(this);
        more.setText("⋮");
        more.setTextColor(Color.WHITE);
        more.setTextSize(20);
        more.setContentDescription("Options iCHEF Staff");
        bar.addView(more, new LinearLayout.LayoutParams(dp(46), dp(44)));
        more.setOnClickListener(view -> openMenu(more));

        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(230, 184, 89)));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        offlineText = new TextView(this);
        offlineText.setText("Connexion indisponible — vérifiez Internet, puis touchez pour réessayer.");
        offlineText.setTextColor(Color.WHITE);
        offlineText.setTextSize(13);
        offlineText.setBackgroundColor(Color.rgb(70, 28, 30));
        offlineText.setPadding(dp(14), dp(12), dp(14), dp(12));
        offlineText.setVisibility(View.GONE);
        offlineText.setOnClickListener(v -> {
            offlineText.setVisibility(View.GONE);
            webView.reload();
        });
        root.addView(offlineText, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void configureWebView() {
        WebSettings cfg = webView.getSettings();
        // L'application web iCHEF nécessite JS + DOM storage pour ses modules.
        cfg.setJavaScriptEnabled(true);
        cfg.setDomStorageEnabled(true);
        cfg.setJavaScriptCanOpenWindowsAutomatically(false);
        cfg.setSupportMultipleWindows(false);
        cfg.setAllowFileAccess(false);
        cfg.setAllowFileAccessFromFileURLs(false);
        cfg.setAllowUniversalAccessFromFileURLs(false);
        cfg.setAllowContentAccess(true); // Seulement via le sélecteur de fichiers Android.
        cfg.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        cfg.setMediaPlaybackRequiresUserGesture(true);
        cfg.setBuiltInZoomControls(false);
        cfg.setDisplayZoomControls(false);
        cfg.setLoadWithOverviewMode(true);
        cfg.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cfg.setSafeBrowsingEnabled(true);

        // Marqueur UI uniquement. Ne JAMAIS l'utiliser comme preuve d'identité.
        cfg.setUserAgentString(cfg.getUserAgentString() + " iCHEFStaffAndroid/1.0");
        CookieManager cookie = CookieManager.getInstance();
        cookie.setAcceptCookie(true);
        // API Staff peut être sur un domaine distinct (tableau-system.onrender.com).
        // A réévaluer avec les paramètres SameSite du serveur lors des tests réels.
        cookie.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isTrusted(uri)) return false;
                openExternally(uri);
                return true;
            }

            // Android 6 / WebView anciens : également protéger l'ancienne API.
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isTrusted(uri)) return false;
                openExternally(uri);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) offlineText.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                firstLoad = false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                if (newProgress > 30) offlineText.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Sélecteur de fichiers indisponible", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> requestMediaPermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (pendingMediaRequest == request) {
                        pendingMediaRequest = null;
                        pendingMediaResources = null;
                    }
                });
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            Uri uri = Uri.parse(url);
            if (!isTrustedDownload(uri)) {
                openExternally(uri);
                return;
            }
            try {
                DownloadManager.Request req = new DownloadManager.Request(uri);
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                name = name.replaceAll("[^a-zA-Z0-9._ -]", "_");
                if (name.length() > 100) name = name.substring(0, 100);
                req.setTitle(name);
                req.setDescription("Document iCHEF Staff");
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setAllowedOverMetered(true);
                if (mimeType != null) req.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) req.addRequestHeader("Cookie", cookies);
                if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                // Dans l'espace privé de l'app : pas de permission de stockage à demander.
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) throw new IllegalStateException("DownloadManager absent");
                dm.enqueue(req);
                Toast.makeText(this, "Téléchargement commencé", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Téléchargement impossible", Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean isTrusted(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && TRUSTED_HOST.equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getUserInfo() == null;
    }

    private boolean isTrustedDownload(Uri uri) {
        return isTrusted(uri) || (uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && "tableau-system.onrender.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443));
    }

    private void openExternally(Uri uri) {
        if (uri == null) return;
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("https")
                && !scheme.equalsIgnoreCase("mailto")
                && !scheme.equalsIgnoreCase("tel"))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (Exception e) { Toast.makeText(this, "Aucune application pour ce lien", Toast.LENGTH_SHORT).show(); }
    }

    private void openMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 1, "Ouvrir une invitation");
        menu.getMenu().add(0, 2, 2, "Accueil Staff");
        menu.getMenu().add(0, 3, 3, "Actualiser");
        menu.getMenu().add(0, 4, 4, "Ouvrir dans le navigateur");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) pasteInvitation();
            else if (id == 2) webView.loadUrl(HOME);
            else if (id == 3) webView.reload();
            else if (id == 4) openExternally(Uri.parse(HOME));
            return true;
        });
        menu.show();
    }

    private void pasteInvitation() {
        EditText edit = new EditText(this);
        edit.setSingleLine(false);
        edit.setMinLines(2);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setHint("Collez le lien reçu par e-mail");
        LinearLayout outer = new LinearLayout(this);
        outer.setPadding(dp(22), dp(8), dp(22), 0);
        outer.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Ouvrir mon invitation iCHEF")
                .setView(outer)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Ouvrir", (d, which) -> openInvitationUrl(edit.getText().toString()))
                .show();
    }

    private boolean validInvitationUrl(Uri uri) {
        return isTrusted(uri) && "/portail-staff.html".equals(uri.getPath());
    }

    private void openInvitationUrl(String raw) {
        if (raw == null || raw.trim().length() > 4096) {
            Toast.makeText(this, "Lien d'invitation non valide", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri;
        try { uri = Uri.parse(raw.trim()); }
        catch (Exception e) { uri = null; }
        if (!validInvitationUrl(uri)) {
            Toast.makeText(this, "Lien non reconnu : vérifiez os.ichef.ch", Toast.LENGTH_LONG).show();
            return;
        }
        webView.loadUrl(uri.toString());
    }

    private void openIncomingLink(Intent intent) {
        Uri uri = intent != null ? intent.getData() : null;
        // Ne jamais logguer l'URL : le fragment peut contenir un jeton d'invitation.
        if (validInvitationUrl(uri)) webView.loadUrl(uri.toString());
        else webView.loadUrl(HOME);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri uri = intent != null ? intent.getData() : null;
        if (validInvitationUrl(uri)) webView.loadUrl(uri.toString());
    }

    private boolean mediaOriginTrusted(PermissionRequest req) {
        return req != null && isTrusted(req.getOrigin());
    }

    private void requestMediaPermission(PermissionRequest req) {
        if (!mediaOriginTrusted(req)) { req.deny(); return; }
        List<String> androidPermissions = new ArrayList<>();
        List<String> webResources = new ArrayList<>();
        for (String resource : req.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                androidPermissions.add(Manifest.permission.RECORD_AUDIO);
                webResources.add(resource);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                androidPermissions.add(Manifest.permission.CAMERA);
                webResources.add(resource);
            }
        }
        if (webResources.isEmpty()) { req.deny(); return; }
        List<String> missing = new ArrayList<>();
        for (String p : androidPermissions) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            req.grant(webResources.toArray(new String[0]));
        } else {
            if (pendingMediaRequest != null) pendingMediaRequest.deny();
            pendingMediaRequest = req;
            pendingMediaResources = webResources.toArray(new String[0]);
            requestPermissions(missing.toArray(new String[0]), MEDIA_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MEDIA_PERMISSIONS || pendingMediaRequest == null) return;
        boolean allGranted = true;
        for (int grant : grantResults) if (grant != PackageManager.PERMISSION_GRANTED) allGranted = false;
        if (allGranted && mediaOriginTrusted(pendingMediaRequest))
            pendingMediaRequest.grant(pendingMediaResources);
        else pendingMediaRequest.deny();
        pendingMediaRequest = null;
        pendingMediaResources = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (pendingMediaRequest != null) pendingMediaRequest.deny();
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}

FICHIER: app/src/main/java/ch/ichef/staff/MainActivity.java
==========================================================================
package ch.ichef.staff;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coquille Android minimale pour l'espace collaborateur iCHEF Staff.
 * NE valide aucun PIN, ne génère aucune session et n'ajoute AUCUN pont JS natif.
 * Toutes les autorisations et les activations restent côté serveur iCHEF.
 */
public class MainActivity extends Activity {
    private static final String HOME = "https://os.ichef.ch/portail-staff.html";
    private static final String TRUSTED_HOST = "os.ichef.ch";
    private static final int FILE_REQUEST = 1001;
    private static final int MEDIA_PERMISSIONS = 1002;

    private WebView webView;
    private ProgressBar progress;
    private TextView offlineText;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMediaRequest;
    private String[] pendingMediaResources;
    private boolean firstLoad = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // L'outil WebView de production ne doit jamais exposer ses outils de debug.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            WebView.setWebContentsDebuggingEnabled(false);
        }
        makeInterface();
        configureWebView();

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            firstLoad = false;
        } else {
            openIncomingLink(getIntent());
        }
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void makeInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(5, 8, 10));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), 0, dp(8), 0);
        bar.setBackgroundColor(Color.rgb(8, 11, 14));

        TextView name = new TextView(this);
        name.setText("iCHEF  STAFF");
        name.setTextColor(Color.rgb(230, 184, 89));
        name.setTextSize(16);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(name, new LinearLayout.LayoutParams(0, dp(44), 1));
        name.setGravity(Gravity.CENTER_VERTICAL);

        Button more = new Button(this);
        more.setText("⋮");
        more.setTextColor(Color.WHITE);
        more.setTextSize(20);
        more.setContentDescription("Options iCHEF Staff");
        bar.addView(more, new LinearLayout.LayoutParams(dp(46), dp(44)));
        more.setOnClickListener(view -> openMenu(more));

        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(230, 184, 89)));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        offlineText = new TextView(this);
        offlineText.setText("Connexion indisponible — vérifiez Internet, puis touchez pour réessayer.");
        offlineText.setTextColor(Color.WHITE);
        offlineText.setTextSize(13);
        offlineText.setBackgroundColor(Color.rgb(70, 28, 30));
        offlineText.setPadding(dp(14), dp(12), dp(14), dp(12));
        offlineText.setVisibility(View.GONE);
        offlineText.setOnClickListener(v -> {
            offlineText.setVisibility(View.GONE);
            webView.reload();
        });
        root.addView(offlineText, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void configureWebView() {
        WebSettings cfg = webView.getSettings();
        // L'application web iCHEF nécessite JS + DOM storage pour ses modules.
        cfg.setJavaScriptEnabled(true);
        cfg.setDomStorageEnabled(true);
        cfg.setJavaScriptCanOpenWindowsAutomatically(false);
        cfg.setSupportMultipleWindows(false);
        cfg.setAllowFileAccess(false);
        cfg.setAllowFileAccessFromFileURLs(false);
        cfg.setAllowUniversalAccessFromFileURLs(false);
        cfg.setAllowContentAccess(true); // Seulement via le sélecteur de fichiers Android.
        cfg.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        cfg.setMediaPlaybackRequiresUserGesture(true);
        cfg.setBuiltInZoomControls(false);
        cfg.setDisplayZoomControls(false);
        cfg.setLoadWithOverviewMode(true);
        cfg.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cfg.setSafeBrowsingEnabled(true);

        // Marqueur UI uniquement. Ne JAMAIS l'utiliser comme preuve d'identité.
        cfg.setUserAgentString(cfg.getUserAgentString() + " iCHEFStaffAndroid/1.0");
        CookieManager cookie = CookieManager.getInstance();
        cookie.setAcceptCookie(true);
        // API Staff peut être sur un domaine distinct (tableau-system.onrender.com).
        // A réévaluer avec les paramètres SameSite du serveur lors des tests réels.
        cookie.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isTrusted(uri)) return false;
                openExternally(uri);
                return true;
            }

            // Android 6 / WebView anciens : également protéger l'ancienne API.
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isTrusted(uri)) return false;
                openExternally(uri);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) offlineText.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                firstLoad = false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                if (newProgress > 30) offlineText.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Sélecteur de fichiers indisponible", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> requestMediaPermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (pendingMediaRequest == request) {
                        pendingMediaRequest = null;
                        pendingMediaResources = null;
                    }
                });
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            Uri uri = Uri.parse(url);
            if (!isTrustedDownload(uri)) {
                openExternally(uri);
                return;
            }
            try {
                DownloadManager.Request req = new DownloadManager.Request(uri);
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                name = name.replaceAll("[^a-zA-Z0-9._ -]", "_");
                if (name.length() > 100) name = name.substring(0, 100);
                req.setTitle(name);
                req.setDescription("Document iCHEF Staff");
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setAllowedOverMetered(true);
                if (mimeType != null) req.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) req.addRequestHeader("Cookie", cookies);
                if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                // Dans l'espace privé de l'app : pas de permission de stockage à demander.
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) throw new IllegalStateException("DownloadManager absent");
                dm.enqueue(req);
                Toast.makeText(this, "Téléchargement commencé", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Téléchargement impossible", Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean isTrusted(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && TRUSTED_HOST.equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getUserInfo() == null;
    }

    private boolean isTrustedDownload(Uri uri) {
        return isTrusted(uri) || (uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && "tableau-system.onrender.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443));
    }

    private void openExternally(Uri uri) {
        if (uri == null) return;
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("https")
                && !scheme.equalsIgnoreCase("mailto")
                && !scheme.equalsIgnoreCase("tel"))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (Exception e) { Toast.makeText(this, "Aucune application pour ce lien", Toast.LENGTH_SHORT).show(); }
    }

    private void openMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 1, "Ouvrir une invitation");
        menu.getMenu().add(0, 2, 2, "Accueil Staff");
        menu.getMenu().add(0, 3, 3, "Actualiser");
        menu.getMenu().add(0, 4, 4, "Ouvrir dans le navigateur");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) pasteInvitation();
            else if (id == 2) webView.loadUrl(HOME);
            else if (id == 3) webView.reload();
            else if (id == 4) openExternally(Uri.parse(HOME));
            return true;
        });
        menu.show();
    }

    private void pasteInvitation() {
        EditText edit = new EditText(this);
        edit.setSingleLine(false);
        edit.setMinLines(2);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setHint("Collez le lien reçu par e-mail");
        LinearLayout outer = new LinearLayout(this);
        outer.setPadding(dp(22), dp(8), dp(22), 0);
        outer.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Ouvrir mon invitation iCHEF")
                .setView(outer)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Ouvrir", (d, which) -> openInvitationUrl(edit.getText().toString()))
                .show();
    }

    private boolean validInvitationUrl(Uri uri) {
        return isTrusted(uri) && "/portail-staff.html".equals(uri.getPath());
    }

    private void openInvitationUrl(String raw) {
        if (raw == null || raw.trim().length() > 4096) {
            Toast.makeText(this, "Lien d'invitation non valide", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri;
        try { uri = Uri.parse(raw.trim()); }
        catch (Exception e) { uri = null; }
        if (!validInvitationUrl(uri)) {
            Toast.makeText(this, "Lien non reconnu : vérifiez os.ichef.ch", Toast.LENGTH_LONG).show();
            return;
        }
        webView.loadUrl(uri.toString());
    }

    private void openIncomingLink(Intent intent) {
        Uri uri = intent != null ? intent.getData() : null;
        // Ne jamais logguer l'URL : le fragment peut contenir un jeton d'invitation.
        if (validInvitationUrl(uri)) webView.loadUrl(uri.toString());
        else webView.loadUrl(HOME);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri uri = intent != null ? intent.getData() : null;
        if (validInvitationUrl(uri)) webView.loadUrl(uri.toString());
    }

    private boolean mediaOriginTrusted(PermissionRequest req) {
        return req != null && isTrusted(req.getOrigin());
    }

    private void requestMediaPermission(PermissionRequest req) {
        if (!mediaOriginTrusted(req)) { req.deny(); return; }
        List<String> androidPermissions = new ArrayList<>();
        List<String> webResources = new ArrayList<>();
        for (String resource : req.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                androidPermissions.add(Manifest.permission.RECORD_AUDIO);
                webResources.add(resource);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                androidPermissions.add(Manifest.permission.CAMERA);
                webResources.add(resource);
            }
        }
        if (webResources.isEmpty()) { req.deny(); return; }
        List<String> missing = new ArrayList<>();
        for (String p : androidPermissions) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            req.grant(webResources.toArray(new String[0]));
        } else {
            if (pendingMediaRequest != null) pendingMediaRequest.deny();
            pendingMediaRequest = req;
            pendingMediaResources = webResources.toArray(new String[0]);
            requestPermissions(missing.toArray(new String[0]), MEDIA_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MEDIA_PERMISSIONS || pendingMediaRequest == null) return;
        boolean allGranted = true;
        for (int grant : grantResults) if (grant != PackageManager.PERMISSION_GRANTED) allGranted = false;
        if (allGranted && mediaOriginTrusted(pendingMediaRequest))
            pendingMediaRequest.grant(pendingMediaResources);
        else pendingMediaRequest.deny();
        pendingMediaRequest = null;
        pendingMediaResources = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (pendingMediaRequest != null) pendingMediaRequest.deny();
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}

