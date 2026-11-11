import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

class Attachment {
  String name;
  int state; // 0-init, 1-outside, 2-inside
  String sala; // nome da sua sala

  // attachment(String name) {
  //   this.name = name;
  //   this.state = 0;
  // }
  Attachment() {
    state = 0;
  }
}

public class ChatServer
{
  // A pre-allocated buffer for the received data
  static private final ByteBuffer buffer = ByteBuffer.allocate( 16384 );

  static private final int INIT=0, OUTSIDE=1, INSIDE=2;
  static private final Map<String, Integer> COMMANDS = new TreeMap<>(Map.of(
    "/nick",  1,
    "/join",  1,
    "/leave", 0,
    "/bye",   0,
    "/priv",  2
  ));
  static private Set<String> usernames = new TreeSet<>();

  // Decoder for incoming text -- assume UTF-8
  static private final Charset charset = Charset.forName("UTF8");
  static private final CharsetDecoder decoder = charset.newDecoder();


  static public void main( String args[] ) throws Exception {
    // Parse port from command line
    int port = Integer.parseInt( args[0] );
    
    try {
      // Instead of creating a ServerSocket, create a ServerSocketChannel
      ServerSocketChannel ssc = ServerSocketChannel.open();

      // Set it to non-blocking, so we can use select
      ssc.configureBlocking( false );

      // Get the Socket connected to this channel, and bind it to the
      // listening port
      ServerSocket ss = ssc.socket();
      InetSocketAddress isa = new InetSocketAddress( port );
      ss.bind( isa );

      // Create a new Selector for selecting
      Selector selector = Selector.open();

      // Register the ServerSocketChannel, so we can listen for incoming
      // connections
      ssc.register( selector, SelectionKey.OP_ACCEPT );
      System.out.println( "Listening on port " + port );

      while (true) {
        // See if we've had any activity -- either an incoming connection,
        // or incoming data on an existing connection
        int num = selector.select();

        // If we don't have any activity, loop around and wait again
        if (num == 0) {
          continue;
        }

        // Get the keys corresponding to the activity that has been
        // detected, and process them one by one
        Set<SelectionKey> keys = selector.selectedKeys();
        Iterator<SelectionKey> it = keys.iterator();
        while (it.hasNext()) {
          // Get a key representing one of bits of I/O activity
          SelectionKey key = it.next();

          // What kind of activity is it?
          if (key.isAcceptable()) {

            // It's an incoming connection.  Register this socket with
            // the Selector so we can listen for input on it
            Socket s = ss.accept();
            System.out.println( "Got connection from "+s );

            // Make sure to make it non-blocking, so we can use a selector
            // on it.
            SocketChannel sc = s.getChannel();
            sc.configureBlocking( false );

            
            // Register it with the selector, for reading
            sc.register( selector, SelectionKey.OP_READ );

            // classe onde fica o nome, estado e sala
            sc.keyFor(selector).attach(new Attachment());

          } else if (key.isReadable()) {

            SocketChannel sc = null;

            try {

              // It's incoming data on a connection -- process it
              sc = (SocketChannel)key.channel();
              
              boolean ok = processInput( sc, selector );

              // If the connection is dead, remove it from the selector
              // and close it
              if (!ok) {
                key.cancel();

                Socket s = null;
                try {
                  s = sc.socket();
                  System.out.println( "Closing connection to "+s );
                  s.close();
                } catch( IOException ie ) {
                  System.err.println( "Error closing socket "+s+": "+ie );
                }
              }

            } catch( IOException ie ) {

              // On exception, remove this channel from the selector
              key.cancel();

              try {
                sc.close();
              } catch( IOException ie2 ) { System.out.println( ie2 ); }

              System.out.println( "Closed "+sc );
            }
          }
        }

        // We remove the selected keys, because we've dealt with them.
        keys.clear();
      }
    } catch( IOException ie ) {
      System.err.println( ie );
    }
  }


  // Just read the message from the socket and send it to stdout
  static private boolean processInput( SocketChannel sc, Selector selector) throws IOException {
    // Read the message to the buffer
    buffer.clear();
    sc.read( buffer );
    buffer.flip();
    
    // If no data, close the connection
    if (buffer.limit()==0) {
      return false;
    }


    String received = charset.decode(buffer).toString().trim();
    Attachment userData = (Attachment) sc.keyFor(selector).attachment();
    // System.out.print("teste: " +  received);
    String command;
    if (( command = getCommand(received)) == null) {
      sendMessage(sc, selector, "ERROR\n", null, null);
    }
    else if (command == "message") {
      if (userData.state != INSIDE)
        sendMessage(sc, selector, "ERROR\n", null, null);
      else 
        sendMessage(sc, selector, "MESSAGE" + userData.name + received + "\n", null, userData.sala);
    }
    else { // todo: switch de comandos 
      switch(command) {
        case "/nick"  -> handleNick(sc, selector, received);
        // case "/join"  -> handleJoin(sc, arg);
        // case "/leave" -> handleLeave(sc);
        // case "/bye"   -> { return false; }
        // case "/priv"  -> handlePriv(sc, arg);
      }
    }
    
    // Decode and print the message to stdout
    // String message = decoder.decode(buffer).toString();
    // System.out.print( message );

    return true;
  }

  // returns null if command is invalid, "message" if it is a normal message and a command if it is valid
  static private String getCommand(String received) {
    String[] words = received.split("\\s+", 2);
    if (COMMANDS.get(words[0]) != null) {
      int nrArgs = COMMANDS.get(words[0]);
      if (words.length != nrArgs+1)
        return null;
      else 
        return words[0];
    }

    return "message";
  }

  static private void handleNick(SocketChannel sc, Selector selector, String received) throws IOException{
    String words[] = received.split(" ");
    Attachment userData = (Attachment) sc.keyFor(selector).attachment();
    String nname = words[1];

    if (usernames.contains(nname))
      sendMessage(sc, selector, "ERROR\n", null, null);
    else {
      if (userData.state == INSIDE) {
        String tmp = userData.sala;
        userData.sala = "";
        sendMessage(sc, selector, "NEWNICK " + userData.name + " " + nname + "\n", null, tmp);
        userData.sala = tmp;
      }
      if (userData.state != INIT) // apagar nome antigo do set
        usernames.remove(userData.name);
        
      sendMessage(sc, selector, "OK\n", null, null);
      userData.name = nname;
      usernames.add(nname);
    }
  }

  static private void sendMessage(SocketChannel sc, Selector selector, String message, String receiver, String room) throws IOException{
    ByteBuffer out = ByteBuffer.wrap((message).getBytes(charset));
    if (receiver!= null) {
      // codigo para enviar uma mensagem privada ao receiver
    }
    else if (room != null){
      // codigo para mandar mensagem para a sala
      for (var key : selector.keys()) {
        if (key.isReadable()) {
            SocketChannel ch = (SocketChannel)key.channel();
            
            while(out.hasRemaining()) 
                ch.write(out);
            out.rewind();
        }
    }
    }
    else { // enviar para o sc
      while (out.hasRemaining())
        sc.write(out);
    }
  }
}
