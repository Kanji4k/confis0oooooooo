package client.confis;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Module icons.
 *
 * The 24 module icons are 16x16 shaded pixel art (see tools/gen_icons.py -> IconData.java). At class-load they are
 * decomposed ONCE into a small list of solid rectangles per icon and per state (normal / disabled / hover), so
 * drawing an icon is a plain loop of fill() calls: no per-frame allocation, no color math, no texture upload,
 * crisp nearest-neighbour pixels at any integer scale.
 *
 * heart / gear / search stay tiny one-color glyphs that are tinted at draw time.
 */
public final class Icons {
    private Icons() {}

    public static final int NORMAL = 0, DISABLED = 1, HOVER = 2;
    public static final int SIZE = 16;

    // ------------------------------------------------------------------ small mono glyphs
    private static final String[] SEARCH = {".####..", "#....#.", "#....#.", "#....#.", ".####..", "....##.", ".....##"};
    private static final String[] HEART = {".##.##.", "#######", "#######", ".#####.", "..###..", "...#..."};
    private static final String[] GEAR = {"...#...", ".#####.", ".#...#.", "##.#.##", ".#...#.", ".#####.", "...#..."};

    private static String[] mono(String id) {
        return switch (id) {
            case "search" -> SEARCH;
            case "heart" -> HEART;
            case "gear" -> GEAR;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ extracted sprite icons
    /**
     * Module icons that come from the reference sheet (tools/extract_icons.py). Each is a 32x32 PNG with a transparent
     * background under assets/<mod>/textures/gui/sprites/module/ (<name>.png, <name>_off.png, <name>_hov.png).
     * They are GUI sprites, so Minecraft stitches them into the GUI atlas once; drawing is one drawGuiTexture call.
     * module id -> sprite name. A module that is not listed here (saturation) keeps its pre-baked icon below.
     */
    private static final String[][] SPRITE_MAP = {
            {"fps", "compass"}, {"ping", "ping"}, {"tps", "tps"}, {"cps", "mouse"}, {"clock", "clock"},
            {"day", "sun"}, {"armor", "armor"}, {"keystrokes", "wasd"}, {"waypoints", "waypoint"},
            {"effects", "potion_purple"}, {"scoreboard", "scoreboard"}, {"reach", "crosshair"},
            {"range", "crosshair"}, {"inventory", "inventory"}, {"totems", "totem"},
            {"potions", "potion_green"}, {"zoom", "magnifier"}, {"freecam", "camera"},
            {"fullbright", "bulb"}, {"noblind", "eye_slash"}, {"tiers", "trophy"}, {"shulker", "shulker"},
            {"crosshair", "brackets"}, {"nodarkness", "moon"}, {"glint", "sparkle"},
    };
    /** sprite[state] per module id, built once. */
    private static final java.util.HashMap<String, Identifier[]> SPRITES = new java.util.HashMap<>();

    static {
        for (String[] m : SPRITE_MAP) {
            String base = "module/" + m[1];
            SPRITES.put(m[0], new Identifier[] {
                    Identifier.of(ConfisClient.MOD_ID, base),             // NORMAL
                    Identifier.of(ConfisClient.MOD_ID, base + "_off"),    // DISABLED
                    Identifier.of(ConfisClient.MOD_ID, base + "_hov"),    // HOVER
            });
        }
    }

    // ------------------------------------------------------------------ pre-baked colored icons
    private static final class Baked {
        /** x, y, w, h (in icon pixels), color index */
        int[] rect;
        int count;
        /** colors[state][colorIndex] = ARGB */
        int[][] colors;
    }

    private static final java.util.HashMap<String, Baked> BAKED = new java.util.HashMap<>();

    static {
        int[] pal = new int[128];
        boolean[] has = new boolean[128];
        String p = IconData.PALETTE;
        for (int i = 0; i + 7 <= p.length(); i += 7) {
            char ch = p.charAt(i);
            pal[ch] = Integer.parseInt(p.substring(i + 1, i + 7), 16);
            has[ch] = true;
        }
        for (int n = 0; n < IconData.IDS.length; n++) BAKED.put(IconData.IDS[n], bake(IconData.ROWS[n], pal, has));
    }

    private static Baked bake(String[] rows, int[] pal, boolean[] has) {
        int h = rows.length, w = rows[0].length();
        boolean[][] done = new boolean[h][w];
        int[] tmp = new int[h * w * 5];
        int n = 0;
        int[] used = new int[128];
        int usedCount = 0;
        int[] slot = new int[128];
        java.util.Arrays.fill(slot, -1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                char ch = rows[y].charAt(x);
                if (done[y][x] || ch == '.' || ch >= 128 || !has[ch]) continue;
                int x1 = x;
                while (x1 + 1 < w && !done[y][x1 + 1] && rows[y].charAt(x1 + 1) == ch) x1++;
                int y1 = y;
                boolean grow = true;
                while (grow && y1 + 1 < h) {          // grow downward while the whole run matches
                    for (int xx = x; xx <= x1; xx++) {
                        if (done[y1 + 1][xx] || rows[y1 + 1].charAt(xx) != ch) { grow = false; break; }
                    }
                    if (grow) y1++;
                }
                for (int yy = y; yy <= y1; yy++) for (int xx = x; xx <= x1; xx++) done[yy][xx] = true;
                if (slot[ch] < 0) { slot[ch] = usedCount; used[usedCount++] = ch; }
                tmp[n++] = x; tmp[n++] = y; tmp[n++] = x1 - x + 1; tmp[n++] = y1 - y + 1; tmp[n++] = slot[ch];
            }
        }
        Baked b = new Baked();
        b.rect = java.util.Arrays.copyOf(tmp, n);
        b.count = n / 5;
        b.colors = new int[3][usedCount];
        for (int i = 0; i < usedCount; i++) {
            int rgb = pal[used[i]];
            b.colors[NORMAL][i] = 0xFF000000 | rgb;
            b.colors[DISABLED][i] = 0xC8000000 | dull(rgb);
            b.colors[HOVER][i] = 0xFF000000 | brighten(rgb);
        }
        return b;
    }

    /** Disabled look: darker and partly desaturated, still clearly recognisable. */
    private static int dull(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int l = (r * 299 + g * 587 + b * 114) / 1000;
        r = (int) ((r + (l - r) * 0.45f) * 0.62f);
        g = (int) ((g + (l - g) * 0.45f) * 0.62f);
        b = (int) ((b + (l - b) * 0.45f) * 0.62f);
        return (r << 16) | (g << 8) | b;
    }

    private static int brighten(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        r = Math.min(255, r + (255 - r) / 7);
        g = Math.min(255, g + (255 - g) / 7);
        b = Math.min(255, b + (255 - b) / 7);
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ drawing
    /** True if this id has a colored module icon. */
    public static boolean has(String id) { return SPRITES.containsKey(id) || BAKED.containsKey(id); }

    /**
     * Shared icon renderer: draws the module icon with its top-left at (x, y); every icon pixel becomes a
     * scale x scale square. state = NORMAL / DISABLED / HOVER (picks a pre-baked palette, no per-frame math).
     */
    public static void drawIcon(DrawContext g, String id, int x, int y, int scale, int state) {
        Identifier[] sp = SPRITES.get(id);
        if (sp != null) {                                  // extracted PNG icon: one textured quad, pixel-exact at scale 2
            int size = SIZE * scale;
            g.drawGuiTexture(RenderPipelines.GUI_TEXTURED, sp[state], x, y, size, size);
            return;
        }
        Baked b = BAKED.get(id);
        if (b == null) return;
        int[] c = b.colors[state];
        int[] r = b.rect;
        for (int i = 0; i < b.count; i++) {
            int o = i * 5;
            int rx = x + r[o] * scale, ry = y + r[o + 1] * scale;
            g.fill(rx, ry, rx + r[o + 2] * scale, ry + r[o + 3] * scale, c[r[o + 4]]);
        }
    }

    /** Back-compatible entry point: colored module icons ignore `color`; heart / gear / search are tinted with it. */
    public static void draw(DrawContext g, String id, int x, int y, int scale, int color) {
        String[] rows = mono(id);
        if (rows == null) {
            drawIcon(g, id, x, y, scale, NORMAL);
            return;
        }
        for (int j = 0; j < rows.length; j++) {
            String r = rows[j];
            int i = 0;
            while (i < r.length()) {
                if (r.charAt(i) != '#') { i++; continue; }
                int k = i;
                while (k < r.length() && r.charAt(k) == '#') k++;
                g.fill(x + i * scale, y + j * scale, x + k * scale, y + (j + 1) * scale, color);
                i = k;
            }
        }
    }

    public static int width(String id) { String[] r = mono(id); return r != null ? r[0].length() : has(id) ? SIZE : 0; }
    public static int height(String id) { String[] r = mono(id); return r != null ? r.length : has(id) ? SIZE : 0; }
}
