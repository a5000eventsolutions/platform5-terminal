package sevts.terminal.tripod;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TripodController {
    private static Logger logger = LoggerFactory.getLogger(TripodController.class.getName());

    private TripodProperties tripodProperties;
    private BravoTripod driver;
    private DoorStatusMonitor doorStatusMonitor;
    //private ConcurrentHashMap<DirectionType, DirectionType> directionRFID;
    private AtomicReference<DirectionType> directionType;

    public TripodController(TripodProperties tripodProperties, BravoTripod bravoTripod) {
        this.tripodProperties = tripodProperties;
        this.driver = bravoTripod;
        this.init();
    }

    public TripodController(BravoTripod bravoTripod) {
        this.driver = bravoTripod;
    }

    @PostConstruct
    private void init() {
        this.directionType = new AtomicReference(this.tripodProperties.getTripodDirection());
        this.doorStatusMonitor = new DoorStatusMonitor();
//        this.directionRFID = new ConcurrentHashMap(2);
//        if (this.tripodProperties.getRFIDDirection() == DirectionType.ENTER) {
//            this.directionRFID.put(DirectionType.ENTER, DirectionType.ENTER);
//            this.directionRFID.put(DirectionType.EXIT, DirectionType.EXIT);
//        } else {
//            this.directionRFID.put(DirectionType.ENTER, DirectionType.EXIT);
//            this.directionRFID.put(DirectionType.EXIT, DirectionType.ENTER);
//        }

    }

    public void start() throws IOException {
        logger.info("Starting tripod device...");
        this.startTripod();
        logger.info("Tripod start success");
    }

    public void restart() throws IOException {
        logger.info("Restarting tripod device...");
        if (this.driver != null) {
            this.driver.disconnect();
        }

        this.startTripod();
        logger.info("Tripod start success");
    }

    protected void startTripod() throws IOException {
        this.driver.connect(this.tripodProperties.getTripodPort());
    }

    public BravoTripod getDriver() {
        return this.driver;
    }

    public void setDoorStatus(TripodStatus status) throws TripodException {
        status = TripodStatus.revertByDirectionType(status, this.getDirectionType());

        try {
            this.driver.setDoorStatus(this.doorStatusMonitor.checkServerCommand(status));
        } catch (IOException var3) {
            throw new TripodException(var3.getMessage(), var3.getCause());
        }
    }

    public boolean setDoorStatus(TripodStatus status, int numberOfTry){//, RequestForServer requestForServer) {
        try {
            this.setDoorStatus(status);
        } catch (TripodException error) {
            logger.error(error.getMessage());
            if (numberOfTry > 0) {
                --numberOfTry;
                this.setDoorStatus(status, numberOfTry);
            } else {
                this.setValidatorState(ValidatorState.ERROR);
               // requestForServer.handleError(error.getMessage());
            }
        }

        return true;
    }

    public TripodProperties getTripodProperties() {
        return this.tripodProperties;
    }

    public ValidatorState getValidatorState(DirectionType type) {
        return this.doorStatusMonitor.getValidatorState(type);
    }

    public void setValidatorState(ValidatorState state) {
        this.doorStatusMonitor.setStatusToAllDirections(state);
    }

    public DirectionType getDirectionType() {
        return (DirectionType)this.directionType.get();
    }

//    public void setDirectionType(DirectionType directionType) {
//        this.directionType.set(directionType);
//    }
//
//    public DirectionType getRFIDDirection(DirectionType key) {
//        return (DirectionType)this.directionRFID.get(key);
//    }
//
//    public void setRFIDDirection(DirectionType key, DirectionType value) {
//        this.directionRFID.put(key, value);
//    }
}
