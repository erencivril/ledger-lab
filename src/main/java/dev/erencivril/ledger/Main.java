package dev.erencivril.ledger;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        App app = App.start(Config.fromEnv(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }
}
