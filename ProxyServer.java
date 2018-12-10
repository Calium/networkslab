import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Created by Niv on 09/12/2018.
 */
public class ProxyServer {
    public static final int MAX_CONNECTIONS = 20; // Maximum amount of connections

    private ArrayList<Connection> m_Connections; // Data structure to hold active connections - mainly to close them if proxy is closed
    private int m_ConnectionsCount; // Amount of connections currently active
    private int m_Port; // Port to listen
    private ServerSocket m_Listener; // Server socket

    public ProxyServer(int i_Port)
    {
        m_Connections = new ArrayList<>();
        m_ConnectionsCount = 0;
        m_Port = i_Port;
    }

    public void Start()
    {
        try
        {
            m_Listener = new ServerSocket(m_Port);
        }
        catch (IOException ioe)
        {
            print("Could not listen on given port: " + m_Port);
            return;
        }
        System.out.println("Server is listening on port: " + m_Port);

        while (true) // Or catch signal? check this
        {
            if (m_ConnectionsCount < MAX_CONNECTIONS)
            {
                try
                {
                    Connection newConnection = new Connection(m_Listener.accept(), this);
                    if (newConnection.start())
                    {
                        addConnection(newConnection);
                    }
                    System.out.println("DEBUG: Connection Count: " + m_ConnectionsCount + " Connections Size: " + m_Connections.size());
                }
                catch (IOException ioe)
                {
                    print("Could not accept new client.");
                }
            }
        }
    }

    public synchronized void addConnection(Connection i_Connection)
    {
        m_Connections.add(i_Connection);
        m_ConnectionsCount++;
    }

    public synchronized void removeConnection(Connection i_Connection)
    {
        m_Connections.remove(i_Connection);
        m_ConnectionsCount--;
        System.out.println("DEBUG: Connection Count: " + m_ConnectionsCount + " Connections Size: " + m_Connections.size());
    }

    public static void main(String[] args)
    {
        ProxyServer server = new ProxyServer(8080);
        server.Start();
    }

    private void print(String i_Message)
    {
        System.err.println("Connection Error: " + i_Message);
    }
}
