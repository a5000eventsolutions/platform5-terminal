package sevts.terminal.tripod;

public enum DirectionType {
    ENTER(1),
    EXIT(2);

    private final int direction;

    private DirectionType(int direct) {
        this.direction = direct;
    }

    public static DirectionType byCode(int code) {
        DirectionType[] arr$ = values();
        int len$ = arr$.length;

        for(int i$ = 0; i$ < len$; ++i$) {
            DirectionType value = arr$[i$];
            if (value.direction == code) {
                return value;
            }
        }

        return null;
    }

    public static DirectionType changeType(DirectionType directionType) {
        return directionType == ENTER ? EXIT : ENTER;
    }

    public int getDirection() {
        return this.direction;
    }
}
