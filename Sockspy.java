
public class Sockspy {
    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack" , "true");
        ProxyServer server = new ProxyServer(8080);
        server.Start();
    }
}
