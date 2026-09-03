package mage.player.ai;

import mage.constants.RangeOfInfluence;
import mage.game.Game;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * A custom XMage computer player that delegates strategic decision making
 * to an external Python neural network via a synchronous TCP socket connection.
 */
public class PythonAIPlayerFrozen extends ComputerPlayer {

    private int port;

    /**
     * Instantiates the player with a default port for standard GUI reflection.
     */
    public PythonAIPlayerFrozen(String name, RangeOfInfluence range, int skill) {
        super(name, range);
        this.port = 8082;
    }

    /**
     * Instantiates the player programmatically with a specific port assignment for multi agent evaluation.
     */
    public PythonAIPlayerFrozen(String name, RangeOfInfluence range, int skill, int port) {
        super(name, range);
        this.port = port;
    }

    /**
     * Creates a deep copy of the player ensuring the specific socket port is retained.
     */
    public PythonAIPlayerFrozen(final PythonAIPlayerFrozen player) {
        super(player);
        this.port = player.port;
    }

    @Override
    public PythonAIPlayerFrozen copy() {
        return new PythonAIPlayerFrozen(this);
    }

    /**
     * Serialises the opening hand and queries the Python server to autonomously accept or mulligan.
     */
    @Override
    public boolean chooseMulligan(Game game) {
        try {
            Socket socket = new Socket("127.0.0.1", this.port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");

                    boolean isLand = card.isLand(game);
                    String denseFeatures = getDenseFeatures(card, game);

                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"is_land\": %b, %s}",
                            card.getId().toString(), safeName, isLand, denseFeatures));
                    firstCard = false;
                }
                handJson.append("]");

                String gameStateJson = String.format("{\"request_type\": \"mulligan\", \"my_hand\": %s}", handJson.toString());
                out.println(gameStateJson);

                String response = in.readLine();
                socket.close();

                if (response != null && response.equals("MULLIGAN")) {
                    System.out.println("DEBUG: Python Frozen on port " + this.port + " decided to MULLIGAN.");
                    return true;
                } else if (response != null && response.equals("KEEP")) {
                    System.out.println("DEBUG: Python Frozen on port " + this.port + " decided to KEEP.");
                    return false;
                }
            } else {
                socket.close();
            }
        } catch (Exception e) {}
        return false;
    }

    /**
     * Handles optional game engine triggers by automatically confirming them.
     */
    @Override
    public boolean chooseUse(mage.constants.Outcome outcome, String message, String secondMessage, String trueText, String falseText, mage.abilities.Ability source, mage.game.Game game) {
        try {
            Socket socket = new Socket("127.0.0.1", this.port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String safeMessage = message != null ? message.replace("\"", "\\\"") : "";
            String json = String.format("{\"request_type\": \"choose_use\", \"message\": \"%s\"}", safeMessage);
            out.println(json);

            String response = in.readLine();
            socket.close();

            if (response != null) {
                if (response.equals("YES")) {
                    System.out.println("DEBUG: Python Frozen (Port " + this.port + ") chose YES for prompt.");
                    return true;
                } else if (response.equals("NO")) {
                    System.out.println("DEBUG: Python Frozen (Port " + this.port + ") chose NO for prompt.");
                    return false;
                }
            }
        } catch (Exception e) {}

        return super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game);
    }

    /**
     * Serialises the active game state into JSON and queries the Python server for main phase actions including playing lands or casting spells.
     */
    @Override
    public boolean priority(Game game) {
        try {
            Socket socket = new Socket("127.0.0.1", this.port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                int turnNumber = game.getTurnNum();
                boolean isMyTurn = game.isActivePlayer(this.playerId);
                boolean isMainPhase = game.canPlaySorcery(this.playerId);

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
                    boolean isLand = card.isLand(game);
                    String denseFeatures = getDenseFeatures(card, game);

                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"can_cast\": %b, \"needs_target\": %b, \"is_land\": %b, %s}",
                            card.getId().toString(), safeName, canCast, needsTarget, isLand, denseFeatures));
                    firstCard = false;
                }
                handJson.append("]");

                StringBuilder fieldJson = new StringBuilder("[");
                boolean firstPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (!firstPerm) fieldJson.append(", ");
                    boolean isLand = perm.isLand(game);
                    String denseFeatures = getDenseFeatures(perm, game);

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

                    fieldJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"tapped\": %b, \"is_land\": %b, \"abilities\": %s, %s}",
                            perm.getId().toString(), perm.getName().replace("\"", "\\\""), perm.isTapped(), isLand, abilitiesJson.toString(), denseFeatures));
                    firstPerm = false;
                }
                fieldJson.append("]");

                StringBuilder oppFieldJson = new StringBuilder("[");
                boolean firstOppPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents()) {
                    if (!perm.getControllerId().equals(this.playerId)) {
                        if (!firstOppPerm) oppFieldJson.append(", ");
                        String denseFeatures = getDenseFeatures(perm, game);

                        oppFieldJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", %s}",
                                perm.getId().toString(), perm.getName().replace("\"", "\\\""), denseFeatures));
                        firstOppPerm = false;
                    }
                }
                oppFieldJson.append("]");

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

                        for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                            for (mage.abilities.Ability ab : perm.getAbilities(game)) {
                                if (ab.getId().equals(abilityId)) {
                                    if (myPlayer.activateAbility((mage.abilities.ActivatedAbility) ab, game)) {
                                        activated = true;
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
            System.err.println("FROZEN PRIORITY SOCKET ERROR (Port " + this.port + "): " + e.getMessage());
        }

        try { Thread.sleep(50); } catch (Exception ignore) {}
        return false;
    }

    /**
     * Compiles available creatures and queries the Python combat network to declare offensive attacks.
     */
    @Override
    public void selectAttackers(Game game, java.util.UUID attackingPlayerId) {
        try {
            Socket socket = new Socket("127.0.0.1", this.port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
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

                StringBuilder attackersJson = new StringBuilder("[");
                boolean firstAttacker = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (perm.isCreature(game) && perm.canAttack(null, game)) {
                        if (!firstAttacker) attackersJson.append(", ");
                        String safeName = perm.getName().replace("\"", "\\\"");
                        String denseFeatures = getDenseFeatures(perm, game);

                        attackersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", %s}",
                                perm.getId().toString(), safeName, denseFeatures));
                        firstAttacker = false;
                    }
                }
                attackersJson.append("]");

                String gameStateJson = String.format("{\"request_type\": \"declare_attackers\", \"opponents\": %s, \"possible_attackers\": %s}",
                        opponentsJson.toString(), attackersJson.toString());
                out.println(gameStateJson);

                String response = in.readLine();
                socket.close();

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
                                System.out.println("DEBUG: Frozen (Port " + this.port + ") Declaring attacker ID " + attackerId);
                            }
                        }
                    }
                } else if (response != null && response.equals("PASS")) {
                    System.out.println("DEBUG: Python Frozen (Port " + this.port + ") chose not to attack.");
                }
            } else {
                socket.close();
            }
        } catch (Exception e) {}
    }

    /**
     * Compiles incoming attackers and available blockers to query the Python combat network for defensive assignments.
     */
    @Override
    public void selectBlockers(mage.abilities.Ability source, Game game, java.util.UUID defendingPlayerId) {
        try {
            Socket socket = new Socket("127.0.0.1", this.port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game.getPlayer(this.playerId);

            if (myPlayer != null) {
                StringBuilder attackersJson = new StringBuilder("[");
                boolean firstAtt = true;
                for (java.util.UUID attackerId : game.getCombat().getAttackers()) {
                    java.util.UUID targetId = game.getCombat().getDefenderId(attackerId);

                    if (targetId != null && targetId.equals(this.playerId)) {
                        mage.game.permanent.Permanent attacker = game.getPermanent(attackerId);
                        if (attacker != null) {
                            if (!firstAtt) attackersJson.append(", ");
                            String safeName = attacker.getName().replace("\"", "\\\"");
                            String denseFeatures = getDenseFeatures(attacker, game);

                            attackersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", %s}",
                                    attacker.getId().toString(), safeName, denseFeatures));
                            firstAtt = false;
                        }
                    }
                }
                attackersJson.append("]");

                StringBuilder blockersJson = new StringBuilder("[");
                boolean firstBlk = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (perm.isCreature(game) && !perm.isTapped()) {
                        if (!firstBlk) blockersJson.append(", ");
                        String safeName = perm.getName().replace("\"", "\\\"");
                        String denseFeatures = getDenseFeatures(perm, game);

                        blockersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", %s}",
                                perm.getId().toString(), safeName, denseFeatures));
                        firstBlk = false;
                    }
                }
                blockersJson.append("]");

                String gameStateJson = String.format("{\"request_type\": \"declare_blockers\", \"incoming_attackers\": %s, \"possible_blockers\": %s}",
                        attackersJson.toString(), blockersJson.toString());
                out.println(gameStateJson);

                String response = in.readLine();
                socket.close();

                if (response != null && response.startsWith("BLOCK:")) {
                    String payload = response.substring(6).trim();
                    if (!payload.isEmpty()) {
                        String[] blocks = payload.split(",");
                        for (String block : blocks) {
                            String[] parts = block.split(":");
                            if (parts.length == 2) {
                                java.util.UUID blockerId = java.util.UUID.fromString(parts[0].trim());
                                java.util.UUID attackerId = java.util.UUID.fromString(parts[1].trim());

                                myPlayer.declareBlocker(this.playerId, blockerId, attackerId, game);
                                System.out.println("DEBUG: Frozen (Port " + this.port + ") Declaring blocker " + blockerId + " against " + attackerId);
                            }
                        }
                    }
                } else if (response != null && response.equals("PASS")) {
                    System.out.println("DEBUG: Python Frozen (Port " + this.port + ") chose to take the damage.");
                }
            } else {
                socket.close();
            }
        } catch (Exception e) {}
    }

    /**
     * Triggers terminal state transmission to the Python server upon match victory.
     */
    @Override
    public void won(mage.game.Game game) {
        super.won(game);
        sendTerminalState("WIN", game);
    }

    /**
     * Triggers terminal state transmission to the Python server upon match defeat.
     */
    @Override
    public void lost(mage.game.Game game) {
        super.lost(game);
        sendTerminalState("LOSS", game);
    }

    /**
     * Packages final game metrics and match results into a JSON payload to finalise the evaluation loop.
     */
    private void sendTerminalState(String result, mage.game.Game game) {
        try {
            java.net.Socket socket = new java.net.Socket("127.0.0.1", this.port);
            java.io.PrintWriter out = new java.io.PrintWriter(socket.getOutputStream(), true);
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));

            mage.players.Player myPlayer = game != null ? game.getPlayer(this.playerId) : null;
            int myLife = myPlayer != null ? myPlayer.getLife() : 0;

            int oppLife = 0;
            if (game != null) {
                for (java.util.UUID oppId : game.getOpponents(this.playerId)) {
                    mage.players.Player opp = game.getPlayer(oppId);
                    if (opp != null) {
                        oppLife = opp.getLife();
                        break;
                    }
                }
            }

            int turns = game != null ? game.getTurnNum() : 0;

            String json = String.format(
                    "{\"request_type\": \"match_over\", \"result\": \"%s\", \"my_life\": %d, \"opp_life\": %d, \"total_turns\": %d}",
                    result, myLife, oppLife, turns
            );

            out.println(json);
            in.readLine();
            socket.close();
        } catch (Exception e) {
            System.err.println("FROZEN TERMINAL SOCKET ERROR (Port " + this.port + "): " + e.getMessage());
        }
    }

    /**
     * Extracts card attributes and keyword abilities into a dense numerical string for tensor encoding.
     */
    private String getDenseFeatures(mage.cards.Card card, mage.game.Game game) {
        int cmc = card.getManaValue();
        int power = card.isCreature(game) ? card.getPower().getValue() : 0;
        int toughness = card.isCreature(game) ? card.getToughness().getValue() : 0;

        java.util.List<String> rules = card.getRules(game);
        String rulesText = (rules != null) ? String.join(" ", rules).toLowerCase() : "";

        int isFlying = rulesText.contains("flying") ? 1 : 0;
        int isTrample = rulesText.contains("trample") ? 1 : 0;
        int isDeathtouch = rulesText.contains("deathtouch") ? 1 : 0;
        int isHaste = rulesText.contains("haste") ? 1 : 0;
        int isLifelink = rulesText.contains("lifelink") ? 1 : 0;
        int isReach = rulesText.contains("reach") ? 1 : 0;
        int isFirstStrike = rulesText.contains("first strike") ? 1 : 0;
        int isDoubleStrike = rulesText.contains("double strike") ? 1 : 0;

        int destroysCreature = (rulesText.contains("destroy target creature") || rulesText.contains("destroy target nonblack creature")) ? 1 : 0;
        int drawsCards = (rulesText.contains("draw a card") || rulesText.contains("draw two cards")) ? 1 : 0;
        int forcesDiscard = rulesText.contains("discards a card") ? 1 : 0;
        int countersSpell = rulesText.contains("counter target spell") ? 1 : 0;
        int dealsDamage = (rulesText.contains("deals damage to any target") || rulesText.contains("deals 3 damage")) ? 1 : 0;

        return String.format(
                "\"cmc\": %d, \"power\": %d, \"toughness\": %d, \"is_flying\": %d, \"is_trample\": %d, \"is_deathtouch\": %d, \"is_haste\": %d, \"is_lifelink\": %d, \"is_reach\": %d, \"is_first_strike\": %d, \"is_double_strike\": %d, \"destroys_creature\": %d, \"draws_cards\": %d, \"forces_discard\": %d, \"counters_spell\": %d, \"deals_direct_damage\": %d",
                cmc, power, toughness, isFlying, isTrample, isDeathtouch, isHaste, isLifelink, isReach, isFirstStrike, isDoubleStrike, destroysCreature, drawsCards, forcesDiscard, countersSpell, dealsDamage
        );
    }

    /**
     * Handles specific selection prompts including the London mulligan bottoming logic via the Python server.
     */
    @Override
    public boolean choose(mage.constants.Outcome outcome, mage.target.Target target, mage.abilities.Ability source, mage.game.Game game) {
        String message = target.getMessage(game);

        boolean isBottomChoice = target.getZone() == mage.constants.Zone.HAND
                && message != null
                && message.toLowerCase().contains("bottom");

        if (isBottomChoice) {
            try {
                Socket socket = new Socket("127.0.0.1", this.port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                mage.players.Player myPlayer = game.getPlayer(this.playerId);

                int numToBottom = target.getMaxNumberOfTargets();
                if (numToBottom == 0) numToBottom = target.getMinNumberOfTargets();

                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");
                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\"}", card.getId().toString(), safeName));
                    firstCard = false;
                }
                handJson.append("]");

                String gameStateJson = String.format("{\"request_type\": \"mulligan_bottom\", \"amount_to_bottom\": %d, \"my_hand\": %s, \"my_battlefield\": [], \"opp_battlefield\": [], \"stack\": []}",
                        numToBottom, handJson.toString());
                out.println(gameStateJson);

                String response = in.readLine();
                socket.close();

                if (response != null && response.startsWith("BOTTOM:")) {
                    String payload = response.substring(7).trim();
                    if (!payload.isEmpty()) {
                        String[] cardIds = payload.split(",");
                        for (String idStr : cardIds) {
                            java.util.UUID cardId = java.util.UUID.fromString(idStr.trim());
                            target.addTarget(cardId, source, game);
                        }
                        return true;
                    }
                }
            } catch (Exception e) {
                System.err.println("FROZEN MULLIGAN SOCKET ERROR (Port " + this.port + "): " + e.getMessage());
            }
        }

        return super.choose(outcome, target, source, game);
    }
}