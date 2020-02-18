package sevts.terminal.tripod.task;

import java.io.IOException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sevts.terminal.tripod.DriverTripod;
import sevts.terminal.tripod.TripodException;
import sevts.terminal.tripod.TripodStatus;


public abstract class AbstractTask implements Task {
    private static Logger logger = LoggerFactory.getLogger(AbstractTask.class.getName());
    protected DriverTripod driverTripod;
    protected TripodStatus tripodStatus;
    protected final int responseSize;
    protected TaskResponseReader taskResponseReader;

    public AbstractTask(DriverTripod driverTripod, int responseSize) throws IOException {
        this.driverTripod = driverTripod;
        this.responseSize = responseSize;
        this.taskResponseReader = new TaskResponseReader();
    }

    public void setTripodStatus(TripodStatus tripodStatus) {
        this.tripodStatus = tripodStatus;
    }

    public int[] readResponse(int[] answer) throws TripodException {
        logger.info("response from tripod " + Arrays.toString(answer));
        return this.taskResponseReader.readResponse(answer, this.responseSize);
    }

    public int[] readResponse(int[] answer, boolean getAnswer) throws TripodException {
        logger.info("response from tripod " + Arrays.toString(answer));
        return this.taskResponseReader.readResponse(answer, this.responseSize, getAnswer);
    }

    protected void delay(int time) {
        try {
            Thread.sleep((long)time);
        } catch (InterruptedException var3) {
            logger.error(var3.getMessage());
        }

    }

    public void setTaskResponseReader(TaskResponseReader taskResponseReader) {
        this.taskResponseReader = taskResponseReader;
    }
}
