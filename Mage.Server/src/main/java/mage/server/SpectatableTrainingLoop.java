package mage.server;

import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DckDeckImporter;
import mage.game.match.MatchOptions;
import mage.constants.MatchTimeLimit;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.players.PlayerType;

import java.util.Collection;
import java.util.UUID;

/**
 * This class represents the second abandoned attempt to automate match generation.
 * It attempted to boot a local server instance wait for a human client connection
 * and programmatically inject automated tables into the lobby.
 *
 * [FAILURE POINT] The Spectatable Server Failure
 * This approach failed because programmatic tables never successfully propagated to the
 * graphical lobby interface making them invisible to the server. The engine enforces
 * strict anti flood safeguards and fundamentally requires an active client to physically
 * click and join each match to sustain the game thread. Requiring manual graphical
 * intervention for every match defeated the purpose of building an automated pipeline.
 */
public class SpectatableTrainingLoop {

    /**
     * Executes the automated server boot and attempts to programmatically force
     * a five player lobby containing the neural network baseline bots and a human anchor.
     */
    public static void main(String[] args) {
        System.out.println("Starting XMage Server for RL Training...");
        System.setProperty("xmage.config.path", "C:/Users/robbi/OneDrive/Documents/GitHub/mage-diss/Mage.Server/config/config.xml");
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        System.setProperty("jboss.bind.address", "127.0.0.1");
        System.setProperty("java.net.preferIPv4Stack", "true");

        new Thread(() -> {
            try {
                mage.server.Main.main(new String[]{});
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        System.out.println("Waiting for server to initialize...");

        mage.server.managers.ManagerFactory factory = null;
        while (factory == null) {
            try {
                factory = mage.server.Main.getManagerFactory();
                if (factory == null) {
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
            }
        }

        System.out.println("\n=======================================================");
        System.out.println(" SERVER ONLINE. WAITING FOR CLIENT CONNECTION...       ");
        System.out.println("=======================================================\n");

        UUID hostId = null;
        while (hostId == null) {
            try {
                Collection<mage.server.User> users = factory.userManager().getUsers();
                if (users != null && !users.isEmpty()) {
                    hostId = users.iterator().next().getId();
                    break;
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                try { Thread.sleep(2000); } catch (Exception ignore) {}
            }
        }

        System.out.println("\nSUCCESS: Active Client detected! Hijacking session: " + hostId);
        System.out.println("Starting automated matches...\n");

        String path = "C:/Users/robbi/OneDrive/Documents/GitHub/mage-diss/Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/";
        DckDeckImporter importer = new DckDeckImporter();
        DeckCardLists listFrogs = importer.importDeck(path + "frogs.dck", false);
        DeckCardLists listCloud = importer.importDeck(path + "cloud.dck", false);
        DeckCardLists listCats = importer.importDeck(path + "cats.dck", false);
        DeckCardLists listOtters = importer.importDeck(path + "otters.dck", false);

        System.out.println("Frogs Deck: " + (listFrogs != null ? listFrogs.getCards().size() + " cards" : "NULL - FILE NOT FOUND OR BROKEN"));
        System.out.println("Cloud Deck: " + (listCloud != null ? listCloud.getCards().size() + " cards" : "NULL - FILE NOT FOUND OR BROKEN"));
        System.out.println("Cats Deck: " + (listCats != null ? listCats.getCards().size() + " cards" : "NULL - FILE NOT FOUND OR BROKEN"));
        System.out.println("Otters Deck: " + (listOtters != null ? listOtters.getCards().size() + " cards" : "NULL - FILE NOT FOUND OR BROKEN"));

        for (int i = 1; i <= 10000; i++) {
            System.out.println("Initiating Match " + i + " on the Server...");

            try {
                UUID roomId = factory.gamesRoomManager().getMainRoomId();

                MatchOptions options = new MatchOptions("RL Training " + i, "Free For All", true);
                options.getPlayerTypes().clear();

                options.getPlayerTypes().add(PlayerType.HUMAN);
                options.getPlayerTypes().add(PlayerType.COMPUTER_NFSP);
                options.getPlayerTypes().add(PlayerType.COMPUTER_PYTHON_AI);
                options.getPlayerTypes().add(PlayerType.COMPUTER_PYTHON_AI);
                options.getPlayerTypes().add(PlayerType.COMPUTER_PYTHON_AI);

                options.setDeckType("Constructed - Freeform");
                options.setAttackOption(MultiplayerAttackOption.MULTIPLE);
                options.setRange(RangeOfInfluence.ALL);
                options.setMatchTimeLimit(MatchTimeLimit.NONE);

                mage.game.Table table = factory.tableManager().createTable(roomId, hostId, options);
                UUID tableId = table.getId();

                boolean jHuman = factory.tableManager().joinTable(hostId, tableId, "Human_Anchor", PlayerType.HUMAN, 5, listFrogs, "");
                boolean j1 = factory.tableManager().joinTable(hostId, tableId, "NFSP_Learner", PlayerType.COMPUTER_NFSP, 5, listFrogs, "");
                boolean j2 = factory.tableManager().joinTable(hostId, tableId, "Baseline_A", PlayerType.COMPUTER_PYTHON_AI, 5, listCloud, "");
                boolean j3 = factory.tableManager().joinTable(hostId, tableId, "Baseline_B", PlayerType.COMPUTER_PYTHON_AI, 5, listCats, "");
                boolean j4 = factory.tableManager().joinTable(hostId, tableId, "Baseline_C", PlayerType.COMPUTER_PYTHON_AI, 5, listOtters, "");

                System.out.println("Seated? You:" + jHuman + " | NFSP:" + j1 + " | Bots:" + j2 + "," + j3 + "," + j4);

                factory.tableManager().startMatch(hostId, roomId, tableId);
                System.out.println("Match started! Your Client should snap to the game board immediately.");

                boolean matchFound = false;
                while (true) {
                    java.util.Optional<mage.game.match.Match> matchOpt = factory.tableManager().getMatch(tableId);

                    if (matchOpt.isPresent()) {
                        matchFound = true;
                        if (matchOpt.get().hasEnded()) {
                            break;
                        }
                    } else {
                        if (matchFound) {
                            break;
                        }
                    }
                    Thread.sleep(2000);
                }

                System.out.println("Match " + i + " Concluded.");
                System.gc();
                Thread.sleep(1000);

            } catch (Exception e) {
                System.err.println("CRITICAL FAILURE DURING MATCH " + i);
                e.printStackTrace();
                break;
            }
        }
    }
}