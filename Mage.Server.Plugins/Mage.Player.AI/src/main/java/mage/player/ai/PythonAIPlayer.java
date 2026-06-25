package mage.player.ai;

import mage.constants.RangeOfInfluence;
import mage.game.Game;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;

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
                            opponentsJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"life\": %d, \"hand_size\": %d}",
                                    opponent.getId().toString(), opponent.getName(), opponent.getLife(), opponent.getHand().size()));
                            firstOpponent = false;
                        }
                    }
                }
                opponentsJson.append("]");

                // 2. Get My Hand (NEW: Added "needs_target" check!)
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");

                    boolean canCast = false;
                    boolean needsTarget = false;

                    if (card.getSpellAbility() != null) {
                        canCast = card.getSpellAbility().canActivate(this.playerId, game).canActivate();
                        // Check if the spell requires a target to be cast!
                        if (!card.getSpellAbility().getTargets().isEmpty()) {
                            needsTarget = true;
                        }
                    }

                    int cmc = card.getManaValue();

                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"can_cast\": %b, \"cmc\": %d, \"needs_target\": %b}",
                            card.getId().toString(), safeName, canCast, cmc, needsTarget));
                    firstCard = false;
                }
                handJson.append("]");

                // 3. Get My Battlefield
                StringBuilder fieldJson = new StringBuilder("[");
                boolean firstPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (!firstPerm) fieldJson.append(", ");
                    String safeName = perm.getName().replace("\"", "\\\"");
                    fieldJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"tapped\": %b}",
                            perm.getId().toString(), safeName, perm.isTapped()));
                    firstPerm = false;
                }
                fieldJson.append("]");

                // 4. Get Opponent's Battlefield (NEW: The Radar!)
                StringBuilder oppFieldJson = new StringBuilder("[");
                boolean firstOppPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents()) {
                    // If we do NOT control it, it belongs to the enemy
                    if (!perm.getControllerId().equals(this.playerId)) {
                        if (!firstOppPerm) oppFieldJson.append(", ");
                        String safeName = perm.getName().replace("\"", "\\\"");
                        oppFieldJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\"}",
                                perm.getId().toString(), safeName));
                        firstOppPerm = false;
                    }
                }
                oppFieldJson.append("]");

                // 5. Send Massive JSON Payload
                String gameStateJson = String.format("{\"request_type\": \"priority\", \"turn\": %d, \"is_my_turn\": %b, \"is_main_phase\": %b, \"my_life\": %d, \"opponents\": %s, \"my_hand\": %s, \"my_battlefield\": %s, \"opp_battlefield\": %s}",
                        turnNumber, isMyTurn, isMainPhase, myLife, opponentsJson.toString(), handJson.toString(), fieldJson.toString(), oppFieldJson.toString());
                out.println(gameStateJson);

                // 6. Wait for Python response
                String response = in.readLine();
                socket.close();

                // 7. Process Python's Action
                if (response != null && response.startsWith("PLAY:")) {
                    String idString = response.substring(5).trim();
                    try {
                        mage.cards.Card cardToPlay = game.getCard(java.util.UUID.fromString(idString));
                        if (cardToPlay != null && cardToPlay.isLand(game) && myPlayer.canPlayLand() && isMainPhase) {
                            if (myPlayer.playLand(cardToPlay, game, false)) return true;
                            else myPlayer.pass(game);
                        }
                    } catch (Exception ex) {}

                } else if (response != null && response.startsWith("CAST:")) {
                    // NEW: Split the command to look for a target ID
                    String[] parts = response.split(":");
                    try {
                        java.util.UUID cardId = java.util.UUID.fromString(parts[1].trim());
                        mage.cards.Card cardToPlay = game.getCard(cardId);

                        if (cardToPlay != null && cardToPlay.getSpellAbility() != null) {
                            System.out.println("DEBUG: Python attempting to cast: " + cardToPlay.getName());

                            // If Python sent a 3rd piece of data, it's a target! Inject it!
                            if (parts.length > 2) {
                                java.util.UUID targetId = java.util.UUID.fromString(parts[2].trim());
                                cardToPlay.getSpellAbility().getTargets().get(0).addTarget(targetId, cardToPlay.getSpellAbility(), game);
                                System.out.println("DEBUG: Aiming at Target ID: " + targetId.toString());
                            }

                            boolean success = myPlayer.activateAbility(cardToPlay.getSpellAbility(), game);
                            if (success) return true;
                            else {
                                System.out.println("DEBUG: Cast aborted (likely invalid target). Passing.");
                                myPlayer.pass(game);
                            }
                        }
                    } catch (Exception ex) { myPlayer.pass(game); }

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
    @Override
    public void selectAttackers(Game game, java.util.UUID attackingPlayerId) {
        try {
            Socket socket = new Socket("127.0.0.1", 5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                // 1. Get Opponents
                StringBuilder opponentsJson = new StringBuilder("[");
                boolean firstOpp = true;
                for (java.util.UUID currentId : game.getPlayerList()) {
                    if (!currentId.equals(this.playerId)) {
                        if (!firstOpp) opponentsJson.append(", ");
                        opponentsJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\"}",
                                currentId.toString(), game.getPlayer(currentId).getName()));
                        firstOpp = false;
                    }
                }
                opponentsJson.append("]");

                // 2. Get the Army
                StringBuilder attackersJson = new StringBuilder("[");
                boolean firstAttacker = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (perm.isCreature(game) && perm.canAttack(null, game)) {
                        if (!firstAttacker) attackersJson.append(", ");
                        String safeName = perm.getName().replace("\"", "\\\"");
                        attackersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"power\": %d}",
                                perm.getId().toString(), safeName, perm.getPower().getValue()));
                        firstAttacker = false;
                    }
                }
                attackersJson.append("]");

                // 3. Send Combat Payload
                String gameStateJson = String.format("{\"request_type\": \"declare_attackers\", \"opponents\": %s, \"possible_attackers\": %s}",
                        opponentsJson.toString(), attackersJson.toString());
                out.println(gameStateJson);

                // 4. Wait for Python
                String response = in.readLine();
                socket.close();

                // 5. Execute
                if (response != null && response.startsWith("ATTACK:")) {
                    String payload = response.substring(7).trim();
                    if (!payload.isEmpty()) {
                        String[] attacks = payload.split(",");
                        for (String attack : attacks) {
                            String[] parts = attack.split(":");
                            if (parts.length == 2) {
                                java.util.UUID attackerId = java.util.UUID.fromString(parts[0].trim());
                                java.util.UUID defenderId = java.util.UUID.fromString(parts[1].trim());
                                myPlayer.declareAttacker(attackerId, defenderId, game, false);
                                System.out.println("DEBUG: Declaring attacker ID " + attackerId);
                            }
                        }
                    }
                } else if (response != null && response.equals("PASS")) {
                    System.out.println("DEBUG: Python chose not to attack.");
                }
            } else {
                socket.close();
            }
        } catch (Exception e) {}
    }
}