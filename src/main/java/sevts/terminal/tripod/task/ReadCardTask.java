package sevts.terminal.tripod.task;


import sevts.terminal.tripod.DriverTripod;
import sevts.terminal.tripod.TripodException;
import java.io.IOException;


public class ReadCardTask extends AbstractTask {
    public ReadCardTask(DriverTripod driverTripod) throws IOException {
        super(driverTripod, 14);
    }

    public TaskResult execute() throws TripodException, IOException {
        int[] answer = null;
        switch(this.tripodStatus) {
            case ENTER:
                this.driverTripod.writeToTripod(196, new int[]{this.tripodStatus.mode()});
                this.delay(50);
                answer = this.readResponse(this.driverTripod.readFromTripod(), true);
                break;
            case EXIT:
                this.driverTripod.writeToTripod(206, new int[]{this.tripodStatus.mode()});
                this.delay(50);
                answer = this.readResponse(this.driverTripod.readFromTripod(), true);
                break;
            default:
                throw new TripodException("Unexpected status");
        }

        return new TaskResult(true, answer);
    }
}
