import jdk.nashorn.internal.ir.annotations.Ignore;

import javax.swing.plaf.basic.BasicInternalFrameTitlePane;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Niv on 09/12/2018.
 */
public class Connection {

    enum eCDCode{
        REQUEST_GRANTED(90),
        REQUEST_REJECTED_OR_FAILED(91),;

        private final int cd_code;

        eCDCode(int i) {
            this.cd_code = i;
        }

        public int getValue() {
            return cd_code;
        }
    }

    /// CLIENT
    private Socket m_Client; // The client using the proxy
    private BufferedInputStream m_ClientReader; // Read messages from client
    private BufferedOutputStream m_ClientWriter; // Write messages to client

    /// TARGET
    private Socket m_Target; // The target destination
    private BufferedInputStream m_TargetReader; // Read messages from target
    private BufferedOutputStream m_TargetWriter; // Write messages to target

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
            m_ClientReader = new BufferedInputStream(m_Client.getInputStream());
            m_ClientWriter = new BufferedOutputStream(m_Client.getOutputStream());
        } catch (IOException ioe) {
            print("Failed to start open streams.");
            return false;
        }
        //System.out.println("DEBUG: Streams are open.");

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
            m_ClientIP = m_Client.getInetAddress().getHostAddress();
            m_ClientPort = m_Client.getPort();

            int socksVersion = m_Client.getInputStream().read();
            int socksCommand = m_Client.getInputStream().read();

            if (socksVersion != 4) {
                /*
                print("while parsing request: Unsupported SOCKS protocol version (got " + socksVersion + ")");
                writeResponse(eCDCode.REQUEST_REJECTED_OR_FAILED.getValue(), portB, ipB);
                close();
                */
                rejectConnection("while parsing request: Unsupported SOCKS protocol version (got "
                                + socksVersion + ")", portB, ipB);
                return false;
            } else if (socksCommand != 1) {
                /*
                print("while parsing request: Unsupported SOCKS protocol command (got " + socksCommand + ")");
                writeResponse(eCDCode.REQUEST_REJECTED_OR_FAILED.getValue(), portB, ipB);
                close();*/
                rejectConnection("while parsing request: Unsupported SOCKS protocol command (got "
                                + socksCommand + ")", portB, ipB);
                return false;
            }

            m_Client.getInputStream().read(portB);
            m_Client.getInputStream().read(ipB);

            m_TargetPort = parsePort(portB);
            m_TargetIP = parseIP(ipB);

            while(m_Client.getInputStream().read() != 0x00) {} // Skip UID and NULL byte - Clear buffer
        }
        catch (IOException ioe)
        {
            rejectConnection("Failed to read target data.", portB, ipB);
            // print("Failed to read target data.");
            // close();
            return false;
        }

        try
        {
            m_Target = new Socket(m_TargetIP, m_TargetPort);
            m_TargetWriter = new BufferedOutputStream(m_Target.getOutputStream());
            m_TargetReader = new BufferedInputStream(m_Target.getInputStream());

            m_Target.setSoTimeout(30000);
            m_Client.setSoTimeout(30000);

            writeResponse(eCDCode.REQUEST_GRANTED.getValue(), portB, ipB);

            System.out.println("Successful connection from " + m_ClientIP + ":" + m_ClientPort + " to " + m_TargetIP + ":" + m_TargetPort);
        }
        catch (IOException ioe)
        {
            /*
            print("Failed to initiate connection with target / send socks reply to client.");
            try
            {
                writeResponse(eCDCode.REQUEST_REJECTED_OR_FAILED.getValue(), portB, ipB);
            } catch (IOException ioe2) {}
            close(); */
            rejectConnection("Failed to initiate connection with target / send socks reply to client.", portB, ipB);
            return false;
        }
        return true;
    }

    private byte[] prepareResponse(int i_Code, byte[] i_Port, byte[] i_IP)
    {
        ByteBuffer response;
        response = ByteBuffer.allocate(8);
        response.put((byte)0); // VN
        response.put((byte)i_Code); // CD
        response.put(i_Port); // Port
        response.put(i_IP); // IP

        return response.array();
    }

    private void writeResponse(int i_Code, byte[] i_Port, byte[] i_IP) throws IOException
    {
        m_Client.getOutputStream().write(prepareResponse(i_Code, i_Port, i_IP));
        m_Client.getOutputStream().flush();
    }

    private void rejectConnection(String i_ErrorMessage, byte[] i_Port, byte[] i_IP)
    {
        print(i_ErrorMessage);
        try
        {
            writeResponse(eCDCode.REQUEST_REJECTED_OR_FAILED.getValue(), i_Port, i_IP);
        } catch (IOException ioe2) {}
        close();
    }

    public void startDataFlow()
    {
        Thread clientThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String message = "";
                byte[] buffer = new byte[4096];
                try
                {
                        int charRead = 0;

                        while ((charRead = m_ClientReader.read(buffer)) != -1) {
                            message += new String(buffer,0, charRead);

                            m_TargetWriter.write(buffer,0,charRead);
                            m_TargetWriter.flush();
                        }
                        if (!message.equals("")) {

                            if (m_TargetPort == 80)
                            {
                                findCredentials(message);
                            }
                        }
                }
                catch (IOException ioe)
                {
                    //print("Client reader closed. Terminating connection...");
                }

            }
        });
        clientThread.start();

        Thread targetThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String message = "";
                byte[] buffer = new byte[4096];
                try
                {
                        int charRead = 0;
                        // System.out.println();
                        while ( ( charRead = m_TargetReader.read(buffer)) != -1) {
                            message += new String(buffer,0,charRead);
                            m_ClientWriter.write(buffer,0,charRead);
                            m_ClientWriter.flush();
                        }
                        message = "";

                }
                catch (IOException ioe)
                {
                   // return;
                }
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
        }finally {
            close();
        }

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
        m_Parent.removeConnection();//this);

        String closingMessage = "";
        if (m_ClientIP != null)
            closingMessage += "Closing connection from " + m_ClientIP + ":" + m_ClientPort;
        if (m_TargetIP != null)
            closingMessage += " to " + m_TargetIP + ":" + m_TargetPort;
        if (!closingMessage.equals(""))
            System.out.println(closingMessage);
            //System.out.println("Closing connection from " + m_ClientIP + ":" + m_ClientPort + " to " + m_TargetIP + ":" + m_TargetPort);
    }

    private String findCredentials(String i_Headers)
    {
        String[] headers = i_Headers.split("\\r\\n");

        String regex = "Host: (.+)";

        Pattern patternHost = Pattern.compile(regex, Pattern.MULTILINE);

        regex = "Authorization: Basic (.+)";
        Pattern patternCredentials = Pattern.compile(regex, Pattern.MULTILINE);

        String host = "", credentials = "";

        for (String header: headers) {
            Matcher matcher = patternHost.matcher(header);

            if(matcher.matches())
            {
                host  = matcher.group(1);
            }

            matcher = patternCredentials.matcher(header);
            if(matcher.matches())
            {
                credentials  = matcher.group(1);
                Base64.Decoder decoder = Base64.getDecoder();
                credentials = new String(decoder.decode(credentials));
            }
        }

        if(host != ""  && credentials != "")
        {
            System.out.println(String.format("Password Found! http://%s@%s/", credentials, host ));
        }

        return "";
    }
}
