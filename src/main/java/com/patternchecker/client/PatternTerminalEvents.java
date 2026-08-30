package com.patternchecker.client;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.widgets.AE2Button;
import com.patternchecker.PatternCheckerMod;
import com.patternchecker.network.NetworkHandler;
import com.patternchecker.network.PatternToolActionPayload;
import com.patternchecker.network.ToolListPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a pattern-checker module to the left edge of the AE2 pattern encoding
 * terminal opened by the tool. The module deliberately uses AE2's own panel
 * texture, button class, colors and 18-pixel layout rhythm so it reads as part
 * of the terminal instead of as a separate overlay.
 */
@EventBusSubscriber(modid = PatternCheckerMod.MODID, value = Dist.CLIENT)
public final class PatternTerminalEvents {

    private static final int MODULE_WIDTH = 158;
    private static final int MODULE_HEIGHT = 218;
    private static final int MODULE_GAP = 4;
    private static final int COLLAPSED_SIZE = 20;
    private static int savedOffsetX;
    private static int savedOffsetY;
    private static Screen activeScreen;
    private static ToolPanel activePanel;
    private static EntryKey persistedSelectionKey;
    private static boolean terminalPanelEnabled = true;

    private record EntryKey(String location, int slot) {
    }

    private PatternTerminalEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!isPatternEncodingScreen(screen)) {
            activeScreen = null;
            activePanel = null;
            return;
        }
        NetworkHandler.clearPendingToolList();
        int moduleHeight = Math.max(1, Math.min(MODULE_HEIGHT, screenHeight(screen)));
        // Keep the checker toggle outside the terminal bounds. FTB Quests adds
        // a button at the terminal edge, and the collapsed 20x20 checker used
        // to sit on top of it and consume its clicks.
        int anchorX = screenGuiLeft(screen) - COLLAPSED_SIZE - 2;
        int anchorY = screenGuiTop(screen) + 6 + COLLAPSED_SIZE + 2;
        int panelWidth = terminalPanelEnabled ? MODULE_WIDTH : COLLAPSED_SIZE;
        int panelHeight = terminalPanelEnabled ? moduleHeight : COLLAPSED_SIZE;
        int panelX = terminalPanelEnabled
                ? anchorX - (MODULE_WIDTH - COLLAPSED_SIZE)
                : anchorX;
        int panelY = terminalPanelEnabled
                ? anchorY + COLLAPSED_SIZE - moduleHeight
                : anchorY;
        EntryKey selectionKey = activePanel != null ? activePanel.selectedKey : persistedSelectionKey;
        ToolPanel panel = new ToolPanel(
                panelX + savedOffsetX,
                panelY + savedOffsetY,
                panelWidth,
                panelHeight,
                anchorX,
                anchorY,
                moduleHeight,
                selectionKey);
        activeScreen = screen;
        activePanel = panel;
    }

    /**
     * AE2 add-ons commonly provide their own screen implementation while
     * keeping the same PatternEncodingTermMenu hierarchy. Avoid hard links to
     * optional add-on classes: detect the vanilla screen directly and use the
     * stable class-name convention for compatible add-on screens.
     */
    private static boolean isPatternEncodingScreen(Screen screen) {
        if (screen instanceof PatternEncodingTermScreen<?>) {
            return true;
        }
        return screen != null
                && screen.getClass().getName().contains("PatternEncodingTermScreen");
    }

    private static int screenHeight(Screen screen) {
        if (screen instanceof PatternEncodingTermScreen<?> vanilla) {
            int height = vanilla.getYSize();
            return height > 0 ? height : MODULE_HEIGHT;
        }
        try {
            var method = screen.getClass().getMethod("getYSize");
            Object value = method.invoke(screen);
            if (value instanceof Number number && number.intValue() > 0) {
                return number.intValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return MODULE_HEIGHT;
    }

    private static int screenGuiLeft(Screen screen) {
        try {
            Object value = screen.getClass().getMethod("getGuiLeft").invoke(screen);
            return value instanceof Number number ? number.intValue() : MODULE_GAP;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return MODULE_GAP;
        }
    }

    private static int screenGuiTop(Screen screen) {
        try {
            Object value = screen.getClass().getMethod("getGuiTop").invoke(screen);
            return value instanceof Number number ? number.intValue() : MODULE_GAP;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return MODULE_GAP;
        }
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getScreen() == activeScreen && activePanel != null
                && activePanel.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (event.getScreen() == activeScreen && activePanel != null
                && activePanel.mouseDragged(
                        event.getMouseX(), event.getMouseY(), event.getMouseButton(),
                        event.getDragX(), event.getDragY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getScreen() == activeScreen && activePanel != null
                && activePanel.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (event.getScreen() == activeScreen && activePanel != null
                && activePanel.mouseScrolled(
                        event.getMouseX(), event.getMouseY(),
                        event.getScrollDeltaX(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (event.getScreen() == activeScreen && activePanel != null) {
            activePanel.renderOverlay(
                    event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    private static final class ToolPanel extends AbstractWidget {

        private static final ResourceLocation PATTERN_TEXTURE =
                ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/pattern.png");

        private static final int TEXTURE_SIZE = 256;
        private static final int SOURCE_WIDTH = 195;
        private static final int HEADER_HEIGHT = 17;

        private static final int BORDER_COLOR = 0xFF413F54;
        private static final int PANEL_COLOR = 0xFFCBCCD4;
        private static final int PANEL_HIGHLIGHT_COLOR = 0xFFF2F2F2;
        private static final int RECESS_COLOR = 0xFFADB0C4;
        private static final int TEXT_COLOR = 0xFF413F54;
        private static final int DIM_TEXT_COLOR = 0xFF6E7080;
        private static final int ACCENT_COLOR = 0xFF2F5FAF;
        private static final int SELECT_COLOR = 0xFFBBD4FF;
        private static final int SELECT_BORDER_COLOR = 0xFF6D8FC4;
        private static final int ERROR_COLOR = 0xFFD13B3B;
        private static final int WARNING_COLOR = 0xFFC9931F;

        private static final int OUTER_PADDING = 4;
        private static final int BUTTON_HEIGHT = 18;
        private static final int BUTTON_GAP = 2;
        private static final int TOP_BUTTON_Y = 19;
        private static final int TOGGLE_BUTTON_Y = 39;
        private static final int LIST_TOP = 61;
        private static final int LIST_SIDE = 5;
        private static final int LIST_BOTTOM_RESERVED = 58;
        private static final int ROW_HEIGHT = 22;
        private static final int ACTION_BUTTONS = 5;
        private static final int TOOLTIP_WIDTH = 240;
        private static final float OVERLAY_Z = 500.0F;

        private final AE2Button scanButton;
        private final AE2Button unbindButton;
        private final AE2Button inputButton;
        private final AE2Button duplicateButton;
        private final AE2Button highlightButton;
        private final AE2Button editButton;
        private final AE2Button extractButton;
        private final AE2Button uploadButton;
        private final AE2Button writeButton;
        private final AE2Button panelToggleButton;
        private final AE2Button[] buttons;

        private int scroll;
        private int selected = -1;
        private EntryKey selectedKey;
        private final int anchorX;
        private final int anchorY;
        private final int expandedHeight;
        private int capturedButton = -1;
        private boolean dragging;
        private boolean scrollbarDragging;
        private int scrollbarDragOffset;
        private int dragOffsetX;
        private int dragOffsetY;
        private int syncDelayFrames = 12;
        private boolean syncRequested;
        private boolean revealRestoredSelection;
        private List<ToolListPayload.Entry> cachedEntries = List.of();
        private Map<Integer, ToolListPayload.Entry> entriesByIndex = Map.of();
        private final Map<String, ItemStack> iconCache = new HashMap<>();

        ToolPanel(int x, int y, int width, int height, int anchorX, int anchorY,
                  int expandedHeight,
                  EntryKey selectionKey) {
            super(x, y, width, height, Component.translatable("patternchecker.menu.title"));
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.expandedHeight = expandedHeight;
            this.selectedKey = selectionKey;
            this.revealRestoredSelection = selectionKey != null;
            refreshEntries(PatternCheckClient.getToolList());
            clampToScreen();
            panelToggleButton = new AE2Button(
                    x + width - COLLAPSED_SIZE, y, COLLAPSED_SIZE, COLLAPSED_SIZE,
                    Component.empty(), button -> {
                        terminalPanelEnabled = !terminalPanelEnabled;
                        setPanelExpanded(terminalPanelEnabled);
                        updatePanelToggleButton();
                    });

            int splitWidth = (width - OUTER_PADDING * 2 - BUTTON_GAP) / 2;
            scanButton = new AE2Button(
                    x + OUTER_PADDING, y + TOP_BUTTON_Y, splitWidth, BUTTON_HEIGHT,
                    Component.translatable("patternchecker.menu.scan"),
                    button -> sendAction(PatternToolActionPayload.ACTION_SCAN, -1));
            unbindButton = new AE2Button(
                    x + OUTER_PADDING + splitWidth + BUTTON_GAP, y + TOP_BUTTON_Y,
                    width - OUTER_PADDING * 2 - BUTTON_GAP - splitWidth, BUTTON_HEIGHT,
                    Component.translatable("patternchecker.menu.unbind"),
                    button -> sendAction(PatternToolActionPayload.ACTION_UNBIND, -1));
            inputButton = new AE2Button(
                    x + OUTER_PADDING, y + TOGGLE_BUTTON_Y, splitWidth, BUTTON_HEIGHT,
                    Component.empty(),
                    button -> sendAction(PatternToolActionPayload.ACTION_TOGGLE_INPUT, -1));
            duplicateButton = new AE2Button(
                    x + OUTER_PADDING + splitWidth + BUTTON_GAP, y + TOGGLE_BUTTON_Y,
                    width - OUTER_PADDING * 2 - BUTTON_GAP - splitWidth, BUTTON_HEIGHT,
                    Component.empty(),
                    button -> sendAction(PatternToolActionPayload.ACTION_TOGGLE_DUPLICATE, -1));

            int actionY = actionButtonY();
            int available = width - OUTER_PADDING * 2 - BUTTON_GAP * (ACTION_BUTTONS - 1);
            int actionWidth = available / ACTION_BUTTONS;
            int remainder = available % ACTION_BUTTONS;
            highlightButton = actionButton(0, actionY, actionWidth, remainder,
                    "patternchecker.menu.highlight", PatternToolActionPayload.ACTION_HIGHLIGHT);
            editButton = actionButton(1, actionY, actionWidth, remainder,
                    "patternchecker.menu.edit", PatternToolActionPayload.ACTION_EDIT);
            extractButton = actionButton(2, actionY, actionWidth, remainder,
                    "patternchecker.menu.extract", PatternToolActionPayload.ACTION_EXTRACT);
            uploadButton = actionButton(3, actionY, actionWidth, remainder,
                    "patternchecker.menu.upload", PatternToolActionPayload.ACTION_UPLOAD);
            writeButton = actionButton(4, actionY, actionWidth, remainder,
                    "patternchecker.menu.write", PatternToolActionPayload.ACTION_WRITE);
            buttons = new AE2Button[]{
                    scanButton, unbindButton, inputButton, duplicateButton,
                    highlightButton, editButton, extractButton, uploadButton, writeButton
            };
            moveButtons();
        }

        private AE2Button actionButton(int index, int y, int baseWidth, int remainder,
                                       String labelKey, int action) {
            int widthBefore = index * baseWidth + Math.min(index, remainder);
            int buttonWidth = baseWidth + (index < remainder ? 1 : 0);
            int x = getX() + OUTER_PADDING + index * BUTTON_GAP + widthBefore;
            return new AE2Button(
                    x, y, buttonWidth, BUTTON_HEIGHT, Component.translatable(labelKey),
                    button -> {
                        if (hasSelectedProvider()) {
                            sendAction(action, selected);
                        }
                    });
        }

        private static Minecraft minecraft() {
            return Minecraft.getInstance();
        }

        private static void sendAction(int action, int index) {
            PacketDistributor.sendToServer(new PatternToolActionPayload(action, index));
        }

        private List<ToolListPayload.Entry> entries() {
            return cachedEntries;
        }

        private ToolListPayload.Entry selectedEntry() {
            return entriesByIndex.get(selected);
        }

        private boolean hasSelectedProvider() {
            ToolListPayload.Entry entry = selectedEntry();
            return entry != null && entry.hasProvider();
        }

        private int listBottom() {
            return getY() + getHeight() - LIST_BOTTOM_RESERVED;
        }

        private int actionButtonY() {
            return getY() + getHeight() - 54;
        }

        private int visibleRows() {
            return Math.max(1, (listBottom() - (getY() + LIST_TOP)) / ROW_HEIGHT);
        }

        private boolean isInside(double mouseX, double mouseY) {
            return mouseX >= getX() && mouseX < getX() + getWidth()
                    && mouseY >= getY() && mouseY < getY() + getHeight();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!PatternCheckClient.getToolList().available() || !isInside(mouseX, mouseY)) {
                return false;
            }
            if (!terminalPanelEnabled) {
                return button == 0 && panelToggleButton.mouseClicked(mouseX, mouseY, button);
            }
            capturedButton = button;
            if (button != 0) {
                return true;
            }
            if (panelToggleButton.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (beginDragging(mouseX, mouseY, button)) {
                return true;
            }
            if (beginScrollbarDragging(mouseX, mouseY, button)) {
                return true;
            }
            for (AE2Button panelButton : buttons) {
                if (panelButton.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
            int listTop = getY() + LIST_TOP;
            int listBottom = listBottom();
            int listLeft = getX() + LIST_SIDE;
            int listRight = getX() + getWidth() - LIST_SIDE;
            if (mouseX < listLeft || mouseX >= listRight || mouseY < listTop || mouseY >= listBottom) {
                return true;
            }

            List<ToolListPayload.Entry> entries = entries();
            int row = ((int) mouseY - listTop) / ROW_HEIGHT + scroll;
            if (row >= 0 && row < entries.size()) {
                ToolListPayload.Entry entry = entries.get(row);
                selected = entry.index();
                selectedKey = keyOf(entry);
                persistedSelectionKey = selectedKey;
                sendAction(PatternToolActionPayload.ACTION_SELECT, selected);
            } else {
                selected = -1;
                selectedKey = null;
                persistedSelectionKey = null;
            }
            return true;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button,
                                    double dragX, double dragY) {
            if (!terminalPanelEnabled) {
                return false;
            }
            if (capturedButton != button) {
                return false;
            }
            if (scrollbarDragging) {
                updateScrollFromMouse(mouseY);
                return true;
            }
            if (dragging) {
                continueDragging(mouseX, mouseY, button);
            }
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (!terminalPanelEnabled) {
                return panelToggleButton.mouseReleased(mouseX, mouseY, button);
            }
            if (capturedButton != button) {
                return false;
            }
            if (scrollbarDragging) {
                scrollbarDragging = false;
                capturedButton = -1;
                return true;
            }
            for (AE2Button panelButton : buttons) {
                panelButton.mouseReleased(mouseX, mouseY, button);
            }
            capturedButton = -1;
            if (button == 0) {
                endDragging(button);
            }
            return true;
        }

        private boolean beginDragging(double mouseX, double mouseY, int button) {
            if (!PatternCheckClient.getToolList().available() || button != 0
                    || mouseX < getX() || mouseX >= getX() + getWidth()
                    || mouseY < getY() || mouseY >= getY() + HEADER_HEIGHT) {
                return false;
            }
            dragging = true;
            dragOffsetX = (int) mouseX - getX();
            dragOffsetY = (int) mouseY - getY();
            return true;
        }

        private boolean continueDragging(double mouseX, double mouseY, int button) {
            if (!dragging || button != 0) {
                return false;
            }
            setX((int) mouseX - dragOffsetX);
            setY((int) mouseY - dragOffsetY);
            clampToScreen();
            moveButtons();
            saveOffsetFromPosition();
            return true;
        }

        private boolean endDragging(int button) {
            if (!dragging || button != 0) {
                return false;
            }
            dragging = false;
            return true;
        }

        private boolean beginScrollbarDragging(double mouseX, double mouseY, int button) {
            if (button != 0 || !terminalPanelEnabled) {
                return false;
            }
            int entryCount = entries().size();
            int visible = visibleRows();
            if (entryCount <= visible) {
                return false;
            }
            int listRight = getX() + getWidth() - LIST_SIDE;
            int trackTop = getY() + LIST_TOP + 2;
            int trackBottom = listBottom() - 2;
            int trackHeight = trackBottom - trackTop;
            int thumbHeight = Math.max(8, trackHeight * visible / entryCount);
            int maxScroll = Math.max(1, entryCount - visible);
            int thumbY = trackTop + scroll * Math.max(0, trackHeight - thumbHeight) / maxScroll;
            if (mouseX < listRight - 8 || mouseX > listRight + 2
                    || mouseY < trackTop || mouseY > trackBottom) {
                return false;
            }
            scrollbarDragging = true;
            scrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight
                    ? (int) mouseY - thumbY
                    : thumbHeight / 2;
            updateScrollFromMouse(mouseY);
            return true;
        }

        private void updateScrollFromMouse(double mouseY) {
            int entryCount = entries().size();
            int visible = visibleRows();
            int maxScroll = Math.max(0, entryCount - visible);
            if (maxScroll == 0) {
                scroll = 0;
                return;
            }
            int trackTop = getY() + LIST_TOP + 2;
            int trackBottom = listBottom() - 2;
            int trackHeight = trackBottom - trackTop;
            int thumbHeight = Math.max(8, trackHeight * visible / entryCount);
            int travel = Math.max(1, trackHeight - thumbHeight);
            int thumbY = Math.max(trackTop,
                    Math.min(trackTop + travel, (int) mouseY - scrollbarDragOffset));
            scroll = Math.max(0, Math.min(maxScroll,
                    Math.round((thumbY - trackTop) * maxScroll / (float) travel)));
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            if (!PatternCheckClient.getToolList().available() || !isInside(mouseX, mouseY)) {
                return false;
            }
            if (!terminalPanelEnabled) {
                return false;
            }
            int maxScroll = Math.max(0, entries().size() - visibleRows());
            int direction = verticalAmount > 0 ? -1 : verticalAmount < 0 ? 1 : 0;
            scroll = Math.max(0, Math.min(maxScroll, scroll + direction));
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            if (!syncRequested) {
                if (syncDelayFrames > 0) {
                    syncDelayFrames--;
                } else {
                    sendAction(PatternToolActionPayload.ACTION_SYNC, -1);
                    syncRequested = true;
                }
            }
            ToolListPayload polled = NetworkHandler.poll();
            if (polled != null) {
                PatternCheckClient.setToolList(polled);
                refreshEntries(polled);
                restoreSelection(revealRestoredSelection);
                revealRestoredSelection = false;
            }
            ToolListPayload payload = PatternCheckClient.getToolList();
            if (cachedEntries.isEmpty() && !payload.entries().isEmpty()) {
                refreshEntries(payload);
            }
            setButtonsVisible(payload.available());
            if (!payload.available()) {
                return;
            }
            if (!terminalPanelEnabled) {
                return;
            }
            updateButtons(payload);

            int x = getX();
            int y = getY();
            int width = getWidth();
            int height = getHeight();

            drawAePanel(gui, x, y, width, height);
            gui.drawString(minecraft().font, Component.translatable("patternchecker.menu.title"),
                    x + 8, y + 6, TEXT_COLOR, false);
            gui.drawString(minecraft().font, Component.literal("↔"),
                    x + width - 14, y + 5, DIM_TEXT_COLOR, false);

            renderList(gui, mouseX, mouseY);
            renderStatus(gui, payload);
        }

        private void refreshEntries(ToolListPayload payload) {
            // The server list is already indexed by entry number. Bucket by
            // the small category range instead of comparator-sorting thousands
            // of entries every time a terminal screen is initialized.
            Map<Integer, List<ToolListPayload.Entry>> buckets = new HashMap<>();
            for (ToolListPayload.Entry entry : payload.entries()) {
                buckets.computeIfAbsent(entry.category(), ignored -> new ArrayList<>()).add(entry);
            }
            List<Integer> categories = new ArrayList<>(buckets.keySet());
            categories.sort(Integer::compareTo);
            List<ToolListPayload.Entry> sorted = new ArrayList<>(payload.entries().size());
            for (Integer category : categories) {
                sorted.addAll(buckets.get(category));
            }
            cachedEntries = List.copyOf(sorted);
            Map<Integer, ToolListPayload.Entry> indexed = new HashMap<>();
            for (ToolListPayload.Entry entry : cachedEntries) {
                indexed.put(entry.index(), entry);
            }
            entriesByIndex = Map.copyOf(indexed);
        }

        private static EntryKey keyOf(ToolListPayload.Entry entry) {
            return new EntryKey(entry.location(), entry.slot());
        }

        private static int findMatchingRow(
                List<ToolListPayload.Entry> entries, EntryKey key) {
            if (key == null) {
                return -1;
            }
            for (int row = 0; row < entries.size(); row++) {
                ToolListPayload.Entry entry = entries.get(row);
                if (entry.location().equals(key.location()) && entry.slot() == key.slot()) {
                    return row;
                }
            }
            return -1;
        }

        private void restoreSelection(boolean reveal) {
            int row = findMatchingRow(cachedEntries, selectedKey);
            if (row < 0) {
                selected = -1;
                selectedKey = null;
                persistedSelectionKey = null;
                clampScroll();
                return;
            }
            selected = cachedEntries.get(row).index();
            if (reveal) {
                int visible = visibleRows();
                if (row < scroll) {
                    scroll = row;
                } else if (row >= scroll + visible) {
                    scroll = row - visible + 1;
                }
            }
            clampScroll();
        }

        private void clampScroll() {
            int maxScroll = Math.max(0, cachedEntries.size() - visibleRows());
            scroll = Math.max(0, Math.min(maxScroll, scroll));
        }

        private void renderOverlay(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            gui.pose().pushPose();
            gui.pose().translate(0.0F, 0.0F, OVERLAY_Z);
            renderWidget(gui, mouseX, mouseY, partialTick);
            if (PatternCheckClient.getToolList().available()) {
                updatePanelToggleButton();
                if (hasRenderableSize(panelToggleButton)) {
                    panelToggleButton.render(gui, mouseX, mouseY, partialTick);
                    gui.renderItem(PatternCheckerMod.PATTERN_CHECKER_TOOL.get().getDefaultInstance(),
                            panelToggleButton.getX() + 2, panelToggleButton.getY() + 2);
                }
                if (terminalPanelEnabled) {
                    for (AE2Button panelButton : buttons) {
                        if (hasRenderableSize(panelButton)) {
                            panelButton.render(gui, mouseX, mouseY, partialTick);
                        }
                    }
                }
            }
            if (terminalPanelEnabled) {
                renderSelectedIssueTooltip(gui, mouseX, mouseY);
            }
            gui.pose().popPose();
        }

        private static boolean hasRenderableSize(AbstractWidget widget) {
            return widget != null && widget.visible
                    && widget.getWidth() > 0 && widget.getHeight() > 0;
        }

        private void setButtonsVisible(boolean visible) {
            scanButton.visible = visible;
            unbindButton.visible = visible;
            inputButton.visible = visible;
            duplicateButton.visible = visible;
            highlightButton.visible = visible;
            editButton.visible = visible;
            extractButton.visible = visible;
            uploadButton.visible = visible;
            writeButton.visible = visible;
        }

        private void updatePanelToggleButton() {
            panelToggleButton.setMessage(Component.empty());
            panelToggleButton.visible = PatternCheckClient.getToolList().available();
        }

        private void setPanelExpanded(boolean expanded) {
            if (expanded) {
                setWidth(MODULE_WIDTH);
                setHeight(expandedHeight);
                setX(anchorX - (MODULE_WIDTH - COLLAPSED_SIZE) + savedOffsetX);
                setY(anchorY + COLLAPSED_SIZE - expandedHeight + savedOffsetY);
                setButtonsVisible(true);
            } else {
                setWidth(COLLAPSED_SIZE);
                setHeight(COLLAPSED_SIZE);
                setX(anchorX + savedOffsetX);
                setY(anchorY + savedOffsetY);
                setButtonsVisible(false);
            }
            clampToScreen();
            moveButtons();
            saveOffsetFromPosition();
        }

        /**
         * Persist the dragged position in the coordinate system of the
         * collapsed icon anchor. Expanded and collapsed panels have different
         * top-left origins, so saving getX()-anchorX directly makes the next
         * terminal screen recreate the panel at the wrong position.
         */
        private void saveOffsetFromPosition() {
            int baseX = terminalPanelEnabled
                    ? anchorX - (MODULE_WIDTH - COLLAPSED_SIZE)
                    : anchorX;
            int baseY = terminalPanelEnabled
                    ? anchorY + COLLAPSED_SIZE - expandedHeight
                    : anchorY;
            savedOffsetX = getX() - baseX;
            savedOffsetY = getY() - baseY;
        }

        private void clampToScreen() {
            int screenWidth = minecraft().getWindow().getGuiScaledWidth();
            int screenHeight = minecraft().getWindow().getGuiScaledHeight();
            setX(Math.max(0, Math.min(screenWidth - getWidth(), getX())));
            setY(Math.max(0, Math.min(screenHeight - getHeight(), getY())));
        }

        private void moveButtons() {
            int x = getX();
            int y = getY();
            int width = getWidth();
            int splitWidth = (width - OUTER_PADDING * 2 - BUTTON_GAP) / 2;
            scanButton.setX(x + OUTER_PADDING);
            scanButton.setY(y + TOP_BUTTON_Y);
            unbindButton.setX(x + OUTER_PADDING + splitWidth + BUTTON_GAP);
            unbindButton.setY(y + TOP_BUTTON_Y);
            inputButton.setX(x + OUTER_PADDING);
            inputButton.setY(y + TOGGLE_BUTTON_Y);
            duplicateButton.setX(x + OUTER_PADDING + splitWidth + BUTTON_GAP);
            duplicateButton.setY(y + TOGGLE_BUTTON_Y);
            // Keep the icon button exactly the same size as the collapsed
            // module and pin it to the module's top-right corner. In the
            // collapsed state this resolves to the whole 20x20 widget,
            // while in the expanded state it becomes the module's header
            // toggle without covering the title.
            panelToggleButton.setX(x + width - COLLAPSED_SIZE);
            panelToggleButton.setY(y);

            int available = width - OUTER_PADDING * 2 - BUTTON_GAP * (ACTION_BUTTONS - 1);
            int baseWidth = available / ACTION_BUTTONS;
            int remainder = available % ACTION_BUTTONS;
            AE2Button[] actions = {highlightButton, editButton, extractButton, uploadButton, writeButton};
            for (int i = 0; i < actions.length; i++) {
                int widthBefore = i * baseWidth + Math.min(i, remainder);
                actions[i].setX(x + OUTER_PADDING + i * BUTTON_GAP + widthBefore);
                actions[i].setY(actionButtonY());
            }
        }

        private void updateButtons(ToolListPayload payload) {
            scanButton.active = payload.bound();
            unbindButton.active = payload.bound();
            inputButton.setMessage(Component.translatable(
                    payload.inputIssues()
                            ? "patternchecker.menu.input.on"
                            : "patternchecker.menu.input.off"));
            duplicateButton.setMessage(Component.translatable(
                    payload.duplicateIssues()
                            ? "patternchecker.menu.duplicate.on"
                            : "patternchecker.menu.duplicate.off"));
            boolean canAct = hasSelectedProvider();
            highlightButton.active = canAct;
            editButton.active = canAct;
            extractButton.active = canAct;
            uploadButton.active = canAct;
            writeButton.active = canAct;
        }

        private void drawAePanel(GuiGraphics gui, int x, int y, int width, int height) {
            blitHorizontalSlice(gui, x, y, width, 0, HEADER_HEIGHT);

            // The terminal's row and bottom textures contain storage and
            // player-inventory slots. They are intentionally not tiled here:
            // this module has no inventory, so its body is the same clean AE2
            // panel material with only the outer bevel retained.
            gui.fill(x, y + HEADER_HEIGHT, x + width, y + height, PANEL_COLOR);
            gui.fill(x, y + HEADER_HEIGHT, x + 1, y + height, BORDER_COLOR);
            gui.fill(x + 1, y + HEADER_HEIGHT, x + 2, y + height - 1, PANEL_HIGHLIGHT_COLOR);
            gui.fill(x, y + height - 1, x + width, y + height, BORDER_COLOR);

            // AE2 owns the shared right edge, avoiding a double-width seam
            // between the tool module and the encoding terminal.
            gui.fill(x + width - 1, y + HEADER_HEIGHT, x + width, y + height - 1, PANEL_COLOR);
        }

        private void blitHorizontalSlice(GuiGraphics gui, int x, int y, int width,
                                         int sourceY, int height) {
            int leftCap = Math.min(4, width);
            int rightCap = Math.min(4, Math.max(0, width - leftCap));
            int centerWidth = Math.max(0, width - leftCap - rightCap);

            if (leftCap > 0) {
                gui.blit(PATTERN_TEXTURE, x, y, 0, sourceY,
                        leftCap, height, TEXTURE_SIZE, TEXTURE_SIZE);
            }
            if (centerWidth > 0) {
                gui.blit(PATTERN_TEXTURE, x + leftCap, y, centerWidth, height,
                        leftCap, sourceY, SOURCE_WIDTH - leftCap - rightCap, height,
                        TEXTURE_SIZE, TEXTURE_SIZE);
            }
            if (rightCap > 0) {
                gui.blit(PATTERN_TEXTURE, x + width - rightCap, y, SOURCE_WIDTH - rightCap, sourceY,
                        rightCap, height, TEXTURE_SIZE, TEXTURE_SIZE);
            }
        }

        private void renderList(GuiGraphics gui, int mouseX, int mouseY) {
            int x = getX();
            int width = getWidth();
            int listLeft = x + LIST_SIDE;
            int listRight = x + width - LIST_SIDE;
            int listTop = getY() + LIST_TOP;
            int listBottom = listBottom();
            int contentRight = listRight - 5;

            gui.fill(listLeft - 1, listTop - 1, listRight + 1, listBottom + 1, BORDER_COLOR);
            gui.fill(listLeft, listTop, listRight, listBottom, RECESS_COLOR);
            gui.enableScissor(listLeft, listTop, listRight, listBottom);

            List<ToolListPayload.Entry> entries = entries();
            int rows = visibleRows();
            if (entries.isEmpty()) {
                gui.drawString(minecraft().font, Component.translatable("patternchecker.menu.none"),
                        listLeft + 4, listTop + 5, DIM_TEXT_COLOR, false);
            } else {
                for (int i = scroll; i < Math.min(entries.size(), scroll + rows); i++) {
                    ToolListPayload.Entry entry = entries.get(i);
                    int rowY = listTop + (i - scroll) * ROW_HEIGHT;
                    boolean hovered = mouseX >= listLeft && mouseX < listRight
                            && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                    renderEntry(gui, entry, listLeft, contentRight, rowY, hovered);
                }
            }
            gui.disableScissor();

            renderScrollbar(gui, entries.size(), listRight, listTop, listBottom);
        }

        private void renderEntry(GuiGraphics gui, ToolListPayload.Entry entry,
                                 int left, int right, int y, boolean hovered) {
            boolean selected = entry.index() == this.selected;
            if (selected) {
                gui.fill(left, y, right + 4, y + ROW_HEIGHT, SELECT_BORDER_COLOR);
                gui.fill(left + 1, y + 1, right + 3, y + ROW_HEIGHT - 1, SELECT_COLOR);
            } else if (hovered) {
                gui.fill(left, y, right + 4, y + ROW_HEIGHT, 0xFFE0E1E6);
            }

            int severityColor = entry.error() ? ERROR_COLOR : WARNING_COLOR;
            gui.fill(left + 2, y + 2, left + 4, y + ROW_HEIGHT - 2, severityColor);

            ItemStack icon = iconFor(entry.itemId());
            int textX = left + 6;
            if (!icon.isEmpty()) {
                gui.renderItem(icon, left + 6, y + 3);
                textX = left + 25;
            }

            int textWidth = Math.max(12, right - textX);
            String output = entry.index() + ". " + entry.output().getString();
            gui.drawString(minecraft().font,
                    minecraft().font.plainSubstrByWidth(output, textWidth),
                    textX, y + 3, TEXT_COLOR, false);

            String location = entry.location();
            gui.drawString(minecraft().font,
                    minecraft().font.plainSubstrByWidth(location, textWidth),
                    textX, y + 13, DIM_TEXT_COLOR, false);
        }

        private void renderSelectedIssueTooltip(GuiGraphics gui, int mouseX, int mouseY) {
            ToolListPayload.Entry entry = entryAt(mouseX, mouseY);
            if (entry == null || entry.index() != selected) {
                return;
            }

            List<FormattedCharSequence> lines = new ArrayList<>();
            String output = entry.output().getString();
            String title = output.isEmpty() ? entry.name() : output;
            lines.add(Component.literal(entry.index() + ". " + title)
                    .withStyle(ChatFormatting.WHITE)
                    .getVisualOrderText());

            ChatFormatting severity = entry.error() ? ChatFormatting.RED : ChatFormatting.GOLD;
            Component issue = Component.translatable(
                            entry.error() ? "patternchecker.chat.error" : "patternchecker.chat.warning")
                    .append(Component.literal(" "))
                    .append(Component.literal(entry.summary()))
                    .withStyle(severity);
            lines.addAll(minecraft().font.split(issue, TOOLTIP_WIDTH));

            if (!entry.input().getString().isEmpty()) {
                lines.addAll(minecraft().font.split(
                        Component.translatable("patternchecker.menu.input", entry.input()),
                        TOOLTIP_WIDTH));
            }
            lines.addAll(minecraft().font.split(
                    Component.literal(entry.location()).withStyle(ChatFormatting.DARK_GRAY),
                    TOOLTIP_WIDTH));
            gui.renderTooltip(minecraft().font, lines, mouseX, mouseY);
        }

        private ToolListPayload.Entry entryAt(double mouseX, double mouseY) {
            int listLeft = getX() + LIST_SIDE;
            int listRight = getX() + getWidth() - LIST_SIDE;
            int listTop = getY() + LIST_TOP;
            int listBottom = listBottom();
            if (mouseX < listLeft || mouseX >= listRight || mouseY < listTop || mouseY >= listBottom) {
                return null;
            }
            List<ToolListPayload.Entry> entries = entries();
            int row = ((int) mouseY - listTop) / ROW_HEIGHT + scroll;
            return row >= 0 && row < entries.size() ? entries.get(row) : null;
        }

        private void renderScrollbar(GuiGraphics gui, int entryCount,
                                     int right, int top, int bottom) {
            int visibleRows = visibleRows();
            if (entryCount <= visibleRows) {
                return;
            }
            int trackTop = top + 2;
            int trackBottom = bottom - 2;
            int trackHeight = trackBottom - trackTop;
            int thumbHeight = Math.max(8, trackHeight * visibleRows / entryCount);
            int maxScroll = entryCount - visibleRows;
            int thumbY = trackTop + scroll * (trackHeight - thumbHeight) / maxScroll;

            gui.fill(right - 4, trackTop, right - 2, trackBottom, 0xFF85879A);
            gui.fill(right - 5, thumbY, right - 1, thumbY + thumbHeight, BORDER_COLOR);
            gui.fill(right - 4, thumbY + 1, right - 2, thumbY + thumbHeight - 1, PANEL_COLOR);
        }

        private void renderStatus(GuiGraphics gui, ToolListPayload payload) {
            int x = getX() + OUTER_PADDING;
            int maxWidth = getWidth() - OUTER_PADDING * 2;
            int y = actionButtonY() + BUTTON_HEIGHT + 3;

            Component status = payload.bound()
                    ? Component.translatable("patternchecker.bound.status", payload.boundLabel())
                    : Component.translatable("patternchecker.bound.notBound");
            gui.drawString(minecraft().font,
                    minecraft().font.plainSubstrByWidth(status.getString(), maxWidth),
                    x, y, payload.bound() ? ACCENT_COLOR : DIM_TEXT_COLOR, false);

            String secondLine;
            int secondLineColor;
            if (!payload.notice().getString().isEmpty()) {
                secondLine = payload.notice().getString();
                secondLineColor = ACCENT_COLOR;
            } else {
                ToolListPayload.Entry selected = selectedEntry();
                secondLine = selected != null
                        ? Component.translatable("patternchecker.menu.hoverHint").getString()
                        : Component.translatable("patternchecker.menu.selectHint").getString();
                secondLineColor = DIM_TEXT_COLOR;
            }
            gui.drawString(minecraft().font,
                    minecraft().font.plainSubstrByWidth(secondLine, maxWidth),
                    x, y + 10, secondLineColor, false);
        }

        private ItemStack iconFor(String itemId) {
            ItemStack cached = iconCache.get(itemId);
            if (cached != null) {
                return cached;
            }
            try {
                var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
                ItemStack icon = item == null ? ItemStack.EMPTY : new ItemStack(item);
                iconCache.put(itemId, icon);
                return icon;
            } catch (Exception ignored) {
                iconCache.put(itemId, ItemStack.EMPTY);
                return ItemStack.EMPTY;
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
        }
    }
}
