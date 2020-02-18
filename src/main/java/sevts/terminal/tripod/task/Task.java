package sevts.terminal.tripod.task;

import sevts.terminal.tripod.TripodException;
import sevts.terminal.tripod.TripodStatus;

import java.io.IOException;


public interface Task {
    TaskResult execute() throws TripodException, IOException;

    void setTripodStatus(TripodStatus var1);

    int[] readResponse(int[] var1, boolean var2) throws TripodException;

    int[] readResponse(int[] var1) throws TripodException;

    void setTaskResponseReader(TaskResponseReader var1);
}
