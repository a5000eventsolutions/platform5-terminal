package sevts.terminal.tripod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BravoTripodProtocol {
    public static final int PACKET_START_HEADER = 16;
    public static final int PACKET_END_HEADER = 17;
    public static final int PACKET_ADR = 1;
    public static final int PACKET_OPEN_DOOR_COMMAND = 205;
    public static final int PACKET_RESET_COMMAND = 207;
    public static final int PACKET_READCARD_COMMAND_ENTRANCE = 196;
    public static final int PACKET_READCARD_COMMAND_EXIT = 206;
    public static final int PACKET_CHECKSTATUS_COMMAND = 195;
    public static final int NO_ERROR = 0;
    public static final int BAD_COMMAND_ERROR = 1;
    public static final int PARAM_ERROR = 4;
    public static final int RESPONSE_DOES_NOT_MATCH_REQUEST_ERROR = 8;
    public static final int UNEXPECTED_STATUS = 9;
    public static final int OPEN_DOOR_TASK_RESPONSE_SIZE = 7;
    public static final int RESET_TASK_RESPONSE_SIZE = 7;
    public static final int CHECK_STATUS_TASK_RESPONSE_SIZE = 8;
    public static final int READ_CARD_TASK_RESPONSE_SIZE = 14;
    public static final int DEFAULT_DELAY = 50;
    private static Logger logger = LoggerFactory.getLogger(BravoTripodProtocol.class.getName());

    public BravoTripodProtocol() {
    }

    public static void printResult(int[] answer) {
        logger.debug("Answer from Tripod");
        StringBuilder sb = new StringBuilder();
        int[] arr$ = answer;
        int len$ = answer.length;

        for(int i$ = 0; i$ < len$; ++i$) {
            int anAnswer = arr$[i$];
            sb.append(Integer.toHexString(anAnswer).toUpperCase());
            sb.append(" ");
        }

        logger.debug(sb.toString());
    }
}
