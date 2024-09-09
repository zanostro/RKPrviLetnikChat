import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.time.LocalDateTime;  
import java.time.format.DateTimeFormatter; 
import javax.net.ssl.*;
import java.security.*;




public class ChatServer {

	protected int serverPort = 1234;

	protected volatile List<Client> clients = new ArrayList<Client>(); // list of clients


	//key-command, value-argument number
	protected HashMap<String, Integer> commands = new HashMap<String, Integer>(){{
		put("/blacklist" , 3);
		put("/blacklistprint" , 1);
		put("/exit", 1);
		put("/find", 2);
		put("/help", 1); 
		put("/private", 3);
		put("/username", 1);
	}};

	protected String helpString = "List of all commands: \n" + 
		"	/blacklist -add <username> -- adds user to your blacklist (you will not recieve their global/private messages - but they can still see your global messages)\n" +
		"	/blacklist -rm <username> -- removes user from your blacklist\n" +  
		"	/blacklistprint -- outputs your blacklist\n" + 
		"	/exit -- disconnects client \n" + 
		"	/find <username> -- check if user is connected to the server \n" + 
		"	/help -- help screen \n" + 
		"	/private <username> <text> -- sends a private message \n" + 
		"	/username -- displays username \n" +
		"----------------------------------\n"
		;

	public static void main(String[] args) throws Exception {
	
		//String timeStamp = new SimpleDateFormat("yyyy.MM.dd: HH.mm.ss").format(Calendar.getInstance().getTime());
		//System.out.println(timeStamp);

		new ChatServer();
	}

	public ChatServer() {
		SSLServerSocket serverSocket = null;

		// create socket
		try {
			//serverSocket = new ServerSocket(this.serverPort); // create the ServerSocket

			String passphrase = "123456";

			KeyStore clientKeyStore = KeyStore.getInstance("JKS"); // KeyStore za shranjevanje odjemalčevih javnih ključev (certifikatov)
			clientKeyStore.load(new FileInputStream("clients.public"), passphrase.toCharArray());

			
			KeyStore serverKeyStore = KeyStore.getInstance("JKS"); // KeyStore za shranjevanje strežnikovega tajnega in javnega ključa
			serverKeyStore.load(new FileInputStream("server.private"), passphrase.toCharArray());

			
			TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
			tmf.init(clientKeyStore);

			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(serverKeyStore, passphrase.toCharArray());

			SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), (new SecureRandom()));

			
			SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
			serverSocket = (SSLServerSocket) factory.createServerSocket(serverPort);
			serverSocket.setNeedClientAuth(true); // tudi odjemalec se MORA predstaviti s certifikatom
			serverSocket.setEnabledCipherSuites(new String[] {"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});


		} catch (Exception e) {
			System.err.println("[system] could not create socket on port " + this.serverPort);
			e.printStackTrace(System.err);
			System.exit(1);
		}

		// start listening for new connections
		System.out.println("[system] listening ...");
		try {
			while (true) {
				SSLSocket newClientSocket = (SSLSocket) serverSocket.accept(); // wait for a new client connection
				newClientSocket.startHandshake();

				Client newClient = new Client(newClientSocket);

				synchronized(this) {
					clients.add(newClient); // add client to the list of clients
				}

				ChatServerConnector conn = new ChatServerConnector(this, newClient); // create a new thread for communication with the new client
				conn.start(); // run the new thread
			}
		} catch (Exception e) {
			System.err.println("[error] Accept failed.");
			e.printStackTrace(System.err);
			System.exit(1);
		}

		// close socket
		System.out.println("[system] closing server socket ...");
		try {
			serverSocket.close();
		} catch (IOException e) {
			e.printStackTrace(System.err);
			System.exit(1);
		}
	}

	public Client findClient(String username){
		
		for (Client client : clients) {
			
			if(client.username == null) continue;
			if(client.username.equals(username)) return client;

		}
	
		return null;
	}

	// send a message to all clients connected to the server
	
	public void sendToAllClients(Client source,JSONObject paket){
		for (Client client : clients) {
			sendToClient(source, client, paket);
		}
	}

	public void sendToAllClients(JSONObject paket){
		sendToAllClients(new Client("system"), paket);
		
	}


	public void sendToClient(Client source, Client destination, JSONObject paket){

		SSLSocket socket = destination.socket;
		DataOutputStream out;	
		String msg = paket.toString();

		try {
			if(destination.isOnBlacklist(source.username)) throw new UsernameBlacklistedException("Username blacklisted");
			out = new DataOutputStream(socket.getOutputStream());
			out.writeUTF(msg);

		} catch (IOException e) {
			System.err.println("[system] could not send message to a client");
			e.printStackTrace();

		} catch (UsernameBlacklistedException e) {}
	}


	public void removeClient(Client client) {
		synchronized(this) {
			clients.remove(client);
		}
	}
}

class ChatServerConnector extends Thread {
	private ChatServer server;
	private Client client = null;

	public ChatServerConnector(ChatServer server, Client client) {
		this.server = server;
		this.client = client;
		try {
			String username = ((SSLSocket) client.socket).getSession().getPeerPrincipal().getName();
		} catch (SSLPeerUnverifiedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


	}

	private volatile boolean exit = false;

	public void run() {
		System.out.println("[system] connected with " + this.client.socket.getInetAddress().getHostName() + ":" + this.client.socket.getPort());

		DataInputStream in;
		try {
			in = new DataInputStream(this.client.socket.getInputStream()); // create input stream for listening for incoming messages
		} catch (IOException e) {
			System.err.println("[system] could not open input stream!");
			e.printStackTrace(System.err);
			this.server.removeClient(client);
			return;
		}

		while (!exit) { // infinite loop in which this thread waits for incoming messages and processes them
			String msg_received;
			try {
				msg_received = in.readUTF(); // read the message from the client
			} catch (Exception e) {
				System.err.println("[system] there was a problem while reading message client on port " + this.client.socket.getPort() + ", removing client");
				e.printStackTrace(System.err);
				this.server.removeClient(this.client);
				return;
			}

			if (msg_received.length() == 0) // invalid message
				continue;

			//System.out.println("--recieved package:[" + this.client.socket.getPort() + "] : " + msg_received); // print the incoming message in the console
			

			JSONParser parser = new JSONParser();  
			JSONObject json = null;
			String type = null;

			try {
				json = (JSONObject) parser.parse(msg_received);
				type = json.get("type").toString();

			} catch (ParseException e1) {
				e1.printStackTrace();
			}  

			if(json != null){
				String recieved = "recieved: " + msg_received.toUpperCase(); // TODO
				String msg = json.get("msg").toString();

				if(!msg.equals("")) System.out.println(json.get("datetime") + " ["+ json.get("username") + "]: " + msg);


				if(type.equals("login")){

					//preveri, ce username ze obstaja
					try{

						String username = json.get("username").toString();
						String [] split = username.split(" ");
						if(split.length > 1) {
							System.out.println("[system]: Error: name violation");
							throw new InvalidUsernameException("Name violation - no whitespaces allowed.");
						}

						if(server.findClient(username) == null) {
							System.out.println("[system]: Adding user " + json.get("username").toString() + " to the chat");
							this.client.username = json.get("username").toString();
						}
						else{
							System.out.println("[system]: Error, username" + username + " already exists. Disconnecting new user.");
							throw new InvalidUsernameException("User named " + username + "is already connected to the chat. Disconnecting!");
							
						}
					}
					catch(InvalidUsernameException e){
						json.put("username", "server");
						json.put("msg", "Error: " + e.toString());
						json.put("type", "exit");
						server.sendToClient(new Client("system"), this.client, json);
						server.removeClient(this.client);
						exit = true;
					}
				}

				if(type.equals("command")){
					String [] split = msg.split(" ");
					String command = split[0];

					if(server.commands.containsKey(command)){
						try{
							
							if(split.length == server.commands.get(command)){

								if(command.equals("/blacklist")){
									if(split[1].equals("-add")){
										client.addToBlacklist(split[2]);

										if(split[2].equals(this.client.username)) throw new InvalidArgumentException("You cannot add yourself to the blacklist.");
										if(split[2].equals("system")) throw new InvalidArgumentException("The system cannot be ignored!");
										if(split[2].equals("server")) throw new InvalidArgumentException("The system cannot be ignored!");

										json.put("username", "server");
										json.put("msg", "User added to the blacklist.");
										server.sendToClient(client, client, json);
									}
									else{
										if(split[1].equals("-rm")){
											client.removeFromBlacklist(split[2]);
											json.put("username", "server");
											json.put("msg", "User removed the from blacklist.");
											server.sendToClient(client, client, json);
										}

										else{
											throw new InvalidArgumentException("Invalid arguments. Try /help to see a list of connamds.");
										}
									}
								}

								if(command.equals("/blacklistprint")){
									json.put("username", "server");
									json.put("msg", client.getBlacklistedNames());
									server.sendToClient(client, client, json);
								}

								if(command.equals("/exit")){
									System.out.println("Disconnecting user " + json.get("username"));

									server.removeClient(this.client);
									json.put("username", "server");
									json.put("type", "exit");
									json.put("msg", "Disconnecting...");
									server.sendToClient(client, client, json);
									exit = true;
								}

								if(command.equals("/find")){
									Client c = server.findClient(split[1]);
									if(c == null) throw new InvalidArgumentException("Person named: " + split[1] + " does not exist.");
									if(c == client) {
										json.put("username", "server");
										json.put("msg", "Have you forgotten your name? The person you are looking for is you!");
										server.sendToClient(client, client, json);
									}
									else{
										json.put("username", "server");
										json.put("msg", "Person " + split[1] + " is connected to the chat.");
										server.sendToClient(client, client, json);
									}

								}
								if(command.equals("/help")){
									json.put("username", "server");
									json.put("msg", server.helpString);
									server.sendToClient(client, client, json);
								}
								if(command.equals("/private")){
									if(client.isOnBlacklist(split[1])) throw new InvalidArgumentException("You cannot send private messages to someone you blacklisted.");
									if(client.username.equals(split[1])) throw new InvalidArgumentException("You cannot send a private message to yourself.");

									Client reciever = server.findClient(split[1]);
									if(reciever== null) throw new InvalidArgumentException("User " + split[1] + " is not connected to the chat.");
									json.put("msg", split[2]);
									json.put("type", "private");
									server.sendToClient(this.client, reciever, json);
									server.sendToClient(this.client, client , json);

								}


								if(command.equals("/username")){
									json.put("username", "server");
									json.put("msg", this.client.username);
									server.sendToClient(client, client, json);
								}
							}

							else throw new InvalidArgumentException("Invalid Arguments. Try /help for a list of possible commands.");
						}

						catch (InvalidArgumentException e){
							json.put("username", "server");
							json.put("msg", "Error: " + e.getMessage());

							server.sendToClient(client, client, json);
						}
					}
					else{
						json.put("username", "server");
						json.put("msg", "No such command exists. Try /help for a list of commands");

						server.sendToClient(client,client, json);
					}
				}

				//msg za vse
				if(type.equals("global_msg")){

					try {
						this.server.sendToAllClients(client, json); // send message to all clients

					} catch (Exception e) {
						System.err.println("[system] there was a problem while sending the message to all clients");
						e.printStackTrace(System.err);
						continue;
					}
				}
			}
		}
	}
}

class Client{

	public volatile String username = null;
	public volatile SSLSocket socket = null;
	public volatile List<String> blacklist = new ArrayList<String>();

	public Client(String username, SSLSocket socket){
		this.username = username;
		this.socket = socket;
	}

	public Client(SSLSocket socket){
		this.socket = socket;
	}

	public void addToBlacklist(String c){
		if(c.equals("system")) return;
		if(c.equals(this.username)) return;
		if(!blacklist.contains(c)) blacklist.add(c);
	}

	public Client(String username){
		this.username = username;
	}

	public void removeFromBlacklist(String c){
		blacklist.remove(c);
	}

	public String getBlacklistedNames(){
		String names  = "\n";
		for (String client : blacklist) {
			names += "	" + client + "\n";
		}
		return names;
	}

	public Boolean isOnBlacklist(String c){
		return blacklist.contains(c);
	}


}



//exceptioni
class InvalidArgumentException extends Exception{
	public InvalidArgumentException(String errorMeString){
		super(errorMeString);
	}
}

class InvalidUsernameException extends Exception{
	public InvalidUsernameException(String errorMeString){
		super(errorMeString);
	}
}

class UsernameBlacklistedException extends Exception{
	public UsernameBlacklistedException(String errorMeString){
		super(errorMeString);
	}
}