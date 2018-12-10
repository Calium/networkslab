import jdk.nashorn.internal.ir.annotations.Ignore;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

/**
 * Created by Niv on 09/12/2018.
 */
public class Connection {

    /// CLIENT
    private Socket m_Client; // The client using the proxy
    private BufferedReader m_ClientReader; // Read messages from client
    private PrintWriter m_ClientWriter; // Write messages to client

    /// TARGET
    private Socket m_Target; // The target destination
    private BufferedReader m_TargetReader; // Read messages from target
    private PrintWriter m_TargetWriter; // Write messages to target\

    // CONNECTION DATA
    private String m_ClientIP;
    private String m_TargetIP;
    private int m_ClientPort;
    private int m_TargetPort;

    private ProxyServer m_Parent; // To notify when the connection is closed

    public Connection(Socket i_Client, ProxyServer i_Parent) {
        m_Client = i_Client;
        m_Parent = i_Parent;
    }

    public boolean start() {
        try {
            m_ClientReader = new BufferedReader(new InputStreamReader(m_Client.getInputStream()));
            m_ClientWriter = new PrintWriter(m_Client.getOutputStream());
        } catch (IOException ioe) {
            print("Failed to start open streams.");
            return false;
        }
        System.out.println("DEBUG: Streams are open.");

        if (readTargetData()) {
            Thread flowThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    startDataFlow();
                }
            });
            flowThread.start();
        } else {
            return false;
        }

        return true;
    }

    public boolean readTargetData()
    {
        String ip;
        int port;
        byte[] portB = new byte[2];
        byte[] ipB = new byte[4];

        try
        {
            System.out.println("DEBUG: Socks version: " + m_Client.getInputStream().read());
            System.out.println("DEBUG: Socks command: " + m_Client.getInputStream().read());
            m_Client.getInputStream().read(portB);
            m_Client.getInputStream().read(ipB);
            System.out.println("DEBUG: Destination Port: " + (m_TargetPort = parsePort(portB)));
            System.out.println("DEBUG: Destination IP: " + (m_TargetIP = parseIP(ipB)));
            while(m_Client.getInputStream().read() != 0x00) {} // Skip UID and NULL byte - Clear buffer
            m_ClientIP = "127.0.0.1";
            m_ClientPort = m_Client.getPort();
        }
        catch (IOException ioe)
        {
            print("Failed to read target data.");
            close();
            return false;
        }

        try
        {
            m_Target = new Socket(m_TargetIP, m_TargetPort);
            m_TargetWriter = new PrintWriter(m_Target.getOutputStream());
            m_TargetReader = new BufferedReader(new InputStreamReader(m_Target.getInputStream()));

            m_Client.getOutputStream().write(prepareResponse(90, portB, ipB));
            m_Client.getOutputStream().flush();
            System.out.println("Successful connection from " + m_ClientIP + ":" + m_ClientPort + " to " + m_TargetIP + ":" + m_TargetPort);
        }
        catch (IOException ioe)
        {
            print("Failed to initiate connection with target.");
            try
            {
                m_Client.getOutputStream().write(prepareResponse(91, portB, ipB));
                m_Client.getOutputStream().flush();
            } catch (IOException ioe2) {} // really?
            close();
            return false;
        }
        return true;
    }

    private byte[] prepareResponse(int i_Code, byte[] i_Port, byte[] i_IP)
    {
        ByteBuffer response;
        response = ByteBuffer.allocate(8);
        response.put((byte)0); // VN
        response.put((byte)i_Code); // CD - Granted
        response.put(i_Port); // Port
        response.put(i_IP); // IP

        return response.array();
    }

    public void startDataFlow()
    {
        Thread clientThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String message = "";
                try
                {
                    while (m_ClientReader.ready())
                    {
                        message += m_ClientReader.readLine() + "\r\n";
                    }
                    if (!message.equals(""))
                    {
                        // System.out.println("DEBUG: Final Message is:\r\n" + message);
                        m_TargetWriter.print(message);
                        m_TargetWriter.flush();
                        message = ""; // Prepare for multi message handling
                    }
                }
                catch (IOException ioe)
                {
                    print("Client reader closed. Terminating connection...");
                }
                /*
                finally
                {
                    close();
                }
                */
            }
        });
        clientThread.start();

        Thread targetThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String message = "";
                boolean waitingForResponse = true;
                try
                {
                    while (waitingForResponse)
                    {
                        while (m_TargetReader.ready())
                        {
                            message += m_TargetReader.readLine() + "\r\n";
                        }
                        if (!message.equals(""))
                        {
                            //System.out.println("DEBUG: Final Message is:\r\n" + message);
                            m_ClientWriter.print(message);
                            m_ClientWriter.flush();
                            message = "";
                            waitingForResponse = false;
                        }
                    }
                }
                catch (IOException ioe)
                {
                    print("Target reader closed. Terminating connection...");
                }
                /*
                finally
                {
                    close();
                }
                */
            }
        });
        targetThread.start();

        try
        {
            clientThread.join();
            targetThread.join();
        }
        catch(InterruptedException ine)
        {
            print("Could not perform join on threads.");
        }
        close();
    }

    /**
     * Big endian parsing of byte array that represents a port
     * @param i_bytePort
     * @return
     */
    private int parsePort(byte[] i_bytePort)
    {
        ByteBuffer portBB = ByteBuffer.wrap(i_bytePort);
        int port = 0;
        port |= (portBB.get() & 0xff);
        port <<= 8;
        port |= (portBB.get() & 0xff);
        return port;
    }

    /**
     * Big endian parsing of byte array that represents an IP
     * @param i_byteIP
     * @return
     */
    private String parseIP(byte[] i_byteIP)
    {
        ByteBuffer ipBB = ByteBuffer.wrap(i_byteIP);
        String ipString = "";
        for (int i = 0; i < i_byteIP.length - 1; i++)
        {
            ipString += (ipBB.get() & 0xff) + ".";
        }
        ipString += (ipBB.get() & 0xff);
        return ipString;
    }

    private void print(String i_Message)
    {
        System.err.println("Connection Error: " + i_Message);
    }

    public void close()
    {
        try
        {
            if (m_ClientReader != null) {
                m_ClientReader.close();
            }
            if (m_ClientWriter != null) {
                m_ClientWriter.close();
            }
            if (m_TargetReader != null) {
                m_TargetReader.close();
            }
            if (m_TargetWriter != null) {
                m_TargetWriter.close();
            }
            if (m_Client != null) {
                m_Client.close();
            }
            if (m_Target != null) {
                m_Target.close();
            }
        }
        catch (IOException ioe) {}
        m_Parent.removeConnection(this);
        System.out.println("Closing connection from " + m_ClientIP + ":" + m_ClientPort + " to " + m_TargetIP + ":" + m_TargetPort);
    }
}
