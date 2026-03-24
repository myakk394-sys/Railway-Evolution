package com.fizzylovely.railwayevolution.network;

import com.fizzylovely.railwayevolution.ai.TrainState;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Serializable snapshot of one train's status, sent from server → client
 * for the F10 control panel GUI.
 */
public class TrainInfo {

    public final UUID    id;
    public final String  displayName;   // From Create Train.name, or shortened UUID
    public final TrainState state;
    public final double  speed;
    public final boolean chatSilenced;
    public final boolean aiEnabled;
    public final boolean recentCollision;

    public TrainInfo(UUID id, String displayName, TrainState state,
                     double speed, boolean chatSilenced, boolean aiEnabled,
                     boolean recentCollision) {
        this.id              = id;
        this.displayName     = displayName;
        this.state           = state;
        this.speed           = speed;
        this.chatSilenced    = chatSilenced;
        this.aiEnabled       = aiEnabled;
        this.recentCollision = recentCollision;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(displayName, 64);
        buf.writeEnum(state);
        buf.writeDouble(speed);
        buf.writeBoolean(chatSilenced);
        buf.writeBoolean(aiEnabled);
        buf.writeBoolean(recentCollision);
    }

    public static TrainInfo decode(FriendlyByteBuf buf) {
        UUID id              = buf.readUUID();
        String displayName   = buf.readUtf(64);
        TrainState state     = buf.readEnum(TrainState.class);
        double speed         = buf.readDouble();
        boolean chatSilenced = buf.readBoolean();
        boolean aiEnabled    = buf.readBoolean();
        boolean recentCollision = buf.readBoolean();
        return new TrainInfo(id, displayName, state, speed, chatSilenced, aiEnabled, recentCollision);
    }

    /** Friendly short label: displayName if non-empty, otherwise first 8 chars of UUID. */
    public String label() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        return id.toString().substring(0, 8);
    }
}
