

// you need to use Smack version 3.4.1

package xmppclient;

import java.util.*;
import java.io.*;
import java.sql.Timestamp;
 
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManagerListener;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.util.StringUtils;

public class XmppClient implements MessageListener, ConnectionListener, PacketListener, ChatManagerListener
{    
    long last_processed_message_time=System.currentTimeMillis();
    
    static XmppClient c;
    
    public String username;
    public String password;
    public String server;
    public String serial_device;
    public String control_user;
    public String control_command;
    public String logfile;
   
    XMPPConnection connection;
 
    public void login(String userName, String password) throws XMPPException
    {        
        ConnectionConfiguration config = new ConnectionConfiguration(server,5222, StringUtils.randomString(20));

        config.setSendPresence(true);
       // config.setDebuggerEnabled(false);
    
        connection = new XMPPConnection(config);
        connection.connect();
        connection.login(userName, password);
        
        connection.addConnectionListener(this);
        connection.addPacketListener(this, null);
        connection.getChatManager().addChatListener(this);
    }
    
    public void writeLog(String message)
    {
        java.util.Date date= new java.util.Date();
	String timestamp = (new Timestamp(date.getTime())).toString();
        
        try(PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(logfile, true))))
        {
            out.println(timestamp+" : " +message);
        }catch (IOException e) {
        }        
    }

    public void displayBuddyList()
    {
        Roster roster = connection.getRoster();
        Collection<RosterEntry> entries = roster.getEntries();

        System.out.println("\n\n" + entries.size() + " buddy(ies):");
        for(RosterEntry r:entries)
        {
        System.out.println(r.getUser());
        }
    }
 
    public void disconnect()
    {
        connection.disconnect();
        writeLog("disconnect");
    }
 
    private String runSystemProcess(String command)
    {
            String result = "";
        
        try {
              String line;
              Process p = Runtime.getRuntime().exec(command);
              BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
              while ((line = input.readLine()) != null)
              {
                writeLog(line);                
                result+=line+"\n";
              }
              input.close();
            }
            catch (Exception err) {
              err.printStackTrace();
              result=err.getMessage();
        }
        return result;
    }

    @Override
    public void connectionClosed() {
        writeLog("connectionClosed");
    }

    @Override
    public void connectionClosedOnError(Exception excptn) {
        writeLog("connectionClosedOnError: "+excptn.getMessage());
    }

    @Override
    public void reconnectingIn(int i) {
        writeLog("reconnectingIn: "+i);
        }

    @Override
    public void reconnectionSuccessful() {
        writeLog("reconnectionSuccessful");
    }

    @Override
    public void reconnectionFailed(Exception excptn) {
        writeLog("reconnectionFailed: "+excptn.getMessage());
    }

    @Override
    public void processPacket(Packet packet) {
        // don't log these - they're too frequent
        //writeLog("processPacket: "+packet.toString());
    }

    @Override
    public void chatCreated(Chat chat, boolean bln) {
        writeLog("chatCreated: "+chat.getParticipant()+", "+bln);
        chat.addMessageListener(this);
    }
   
    
    class SerialThread implements Runnable
    {
        Chat chat;
        String command;
        
        public SerialThread(Chat chat, String command)
        {
            this.chat = chat;
            this.command = command;
        }

        @Override
        public void run()
        {
            writeToHardware(chat,command);
        }        

        // send the serial command to the hardware, via the external C command
        private void writeToHardware(Chat chat, String command)
        {
             String operation=null;

            // currently the mapping from message to serial command
            // is hardcoded here - TODO: move it to the config

            if(command.equalsIgnoreCase("build"))
                operation = "op";

            if(command.equalsIgnoreCase("pass"))
                operation = "r0 o0 g1";

            if(command.equalsIgnoreCase("fail"))
                operation = "g0 o0 rf";

            if(command.equalsIgnoreCase("abort"))
                operation = "o0";

            if(command.equalsIgnoreCase("off"))
                operation = "r0 g0 o0";

            if(command.equalsIgnoreCase("fail-alarm"))
                operation = "g0 o0 rf bp";

            if(operation != null)
            {
                String commandline = control_command+" "+serial_device+ " " + operation;

                writeLog("executing command: "+commandline);

                String result = runSystemProcess(commandline);
                try
                {
                    chat.sendMessage("Response from device: \n"+result);
                    chat.sendMessage("Message processed: "+command+" = "+commandline);
                }catch(Exception e)
                {
                    writeLog("ProcessMessage: "+e.getMessage());
                }
            }
            else
            {
                try
                {
                    chat.sendMessage("Invalid command");
                }catch(Exception e)
                {
                    writeLog("ProcessMessage: "+e.getMessage());
                }
                writeLog("Invalid command");
            }
        }
    }
    
    @Override
    public void processMessage(Chat chat, Message message)
    {
        writeLog(chat.getParticipant() + ": incoming message: "+message.getType().name());
       
        // only respond to chat messages from the control user
        // TODO: allow wildcards for the control user
        if(message.getType() == Message.Type.chat && chat.getParticipant().startsWith(control_user))
        {
            writeLog(chat.getParticipant() + " says: " + message.getBody());

            if(message.getBody()==null)
            {
                return;
            }
            
            // if messsages are too frequent then discard some
            // this could also mean that messages are stacked on the server
            if((last_processed_message_time+1000)>=System.currentTimeMillis())
            {
                writeLog("Discarding message - rate too high");
                return;
            }
            
            last_processed_message_time=System.currentTimeMillis();
                    
            // do some rudimentary security before executing the string!
            String[] array = message.getBody().split(" ");
            
            String response = "Processing command";
            
            if(array.length==1)
            {
                // 'ping' command to test the client is running
                if(array[0].equalsIgnoreCase("ping"))
                {
                    response = "pong";
                }
                else// send the command to the hardware
                {
                    // don't block the XMPP thread, perform slow serial command in separate thread
                   (new Thread(new SerialThread(chat, array[0]))).start();  
                }
            }
            else// error
            {
                writeLog("Not correct number of arguments in message");
                response = "Thatz not OK!";            
            }

            try
            {
                 chat.sendMessage(response);
            }catch(Exception e)
            {
                 writeLog("processMessage: "+e.getMessage());
            }
        }
    }
    
    public boolean isConnected()
    {
        return connection.isConnected();
    }
    
    public void connect()
    {        
        try
        {
            login(username, password);

             writeLog("Connected");
        }
        catch(Exception e)
        {
            writeLog("connect: "+e.getMessage());
        }        
    }
 
    public static void main(String args[]) throws XMPPException, IOException
    {   
        if(args.length<1)
        {
            System.out.println("Please specify a settings file");
            System.exit(0);
        }
        
        c = new XmppClient();
        
        // load settings
        Properties properties = new Properties();
        try{
            properties.load(new FileInputStream(args[0]));

            c.username          = properties.getProperty("username");
            c.password          = properties.getProperty("password");
            c.server            = properties.getProperty("xmpp-server");
            c.serial_device     = properties.getProperty("serial-device");
            c.control_user      = properties.getProperty("control-user");
            c.control_command   = properties.getProperty("control-command");
            c.logfile           = properties.getProperty("logfile");
  
            c.writeLog("-------------------------------------------");
            c.writeLog("Starting");

            for(String key : properties.stringPropertyNames()) {
              String value = properties.getProperty(key);
                c.writeLog(key + " => " + value);
             }

        }catch (IOException e){
            System.out.println("Load properties: "+e.getMessage());
        }

           c.writeLog("Settings loaded OK");

            // turn on the enhanced debugger
            //XMPPConnection.DEBUG_ENABLED = true;
            c.connect();
            
            System.out.println("Type 'exit' to disconnect");

            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            while(!(br.readLine()).equals("exit"));
            
            c.writeLog("Disconnecting");
            c.disconnect();
            c.writeLog("Finished");
    }

}

