package sevts.terminal.tripod;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DriverTripod {
    private static Logger logger = LoggerFactory.getLogger(DriverTripod.class.getName());
    protected int checksum;
    protected OutputStream outputStream;
    protected BufferedInputStream bufferedInputStream;
    protected StringBuilder stringBuilder = new StringBuilder();

    public DriverTripod(BufferedInputStream bufferedInputStream, OutputStream outputStream) {
        this.bufferedInputStream = bufferedInputStream;
        this.outputStream = outputStream;
    }

    protected void resetChecksum() {
        this.checksum = 0;
        this.stringBuilder.delete(0, this.stringBuilder.capacity());
    }

    protected void startPacket(int packetType) throws IOException {
        this.resetChecksum();
        this.outputStream.flush();
        this.outputStream.write(16);
        this.checksum += 16;
        this.outputStream.write(1);
        ++this.checksum;
        this.outputStream.write(packetType);
        this.checksum += packetType;
        this.addToSB(16);
        this.addToSB(1);
        this.addToSB(packetType);
    }

    protected void endPacket() throws IOException {
        this.outputStream.write(17);
        this.checksum += 17;
        this.outputStream.write(this.checksum);
        this.outputStream.flush();
        this.addToSB(17);
        this.addToSB(this.checksum);
    }

    protected void addToSB(int i) {
        this.stringBuilder.append(Integer.toHexString(i % 255).toUpperCase());
        this.stringBuilder.append(" ");
    }

    protected void writeParameter(int i) throws IOException {
        this.outputStream.write(i);
        this.checksum += i;
        this.addToSB(i);
    }

    protected int[] read() {
        int[] mas = new int[0];

        try {
            int size;
            if ((size = this.bufferedInputStream.available()) > 0) {
                mas = new int[size];

                for(int i = 0; i < size; ++i) {
                    int res = this.bufferedInputStream.read();
                    mas[i] = res;
                }

                BravoTripodProtocol.printResult(mas);
            }
        } catch (IOException var5) {
            logger.error(var5.getMessage());
        }

        return mas;
    }

    public int[] readFromTripod() throws IOException {
        return this.read();
    }

    public void writeToTripod(int code, int[] parameters) throws IOException {
        this.startPacket(code);
        this.writeParameter(parameters.length);
        int[] arr$ = parameters;
        int len$ = parameters.length;

        for(int i$ = 0; i$ < len$; ++i$) {
            int value = arr$[i$];
            this.writeParameter(value);
        }

        this.endPacket();
        logger.debug(this.stringBuilder.toString());
    }

    public String getLastCommand() {
        return this.stringBuilder.toString().trim();
    }
}
