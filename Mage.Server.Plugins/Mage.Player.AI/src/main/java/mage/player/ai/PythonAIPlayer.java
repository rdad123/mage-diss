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

                boolean isMyTurn = game.isActivePlayer(this.playerId);

                // Tell Python if it is the Main Phase (Stack is empty, legal to play lands/creatures)
                boolean isMainPhase = game.canPlaySorcery(this.playerId);

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

                // 2. Get My Hand
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");
                    handJson.append(String.format(
                            "{\"id\": \"%s\", \"name\": \"%s\"}",
                            card.getId().toString(), safeName
                    ));
                    firstCard = false;
                }
                handJson.append("]");

                // 3. Get My Battlefield
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

                // 4. Send JSON Payload
                String gameStateJson = String.format(
                        "{\"turn\": %d, \"is_my_turn\": %b, \"is_main_phase\": %b, \"my_life\": %d, \"opponents\": %s, \"my_hand\": %s, \"my_battlefield\": %s}",
                        turnNumber, isMyTurn, isMainPhase, myLife, opponentsJson.toString(), handJson.toString(), fieldJson.toString()
                );
                out.println(gameStateJson);

            }

            //start listening for python response
            String response = in.readLine();
            socket.close(); // Hang up the phone

            if (response != null && response.startsWith("PLAY:")) {
                String idString = response.substring(5).trim();
                try {
                    java.util.UUID cardId = java.util.UUID.fromString(idString);
                    mage.cards.Card cardToPlay = game.getCard(cardId);

                    if (cardToPlay != null) {
                        System.out.println("DEBUG: Found the card object: " + cardToPlay.getName());

                        boolean inHand = myPlayer.getHand().contains(cardId);
                        System.out.println("DEBUG: Is it in my hand? " + inHand);

                        boolean isLand = cardToPlay.isLand(game);
                        System.out.println("DEBUG: Is it a land? " + isLand);

                        boolean canPlayLand = myPlayer.canPlayLand();
                        System.out.println("DEBUG: Has myPlayer hit their land drop limit? " + !canPlayLand);

                        boolean isMainPhase = game.canPlaySorcery(myPlayer.getId());
                        System.out.println("DEBUG: Is it the Main Phase? " + isMainPhase);

                        if (inHand && isLand && canPlayLand && isMainPhase) {
                            System.out.println("DEBUG: All checks passed. Attempting playLand...");
                            boolean success = myPlayer.playLand(cardToPlay, game, false);
                            System.out.println("DEBUG: Did the engine execute it? " + success);

                            if (success) {
                                return true;
                            }
                        } else {
                            System.out.println("DEBUG: One of the checks failed. Skipping playLand.");
                        }
                    } else {
                        System.out.println("DEBUG: Failed to find a card with ID: " + idString);
                    }
                } catch (Exception ex) {
                    System.out.println("ERROR: Something crashed during the play check. " + ex.getMessage());
                }
            }
            // ========================================================

        } catch (Exception e) {
            System.out.println("Failed to connect to Python.");
        }

        // If Python sends "PASS" or fails, fall back to default AI
        return super.priority(game);
    }
}