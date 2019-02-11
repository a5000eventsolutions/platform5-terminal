package sevts.terminal.tripod;

import java.util.concurrent.ConcurrentHashMap;

public class DoorStatusMonitor {
    protected ConcurrentHashMap<DirectionType, ValidatorState> directions = new ConcurrentHashMap(2);

    public DoorStatusMonitor() {
        this.directions.put(DirectionType.ENTER, ValidatorState.UID);
        this.directions.put(DirectionType.EXIT, ValidatorState.UID);
    }

    public ValidatorState getValidatorState(DirectionType type) {
        return (ValidatorState)this.directions.get(type);
    }

    public ConcurrentHashMap<DirectionType, ValidatorState> getDirections() {
        return this.directions;
    }

    public TripodStatus checkServerCommand(TripodStatus status) {
        switch(status) {
            case ENTER:
                this.directions.put(DirectionType.ENTER, ValidatorState.UID);
                break;
            case ENTER_ALWAYS:
                this.directions.put(DirectionType.ENTER, ValidatorState.OPEN);
                break;
            case EXIT_ALWAYS:
                this.directions.put(DirectionType.EXIT, ValidatorState.OPEN);
                break;
            case EXIT:
                this.directions.put(DirectionType.EXIT, ValidatorState.UID);
                break;
            case TWO_WAY:
                this.setStatusToAllDirections(ValidatorState.OPEN);
                break;
            case CLOSE:
                this.setStatusToAllDirections(ValidatorState.UID);
                break;
            case BLOCK:
                this.setStatusToAllDirections(ValidatorState.BLOCK);
                return TripodStatus.CLOSE;
        }

        return status;
    }

    public void setStatusToAllDirections(ValidatorState state) {
        this.directions.put(DirectionType.ENTER, state);
        this.directions.put(DirectionType.EXIT, state);
    }
}
