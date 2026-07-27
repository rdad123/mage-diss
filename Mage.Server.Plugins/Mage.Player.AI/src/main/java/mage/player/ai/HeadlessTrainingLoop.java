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

public class HeadlessTrainingLoop {


    public static void main(String[] args) throws MageException {
        System.out.println("Starting 4-Player Headless RL Training Loop...");

        // NEW: Turn on the XMage internal error logger!
        java.util.logging.Logger.getLogger("com.j256.ormlite").setLevel(java.util.logging.Level.WARNING);
        // IfXMage uses Log4j instead (which it looks like it might based on the format), try this:
        org.apache.log4j.Logger.getLogger("com.j256.ormlite").setLevel(org.apache.log4j.Level.WARN);
        org.apache.log4j.BasicConfigurator.configure();
        // =======================================================
        // ABSOLUTE PATH TO THE SETS DATA
        // =======================================================
        // This MUST point to the folder containing the 'Mage.Sets'
        // compiled assets, usually found in your repo under 'Mage.Sets/target/classes'
        String setsPath = "C:/Users/robbi/OneDrive/Documents/GitHub/mage-diss/Mage.Sets/target/classes";

        // Tell XMage where the engine is
        System.setProperty("mage.directory", setsPath);

        System.out.println("Booting up XMage Card Database...");
        CardScanner.scan();
        System.out.println("Database online!");

        System.setProperty("mage.directory", setsPath);

        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        mage.client.MageFrame frame = new mage.client.MageFrame();
        frame.setVisible(true); // Pops up the main XMage window

        // 1. Use the EXACT absolute path to your AI module
        String path = "C:/Users/robbi/OneDrive/Documents/GitHub/mage-diss/Mage.Server.Plugins/Mage.Player.AI/src/main/java/mage/player/ai/";
        int totalGames = 10000;

        for (int i = 1; i <= totalGames; i++) {
            System.out.println("\nStarting Match " + i);

            try {
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

                // 2. Set these to FALSE. If the file is missing, it will throw an explicit error!
                DeckCardLists listFrogs = importer.importDeck(path + "frogs.dck", false);
                DeckCardLists listCloud = importer.importDeck(path + "cloud.dck", false);
                DeckCardLists listCats = importer.importDeck(path + "cats.dck", false);
                DeckCardLists listOtters = importer.importDeck(path + "otters.dck", false);

                // =======================================================
                // DIAGNOSTIC CHECK 1: Did the text file actually read?
                // =======================================================
                System.out.println("DEBUG: Cards parsed from Frogs text file = " + listFrogs.getCards().size());

                Deck deckLearner = Deck.load(listFrogs);
                Deck deckA = Deck.load(listCloud);
                Deck deckB = Deck.load(listCats);
                Deck deckC = Deck.load(listOtters);

                // =======================================================
                // DIAGNOSTIC CHECK 2: Did the database match the cards?
                // =======================================================
                System.out.println("DEBUG: Final Physical Learner Deck Size = " + deckLearner.getCards().size());

                if (deckLearner.getCards().size() == 0) {
                    System.err.println("CRITICAL: Deck is empty! The database failed to find the cards.");
                    break;
                }

                // ... deck loading ...
                game.addPlayer(nfspBot, deckLearner);
                game.addPlayer(baseline1, deckA);
                game.addPlayer(baseline2, deckB);
                game.addPlayer(baseline3, deckC);

// NEW: Spawn the visualizer in a background thread
                final java.util.UUID learnerId = nfspBot.getId();
                final Game finalGame = game;
                new Thread(() -> {
                    try {
                        Thread.sleep(500);

                        // Pass the new final learnerId here
                        GameView gameView = new GameView(finalGame.getState(), finalGame, learnerId, null);

                        GamePanel gamePanel = new GamePanel();

                        // 2. Pass 0 for the player index/seat number
                        gamePanel.init(0, gameView, true);

                        javax.swing.JFrame gameFrame = new javax.swing.JFrame("Watch Bot Match");
                        gameFrame.add(gamePanel);
                        gameFrame.setSize(1200, 800);
                        gameFrame.setVisible(true);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

// Start the blocking game loop
                game.start(nfspBot.getId());

                System.out.println("DEBUG: Game ended on Turn " + game.getTurnNum());


                System.out.println("DEBUG: Game ended on Turn " + game.getTurnNum());
                System.out.println("Match " + i + " Concluded.");

                // =======================================================
                // MANUAL GARBAGE COLLECTION & SOCKET CLEARING
                // =======================================================
                // 1. Force the game engine to clear its state
                game.cleanUp();

                // 2. Erase the player references so their sockets fully close
                nfspBot = null;
                baseline1 = null;
                baseline2 = null;
                baseline3 = null;
                game = null;

                // 3. Force Java to dump the stale objects from RAM
                System.gc();

                // 4. Give the background threads a half-second to finish closing the sockets
                // before Python gets slammed with the next match's connection requests.
                Thread.sleep(500);

            } catch (Throwable t) {
                System.err.println("CRITICAL FAILURE DURING MATCH " + i);
                t.printStackTrace();
                break;
            }
        }
    }
}