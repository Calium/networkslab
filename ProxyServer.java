import java.io.*;
import java.net.*;

/**
 * Created by Niv on 09/12/2018.
 */

public class ProxyServer {
    public static final int MAX_CONNECTIONS = 20; // Maximum amount of connections

    private int m_ConnectionsCount; // Amount of connections currently active
    private int m_Port; // Port to listen
    private ServerSocket m_Listener; // Server socket

    public ProxyServer(int i_Port)
    {
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

        System.out.println("Server is listening on port: " + m_Port); // Quality of life print

        while (true)
        {
            if (m_ConnectionsCount < MAX_CONNECTIONS)
            {
                try
                {
                    Connection newConnection = new Connection(m_Listener.accept(), this);
                    addConnection();
                    newConnection.start();
                }
                catch (IOException ioe)
                {
                    print("Could not accept new client.");
                }
            }
            else
            {
                try
                {
                    synchronized (this) {
                        this.wait();
                    }
                }
                catch (InterruptedException ie)
                {
                }
                catch (Exception e) {}
            }
        }
    }

    public synchronized void addConnection()
    {
        m_ConnectionsCount++;
    }

    public synchronized void removeConnection()
    {
        m_ConnectionsCount--;
        this.notifyAll();
    }

    private void print(String i_Message)
    {
        System.err.println("Connection Error: " + i_Message);
    }
}
