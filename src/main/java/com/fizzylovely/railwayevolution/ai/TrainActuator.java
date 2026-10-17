package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.ai.core.ITrainGraph;
import com.fizzylovely.railwayevolution.ai.core.ITrainHandle;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Compact command boundary between the decision core and Create's train adapter.
 * Read operations are delegated unchanged; all speed, brake, throttle and
 * navigation commands pass through this class.
 */
public final class TrainActuator implements ITrainHandle {
    private final ITrainHandle train;

    public TrainActuator(ITrainHandle train) {
        this.train = train;
    }

    public ITrainHandle train() { return train; }

    @Override
    public void setSpeed(double speed) {
        if (!Double.isFinite(speed)) {
            train.forceStop();
            return;
        }
        double maxSpeed = Math.abs(train.getMaxSpeed());
        if (Double.isFinite(maxSpeed)) {
            speed = Math.max(-maxSpeed, Math.min(maxSpeed, speed));
        }
        train.setSpeed(speed);
    }

    @Override
    public void setThrottle(double throttle) {
        train.setThrottle(Double.isFinite(throttle)
                ? Math.max(0.0, Math.min(1.0, throttle))
                : 0.0);
    }

    @Override public void forceStop() { train.forceStop(); }
    @Override public void resumeNavigation() { train.resumeNavigation(); }
    @Override public void cancelNavigation() { train.cancelNavigation(); }
    @Override public void restoreFullThrottle() { train.restoreFullThrottle(); }

    @Override public UUID getId() { return train.getId(); }
    @Override public String getDisplayName() { return train.getDisplayName(); }
    @Override public double getSpeed() { return train.getSpeed(); }
    @Override public double getMaxSpeed() { return train.getMaxSpeed(); }
    @Override public double getAcceleration() { return train.getAcceleration(); }
    @Override public double getThrottle() { return train.getThrottle(); }
    @Override public boolean isDerailed() { return train.isDerailed(); }
    @Override public boolean isBlocked() { return train.isBlocked(); }
    @Override public boolean isPlayerControlled() { return train.isPlayerControlled(); }
    @Override public @Nullable UUID getControllingPlayerUUID() { return train.getControllingPlayerUUID(); }
    @Override public double getDistanceToDestination() { return train.getDistanceToDestination(); }
    @Override public boolean hasDestination() { return train.hasDestination(); }
    @Override public boolean isWaitingForSignal() { return train.isWaitingForSignal(); }
    @Override public double getDistanceToSignal() { return train.getDistanceToSignal(); }
    @Override public boolean isOnGraph() { return train.isOnGraph(); }
    @Override public @Nullable Vec3 getLeadingPosition() { return train.getLeadingPosition(); }
    @Override public @Nullable Vec3 getTrailingPosition() { return train.getTrailingPosition(); }
    @Override public List<Vec3> getAllBogeyPositions() { return train.getAllBogeyPositions(); }
    @Override public double getTotalLength() { return train.getTotalLength(); }
    @Override public @Nullable ITrainGraph getGraph() { return train.getGraph(); }
    @Override public @Nullable Object getLeadingEdgeRef() { return train.getLeadingEdgeRef(); }
    @Override public @Nullable Object getLeadingNode1Ref() { return train.getLeadingNode1Ref(); }
    @Override public @Nullable Object getLeadingNode2Ref() { return train.getLeadingNode2Ref(); }
    @Override public double getLeadingEdgePosition() { return train.getLeadingEdgePosition(); }
    @Override public @Nullable Object getTrailingEdgeRef() { return train.getTrailingEdgeRef(); }
    @Override public @Nullable Object getTrailingNode1Ref() { return train.getTrailingNode1Ref(); }
    @Override public @Nullable Object getTrailingNode2Ref() { return train.getTrailingNode2Ref(); }
    @Override public double getTrailingEdgePosition() { return train.getTrailingEdgePosition(); }
    @Override public List<UUID> getOccupiedSignalGroups() { return train.getOccupiedSignalGroups(); }
}
