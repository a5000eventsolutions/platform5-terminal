package sevts.terminal.tripod;

import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    public BravoTripodImpl() {
    }

    public void connect(String portIdentifier) throws IOException {
        try {
            CommPortIdentifier idf = CommPortIdentifier.getPortIdentifier(portIdentifier);
            this.serialPort = (SerialPort)idf.open(TripodController.class.getCanonicalName(), 30000);
            this.serialPort.setSerialPortParams(9600, 8, 1, 2);
            this.driverTripod = new DriverTripod(new BufferedInputStream(this.serialPort.getInputStream()), this.serialPort.getOutputStream());
            this.openDoorTask = new OpenDoorTask(this.driverTripod);
            this.resetTask = new ResetTask(this.driverTripod);
            this.checkStatusTask = new CheckStatusTask(this.driverTripod);
            this.isInit.set(true);
        } catch (NoSuchPortException var3) {
            throw new IOException("No such port exception, port = " + portIdentifier, var3);
        } catch (UnsupportedCommOperationException var4) {
            throw new IOException(var4.getMessage(), var4);
        } catch (PortInUseException var5) {
            throw new IOException(var5.getMessage(), var5);
        }
    }

    public void disconnect() {
        if (this.serialPort != null) {
            this.isInit.set(false);
            this.serialPort.close();
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
