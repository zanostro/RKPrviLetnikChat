import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;




public class ChatClient extends Thread
{
	protected int serverPort = 1234;

	BufferedReader std_in = new BufferedReader(new InputStreamReader(System.in));
	String username;


	public static void main(String[] args) throws Exception {
		
		new ChatClient();
	}

	public ChatClient() throws Exception {
		Socket socket = null;
		DataInputStream in = null;
		DataOutputStream out = null;

		System.out.println("Please input username:");
		username = std_in.readLine();


		// connect to the chat server
		try {
			System.out.println("[system] connecting to chat server ...");
			socket = new Socket("localhost", serverPort); // create socket connection
			in = new DataInputStream(socket.getInputStream()); // create input stream for listening for incoming messages
			out = new DataOutputStream(socket.getOutputStream()); // create output stream for sending messages
			System.out.println("[system] connected");

			ChatClientMessageReceiver message_receiver = new ChatClientMessageReceiver(in); // create a separate thread for listening to messages from the chat server
			message_receiver.start(); // run the new thread
		} catch (Exception e) {
			e.printStackTrace(System.err);
			System.exit(1);
		}

		JSONParser jsonParser = new JSONParser();
		JSONObject jsonObject = null;


		try{
			Object obj = jsonParser.parse(new FileReader("data.json"));
			jsonObject =  (JSONObject) obj;
			jsonObject.put("username", username);
			jsonObject.put("type", "login");

			//System.out.println(jsonObject);

           this.sendMessage(jsonObject.toString(), out);

		}
		catch(Exception e){
			System.out.println(e);
			System.exit(1);
		}

		

		// read from STDIN and send messages to the chat server
		String userInput;
		while (((userInput = std_in.readLine()) != null) && !userInput.equals("\n")) { // read a line from the console
			
			if(!userInput.equals("\n") && (userInput.length() != 0)){


				if(userInput.charAt(0) == '/'){
					jsonObject.put("type", "command");
				}
				else{
					jsonObject.put("type", "global_msg");
				}
			
				jsonObject.put("msg", userInput);
				String timeStamp = new SimpleDateFormat("yyyy.MM.dd: HH.mm.ss").format(Calendar.getInstance().getTime());
				jsonObject.put("datetime", timeStamp);

				this.sendMessage(jsonObject.toString(), out); // send the message to the chat server
			}
		}

		// cleanup
		out.close();
		in.close();
		std_in.close();
		socket.close();
	}

	private void sendMessage(String message, DataOutputStream out) {
		try {
			out.writeUTF(message); // send the message to the chat server
			out.flush(); // ensure the message has been sent
		} catch (IOException e) {
			System.err.println("[system] could not send message");
			e.printStackTrace(System.err);
		}
	}
}



// wait for messages from the chat server and print the out
class ChatClientMessageReceiver extends Thread {
	private DataInputStream in;

	public ChatClientMessageReceiver(DataInputStream in) {
		this.in = in;
	}

	public void run() {
		try {
			String msg;
			JSONParser parser = new JSONParser();
			JSONObject json = null;


			while ((msg = this.in.readUTF()) != null) { // read new message
				json = (JSONObject) parser.parse(msg);
				msg = json.get("datetime") + " [" + json.get("username").toString()  + "]: "+ json.get("msg").toString();

				if(json.get("type").toString().equals("exit")){
					System.out.println("[system]: connection to the server lost. Please restart the aplication!");
					System.exit(1);
				}


				if(json.get("type").toString().equals("private")){
					msg = ("(private) --> ") + msg;
				}

				System.out.println(msg);

			}




		} catch (Exception e) {
			System.err.println("[system] could not read message");
			e.printStackTrace(System.err);
			System.exit(1);
		}
	}
}
