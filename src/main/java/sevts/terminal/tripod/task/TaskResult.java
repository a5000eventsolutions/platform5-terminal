package sevts.terminal.tripod.task;


public class TaskResult {
    protected int[] mas;
    protected boolean success;

    public TaskResult(boolean success) {
        this.success = success;
        this.mas = new int[0];
    }

    public TaskResult(boolean success, int[] mas) {
        this.success = success;
        this.mas = mas;
    }

    public int[] getMas() {
        return this.mas;
    }

    public void setMas(int[] mas) {
        this.mas = mas;
    }

    public boolean isSucces() {
        return this.success;
    }

    public void setSucces(boolean success) {
        this.success = success;
    }
}
