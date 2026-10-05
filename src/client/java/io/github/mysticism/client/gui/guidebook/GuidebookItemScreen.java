package io.github.mysticism.client.gui.guidebook;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Read-only vanilla item details; the supplied mutable stack is defensively copied. */
final class GuidebookItemScreen extends Screen {
    private final Screen parent;
    private final ItemStack stack;
    private List<List<OrderedText>> pages = List.of();
    private int page, left, right;
    GuidebookItemScreen(Screen parent, ItemStack stack) {
        super(stack.getName().copy()); this.parent = parent; this.stack = stack.copy();
    }
    @Override protected void init() {
        left = Math.max(8, (width - 480) / 2); right = width - left;
        List<OrderedText> rows = new ArrayList<>();
        List<Text> details = new ArrayList<>(getTooltipFromItem(client, stack));
        details.add(Text.translatable("guidebook.mysticism.item.details"));
        for (Text detail : details) rows.addAll(textRenderer.wrapLines(detail, Math.max(24, right - left - 24)));
        pages = GuidebookPagination.pack(rows, Math.max(1, (height - 120) / 12), ignored -> 1);
        page = GuidebookPagination.clamp(page, pages.size());
        buttons();
    }
    private void buttons() {
        clearChildren();
        addDrawableChild(ButtonWidget.builder(Text.translatable("guidebook.mysticism.back"), ignored -> close())
                .dimensions(left, height - 28, right - left, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), ignored -> turn(-1))
                .dimensions(left, 28, 24, 20).build()).active = page > 0;
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), ignored -> turn(1))
                .dimensions(right - 24, 28, 24, 20).build()).active = page < pages.size() - 1;
    }
    private void turn(int delta) { page = GuidebookPagination.clamp(page + delta, pages.size()); buttons(); }
    @Override public void render(DrawContext context, int x, int y, float delta) {
        GuidebookShimmer.render(context, width, height, GuidebookClient.reducedMotion);
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(title.getString(), right - left), width / 2, 12, 0xf2dcff);
        context.drawItem(stack, width / 2 - 8, 30);
        context.enableScissor(left, 56, right, Math.max(57, height - 44));
        try {
            int rowY = 58;
            for (OrderedText row : pages.get(page)) { context.drawTextWithShadow(textRenderer, row, left + 12, rowY, 0xe2dce9); rowY += 12; }
        } finally { context.disableScissor(); }
        super.render(context, x, y, delta);
        if (x >= width / 2 - 8 && x < width / 2 + 8 && y >= 30 && y < 46) context.drawItemTooltip(textRenderer, stack, x, y);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) { turn(vertical > 0 ? -1 : 1); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_BACKSPACE) { close(); return true; }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_PAGE_UP) { turn(-1); return true; }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_PAGE_DOWN) { turn(1); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { if (client != null) client.setScreen(parent); }
}
