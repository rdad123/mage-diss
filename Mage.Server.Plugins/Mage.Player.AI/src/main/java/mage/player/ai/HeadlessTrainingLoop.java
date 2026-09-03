package mage.player.ai;

import mage.MageException;
import mage.game.Game;
import mage.game.FreeForAll;
import mage.game.GameOptions;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.mulligan.MulliganType;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DckDeckImporter;
import mage.cards.repository.CardScanner;

import mage.view.GameView;
import mage.client.game.GamePanel;

/**
 * This class represents the initial abandoned attempt to automate match generation entirely in memory.
 * It bypassed the standard XMage server lobby system to execute a continuous training loop.
 * This approach ultimately failed because the Java engine tightly couples internal game logic
 * with graphical observer classes causing fatal exceptions when physical client wrappers are missing.
 */
public class HeadlessTrainingLoop {

    public static void main(String[] args) throws MageException {
        System.out.println("Starting Four Player Headless RL Training Loop...");

        org.apache.log4j.Logger.getLogger("com.j256.ormlite").setLevel(org.apache.log4j.Level.WARN);
        org.apache.log4j.BasicConfigurator.configure();

        String setsPath = "C:/Users/robbi/OneDrive/Documents/GitHub/mage-diss/Mage.Sets/target/classes";
        System.setProperty("mage.directory", setsPath);

        System.out.println("Booting up XMage Card Database...");
        CardScanner.scan();
        System.out.println("Database online!");

        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // [FAILURE POINT] Missing Client Listeners
        // Instantiating the MageFrame directly in memory without progressing through the standard
        // network login sequence leaves critical background listener objects uninitialised.
        mage.client.MageFrame frame = new mage.client.MageFrame();
        frame.setVisible(true);

        String path = "C:/Users/robbi/OneDrive/Documents/GitHub/mage-diss/Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/";
        int totalGames = 10000;

        for (int i = 1; i <= totalGames; i++) {
            System.out.println("\nStarting Match " + i);

            try {
                // [FAILURE POINT] Bypassing the Server Lobby
                // Constructing the FreeForAll object directly bypasses the server table manager.
                // The engine fundamentally requires a registered human host to authorise a lobby
                // and generate the necessary wrapper objects to track match statistics.
                Game game = new FreeForAll(MultiplayerAttackOption.MULTIPLE, RangeOfInfluence.ALL, MulliganType.GAME_DEFAULT.getMulligan(0), 40, 7);

                GameOptions options = new GameOptions();
                options.testMode = true;
                options.skipInitShuffling = false;
                game.setGameOptions(options);

                PythonAIPlayerNFSP nfspBot = new PythonAIPlayerNFSP("NFSP_Learner", RangeOfInfluence.ALL, 5);
                PythonAIPlayer baseline1 = new PythonAIPlayer("Baseline_Aggro_A", RangeOfInfluence.ALL, 5);
                PythonAIPlayer baseline2 = new PythonAIPlayer("Baseline_Aggro_B", RangeOfInfluence.ALL, 5);
                PythonAIPlayer baseline3 = new PythonAIPlayer("Baseline_Aggro_C", RangeOfInfluence.ALL, 5);

                DckDeckImporter importer = new DckDeckImporter();

                DeckCardLists listFrogs = importer.importDeck(path + "frogs.dck", false);
                DeckCardLists listCloud = importer.importDeck(path + "cloud.dck", false);
                DeckCardLists listCats = importer.importDeck(path + "cats.dck", false);
                DeckCardLists listOtters = importer.importDeck(path + "otters.dck", false);

                Deck deckLearner = Deck.load(listFrogs);
                Deck deckA = Deck.load(listCloud);
                Deck deckB = Deck.load(listCats);
                Deck deckC = Deck.load(listOtters);

                if (deckLearner.getCards().size() == 0) {
                    System.err.println("CRITICAL: Deck is empty! The database failed to find the cards.");
                    break;
                }

                game.addPlayer(nfspBot, deckLearner);
                game.addPlayer(baseline1, deckA);
                game.addPlayer(baseline2, deckB);
                game.addPlayer(baseline3, deckC);

                final java.util.UUID learnerId = nfspBot.getId();
                final Game finalGame = game;

                new Thread(() -> {
                    try {
                        Thread.sleep(500);

                        // [FAILURE POINT] The Fatal Null Pointer Exception
                        // Attempting to generate a graphical GameView without a fully initialised server match object
                        // triggers a crash by turn three. The GameView attempts to query PlayerView to retrieve win counts.
                        // Because the simulation bypassed the lobby the player has no MatchPlayer wrapper returning null
                        // and instantly terminating the thread.
                        GameView gameView = new GameView(finalGame.getState(), finalGame, learnerId, null);
                        GamePanel gamePanel = new GamePanel();

                        gamePanel.init(0, gameView, true);

                        javax.swing.JFrame gameFrame = new javax.swing.JFrame("Watch Bot Match");
                        gameFrame.add(gamePanel);
                        gameFrame.setSize(1200, 800);
                        gameFrame.setVisible(true);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

                game.start(nfspBot.getId());

                System.out.println("DEBUG: Game ended on Turn " + game.getTurnNum());
                System.out.println("Match " + i + " Concluded.");

                game.cleanUp();

                nfspBot = null;
                baseline1 = null;
                baseline2 = null;
                baseline3 = null;
                game = null;

                System.gc();
                Thread.sleep(500);

            } catch (Throwable t) {
                System.err.println("CRITICAL FAILURE DURING MATCH " + i);
                t.printStackTrace();
                break;
            }
        }
    }
}