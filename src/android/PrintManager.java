package de.appplant.cordova.plugin.printer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintJob;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.print.PrintHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;

import static android.content.Context.PRINT_SERVICE;
import static android.print.PrintJobInfo.STATE_COMPLETED;
import static de.appplant.cordova.plugin.printer.PrintContent.ContentType.UNSUPPORTED;

class PrintManager {
    private final @NonNull Context context;
    private @Nullable WebView view;

    PrintManager(@NonNull Context context) {
        this.context = context;
    }

    boolean canPrintItem(@Nullable String item) {
        boolean supported = PrintHelper.systemSupportsPrint();

        if (item != null) {
            supported = PrintContent.getContentType(item, context) != UNSUPPORTED;
        }

        return supported;
    }

    /**
     * List of all printable document types (utis).
     */
    @NonNull
    static JSONArray getPrintableTypes()
    {
        JSONArray utis = new JSONArray();

        utis.put("com.adobe.pdf");
        utis.put("com.microsoft.bmp");
        utis.put("public.jpeg");
        utis.put("public.jpeg-2000");
        utis.put("public.png");
        utis.put("public.heif");
        utis.put("com.compuserve.gif");
        utis.put("com.microsoft.ico");

        return utis;
    }

    void print(@Nullable String content, @NonNull JSONObject settings,
               @NonNull WebView view, @NonNull OnPrintFinishCallback callback) {
        switch (PrintContent.getContentType(content, context)) {
            case IMAGE:
                assert content != null;
                printImage(content, settings, callback);
                break;
            case PDF:
                assert content != null;
                printPdf(content, settings, callback);
                break;
            case HTML:
                if (content == null || content.isEmpty()) {
                    printWebView(view, settings, callback);
                } else {
                    printHtml(content, settings, callback);
                }
                break;
            case UNSUPPORTED:
                // Handle unsupported content
                break;
            case PLAIN:
                printText(content, settings, callback);
        }
    }

    private void printHtml(@Nullable String content,
                           @NonNull JSONObject settings,
                           @NonNull OnPrintFinishCallback callback) {
        printContent(content, "text/html", settings, callback);
    }

    private void printText(@Nullable String content,
                           @NonNull JSONObject settings,
                           @NonNull OnPrintFinishCallback callback) {
        printContent(content, "text/plain", settings, callback);
    }

    private void printContent(@Nullable String content, @NonNull String mimeType,
                              @NonNull JSONObject settings,
                              @NonNull OnPrintFinishCallback callback) {
        ((Activity) context).runOnUiThread(() -> {
            view = createWebView(settings);

            view.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return false;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    printWebView(PrintManager.this.view, settings, callback);
                    PrintManager.this.view = null;
                }
            });

            assert content != null;
            view.loadDataWithBaseURL("file:///android_asset/www/", content, mimeType, "UTF-8", null);
        });
    }

    private void printWebView(@NonNull WebView view,
                              @NonNull JSONObject settings,
                              @NonNull OnPrintFinishCallback callback) {
        PrintOptions options = new PrintOptions(settings);
        String jobName = options.getJobName();

        ((Activity) context).runOnUiThread(() -> {
            PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(jobName);
            PrintProxy proxy = new PrintProxy(adapter, () -> callback.onFinish(isPrintJobCompleted(jobName)));
            printAdapter(proxy, options);
        });
    }

    private void printPdf(@NonNull String path, @NonNull JSONObject settings,
                          @NonNull OnPrintFinishCallback callback) {
        InputStream stream = PrintContent.open(path, context);
        if (stream == null) return;

        PrintOptions options = new PrintOptions(settings);
        String jobName = options.getJobName();
        int pageCount = options.getPageCount();
        PrintAdapter adapter = new PrintAdapter(jobName, pageCount, stream, () -> callback.onFinish(isPrintJobCompleted(jobName)));
        printAdapter(adapter, options);
    }

    private void printAdapter(@NonNull PrintDocumentAdapter adapter,
                              @NonNull PrintOptions options) {
        String jobName = options.getJobName();
        PrintAttributes attrs = options.toPrintAttributes();
        getPrintManager().print(jobName, adapter, attrs);
    }

    private void printImage(@NonNull String path, @NonNull JSONObject settings,
                            @NonNull OnPrintFinishCallback callback) {
        Bitmap bitmap = PrintContent.decode(path, context);
        if (bitmap == null) return;

        PrintOptions options = new PrintOptions(settings);
        PrintHelper printer = new PrintHelper(context);
        String jobName = options.getJobName();
        options.decoratePrintHelper(printer);
        printer.printBitmap(jobName, bitmap, () -> callback.onFinish(isPrintJobCompleted(jobName)));
    }

    @NonNull
    private WebView createWebView(@NonNull JSONObject settings) {
        boolean jsEnabled = settings.optBoolean("javascript", false);
        WebView view = new WebView(context);
        WebSettings spec = view.getSettings();
        JSONObject font = settings.optJSONObject("font");

        spec.setDatabaseEnabled(true);
        spec.setGeolocationEnabled(true);
        spec.setSaveFormData(true);
        spec.setUseWideViewPort(true);
        spec.setJavaScriptEnabled(jsEnabled);

        if (font != null && font.has("size")) {
            spec.setDefaultFixedFontSize(font.optInt("size", 16));
        }

        spec.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);

        return view;
    }

    /**
     * Finds the print job by its name.
     *
     * @param jobName The name of the print job.
     *
     * @return null if it could not find any job with this label.
     */
    @Nullable
    private PrintJob findPrintJobByName (@NonNull String jobName)
    {
        for (PrintJob job : getPrintManager().getPrintJobs()) {
            if (job.getInfo().getLabel().equals(jobName)) {
                return job;
            }
        }

        return null;
    }

    private boolean isPrintJobCompleted(@NonNull String jobName) {
        PrintJob job = findPrintJobByName(jobName);
        return (job == null || job.getInfo().getState() <= STATE_COMPLETED);
    }

    @SuppressLint("ServiceCast")
    @NonNull
    private android.print.PrintManager getPrintManager() {
        return (android.print.PrintManager) context.getSystemService(PRINT_SERVICE);
    }

    public interface OnPrintFinishCallback {
        void onFinish(boolean completed);
    }
}