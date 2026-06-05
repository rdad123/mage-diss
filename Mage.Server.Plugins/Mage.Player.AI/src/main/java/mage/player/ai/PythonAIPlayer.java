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
                int myLife = myPlayer.getLife();
                int turnNumber = game.getTurnNum();

                // 1. Get Opponents
                StringBuilder opponentsJson = new StringBuilder("[");
                boolean firstOpponent = true;
                for (java.util.UUID currentId : game.getPlayerList()) {
                    if (!currentId.equals(this.playerId)) {
                        mage.players.Player opponent = game.getPlayer(currentId);
                        if (opponent != null) {
                            if (!firstOpponent) opponentsJson.append(", ");
                            opponentsJson.append(String.format(
                                    "{\"name\": \"%s\", \"life\": %d, \"hand_size\": %d}",
                                    opponent.getName(), opponent.getLife(), opponent.getHand().size()
                            ));
                            firstOpponent = false;
                        }
                    }
                }
                opponentsJson.append("]");

                // 2. Get My Hand (Need IDs to cast them later!)
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    // We must escape quotes in card names just in case!
                    String safeName = card.getName().replace("\"", "\\\"");
                    handJson.append(String.format(
                            "{\"id\": \"%s\", \"name\": \"%s\"}",
                            card.getId().toString(), safeName
                    ));
                    firstCard = false;
                }
                handJson.append("]");

                // 3. Get My Battlefield (Lands, Creatures, etc.)
                StringBuilder fieldJson = new StringBuilder("[");
                boolean firstPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (!firstPerm) fieldJson.append(", ");
                    String safeName = perm.getName().replace("\"", "\\\"");
                    fieldJson.append(String.format(
                            "{\"id\": \"%s\", \"name\": \"%s\", \"tapped\": %b, \"power\": %d, \"toughness\": %d}",
                            perm.getId().toString(), safeName, perm.isTapped(), perm.getPower().getValue(), perm.getToughness().getValue()
                    ));
                    firstPerm = false;
                }
                fieldJson.append("]");

                // 4. Combine into final massive JSON string
                String gameStateJson = String.format(
                        "{\"turn\": %d, \"my_life\": %d, \"opponents\": %s, \"my_hand\": %s, \"my_battlefield\": %s}",
                        turnNumber, myLife, opponentsJson.toString(), handJson.toString(), fieldJson.toString()
                );

                // 5. Send it
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