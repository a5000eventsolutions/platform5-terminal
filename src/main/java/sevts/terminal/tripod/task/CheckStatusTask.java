package sevts.terminal.tripod.task;

import sevts.terminal.tripod.DriverTripod;
import sevts.terminal.tripod.TripodException;

import java.io.IOException;

public class CheckStatusTask extends AbstractTask {
    public CheckStatusTask(DriverTripod driverTripod) throws IOException {
        super(driverTripod, 8);
    }

    public TaskResult execute() throws TripodException, IOException {
        this.driverTripod.writeToTripod(195, new int[0]);
        this.delay(50);
        int[] answer = this.readResponse(this.driverTripod.readFromTripod(), true);
        return new TaskResult(true, answer);
    }
}
