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
  static private Map<String, Set<SelectionKey>> rooms = new TreeMap<>();
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

    String received = decoder.decode(buffer).toString().trim();
    SelectionKey key = sc.keyFor(selector);
    Attachment userData = (Attachment) key.attachment();
    // System.out.print("teste: " +  received);
    ArrayList<String> args;
    if (( args = getCommand(received)) == null) {
      sendError(key);
    }
    else if (args.getFirst() == "message") {
      if (userData.state != INSIDE)
        sendError(key);
      else 
        sendMessage("MESSAGE" + " " + userData.name + " " + received + "\n", getRoom(userData.sala));
    }
    else { // todo: switch de comandos 
      switch(args.getFirst()) {
        case "/nick"  -> handleNick(key, args);
        case "/join"  -> handleJoin(key, args);
        case "/leave" -> handleLeave(key);
        case "/bye"   -> { leaveRoom(key); goodBye(key); return false; }
        case "/priv"  -> handlePriv(key, selector, args);
      }
    }
    

    return true;
  }

  // returns null if command is invalid, "message" if it is a normal message and a command if it is valid
  static private ArrayList<String> getCommand(String received) {
    ArrayList<String> words = new ArrayList<>(Arrays.asList(received.split("\\s+", 3)));

    Integer nrArgs = COMMANDS.get(words.getFirst());
    if (nrArgs != null) 
      return words.size() == nrArgs + 1 ? words : null;
    
    words.addFirst("message");
    return words;
  }

  private static Set<SelectionKey> getRoom(String room) {
    return rooms.get(room);
  }

  private static void sendError(SelectionKey key) throws IOException {
    sendMessage("ERROR\n", Set.of(key));
  }

  private static void sendConfirmation(SelectionKey key) throws IOException {
    sendMessage("OK\n", Set.of(key));
  }


  static private void handleNick(SelectionKey key, ArrayList<String> args) throws IOException{
    Attachment userData = (Attachment) key.attachment();
    String nname = args.get(1);

    if (usernames.contains(nname))
      sendError(key);
    else {
      if (userData.state == INSIDE) {
        Set<SelectionKey> room = new HashSet<>(getRoom(userData.sala));
        room.remove(key);
        sendMessage("NEWNICK " + userData.name + " " + nname + "\n", room);
      }
      if (userData.state != INIT) // apagar nome antigo do set
        usernames.remove(userData.name);
      else 
        userData.state = OUTSIDE;
        
      sendConfirmation(key);
      userData.name = nname;
      usernames.add(nname);
    }
  }

  static private void handleJoin(SelectionKey key, ArrayList<String> args) throws IOException {
    Attachment userData = (Attachment) key.attachment();
    if (userData.state == INIT) {
      sendError(key);
      return ;
    }
    leaveRoom(key);
    
    String newRoom = args.get(1);
    userData.state = INSIDE;
    userData.sala = newRoom;
    sendConfirmation(key);
    if (getRoom(newRoom) == null) 
      rooms.put(newRoom, Set.of(key));
    else {
      Set<SelectionKey> room = new HashSet<>(getRoom(newRoom));
      sendMessage("JOINED " + userData.name + "\n", room);
      rooms.put(newRoom, room); // atualiza o set da sala
      room.add(key);
    }

  }

  static private boolean leaveRoom(SelectionKey key) throws IOException { // retorna falso se o user nao estava INSIDE
    Attachment userData = (Attachment) key.attachment();

    if (userData.state != INSIDE) return false;

    userData.state = OUTSIDE;
    Set<SelectionKey> room = new HashSet<>(getRoom(userData.sala)); // é preciso usar new hashset para o set nao ser imutavel
    room.remove(key);
    if (room.size() > 0)
      rooms.put(userData.sala, room); // atualiza o set da sala
    else
      rooms.remove(userData.sala);
    sendMessage("LEFT " + userData.name + "\n", room);

    return true;
  }


  static private void handleLeave(SelectionKey key) throws IOException {
    if (leaveRoom(key))
      sendConfirmation(key);
    else
      sendError(key); 
  }
  
  private static void goodBye(SelectionKey key) throws IOException{
    sendMessage("BYE\n", Set.of(key));
  }

  static private void handlePriv(SelectionKey key, Selector selector, ArrayList<String> args) throws IOException {
    Attachment userData = (Attachment) key.attachment();
    if (userData.name == null) {
      sendError(key);
      return ;
    }

    SelectionKey receiverKey = getKey(args.get(1), selector);
    if (receiverKey == null) {
      sendError(key);
      return ;
    }
    
    sendConfirmation(key);
    sendMessage("PRIVATE " + userData.name + " " + args.get(2) + "\n", Set.of(receiverKey));
  }

  static private SelectionKey getKey(String name, Selector selector) {
    for (var key : selector.keys()) {
      if (key.attachment() instanceof Attachment a && name.equals(a.name))
            return key;
    }

    return null;
  }

  static private void sendMessage(String message, Set<SelectionKey> receiversKeys) throws IOException{
    ByteBuffer out = ByteBuffer.wrap((message).getBytes(charset));
    for (var key : receiversKeys) {
      SocketChannel sc = (SocketChannel) key.channel();
      while (out.hasRemaining())
        sc.write(out);
      out.rewind();
    }
  }
}
