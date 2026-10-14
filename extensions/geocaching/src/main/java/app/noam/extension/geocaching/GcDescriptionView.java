package app.noam.extension.geocaching;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Looper;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.util.Base64;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native viewer for cache descriptions. Offline copies keep their images as data: URIs.
 * The app needs Android 10, so API 29 is always there.
 */
@RequiresApi(29)
public final class GcDescriptionView {
    private static final String BASE = "https://www.geocaching.com";
    private static final Pattern NOISE = Pattern.compile(
        "<!--.*?-->|<script\\b.*?</script>|<style\\b.*?</style>|<noscript\\b.*?</noscript>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLOCK_START = Pattern.compile(
        "<(table|img|form|button|input|iframe|textarea)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONCLICK_URL = Pattern.compile(
        "(?:window\\.open|location(?:\\.href)?\\s*=|location\\.assign)\\s*\\(?\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT_TAG = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR = Pattern.compile(
        "<a\\b[^>]*?\\bhref\\s*=\\s*(['\"])(.*?)\\1[^>]*>|</a\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile(
        "\\b([a-zA-Z-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern ALIGN_CENTER = Pattern.compile(
        "<(p|div|span|td|th|h[1-6])\\b([^>]*?)\\balign\\s*=\\s*['\"]?center['\"]?", Pattern.CASE_INSENSITIVE);

    private static final ExecutorService IMAGES = Executors.newFixedThreadPool(3);
    private static final LruCache<String, byte[]> IMAGE_BYTES = new LruCache<String, byte[]>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    private GcDescriptionView() {}

    /** Called at the top of the description dialog's state handler; true when it drew the description. */
    public static boolean show(Object fragment, Object state) {
        if (state == null || !"y64".equals(state.getClass().getName())) return false;
        try {
            View root = (View) fragment.getClass().getMethod("getView").invoke(fragment);
            if (root == null) return false;
            String shortHtml = (String) field(state, "a");
            String longHtml = (String) field(state, "b");
            if (Looper.myLooper() != Looper.getMainLooper()) {
                root.post(() -> draw(root, shortHtml, longHtml));
            } else {
                draw(root, shortHtml, longHtml);
            }
            return true;
        } catch (Throwable t) {
            Utils.logError("Native description failed, using the app's own view", t);
            return false;
        }
    }

    private static void draw(View root, String shortHtml, String longHtml) {
        Context context = root.getContext();
        for (String gone : new String[]{"initial_loading_view_description", "offline_full_screen",
            "tab_layout_description", "image_text_cta_view_description_error"}) {
            View v = root.findViewById(id(context, gone));
            if (v != null) v.setVisibility(View.GONE);
        }

        int pagerId = id(context, "view_pager_description_types");
        View slot = root.findViewById(pagerId);
        if (slot == null) return;
        ScrollView scroll;
        if (slot instanceof ScrollView) {
            scroll = (ScrollView) slot;
            scroll.removeAllViews();
        } else {
            ViewGroup parent = (ViewGroup) slot.getParent();
            int index = parent.indexOfChild(slot);
            ViewGroup.LayoutParams params = slot.getLayoutParams();
            parent.removeView(slot);
            scroll = new ScrollView(context);
            scroll.setId(pagerId);
            scroll.setBackgroundColor(color(context, "background_page", Color.WHITE));
            scroll.setFillViewport(true);
            parent.addView(scroll, index, params);
        }
        scroll.setVisibility(View.VISIBLE);

        Style style = new Style(context);
        LinearLayout content = vertical(context);
        int pad = Utils.dp(16);
        content.setPadding(pad, pad, pad, Utils.dp(48));
        style.maxWidth = root.getWidth() > 0 ? root.getWidth() - 2 * pad : context.getResources().getDisplayMetrics().widthPixels - 2 * pad;

        String s = shortHtml == null ? "" : shortHtml.trim();
        String l = longHtml == null ? "" : longHtml.trim();
        if (!s.isEmpty()) render(s, content, style, null);
        if (!l.isEmpty() && !l.equals(s)) render(l, content, style, null);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Splits html into text runs, images and tables, appending a native view for each. */
    private static void render(String html, LinearLayout out, Style style, String inheritedHref) {
        html = prepare(html);
        String href = inheritedHref;
        int position = 0;
        Matcher block = BLOCK_START.matcher(html);
        while (block.find(position)) {
            String before = html.substring(position, block.start());
            addText(before, out, style);
            href = trackAnchor(before, href);
            String tag = block.group(1).toLowerCase(Locale.ROOT);
            int openEnd = html.indexOf('>', block.start());
            if (openEnd < 0) openEnd = html.length() - 1;
            String open = html.substring(block.start(), openEnd + 1);
            switch (tag) {
                case "img":
                    addImage(open, out, style, href);
                    position = openEnd + 1;
                    break;
                case "input":
                    addInput(open, out, style, href);
                    position = openEnd + 1;
                    break;
                case "table": {
                    int end = closingTag(html, block.start(), "table");
                    addTable(html.substring(block.start(), end), out, style, href);
                    position = end;
                    break;
                }
                default: {
                    int end = closingTag(html, block.start(), tag);
                    String inner = html.substring(openEnd + 1, Math.max(openEnd + 1, html.lastIndexOf('<', end - 1)));
                    if (tag.equals("form")) addForm(open, inner, out, style, href);
                    else if (tag.equals("button")) addButton(open, text(inner), out, style, href);
                    else if (tag.equals("iframe")) addEmbed(open, out, style);
                    else addTextField(open, inner, out, style);
                    position = end;
                    break;
                }
            }
        }
        addText(html.substring(position), out, style);
    }

    private static String prepare(String html) {
        html = NOISE.matcher(html).replaceAll("");
        html = html.replaceAll("(?i)<center\\b[^>]*>", "<div style=\"text-align:center\">").replaceAll("(?i)</center\\s*>", "</div>");
        return ALIGN_CENTER.matcher(html).replaceAll("<$1$2 style=\"text-align:center\"");
    }

    /** The href of the link still open at the end of a text run, so an image inside it stays a link. */
    private static String trackAnchor(String text, String href) {
        Matcher m = ANCHOR.matcher(text);
        while (m.find()) href = m.group(2) != null ? m.group(2) : null;
        return href;
    }

    private static int closingTag(String html, int start, String tag) {
        Pattern p = Pattern.compile("<(/?)" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        int depth = 0;
        int from = start;
        while (m.find(from)) {
            depth += m.group(1).isEmpty() ? 1 : -1;
            from = m.end();
            if (depth == 0) return m.end();
        }
        return html.length();
    }

    /** Inner html of each top-level element named one of tags, not descending into nested tables. */
    private static List<String[]> children(String html, String... tags) {
        List<String[]> result = new ArrayList<>();
        Pattern open = Pattern.compile("<(" + String.join("|", tags) + "|table)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
        Matcher m = open.matcher(html);
        int from = 0;
        while (m.find(from)) {
            String tag = m.group(1).toLowerCase(Locale.ROOT);
            int end = closingTag(html, m.start(), tag);
            if (!tag.equals("table")) {
                int innerEnd = html.lastIndexOf('<', end - 1);
                if (innerEnd < m.end()) innerEnd = end;
                result.add(new String[]{m.group(2), html.substring(m.end(), innerEnd)});
            }
            from = Math.max(end, m.end());
        }
        return result;
    }

    private static void addText(String html, LinearLayout out, Style style) {
        if (html.replaceAll("(?s)<[^>]*>", "").replace("&nbsp;", " ").trim().isEmpty()) return;
        Spanned parsed = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        SpannableStringBuilder text = new SpannableStringBuilder(parsed);
        while (text.length() > 0 && Character.isWhitespace(text.charAt(text.length() - 1))) text.delete(text.length() - 1, text.length());
        while (text.length() > 0 && Character.isWhitespace(text.charAt(0))) text.delete(0, 1);
        if (text.length() == 0) return;
        fixColors(text, style);
        fixLinks(text);

        TextView view = new TextView(out.getContext());
        view.setText(text);
        view.setTextColor(style.text);
        view.setLinkTextColor(style.link);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setLineSpacing(0, 1.2f);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setMaxWidth(style.maxWidth);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Utils.dp(8);
        out.addView(view, params);
    }

    /** Drops background colors and any text color too close to the page background to read. */
    private static void fixColors(SpannableStringBuilder text, Style style) {
        for (BackgroundColorSpan span : text.getSpans(0, text.length(), BackgroundColorSpan.class)) text.removeSpan(span);
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) {
            if (contrast(span.getForegroundColor(), style.background) < 3.0) text.removeSpan(span);
        }
    }

    private static void fixLinks(SpannableStringBuilder text) {
        for (URLSpan span : text.getSpans(0, text.length(), URLSpan.class)) {
            int start = text.getSpanStart(span), end = text.getSpanEnd(span), flags = text.getSpanFlags(span);
            text.removeSpan(span);
            String target = absolute(span.getURL());
            text.setSpan(new URLSpan(target) {
                @Override
                public void onClick(View widget) {
                    open(widget.getContext(), target);
                }
            }, start, end, flags);
        }
    }

    private static void addImage(String tag, LinearLayout out, Style style, String href) {
        String src = attribute(tag, "src");
        if (src == null || src.isEmpty()) return;
        Context context = out.getContext();
        ImageView image = new ImageView(context);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        String alt = attribute(tag, "alt");
        if (alt != null) image.setContentDescription(alt);
        int declared = number(attribute(tag, "width"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 0);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = Utils.dp(8);
        out.addView(image, params);

        if (href != null && !href.trim().isEmpty()) {
            String target = absolute(href);
            image.setOnClickListener(v -> open(v.getContext(), target));
        }

        String url = src.startsWith("data:") ? src : absolute(src);
        IMAGES.execute(() -> {
            try {
                byte[] bytes = load(url);
                Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                    (decoder, info, source) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        int limit = Math.max(1, Math.min(style.maxWidth * 2, 2048));
                        if (w > limit) decoder.setTargetSize(limit, Math.max(1, h * limit / w));
                    });
                image.post(() -> {
                    float density = context.getResources().getDisplayMetrics().density;
                    int natural = drawable.getIntrinsicWidth();
                    int cssWidth = declared > 0 ? declared : natural;
                    int width = Math.min(style.maxWidth, Math.round(cssWidth * density));
                    if (width <= 0) width = style.maxWidth;
                    int height = Math.round(width * (float) drawable.getIntrinsicHeight() / Math.max(1, natural));
                    image.setImageDrawable(drawable);
                    params.width = width;
                    params.height = height;
                    image.setLayoutParams(params);
                    if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).start();
                });
            } catch (Throwable t) {
                Utils.log("Description image skipped: " + (url.startsWith("data:") ? "data uri" : url) + " (" + t + ")");
            }
        });
    }

    private static byte[] load(String url) throws Exception {
        byte[] cached = IMAGE_BYTES.get(url);
        if (cached != null) return cached;
        byte[] bytes;
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
        } else if (url.startsWith("http://")) {
            try {
                bytes = GcLiveSession.getImage("https://" + url.substring(7));
            } catch (Exception e) {
                bytes = GcLiveSession.getImage(url);
            }
        } else {
            bytes = GcLiveSession.getImage(url);
        }
        IMAGE_BYTES.put(url, bytes);
        return bytes;
    }

    /** A form: its fields render natively and its buttons submit to its action (GET, like a browser link). */
    private static void addForm(String open, String inner, LinearLayout out, Style style, String href) {
        Form form = new Form(attribute(open, "action"));
        Matcher input = INPUT_TAG.matcher(inner);
        while (input.find()) {
            String type = attribute(input.group(), "type");
            String name = attribute(input.group(), "name");
            if ("hidden".equalsIgnoreCase(type) && name != null) {
                String value = attribute(input.group(), "value");
                form.hidden.put(name, value == null ? "" : value);
            }
        }
        LinearLayout box = vertical(out.getContext());
        out.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        render(inner, box, style.withForm(form), href);
    }

    private static void addInput(String tag, LinearLayout out, Style style, String href) {
        String type = attribute(tag, "type");
        type = type == null ? "text" : type.toLowerCase(Locale.ROOT);
        switch (type) {
            case "hidden":
                return;
            case "submit":
            case "button":
            case "image": {
                String label = attribute(tag, "value");
                if (type.equals("image")) {
                    addImage("<img src=\"" + nullToEmpty(attribute(tag, "src")) + "\">", out, style,
                        style.form != null && style.form.action != null ? style.form.action : href);
                    return;
                }
                addButton(tag, label == null || label.isEmpty() ? "Submit" : label, out, style, href);
                return;
            }
            case "checkbox":
            case "radio":
                return;
            default:
                addTextField(tag, attribute(tag, "value"), out, style);
        }
    }

    private static void addTextField(String tag, String value, LinearLayout out, Style style) {
        android.widget.EditText field = new android.widget.EditText(out.getContext());
        field.setTextColor(style.text);
        field.setHintTextColor(style.border);
        field.setSingleLine(!tag.toLowerCase(Locale.ROOT).startsWith("<textarea"));
        String placeholder = attribute(tag, "placeholder");
        if (placeholder != null) field.setHint(placeholder);
        if (value != null && !value.isEmpty()) field.setText(text(value));
        String name = attribute(tag, "name");
        if (style.form != null && name != null) style.form.fields.put(name, field);
        out.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Buttons open what they would in a browser: their form, their onclick URL, or the link around them. */
    private static void addButton(String tag, String label, LinearLayout out, Style style, String href) {
        String target = null;
        String onclick = attribute(tag, "onclick");
        if (onclick != null) {
            Matcher url = ONCLICK_URL.matcher(onclick);
            if (url.find()) target = absolute(url.group(1));
        }
        String formaction = attribute(tag, "formaction");
        Form form = style.form;
        String direct = target != null ? target : formaction != null ? absolute(formaction)
            : href != null && !href.trim().isEmpty() ? absolute(href) : null;
        if (direct == null && form == null) return;

        android.widget.Button button = new android.widget.Button(out.getContext());
        button.setText(label.trim().isEmpty() ? "Open" : label.trim());
        button.setAllCaps(false);
        button.setOnClickListener(v -> open(v.getContext(), direct != null ? direct : form.url()));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = Utils.dp(8);
        out.addView(button, params);
    }

    /** Embedded players and widgets (YouTube, checkers, maps) open in their own app or the browser. */
    private static void addEmbed(String tag, LinearLayout out, Style style) {
        String src = attribute(tag, "src");
        if (src == null || src.trim().isEmpty()) return;
        String url = absolute(src);
        String host = Uri.parse(url).getHost();
        addButton("<button>", "Open embedded content" + (host == null ? "" : " (" + host + ")"), out, style, url);
    }

    private static String text(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static final class Form {
        final String action;
        final java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        final java.util.Map<String, android.widget.EditText> fields = new java.util.LinkedHashMap<>();

        Form(String action) {
            this.action = action == null || action.trim().isEmpty() ? null : absolute(action);
        }

        String url() {
            Uri.Builder builder = Uri.parse(action == null ? BASE : action).buildUpon();
            for (java.util.Map.Entry<String, String> e : hidden.entrySet()) builder.appendQueryParameter(e.getKey(), e.getValue());
            for (java.util.Map.Entry<String, android.widget.EditText> e : fields.entrySet()) {
                builder.appendQueryParameter(e.getKey(), String.valueOf(e.getValue().getText()));
            }
            return builder.build().toString();
        }
    }

    private static void addTable(String html, LinearLayout out, Style style, String href) {
        Context context = out.getContext();
        String tableTag = html.substring(0, html.indexOf('>') + 1);
        String body = html.substring(tableTag.length(), Math.max(tableTag.length(), html.lastIndexOf('<')));
        List<String[]> rows = children(body, "tr");
        if (rows.isEmpty()) {
            render(body, out, style, href);
            return;
        }
        List<List<String[]>> cells = new ArrayList<>();
        int columns = 0;
        for (String[] row : rows) {
            List<String[]> rowCells = children(row[1], "td", "th");
            cells.add(rowCells);
            columns = Math.max(columns, rowCells.size());
        }

        if (columns <= 1) {
            // Layout-only tables (one column) read best as plain blocks.
            for (List<String[]> row : cells) for (String[] cell : row) render(cell[1], out, style, href);
            return;
        }

        boolean bordered = number(attribute(tableTag, "border")) > 0;
        Style cellStyle = style.child((int) (style.maxWidth * 0.85f));
        TableLayout table = new TableLayout(context);
        for (List<String[]> row : cells) {
            TableRow tableRow = new TableRow(context);
            for (String[] cell : row) {
                LinearLayout cellView = vertical(context);
                int pad = Utils.dp(6);
                cellView.setPadding(pad, pad, pad, pad);
                if (bordered) {
                    GradientDrawable border = new GradientDrawable();
                    border.setStroke(Math.max(1, Utils.dp(1) / 2), style.border);
                    cellView.setBackground(border);
                }
                String cellHtml = cell[1];
                if (cell[0] != null && cell[0].toLowerCase(Locale.ROOT).contains("center")) {
                    cellHtml = "<div style=\"text-align:center\">" + cellHtml + "</div>";
                }
                render(cellHtml, cellView, cellStyle, href);
                int span = Math.max(1, number(attribute("<td " + cell[0] + ">", "colspan")));
                TableRow.LayoutParams lp = new TableRow.LayoutParams();
                lp.span = span;
                tableRow.addView(cellView, lp);
            }
            table.addView(tableRow);
        }
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.addView(table);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Utils.dp(8);
        out.addView(scroller, params);
    }

    /** geocaching.com links stay in the app; other links open in an in-app page when that feature is on. */
    private static void open(Context context, String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("coord.info") || host.endsWith("geocaching.com")) {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri).setPackage(context.getPackageName());
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    context.startActivity(intent);
                    return;
                }
            }
            if (url.startsWith("http") && GcFeatures.linksInApp()) {
                GcWebDialog.show(context, url);
            } else {
                context.startActivity(new Intent(Intent.ACTION_VIEW, uri));
            }
        } catch (Throwable t) {
            Utils.logError("Could not open " + url, t);
        }
    }

    private static String absolute(String url) {
        url = url.trim().replace("&amp;", "&");
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return BASE + url;
        if (!url.contains(":")) return BASE + "/" + url;
        return url;
    }

    private static String attribute(String tag, String name) {
        Matcher m = ATTRIBUTE.matcher(tag);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(name)) {
                String v = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
                return v == null ? null : Html.fromHtml(v, Html.FROM_HTML_MODE_LEGACY).toString();
            }
        }
        return null;
    }

    private static int number(String value) {
        if (value == null) return 0;
        Matcher m = Pattern.compile("\\d+").matcher(value);
        if (!m.find() || value.trim().endsWith("%")) return 0;
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int color) {
        double[] c = {Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static LinearLayout vertical(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static int id(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }

    private static int color(Context context, String name, int fallback) {
        Resources res = context.getResources();
        int id = res.getIdentifier(name, "color", context.getPackageName());
        return id == 0 ? fallback : res.getColor(id, context.getTheme());
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static final class Style {
        final int text, link, background, border;
        int maxWidth;
        Form form;

        Style(Context context) {
            background = color(context, "background_page", Color.WHITE);
            text = color(context, "foreground_neutral_primary", Color.BLACK);
            link = color(context, "foreground_info_primary", 0xFF1E88E5);
            border = (text & 0x00FFFFFF) | 0x55000000;
        }

        private Style(Style parent, int maxWidth) {
            text = parent.text;
            link = parent.link;
            background = parent.background;
            border = parent.border;
            form = parent.form;
            this.maxWidth = maxWidth;
        }

        Style child(int width) {
            return new Style(this, width);
        }

        Style withForm(Form form) {
            Style copy = new Style(this, maxWidth);
            copy.form = form;
            return copy;
        }
    }
}
