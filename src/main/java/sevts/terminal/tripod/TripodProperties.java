package sevts.terminal.tripod;

import sevts.terminal.config.Settings;
import java.io.IOException;


public class TripodProperties {

    //private Properties properties;
    Settings.TripodConfig settings;

    public TripodProperties() {
    }

    public TripodProperties(Settings settings) throws IOException {
        this.settings = settings.tripod();
    }

    public DirectionType getTripodDirection() {
        return DirectionType.valueOf(settings.direction());
    }


    public String getTripodPort() {
        return settings.port();
    }
}
