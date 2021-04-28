package sevts.terminal.tripod;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sevts.terminal.tripod.task.CheckStatusTask;
import sevts.terminal.tripod.task.OpenDoorTask;
import sevts.terminal.tripod.task.ResetTask;


public class BravoTripodImpl implements BravoTripod {
    protected SerialPort serialPort;
    protected DriverTripod driverTripod;
    protected OpenDoorTask openDoorTask;
    protected ResetTask resetTask;
    protected CheckStatusTask checkStatusTask;
    protected AtomicBoolean isInit = new AtomicBoolean(false);

    private static Logger logger = LoggerFactory.getLogger(BravoTripodImpl.class.getName());

    public BravoTripodImpl() {
    }

    public void connect(String portIdentifier) throws IOException {
        try {

            this.serialPort = SerialPort.getCommPorts()[Integer.parseInt(portIdentifier)];//SerialPort.getCommPort(portIdentifier);
            serialPort.setBaudRate(9600);
            serialPort.setComPortParameters(9600, 8, 1, 2);
            boolean isOpen = serialPort.openPort();
            if(!isOpen) { throw new IOException("Cannot open Tripod serial port: " + portIdentifier); }
            this.driverTripod = new DriverTripod(new BufferedInputStream(this.serialPort.getInputStream()), this.serialPort.getOutputStream());
            this.openDoorTask = new OpenDoorTask(this.driverTripod);
            this.resetTask = new ResetTask(this.driverTripod);
            this.checkStatusTask = new CheckStatusTask(this.driverTripod);
            this.isInit.set(true);
        } catch (Exception var4) {
            throw new IOException(var4.getMessage(), var4);
        }
    }

    public void disconnect() {
        if (this.serialPort != null) {
            this.isInit.set(false);
            this.serialPort.closePort();
        }

    }

    public synchronized Void setDoorStatus(TripodStatus mode) throws IOException, TripodException {
        this.checkInit();
        this.openDoorTask.setTripodStatus(mode);
        this.openDoorTask.execute();
        return null;
    }

    public synchronized void reset() throws IOException, TripodException {
        this.checkInit();
        this.resetTask.execute();
    }

    public void checkTripodStatus() throws IOException, TripodException {
        this.checkInit();
        this.checkStatusTask.execute();
    }

    protected void checkInit() throws TripodException {
        if (!this.isInit.get()) {
            throw new TripodException("Tripod does not init, try to restart");
        }
    }
}
