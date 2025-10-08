//package io.github.mysticism.client.gui.toast;
//
//import net.minecraft.client.font.TextRenderer;
//import net.minecraft.client.gui.DrawContext;
//import net.minecraft.client.toast.Toast;
//import net.minecraft.client.toast.ToastManager;
//import net.minecraft.item.ItemStack;
//import net.minecraft.item.Items;
//import net.minecraft.text.Text;
//
///**
// * Simple custom toast for 1.21.x Fabric/Yarn.
// * Notes:
// * - draw(...) now returns void and receives a TextRenderer.
// * - Control lifetime via getVisibility() + update(...).
// */
//public class DownloadToast implements Toast {
//    private final Text title;
//    private final Text description;
//
//    // toast state
//    private Toast.Visibility visibility = Toast.Visibility.SHOW;
//    private long firstTime = -1L;        // first timestamp seen (ms)
//    private final long durationMs = 5000L;
//
//    public DownloadToast(Text title, Text description) {
//        this.title = title;
//        this.description = description;
//    }
//
//    @Override
//    public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
//        // remember when we first rendered
//        if (firstTime < 0) firstTime = startTime;
//
//        // --- background ---
//        // Vanilla toasts use a texture; to avoid depending on internal sprites,
//        // we draw a simple rounded-ish panel using fills that works across packs.
//        // Panel
//        int w = getWidth();
//        int h = getHeight();
//        // base background (slightly translucent dark)
//        context.fill(0, 0, w, h, 0xCC1F1F1F);
//        // top highlight line
//        context.fill(0, 0, w, 1, 0x40FFFFFF);
//        // bottom shadow line
//        context.fill(0, h - 1, w, h, 0x40000000);
//
//        // --- contents ---
//        // Icon (e.g., enchanting table)
//        context.drawItem(new ItemStack(Items.ENCHANTING_TABLE), 8, 8);
//
//        // Text
//        context.drawText(textRenderer, title, 30, 7, 0xFF500050, false);
//        context.drawText(textRenderer, description, 30, 18, 0xFF000000, false);
//    }
//
//    @Override
//    public void update(ToastManager manager, long time) {
//        // time is the same clock used for draw(startTime)
//        if (firstTime >= 0 && (time - firstTime) >= durationMs) {
//            visibility = Toast.Visibility.HIDE;
//        }
//    }
//
//    @Override
//    public Toast.Visibility getVisibility() {
//        return visibility;
//    }
//
//    // (Optional) override size if you want; defaults are fine:
//    // @Override public int getWidth() { return Toast.BASE_WIDTH; }
//    // @Override public int getHeight() { return Toast.BASE_HEIGHT; }
//
//    /** Helper to show this toast. */
//    public static void show(ToastManager manager, Text title, Text description) {
//        manager.add(new DownloadToast(title, description));
//    }
//}
