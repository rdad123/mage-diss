package mage.player.ai;

import mage.constants.Outcome;
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
        System.out.println("\n=================================");
        System.out.println("BOT CONSTRUCTOR FIRING!");
        System.out.println("=================================\n");
    }

    public PythonAIPlayer(final PythonAIPlayer player) {
        super(player);
        System.out.println("\n=================================");
        System.out.println("BOT CONSTRUCTOR FIRING!");
        System.out.println("=================================\n");
    }

    @Override
    public PythonAIPlayer copy() {

        return new PythonAIPlayer(this);
    }

    // ========================================================
    // 1. MULLIGAN PHASE (Renamed to match parent class exactly!)
    // ========================================================
    @Override
    public boolean chooseMulligan(Game game) {
        System.out.println("PYTHON BOT: Auto-keeping opening hand!");
        try {
            Socket socket = new Socket("127.0.0.1", 5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");

                    // NEW: Tell Python if it's a land during the mulligan phase!
                    boolean isLand = card.isLand(game);

                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"is_land\": %b}",
                            card.getId().toString(), safeName, isLand));
                    firstCard = false;
                }
                handJson.append("]");

                String gameStateJson = String.format("{\"request_type\": \"mulligan\", \"my_hand\": %s}", handJson.toString());
                out.println(gameStateJson);

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

    // ========================================================
    // 2. YES/NO TRIGGERS (New Phase!)
    // ========================================================
    // ==========================================================
    // NEW HOOK: Handle Optional Yes/No Triggers (e.g., "You may...")
    // ==========================================================
    @Override
    public boolean chooseUse(mage.constants.Outcome outcome, String message, String secondMessage, String trueText, String falseText, mage.abilities.Ability source, mage.game.Game game) {
        try {
            Socket socket = new Socket("127.0.0.1", 5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Send the prompt to Python
            String safeMessage = message != null ? message.replace("\"", "\\\"") : "";
            String json = String.format("{\"request_type\": \"choose_use\", \"message\": \"%s\"}", safeMessage);
            out.println(json);

            String response = in.readLine();
            socket.close();

            if (response != null) {
                if (response.equals("YES")) {
                    System.out.println("DEBUG: Python chose YES for prompt.");
                    return true;
                } else if (response.equals("NO")) {
                    System.out.println("DEBUG: Python chose NO for prompt.");
                    return false;
                }
            }
        } catch (Exception e) {}

        return super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game);
    }

    // ========================================================
    // 3. PRIORITY (Now features "The Stack" for Instant Speed)
    // ========================================================
    @Override
    public boolean priority(Game game) {
        try {
            Socket socket = new Socket("127.0.0.1", 5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                int turnNumber = game.getTurnNum();
                boolean isMyTurn = game.isActivePlayer(this.playerId);
                boolean isMainPhase = game.canPlaySorcery(this.playerId);

                // Opponents
                StringBuilder opponentsJson = new StringBuilder("[");
                boolean firstOpponent = true;
                for (java.util.UUID currentId : game.getPlayerList()) {
                    if (!currentId.equals(this.playerId)) {
                        mage.players.Player opponent = game.getPlayer(currentId);
                        if (opponent != null) {
                            if (!firstOpponent) opponentsJson.append(", ");
                            opponentsJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"life\": %d}",
                                    opponent.getId().toString(), opponent.getName(), opponent.getLife()));
                            firstOpponent = false;
                        }
                    }
                }
                opponentsJson.append("]");

                // Get Hand
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");
                    boolean canCast = false;
                    boolean needsTarget = false;
                    if (card.getSpellAbility() != null) {
                        canCast = card.getSpellAbility().canActivate(this.playerId, game).canActivate();
                        if (!card.getSpellAbility().getTargets().isEmpty()) {
                            needsTarget = true;
                        }
                    }
                    int cmc = card.getManaValue();
                    boolean isLand = card.isLand(game); // NEW FLAG

                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"can_cast\": %b, \"cmc\": %d, \"needs_target\": %b, \"is_land\": %b}",
                            card.getId().toString(), safeName, canCast, cmc, needsTarget, isLand));
                    firstCard = false;
                }
                handJson.append("]");

                // My Battlefield
                StringBuilder fieldJson = new StringBuilder("[");
                boolean firstPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (!firstPerm) fieldJson.append(", ");
                    boolean isLand = perm.isLand(game);

                    // --- Extract Activated Abilities ---
                    StringBuilder abilitiesJson = new StringBuilder("[");
                    boolean firstAb = true;
                    for (mage.abilities.Ability ability : perm.getAbilities(game)) {
                        if (ability instanceof mage.abilities.ActivatedAbility) {
                            if (ability.getAbilityType() == mage.constants.AbilityType.ACTIVATED_NONMANA) {
                                if (ability.getZone() == mage.constants.Zone.BATTLEFIELD) {
                                    if (((mage.abilities.ActivatedAbility) ability).canActivate(this.playerId, game).canActivate()) {
                                        if (!firstAb) abilitiesJson.append(", ");
                                        String abName = ability.toString().replace("\"", "\\\"");
                                        abilitiesJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\"}",
                                                ability.getId().toString(), abName));
                                        firstAb = false;
                                    }
                                }
                            }
                        }
                    }
                    abilitiesJson.append("]");

                    fieldJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"tapped\": %b, \"is_land\": %b, \"abilities\": %s}",
                            perm.getId().toString(), perm.getName().replace("\"", "\\\""), perm.isTapped(), isLand, abilitiesJson.toString()));
                    firstPerm = false;
                }
                fieldJson.append("]");

                // Opponent Battlefield
                StringBuilder oppFieldJson = new StringBuilder("[");
                boolean firstOppPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents()) {
                    if (!perm.getControllerId().equals(this.playerId)) {
                        if (!firstOppPerm) oppFieldJson.append(", ");
                        oppFieldJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\"}",
                                perm.getId().toString(), perm.getName().replace("\"", "\\\"")));
                        firstOppPerm = false;
                    }
                }
                oppFieldJson.append("]");

                // NEW: THE STACK (What are we responding to?)
                StringBuilder stackJson = new StringBuilder("[");
                boolean firstStack = true;
                for (mage.game.stack.StackObject stackObj : game.getStack()) {
                    if (!firstStack) stackJson.append(", ");
                    stackJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\"}",
                            stackObj.getId().toString(), stackObj.getName().replace("\"", "\\\"")));
                    firstStack = false;
                }
                stackJson.append("]");

                String gameStateJson = String.format("{\"request_type\": \"priority\", \"turn\": %d, \"is_my_turn\": %b, \"is_main_phase\": %b, \"my_hand\": %s, \"my_battlefield\": %s, \"opp_battlefield\": %s, \"stack\": %s}",
                        turnNumber, isMyTurn, isMainPhase, handJson.toString(), fieldJson.toString(), oppFieldJson.toString(), stackJson.toString());
                out.println(gameStateJson);

                String response = in.readLine();
                socket.close();

                // PLAYING/CASTING LOGIC
                if (response != null && response.startsWith("PLAY:")) {
                    String idString = response.substring(5).trim();
                    try {
                        mage.cards.Card card = game.getCard(java.util.UUID.fromString(idString));
                        if (card != null && card.isLand(game) && myPlayer.canPlayLand() && isMainPhase) {
                            if (myPlayer.playLand(card, game, false)) return true;
                            else myPlayer.pass(game);
                        }
                    } catch (Exception ex) { myPlayer.pass(game); }

                } else if (response != null && response.startsWith("CAST:")) {
                    String[] parts = response.split(":");
                    try {
                        mage.cards.Card card = game.getCard(java.util.UUID.fromString(parts[1].trim()));
                        if (card != null && card.getSpellAbility() != null) {
                            if (parts.length > 2) {
                                java.util.UUID targetId = java.util.UUID.fromString(parts[2].trim());
                                card.getSpellAbility().getTargets().get(0).addTarget(targetId, card.getSpellAbility(), game);
                            }
                            if (myPlayer.activateAbility(card.getSpellAbility(), game)) return true;
                            else myPlayer.pass(game);
                        }
                    } catch (Exception ex) { myPlayer.pass(game); }

                } else if (response != null && response.startsWith("ACTIVATE:")) {
                    String abilityIdStr = response.substring(9).trim();
                    try {
                        java.util.UUID abilityId = java.util.UUID.fromString(abilityIdStr);
                        boolean activated = false;

                        // Find the matching permanent and ability
                        for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                            for (mage.abilities.Ability ab : perm.getAbilities(game)) {
                                if (ab.getId().equals(abilityId)) {
                                    if (ab instanceof mage.abilities.ActivatedAbility) {
                                        if (myPlayer.activateAbility((mage.abilities.ActivatedAbility) ab, game)) {
                                            activated = true;
                                        }
                                    }
                                    break;
                                }
                            }
                            if (activated) break;
                        }
                        if (activated) return true;
                        else myPlayer.pass(game);
                    } catch (Exception ex) { myPlayer.pass(game); }

                } else if (response != null && response.equals("PASS")) {
                    myPlayer.pass(game);
                    try { Thread.sleep(50); } catch (Exception ignore) {}
                    return false;
                }
            } else { socket.close(); }
        } catch (Exception e) {
            System.err.println("PRIORITY SOCKET ERROR: " + e.getMessage());
        }

        try { Thread.sleep(50); } catch (Exception ignore) {}
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

    @Override
    public void selectBlockers(mage.abilities.Ability source, Game game, java.util.UUID defendingPlayerId) {
        try {
            Socket socket = new Socket("127.0.0.1", 5000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                // 1. Get Incoming Attackers (Only the ones aimed at US!)
                StringBuilder attackersJson = new StringBuilder("[");
                boolean firstAtt = true;
                for (java.util.UUID attackerId : game.getCombat().getAttackers()) {
                    java.util.UUID targetId = game.getCombat().getDefenderId(attackerId);

                    // Verify the creature is actually swinging at our bot
                    if (targetId != null && targetId.equals(this.playerId)) {
                        mage.game.permanent.Permanent attacker = game.getPermanent(attackerId);
                        if (attacker != null) {
                            if (!firstAtt) attackersJson.append(", ");
                            String safeName = attacker.getName().replace("\"", "\\\"");
                            attackersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"power\": %d, \"toughness\": %d}",
                                    attacker.getId().toString(), safeName, attacker.getPower().getValue(), attacker.getToughness().getValue()));
                            firstAtt = false;
                        }
                    }
                }
                attackersJson.append("]");

                // 2. Get Our Available Blockers
                StringBuilder blockersJson = new StringBuilder("[");
                boolean firstBlk = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    // A very basic check: If it's a creature and untapped, it can theoretically block
                    if (perm.isCreature(game) && !perm.isTapped()) {
                        if (!firstBlk) blockersJson.append(", ");
                        String safeName = perm.getName().replace("\"", "\\\"");
                        blockersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"power\": %d, \"toughness\": %d}",
                                perm.getId().toString(), safeName, perm.getPower().getValue(), perm.getToughness().getValue()));
                        firstBlk = false;
                    }
                }
                blockersJson.append("]");

                // 3. Send Combat Payload
                String gameStateJson = String.format("{\"request_type\": \"declare_blockers\", \"incoming_attackers\": %s, \"possible_blockers\": %s}",
                        attackersJson.toString(), blockersJson.toString());
                out.println(gameStateJson);

                // 4. Wait for Python
                String response = in.readLine();
                socket.close();

                // 5. Execute the Blocks!
                if (response != null && response.startsWith("BLOCK:")) {
                    String payload = response.substring(6).trim();
                    if (!payload.isEmpty()) {
                        String[] blocks = payload.split(",");
                        for (String block : blocks) {
                            String[] parts = block.split(":");
                            if (parts.length == 2) {
                                java.util.UUID blockerId = java.util.UUID.fromString(parts[0].trim());
                                java.util.UUID attackerId = java.util.UUID.fromString(parts[1].trim());

                                // Physically push our creature in front of the attacker
                                myPlayer.declareBlocker(this.playerId, blockerId, attackerId, game);
                                System.out.println("DEBUG: Declaring blocker " + blockerId + " against " + attackerId);
                            }
                        }
                    }
                } else if (response != null && response.equals("PASS")) {
                    System.out.println("DEBUG: Python chose to take the damage.");
                }
            } else {
                socket.close();
            }
        } catch (Exception e) {}
    }
}