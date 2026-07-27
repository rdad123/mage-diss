package mage.player.ai;

import mage.constants.RangeOfInfluence;
import mage.game.Game;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class PythonAIPlayerNFSP extends ComputerPlayer {

    public PythonAIPlayerNFSP(String name, RangeOfInfluence range, int skill) {

        super(name, range);
    }

    public PythonAIPlayerNFSP(final PythonAIPlayerNFSP player) {

        super(player);
    }

    @Override
    public PythonAIPlayerNFSP copy() {
        return new PythonAIPlayerNFSP(this);

    }

    // ========================================================
    // 1. MULLIGAN PHASE (Renamed to match parent class exactly!)
    // ========================================================
    @Override
    public boolean chooseMulligan(Game game) {
        try {
            Socket socket = new Socket("127.0.0.1", 5001);
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
            Socket socket = new Socket("127.0.0.1", 5001);
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
            Socket socket = new Socket("127.0.0.1", 5001);
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
                    boolean isLand = card.isLand(game);
                    String denseFeatures = getDenseFeatures(card, game); // CALL THE HELPER!

                    // We removed 'cmc' from this format string because the helper method handles it now!
                    // Notice the %s at the end to drop in the dense features.
                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"can_cast\": %b, \"needs_target\": %b, \"is_land\": %b, %s}",
                            card.getId().toString(), safeName, canCast, needsTarget, isLand, denseFeatures));
                    firstCard = false;
                }
                handJson.append("]");

                // My Battlefield
                StringBuilder fieldJson = new StringBuilder("[");
                boolean firstPerm = true;
                for (mage.game.permanent.Permanent perm : game.getBattlefield().getAllActivePermanents(this.playerId)) {
                    if (!firstPerm) fieldJson.append(", ");
                    boolean isLand = perm.isLand(game);
                    String denseFeatures = getDenseFeatures(perm, game);

                    StringBuilder abilitiesJson = new StringBuilder("[");
                    boolean firstAb = true;
                    for (mage.abilities.Ability ability : perm.getAbilities(game)) {
                        // 1. Safe cast check
                        if (ability instanceof mage.abilities.ActivatedAbility) {

                            // 2. THE MASTER FILTER: Only accept non-mana activated abilities
                            if (ability.getAbilityType() == mage.constants.AbilityType.ACTIVATED_NONMANA) {

                                // 3. Ensure it's meant to be used from the battlefield
                                if (ability.getZone() == mage.constants.Zone.BATTLEFIELD) {

                                    // 4. Check if we actually have the mana/targets to activate it right now
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

                // Opponent Battlefield
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

                // THE STACK (What are we responding to?)
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
            System.err.println("PRIORITY SOCKET ERROR: " + e.getMessage());
        }

        try { Thread.sleep(50); } catch (Exception ignore) {}
        return false;
    }
    @Override
    public void selectAttackers(Game game, java.util.UUID attackingPlayerId) {
        try {
            Socket socket = new Socket("127.0.0.1", 5001);
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
                        String denseFeatures = getDenseFeatures(perm, game);

                        attackersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", %s}",
                                perm.getId().toString(), safeName, denseFeatures));
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
            Socket socket = new Socket("127.0.0.1", 5001);
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
                            String denseFeatures = getDenseFeatures(attacker, game);

                            attackersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", %s}",
                                    attacker.getId().toString(), safeName, denseFeatures));
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
                        String denseFeatures = getDenseFeatures(perm, game);

                        blockersJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", %s}",
                                perm.getId().toString(), safeName, denseFeatures));
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

    // ==========================================================
    // REINFORCEMENT LEARNING REWARD HOOKS
    // ==========================================================
    @Override
    public void won(mage.game.Game game) {
        super.won(game);
        // PASS THE GAME PARAMETER
        sendTerminalState("WIN", game);
    }

    @Override
    public void lost(mage.game.Game game) {
        super.lost(game);
        // PASS THE GAME PARAMETER
        sendTerminalState("LOSS", game);
    }

    // UPDATE THE SIGNATURE TO EXPECT THE GAME OBJECT
    private void sendTerminalState(String result, mage.game.Game game) {
        try {
            java.net.Socket socket = new java.net.Socket("127.0.0.1", 5001);
            java.io.PrintWriter out = new java.io.PrintWriter(socket.getOutputStream(), true);
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));

            // 1. Get our life total safely
            mage.players.Player myPlayer = game != null ? game.getPlayer(this.playerId) : null;
            int myLife = myPlayer != null ? myPlayer.getLife() : 0;

            // 2. Get opponent's life total (grabs the first opponent it finds)
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

            // 3. Get total turns
            int turns = game != null ? game.getTurnNum() : 0;

            // 4. Build the expanded JSON payload
            String json = String.format(
                    "{\"request_type\": \"match_over\", \"result\": \"%s\", \"my_life\": %d, \"opp_life\": %d, \"total_turns\": %d}",
                    result, myLife, oppLife, turns
            );

            out.println(json);

            in.readLine();
            socket.close();
        } catch (Exception e) {
            System.err.println("TERMINAL SOCKET ERROR: " + e.getMessage());
        }
    }

    // ==========================================================
    // DENSE FEATURE EXTRACTOR (NLP & STATS)
    // ==========================================================
    private String getDenseFeatures(mage.cards.Card card, mage.game.Game game) {
        int cmc = card.getManaValue();
        int power = card.isCreature(game) ? card.getPower().getValue() : 0;
        int toughness = card.isCreature(game) ? card.getToughness().getValue() : 0;

        // Extract Oracle text SAFELY (Basic lands and tokens sometimes return null rules!)
        java.util.List<String> rules = card.getRules(game);
        String rulesText = (rules != null) ? String.join(" ", rules).toLowerCase() : "";

        // Combat Keywords
        int isFlying = rulesText.contains("flying") ? 1 : 0;
        int isTrample = rulesText.contains("trample") ? 1 : 0;
        int isDeathtouch = rulesText.contains("deathtouch") ? 1 : 0;
        int isHaste = rulesText.contains("haste") ? 1 : 0;
        int isLifelink = rulesText.contains("lifelink") ? 1 : 0;
        int isReach = rulesText.contains("reach") ? 1 : 0;
        int isFirstStrike = rulesText.contains("first strike") ? 1 : 0;
        int isDoubleStrike = rulesText.contains("double strike") ? 1 : 0;

        // Spell Effects
        int destroysCreature = (rulesText.contains("destroy target creature") || rulesText.contains("destroy target nonblack creature")) ? 1 : 0;
        int drawsCards = (rulesText.contains("draw a card") || rulesText.contains("draw two cards")) ? 1 : 0;
        int forcesDiscard = rulesText.contains("discards a card") ? 1 : 0;
        int countersSpell = rulesText.contains("counter target spell") ? 1 : 0;
        int dealsDamage = (rulesText.contains("deals damage to any target") || rulesText.contains("deals 3 damage")) ? 1 : 0;

        // Return as a comma-separated list of JSON keys
        return String.format(
                "\"cmc\": %d, \"power\": %d, \"toughness\": %d, \"is_flying\": %d, \"is_trample\": %d, \"is_deathtouch\": %d, \"is_haste\": %d, \"is_lifelink\": %d, \"is_reach\": %d, \"is_first_strike\": %d, \"is_double_strike\": %d, \"destroys_creature\": %d, \"draws_cards\": %d, \"forces_discard\": %d, \"counters_spell\": %d, \"deals_direct_damage\": %d",
                cmc, power, toughness, isFlying, isTrample, isDeathtouch, isHaste, isLifelink, isReach, isFirstStrike, isDoubleStrike, destroysCreature, drawsCards, forcesDiscard, countersSpell, dealsDamage
        );
    }

    // ========================================================
    // 4. LONDON MULLIGAN (Card Selection Hook)
    // ========================================================
    @Override
    public boolean choose(mage.constants.Outcome outcome, mage.target.Target target, mage.abilities.Ability source, mage.game.Game game) {

        String message = target.getMessage(game);

        // THE FIX: Check if the game is asking us to put a card from our hand on the bottom of the library
        boolean isBottomChoice = target.getZone() == mage.constants.Zone.HAND
                && message != null
                && message.toLowerCase().contains("bottom");

        if (isBottomChoice) {
            try {
                Socket socket = new Socket("127.0.0.1", 5001); // 5001 for NFSP Network
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                mage.players.Player myPlayer = game.getPlayer(this.playerId);

                int numToBottom = target.getMaxNumberOfTargets();
                if (numToBottom == 0) numToBottom = target.getMinNumberOfTargets();

                // Build Hand JSON
                StringBuilder handJson = new StringBuilder("[");
                boolean firstCard = true;
                for (mage.cards.Card card : myPlayer.getHand().getCards(game)) {
                    if (!firstCard) handJson.append(", ");
                    String safeName = card.getName().replace("\"", "\\\"");
                    handJson.append(String.format("{\"id\": \"%s\", \"name\": \"%s\"}", card.getId().toString(), safeName));
                    firstCard = false;
                }
                handJson.append("]");

                // Send a full state so the Python MTGStateEncoder doesn't crash on missing keys
                String gameStateJson = String.format("{\"request_type\": \"mulligan_bottom\", \"amount_to_bottom\": %d, \"my_hand\": %s, \"my_battlefield\": [], \"opp_battlefield\": [], \"stack\": []}",
                        numToBottom, handJson.toString());
                out.println(gameStateJson);

                String response = in.readLine();
                socket.close();

                // Apply the Network's choices
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
                System.err.println("MULLIGAN SOCKET ERROR: " + e.getMessage());
            }
        }

        // If it's a different type of choice or the socket fails, fall back to the default AI
        return super.choose(outcome, target, source, game);
    }
    
}