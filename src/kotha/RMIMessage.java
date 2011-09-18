package kotha;

class RMIMessage {

    final long id;
    final String methodName;
    final Object[] args;

    RMIMessage() {
        this(0, null);
    }

    RMIMessage(long id, String methodName, Object... args) {
        this.id = id;
        this.methodName = methodName;
        this.args = args;
    }
}
