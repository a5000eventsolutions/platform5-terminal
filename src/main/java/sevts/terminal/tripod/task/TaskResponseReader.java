package sevts.terminal.tripod.task;


import sevts.terminal.tripod.TripodException;

public class TaskResponseReader {
    public TaskResponseReader() {
    }

    public int[] readResponse(int[] answer, int responseSize) throws TripodException {
        return this.readResponse(answer, responseSize, false);
    }

    public int[] readResponse(int[] answer, int responseSize, boolean getAnswer) throws TripodException {
        if (answer.length == responseSize && answer.length >= 5) {
            if (getAnswer) {
                answer = this.getNewAnswer(answer);
            }

            return answer;
        } else {
            throw new TripodException("Wrong response size!");
        }
    }

    protected int[] getNewAnswer(int[] answer) throws TripodException {
        int actualsize = answer[3] - 1;
        int[] newanswer = new int[actualsize];
        System.arraycopy(answer, 5, newanswer, 0, actualsize - 5);
        return newanswer;
    }
}
