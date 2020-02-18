package sevts.terminal.tripod.task;

import sevts.terminal.tripod.DriverTripod;
import sevts.terminal.tripod.TripodException;
import sevts.terminal.tripod.TripodStatus;

import java.io.IOException;


public class OpenDoorTask extends AbstractTask {
    public OpenDoorTask(DriverTripod driverTripod) throws IOException {
        super(driverTripod, 7);
    }

    public TaskResult execute() throws TripodException, IOException {
        switch(this.tripodStatus) {
            case TWO_WAY:
                this.driverTripod.writeToTripod(205, new int[]{TripodStatus.ENTER_ALWAYS.mode()});
                this.delay(50);
                this.readResponse(this.driverTripod.readFromTripod());
                this.delay(50);
                this.driverTripod.writeToTripod(205, new int[]{TripodStatus.EXIT_ALWAYS.mode()});
                this.delay(50);
                this.readResponse(this.driverTripod.readFromTripod());
                break;
            case ENTER_ALWAYS:
                this.driverTripod.writeToTripod(205, new int[]{this.tripodStatus.mode()});
                this.delay(50);
                this.readResponse(this.driverTripod.readFromTripod());
                break;
            case EXIT_ALWAYS:
                this.driverTripod.writeToTripod(205, new int[]{this.tripodStatus.mode()});
                this.delay(50);
                this.readResponse(this.driverTripod.readFromTripod());
                break;
            case ENTER:
                this.driverTripod.writeToTripod(205, new int[]{this.tripodStatus.mode()});
                this.delay(50);
                this.readResponse(this.driverTripod.readFromTripod());
                break;
            case EXIT:
                this.driverTripod.writeToTripod(205, new int[]{this.tripodStatus.mode()});
                this.delay(50);
                this.readResponse(this.driverTripod.readFromTripod());
                break;
            case CLOSE:
                this.driverTripod.writeToTripod(205, new int[]{this.tripodStatus.mode()});
                this.delay(50);
                this.readResponse(this.driverTripod.readFromTripod());
        }

        return new TaskResult(true);
    }
}
