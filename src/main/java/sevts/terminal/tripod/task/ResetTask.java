package sevts.terminal.tripod.task;

import sevts.terminal.tripod.DriverTripod;
import sevts.terminal.tripod.TripodException;
import java.io.IOException;


public class ResetTask extends AbstractTask {
    public ResetTask(DriverTripod driverTripod) throws IOException {
        super(driverTripod, 7);
    }

    public TaskResult execute() throws TripodException, IOException {
        this.driverTripod.writeToTripod(207, new int[0]);
        this.delay(50);
        this.readResponse(this.driverTripod.readFromTripod());
        return new TaskResult(true);
    }
}
