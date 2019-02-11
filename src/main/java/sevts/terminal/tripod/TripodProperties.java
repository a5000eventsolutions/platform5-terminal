package sevts.terminal.tripod;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

public class TripodProperties {

    private Properties properties;

    public TripodProperties() {
    }

    public TripodProperties(String propertiesPath) throws IOException {
        this.properties = new Properties();
        File file = new File(propertiesPath);
        if (file.exists()) {
            this.properties.load(new FileReader(file));
        } else {
            this.properties.load(this.getClass().getClassLoader().getResourceAsStream(propertiesPath));
        }

    }

    public DirectionType getTripodDirection() {
        return DirectionType.valueOf(this.properties.getProperty("a5000.acs.devices.tripodDirection", "Enter").toUpperCase());
    }

    public DirectionType getRFIDDirection() {
        return DirectionType.valueOf(this.properties.getProperty("a5000.acs.devices.rfid.direction", "Enter").toUpperCase());
    }


    public String getTripodPort() {
        return this.properties.getProperty("a5000.acs.devices.tripodPort", "/dev/ttyUSB0");
    }

    public Properties getProperties() {
        return this.properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }
}
