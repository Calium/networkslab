
public class Sockspy {
    public static void main(String[] args) {
        ProxyServer server = new ProxyServer(8080);
        server.Start();
    }
}
