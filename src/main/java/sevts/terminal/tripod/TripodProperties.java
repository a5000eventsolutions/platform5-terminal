package sevts.terminal.tripod;

import sevts.terminal.config.Settings;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

public class TripodProperties {

    //private Properties properties;
    Settings.TripodConfig settings;

    public TripodProperties() {
    }

    public TripodProperties(Settings settings) throws IOException {
//        this.properties = new Properties();
//        File file = new File(propertiesPath);
//        if (file.exists()) {
//            this.properties.load(new FileReader(file));
//        } else {
//            this.properties.load(this.getClass().getClassLoader().getResourceAsStream(propertiesPath));
//        }
        this.settings = settings.tripod();
    }

    public DirectionType getTripodDirection() {
       // return DirectionType.valueOf(this.properties.getProperty("a5000.acs.devices.tripodDirection", "Enter").toUpperCase());
        return DirectionType.valueOf(settings.direction());
    }


    public String getTripodPort() {
        //return this.properties.getProperty("a5000.acs.devices.tripodPort", "COM3");
        return settings.port();
    }
}
