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
                boolean isMainPhase = game.canPlaySorcery(this.playerId);

                // 1. Get Opponents
                StringBuilder opponentsJson = new StringBuilder("[");
                boolean firstOpponent = true;
                for (java.util.UUID currentId : game.getPlayerList()) {
                    if (!currentId.equals(this.playerId)) {
                        mage.players.Player opponent = game.getPlayer(currentId);
                        if (opponent != null) {
                            if (!firstOpponent) opponentsJson.append(", ");
                            opponentsJson.append(String.format("{\"name\": \"%s\", \"life\": %d, \"hand_size\": %d}",
                                    opponent.getName(), opponent.getLife(), opponent.getHand().size()));
                            firstOpponent = false;
                        }
                    }
                }
                opponentsJson.append("]");

                // 2. Get My Hand (NEW: Asking Java for the exact integer CMC!)
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");

                    boolean canCast = false;
                    if (card.getSpellAbility() != null) {
                        canCast = card.getSpellAbility().canActivate(this.playerId, game).canActivate();
                    }

                    // getManaValue() returns the exact integer cost (e.g. 6 for Time Stop)
                    // Note: If your version throws an error here, change it to card.getConvertedManaCost()
                    int cmc = card.getManaValue();

                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"can_cast\": %b, \"cmc\": %d}",
                            card.getId().toString(), safeName, canCast, cmc));
                    firstCard = false;
                }
                handJson.append("]");

                // 3. Get My Battlefield
                StringBuilder fieldJson = new StringBuilder("[");
                boolean firstPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (!firstPerm) fieldJson.append(", ");
                    String safeName = perm.getName().replace("\"", "\\\"");
                    fieldJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"tapped\": %b, \"power\": %d, \"toughness\": %d}",
                            perm.getId().toString(), safeName, perm.isTapped(), perm.getPower().getValue(), perm.getToughness().getValue()));
                    firstPerm = false;
                }
                fieldJson.append("]");

                // 4. Send JSON Payload
                String gameStateJson = String.format("{\"request_type\": \"priority\", \"turn\": %d, \"is_my_turn\": %b, \"is_main_phase\": %b, \"my_life\": %d, \"opponents\": %s, \"my_hand\": %s, \"my_battlefield\": %s}",
                        turnNumber, isMyTurn, isMainPhase, myLife, opponentsJson.toString(), handJson.toString(), fieldJson.toString());
                out.println(gameStateJson);

                // 5. Wait for Python response
                String response = in.readLine();
                socket.close();

                // 6. Process Python's Action
                if (response != null && response.startsWith("PLAY:")) {
                    String idString = response.substring(5).trim();
                    try {
                        mage.cards.Card cardToPlay = game.getCard(java.util.UUID.fromString(idString));
                        if (cardToPlay != null && cardToPlay.isLand(game) && myPlayer.canPlayLand() && isMainPhase) {
                            if (myPlayer.playLand(cardToPlay, game, false)) {
                                return true;
                            } else {
                                myPlayer.pass(game); // SAFETY NET
                            }
                        }
                    } catch (Exception ex) {}

                } else if (response != null && response.startsWith("CAST:")) {
                    String idString = response.substring(5).trim();
                    try {
                        mage.cards.Card cardToPlay = game.getCard(java.util.UUID.fromString(idString));
                        if (cardToPlay != null && cardToPlay.getSpellAbility() != null) {
                            System.out.println("DEBUG: Python attempting to cast: " + cardToPlay.getName());

                            boolean success = myPlayer.activateAbility(cardToPlay.getSpellAbility(), game);
                            System.out.println("DEBUG: Did the spell cast successfully? " + success);

                            if (success) {
                                return true;
                            } else {
                                // THE FREEZE FIX: If the cast aborts (e.g. missing targets), safely pass!
                                System.out.println("DEBUG: Cast aborted. Safely passing priority.");
                                myPlayer.pass(game);
                            }
                        }
                    } catch (Exception ex) {}

                } else if (response != null && response.equals("PASS")) {
                    myPlayer.pass(game);
                    try { Thread.sleep(50); } catch (Exception ignore) {}
                    return false;
                }
            } else {
                socket.close();
            }
        } catch (Exception e) {}

        try { Thread.sleep(50); } catch (Exception ignore) {}
        return false;
    }

    @Override
    public boolean chooseMulligan(Game game) {
        try {
            Socket socket = new Socket("127.0.0.1", 5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                // 2. Get My Hand (NEW: Added mana_cost string!)
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");

                    boolean canCast = false;
                    if (card.getSpellAbility() != null) {
                        canCast = card.getSpellAbility().canActivate(this.playerId, game).canActivate();
                    }

                    // card.getManaCost().toString() outputs formats like "{2}{U}{U}"
                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"can_cast\": %b, \"mana_cost\": \"%s\"}",
                            card.getId().toString(), safeName, canCast, card.getManaCost().toString()));
                    firstCard = false;
                }
                handJson.append("]");

                String gameStateJson = String.format("{\"request_type\": \"mulligan\", \"my_hand\": %s}", handJson.toString());
                out.println(gameStateJson);

                // DEADLOCK FIX: Only wait for a response if we actually sent the JSON!
                String response = in.readLine();
                socket.close();

                if (response != null && response.equals("MULLIGAN")) {
                    System.out.println("DEBUG: Python decided to MULLIGAN.");
                    return true;
                } else if (response != null && response.equals("KEEP")) {
                    System.out.println("DEBUG: Python decided to KEEP.");
                    return false;
                }
            } else {
                socket.close();
            }
        } catch (Exception e) {
            // Silently catch
        }
        return false;
    }
}