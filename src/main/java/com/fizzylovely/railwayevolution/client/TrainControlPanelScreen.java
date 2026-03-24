package com.fizzylovely.railwayevolution.client;

import com.fizzylovely.railwayevolution.ai.TrainState;
import com.fizzylovely.railwayevolution.network.ModNetwork;
import com.fizzylovely.railwayevolution.network.TrainCommandPacket;
import com.fizzylovely.railwayevolution.network.TrainInfo;
import com.fizzylovely.railwayevolution.network.OpenPanelRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * F10 Train Control Panel — shows all AI-tracked trains with controls.
 *
 * Layout (per-train row, height = ROW_H = 22 px):
 *   [!!] label  state  speed  [Chat: ON/OFF]  [AI: ON/OFF]  [Ack Crash]
 *
 * Left panel is 320 px wide, centered in the screen.
 * Mouse-wheel scrolls the list. Click buttons send C→S packets immediately.
 * A "Refresh" button at the bottom re-requests the train list from the server.
 */
@SuppressWarnings("null")
public class TrainControlPanelScreen extends Screen {

    // ─── Layout constants ───
    private static final int PANEL_W   = 340;
    private static final int HEADER_H  = 36;
    private static final int FOOTER_H  = 28;
    private static final int ROW_H     = 24;
    private static final int ROW_PAD   =  2;
    private static final int SCROLL_SPEED = ROW_H;

    // Colours (ARGB)
    private static final int COL_BG        = 0xD0101820;
    private static final int COL_HEADER    = 0xFF1A2A3A;
    private static final int COL_ROW_EVEN  = 0xD0182030;
    private static final int COL_ROW_ODD   = 0xD01A2438;
    private static final int COL_CRASH     = 0xFFCC2222;
    private static final int COL_BORDER    = 0xFF2A4060;
    private static final int COL_WHITE     = 0xFFFFFFFF;
    private static final int COL_YELLOW    = 0xFFFFDD00;
    @SuppressWarnings("unused") private static final int COL_GREEN = 0xFF44DD44;
    @SuppressWarnings("unused") private static final int COL_RED   = 0xFFDD4444;
    private static final int COL_GREY      = 0xFF888888;
    @SuppressWarnings("unused") private static final int COL_CYAN  = 0xFF44DDDD;

    private List<TrainInfo> trains = new ArrayList<>();
    private int scrollOffset = 0;   // pixels scrolled from top
    private int panelX, panelY, listY, listH;

    // Mute-all toggle (client-side only, sends individual toggle for each train)
    private boolean allMuted = false;

    public TrainControlPanelScreen(List<TrainInfo> trains) {
        super(Component.literal("Train Control Panel"));
        this.trains = new ArrayList<>(trains);
    }

    /** Called from TrainListPacket handler to refresh without closing the screen. */
    public void refreshData(List<TrainInfo> newTrains) {
        this.trains = new ArrayList<>(newTrains);
        rebuildButtons();
    }

    @Override
    protected void init() {
        super.init();
        panelX = (this.width  - PANEL_W) / 2;
        panelY = (this.height - computePanelH()) / 2;
        listY  = panelY + HEADER_H;
        listH  = computePanelH() - HEADER_H - FOOTER_H;
        rebuildButtons();
    }

    private int computePanelH() {
        int maxVisible = Math.min(trains.size(), 10);
        return HEADER_H + FOOTER_H + Math.max(1, maxVisible) * (ROW_H + ROW_PAD);
    }

    /** Remove old row buttons and re-create them for the current train list + scroll. */
    private void rebuildButtons() {
        clearWidgets();

        int panelH = computePanelH();
        panelY = (this.height - panelH) / 2;
        listY  = panelY + HEADER_H;
        listH  = panelH - HEADER_H - FOOTER_H;

        // Clamp scroll
        int totalH = trains.size() * (ROW_H + ROW_PAD);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, totalH - listH)));

        int firstRow = scrollOffset / (ROW_H + ROW_PAD);
        int visibleRows = listH / (ROW_H + ROW_PAD) + 2; // +2 for partial rows

        for (int i = firstRow; i < Math.min(firstRow + visibleRows, trains.size()); i++) {
            TrainInfo info  = trains.get(i);
            int rowY = listY + i * (ROW_H + ROW_PAD) - scrollOffset;
            if (rowY + ROW_H < listY || rowY > listY + listH) continue;

            // Button X positions
            int btnChatX = panelX + PANEL_W - 190;
            int btnAiX   = panelX + PANEL_W - 120;
            int btnAckX  = panelX + PANEL_W - 50;
            int btnW     = 62;
            int btnH     = ROW_H - 4;
            int btnY     = rowY + 2;

            final UUID trainId = info.id;
            final int rowIdx   = i;

            // [Chat ON/OFF]
            String chatLabel = info.chatSilenced ? "Chat: OFF" : "Chat: ON";
            addRenderableWidget(Button.builder(Component.literal(chatLabel), btn -> {
                ModNetwork.CHANNEL.sendToServer(new TrainCommandPacket(trainId, TrainCommandPacket.Command.TOGGLE_CHAT));
                // Optimistic local update
                TrainInfo cur = trains.get(rowIdx);
                trains.set(rowIdx, new TrainInfo(cur.id, cur.displayName, cur.state, cur.speed,
                        !cur.chatSilenced, cur.aiEnabled, cur.recentCollision));
                rebuildButtons();
            }).bounds(btnChatX, btnY, btnW, btnH).build());

            // [AI ON/OFF]
            String aiLabel = info.aiEnabled ? "AI: ON" : "AI: OFF";
            addRenderableWidget(Button.builder(Component.literal(aiLabel), btn -> {
                ModNetwork.CHANNEL.sendToServer(new TrainCommandPacket(trainId, TrainCommandPacket.Command.TOGGLE_AI));
                TrainInfo cur = trains.get(rowIdx);
                trains.set(rowIdx, new TrainInfo(cur.id, cur.displayName, cur.state, cur.speed,
                        cur.chatSilenced, !cur.aiEnabled, cur.recentCollision));
                rebuildButtons();
            }).bounds(btnAiX, btnY, btnW, btnH).build());

            // [Ack] button (only visible when crashed)
            if (info.recentCollision) {
                addRenderableWidget(Button.builder(Component.literal("Ack !!"), btn -> {
                    ModNetwork.CHANNEL.sendToServer(new TrainCommandPacket(trainId, TrainCommandPacket.Command.RESET_COLLISION));
                    TrainInfo cur = trains.get(rowIdx);
                    trains.set(rowIdx, new TrainInfo(cur.id, cur.displayName, cur.state, cur.speed,
                            cur.chatSilenced, cur.aiEnabled, false));
                    rebuildButtons();
                }).bounds(btnAckX, btnY, 44, btnH).build());
            }
        }

        // Footer buttons
        int footerY = panelY + computePanelH() - FOOTER_H + 4;
        int btnMuteAllX = panelX + 8;
        addRenderableWidget(Button.builder(
                Component.literal(allMuted ? "Unmute All" : "Mute All"),
                btn -> toggleAllMute()
        ).bounds(btnMuteAllX, footerY, 90, 20).build());

        int btnRefreshX = panelX + PANEL_W - 98;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), btn -> {
            ModNetwork.CHANNEL.sendToServer(new OpenPanelRequestPacket());
        }).bounds(btnRefreshX, footerY, 60, 20).build());

        int btnCloseX = panelX + PANEL_W - 34;
        addRenderableWidget(Button.builder(Component.literal("X"), btn -> onClose())
                .bounds(btnCloseX, footerY, 26, 20).build());
    }

    private void toggleAllMute() {
        // Toggle each train that doesn't already match the target state
        boolean target = !allMuted; // we want this new state
        for (TrainInfo info : trains) {
            if (info.chatSilenced != target) {
                ModNetwork.CHANNEL.sendToServer(new TrainCommandPacket(info.id, TrainCommandPacket.Command.TOGGLE_CHAT));
            }
        }
        allMuted = target;
        // Optimistic update
        List<TrainInfo> updated = new ArrayList<>();
        for (TrainInfo info : trains) {
            updated.add(new TrainInfo(info.id, info.displayName, info.state, info.speed,
                    target, info.aiEnabled, info.recentCollision));
        }
        trains = updated;
        rebuildButtons();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        // Semi-transparent background for whole screen
        renderBackground(g);

        int panelH = computePanelH();

        // Panel background
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, COL_BG);
        // Border
        g.hLine(panelX, panelX + PANEL_W - 1, panelY,             COL_BORDER);
        g.hLine(panelX, panelX + PANEL_W - 1, panelY + panelH - 1, COL_BORDER);
        g.vLine(panelX,             panelY, panelY + panelH - 1, COL_BORDER);
        g.vLine(panelX + PANEL_W - 1, panelY, panelY + panelH - 1, COL_BORDER);

        // Header
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + HEADER_H, COL_HEADER);
        g.drawString(font, "§b⬛ Train Control Panel", panelX + 8, panelY + 6, COL_WHITE, false);
        g.drawString(font, "§7" + trains.size() + " trains tracked  |  F10 to close",
                panelX + 8, panelY + 20, COL_GREY, false);

        // Column headers
        int colHdrY = panelY + HEADER_H - 12;
        g.drawString(font, "§7Train / State / Speed", panelX + 8, colHdrY, COL_GREY, false);
        g.drawString(font, "§7Chat    AI     Ack",
                panelX + PANEL_W - 190, colHdrY, COL_GREY, false);

        // Enable scissor (clip rows to list area)
        g.enableScissor(panelX, listY, panelX + PANEL_W, listY + listH);

        int firstRow = scrollOffset / (ROW_H + ROW_PAD);
        int totalRows = trains.size();
        int visibleRows = listH / (ROW_H + ROW_PAD) + 2;

        for (int i = firstRow; i < Math.min(firstRow + visibleRows, totalRows); i++) {
            TrainInfo info = trains.get(i);
            int rowY = listY + i * (ROW_H + ROW_PAD) - scrollOffset;

            // Row background
            int bgColor = (i % 2 == 0) ? COL_ROW_EVEN : COL_ROW_ODD;
            if (info.recentCollision) bgColor = blendColor(bgColor, COL_CRASH, 0.25f);
            g.fill(panelX + 1, rowY, panelX + PANEL_W - 1, rowY + ROW_H, bgColor);

            // Collision icon
            if (info.recentCollision) {
                g.drawString(font, "§c!!", panelX + 4, rowY + 7, COL_CRASH, false);
            }

            // Train label
            String label = info.label();
            g.drawString(font, "§e" + label, panelX + 18, rowY + 4, COL_YELLOW, false);

            // State badge
            String stateStr = abbreviate(info.state) + (info.aiEnabled ? "" : "§8[off]");
            g.drawString(font, stateColor(info.state) + stateStr, panelX + 18, rowY + 13, COL_WHITE, false);

            // Speed
            String spd = String.format("§7%.1f b/t", info.speed);
            int spdW = font.width(spd);
            g.drawString(font, spd, panelX + PANEL_W - 192 - spdW - 4, rowY + 8, COL_WHITE, false);
        }

        g.disableScissor();

        // Scroll bar (only if content overflows)
        int totalH = totalRows * (ROW_H + ROW_PAD);
        if (totalH > listH) {
            int scrollBarH   = Math.max(16, listH * listH / totalH);
            int scrollBarY   = listY + scrollOffset * (listH - scrollBarH) / Math.max(1, totalH - listH);
            int scrollBarX   = panelX + PANEL_W - 6;
            g.fill(scrollBarX, listY, scrollBarX + 4,      listY + listH,     0x40FFFFFF);
            g.fill(scrollBarX, scrollBarY, scrollBarX + 4, scrollBarY + scrollBarH, 0xBBAABBCC);
        }

        // Footer separator
        g.hLine(panelX, panelX + PANEL_W - 1, panelY + panelH - FOOTER_H, COL_BORDER);

        // Render buttons
        super.render(g, mx, my, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scrollOffset -= (int)(delta * SCROLL_SPEED);
        int totalH = trains.size() * (ROW_H + ROW_PAD);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, totalH - listH)));
        rebuildButtons();
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ─── Helpers ───

    private static String stateColor(TrainState s) {
        return switch (s) {
            case CRUISING            -> "§a";
            case ANALYZING_OBSTACLE  -> "§e";
            case YIELDING            -> "§c";
            case BYPASSING           -> "§d";
            case RETURNING           -> "§b";
            case REVERSING           -> "§6";
            case TRAFFIC_JAM         -> "§4";
            case WAIT_FOR_CLEARANCE  -> "§5";
        };
    }

    private static String abbreviate(TrainState s) {
        return switch (s) {
            case CRUISING            -> "CRS";
            case ANALYZING_OBSTACLE  -> "ANA";
            case BYPASSING           -> "BYP";
            case YIELDING            -> "YLD";
            case RETURNING           -> "RET";
            case REVERSING           -> "REV";
            case TRAFFIC_JAM         -> "JAM";
            case WAIT_FOR_CLEARANCE  -> "WFC";
        };
    }

    /** Linear blend two ARGB colours. t=0 → a, t=1 → b. */
    private static int blendColor(int a, int b, float t) {
        int aa = (a >> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int)(aa + (ba - aa) * t);
        int rr = (int)(ar + (br - ar) * t);
        int rg = (int)(ag + (bg - ag) * t);
        int rb = (int)(ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}
