public class Sockspy
{
    public static void main(String[] args)
    {
        System.setProperty("java.net.preferIPv4Stack" , "true"); // To get correct IP Address from Inet.
        ProxyServer server = new ProxyServer(8080);
        server.Start();
    }
}
