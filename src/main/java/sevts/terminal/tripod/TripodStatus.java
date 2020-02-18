package sevts.terminal.tripod;

public enum TripodStatus {
    ENTER(1),
    EXIT(2),
    ENTER_ALWAYS(4),
    EXIT_ALWAYS(8),
    TWO_WAY(10),
    CLOSE(16),
    BLOCK(20);

    private final int mode;

    private TripodStatus(int mode) {
        this.mode = mode;
    }

    public int mode() {
        return this.mode;
    }

    public static TripodStatus getTripodStatusByDirectionType(DirectionType type) {
        return DirectionType.ENTER == type ? ENTER : EXIT;
    }

    public static TripodStatus revertByDirectionType(TripodStatus status, DirectionType type) {
        return type == DirectionType.ENTER ? status : revert(status);
    }

    public static TripodStatus revert(TripodStatus status) {
        switch(status) {
            case ENTER:
                return EXIT;
            case ENTER_ALWAYS:
                return EXIT_ALWAYS;
            case EXIT:
                return ENTER;
            case EXIT_ALWAYS:
                return ENTER_ALWAYS;
            default:
                return status;
        }
    }

    public static TripodStatus byStatus(int status) {
        TripodStatus[] arr$ = values();
        int len$ = arr$.length;

        for(int i$ = 0; i$ < len$; ++i$) {
            TripodStatus value = arr$[i$];
            if (value.mode == status) {
                return value;
            }
        }

        return null;
    }
}
