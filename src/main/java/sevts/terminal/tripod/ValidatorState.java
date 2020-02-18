package sevts.terminal.tripod;

public enum ValidatorState {
    OPEN(0),
    UID(1),
    BLOCK(2),
    OFFLINE(10),
    ERROR(11);

    private final int stat;

    private ValidatorState(int stat) {
        this.stat = stat;
    }

    public int mode() {
        return this.stat;
    }
}
