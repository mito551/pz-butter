package pzbutter;

public class Main {
    public static void main(String[] args) {
        Settings.load();
        System.out.println("[PZButter] " + Settings.VERSION + ", enabled=" + Settings.enabled);
    }
}
