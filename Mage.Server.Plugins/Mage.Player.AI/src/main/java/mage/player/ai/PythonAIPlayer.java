package mage.player.ai;

import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.result.ResultProtos;

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
        try {
            Socket socket = new Socket("127.0.0.1", 5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                int life = myPlayer.getLife();
                int handSize = myPlayer.getHand().size();
                int turnNumber = game.getTurnNum();

                String gameStateJson = String.format(
                        "{\"life\": %d, \"hand_size\": %d, \"turn\": %d}",
                        life, handSize, turnNumber
                );

                out.println(gameStateJson);
            }

            String response = in.readLine();

            socket.close();

        } catch (Exception e) {
            System.out.println("Failed to connect to Python.");
        }

        return super.priority(game);
    }
}