package client.confis;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.class_124;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_332;
import net.minecraft.class_437;

/**
 * Black-and-white module hub. Everything is drawn immediate-mode with the vanilla Font (so
 * resource-pack fonts apply). Mouse and typing are polled from GLFW, which keeps this independent
 * of Screen's input-method signatures that change between Minecraft versions.
 */
public class ConfigScreen extends class_437 {
    private enum Tab {
        ALL("ALL"), INFO("INFO"), HUD("HUD"), MAP("MAP"), FAVORITES("FAVORITES"),
        WAYPOINTS("WAYPOINTS"), SETTINGS("SETTINGS");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private enum Mode { MENU, OPTIONS, EDIT }

    private static final int[] PRESETS = {0xFFFFFF, 0x000000, 0xFF5555, 0xFFAA00, 0xFFFF55,
            0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF};

    /** Fill passed to button() for "normal" and "active/accent" pills (Glass picks the themed style from it). */
    private static final int BTN = 0xFF1E1E1E;
    private static final int ACTIVE = 0xFFFFFFFF;
    private static final int BTN_TXT = 0;        // text color is chosen by the theme; kept so call sites stay unchanged
    private static final int COLOR_EDITOR_H = 11 + 14 + 33 + 13;

    private Tab tab = Tab.ALL;
    private Mode mode = Mode.MENU;
    private int page = 0;
    private int scroll = 0, maxScroll = 0;
    private float scrollShown = 0f;
    /** Y offset (from the panel top) where the content below the tab bar starts; 52 with one tab row, 70 with two. */
    private int top = 52;
    private boolean barDrag;
    private int settingsPage;                 // 0 = general, 1 = appearance

    // filtered module list: rebuilt only when the tab, the search text or the favorites change
    private final List<Module> shown = new ArrayList<>();
    private Tab shownTab;
    private String shownQuery;
    private int shownFavVer = -1, favVersion;
    // card-title wrapping cache (recomputed only when the available width changes)
    private int nameAvail = -1;
    private final java.util.HashMap<String, String[]> nameLines = new java.util.HashMap<>();
    private Module optMod;

    // search box
    private String query = "";
    private boolean searchFocus;
    private final boolean[] keyWas = new boolean[GLFW.GLFW_KEY_LAST + 1];

    // mouse state (polled)
    private int mx, my;
    private boolean down, wasDown, clicked;
    private String activeSlider;

    // hud dragging
    private Module dragging;
    private int dragOffX, dragOffY;

    // panel geometry
    private int px, py, pw, ph;

    // pop-up animation when the menu opens
    private long openedAt = System.nanoTime();
    private boolean animating;

    public ConfigScreen() {
        super(class_2561.method_43470(ConfisClient.NAME));
    }

    @Override
    public boolean method_25421() {
        return false;
    }

    @Override
    public void method_25432() {
        Config.save();
    }

    // ------------------------------------------------------------------ input
    private void pollMouse() {
        long win = GLFW.glfwGetCurrentContext();
        boolean d = win != 0L && GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        clicked = d && !wasDown;
        boolean released = !d && wasDown;
        down = d;
        wasDown = d;
        if (released) {
            activeSlider = null;
            Config.save();
        }
    }

    /** Typing for the search box: edge-detects letter / digit / space / backspace keys. */
    private void pollTyping() {
        long win = GLFW.glfwGetCurrentContext();
        if (win == 0L) return;
        for (int k = GLFW.GLFW_KEY_A; k <= GLFW.GLFW_KEY_Z; k++) {
            boolean d = GLFW.glfwGetKey(win, k) == GLFW.GLFW_PRESS;
            if (d && !keyWas[k] && searchFocus && query.length() < 16) query += (char) ('a' + (k - GLFW.GLFW_KEY_A));
            keyWas[k] = d;
        }
        for (int k = GLFW.GLFW_KEY_0; k <= GLFW.GLFW_KEY_9; k++) {
            boolean d = GLFW.glfwGetKey(win, k) == GLFW.GLFW_PRESS;
            if (d && !keyWas[k] && searchFocus && query.length() < 16) query += (char) ('0' + (k - GLFW.GLFW_KEY_0));
            keyWas[k] = d;
        }
        boolean sp = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        if (sp && !keyWas[GLFW.GLFW_KEY_SPACE] && searchFocus && query.length() < 16) query += " ";
        keyWas[GLFW.GLFW_KEY_SPACE] = sp;
        boolean bs = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS;
        if (bs && !keyWas[GLFW.GLFW_KEY_BACKSPACE] && searchFocus && !query.isEmpty()) {
            query = query.substring(0, query.length() - 1);
            page = 0;
            scroll = 0;
        }
        keyWas[GLFW.GLFW_KEY_BACKSPACE] = bs;
    }

    private boolean in(int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** True once if the mouse was clicked inside the rectangle this frame. */
    private boolean clickIn(int x, int y, int w, int h) {
        if (clicked && in(x, y, w, h)) {
            clicked = false;
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- colors
    private static int lighten(int c, int amt) {
        int a = c >>> 24;
        int r = Math.min(255, ((c >> 16) & 255) + amt);
        int g = Math.min(255, ((c >> 8) & 255) + amt);
        int b = Math.min(255, (c & 255) + amt);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int darken(int c, int amt) {
        int a = c >>> 24;
        int r = Math.max(0, ((c >> 16) & 255) - amt);
        int g = Math.max(0, ((c >> 8) & 255) - amt);
        int b = Math.max(0, (c & 255) - amt);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int opaque(int c) {
        return 0xFF000000 | c;
    }

    /** Black or white, whichever reads better on top of color c. */
    private static int contrast(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return (r * 299 + g * 587 + b * 114) / 1000 > 140 ? 0xFF000000 : 0xFFFFFFFF;
    }

    // --------------------------------------------------------------- widgets
    private boolean button(class_332 g, int x, int y, int w, int h, String label, int fill, int textCol) {
        boolean hov = in(x, y, w, h);
        int tc = Glass.button(g, x, y, w, h, fill, hov);
        g.method_25300(this.field_22793, label, x + w / 2, y + (h - 8) / 2, tc);
        return clickIn(x, y, w, h);
    }

    private float slider(class_332 g, String id, String tag, int x, int y, int w, int h, float v) {
        return slider(g, id, tag, x, y, w, h, v, true);
    }

    private float slider(class_332 g, String id, String tag, int x, int y, int w, int h, float v, boolean enabled) {
        if (enabled && clicked && in(x, y, w, h)) {
            activeSlider = id;
            clicked = false;
        }
        if (enabled && down && id.equals(activeSlider)) {
            v = Math.max(0f, Math.min(1f, (mx - x) / (float) w));
        }
        Glass.fillRound(g, x, y, w, h, 4, enabled ? Glass.rimArgb(0x30) : Glass.rimArgb(0x16));
        int fw = Math.round(w * v);
        if (fw > 0) Glass.fillRound(g, x, y, Math.max(fw, 3), h, 4, Glass.rimArgb(enabled ? 0x78 : 0x34));
        int kx = x + Math.round((w - 3) * v);
        Glass.fillRound(g, kx, y - 1, 3, h + 2, 2, enabled ? 0xFFFFFFFF : 0x80FFFFFF);
        g.method_51433(this.field_22793, tag, x + 4, y + 1, enabled ? Theme.text : Theme.dim, !Theme.darkText);
        return v;
    }

    /** Swatch + RGB(A) sliders + quick presets. Returns the (possibly changed) ARGB color. */
    private int colorEditor(class_332 g, String id, int x, int y, int w, String label, int argb, boolean alpha) {
        g.method_51433(this.field_22793, label, x, y, Theme.text, !Theme.darkText);
        y += 11;
        int a = alpha ? (argb >>> 24) : 255;
        int r = (argb >> 16) & 255, gr = (argb >> 8) & 255, b = argb & 255;

        g.method_25294(x - 1, y - 1, x + 13, y + 11, 0xFFFFFFFF);
        if (alpha) {                                    // checkerboard so the transparency of the color is visible
            for (int cy = 0; cy < 10; cy += 5)
                for (int cx = 0; cx < 12; cx += 4)
                    g.method_25294(x + cx, y + cy, Math.min(x + cx + 4, x + 12), Math.min(y + cy + 5, y + 10),
                            (((cx / 4) + (cy / 5)) & 1) == 0 ? 0xFFC8C8C8 : 0xFF787878);
            g.method_25294(x, y, x + 12, y + 10, (a << 24) | (argb & 0xFFFFFF));
        } else {
            g.method_25294(x, y, x + 12, y + 10, opaque(argb));
        }
        String hex = String.format("#%02X%02X%02X", r, gr, b);
        g.method_51433(this.field_22793, hex, x + 18, y + 1, Theme.dim, !Theme.darkText);
        y += 14;

        r = Math.round(slider(g, id + "r", "R", x, y, w, 9, r / 255f) * 255);
        y += 11;
        gr = Math.round(slider(g, id + "g", "G", x, y, w, 9, gr / 255f) * 255);
        y += 11;
        b = Math.round(slider(g, id + "b", "B", x, y, w, 9, b / 255f) * 255);
        y += 11;
        if (alpha) {
            int pct = Math.round(a / 2.55f);                       // opacity in whole percent: 0% = clear, 100% = solid
            int np = Math.round(slider(g, id + "a", "Opacity: " + pct + "%", x, y, w, 9, pct / 100f) * 100);
            if (np != pct) a = Math.round(np * 2.55f);
            y += 11;
        }

        int sw = (w - 8 * 2) / PRESETS.length;
        for (int i = 0; i < PRESETS.length; i++) {
            int sx = x + i * (sw + 2);
            g.method_25294(sx - 1, y - 1, sx + sw + 1, y + 11, in(sx, y, sw, 10) ? 0xFFFFFFFF : 0xFF444444);
            g.method_25294(sx, y, sx + sw, y + 10, opaque(PRESETS[i]));
            if (clickIn(sx, y, sw, 10)) {
                r = (PRESETS[i] >> 16) & 255;
                gr = (PRESETS[i] >> 8) & 255;
                b = PRESETS[i] & 255;
            }
        }
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }

    private void outline(class_332 g, int x, int y, int w, int h, int col) {
        g.method_25294(x, y, x + w, y + 1, col);
        g.method_25294(x, y + h - 1, x + w, y + h, col);
        g.method_25294(x, y, x + 1, y + h, col);
        g.method_25294(x + w - 1, y, x + w, y + h, col);
    }

    // ---------------------------------------------------------------- render
    /** No blur and no dimming at all: the world stays sharp behind the glass and no full-screen effect runs. */
    @Override
    public void method_25420(class_332 g, int mouseX, int mouseY, float delta) {
        // intentionally empty
    }

    /** The menu pops up: it grows from 88% to full size and fades in over about 0.2 seconds. */
    @Override
    public void method_25394(class_332 g, int mouseX, int mouseY, float delta) {
        float t = Math.min(1f, (System.nanoTime() - openedAt) / 220_000_000f);
        Theme.ensure();
        animating = Theme.animations && mode != Mode.EDIT && t < 1f;
        if (!animating) {
            renderMenu(g, mouseX, mouseY, delta);
            return;
        }
        float e = 1f - (float) Math.pow(1f - t, 3);
        float s = 0.88f + 0.12f * e;
        float cx = this.field_22789 / 2f, cy = this.field_22790 / 2f;
        g.method_51448().pushMatrix();
        g.method_51448().translate(cx, cy);
        g.method_51448().scale(s, s);
        g.method_51448().translate(-cx, -cy);
        try {
            renderMenu(g, mouseX, mouseY, delta);
        } finally {
            g.method_51448().popMatrix();
        }
    }

    private void renderMenu(class_332 g, int mouseX, int mouseY, float delta) {
        mx = mouseX;
        my = mouseY;
        pollMouse();
        if (animating) clicked = false;     // ignore clicks while the menu is still growing
        pollTyping();
        if (rebinding) pollRebind();        // scanning ~350 keys every frame is wasted work otherwise

        if (mode == Mode.EDIT) {
            renderEdit(g);
            return;
        }
        super.method_25394(g, mouseX, mouseY, delta);

        Config cfg = Config.get();
        pw = Math.min(480, this.field_22789 - 16);
        ph = Math.min(284, this.field_22790 - 16);
        px = (this.field_22789 - pw) / 2;
        py = (this.field_22790 - ph) / 2;
        int accent = opaque(cfg.accent);

        // liquid glass window: translucent, so the blurred game shows through
        Glass.panel(g, px, py, pw, ph);
        g.method_25294(px + 12, py + 24, px + pw - 12, py + 25, Glass.rimArgb(0x40));

        // name on top of the menu
        class_2561 title = class_2561.method_43470(ConfisClient.NAME).method_27692(class_124.field_1067);
        g.method_51439(this.field_22793, title, px + 8, py + 8, Theme.text, !Theme.darkText);
        int titleW = this.field_22793.method_27525(title);

        // close button
        if (button(g, px + pw - 20, py + 5, 14, 14, "X", BTN, BTN_TXT)) {
            this.method_25419();
            return;
        }

        if (mode == Mode.OPTIONS) {
            if (button(g, px + pw - 62, py + 5, 38, 14, "BACK", BTN, BTN_TXT)) {
                mode = Mode.MENU;
                optMod = null;
                return;
            }
            g.method_51433(this.field_22793, (optMod == null ? "" : optMod.name.toUpperCase(Locale.ROOT)) + " OPTIONS",
                    px + 8 + titleW + 14, py + 8, Theme.dim, !Theme.darkText);
            renderOptions(g, cfg);
            return;
        }

        // header: current page name, search box, edit-hud button (adapts to narrow windows instead of overlapping)
        int titleEnd = px + 8 + titleW + 14;
        String pageName = tab == Tab.ALL ? "ALL MODULES" : tab.label;
        if (button(g, px + pw - 20 - 4 - 56, py + 5, 56, 14, "EDIT HUD", ACTIVE, 0)) {
            mode = Mode.EDIT;
            dragging = null;
            return;
        }
        int toolsX = px + pw - 20 - 4 - 56 - 6 - 46;
        if (button(g, toolsX, py + 5, 46, 14, "TOOLS", BTN, BTN_TXT)) {
            class_310.method_1551().method_1507(new ToolsScreen(this));
            return;
        }
        int sbW = Math.min(110, toolsX - 6 - (px + 8 + titleW + 8));
        int sbX = toolsX - 6 - sbW;
        boolean showName = sbX - 6 >= titleEnd + this.field_22793.method_1727(pageName);
        if (showName) g.method_51433(this.field_22793, pageName, titleEnd, py + 8, Theme.text, !Theme.darkText);
        if (tab.ordinal() <= Tab.FAVORITES.ordinal() && sbW >= 36) {
            if (clicked) searchFocus = in(sbX, py + 5, sbW, 14);
            Glass.field(g, sbX, py + 5, sbW, 14, searchFocus, accent);
            boolean caret = searchFocus && (System.currentTimeMillis() / 500) % 2 == 0;
            String shownText = query.isEmpty() && !searchFocus ? "Search modules..." : (caret ? query + "_" : query);
            g.method_51433(this.field_22793, fitText(shownText, sbW - 8), sbX + 4, py + 8, query.isEmpty() && !searchFocus ? Theme.dim : Theme.text, !Theme.darkText);
        } else {
            searchFocus = false;
        }

        // tab bar: one row when every label fits, otherwise two rows (4 + 3) so labels never overlap
        int tabGap = 3;
        int nTabs = Tab.values().length;
        int need = 0;
        for (Tab t : Tab.values()) need = Math.max(need, this.field_22793.method_1727(t.label) + 4);
        int perRow = (pw - 16 - tabGap * (nTabs - 1)) / nTabs >= need ? nTabs : 4;
        int rows = (nTabs + perRow - 1) / perRow;
        top = 29 + rows * 19 + 4;
        for (int i = 0; i < nTabs; i++) {
            Tab t = Tab.values()[i];
            boolean active = t == tab;
            int rowN = i / perRow, colN = i % perRow;
            int inRow = Math.min(perRow, nTabs - rowN * perRow);
            int tabW = (pw - 16 - tabGap * (inRow - 1)) / inRow;
            int x = px + 8 + colN * (tabW + tabGap);
            int tabY = py + 29 + rowN * 19;
            if (button(g, x, tabY, tabW, 16, t.label, active ? ACTIVE : BTN, 0)) {
                if (tab != t) playClick();
                tab = t;
                page = 0;
                scroll = 0;
                scrollShown = 0f;
            }
        }

        switch (tab) {
            case WAYPOINTS -> renderWaypoints(g, cfg);
            case SETTINGS -> renderSettings(g, cfg);
            default -> renderMods(g, cfg);
        }
    }

    /** Vanilla button click sound (used by the category tabs). */
    private static void playClick() {
        // The factory method for UI sounds was renamed between Minecraft versions (master -> ui ...), so it is found by
        // its signature (static, (sound, float) -> SoundInstance) instead of by name. A missing sound never crashes.
        try {
            Object ev = net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK;
            for (java.lang.reflect.Method m : net.minecraft.client.sound.PositionedSoundInstance.class.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) || p.length != 2 || p[1] != float.class) continue;
                if (!net.minecraft.client.sound.SoundInstance.class.isAssignableFrom(m.getReturnType())) continue;
                Object arg = p[0].isInstance(ev) ? ev
                        : p[0] == net.minecraft.sound.SoundEvent.class ? ((net.minecraft.registry.entry.RegistryEntry<?>) ev).value() : null;
                if (arg == null) continue;
                net.minecraft.client.MinecraftClient.getInstance().getSoundManager()
                        .play((net.minecraft.client.sound.SoundInstance) m.invoke(null, arg, 1.0F));
                return;
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- mods grid
    private static final int CARD_H = 74, CARD_H_NO_ICON = 44, CARD_GAP = 4, MIN_CARD_W = 80;

    /** Word-wraps / ellipsizes a card title to at most two lines. Cached per available width. */
    private String[] titleLines(String name, int avail) {
        if (avail != nameAvail) {
            nameLines.clear();
            nameAvail = avail;
        }
        String[] r = nameLines.get(name);
        if (r == null) {
            r = wrapTitle(name, avail);
            nameLines.put(name, r);
        }
        return r;
    }

    private String fitText(String s, int avail) {
        if (this.field_22793.method_1727(s) <= avail) return s;
        while (s.length() > 1 && this.field_22793.method_1727(s + "...") > avail) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private String[] wrapTitle(String name, int avail) {
        if (this.field_22793.method_1727(name) <= avail) return new String[]{name};
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) != ' ') continue;
            String a = name.substring(0, i);
            if (this.field_22793.method_1727(a) <= avail) {
                // prefer the LAST space that still fits on line one
                int best = i;
                for (int j = i + 1; j < name.length(); j++) {
                    if (name.charAt(j) == ' ' && this.field_22793.method_1727(name.substring(0, j)) <= avail) best = j;
                }
                return new String[]{name.substring(0, best), fitText(name.substring(best + 1), avail)};
            }
            break;
        }
        return new String[]{fitText(name, avail)};
    }

    /** Rebuilds the filtered module list only if the tab, search text or favorites changed. */
    private List<Module> filtered() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (tab == shownTab && q.equals(shownQuery) && favVersion == shownFavVer) return shown;
        shown.clear();
        for (Module m : Modules.ALL) {
            boolean ok = switch (tab) {
                case INFO -> m.category.equals("INFO");
                case HUD -> m.category.equals("HUD");
                case MAP -> m.category.equals("MAP");
                case FAVORITES -> m.cfg().favorite;
                default -> true;
            };
            if (ok && (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q))) shown.add(m);
        }
        shownTab = tab;
        shownQuery = q;
        shownFavVer = favVersion;
        return shown;
    }

    private void renderMods(class_332 g, Config cfg) {
        int gx = px + 8;
        int gw = pw - 16;
        int gy = py + top;

        List<Module> list = filtered();

        // as many columns as fit (max 5): narrow windows get fewer columns instead of squeezed cards
        int cols = Math.max(1, Math.min(5, (gw + CARD_GAP) / (MIN_CARD_W + CARD_GAP)));
        int gap = CARD_GAP;
        int cw = (gw - (cols - 1) * gap) / cols;
        boolean icons = Config.get().showModuleIcons;
        int chh = icons ? CARD_H : CARD_H_NO_ICON;   // icons hidden: cards shrink so there is no empty icon area
        int viewH = ph - top - 6;
        int totalRows = Math.max(1, (list.size() + cols - 1) / cols);
        int contentH = totalRows * (chh + gap);
        maxScroll = Math.max(0, contentH - viewH);
        if (scroll > maxScroll) scroll = maxScroll;
        if (scroll < 0) scroll = 0;

        // scrollbar geometry + dragging
        int barX = px + pw - 5;
        int kh = Math.max(16, viewH * viewH / Math.max(1, contentH));
        if (maxScroll > 0) {
            if (clicked && in(barX - 3, gy, 10, viewH)) { barDrag = true; clicked = false; }
            if (barDrag) {
                if (down) {
                    float f = (my - gy - kh / 2f) / Math.max(1, viewH - kh);
                    scroll = Math.max(0, Math.min(maxScroll, Math.round(f * maxScroll)));
                    scrollShown = scroll;
                } else {
                    barDrag = false;
                }
            }
        } else {
            barDrag = false;
        }
        // short ease toward the target scroll position (snaps when animations are off)
        if (Theme.animations && !barDrag) {
            float dlt = scroll - scrollShown;
            scrollShown = Math.abs(dlt) < 0.5f ? scroll : scrollShown + dlt * 0.4f;
        } else {
            scrollShown = scroll;
        }
        int sy = Math.round(scrollShown);

        if (list.isEmpty()) {
            g.method_51433(this.field_22793, tab == Tab.FAVORITES ? "No favorites yet. Click the heart on a module."
                    : "No modules match your search.", gx + 4, gy + 6, Theme.dim, !Theme.darkText);
        }

        boolean savedClicked = clicked;
        if (!in(gx, gy, gw, viewH)) clicked = false;
        g.method_44379(gx, gy, gx + gw, gy + viewH); // clip cards to the grid area
        int titleAvail = cw - 5 - 15;                // keeps long names clear of the favorite heart
        int first = Math.max(0, (sy - chh) / (chh + gap)) * cols;
        for (int n = first; n < list.size(); n++) {
            int y = gy + (n / cols) * (chh + gap) - sy;
            if (y > gy + viewH) break;               // rows below the view: nothing else to draw
            if (y + chh < gy) continue;              // rows above the view
            Module m = list.get(n);
            Config.ModuleCfg c = m.cfg();
            int x = gx + (n % cols) * (cw + gap);
            boolean on = c.enabled;
            boolean hov = in(x, y, cw, chh) && in(gx, gy, gw, viewH);

            Glass.card(g, x, y, cw, chh, hov, on);

            String[] lines = titleLines(m.name, titleAvail);
            for (int i = 0; i < lines.length; i++) {
                g.method_51433(this.field_22793, lines[i], x + 5, y + 4 + i * 9, Theme.text, !Theme.darkText);
            }

            // favorite heart (top-right)
            int hx = x + cw - 12, hy = y + 4;
            boolean fav = c.favorite;
            Icons.draw(g, "heart", hx, hy, 1, fav ? 0xFFFF4D6D : ((Theme.text & 0xFFFFFF) | 0x90000000));
            if (clickIn(hx - 2, hy - 2, 11, 10)) {
                c.favorite = !c.favorite;
                favVersion++;
                Config.save();
            }

            // module icon: 16x16 art drawn at 2x, pre-baked normal / disabled / hover palettes
            int state = on ? (hov ? Icons.HOVER : Icons.NORMAL) : (hov ? Icons.NORMAL : Icons.DISABLED);
            if (icons) Icons.drawIcon(g, m.id, x + (cw - 32) / 2, y + 22, 2, state);

            // gear (options) + enabled/disabled bar
            int by = y + chh - 16;
            boolean gearHov = in(x + 4, by, 14, 12);
            int gtc = Glass.button(g, x + 4, by, 14, 12, BTN, gearHov);
            Icons.draw(g, "gear", x + 7, by + 2, 1, gtc);
            if (clickIn(x + 4, by, 14, 12)) {
                optMod = m;
                mode = Mode.OPTIONS;
            }
            int tx2 = x + 21, tw = cw - 25;
            int ttc = Glass.toggle(g, tx2, by, tw, 12, on, in(tx2, by, tw, 12));
            g.method_25300(this.field_22793, on ? "Enabled" : "Disabled", tx2 + tw / 2, by + 2, ttc);
            if (clickIn(tx2, by, tw, 12)) {
                c.enabled = !c.enabled;
                Config.save();
            }
        }

        g.method_44380(); // end clip
        clicked = savedClicked && clicked;
        if (maxScroll > 0) {
            Glass.fillRound(g, barX, gy, 3, viewH, 2, Glass.rimArgb(0x30));
            int ky = gy + (viewH - kh) * sy / maxScroll;
            Glass.fillRound(g, barX, ky, 3, kh, 2, Glass.rimArgb(barDrag ? 0xE0 : 0xA0));
        }
    }

    @Override
    public boolean method_25401(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mode == Mode.EDIT) {
            Module hov = hoveredModule();
            if (hov != null) {
                selected = hov;
                changeScale(hov, vertical > 0 ? 0.1f : -0.1f);
                return true;
            }
        }
        if (mode == Mode.MENU && tab.ordinal() <= Tab.FAVORITES.ordinal()) {
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - vertical * 24));
            return true;
        }
        return super.method_25401(mouseX, mouseY, horizontal, vertical);
    }

    // ----------------------------------------------------------- options page
    private void renderOptions(class_332 g, Config cfg) {
        if (optMod == null) return;
        Module m = optMod;
        Config.ModuleCfg c = m.cfg();
        boolean border = m.hasBorder();
        int cols = 2 + (m.accentLabel != null ? 1 : 0) + (border ? 1 : 0);
        if (!m.colorOptions) {                       // switch-only module: just its own settings
            if (m.hint != null) g.method_51433(this.field_22793, m.hint, px + 8, py + 40, Theme.dim, !Theme.darkText);
            renderExtra(g, m, py + 60);
            return;
        }
        int colW = (pw - 16 - (cols - 1) * 12) / cols;
        int y = py + 36;
        int x = px + 8;

        c.bg = colorEditor(g, "o_bg", x, y, colW, m.bgLabel, c.bg, true);
        x += colW + 12;
        c.text = colorEditor(g, "o_tx", x, y, colW, m.textLabel, c.text, true);
        if (m.accentLabel != null) {
            x += colW + 12;
            c.accent = colorEditor(g, "o_ac", x, y, colW, m.accentLabel, c.accent, true);
        }
        if (border) {
            x += colW + 12;
            c.border = colorEditor(g, "o_bd", x, y, colW, "Border color", c.border, true);
        }

        int by = y + COLOR_EDITOR_H + 14;
        int bw = (pw - 16 - 8) / 2;
        if (m.bgToggle && button(g, px + 8, by, bw, 14, "Background: " + (c.showBg ? "ON" : "OFF"), BTN, BTN_TXT)) {
            c.showBg = !c.showBg;
            Config.save();
        }
        if (button(g, m.bgToggle ? px + 8 + bw + 8 : px + 8, by, bw, 14, "Reset colors", BTN, BTN_TXT)) {
            m.resetColors();
            Config.save();
        }
        if (m.movable()) {
            int sy = by + 22;
            g.method_51433(this.field_22793, "Scale: " + String.format("%.2fx", c.scale), px + 8, sy + 3, Theme.text, !Theme.darkText);
            if (button(g, px + 90, sy, 16, 14, "-", BTN, BTN_TXT)) { c.scale = Math.max(0.5f, Math.round((c.scale - 0.1f) * 10) / 10f); Config.save(); }
            if (button(g, px + 110, sy, 16, 14, "+", BTN, BTN_TXT)) { c.scale = Math.min(3.0f, Math.round((c.scale + 0.1f) * 10) / 10f); Config.save(); }
            if (button(g, px + 130, sy, 30, 14, "1.0x", BTN, BTN_TXT)) { c.scale = 1.0f; Config.save(); }
        }
        if (!m.movable()) {
            g.method_51433(this.field_22793, m.hint != null ? m.hint : m instanceof Modules.WaypointsModule ? "Colors apply to the in-world waypoint labels."
                    : m.accentLabel != null ? "This module draws on the crosshair, so it has no position or size."
                    : "No position to edit: just switch this module on or off.", px + 8, by + 22, Theme.dim, !Theme.darkText);
        }
        renderExtra(g, m, by + 44);
    }

    private boolean rebinding;
    private String rebindWhat = "zoom";
    private final boolean[] rebindWas = new boolean[GLFW.GLFW_KEY_LAST + 1];

    /** Waits for the next key press after "Zoom key" was clicked. Esc / Right Shift cancel. */
    private void pollRebind() {
        long win = GLFW.glfwGetCurrentContext();
        if (win == 0L) return;
        for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++) {
            boolean d = GLFW.glfwGetKey(win, k) == GLFW.GLFW_PRESS;
            if (d && !rebindWas[k] && rebinding) {
                if (k != GLFW.GLFW_KEY_ESCAPE && k != GLFW.GLFW_KEY_RIGHT_SHIFT) {
                    if ("freecam".equals(rebindWhat)) Config.get().freecamKey = k;
                    else Config.get().zoomKey = k;
                    Config.save();
                }
                rebinding = false;
            }
            rebindWas[k] = d;
        }
    }

    /** Extra options for modules that need more than colors and scale. */
    private void renderExtra(class_332 g, Module m, int y) {
        Config cfg = Config.get();
        int accent = opaque(cfg.accent);
        int x = px + 8;
        if (m.id.equals("zoom")) {
            String label = rebinding ? "Press a key...  (Esc = cancel)" : "Zoom key: " + Zoom.keyName(cfg.zoomKey) + "  (click to change)";
            boolean waiting = rebinding && "zoom".equals(rebindWhat);
            if (waiting) label = "Press a key...  (Esc = cancel)";
            else if (rebinding) label = "Zoom key: " + Zoom.keyName(cfg.zoomKey) + "  (click to change)";
            if (button(g, x, y, 190, 14, label, waiting ? ACTIVE : BTN, 0)) { rebinding = true; rebindWhat = "zoom"; }
            g.method_51433(this.field_22793, "Zoom: " + String.format("%.1fx", cfg.zoomFactor), x + 200, y + 3, Theme.text, !Theme.darkText);
            if (button(g, x + 262, y, 16, 14, "-", BTN, BTN_TXT)) { cfg.zoomFactor = Math.max(1.5f, Math.round((cfg.zoomFactor - 0.5f) * 2) / 2f); Config.save(); }
            if (button(g, x + 282, y, 16, 14, "+", BTN, BTN_TXT)) { cfg.zoomFactor = Math.min(10f, Math.round((cfg.zoomFactor + 0.5f) * 2) / 2f); Config.save(); }
            g.method_51433(this.field_22793, "Hold the key in game to zoom.", x, y + 20, Theme.dim, !Theme.darkText);
        } else if (m.id.equals("freecam")) {
            boolean waiting = rebinding && "freecam".equals(rebindWhat);
            String label = waiting ? "Press a key...  (Esc = cancel)" : "Freecam key: " + Zoom.keyName(cfg.freecamKey) + "  (click to change)";
            if (button(g, x, y, 190, 14, label, waiting ? ACTIVE : BTN, 0)) { rebinding = true; rebindWhat = "freecam"; }
            g.method_51433(this.field_22793, "Speed: " + String.format("%.1fx", cfg.freecamSpeed), x + 200, y + 3, Theme.text, !Theme.darkText);
            if (button(g, x + 262, y, 16, 14, "-", BTN, BTN_TXT)) { cfg.freecamSpeed = Math.max(0.5f, Math.round((cfg.freecamSpeed - 0.5f) * 2) / 2f); Config.save(); }
            if (button(g, x + 282, y, 16, 14, "+", BTN, BTN_TXT)) { cfg.freecamSpeed = Math.min(5f, Math.round((cfg.freecamSpeed + 0.5f) * 2) / 2f); Config.save(); }
            g.method_51433(this.field_22793, "Press the key in game to toggle. Fly: W A S D, Space, Shift (Ctrl = faster).", x, y + 20, Theme.dim, !Theme.darkText);
        } else if (m.id.equals("armor")) {
            Config.ModuleCfg ac = m.cfg();
            String mode = ac.armorMode == null ? "NUMBER" : ac.armorMode;
            String modeName = mode.equals("PERCENT") ? "Percentage" : mode.equals("BOTH") ? "Number + percentage" : "Number";
            if (button(g, x, y, 190, 14, "Durability: " + modeName + "  (click)", BTN, BTN_TXT)) {
                ac.armorMode = mode.equals("NUMBER") ? "PERCENT" : mode.equals("PERCENT") ? "BOTH" : "NUMBER";
                Config.save();
            }
            boolean left = "LEFT".equals(ac.armorSide);
            if (button(g, x, y + 18, 190, 14, "Text position: " + (left ? "Left of item" : "Right of item") + "  (click)", BTN, BTN_TXT)) {
                ac.armorSide = left ? "RIGHT" : "LEFT";
                Config.save();
            }
        } else if (m.id.equals("inventory")) {
            Config.ModuleCfg ic = m.cfg();
            if (button(g, x, y, 190, 14, "Quick Access Bar: " + (ic.quickBar ? "ON" : "OFF") + "  (click)",
                    ic.quickBar ? ACTIVE : BTN, 0)) {
                ic.quickBar = !ic.quickBar;
                Config.save();
            }
            g.method_51433(this.field_22793, "The bottom hotbar row of the Inventory Hub.", x, y + 20, Theme.dim, !Theme.darkText);
        } else if (m.id.equals("nodarkness")) {
            int pct = cfg.darknessReduction;
            float v = slider(g, "nd_red", "Darkness removed: " + pct + "%", x, y, 190, 12, pct / 100f);
            int np = Math.round(v * 100);
            if (np != pct) cfg.darknessReduction = np;
            g.method_51433(this.field_22793, "100% removes the Darkness effect completely (Warden / sculk pulse and fog).", x, y + 20, Theme.dim, !Theme.darkText);
            g.method_51433(this.field_22793, "Below 100% it only softens the pulsing. For dark caves use Fullbright.", x, y + 32, Theme.dim, !Theme.darkText);
        } else if (m.id.equals("glint")) {
            if (button(g, x, y, 190, 14, "Glint: " + (cfg.glintCustom ? "Custom" : "Vanilla") + "  (click)", cfg.glintCustom ? ACTIVE : BTN, 0)) {
                cfg.glintCustom = !cfg.glintCustom;
                Config.save();
            }
            int ip = cfg.glintIntensity;
            float iv = slider(g, "gl_int", "Intensity: " + ip + "%", x, y + 18, 190, 12, ip / 100f);
            int ni = Math.round(iv * 100);
            if (ni != ip) cfg.glintIntensity = ni;
            int bw2 = 94;
            if (button(g, x, y + 36, bw2, 14, "Weapons: " + (cfg.glintWeapons ? "ON" : "OFF"), cfg.glintWeapons ? ACTIVE : BTN, 0)) { cfg.glintWeapons = !cfg.glintWeapons; Config.save(); }
            if (button(g, x + bw2 + 2, y + 36, bw2, 14, "Armor: " + (cfg.glintArmor ? "ON" : "OFF"), cfg.glintArmor ? ACTIVE : BTN, 0)) { cfg.glintArmor = !cfg.glintArmor; Config.save(); }
            if (button(g, x, y + 54, bw2, 14, "Tools: " + (cfg.glintTools ? "ON" : "OFF"), cfg.glintTools ? ACTIVE : BTN, 0)) { cfg.glintTools = !cfg.glintTools; Config.save(); }
            if (button(g, x + bw2 + 2, y + 54, bw2, 14, "Other: " + (cfg.glintOther ? "ON" : "OFF"), cfg.glintOther ? ACTIVE : BTN, 0)) { cfg.glintOther = !cfg.glintOther; Config.save(); }
            g.method_51433(this.field_22793, "ON/OFF = whether that kind of enchanted item shows a glint at all.", x, y + 74, Theme.dim, !Theme.darkText);
        } else if (m.id.equals("tiers")) {
            String mode = cfg.tierMode == null ? "BOTH" : cfg.tierMode;
            String name = mode.equals("MC") ? "MCTiers" : mode.equals("PVP") ? "PvPTiers" : "Both";
            if (button(g, x, y, 190, 14, "Tier source: " + name + "  (click to change)", BTN, BTN_TXT)) {
                cfg.tierMode = mode.equals("BOTH") ? "PVP" : mode.equals("PVP") ? "MC" : "BOTH";
                TierTagger.clear();
                Config.save();
            }
            g.method_51433(this.field_22793, "Shown behind player names and in this box.", x, y + 20, Theme.dim, !Theme.darkText);
        }
    }

    // ---------------------------------------------------------- settings tab
    private void renderSettings(class_332 g, Config cfg) {
        int by = py + top;
        if (button(g, px + 8, by, 90, 14, "GENERAL", settingsPage == 0 ? ACTIVE : BTN, 0)) settingsPage = 0;
        if (button(g, px + 8 + 94, by, 110, 14, "APPEARANCE", settingsPage == 1 ? ACTIVE : BTN, 0)) settingsPage = 1;
        if (settingsPage == 1) {
            renderAppearance(g, cfg, by + 20);
            return;
        }
        renderGeneral(g, cfg, by + 20);
    }

    private void renderGeneral(class_332 g, Config cfg, int y) {
        int w = (pw - 30) / 2;
        int lx = px + 10;
        int rx = lx + w + 10;

        cfg.accent = opaque(colorEditor(g, "s_acc", lx, y, w, "Menu accent color", cfg.accent, false));

        int by = y;
        // global switch: show / hide every module icon (applies instantly, saved in the config)
        boolean icOn = cfg.showModuleIcons;
        int itc = Glass.toggle(g, rx, by, w, 14, icOn, in(rx, by, w, 14));
        g.method_25300(this.field_22793, "Module Icons: " + (icOn ? "ON" : "OFF"), rx + w / 2, by + 3, itc);
        if (clickIn(rx, by, w, 14)) {
            cfg.showModuleIcons = !cfg.showModuleIcons;
            Config.save();
        }
        by += 18;
        if (button(g, rx, by, w, 14, "Clock: " + (cfg.clock24h ? "24-hour" : "12-hour"), BTN, BTN_TXT)) {
            cfg.clock24h = !cfg.clock24h;
            Config.save();
        }
        by += 18;
        if (button(g, rx, by, w, 14, "Name badge (C): " + (cfg.nameBadge ? "ON" : "OFF"), BTN, BTN_TXT)) {
            cfg.nameBadge = !cfg.nameBadge;
            Config.save();
        }
        by += 18;
        if (button(g, rx, by, w, 14, "Menu animations: " + (cfg.menuAnimations ? "ON" : "OFF"), BTN, BTN_TXT)) {
            cfg.menuAnimations = !cfg.menuAnimations;
            Config.save();
        }
        by += 18;
        if (button(g, rx, by, w, 14, "Reset all positions", BTN, BTN_TXT)) {
            for (Module m : Modules.ALL) { m.resetPosition(); m.cfg().scale = 1f; }
            Config.save();
        }
        by += 18;
        if (button(g, rx, by, w, 14, "Reset all colors", BTN, BTN_TXT)) {
            for (Module m : Modules.ALL) m.resetColors();
            cfg.accent = 0xFFFFFFFF;
            Config.save();
        }
        by += 18;
        g.method_51433(this.field_22793, "Menu look: Settings > APPEARANCE", rx, by + 3, Theme.dim, !Theme.darkText);

        // name at the bottom right of the settings page
        class_2561 name = class_2561.method_43470(ConfisClient.NAME).method_27692(class_124.field_1067);
        g.method_51439(this.field_22793, name, px + pw - 8 - this.field_22793.method_27525(name), py + ph - 14, Theme.text, !Theme.darkText);
    }

    /** Background Appearance: mode (Solid/Glass), 12 color swatches, glass sliders and a live preview. */
    private void renderAppearance(class_332 g, Config cfg, int y0) {
        int x0 = px + 10;
        int prevW = 150;
        int leftW = pw - 20 - prevW - 12;
        int shadow = Theme.darkText ? 0 : 1;
        boolean sh = shadow == 1;

        // --- Background Mode
        g.method_51433(this.field_22793, "Background Mode", x0, y0 + 3, Theme.text, sh);
        int mw = Math.max(44, (leftW - 100) / 2 - 2);
        if (button(g, x0 + 100, y0, mw, 14, "Solid", !cfg.themeGlass ? ACTIVE : BTN, 0)) { Theme.setGlass(false); Config.save(); }
        if (button(g, x0 + 100 + mw + 4, y0, mw, 14, "Glass", cfg.themeGlass ? ACTIVE : BTN, 0)) { Theme.setGlass(true); Config.save(); }

        // --- Color Selection (4 x 3 grid, swatch + label, selected = bright border + check mark)
        int cy0 = y0 + 22;
        g.method_51433(this.field_22793, "Color Selection", x0, cy0, Theme.text, sh);
        cy0 += 11;
        int cols = 4, cellW = leftW / cols, cellH = 17;
        Theme.ThemeColor[] all = Theme.ThemeColor.values();
        for (int i = 0; i < all.length; i++) {
            Theme.ThemeColor tc = all[i];
            int cx = x0 + (i % cols) * cellW, cy = cy0 + (i / cols) * cellH;
            boolean selected = tc == Theme.color;
            boolean hov = in(cx, cy, cellW - 2, cellH - 1);
            if (selected) Glass.fillRound(g, cx - 2, cy - 1, cellW - 2, cellH - 1, 3, Glass.rimArgb(0x40));
            Glass.swatch(g, cx + 1, cy + 1, 14, 11, tc.rgb, selected, hov);
            g.method_51433(this.field_22793, tc.label, cx + 19, cy + 3, Theme.text, sh);
            if (clickIn(cx - 2, cy - 1, cellW - 2, cellH - 1)) { Theme.setColor(tc); Config.save(); }
        }

        // --- Glass Settings
        int sy = cy0 + 3 * cellH + 6;
        g.method_51433(this.field_22793, cfg.themeGlass ? "Glass Settings" : "Glass Settings (switch to Glass mode)", x0, sy, cfg.themeGlass ? Theme.text : Theme.dim, sh);
        sy += 11;
        boolean gl = cfg.themeGlass;
        float vo = (Theme.clamp(cfg.glassOpacity, Theme.MIN_OPACITY, Theme.MAX_OPACITY) - Theme.MIN_OPACITY) / (float) (Theme.MAX_OPACITY - Theme.MIN_OPACITY);
        vo = slider(g, "t_op", "Glass opacity: " + cfg.glassOpacity + "%", x0, sy, leftW, 10, vo, gl);
        if (gl) cfg.glassOpacity = Theme.MIN_OPACITY + Math.round(vo * (Theme.MAX_OPACITY - Theme.MIN_OPACITY));
        sy += 13;
        float vb = slider(g, "t_bo", "Border intensity: " + cfg.borderIntensity + "%", x0, sy, leftW, 10, cfg.borderIntensity / 100f);
        cfg.borderIntensity = Math.round(vb * 100);
        sy += 13;
        float vh = slider(g, "t_hi", "Highlight intensity: " + cfg.highlightIntensity + "%", x0, sy, leftW, 10, cfg.highlightIntensity / 100f, gl);
        if (gl) cfg.highlightIntensity = Math.round(vh * 100);
        sy += 16;
        int bw = (leftW - 4) / 2;
        if (button(g, x0, sy, bw, 14, "Animations: " + (cfg.menuAnimations ? "ON" : "OFF"), BTN, BTN_TXT)) {
            cfg.menuAnimations = !cfg.menuAnimations;
            Config.save();
        }
        if (button(g, x0 + bw + 4, sy, bw, 14, "Reset appearance", BTN, BTN_TXT)) {
            cfg.themeColor = Theme.ThemeColor.BLUE.name();
            cfg.themeGlass = true;
            Theme.resetGlassSettings();
            Config.save();
        }

        // --- live preview (right): a fake "world" behind a mini menu so transparency is visible
        int vx = px + pw - 10 - prevW, vy = y0;
        int vh2 = 112;
        g.method_25294(vx, vy, vx + prevW / 2, vy + vh2, 0xFF3F7A3C);
        g.method_25294(vx + prevW / 2, vy, vx + prevW, vy + vh2, 0xFF7A6240);
        g.method_25294(vx, vy + vh2 - 22, vx + prevW, vy + vh2, 0xFF2B2F36);
        g.method_25294(vx + 20, vy + 8, vx + 44, vy + 30, 0xFFD8D8D8);
        Glass.panel(g, vx + 4, vy + 4, prevW - 8, vh2 - 8);
        int tabY = vy + 9;
        int t1 = Glass.button(g, vx + 9, tabY, 36, 12, ACTIVE, false);
        g.method_25300(this.field_22793, "HUD", vx + 9 + 18, tabY + 2, t1);
        int t2 = Glass.button(g, vx + 49, tabY, 36, 12, BTN, false);
        g.method_25300(this.field_22793, "INFO", vx + 49 + 18, tabY + 2, t2);
        int pcw = 62, pch = 74;
        int pcx = vx + 9, pcy = tabY + 17;
        Glass.card(g, pcx, pcy, pcw, pch - 8, false, true);
        g.method_51433(this.field_22793, "FPS", pcx + 5, pcy + 4, Theme.text, sh);
        if (cfg.showModuleIcons) Icons.drawIcon(g, "fps", pcx + (pcw - 32) / 2, pcy + 14, 2, Icons.NORMAL);
        int ttc = Glass.toggle(g, pcx + 4, pcy + pch - 8 - 16, pcw - 8, 12, true, false);
        g.method_25300(this.field_22793, "Enabled", pcx + pcw / 2, pcy + pch - 8 - 14, ttc);
        int pcx2 = pcx + pcw + 6;
        Glass.card(g, pcx2, pcy, pcw - 10, pch - 8, false, false);
        g.method_51433(this.field_22793, "Ping", pcx2 + 5, pcy + 4, Theme.text, sh);
        if (cfg.showModuleIcons) Icons.drawIcon(g, "ping", pcx2 + (pcw - 10 - 32) / 2, pcy + 14, 2, Icons.DISABLED);
        int dtc = Glass.toggle(g, pcx2 + 4, pcy + pch - 8 - 16, pcw - 18, 12, false, false);
        g.method_25300(this.field_22793, "Disabled", pcx2 + (pcw - 10) / 2, pcy + pch - 8 - 14, dtc);
        g.method_51433(this.field_22793, "Live preview", vx + 2, vy + vh2 + 3, Theme.dim, sh);
    }

    // -------------------------------------------------------- waypoints tab
    private void renderWaypoints(class_332 g, Config cfg) {
        class_310 mc = class_310.method_1551();
        int accent = opaque(cfg.accent);
        if (button(g, px + 8 + 190 + 6, py + top, 120, 14, "+ Add by coordinates", BTN, BTN_TXT)) {
            mc.method_1507(new WaypointAddScreen(this));
            return;
        }
        if (button(g, px + 8, py + top, 190, 14, "+ Add waypoint at my position", ACTIVE, 0)
                && mc.field_1724 != null && mc.field_1687 != null) {
            cfg.waypointList.add(new Waypoint("Waypoint " + (cfg.waypointList.size() + 1),
                    mc.field_1724.method_31477(), mc.field_1724.method_31478(), mc.field_1724.method_31479(),
                    mc.field_1687.method_27983().toString(), Waypoints.nextColor(cfg)));
            Config.save();
        }

        int rowH = 17;
        int rowsTop = py + top + 20;
        int perPage = Math.max(1, (ph - top - 20 - 22) / rowH);
        int pages = Math.max(1, (cfg.waypointList.size() + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;

        if (cfg.waypointList.isEmpty()) {
            g.method_51433(this.field_22793, "No waypoints yet. Click the button above to add one here.",
                    px + 8, rowsTop + 4, Theme.dim, !Theme.darkText);
        }
        for (int n = 0; n < perPage; n++) {
            int idx = page * perPage + n;
            if (idx >= cfg.waypointList.size()) break;
            Waypoint w = cfg.waypointList.get(idx);
            int y = rowsTop + n * rowH;
            Glass.card(g, px + 8, y, pw - 16, rowH - 2, false, false);

            g.method_25294(px + 11, y + 2, px + 24, y + 13, 0xFFFFFFFF);
            g.method_25294(px + 12, y + 3, px + 23, y + 12, opaque(w.color));
            if (clickIn(px + 11, y + 2, 13, 11)) {
                int ci = 0;
                for (int i = 0; i < Waypoints.PALETTE.length; i++) if (Waypoints.PALETTE[i] == w.color) ci = i;
                w.color = Waypoints.PALETTE[(ci + 1) % Waypoints.PALETTE.length];
                Config.save();
            }
            String dim = w.dim.contains("nether") ? "Nether" : w.dim.contains("end") ? "End"
                    : w.dim.contains("overworld") ? "Overworld" : "?";
            g.method_51433(this.field_22793, w.name, px + 30, y + 4, Theme.text, !Theme.darkText);
            g.method_51433(this.field_22793, w.x + ", " + w.y + ", " + w.z + "  (" + dim + ")",
                    px + 120, y + 4, Theme.dim, !Theme.darkText);
            if (button(g, px + pw - 8 - 16, y + 1, 14, 13, "X", BTN, BTN_TXT)) {
                cfg.waypointList.remove(idx);
                Config.save();
                break;
            }
        }

        if (pages > 1) {
            int by = py + ph - 17;
            if (button(g, px + pw - 78, by, 18, 12, "<", BTN, BTN_TXT)) page = Math.max(0, page - 1);
            g.method_25300(this.field_22793, (page + 1) + "/" + pages, px + pw - 44, by + 2, Theme.text);
            if (button(g, px + pw - 28, by, 18, 12, ">", BTN, BTN_TXT)) page = Math.min(pages - 1, page + 1);
        }
    }

    // -------------------------------------------------------------- edit HUD
    private Module selected;

    private void changeScale(Module m, float delta) {
        Config.ModuleCfg c = m.cfg();
        c.scale = Math.max(0.5f, Math.min(3.0f, Math.round((c.scale + delta) * 10) / 10f));
        Config.save();
    }

    /** Top-most movable, enabled module under the mouse (or null). */
    private Module hoveredModule() {
        class_310 mc = class_310.method_1551();
        int W = this.field_22789, H = this.field_22790;
        for (int i = Modules.ALL.size() - 1; i >= 0; i--) {
            Module m = Modules.ALL.get(i);
            if (!m.movable() || !m.cfg().enabled) continue;
            if (in(m.posX(mc, W), m.posY(mc, H), m.sw(mc), m.sh(mc))) return m;
        }
        return null;
    }

    private void renderEdit(class_332 g) {
        class_310 mc = class_310.method_1551();
        Config cfg = Config.get();
        int accent = opaque(cfg.accent);
        int W = this.field_22789, H = this.field_22790;

        g.method_25300(this.field_22793, "Drag to move  |  mouse wheel or - / + to resize", W / 2, 8, 0xFFFFFFFF);
        if (button(g, W / 2 - 62, 22, 60, 14, "DONE", ACTIVE, 0)) {
            mode = Mode.MENU;
            openedAt = System.nanoTime();     // pop the menu back up
            dragging = null;
            selected = null;
            Config.save();
            return;
        }
        if (button(g, W / 2 + 2, 22, 60, 14, "RESET", BTN, BTN_TXT)) {
            for (Module m : Modules.ALL) m.resetPosition();
            Config.save();
        }

        List<Module> movable = new ArrayList<>();
        for (Module m : Modules.ALL) if (m.movable() && m.cfg().enabled) movable.add(m);

        if (selected != null && !movable.contains(selected)) selected = null;

        // size controls under the selected element: [-] 1.0x [+]
        int ctlX = 0, ctlY = 0, ctlW = 76, ctlH = 14;
        if (selected != null) {
            int sx = selected.posX(mc, W), sy = selected.posY(mc, H);
            ctlX = Math.max(2, Math.min(W - ctlW - 2, sx + selected.sw(mc) / 2 - ctlW / 2));
            ctlY = sy + selected.sh(mc) + 4;
            if (ctlY + ctlH > H - 2) ctlY = Math.max(40, sy - ctlH - 4);
        }

        if (clicked && dragging == null && !(selected != null && in(ctlX, ctlY, ctlW, ctlH))) {
            for (int i = movable.size() - 1; i >= 0; i--) {
                Module m = movable.get(i);
                int x = m.posX(mc, W), y = m.posY(mc, H);
                if (in(x, y, m.sw(mc), m.sh(mc))) {
                    dragging = m;
                    selected = m;
                    dragOffX = mx - x;
                    dragOffY = my - y;
                    clicked = false;
                    break;
                }
            }
        }
        if (dragging != null) {
            if (down) {
                int w = dragging.sw(mc), h = dragging.sh(mc);
                int nx = Math.max(0, Math.min(W - w, mx - dragOffX));
                int ny = Math.max(0, Math.min(H - h, my - dragOffY));
                if (nx < 5) nx = 0;
                if (ny < 5) ny = 0;
                if (nx > W - w - 5) nx = W - w;
                if (ny > H - h - 5) ny = H - h;
                if (Math.abs(nx + w / 2 - W / 2) < 4) nx = W / 2 - w / 2;
                Config.ModuleCfg c = dragging.cfg();
                c.fx = nx / (double) W;
                c.fy = ny / (double) H;
            } else {
                dragging = null;
                Config.save();
            }
        }

        for (Module m : movable) {
            int x = m.posX(mc, W), y = m.posY(mc, H);
            int w = m.sw(mc), h = m.sh(mc);
            boolean active = m == dragging;
            if (in(x, y, w, h) || active) g.method_25294(x, y, x + w, y + h, 0x30FFFFFF);
            outline(g, x - 1, y - 1, w + 2, h + 2, active ? 0xFFFFFFFF : m == selected ? accent : 0xFF9A9A9A);
        }

        if (selected != null) {
            Config.ModuleCfg sc = selected.cfg();
            if (button(g, ctlX, ctlY, 16, ctlH, "-", BTN, BTN_TXT)) changeScale(selected, -0.1f);
            g.method_25300(this.field_22793, String.format("%.1fx", sc.scale), ctlX + ctlW / 2, ctlY + 3, 0xFFFFFFFF);
            if (button(g, ctlX + ctlW - 16, ctlY, 16, ctlH, "+", BTN, BTN_TXT)) changeScale(selected, 0.1f);
        }
    }
}
