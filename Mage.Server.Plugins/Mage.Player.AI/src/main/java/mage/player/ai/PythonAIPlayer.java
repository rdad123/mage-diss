package mage.player.ai;

import mage.constants.RangeOfInfluence;
import mage.game.Game;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class PythonAIPlayer extends ComputerPlayer {

    public PythonAIPlayer(String name, RangeOfInfluence range, int skill) {
        super(name, range);
    }

    public PythonAIPlayer(final PythonAIPlayer player) {
        super(player);
    }

    @Override
    public PythonAIPlayer copy() {
        return new PythonAIPlayer(this);
    }

    @Override
    public boolean priority(Game game) {
        System.out.println("====== PYTHON AI IS THINKING ======");

        // --- THE NETWORK BRIDGE ---
        try {
            // 1. "Dial" the Python server on local port 5000
            Socket socket = new Socket("127.0.0.1", 5000);

            // 2. Set up the audio pieces of the phone (Send and Receive)
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // 3. Send a test message to Python
            out.println("PING_FROM_JAVA");

            // 4. Wait patiently for Python to send a message back
            String response = in.readLine();
            System.out.println("Received from Python: " + response);

            // 5. Hang up the phone
            socket.close();

        } catch (Exception e) {
            System.out.println("Failed to connect: Is the Python script running?");
        }
        // --------------------------

        // We still return the default AI priority so the game doesn't freeze!
        return super.priority(game);
    }
}