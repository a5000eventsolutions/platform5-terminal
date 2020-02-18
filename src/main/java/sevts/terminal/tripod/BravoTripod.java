package sevts.terminal.tripod;


import java.io.IOException;


public interface BravoTripod {
    void connect(String var1) throws IOException;

    void disconnect();

    Void setDoorStatus(TripodStatus mode) throws IOException, TripodException;

    void reset() throws IOException, TripodException;

    void checkTripodStatus() throws IOException, TripodException;
}


